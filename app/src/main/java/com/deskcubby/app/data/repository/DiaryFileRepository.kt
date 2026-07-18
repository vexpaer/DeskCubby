package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.DiaryEditorDocument
import com.deskcubby.app.data.model.DiaryTrashItem
import com.deskcubby.app.data.model.ImportedMedia
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ExternalFileConflictException(
    val diskDocument: DiaryEditorDocument,
) : IllegalStateException("日记已被其他应用修改")

@Singleton
class DiaryFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexDao: DiaryIndexDao,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val writeMutex = Mutex()

    suspend fun scan(settings: AppSettings): List<DiaryDocument> = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext emptyList()
        val documents = root.listFiles()
            .asSequence()
            .filter { it.isFile && it.name?.endsWith(".md", ignoreCase = true) == true }
            .map { document ->
                val content = readText(document.uri)
                val date = extractDate(document.name.orEmpty(), document.lastModified())
                val title = markdownStem(document.name.orEmpty())
                DiaryDocument(
                    uri = document.uri.toString(),
                    name = document.name.orEmpty(),
                    title = title,
                    dateIso = date.toString(),
                    monthKey = "%04d.%02d".format(Locale.ROOT, date.year, date.monthValue),
                    lastModified = document.lastModified(),
                    size = document.length(),
                    wordCount = DiaryTextUtils.wordCount(content),
                ) to DiaryTextUtils.sha256(content.toByteArray())
            }
            .toList()

        indexDao.replaceAfterSuccessfulScan(
            documents.map { (item, hash) ->
                DiaryIndexEntity(
                    uri = item.uri,
                    name = item.name,
                    title = item.title,
                    dateIso = item.dateIso,
                    monthKey = item.monthKey,
                    lastModified = item.lastModified,
                    size = item.size,
                    wordCount = item.wordCount,
                    sha256 = hash,
                    indexedAt = System.currentTimeMillis(),
                )
            },
        )
        documents.map { it.first }.sortedWith(compareByDescending<DiaryDocument> { it.dateIso }.thenByDescending { it.name })
    }

    suspend fun load(uri: String): DiaryEditorDocument = withContext(Dispatchers.IO) {
        load(Uri.parse(uri))
    }

    suspend fun enterToday(settings: AppSettings, today: LocalDate = LocalDate.now()): DiaryEditorDocument =
        withContext(Dispatchers.IO) {
            val root = settings.diaryTreeUri?.let(::tree)
                ?: error("请先在设置中选择日记目录")
            val baseName = formatDate(today, settings.fileNamePattern, "yyyy-MM-dd '日记'")
            val fileName = DiaryTextUtils.sanitizeFileName(baseName.removeSuffix(".md")) + ".md"
            val existing = root.listFiles().firstOrNull { it.name.equals(fileName, ignoreCase = true) }
            if (existing != null) return@withContext load(existing.uri)

            val title = baseName.removeSuffix(".md")
            val dateText = today.toString()
            val content = settings.markdownTemplate
                .replace("{title}", title)
                .replace("{date}", dateText)
            val created = root.createFile("text/markdown", fileName)
                ?: error("无法在所选目录中创建日记")
            writeText(created.uri, content)
            load(created.uri)
        }

    suspend fun create(settings: AppSettings, title: String, date: LocalDate = LocalDate.now()): DiaryEditorDocument =
        withContext(Dispatchers.IO) {
            val root = settings.diaryTreeUri?.let(::tree) ?: error("请先选择日记目录")
            val dateText = date.toString()
            val safeTitle = DiaryTextUtils.sanitizeFileName(title.ifBlank { "新日记" })
            var candidate = "$dateText $safeTitle.md"
            var sequence = 2
            while (root.findFile(candidate) != null) {
                candidate = "$dateText $safeTitle ($sequence).md"
                sequence++
            }
            val file = root.createFile("text/markdown", candidate) ?: error("创建日记失败")
            val content = settings.markdownTemplate
                .replace("{title}", title.ifBlank { "新日记" })
                .replace("{date}", dateText)
            writeText(file.uri, content)
            load(file.uri)
        }

    suspend fun save(
        uri: String,
        content: String,
        expectedSha256: String,
        force: Boolean = false,
    ): DiaryEditorDocument = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val target = Uri.parse(uri)
            val onDisk = load(target)
            if (!force && onDisk.sha256 != expectedSha256) throw ExternalFileConflictException(onDisk)
            writeText(target, content)
            load(target)
        }
    }

    suspend fun rename(uri: String, newFileName: String, settings: AppSettings): DiaryEditorDocument =
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val root = settings.diaryTreeUri?.let(::tree)
                    ?: error("请先在设置中选择日记目录")
                val sourceUri = Uri.parse(uri)
                val source = DocumentFile.fromSingleUri(context, sourceUri)
                    ?: error("找不到要重命名的日记文件")
                val currentName = source.name ?: error("无法读取当前日记文件名")
                val targetName = DiaryTextUtils.normalizeMarkdownFileName(newFileName)

                if (currentName == targetName) {
                    return@withContext load(sourceUri)
                }

                val duplicate = root.listFiles().firstOrNull { candidate ->
                    candidate.uri != sourceUri && candidate.name.equals(targetName, ignoreCase = true)
                }
                require(duplicate == null) { "目录中已存在同名日记：$targetName" }

                var directFailure: Throwable? = null
                val renamedUri = try {
                    DocumentsContract.renameDocument(resolver, sourceUri, targetName)
                } catch (error: Exception) {
                    directFailure = error
                    null
                } ?: run {
                    val fallbackSucceeded = runCatching { source.renameTo(targetName) }
                        .onFailure { directFailure = it }
                        .getOrDefault(false)
                    if (!fallbackSucceeded) {
                        throw IllegalStateException(
                            "重命名失败，存储服务拒绝了文件名：$targetName",
                            directFailure,
                        )
                    }
                    root.listFiles().firstOrNull { it.name == targetName }?.uri ?: source.uri
                }

                // Some providers return a new document URI after rename, while others retain the
                // original URI. Resolve the document from both places before reporting success.
                val renamedFile = DocumentFile.fromSingleUri(context, renamedUri)
                    ?.takeIf { it.exists() && it.name.equals(targetName, ignoreCase = true) }
                    ?: root.listFiles().firstOrNull { it.name.equals(targetName, ignoreCase = true) }
                    ?: error("文件可能已重命名，但存储服务没有返回可访问的新文件")
                val renamedDocument = load(renamedFile.uri)
                updateIndexAfterRename(uri, renamedDocument)
                renamedDocument
            }
        }

    private suspend fun updateIndexAfterRename(oldUri: String, document: DiaryEditorDocument) {
        val date = extractDate(document.name, document.lastModified)
        val renamedIndex = DiaryIndexEntity(
            uri = document.uri,
            name = document.name,
            title = markdownStem(document.name),
            dateIso = date.toString(),
            monthKey = "%04d.%02d".format(Locale.ROOT, date.year, date.monthValue),
            lastModified = document.lastModified,
            size = document.size,
            wordCount = DiaryTextUtils.wordCount(document.content),
            sha256 = document.sha256,
            indexedAt = System.currentTimeMillis(),
        )
        val preserved = indexDao.getAll().filterNot { it.uri == oldUri || it.uri == document.uri }
        indexDao.replaceAfterSuccessfulScan(preserved + renamedIndex)
    }

    suspend fun delete(uri: String, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext false
        val document = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return@withContext false
        val originalName = document.name ?: return@withContext false
        val trashRoot = root.findFile(TRASH_DIRECTORY) ?: root.createDirectory(TRASH_DIRECTORY)
            ?: error("无法创建日记回收站目录")
        val storedName = "${System.currentTimeMillis()}__$originalName"
        val backup = trashRoot.createFile("application/octet-stream", storedName)
            ?: error("无法在回收站中创建备份")
        runCatching {
            val bytes = readBytes(document.uri)
            resolver.openOutputStream(backup.uri, "w").use { output ->
                requireNotNull(output) { "无法写入日记回收站" }
                output.write(bytes)
            }
            require(readBytes(backup.uri).contentEquals(bytes)) { "回收站备份校验失败" }
            require(document.delete()) { "原日记无法删除，文件已保持不变" }
            true
        }.onFailure { backup.delete() }.getOrThrow()
    }

    suspend fun scanTrash(settings: AppSettings): List<DiaryTrashItem> = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext emptyList()
        val currentTrash = root.findFile(TRASH_DIRECTORY)?.listFiles().orEmpty()
            .filter { it.isFile }
            .map { file ->
                val storedName = file.name.orEmpty()
                DiaryTrashItem(
                    uri = file.uri.toString(),
                    originalName = storedName.substringAfter("__", storedName),
                    deletedAt = storedName.substringBefore("__").toLongOrNull() ?: file.lastModified(),
                )
            }
        val legacyTrash = root.listFiles()
            .filter { it.isFile && it.name?.endsWith(".$TRASH_SUFFIX", ignoreCase = true) == true }
            .map { file ->
                DiaryTrashItem(
                    uri = file.uri.toString(),
                    originalName = file.name.orEmpty().removeSuffix(".$TRASH_SUFFIX"),
                    deletedAt = file.lastModified(),
                )
            }
        (currentTrash + legacyTrash).sortedByDescending { it.deletedAt }
    }

    suspend fun restore(uri: String, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        val root = settings.diaryTreeUri?.let(::tree) ?: return@withContext false
        val document = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return@withContext false
        val storedName = document.name.orEmpty()
        val legacy = storedName.endsWith(".$TRASH_SUFFIX", ignoreCase = true)
        val original = if (legacy) storedName.removeSuffix(".$TRASH_SUFFIX") else storedName.substringAfter("__", storedName)
        var candidate = original
        var sequence = 2
        while (root.listFiles().any { it.name.equals(candidate, ignoreCase = true) }) {
            val extension = original.substringAfterLast('.', "md")
            val stem = original.removeSuffix(".$extension")
            candidate = "$stem (恢复 $sequence).$extension"
            sequence++
        }
        if (legacy) {
            document.renameTo(candidate)
        } else {
            val restored = root.createFile("text/markdown", candidate) ?: return@withContext false
            runCatching {
                val bytes = readBytes(document.uri)
                resolver.openOutputStream(restored.uri, "w").use { output ->
                    requireNotNull(output) { "无法恢复日记" }
                    output.write(bytes)
                }
                require(readBytes(restored.uri).contentEquals(bytes)) { "恢复后的日记校验失败" }
                require(document.delete()) { "日记已恢复，但回收站副本无法移除" }
                true
            }.onFailure { restored.delete() }.getOrThrow()
        }
    }

    suspend fun permanentlyDelete(uri: String): Boolean = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(context, Uri.parse(uri)) ?: return@withContext false
        val name = document.name.orEmpty()
        val isTrash = name.endsWith(".$TRASH_SUFFIX", ignoreCase = true) ||
            name.substringBefore("__").toLongOrNull() != null
        if (!isTrash) return@withContext false
        document.delete()
    }

    suspend fun importImage(
        sourceUri: Uri,
        category: String?,
        settings: AppSettings,
        date: LocalDate = LocalDate.now(),
    ): ImportedMedia = withContext(Dispatchers.IO) {
        val root = settings.mediaTreeUri?.let(::tree) ?: error("请先在设置中选择媒体目录")
        val mime = resolver.getType(sourceUri) ?: "image/jpeg"
        val extension = inferExtension(sourceUri, mime)
        val categoryText = category?.takeIf(String::isNotBlank) ?: "图片"
        val dateText = date.toString()
        val fileName = DiaryTextUtils.nextMediaFileName(
            pattern = settings.imageNamePattern,
            dateText = dateText,
            category = categoryText,
            extension = extension,
            existingNames = root.listFiles().mapNotNull { it.name },
        )

        val created = root.createFile(mime, fileName) ?: error("无法创建媒体文件")
        runCatching {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                resolver.openOutputStream(created.uri, "w").use { output ->
                    requireNotNull(output) { "无法写入媒体目录" }
                    input.copyTo(output)
                }
            }
        }.onFailure { created.delete() }.getOrThrow()

        val actualName = created.name ?: fileName
        ImportedMedia(
            documentUri = created.uri.toString(),
            fileName = actualName,
            markdown = "![$categoryText](<${actualName.replace(">", "%3E")}>)",
        )
    }

    suspend fun resolveMedia(markdownTarget: String, settings: AppSettings): Uri? = withContext(Dispatchers.IO) {
        val root = settings.mediaTreeUri?.let(::tree) ?: return@withContext null
        val fileName = Uri.decode(markdownTarget.trim('<', '>').substringAfterLast('/'))
        root.listFiles().firstOrNull { it.name == fileName }?.uri
    }

    fun hasPersistedAccess(uri: String?): Boolean {
        if (uri == null) return false
        return resolver.persistedUriPermissions.any {
            it.uri.toString() == uri && it.isReadPermission && it.isWritePermission
        }
    }

    private fun tree(raw: String): DocumentFile =
        DocumentFile.fromTreeUri(context, Uri.parse(raw)) ?: error("目录授权已失效，请重新选择")

    private fun load(uri: Uri): DiaryEditorDocument {
        val document = DocumentFile.fromSingleUri(context, uri) ?: error("日记文件不存在")
        val bytes = readBytes(uri)
        return DiaryEditorDocument(
            uri = uri.toString(),
            name = document.name.orEmpty(),
            content = bytes.toString(Charsets.UTF_8),
            lastModified = document.lastModified(),
            size = document.length(),
            sha256 = DiaryTextUtils.sha256(bytes),
        )
    }

    private fun readText(uri: Uri): String = readBytes(uri).toString(Charsets.UTF_8)

    private fun readBytes(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取文件" }
            input.copyTo(output)
        }
        return output.toByteArray()
    }

    private fun writeText(uri: Uri, content: String) {
        val stream = resolver.openOutputStream(uri, "rwt") ?: resolver.openOutputStream(uri, "wt")
        stream.use { output ->
            requireNotNull(output) { "无法写入文件" }
            output.write(content.toByteArray())
            output.flush()
        }
    }

    private fun inferExtension(uri: Uri, mime: String): String {
        val byMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        if (!byMime.isNullOrBlank()) return byMime.lowercase(Locale.ROOT)
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        return displayName?.substringAfterLast('.', "jpg")?.lowercase(Locale.ROOT) ?: "jpg"
    }

    private fun extractDate(name: String, modified: Long): LocalDate {
        DATE_REGEX.find(name)?.value?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()?.let { return it }
        }
        val instant = if (modified > 0) Instant.ofEpochMilli(modified) else Instant.now()
        return instant.atZone(ZoneId.systemDefault()).toLocalDate()
    }

    private fun markdownStem(name: String): String =
        if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name

    private fun formatDate(date: LocalDate, pattern: String, fallback: String): String = try {
        date.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    } catch (_: IllegalArgumentException) {
        date.format(DateTimeFormatter.ofPattern(fallback, Locale.getDefault()))
    } catch (_: DateTimeParseException) {
        date.toString()
    }

    companion object {
        private val DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
        private const val TRASH_SUFFIX = "deskcubby-trash"
        private const val TRASH_DIRECTORY = ".DeskCubby Trash"
    }
}
