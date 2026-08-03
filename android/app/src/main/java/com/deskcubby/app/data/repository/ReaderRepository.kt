package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ReaderBookType { TXT, PDF }

enum class ReaderOrientation { FOLLOW_SYSTEM, PORTRAIT, LANDSCAPE }

enum class ReaderBackground { WHITE, PAPER, SEPIA, GREEN, NIGHT }

data class ReaderPreferences(
    val background: ReaderBackground = ReaderBackground.PAPER,
    val fontSizeSp: Float = 19f,
    val lineHeightMultiplier: Float = 1.6f,
    val paragraphSpacingDp: Float = 10f,
    val orientation: ReaderOrientation = ReaderOrientation.FOLLOW_SYSTEM,
)

data class ReaderBook(
    val id: String,
    val uri: String,
    val title: String,
    val type: ReaderBookType,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val textParagraphIndex: Int = 0,
    val pdfPageIndex: Int = 0,
)

data class ReaderLibraryState(
    val books: List<ReaderBook> = emptyList(),
    val preferences: ReaderPreferences = ReaderPreferences(),
)

sealed interface ReaderContent {
    data class TextBook(val paragraphs: List<String>) : ReaderContent
    data class PdfBook(val pageCount: Int) : ReaderContent
}

@Singleton
class ReaderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val file = File(directory, STATE_FILE_NAME)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(readStateOrDefault())
    val state: StateFlow<ReaderLibraryState> = _state.asStateFlow()

    suspend fun import(uri: Uri): ReaderBook = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(uri.scheme == ContentResolver.SCHEME_CONTENT) { "Only document URIs are supported" }
            val displayName = queryDisplayName(uri)
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val type = when {
                mime == "application/pdf" || displayName.endsWith(".pdf", ignoreCase = true) ->
                    ReaderBookType.PDF
                mime.startsWith("text/") || displayName.endsWith(".txt", ignoreCase = true) ->
                    ReaderBookType.TXT
                else -> throw IllegalArgumentException("请选择 TXT 或 PDF 文件")
            }
            // Validate that the provider can still be read before adding a library entry.
            when (type) {
                ReaderBookType.TXT -> readText(uri)
                ReaderBookType.PDF -> readPdfPageCount(uri)
            }
            val alreadyPersisted = resolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.uri == uri
            }
            if (!alreadyPersisted) {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val now = System.currentTimeMillis()
            val existing = _state.value.books.firstOrNull { it.uri == uri.toString() }
            val book = existing?.copy(lastOpenedAt = now) ?: ReaderBook(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                title = displayName.substringBeforeLast('.').trim().ifBlank { "Untitled" }.take(240),
                type = type,
                addedAt = now,
                lastOpenedAt = now,
            )
            val updated = _state.value.copy(
                books = (_state.value.books.filterNot { it.id == book.id } + book)
                    .sortedByDescending(ReaderBook::lastOpenedAt),
            )
            try {
                writeVerified(updated)
                _state.value = updated
                book
            } catch (error: Exception) {
                if (!alreadyPersisted) {
                    runCatching {
                        resolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
                throw error
            }
        }
    }

    suspend fun load(book: ReaderBook): ReaderContent = withContext(Dispatchers.IO) {
        val uri = Uri.parse(book.uri)
        when (book.type) {
            ReaderBookType.TXT -> ReaderContent.TextBook(readText(uri))
            ReaderBookType.PDF -> ReaderContent.PdfBook(readPdfPageCount(uri))
        }
    }

    suspend fun renderPdfPage(book: ReaderBook, pageIndex: Int, targetWidthPx: Int): Bitmap =
        withContext(Dispatchers.IO) {
            require(book.type == ReaderBookType.PDF)
            val uri = Uri.parse(book.uri)
            val descriptor = resolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("无法打开 PDF")
            descriptor.use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    require(pageIndex in 0 until renderer.pageCount) { "PDF 页码无效" }
                    renderer.openPage(pageIndex).use { page ->
                        val width = targetWidthPx.coerceIn(320, MAX_PDF_WIDTH_PX)
                        val height = (width.toDouble() * page.height / page.width)
                            .toInt()
                            .coerceAtLeast(1)
                        require(width.toLong() * height <= MAX_PDF_PIXELS) { "PDF 页面尺寸过大" }
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
        }

    suspend fun markOpened(bookId: String) = updateBook(bookId) { book ->
        book.copy(lastOpenedAt = System.currentTimeMillis())
    }

    suspend fun saveTextProgress(bookId: String, paragraphIndex: Int) = updateBook(bookId) { book ->
        book.copy(textParagraphIndex = paragraphIndex.coerceIn(0, MAX_PARAGRAPHS - 1))
    }

    suspend fun savePdfProgress(bookId: String, pageIndex: Int) = updateBook(bookId) { book ->
        book.copy(pdfPageIndex = pageIndex.coerceAtLeast(0))
    }

    suspend fun updatePreferences(value: ReaderPreferences) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val updated = _state.value.copy(preferences = value.normalized())
            writeVerified(updated)
            _state.value = updated
        }
    }

    suspend fun remove(bookId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val removed = _state.value.books.firstOrNull { it.id == bookId } ?: return@withContext
            val updated = _state.value.copy(books = _state.value.books.filterNot { it.id == bookId })
            writeVerified(updated)
            _state.value = updated
            if (updated.books.none { it.uri == removed.uri }) {
                runCatching {
                    resolver.releasePersistableUriPermission(
                        Uri.parse(removed.uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }

    private suspend fun updateBook(bookId: String, transform: (ReaderBook) -> ReaderBook) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (_state.value.books.none { it.id == bookId }) return@withContext
                val updated = _state.value.copy(
                    books = _state.value.books.map { if (it.id == bookId) transform(it) else it }
                        .sortedByDescending(ReaderBook::lastOpenedAt),
                )
                writeVerified(updated)
                _state.value = updated
            }
        }

    private fun readText(uri: Uri): List<String> {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_TEXT_BYTES) { "TXT 文件超过 ${MAX_TEXT_BYTES / 1024 / 1024} MiB 上限" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法打开 TXT")
        val decoded = decodeText(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = decoded.lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .take(MAX_PARAGRAPHS + 1)
            .toList()
        require(paragraphs.size <= MAX_PARAGRAPHS) { "TXT 段落数量过多" }
        return paragraphs.ifEmpty { listOf("") }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("GB18030"))
        }
    }

    private fun readPdfPageCount(uri: Uri): Int {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("无法打开 PDF")
        return descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.pageCount.also { require(it in 1..MAX_PDF_PAGES) { "PDF 页数超过上限" } }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.trim()?.take(255)?.takeIf(String::isNotBlank) ?: "Untitled"

    private fun ReaderPreferences.normalized() = copy(
        fontSizeSp = fontSizeSp.takeIf(Float::isFinite)?.coerceIn(12f, 38f) ?: 19f,
        lineHeightMultiplier = lineHeightMultiplier.takeIf(Float::isFinite)
            ?.coerceIn(1f, 2.4f) ?: 1.6f,
        paragraphSpacingDp = paragraphSpacingDp.takeIf(Float::isFinite)
            ?.coerceIn(0f, 36f) ?: 10f,
    )

    private fun readStateOrDefault(): ReaderLibraryState = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_STATE_BYTES) return@runCatching ReaderLibraryState()
        decodeState(file.readText(Charsets.UTF_8))
    }.getOrDefault(ReaderLibraryState())

    private fun writeVerified(value: ReaderLibraryState) {
        directory.mkdirs()
        val encoded = encodeState(value)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES)
        val pending = File(directory, "$STATE_FILE_NAME.pending")
        FileOutputStream(pending).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(decodeState(pending.readText(Charsets.UTF_8)) == value)
        if (!pending.renameTo(file)) {
            FileOutputStream(file).use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(decodeState(file.readText(Charsets.UTF_8)) == value)
            pending.delete()
        }
    }

    private fun encodeState(value: ReaderLibraryState): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("preferences", JSONObject()
            .put("background", value.preferences.background.name)
            .put("fontSizeSp", value.preferences.fontSizeSp.toDouble())
            .put("lineHeightMultiplier", value.preferences.lineHeightMultiplier.toDouble())
            .put("paragraphSpacingDp", value.preferences.paragraphSpacingDp.toDouble())
            .put("orientation", value.preferences.orientation.name))
        .put("books", JSONArray().apply {
            value.books.forEach { book ->
                put(JSONObject()
                    .put("id", book.id)
                    .put("uri", book.uri)
                    .put("title", book.title)
                    .put("type", book.type.name)
                    .put("addedAt", book.addedAt)
                    .put("lastOpenedAt", book.lastOpenedAt)
                    .put("textParagraphIndex", book.textParagraphIndex)
                    .put("pdfPageIndex", book.pdfPageIndex))
            }
        })
        .toString()

    private fun decodeState(raw: String): ReaderLibraryState {
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION)
        val preferencesJson = root.getJSONObject("preferences")
        val preferences = ReaderPreferences(
            background = enumValueOr(preferencesJson.getString("background"), ReaderBackground.PAPER),
            fontSizeSp = preferencesJson.getDouble("fontSizeSp").toFloat(),
            lineHeightMultiplier = preferencesJson.getDouble("lineHeightMultiplier").toFloat(),
            paragraphSpacingDp = preferencesJson.getDouble("paragraphSpacingDp").toFloat(),
            orientation = enumValueOr(
                preferencesJson.getString("orientation"),
                ReaderOrientation.FOLLOW_SYSTEM,
            ),
        ).normalized()
        val booksJson = root.getJSONArray("books")
        require(booksJson.length() <= MAX_BOOKS)
        val ids = hashSetOf<String>()
        val books = buildList {
            repeat(booksJson.length()) { index ->
                val item = booksJson.getJSONObject(index)
                val id = item.getString("id")
                val uri = item.getString("uri")
                require(ids.add(id) && ID_PATTERN.matches(id))
                require(uri.startsWith("content://") && uri.length <= 8_192)
                add(ReaderBook(
                    id = id,
                    uri = uri,
                    title = item.getString("title").take(240),
                    type = enumValueOr(item.getString("type"), ReaderBookType.TXT),
                    addedAt = item.getLong("addedAt").coerceAtLeast(0L),
                    lastOpenedAt = item.getLong("lastOpenedAt").coerceAtLeast(0L),
                    textParagraphIndex = item.optInt("textParagraphIndex", 0).coerceAtLeast(0),
                    pdfPageIndex = item.optInt("pdfPageIndex", 0).coerceAtLeast(0),
                ))
            }
        }.sortedByDescending(ReaderBook::lastOpenedAt)
        return ReaderLibraryState(books, preferences)
    }

    private inline fun <reified T : Enum<T>> enumValueOr(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    companion object {
        const val DIRECTORY_NAME = "reader"
        const val STATE_FILE_NAME = "reader-state-v1.json"
        private const val SCHEMA_VERSION = 1
        private const val MAX_STATE_BYTES = 2 * 1024 * 1024
        private const val MAX_TEXT_BYTES = 32 * 1024 * 1024
        private const val MAX_PARAGRAPHS = 250_000
        private const val MAX_BOOKS = 500
        private const val MAX_PDF_PAGES = 20_000
        private const val MAX_PDF_WIDTH_PX = 2_048
        private const val MAX_PDF_PIXELS = 8_000_000L
        private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,256}")
    }
}
