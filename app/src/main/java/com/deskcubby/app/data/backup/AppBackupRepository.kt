package com.deskcubby.app.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.BrowserRecordDao
import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.data.local.DateRecordDao
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.FlashThoughtDao
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.SavedPoemDao
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.local.ThoughtCategoryDao
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppBackupContent(
    val settings: AppSettings,
    val thoughts: List<FlashThoughtEntity>,
    val categories: List<ThoughtCategoryEntity>,
    val favorites: List<BrowserRecordEntity>,
    val dateRecords: List<DateRecordEntity>,
    val poems: List<SavedPoemEntity>,
)

class AppBackupException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class AutomaticBackupConfigurationChangedException : IOException(
    "自动保存已取消：保存文件夹设置已更改。",
)

@Singleton
class AppBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val thoughtDao: FlashThoughtDao,
    private val categoryDao: ThoughtCategoryDao,
    private val browserDao: BrowserRecordDao,
    private val dateRecordDao: DateRecordDao,
    private val savedPoemDao: SavedPoemDao,
    private val settingsRepository: SettingsRepository,
) {
    private val operationMutex = Mutex()

    fun observeContent(): Flow<AppBackupContent> {
        val databaseContent = combine(
            thoughtDao.observeAllForBackup(),
            categoryDao.observeAllForBackup(),
            browserDao.observeFavorites(),
            dateRecordDao.observeAllForBackup(),
            savedPoemDao.observeAllForBackup(),
        ) { thoughts, categories, favorites, dateRecords, poems ->
            BackupDatabaseContent(
                thoughts = thoughts,
                categories = categories,
                favorites = favorites,
                dateRecords = dateRecords,
                poems = poems,
            )
        }
        return combine(settingsRepository.settings, databaseContent) { settings, content ->
            AppBackupContent(
                settings = settings,
                thoughts = content.thoughts,
                categories = content.categories,
                favorites = content.favorites,
                dateRecords = content.dateRecords,
                poems = content.poems,
            )
        }.flowOn(Dispatchers.IO)
    }

    suspend fun currentContent(): AppBackupContent = operationMutex.withLock {
        withContext(Dispatchers.IO) { loadCurrentContent() }
    }

    suspend fun exportTo(uri: Uri): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val content = loadCurrentContent()
            val backup = content.toBackup()
            val json = BackupJsonCodec.encode(backup)
            writeAndVerifyBackup(uri, json, "导出")
            backup.toSummary()
        }
    }

    suspend fun importFrom(uri: Uri): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val raw = readDocument(uri, "导入")
            val backup = decodeDocument(raw, "导入")
            restoreBackup(backup)
        }
    }

    suspend fun inspectAutomatic(treeUri: String): BackupSummary? =
        inspectAutomatic(parseTreeUri(treeUri, "读取自动备份"))

    suspend fun inspectAutomatic(treeUri: Uri): BackupSummary? = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val root = resolveTree(treeUri, "读取自动备份", requireWrite = true)
            readAutomaticBackup(root)?.toSummary()
        }
    }

    suspend fun importAutomatic(treeUri: String): BackupSummary =
        importAutomatic(parseTreeUri(treeUri, "导入自动备份"))

    suspend fun importAutomatic(treeUri: Uri): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val root = resolveTree(treeUri, "导入自动备份", requireWrite = false)
            val backup = readAutomaticBackup(root)
                ?: throw AppBackupException("导入自动备份失败：所选文件夹中没有 $BACKUP_FILE_NAME。")
            restoreBackup(backup)
        }
    }

    suspend fun writeAutomatic(
        treeUri: String,
        content: AppBackupContent,
    ): BackupSummary = writeAutomatic(parseTreeUri(treeUri, "自动保存"), content)

    suspend fun writeAutomatic(
        treeUri: Uri,
        content: AppBackupContent,
    ): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val root = resolveTree(treeUri, "自动保存", requireWrite = true)
            writeAutomaticToRoot(root, content)
        }
    }

    suspend fun writeCurrentAutomatic(treeUri: String): BackupSummary = writeCurrentAutomatic(
        treeUri = parseTreeUri(treeUri, "自动保存"),
        expectedTreeUri = treeUri,
    )

    suspend fun writeCurrentAutomatic(treeUri: Uri): BackupSummary =
        writeCurrentAutomatic(treeUri, expectedTreeUri = treeUri.toString())

    private suspend fun writeCurrentAutomatic(
        treeUri: Uri,
        expectedTreeUri: String,
    ): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val content = loadCurrentContent()
            if (content.settings.backupTreeUri != expectedTreeUri) {
                throw AutomaticBackupConfigurationChangedException()
            }
            val root = resolveTree(treeUri, "自动保存", requireWrite = true)
            writeAutomaticToRoot(root, content)
        }
    }

    private suspend fun restoreBackup(backup: AppBackup): BackupSummary {
        val previousSettings = try {
            settingsRepository.settings.first()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AppBackupException("导入失败：无法读取当前设置，原有内容未改变。", error)
        }

        // Apply the reversible DataStore edit first and make the Room transaction the final
        // commit. If the process dies between stores, existing user-created database content
        // is never destructively replaced before settings have been durably written.
        val restoredSettings = mergeLegacyBackupAiApiKeys(
            imported = backup.settings,
            current = previousSettings,
            formatVersion = backup.formatVersion,
        )
        try {
            settingsRepository.restoreFromBackup(restoredSettings)
        } catch (error: CancellationException) {
            rollbackSettingsAfterImportFailure(previousSettings, error)
            throw error
        } catch (error: Exception) {
            rollbackSettingsAfterImportFailure(previousSettings, error)
            val message = if (error.suppressed.isNotEmpty()) {
                "导入失败：无法恢复设置，原设置回滚也未完全成功。"
            } else {
                "导入失败：无法恢复设置，原有内容未改变。"
            }
            throw AppBackupException(message, error)
        }

        try {
            database.withTransaction {
                // Thoughts must be removed before their referenced categories, then restored
                // only after every category exists, so foreign-key checks stay valid.
                thoughtDao.clearAllForBackup()
                categoryDao.clearAllForBackup()
                if (backup.categories.isNotEmpty()) {
                    categoryDao.insertAllForBackup(backup.categories)
                }
                if (backup.thoughts.isNotEmpty()) {
                    thoughtDao.insertAllForBackup(backup.thoughts)
                }
                browserDao.replaceFavoritesForBackup(backup.favorites)
                dateRecordDao.replaceAllForBackup(backup.dateRecords)
                savedPoemDao.replaceAllForBackup(backup.poems)
            }
        } catch (error: CancellationException) {
            rollbackSettingsAfterImportFailure(previousSettings, error)
            throw error
        } catch (error: Exception) {
            rollbackSettingsAfterImportFailure(previousSettings, error)
            val message = if (error.suppressed.isNotEmpty()) {
                "导入失败：无法写入数据库，原设置回滚也未完全成功。"
            } else {
                "导入失败：无法写入数据库，原设置已恢复且原有内容未改变。"
            }
            throw AppBackupException(message, error)
        }

        return backup.toSummary()
    }

    private fun writeAutomaticToRoot(
        root: DocumentFile,
        content: AppBackupContent,
    ): BackupSummary {
        val backup = content.toBackup()
        val json = BackupJsonCodec.encode(backup)

        val main = findAutomaticFile(root, BACKUP_FILE_NAME)
        val existingPending = findAutomaticFile(root, PENDING_FILE_NAME)
        val existingPrevious = findAutomaticFile(root, PREVIOUS_FILE_NAME)

        val currentMain = main?.let { readCompatibleBackupForRotation(it, "自动保存读取主文件") }
        existingPending?.let { readCompatibleBackupForRotation(it, "自动保存读取待完成文件") }
        existingPrevious?.let { readCompatibleBackupForRotation(it, "自动保存读取上一版本") }

        val pending = existingPending ?: getOrCreateAutomaticFile(root, PENDING_FILE_NAME)
        writeAndVerifyBackup(pending, json, "自动保存临时文件")

        if (currentMain != null) {
            val previous = existingPrevious ?: getOrCreateAutomaticFile(root, PREVIOUS_FILE_NAME)
            writeAndVerifyBackup(previous, currentMain, "自动保存上一版本")
        }

        val target = main ?: getOrCreateAutomaticFile(root, BACKUP_FILE_NAME)
        writeAndVerifyBackup(target, json, "自动保存主文件")
        deletePendingFile(pending)
        return backup.toSummary()
    }

    private fun writeAndVerifyBackup(
        document: DocumentFile,
        json: String,
        action: String,
    ) = writeAndVerifyBackup(document.uri, json, action)

    private fun writeAndVerifyBackup(
        uri: Uri,
        json: String,
        action: String,
    ) {
        writeDocument(uri, json, action)
        val verifiedRaw = readDocument(uri, "$action 校验")
        if (verifiedRaw != json) {
            throw AppBackupException("${action}失败：写入后内容不完整，请检查存储空间。")
        }
        decodeDocument(verifiedRaw, "$action 校验")
    }

    private fun readCompatibleBackupForRotation(
        document: DocumentFile,
        action: String,
    ): String? {
        val raw = readDocument(document.uri, action)
        return try {
            BackupJsonCodec.decode(raw)
            raw
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isUnsupportedBackupVersion(error)) {
                throw AppBackupException(
                    "自动保存失败：$action 发现来自更新版本的备份，当前应用不能覆盖它。",
                    error,
                )
            }
            null
        }
    }

    private fun readAutomaticBackup(root: DocumentFile): AppBackup? {
        val candidates = listOf(
            // A verified pending file means the pending -> main rotation started but may not
            // have finished. Prefer it without comparing wall-clock timestamps, which can move
            // backwards after a manual/NTP clock correction.
            AutomaticBackupCandidate(PENDING_FILE_NAME, "读取待完成自动备份", priority = 3),
            AutomaticBackupCandidate(BACKUP_FILE_NAME, "读取自动备份", priority = 2),
            AutomaticBackupCandidate(PREVIOUS_FILE_NAME, "读取上一版本备份", priority = 1),
        ).mapNotNull { candidate ->
            findAutomaticFile(root, candidate.name)?.let { candidate to it }
        }
        if (candidates.isEmpty()) return null

        val failures = mutableListOf<Throwable>()
        val valid = buildList {
            candidates.forEach { (candidate, document) ->
                try {
                    add(
                        DecodedAutomaticBackup(
                            backup = decodeDocument(
                                readDocument(document.uri, candidate.action),
                                candidate.action,
                            ),
                            priority = candidate.priority,
                        ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (isUnsupportedBackupVersion(error)) throw error
                    failures += error
                }
            }
        }
        valid.maxByOrNull(DecodedAutomaticBackup::priority)?.let { return it.backup }

        val cause = failures.lastOrNull()
            ?: AppBackupException("读取自动备份失败：没有可用的备份文件。")
        failures.dropLast(1).forEach(cause::addSuppressed)
        throw AppBackupException(
            "读取自动备份失败：主文件、待完成文件和上一版本均不可用。",
            cause,
        )
    }

    private data class AutomaticBackupCandidate(
        val name: String,
        val action: String,
        val priority: Int,
    )

    private data class DecodedAutomaticBackup(
        val backup: AppBackup,
        val priority: Int,
    )

    private fun resolveTree(
        treeUri: Uri,
        action: String,
        requireWrite: Boolean,
    ): DocumentFile {
        val isSafTreeUri = try {
            treeUri.scheme == "content" && DocumentsContract.isTreeUri(treeUri)
        } catch (error: Exception) {
            throw AppBackupException("${action}失败：文件夹地址无效，请重新选择。", error)
        }
        if (!isSafTreeUri) {
            throw AppBackupException("${action}失败：请选择有效的系统文件夹。")
        }

        val root = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (error: Exception) {
            throw AppBackupException("${action}失败：无法识别所选文件夹，请重新选择。", error)
        } ?: throw AppBackupException("${action}失败：无法访问所选文件夹，请重新选择。")

        val accessible = try {
            root.exists() && root.isDirectory && root.canRead() && (!requireWrite || root.canWrite())
        } catch (error: Exception) {
            throw AppBackupException("${action}失败：无法读取所选文件夹信息，请重新选择。", error)
        }
        if (!accessible) {
            val permission = if (requireWrite) "读写" else "读取"
            throw AppBackupException("${action}失败：所选文件夹不存在或没有$permission 权限，请重新选择。")
        }
        return root
    }

    private fun findAutomaticFile(root: DocumentFile, name: String): DocumentFile? {
        val document = try {
            root.findFile(name)
        } catch (error: Exception) {
            throw AppBackupException("访问自动备份失败：无法查找 $name。", error)
        } ?: return null
        if (!document.isFile) {
            throw AppBackupException("访问自动备份失败：保存文件夹中存在同名文件夹“$name”。")
        }
        return document
    }

    private fun getOrCreateAutomaticFile(root: DocumentFile, name: String): DocumentFile =
        findAutomaticFile(root, name) ?: try {
            val created = root.createFile(BACKUP_MIME_TYPE, name)
                ?: throw AppBackupException("自动保存失败：无法在所选文件夹创建 $name。")
            ensureFixedFileName(created, name)
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppBackupException) {
            throw error
        } catch (error: Exception) {
            throw AppBackupException("自动保存失败：无法创建 $name。", error)
        }

    private fun ensureFixedFileName(document: DocumentFile, expectedName: String): DocumentFile {
        val initialName = try {
            document.name
        } catch (error: Exception) {
            throw AppBackupException("自动保存失败：无法确认新文件 $expectedName 的名称。", error)
        }
        if (initialName == expectedName) return document

        val renamed = try {
            document.renameTo(expectedName)
        } catch (error: Exception) {
            false
        }
        val finalName = if (renamed) {
            try {
                document.name
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (finalName == expectedName) return document

        try {
            document.delete()
        } catch (_: Exception) {
            // Best-effort cleanup before returning the original naming failure.
        }
        throw AppBackupException(
            "自动保存失败：存储提供方将 $expectedName 改名为 ${initialName ?: "未知名称"}。",
        )
    }

    private fun deletePendingFile(pending: DocumentFile) {
        // Some writable SAF providers do not advertise delete support. A verified pending
        // file is harmless and remains a valid recovery candidate for the next save/import.
        try {
            pending.delete()
        } catch (_: Exception) {
            // The verified main file is already durable; keep pending as a recovery copy.
        }
    }

    private fun parseTreeUri(raw: String, action: String): Uri {
        if (raw.isBlank()) {
            throw AppBackupException("${action}失败：尚未选择应用内容保存文件夹。")
        }
        return try {
            Uri.parse(raw)
        } catch (error: Exception) {
            throw AppBackupException("${action}失败：保存文件夹地址无效，请重新选择。", error)
        }
    }

    private fun decodeDocument(raw: String, action: String): AppBackup = try {
        BackupJsonCodec.decode(raw)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val message = if (isUnsupportedBackupVersion(error)) {
            "${action}失败：备份来自更新版本，请升级应用后再试。"
        } else {
            "${action}失败：备份文件格式无效或已损坏。"
        }
        throw AppBackupException(message, error)
    }

    private fun isUnsupportedBackupVersion(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current.message?.contains("Unsupported backup version", ignoreCase = true) == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private suspend fun loadCurrentContent(): AppBackupContent {
        val settings = settingsRepository.settings.first()
        val databaseContent = database.withTransaction {
            BackupDatabaseContent(
                thoughts = thoughtDao.getAllForBackup(),
                categories = categoryDao.getAllForBackup(),
                favorites = browserDao.getFavoritesForBackup(),
                dateRecords = dateRecordDao.getAllForBackup(),
                poems = savedPoemDao.getAllForBackup(),
            )
        }
        return AppBackupContent(
            settings = settings,
            thoughts = databaseContent.thoughts,
            categories = databaseContent.categories,
            favorites = databaseContent.favorites,
            dateRecords = databaseContent.dateRecords,
            poems = databaseContent.poems,
        )
    }

    private suspend fun rollbackSettingsAfterImportFailure(
        previousSettings: AppSettings,
        originalError: Throwable,
    ) {
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                settingsRepository.restoreFromBackup(previousSettings)
            }
        } catch (rollbackError: Exception) {
            originalError.addSuppressed(rollbackError)
        }
    }

    private fun readDocument(uri: Uri, action: String): String {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: Exception) {
            throw AppBackupException("${action}失败：无法打开 JSON 文件，请检查访问权限。", error)
        } ?: throw AppBackupException("${action}失败：无法读取 JSON 文件。")

        return try {
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalBytes = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    if (totalBytes > MAX_IMPORT_BYTES) {
                        throw AppBackupException("${action}失败：JSON 文件不能超过 10 MiB。")
                    }
                    output.write(buffer, 0, count)
                }
                try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray()))
                        .toString()
                        .removePrefix("\uFEFF")
                } catch (error: CharacterCodingException) {
                    throw AppBackupException("${action}失败：JSON 文件不是有效的 UTF-8 文本。", error)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppBackupException) {
            throw error
        } catch (error: Exception) {
            throw AppBackupException("${action}失败：读取 JSON 文件时发生错误。", error)
        }
    }

    private fun writeDocument(uri: Uri, json: String, action: String) {
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw AppBackupException("${action}失败：无法写入目标文件。")
            output.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(json)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppBackupException) {
            throw error
        } catch (error: Exception) {
            throw AppBackupException("${action}失败：无法写入目标文件，请检查文件夹权限或剩余空间。", error)
        }
    }

    private fun AppBackupContent.toBackup(): AppBackup = AppBackup(
        formatVersion = BackupJsonCodec.FORMAT_VERSION,
        exportedAt = System.currentTimeMillis(),
        settings = settings,
        thoughts = thoughts,
        categories = categories,
        favorites = favorites,
        dateRecords = dateRecords,
        poems = poems,
    )

    private fun AppBackup.toSummary(): BackupSummary = BackupSummary(
        thoughtCount = thoughts.size,
        favoriteCount = favorites.size,
        exportedAt = exportedAt,
        dateRecordCount = dateRecords.size,
        categoryCount = categories.size,
        poemCount = poems.size,
    )

    private data class BackupDatabaseContent(
        val thoughts: List<FlashThoughtEntity>,
        val categories: List<ThoughtCategoryEntity>,
        val favorites: List<BrowserRecordEntity>,
        val dateRecords: List<DateRecordEntity>,
        val poems: List<SavedPoemEntity>,
    )

    private companion object {
        const val BACKUP_FILE_NAME = "DeskCubby.json"
        const val PENDING_FILE_NAME = "DeskCubby.pending.json"
        const val PREVIOUS_FILE_NAME = "DeskCubby.previous.json"
        const val BACKUP_MIME_TYPE = "application/json"
        const val MAX_IMPORT_BYTES = 10 * 1024 * 1024
    }
}

/**
 * Backups before v12 did not contain API keys. Preserve a local key only when both the
 * configuration id and endpoint still match; v12+ backups explicitly own the key value.
 */
internal fun mergeLegacyBackupAiApiKeys(
    imported: AppSettings,
    current: AppSettings,
    formatVersion: Int,
): AppSettings {
    if (formatVersion >= PLAINTEXT_AI_KEY_BACKUP_VERSION) return imported
    val importedConfigs = imported.aiConfigs.ifEmpty {
        imported.aiModel.takeIf(String::isNotBlank)?.let { legacyModel ->
            listOf(
                AiModelConfig(
                    id = "legacy-text",
                    name = "文字模型",
                    type = AiModelType.TEXT,
                    endpointUrl = imported.aiEndpointUrl,
                    model = legacyModel,
                    allowInsecureHttp = imported.aiAllowInsecureHttp,
                    temperature = imported.aiTemperature,
                    systemPrompt = imported.aiSystemPrompt,
                ),
            )
        }.orEmpty()
    }
    val mergedConfigs = importedConfigs.map { importedConfig ->
        val localKey = current.aiConfigs.firstOrNull { localConfig ->
            localConfig.id == importedConfig.id &&
                localConfig.endpointUrl.trim() == importedConfig.endpointUrl.trim()
        }?.apiKey.orEmpty()
        importedConfig.copy(apiKey = localKey)
    }
    return imported.copy(aiConfigs = mergedConfigs)
}

private const val PLAINTEXT_AI_KEY_BACKUP_VERSION = 12
