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
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
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
    val shareUri: Uri,
    val downloadUri: Uri?,
    val fileBytes: Long,
    val counts: Map<String, Int>,
    val failedFiles: List<String> = emptyList(),
    val failedReason: String? = null,
) {
    val success: Boolean
        get() = failedFiles.isEmpty() && failedReason == null && downloadUri != null
}

@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: AppBackupRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun buildAndExport(selection: ExportSelection): ZipExportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(selection.anySelected) { "未选择任何导出内容 / Nothing selected to export" }
            val zipFile = buildZip(selection)
            val baseName = zipFile.name.removeSuffix(".zip")
            val (downloadUri, reason) = copyToDownloads(zipFile, baseName)
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
                failedFiles = failedFiles,
                failedReason = reason,
            ).also {
                runCatching { File(context.cacheDir, TMP_DIR).deleteRecursively() }
            }
        }
    }

    private val selectionCounts = LinkedHashMap<String, Int>()
    private val failedFiles = mutableListOf<String>()

    private suspend fun buildZip(selection: ExportSelection): File {
        val settings = settingsRepository.settings.first()
        val baseName = safeZipBaseName(settings.userName)
        val exportDir = File(context.cacheDir, EXPORT_CACHE_DIR).apply { mkdirs() }
        val zipFile = File(exportDir, "$baseName.zip")
        zipFile.delete()

        selectionCounts.clear()
        failedFiles.clear()
        FileOutputStream(zipFile).use { fos ->
            ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                zos.setLevel(6)
                if (selection.anySelected) putTextEntry(zos, "README.md", readmeText())
                if (selection.diaries) {
                    copyTreeFiles(
                        zos,
                        settings.diaryTreeUri,
                        DIR_PREFIX,
                        TreeKind.DIARY,
                        selectionCounts,
                        failedFiles,
                    )
                }
                if (selection.media) {
                    copyTreeFiles(
                        zos,
                        settings.mediaTreeUri,
                        MEDIA_PREFIX,
                        TreeKind.MEDIA,
                        selectionCounts,
                        failedFiles,
                    )
                }
                if (selection.notes) {
                    copyTreeFiles(
                        zos,
                        settings.notesTreeUri,
                        NOTES_PREFIX,
                        TreeKind.NOTES,
                        selectionCounts,
                        failedFiles,
                    )
                }
                if (selection.anyStructuredSelected) {
                    putTextEntry(zos, "$DATA_PREFIX/data.json", buildDataJson(selection))
                }
            }
        }
        return zipFile
    }

    private suspend fun copyTreeFiles(
        zos: ZipOutputStream,
        treeUri: String?,
        destPrefix: String,
        kind: TreeKind,
        counts: MutableMap<String, Int>,
        failed: MutableList<String>,
    ) {
        if (treeUri.isNullOrBlank()) {
            failed.add("${kind.label} (目录未设置 / folder not set)")
            return
        }
        val root = try {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        } catch (_: Exception) {
            failed.add("${kind.label} (目录不可访问 / folder inaccessible)")
            return
        } ?: run {
            failed.add("${kind.label} (目录不可访问 / folder inaccessible)")
            return
        }

        fun writeFile(file: DocumentFile, relative: String) {
            val workspaceMetadata = kind == TreeKind.DIARY && relative in WORKSPACE_EXPORT_PATHS
            val valid = if (workspaceMetadata) relative else sanitizeEntryName(relative) ?: return
            if (kind == TreeKind.DIARY) {
                val diaryMarkdown = file.name?.endsWith(".md", ignoreCase = true) == true
                if (!diaryMarkdown && !workspaceMetadata) return
            }
            if (file.name?.endsWith(".$TRASH_SUFFIX", ignoreCase = true) == true) return
            val uri = file.uri
            val temp = File(
                File(context.cacheDir, TMP_DIR).apply { mkdirs() },
                "${UUID.randomUUID()}.tmp",
            )
            val copied: Long = try {
                readToTemp(uri, temp)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                temp.delete()
                failed.add(relative)
                return
            }
            if (copied < 0) {
                temp.delete()
                failed.add(relative)
                return
            }
            try {
                writeEntry(zos, destPrefix + valid) { output ->
                    temp.inputStream().use { it.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                counts[kind.label] = counts.getOrDefault(kind.label, 0) + 1
            } finally {
                temp.delete()
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
                            walk(
                                child,
                                if (path.isEmpty()) child.name.orEmpty() else "$path/${child.name}",
                            )
                        }
                    } else if (child.isFile) {
                        writeFile(
                            child,
                            if (path.isEmpty()) child.name.orEmpty() else "$path/${child.name}",
                        )
                    }
                }
        }
        walk(root, "")
    }

    private fun readToTemp(uri: Uri, temp: File): Long {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
        if (input == null) return -1
        return input.use { source ->
            temp.outputStream().use { target -> source.copyTo(target, DEFAULT_BUFFER_SIZE) }
        }
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

    private fun copyToDownloads(zipFile: File, baseName: String): Pair<Uri?, String?> {
        val fileName = "${baseName}.zip"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                insertIntoMediaStore(zipFile, fileName)
            } else {
                copyToLegacyDownloads(zipFile, fileName)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null to (error.message ?: "无法写入下载目录 / Could not write to Downloads")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoMediaStore(zipFile: File, fileName: String): Pair<Uri?, String?> {
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
        val uri = resolver.insert(collection, values)
            ?: return null to "无法创建下载文件 / Could not create Downloads entry"
        return try {
            val output = resolver.openOutputStream(uri, "w")
            if (output == null) {
                runCatching { resolver.delete(uri, null, null) }
                return null to "无法打开下载文件 / Could not open Downloads output"
            }
            val written = output.use { out ->
                zipFile.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE) }
            }
            if (written != zipFile.length()) {
                runCatching { resolver.delete(uri, null, null) }
                return null to "文件写入不完整 / Download write was incomplete"
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            val expectedHash = sha256Hex { zipFile.inputStream() }
            val reopen = resolver.openInputStream(uri)
            if (reopen == null) {
                runCatching { resolver.delete(uri, null, null) }
                return null to "无法验证下载文件 / Could not verify Downloads copy"
            }
            val actualHash = sha256Hex { reopen }
            if (actualHash != expectedHash) {
                runCatching { resolver.delete(uri, null, null) }
                return null to "下载文件校验失败 / Downloads copy failed verification"
            }
            uri to null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null to "无法写入下载文件 / Could not write to Downloads"
        }
    }

    private fun copyToLegacyDownloads(zipFile: File, fileName: String): Pair<Uri?, String?> {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val deskCubbyDir = File(downloadDir, "DeskCubby")
        if (!deskCubbyDir.exists() && !deskCubbyDir.mkdirs()) {
            return null to "无法创建下载目录 / Could not create Downloads folder"
        }
        val target = File(deskCubbyDir, fileName)
        val written = try {
            zipFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { target.delete() }
            return null to (error.message ?: "无法写入下载文件 / Could not write to Downloads")
        }
        if (written != zipFile.length()) {
            runCatching { target.delete() }
            return null to "文件写入不完整 / Download write was incomplete"
        }
        val expectedHash = sha256Hex { zipFile.inputStream() }
        val actualHash = runCatching { sha256Hex { target.inputStream() } }.getOrNull()
        if (actualHash != expectedHash) {
            runCatching { target.delete() }
            return null to "下载文件校验失败 / Downloads copy failed verification"
        }
        return Uri.fromFile(target) to null
    }

    private fun sha256Hex(open: () -> InputStream?): String? = try {
        val input = open() ?: return null
        input.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
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
        const val TMP_DIR = "export-zip-tmp"
        const val DIR_PREFIX = "diaries/"
        const val MEDIA_PREFIX = "media/"
        const val NOTES_PREFIX = "notes/"
        const val DATA_PREFIX = "data"
        const val TRASH_DIRECTORY = ".DeskCubby Trash"
        const val TRASH_SUFFIX = "deskcubby-trash"
        val WORKSPACE_EXPORT_PATHS = setOf(
            ".deskcubby/fields.json",
            ".deskcubby/records.json",
            ".deskcubby/statistics.json",
            ".deskcubby/settings.json",
        )

        val README = """
            # DeskCubby 数据导出一览 / DeskCubby Export Overview

            本压缩包由 DeskCubby 应用生成，包含你勾选的内容。文件名：<用户名>.zip，
            保存在手机「下载 / Downloads / DeskCubby」文件夹中。

            This archive was generated by the DeskCubby app and contains the content you selected.
            Filename: <username>.zip, saved to your Downloads / DeskCubby folder.

            ## 目录结构 / Folder structure

            README.md                      本说明文件 / This file
            diaries/                       日记 markdown 与 .deskcubby 工作区元数据
                                          Diary markdown + .deskcubby workspace metadata
            media/                         媒体图片与元数据（照片、dc-media.json 等）
                                          Media files and metadata (photos, dc-media.json, etc.)
            notes/                         笔记 markdown 原文（保留子目录）/ Notes (.md), subfolders preserved
            data/data.json                 结构化数据（诗词、小巧思、收藏、日期记录、阅读进度、
                                           游戏、Vault 加密数据、使用统计、Agent 对话、设置与订阅）
                                           Structured data (poems, thoughts, favorites, date records,
                                           reading progress, games, encrypted Vault, usage stats,
                                           Agent conversations, settings & subscriptions)

            diaries/.deskcubby/fields.json、records.json、statistics.json、settings.json 属于
            日记 workspace 元数据；它们与 Markdown 一起备份。设备本地 DataStore、API Key、
            今日日记切换时间与权限设置不会作为 workspace 元数据导出。

            data/data.json is a standalone JSON file that can be restored via App Data → Import;
            diary/media/note files are kept in their original format and are not restored via JSON.
            Media metadata (calories, foods, place) is backed up with media/dc-media.json.

            ## 说明 / Notes

            - 只有勾选的内容会被导出；未配置目录（日记/媒体/笔记）的类型会被跳过。
              Only selected content is exported; types without a configured folder are skipped.
            - 无法读取的文件会被跳过并在此界面列出，不会静默产生空文件。
              Files that cannot be read are skipped and listed here; they are never silently emptied.
            - 凭据、API Key 与本地目录 URI 不会进入导出文件，以保护隐私。
              Credentials, API keys and local folder URIs are excluded for privacy.
        """.trimIndent()
    }
}