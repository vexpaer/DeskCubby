package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
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
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

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
    private val mediaMutex = Mutex()

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

    suspend fun enterToday(
        settings: AppSettings,
        today: LocalDate = LocalDate.now(),
    ): DiaryEditorDocument = writeMutex.withLock {
        enterTodayUnlocked(settings, today)
    }

    private suspend fun enterTodayUnlocked(
        settings: AppSettings,
        today: LocalDate,
    ): DiaryEditorDocument =
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
            try {
                writeText(created.uri, content)
                load(created.uri)
            } catch (error: Exception) {
                val committed = runCatching { readText(created.uri) == content }.getOrDefault(false)
                if (committed && error !is CancellationException) return@withContext load(created.uri)
                if (!committed) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { created.delete() }
                    }
                }
                throw error
            }
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
    ): ImportedMedia = mediaMutex.withLock {
        var created: DocumentFile? = null
        var compressedFile: File? = null
        try {
            withContext(Dispatchers.IO) {
                val root = settings.mediaTreeUri?.let(::tree) ?: error("请先在设置中选择媒体目录")
                val sourceMime = resolver.getType(sourceUri) ?: "image/jpeg"
                val sourceExtension = inferExtension(sourceUri, sourceMime)
                val shouldCompress = settings.mealImageCompressionEnabled && !category.isNullOrBlank()
                compressedFile = if (shouldCompress && isCompressibleImage(sourceMime, sourceExtension)) {
                    compressMealImageToCache(sourceUri, settings.mealImageCompressionQuality)
                } else {
                    null
                }
                val mime = if (compressedFile != null) "image/jpeg" else sourceMime
                val extension = if (compressedFile != null) "jpg" else sourceExtension
                val categoryText = category?.takeIf(String::isNotBlank) ?: "图片"
                val dateText = date.toString()
                val fileName = DiaryTextUtils.nextMediaFileName(
                    pattern = settings.imageNamePattern,
                    dateText = dateText,
                    category = categoryText,
                    extension = extension,
                    existingNames = root.listFiles().mapNotNull { it.name },
                )

                val destination = root.createFile(mime, fileName) ?: error("无法创建媒体文件")
                created = destination
                val inputStream = compressedFile?.inputStream() ?: resolver.openInputStream(sourceUri)
                inputStream.use { input ->
                    requireNotNull(input) { "无法读取所选图片" }
                    resolver.openOutputStream(destination.uri, "w").use { output ->
                        requireNotNull(output) { "无法写入媒体目录" }
                        input.copyTo(output)
                        output.flush()
                    }
                }

                val actualName = destination.name ?: fileName
                ImportedMedia(
                    documentUri = destination.uri.toString(),
                    fileName = actualName,
                    markdown = "![$categoryText](<${actualName.replace(">", "%3E")}>)",
                )
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { created?.delete() }
            }
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { compressedFile?.delete() }
            }
        }
    }

    suspend fun appendImageToToday(
        sourceUri: Uri,
        category: String,
        settings: AppSettings,
        date: LocalDate = LocalDate.now(),
    ): ImportedMedia {
        val media = importImage(sourceUri, category, settings, date)
        var diaryUri: Uri? = null
        var originalContent: String? = null
        var updatedContent: String? = null
        return try {
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    val document = enterTodayUnlocked(settings, date)
                    diaryUri = Uri.parse(document.uri)
                    originalContent = document.content
                    val lineBreak = if (
                        document.content.isEmpty() ||
                        document.content.endsWith('\n') ||
                        document.content.endsWith('\r')
                    ) {
                        ""
                    } else {
                        DiaryTextUtils.preferredLineEnding(document.content)
                    }
                    updatedContent = document.content + lineBreak + media.markdown
                    writeText(requireNotNull(diaryUri), requireNotNull(updatedContent))
                    check(readText(requireNotNull(diaryUri)) == updatedContent) {
                        "图片写入今日日记后的校验失败"
                    }
                    media
                }
            }
        } catch (error: Exception) {
            var committed = false
            var safeToDeleteMedia = diaryUri == null
            withContext(NonCancellable + Dispatchers.IO) {
                val target = diaryUri
                val desired = updatedContent
                val original = originalContent
                if (target != null && desired != null && original != null) {
                    val diskContent = runCatching { readText(target) }.getOrNull()
                    if (diskContent == desired) {
                        committed = true
                    } else {
                        val rollback = runCatching {
                            writeText(target, original)
                            check(readText(target) == original) { "今日日记原文恢复校验失败" }
                        }
                        safeToDeleteMedia = rollback.isSuccess
                        rollback.exceptionOrNull()?.let(error::addSuppressed)
                    }
                }
                if (safeToDeleteMedia) {
                    runCatching {
                        DocumentFile.fromSingleUri(context, Uri.parse(media.documentUri))?.delete()
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
            }
            if (committed && error !is CancellationException) return media
            throw error
        }
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

    private fun isCompressibleImage(mime: String, extension: String): Boolean {
        val normalizedMime = mime.lowercase(Locale.ROOT)
        val normalizedExtension = extension.lowercase(Locale.ROOT)
        return normalizedMime in COMPRESSIBLE_IMAGE_MIMES ||
            normalizedExtension in COMPRESSIBLE_IMAGE_EXTENSIONS
    }

    private fun compressMealImageToCache(sourceUri: Uri, quality: Int): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }.getOrElse { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val target = compressedImageSize(bounds.outWidth, bounds.outHeight, COMPRESSED_IMAGE_MAX_EDGE_PX)
        val options = BitmapFactory.Options().apply {
            inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, target)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching {
            resolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "无法读取所选图片" }
                BitmapFactory.decodeStream(input, null, options)
            }
        }.getOrNull() ?: return null

        var bitmap = decoded
        var tempFile: File? = null
        return try {
            // Scale before applying EXIF rotation so a large source and a same-sized rotated copy
            // are never held at the same time. This keeps peak memory bounded on camera photos.
            val scaledTarget = compressedImageSize(bitmap.width, bitmap.height, COMPRESSED_IMAGE_MAX_EDGE_PX)
            if (bitmap.width != scaledTarget.width || bitmap.height != scaledTarget.height) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    scaledTarget.width,
                    scaledTarget.height,
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            val oriented = applyExifOrientation(bitmap, readExifOrientation(sourceUri))
            if (oriented !== bitmap) {
                bitmap.recycle()
                bitmap = oriented
            }

            if (bitmap.hasAlpha()) {
                val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(flattened).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                bitmap.recycle()
                bitmap = flattened
            }

            val directory = File(context.cacheDir, "meal-image-compression").apply {
                check(exists() || mkdirs()) { "无法创建图片压缩缓存" }
            }
            val compressed = File.createTempFile("meal-", ".jpg", directory)
            tempFile = compressed
            compressed.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(30, 95), output)) {
                    "无法压缩饮食图片"
                }
                output.flush()
            }

            val sourceSize = sourceByteSize(sourceUri)
            if (compressed.length() <= 0L || (sourceSize > 0L && compressed.length() >= sourceSize)) {
                compressed.delete()
                null
            } else {
                compressed
            }
        } catch (_: OutOfMemoryError) {
            tempFile?.delete()
            null
        } catch (_: Exception) {
            tempFile?.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun readExifOrientation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun sourceByteSize(uri: Uri): Long = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor -> descriptor.length } ?: -1L
    }.getOrDefault(-1L)

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
        private val COMPRESSIBLE_IMAGE_MIMES = setOf(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/heic",
            "image/heif",
            "image/webp",
            "image/avif",
        )
        private val COMPRESSIBLE_IMAGE_EXTENSIONS = setOf(
            "jpg",
            "jpeg",
            "png",
            "heic",
            "heif",
            "webp",
            "avif",
        )
        private const val COMPRESSED_IMAGE_MAX_EDGE_PX = 2_560
        private const val TRASH_SUFFIX = "deskcubby-trash"
        private const val TRASH_DIRECTORY = ".DeskCubby Trash"
    }
}

internal data class CompressedImageSize(val width: Int, val height: Int)

internal fun compressedImageSize(
    width: Int,
    height: Int,
    maxEdge: Int = 2_560,
): CompressedImageSize {
    require(width > 0 && height > 0 && maxEdge > 0)
    val longestEdge = max(width, height)
    if (longestEdge <= maxEdge) return CompressedImageSize(width, height)
    val scale = maxEdge.toDouble() / longestEdge.toDouble()
    return CompressedImageSize(
        width = (width * scale).roundToInt().coerceAtLeast(1),
        height = (height * scale).roundToInt().coerceAtLeast(1),
    )
}

internal fun imageSampleSize(
    width: Int,
    height: Int,
    target: CompressedImageSize,
): Int {
    require(width > 0 && height > 0 && target.width > 0 && target.height > 0)
    // BitmapFactory samples most efficiently in powers of two. Allowing the decoded edge to be
    // at most 20% above the output target avoids 40-100 MiB intermediate bitmaps; landing a little
    // below the target is preferable to risking an OOM on common 12-48 MP camera photos.
    val targetEdge = max(target.width, target.height)
    val decodedEdgeLimit = (targetEdge * 1.2).roundToInt().coerceAtLeast(targetEdge)
    var sample = 1
    while (max(width, height) / sample > decodedEdgeLimit && sample <= Int.MAX_VALUE / 2) {
        sample *= 2
    }
    return sample
}
