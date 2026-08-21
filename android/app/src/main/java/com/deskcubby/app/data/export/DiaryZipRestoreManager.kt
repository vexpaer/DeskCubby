package com.deskcubby.app.data.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class DiaryZipRestoreResult(
    val diaryMarkdownCount: Int,
    val workspaceMetadataCount: Int,
    val bytesRestored: Long,
) {
    val totalFiles: Int
        get() = diaryMarkdownCount + workspaceMetadataCount
}

internal enum class DiaryRestoreEntryKind {
    MARKDOWN,
    WORKSPACE_METADATA,
}

internal data class DiaryRestoreEntry(
    val relativePath: String,
    val kind: DiaryRestoreEntryKind,
)

/**
 * Returns the diary-workspace entry represented by [name], or null for unrelated archive content.
 * Malformed paths under `diaries/` fail closed so a crafted archive cannot escape the selected SAF
 * tree or create ambiguous files.
 */
internal fun classifyDiaryRestoreEntry(name: String): DiaryRestoreEntry? {
    if (!name.startsWith(DIARY_ZIP_PREFIX)) return null
    val relative = name.removePrefix(DIARY_ZIP_PREFIX)
    require(relative.isNotBlank()) { "日记压缩包包含空路径 / Diary archive contains an empty path" }
    require('\\' !in relative) { "日记压缩包包含非法路径分隔符 / Diary archive contains an invalid path separator" }
    val segments = relative.split('/')
    require(segments.none { segment ->
        segment.isBlank() || segment == "." || segment == ".." ||
            segment.any { it.code in 0..31 } ||
            segment.any { it in INVALID_RESTORE_NAME_CHARS }
    }) { "日记压缩包包含非法路径 / Diary archive contains an invalid path" }
    val normalized = segments.joinToString("/")
    return when {
        normalized in WORKSPACE_RESTORE_PATHS -> DiaryRestoreEntry(
            relativePath = normalized,
            kind = DiaryRestoreEntryKind.WORKSPACE_METADATA,
        )
        normalized.startsWith(".deskcubby/") -> throw IllegalArgumentException(
            "压缩包包含不受支持的 .deskcubby 文件 / Archive contains an unsupported .deskcubby file",
        )
        normalized.endsWith(".md", ignoreCase = true) -> DiaryRestoreEntry(
            relativePath = normalized,
            kind = DiaryRestoreEntryKind.MARKDOWN,
        )
        else -> null
    }
}

@Singleton
class DiaryZipRestoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun restore(zipUri: Uri): DiaryZipRestoreResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val settings = settingsRepository.settings.first()
            val treeUri = settings.diaryTreeUri
                ?: error("请先选择日记目录 / Choose a diary folder first")
            val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: error("日记目录不可访问 / Diary folder is inaccessible")
            require(root.isDirectory && root.canWrite()) {
                "日记目录不可写 / Diary folder is not writable"
            }

            val stageRoot = File(context.cacheDir, RESTORE_STAGE_DIR)
                .resolve(UUID.randomUUID().toString())
                .apply { mkdirs() }
            try {
                val staged = stageArchive(zipUri, stageRoot)
                require(staged.isNotEmpty()) {
                    "压缩包中没有可恢复的日记或 workspace 文件 / No restorable diary workspace files were found"
                }
                restoreStagedFiles(root, staged)
                DiaryZipRestoreResult(
                    diaryMarkdownCount = staged.count { it.entry.kind == DiaryRestoreEntryKind.MARKDOWN },
                    workspaceMetadataCount = staged.count { it.entry.kind == DiaryRestoreEntryKind.WORKSPACE_METADATA },
                    bytesRestored = staged.sumOf { it.bytes },
                )
            } finally {
                runCatching { stageRoot.deleteRecursively() }
            }
        }
    }

    private suspend fun stageArchive(zipUri: Uri, stageRoot: File): List<StagedEntry> {
        val source = context.contentResolver.openInputStream(zipUri)
            ?: error("无法读取压缩包 / Could not read archive")
        val staged = mutableListOf<StagedEntry>()
        val seenPaths = HashSet<String>()
        var archiveEntries = 0
        var totalBytes = 0L

        BufferedInputStream(source).use { buffered ->
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val zipEntry = zip.nextEntry ?: break
                    archiveEntries += 1
                    require(archiveEntries <= MAX_ARCHIVE_ENTRIES) {
                        "压缩包文件数量过多 / Archive contains too many entries"
                    }
                    try {
                        if (zipEntry.isDirectory) continue
                        val entry = classifyDiaryRestoreEntry(zipEntry.name) ?: continue
                        require(seenPaths.add(entry.relativePath.lowercase(Locale.ROOT))) {
                            "压缩包包含重复日记路径 / Archive contains duplicate diary paths"
                        }
                        val stagedFile = File(stageRoot, "${staged.size}.payload")
                        val entryLimit = if (entry.kind == DiaryRestoreEntryKind.WORKSPACE_METADATA) {
                            MAX_WORKSPACE_FILE_BYTES
                        } else {
                            MAX_MARKDOWN_FILE_BYTES
                        }
                        val copied = copyBounded(
                            source = zip,
                            target = stagedFile,
                            entryLimit = entryLimit,
                            remainingTotal = MAX_TOTAL_RESTORE_BYTES - totalBytes,
                        )
                        totalBytes += copied
                        if (entry.kind == DiaryRestoreEntryKind.WORKSPACE_METADATA) {
                            validateJson(stagedFile, entry.relativePath)
                        }
                        staged += StagedEntry(entry, stagedFile, copied)
                    } finally {
                        runCatching { zip.closeEntry() }
                    }
                }
            }
        }
        return staged
    }

    private suspend fun copyBounded(
        source: InputStream,
        target: File,
        entryLimit: Long,
        remainingTotal: Long,
    ): Long {
        require(remainingTotal > 0L) { "压缩包解压后过大 / Archive is too large when expanded" }
        var copied = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        target.outputStream().buffered().use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                copied += read
                require(copied <= entryLimit) {
                    "压缩包中的单个日记文件过大 / A diary archive entry is too large"
                }
                require(copied <= remainingTotal) {
                    "压缩包解压后过大 / Archive is too large when expanded"
                }
                output.write(buffer, 0, read)
            }
            output.flush()
        }
        return copied
    }

    private fun validateJson(file: File, relativePath: String) {
        val parsed = runCatching {
            JSONTokener(file.readText(Charsets.UTF_8)).nextValue()
        }.getOrNull()
        require(parsed is JSONObject || parsed is JSONArray) {
            "workspace 元数据 JSON 无效：$relativePath / Invalid workspace metadata JSON"
        }
    }

    private suspend fun restoreStagedFiles(root: DocumentFile, staged: List<StagedEntry>) {
        // Write Markdown first and workspace metadata last. If a provider fails partway through,
        // metadata will not point at a workspace whose diary body files have not been attempted yet.
        staged.sortedWith(compareBy<StagedEntry> { it.entry.kind == DiaryRestoreEntryKind.WORKSPACE_METADATA }
            .thenBy { it.entry.relativePath })
            .forEach { stagedEntry ->
                currentCoroutineContext().ensureActive()
                writeOne(root, stagedEntry)
            }
    }

    private fun writeOne(root: DocumentFile, staged: StagedEntry) {
        val parts = staged.entry.relativePath.split('/')
        var directory = root
        parts.dropLast(1).forEach { segment ->
            val existing = directory.findFile(segment)
            directory = when {
                existing == null -> directory.createDirectory(segment)
                    ?: error("无法创建恢复目录：$segment / Could not create restore directory")
                existing.isDirectory -> existing
                else -> error("恢复路径被同名文件占用：$segment / Restore path is blocked by a file")
            }
        }

        val fileName = parts.last()
        val existing = directory.findFile(fileName)
        val target = when {
            existing == null -> directory.createFile(
                if (staged.entry.kind == DiaryRestoreEntryKind.WORKSPACE_METADATA) {
                    "application/json"
                } else {
                    "text/markdown"
                },
                fileName,
            ) ?: error("无法创建恢复文件：${staged.entry.relativePath} / Could not create restore file")
            existing.isFile -> existing
            else -> error("恢复文件路径被目录占用：${staged.entry.relativePath} / Restore file path is blocked by a directory")
        }

        val output = context.contentResolver.openOutputStream(target.uri, "wt")
            ?: error("无法写入恢复文件：${staged.entry.relativePath} / Could not open restore output")
        val written = output.use { sink ->
            staged.file.inputStream().use { source -> source.copyTo(sink, DEFAULT_BUFFER_SIZE) }
        }
        require(written == staged.bytes) {
            "恢复文件写入不完整：${staged.entry.relativePath} / Restore write was incomplete"
        }
        val expectedHash = sha256Hex { staged.file.inputStream() }
        val actualHash = sha256Hex { context.contentResolver.openInputStream(target.uri) }
        require(expectedHash != null && expectedHash == actualHash) {
            "恢复文件校验失败：${staged.entry.relativePath} / Restored file failed verification"
        }
    }

    private fun sha256Hex(open: () -> InputStream?): String? {
        return try {
            val input = open() ?: return null
            input.use { source ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { byte -> "%02x".format(byte) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private data class StagedEntry(
        val entry: DiaryRestoreEntry,
        val file: File,
        val bytes: Long,
    )

    private companion object {
        const val RESTORE_STAGE_DIR = "diary-zip-restore"
        const val MAX_ARCHIVE_ENTRIES = 20_000
        const val MAX_MARKDOWN_FILE_BYTES = 64L * 1024L * 1024L
        const val MAX_WORKSPACE_FILE_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_RESTORE_BYTES = 512L * 1024L * 1024L
    }
}

private const val DIARY_ZIP_PREFIX = "diaries/"
private val INVALID_RESTORE_NAME_CHARS = setOf(':', '*', '?', '"', '<', '>', '|')
private val WORKSPACE_RESTORE_PATHS = setOf(
    ".deskcubby/fields.json",
    ".deskcubby/records.json",
    ".deskcubby/statistics.json",
    ".deskcubby/settings.json",
)
