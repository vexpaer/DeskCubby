package com.deskcubby.app.data.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.deskcubby.app.data.backup.AppBackup
import com.deskcubby.app.data.backup.AppBackupRepository
import com.deskcubby.app.data.backup.BackupJsonCodec
import com.deskcubby.app.data.backup.sanitizedForManualBackup
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.VaultEncryptedBackup
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Which content types the user wants inside the exported zip. */
data class ExportSelection(
    val diaries: Boolean = true,
    val media: Boolean = true,
    val notes: Boolean = true,
    val poems: Boolean = true,
    val thoughts: Boolean = true,
    val favorites: Boolean = false,
    val dateRecords: Boolean = false,
    val readingProgress: Boolean = false,
    val games: Boolean = false,
    val vault: Boolean = false,
    val usage: Boolean = false,
    val agentChats: Boolean = false,
    val settings: Boolean = true,
) {
    val anyStructuredSelected: Boolean
        get() = poems || thoughts || favorites || dateRecords || readingProgress ||
            games || vault || usage || agentChats || settings

    val anyFileSelected: Boolean
        get() = diaries || media || notes

    val anySelected: Boolean
        get() = anyFileSelected || anyStructuredSelected
}

data class ZipExportResult(
    val fileName: String,
    /** Uri usable in an ACTION_SEND share intent (FileProvider cache copy). */
    val shareUri: Uri,
    /** Real Downloads location as a MediaStore/downloads Uri, or the cache file when null. */
    val downloadUri: Uri?,
    val fileBytes: Long,
    val counts: Map<String, Int>,
)

@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: AppBackupRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val mutex = Mutex()

    /** Builds the zip in app cache, then copies it to the Downloads folder. */
    suspend fun buildAndExport(selection: ExportSelection): ZipExportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(selection.anySelected) { "未选择任何导出内容 / Nothing selected to export" }

            val zipFile = buildZip(selection)
            val baseName = zipFile.name.removeSuffix(".zip")
            val downloadUri = copyToDownloads(zipFile, baseName)
            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile,
            )
            ZipExportResult(
                fileName = zipFile.name,
                shareUri = shareUri,
                downloadUri = downloadUri,
                fileBytes = zipFile.length(),
                counts = selectionCounts,
            )
        }
    }

    private val selectionCounts = LinkedHashMap<String, Int>()

    private suspend fun buildZip(selection: ExportSelection): File {
        val settings = settingsRepository.settings.first()
        val baseName = safeZipBaseName(settings.userName)
        val exportDir = File(context.cacheDir, EXPORT_CACHE_DIR).apply { mkdirs() }
        val zipFile = File(exportDir, "$baseName.zip")
        zipFile.delete()

        selectionCounts.clear()
        FileOutputStream(zipFile).use { fos ->
            ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                zos.setLevel(6)
                if (selection.anySelected) {
                    putTextEntry(zos, "README.md", readmeText())
                }
                if (selection.diaries) copyTreeFiles(zos, settings.diaryTreeUri, DIR_PREFIX, TreeKind.DIARY, selectionCounts)
                if (selection.media) copyTreeFiles(zos, settings.mediaTreeUri, MEDIA_PREFIX, TreeKind.MEDIA, selectionCounts)
                if (selection.notes) copyTreeFiles(zos, settings.notesTreeUri, NOTES_PREFIX, TreeKind.NOTES, selectionCounts)
                if (selection.anyStructuredSelected) {
                    val dataJson = buildDataJson(selection)
                    putTextEntry(zos, "$DATA_PREFIX/data.json", dataJson)
                }
            }
        }
        return zipFile
    }

    /**
     * Streams the files of a SAF tree into the zip under [destPrefix], preserving relative folder
     * structure for [kind]. Media sidecar metadata files are skipped.
     */
    private suspend fun copyTreeFiles(
        zos: ZipOutputStream,
        treeUri: String?,
        destPrefix: String,
        kind: TreeKind,
        counts: MutableMap<String, Int>,
    ) {
        if (treeUri.isNullOrBlank()) return
        val root = try {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        } catch (_: Exception) {
            return
        } ?: return

        fun writeFile(file: DocumentFile, relative: String) {
            val valid = sanitizeEntryName(relative) ?: return
            if (kind == TreeKind.MEDIA && file.name in MEDIA_SIDECAR_NAMES) return
            if (kind == TreeKind.DIARY && file.name?.endsWith(".md", ignoreCase = true) != true) return
            if (file.name?.endsWith(".$TRASH_SUFFIX", ignoreCase = true) == true) return
            val uri = file.uri
            counts[kind.label] = counts.getOrDefault(kind.label, 0) + 1
            writeEntry(zos, destPrefix + valid) { output ->
                val input = try {
                    context.contentResolver.openInputStream(uri)
                } catch (_: Exception) {
                    null
                }
                if (input != null) {
                    input.use { it.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
            }
        }

        suspend fun walk(document: DocumentFile, path: String) {
            currentCoroutineContext().ensureActive()
            document.listFiles()
                .sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
                .forEach { child ->
                    currentCoroutineContext().ensureActive()
                    if (child.isDirectory) {
                        if (child.name != TRASH_DIRECTORY) {
                            walk(child, if (path.isEmpty()) child.name.orEmpty() else "$path/${child.name}")
                        }
                    } else if (child.isFile) {
                        writeFile(child, if (path.isEmpty()) child.name.orEmpty() else "$path/${child.name}")
                    }
                }
        }
        walk(root, "")
    }

    private suspend fun buildDataJson(selection: ExportSelection): String {
        val content = backupRepository.currentContent()
        val backup = AppBackup(
            formatVersion = BackupJsonCodec.FORMAT_VERSION,
            exportedAt = System.currentTimeMillis(),
            settings = if (selection.settings) {
                content.settings.sanitizedForManualBackup()
            } else {
                com.deskcubby.app.data.model.AppSettings()
            },
            thoughts = if (selection.thoughts) content.thoughts else emptyList(),
            categories = if (selection.thoughts) content.categories else emptyList(),
            favorites = if (selection.favorites) content.favorites else emptyList(),
            dateRecords = if (selection.dateRecords) content.dateRecords else emptyList(),
            poetryCategories = if (selection.poems) content.poetryCategories else emptyList(),
            poems = if (selection.poems) content.poems else emptyList(),
            vault = if (selection.vault) content.vault else VaultEncryptedBackup(null, null, emptyList()),
            gameStates = if (selection.games) content.gameStates else emptyList(),
            gameStatistics = if (selection.games) content.gameStatistics else emptyList(),
            usageDevices = if (selection.usage) content.usageDevices else emptyList(),
            readerProgress = if (selection.readingProgress) content.readerProgress else emptyList(),
            agentChats = if (selection.agentChats) content.agentChats else byteArrayOf(),
        )
        return BackupJsonCodec.encode(backup)
    }

    /** Writes the zip into the public Downloads/DeskCubby folder, returning its content Uri. */
    private fun copyToDownloads(zipFile: File, baseName: String): Uri? {
        val fileName = "${baseName}.zip"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertIntoMediaStore(zipFile, fileName)
            } else {
                copyToLegacyDownloads(zipFile, fileName)
            }
        } catch (_: Exception) {
            // Keep the cache-only copy shareable; the dialog reports cache location.
            null
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoMediaStore(zipFile: File, fileName: String): Uri? {
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/DeskCubby",
            )
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                zipFile.inputStream().use { it.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
    }

    private fun copyToLegacyDownloads(zipFile: File, fileName: String): Uri? {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val deskCubbyDir = File(downloadDir, "DeskCubby")
        if (!deskCubbyDir.exists() && !deskCubbyDir.mkdirs()) return null
        val target = File(deskCubbyDir, fileName)
        zipFile.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        return Uri.fromFile(target)
    }

    private fun readmeText(): String = README

    private fun putTextEntry(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private inline fun writeEntry(
        zos: ZipOutputStream,
        name: String,
        block: (java.io.OutputStream) -> Unit,
    ) {
        try {
            zos.putNextEntry(ZipEntry(name))
            block(zos)
        } catch (error: CancellationException) {
            throw error
        } finally {
            runCatching { zos.closeEntry() }
        }
    }

    private fun sanitizeEntryName(name: String): String? {
        val cleaned = name
            .replace('\\', '/')
            .split('/')
            .joinToString("/") { segment ->
                val sanitized = segment
                    .trim()
                    .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
                    .trim('.', ' ')
                if (sanitized.isBlank() || sanitized == "." || sanitized == "..") "" else sanitized
            }
            .split('/')
            .filter(String::isNotEmpty)
            .joinToString("/")
        return cleaned.takeIf(String::isNotEmpty)
    }

    private fun safeZipBaseName(userName: String): String {
        val base = userName.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim('.', ' ')
            .take(40)
        return base.ifBlank { "DeskCubby" }
    }

    private enum class TreeKind(val label: String) {
        DIARY("diaries"),
        MEDIA("media"),
        NOTES("notes"),
    }

    private companion object {
        const val EXPORT_CACHE_DIR = "export-zip"
        const val DIR_PREFIX = "diaries/"
        const val MEDIA_PREFIX = "media/"
        const val NOTES_PREFIX = "notes/"
        const val DATA_PREFIX = "data"

        val MEDIA_SIDECAR_NAMES = setOf(
            "dc-media.json",
            "dc-media.pending.json",
            "dc-media.previous.json",
            "deskcubby-media.json",
        )

        const val TRASH_DIRECTORY = ".DeskCubby Trash"
        const val TRASH_SUFFIX = "deskcubby-trash"

        val README = """
            # DeskCubby 数据导出一览 / DeskCubby Export Overview

            本压缩包由 DeskCubby 应用生成，包含你勾选的内容。文件名：<用户名>.zip，
            保存在手机「下载 / Downloads / DeskCubby」文件夹中。

            This archive was generated by the DeskCubby app and contains the content you selected.
            Filename: <username>.zip, saved to your Downloads / DeskCubby folder.

            ## 目录结构 / Folder structure

            README.md                      本说明文件 / This file
            diaries/                       日记 markdown 原文（.md）/ Diary markdown files
            media/                         媒体图片等文件 / Media files (photos, etc.)
            notes/                         笔记 markdown 原文（保留子目录）/ Notes (.md), subfolders preserved
            data/data.json               结构化数据（诗词、小巧思、收藏、日期记录、阅读进度、
                                         游戏、Vault 加密数据、使用统计、Agent 对话、设置与订阅）
                                         Structured data (poems, thoughts, favorites, date records,
                                         reading progress, games, encrypted Vault, usage stats,
                                         Agent conversations, settings & subscriptions)

            data/data.json 是独立 JSON 文件，可由「应用数据 → 导入」恢复相应内容；媒体/日记/
            笔记原文件以原始格式保存，不随 JSON 恢复。

            data/data.json is a standalone JSON file that can be restored via App Data → Import;
            diary/media/note files are kept in their original format and are not restored via JSON.

            ## 说明 / Notes

            - 只有勾选的内容会被导出；未配置目录（日记/媒体/笔记）的类型会被跳过。
              Only selected content is exported; types without a configured folder are skipped.
            - 凭据、API Key 与本地目录 URI 不会进入导出文件，以保护隐私。
              Credentials, API keys and local folder URIs are excluded for privacy.
        """.trimIndent()
    }
}
