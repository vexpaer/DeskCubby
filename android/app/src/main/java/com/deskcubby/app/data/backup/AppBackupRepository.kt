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
import com.deskcubby.app.data.local.GameStateDao
import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.local.GameStatisticDao
import com.deskcubby.app.data.local.GameStatisticEntity
import com.deskcubby.app.data.local.PoetryCategoryDao
import com.deskcubby.app.data.local.PoetryCategoryEntity
import com.deskcubby.app.data.local.SavedPoemDao
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.local.ThoughtCategoryDao
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.normalizeHomeGameShortcutIds
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.sync.AgentChatSyncRepository
import com.deskcubby.app.data.sync.AgentChatSyncCodec
import com.deskcubby.app.data.sync.sha256
import com.deskcubby.app.data.repository.ReaderProgressRecord
import com.deskcubby.app.data.repository.ReaderRepository
import com.deskcubby.app.data.repository.VaultEncryptedBackup
import com.deskcubby.app.data.repository.VaultRepository
import com.deskcubby.app.data.statistics.UsageDeviceRecord
import com.deskcubby.app.data.statistics.UsageDeviceRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppBackupContent(
    val settings: AppSettings,
    val thoughts: List<FlashThoughtEntity>,
    val categories: List<ThoughtCategoryEntity>,
    val favorites: List<BrowserRecordEntity>,
    val dateRecords: List<DateRecordEntity>,
    val poetryCategories: List<PoetryCategoryEntity>,
    val poems: List<SavedPoemEntity>,
    val vault: VaultEncryptedBackup = VaultEncryptedBackup(null, null, emptyList()),
    val gameStates: List<GameStateEntity> = emptyList(),
    val gameStatistics: List<GameStatisticEntity> = emptyList(),
    val usageDevices: List<UsageDeviceRecord> = emptyList(),
    val readerProgress: List<ReaderProgressRecord> = emptyList(),
    val agentChats: ByteArray = byteArrayOf(),
)

class AppBackupException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

@Singleton
class AppBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val thoughtDao: FlashThoughtDao,
    private val categoryDao: ThoughtCategoryDao,
    private val browserDao: BrowserRecordDao,
    private val dateRecordDao: DateRecordDao,
    private val poetryCategoryDao: PoetryCategoryDao,
    private val savedPoemDao: SavedPoemDao,
    private val gameStateDao: GameStateDao,
    private val gameStatisticDao: GameStatisticDao,
    private val settingsRepository: SettingsRepository,
    private val vaultRepository: VaultRepository,
    private val usageDeviceRepository: UsageDeviceRepository,
    private val readerRepository: ReaderRepository,
    private val agentChatSyncRepository: AgentChatSyncRepository,
) {
    private val operationMutex = Mutex()

    suspend fun currentContent(): AppBackupContent = operationMutex.withLock {
        withContext(Dispatchers.IO) { loadCurrentContent() }
    }

    /** Produces the same validated JSON snapshot used by preview, manual and automatic backups. */
    suspend fun exportTo(uri: Uri): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val content = loadCurrentContent()
            val backup = content.toBackup()
            val json = BackupJsonCodec.encode(backup)
            val target = resolveNonOverwritingExportTarget(uri)
            writeAndVerifyBackup(target, json, "导出")
            backup.toSummary()
        }
    }

    /** Parses and validates a manual backup without changing any local data. */
    suspend fun inspectFrom(uri: Uri): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            decodeDocument(readDocument(uri, "备份预览"), "备份预览").toSummary()
        }
    }

    suspend fun importFrom(uri: Uri): BackupSummary = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            val raw = readDocument(uri, "导入")
            val backup = decodeDocument(raw, "导入")
            restoreBackup(backup)
        }
    }

    /**
     * Never silently overwrites an existing user file. If the SAF picker returned an existing
     * name, a sibling DC-YYYY-MM-DD-2.json / -3.json is created instead.
     */
    private fun resolveNonOverwritingExportTarget(requested: Uri): Uri {
        val document = try {
            DocumentFile.fromSingleUri(context, requested)
        } catch (_: Exception) {
            null
        }
        if (document?.exists() != true) return requested

        val name = document.name ?: return requested
        val parent = document.parentFile ?: return requested
        val base = name.removeSuffix(".json")
        val mime = document.type?.takeIf(String::isNotBlank) ?: BACKUP_MIME_TYPE
        var sequence = 2
        while (sequence <= 10_000) {
            val candidate = "$base-$sequence.json"
            val sibling = try {
                parent.findFile(candidate)
            } catch (_: Exception) {
                null
            }
            if (sibling == null) {
                val created = try {
                    parent.createFile(mime, candidate)
                } catch (_: Exception) {
                    null
                } ?: throw AppBackupException(
                    "导出失败：无法创建不重名的 $candidate。",
                )
                if (created.name == candidate || created.renameTo(candidate)) {
                    return created.uri
                }
                runCatching { created.delete() }
            }
            sequence += 1
        }
        throw AppBackupException("导出失败：同名文件过多，请选择其他位置。")
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

    private suspend fun restoreBackup(backup: AppBackup): BackupSummary {
        val previous = try {
            loadCurrentContent(
                includeV20Private = backup.formatVersion >= 20,
                includeReaderProgress = backup.formatVersion >= 28,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw AppBackupException("导入失败：无法读取当前数据，原有内容未改变。", error)
        }
        val previousSettings = previous.settings

        val portableImported = backup.settings.sanitizedForManualBackup()
        val previousAgentChats = previous.agentChats
        var agentChatsMayNeedRollback = false
        val restoredSettings = mergeAndroidOnlyGameShortcut(
            imported = mergeBackupCloudSyncSettings(
                imported = mergeLegacyBackupAiApiKeys(
                    imported = portableImported,
                    current = previousSettings,
                    formatVersion = backup.formatVersion,
                ),
                current = previousSettings,
                formatVersion = backup.formatVersion,
            ),
            current = previousSettings,
        ).copy(
            // Usage access and health-data authorization are device-local grants. Imports
            // never activate sensitive collection on a device that has not confirmed them.
            usageTrackingEnabled = false,
            stepTrackingEnabled = false,
        )
        var databaseMayNeedRollback = false
        try {
            settingsRepository.restoreFromBackup(restoredSettings, preserveDeviceState = false)
            // Restore the fallible private stores before replacing the transactional core data.
            // This leaves the Room transaction as the final commit point, so a later failure
            // cannot roll a freshly recorded game-statistics increment back to an old snapshot.
            if (backup.formatVersion >= 20) {
                vaultRepository.restoreEncryptedBackup(backup.vault)
                usageDeviceRepository.mergeBackup(backup.usageDevices)
            }
            if (backup.formatVersion >= 28) {
                readerRepository.importProgressRecords(backup.readerProgress)
            }
            if (backup.formatVersion >= 34 && backup.agentChats.isNotEmpty()) {
                agentChatSyncRepository.replaceFromBackupSnapshot(backup.agentChats)
                agentChatsMayNeedRollback = true
            }
            database.withTransaction {
                // Game actions can save both their board and statistics while the import
                // confirmation is open. Read both inside this transaction so every write committed
                // before the replace is preserved; writes committed afterwards are serialized
                // after the replace.
                val liveGameStates = gameStateDao.getAllForBackup()
                val liveGameStatistics = gameStatisticDao.getAllForBackup()
                replaceDatabaseContent(
                    thoughts = backup.thoughts,
                    categories = backup.categories,
                    favorites = backup.favorites,
                    dateRecords = backup.dateRecords,
                    poetryCategories = backup.poetryCategories,
                    poems = backup.poems,
                    gameStates = if (backup.formatVersion >= 20) {
                        mergeGameStateBackups(liveGameStates, backup.gameStates)
                    } else {
                        liveGameStates
                    },
                    gameStatistics = if (backup.formatVersion >= 24) {
                        mergeGameStatisticBackups(
                            liveGameStatistics,
                            backup.gameStatistics,
                        )
                    } else {
                        liveGameStatistics
                    },
                )
                // Mark the database as requiring rollback before Room starts its commit. If the
                // caller is cancelled while withTransaction resumes after a successful commit,
                // the cancellation handler must restore Room together with Settings/private data.
                // If the transaction itself rolls back, restoring the previous snapshot again is
                // harmless and keeps every cancellation boundary consistent.
                databaseMayNeedRollback = true
            }
        } catch (error: CancellationException) {
            rollbackImport(
                previous = previous,
                originalError = error,
                restoreV20Private = backup.formatVersion >= 20,
                restoreReaderProgress = backup.formatVersion >= 28,
                restoreDatabase = databaseMayNeedRollback,
                restoreAgentChats = agentChatsMayNeedRollback,
                previousAgentChats = previousAgentChats,
            )
            throw error
        } catch (error: Exception) {
            rollbackImport(
                previous = previous,
                originalError = error,
                restoreV20Private = backup.formatVersion >= 20,
                restoreReaderProgress = backup.formatVersion >= 28,
                restoreDatabase = databaseMayNeedRollback,
                restoreAgentChats = agentChatsMayNeedRollback,
                previousAgentChats = previousAgentChats,
            )
            val message = if (error.suppressed.isNotEmpty()) {
                "导入失败：原数据回滚未完全成功，请保留当前备份文件。"
            } else {
                "导入失败：原有内容已恢复。"
            }
            throw AppBackupException(message, error)
        }

        return backup.toSummary()
    }

    private suspend fun replaceDatabaseContent(
        thoughts: List<FlashThoughtEntity>,
        categories: List<ThoughtCategoryEntity>,
        favorites: List<BrowserRecordEntity>,
        dateRecords: List<DateRecordEntity>,
        poetryCategories: List<PoetryCategoryEntity>,
        poems: List<SavedPoemEntity>,
        gameStates: List<GameStateEntity>,
        gameStatistics: List<GameStatisticEntity>,
    ) {
        // Thoughts must be removed before their referenced categories, then restored only after
        // every category exists, so foreign-key checks stay valid.
        thoughtDao.clearAllForBackup()
        categoryDao.clearAllForBackup()
        if (categories.isNotEmpty()) categoryDao.insertAllForBackup(categories)
        if (thoughts.isNotEmpty()) thoughtDao.insertAllForBackup(thoughts)
        browserDao.replaceFavoritesForBackup(favorites)
        dateRecordDao.replaceAllForBackup(dateRecords)
        savedPoemDao.clearAllForBackup()
        poetryCategoryDao.clearAllForBackup()
        if (poetryCategories.isNotEmpty()) {
            poetryCategoryDao.insertAllForBackup(poetryCategories)
        }
        if (poems.isNotEmpty()) savedPoemDao.insertAllForBackup(poems)
        gameStateDao.replaceAllForBackup(gameStates)
        gameStatisticDao.replaceAllForBackup(gameStatistics)
    }

    private suspend fun rollbackImport(
        previous: AppBackupContent,
        originalError: Throwable,
        restoreV20Private: Boolean,
        restoreReaderProgress: Boolean,
        restoreDatabase: Boolean,
        restoreAgentChats: Boolean,
        previousAgentChats: ByteArray,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                settingsRepository.restoreFromBackup(previous.settings)
            }.exceptionOrNull()?.let(originalError::addSuppressed)
            if (restoreDatabase) {
                runCatching {
                    database.withTransaction {
                        replaceDatabaseContent(
                            thoughts = previous.thoughts,
                            categories = previous.categories,
                            favorites = previous.favorites,
                            dateRecords = previous.dateRecords,
                            poetryCategories = previous.poetryCategories,
                            poems = previous.poems,
                            gameStates = previous.gameStates,
                            gameStatistics = previous.gameStatistics,
                        )
                    }
                }.exceptionOrNull()?.let(originalError::addSuppressed)
            }
            if (restoreV20Private) {
                runCatching {
                    vaultRepository.restoreEncryptedBackup(previous.vault)
                }.exceptionOrNull()?.let(originalError::addSuppressed)
                runCatching {
                    usageDeviceRepository.replaceAllForRollback(previous.usageDevices)
                }.exceptionOrNull()?.let(originalError::addSuppressed)
            }
            if (restoreReaderProgress) {
                runCatching {
                    readerRepository.replaceProgressRecordsForRollback(previous.readerProgress)
                }.exceptionOrNull()?.let(originalError::addSuppressed)
            }
            if (restoreAgentChats && previousAgentChats.isNotEmpty()) {
                runCatching {
                    agentChatSyncRepository.replaceFromBackupSnapshot(previousAgentChats)
                }.exceptionOrNull()?.let(originalError::addSuppressed)
            }
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

    private suspend fun loadCurrentContent(
        includeV20Private: Boolean = true,
        includeReaderProgress: Boolean = true,
    ): AppBackupContent {
        val settings = settingsRepository.settings.first()
        val databaseContent = database.withTransaction {
            BackupDatabaseContent(
                thoughts = thoughtDao.getAllForBackup(),
                categories = categoryDao.getAllForBackup(),
                favorites = browserDao.getFavoritesForBackup(),
                dateRecords = dateRecordDao.getAllForBackup(),
                poetryCategories = poetryCategoryDao.getAllForBackup(),
                poems = savedPoemDao.getAllForBackup(),
                gameStates = gameStateDao.getAllForBackup(),
                gameStatistics = gameStatisticDao.getAllForBackup(),
            )
        }
        val vault = if (includeV20Private) {
            vaultRepository.createEncryptedBackup()
        } else {
            VaultEncryptedBackup(null, null, emptyList())
        }
        val usageDevices = if (includeV20Private) {
            usageDeviceRepository.snapshotAll()
        } else {
            emptyList()
        }
        val readerProgress = if (includeReaderProgress) {
            readerRepository.exportProgressRecords()
        } else {
            emptyList()
        }
        val agentChats = agentChatSyncRepository.snapshot(
            AgentChatSyncRepository.MAX_JSON_BYTES.toLong(),
        ).bytes
        return AppBackupContent(
            settings = settings,
            thoughts = databaseContent.thoughts,
            categories = databaseContent.categories,
            favorites = databaseContent.favorites,
            dateRecords = databaseContent.dateRecords,
            poetryCategories = databaseContent.poetryCategories,
            poems = databaseContent.poems,
            vault = vault,
            gameStates = databaseContent.gameStates,
            gameStatistics = databaseContent.gameStatistics,
            usageDevices = usageDevices,
            readerProgress = readerProgress,
            agentChats = agentChats,
        )
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
                        throw AppBackupException("${action}失败：JSON 文件不能超过 64 MiB。")
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

    private fun AppBackupContent.toBackup(): AppBackup {
        val projected = projectForV28Export()
        return AppBackup(
            formatVersion = BackupJsonCodec.FORMAT_VERSION,
            exportedAt = System.currentTimeMillis(),
            settings = projected.settings,
            thoughts = projected.thoughts,
            categories = projected.categories,
            favorites = projected.favorites,
            dateRecords = projected.dateRecords,
            poetryCategories = projected.poetryCategories,
            poems = projected.poems,
            vault = projected.vault,
            gameStates = projected.gameStates,
            gameStatistics = projected.gameStatistics,
            usageDevices = projected.usageDevices,
            readerProgress = projected.readerProgress,
            agentChats = projected.agentChats,
        )
    }

    private fun AppBackup.toSummary(): BackupSummary = BackupSummary(
        thoughtCount = thoughts.size,
        favoriteCount = favorites.size,
        exportedAt = exportedAt,
        dateRecordCount = dateRecords.size,
        categoryCount = categories.size,
        poetryCategoryCount = poetryCategories.size,
        poemCount = poems.size,
        vaultItemCount = vault.items.count { it.id > 0L },
        gameStateCount = gameStates.size,
        gameStatisticCount = gameStatistics.size,
        usageDeviceCount = usageDevices.size,
        usageDayCount = usageDevices.sumOf { it.history.days.size },
        readerProgressCount = readerProgress.size,
        agentConversationCount = if (agentChats.isNotEmpty()) {
            runCatching { AgentChatSyncCodec.decode(agentChats).conversations.size }.getOrDefault(0)
        } else {
            0
        },
    )

    private data class BackupDatabaseContent(
        val thoughts: List<FlashThoughtEntity>,
        val categories: List<ThoughtCategoryEntity>,
        val favorites: List<BrowserRecordEntity>,
        val dateRecords: List<DateRecordEntity>,
        val poetryCategories: List<PoetryCategoryEntity>,
        val poems: List<SavedPoemEntity>,
        val gameStates: List<GameStateEntity> = emptyList(),
        val gameStatistics: List<GameStatisticEntity> = emptyList(),
    )

    private data class BackupExtraContent(
        val gameStates: List<GameStateEntity>,
        val gameStatistics: List<GameStatisticEntity>,
        val usageDevices: List<UsageDeviceRecord>,
    )

    private companion object {
        const val BACKUP_MIME_TYPE = "application/json"
        const val MAX_IMPORT_BYTES = BackupJsonCodec.MAX_JSON_BYTES
    }
}

/**
 * Android has a local-only Go game that Windows 0.4's v28 whitelist cannot decode. Keep its Room
 * save, statistics and Home shortcut at runtime, but omit all three from the cross-platform v28
 * projection until a future backup version can advertise them safely.
 */
internal fun AppBackupContent.projectForV28Export(): AppBackupContent = copy(
    settings = settings.sanitizedForManualBackup().copy(
        homeGameShortcuts = settings.homeGameShortcuts.filterNot {
            it == ANDROID_ONLY_GAME_ID
        },
    ),
    gameStates = gameStates.filterNot { it.gameId == ANDROID_ONLY_GAME_ID },
    gameStatistics = gameStatistics.filterNot { it.gameId == ANDROID_ONLY_GAME_ID },
)

/**
 * Manual backups are portable structured data, not device images. Every SAF URI, local grant,
 * usage/health authorization preference and AI/cloud secret is removed before encoding.
 */
internal fun AppSettings.sanitizedForManualBackup(): AppSettings = copy(
    backgroundImageUri = null,
    backupTreeUri = null,
    diaryTreeUri = null,
    mediaTreeUri = null,
    notesTreeUri = null,
    poetryFontUri = null,
    usageTrackingEnabled = false,
    stepTrackingEnabled = false,
    cloudSyncEnabled = false,
    aiConfigs = aiConfigs.map { it.copy(apiKey = "") },
    cloudSyncConfigs = cloudSyncConfigs.map { config ->
        config.copy(
            enabled = false,
            webDavPassword = "",
            s3AccessKey = "",
            s3SecretKey = "",
            s3SessionToken = "",
        )
    },
    desktopWidgetConfigs = desktopWidgetConfigs.map { it.copy(backgroundImageUri = null) },
)

private const val ANDROID_ONLY_GAME_ID = "go"

/** v28 cannot encode Go, so importing a v28 projection must not clear its local shortcut choice. */
internal fun mergeAndroidOnlyGameShortcut(
    imported: AppSettings,
    current: AppSettings,
): AppSettings {
    val selected = if (ANDROID_ONLY_GAME_ID in current.homeGameShortcuts) {
        imported.homeGameShortcuts + ANDROID_ONLY_GAME_ID
    } else {
        imported.homeGameShortcuts
    }
    return imported.copy(homeGameShortcuts = normalizeHomeGameShortcutIds(selected))
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

internal fun mergeGameStateBackups(
    current: List<GameStateEntity>,
    imported: List<GameStateEntity>,
): List<GameStateEntity> {
    val currentById = current.associateBy(GameStateEntity::gameId)
    val importedById = imported.associateBy(GameStateEntity::gameId)
    return (currentById.keys + importedById.keys)
        .sorted()
        .map { gameId ->
            val local = currentById[gameId]
            val remote = importedById[gameId]
            when {
                local == null -> checkNotNull(remote)
                remote == null -> local
                else -> {
                    val newest = if (remote.updatedAt >= local.updatedAt) remote else local
                    newest.copy(
                        highScore = maxOf(local.highScore, remote.highScore),
                        updatedAt = maxOf(local.updatedAt, remote.updatedAt),
                    )
                }
            }
        }
}

internal fun mergeGameStatisticBackups(
    current: List<GameStatisticEntity>,
    imported: List<GameStatisticEntity>,
): List<GameStatisticEntity> {
    fun keyOf(item: GameStatisticEntity) = item.gameId to item.metricKey
    val currentByKey = current.associateBy(::keyOf)
    val importedByKey = imported.associateBy(::keyOf)
    return (currentByKey.keys + importedByKey.keys)
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .map { key ->
            val local = currentByKey[key]
            val remote = importedByKey[key]
            when {
                local == null -> checkNotNull(remote)
                remote == null -> local
                else -> local.copy(
                    value = maxOf(local.value, remote.value),
                    updatedAt = maxOf(local.updatedAt, remote.updatedAt),
                )
            }
        }
}

internal fun mergeBackupCloudSyncSettings(
    imported: AppSettings,
    current: AppSettings,
    formatVersion: Int,
): AppSettings = if (formatVersion < 13) {
    imported.copy(
        cloudSyncEnabled = false,
        cloudSyncConfigs = current.cloudSyncConfigs,
    )
} else {
    // Credentials are device-local and are resolved only after the user reviews and re-enables
    // an imported configuration.
    imported.copy(cloudSyncEnabled = false)
}
