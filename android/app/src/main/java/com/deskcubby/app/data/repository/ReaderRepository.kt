package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
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

enum class ReaderBackground { WHITE, PAPER, SEPIA, GREEN, NIGHT, CUSTOM }

enum class ReaderChapterDetectionMode { SMART, CUSTOM, SMART_AND_CUSTOM }

data class ReaderPreferences(
    val background: ReaderBackground = ReaderBackground.PAPER,
    val customBackgroundArgb: Int = 0xFFF4F0E6.toInt(),
    val fontSizeSp: Float = 19f,
    val lineHeightMultiplier: Float = 1.6f,
    val paragraphSpacingDp: Float = 10f,
    val orientation: ReaderOrientation = ReaderOrientation.FOLLOW_SYSTEM,
    val chapterDetectionMode: ReaderChapterDetectionMode =
        ReaderChapterDetectionMode.SMART_AND_CUSTOM,
    val customChapterRegex: String = "",
    val chapterHeadingMaxChars: Int = 160,
)

data class ReaderBook(
    val id: String,
    val uri: String,
    val title: String,
    val type: ReaderBookType,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val textParagraphIndex: Int = 0,
    /** -1 is used only while migrating a schema-v1 paragraph-only progress record. */
    val textPageIndex: Int = 0,
    val pdfPageIndex: Int = 0,
)

data class ReaderLibraryState(
    val books: List<ReaderBook> = emptyList(),
    val preferences: ReaderPreferences = ReaderPreferences(),
)

enum class ReaderStorageIssue { STATE_FILE_DAMAGED, COMMIT_FAILED }

sealed interface ReaderContent {
    data class TextBook(
        val pages: List<ReaderTextPage>,
        val chapters: List<ReaderChapter>,
    ) : ReaderContent
    data class PdfBook(val pageCount: Int) : ReaderContent
}

data class ReaderTextPage(
    val text: String,
    val firstParagraphIndex: Int,
)

data class ReaderChapter(
    val title: String,
    val pageIndex: Int,
    val paragraphIndex: Int,
)

internal data class ReaderTextLayout(
    val pages: List<ReaderTextPage>,
    val chapters: List<ReaderChapter>,
)

@Singleton
class ReaderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val file = File(directory, STATE_FILE_NAME)
    private val pendingFile = File(directory, "$STATE_FILE_NAME.pending")
    private val mutex = Mutex()
    private val _state = MutableStateFlow(ReaderLibraryState())
    val state: StateFlow<ReaderLibraryState> = _state.asStateFlow()
    private val _storageIssue = MutableStateFlow<ReaderStorageIssue?>(null)
    val storageIssue: StateFlow<ReaderStorageIssue?> = _storageIssue.asStateFlow()
    private var initialized = false

    /** Loads the bounded library JSON off the main thread. Safe to call more than once. */
    suspend fun initialize() = mutex.withLock {
        withContext(Dispatchers.IO) { ensureInitializedLocked() }
    }

    suspend fun import(uri: Uri): ReaderBook = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareForMutationLocked()
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
                ReaderBookType.TXT -> readText(uri, _state.value.preferences)
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
            ReaderBookType.TXT -> readText(uri, _state.value.preferences).let { layout ->
                ReaderContent.TextBook(layout.pages, layout.chapters)
            }
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

    suspend fun saveTextProgress(bookId: String, pageIndex: Int, paragraphIndex: Int) =
        updateBook(bookId) { book ->
        book.copy(
            textPageIndex = pageIndex.coerceIn(0, MAX_TEXT_PAGES - 1),
            textParagraphIndex = paragraphIndex.coerceIn(0, MAX_PARAGRAPHS - 1),
        )
    }

    suspend fun savePdfProgress(bookId: String, pageIndex: Int) = updateBook(bookId) { book ->
        book.copy(pdfPageIndex = pageIndex.coerceAtLeast(0))
    }

    suspend fun updatePreferences(value: ReaderPreferences) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareForMutationLocked()
            val updated = _state.value.copy(preferences = normalizeReaderPreferences(value))
            writeVerified(updated)
            _state.value = updated
        }
    }

    suspend fun remove(bookId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareForMutationLocked()
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
                prepareForMutationLocked()
                if (_state.value.books.none { it.id == bookId }) return@withContext
                val updated = _state.value.copy(
                    books = _state.value.books.map { if (it.id == bookId) transform(it) else it }
                        .sortedByDescending(ReaderBook::lastOpenedAt),
                )
                writeVerified(updated)
                _state.value = updated
            }
        }

    private fun readText(uri: Uri, preferences: ReaderPreferences): ReaderTextLayout {
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
        val decoded = decodeReaderText(bytes).replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = decoded.lineSequence()
            .map(String::trimEnd)
            .filter(String::isNotBlank)
            .take(MAX_PARAGRAPHS + 1)
            .toList()
        require(paragraphs.size <= MAX_PARAGRAPHS) { "TXT 段落数量过多" }
        return paginateReaderText(
            paragraphs = paragraphs.ifEmpty { listOf("") },
            preferences = preferences,
        )
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

    private fun ensureInitializedLocked() {
        if (initialized) return
        when (val committed = readStateCandidate(file)) {
            StateCandidate.Missing -> when (val pending = readStateCandidate(pendingFile)) {
                StateCandidate.Missing -> _state.value = ReaderLibraryState()
                StateCandidate.Invalid -> _storageIssue.value = ReaderStorageIssue.STATE_FILE_DAMAGED
                is StateCandidate.Valid -> {
                    val recovered = runCatching {
                        Os.rename(pendingFile.absolutePath, file.absolutePath)
                        ReaderStateCodec.decode(readBoundedStateFile(file)) == pending.state
                    }.getOrDefault(false)
                    if (recovered) {
                        _state.value = pending.state
                    } else {
                        _storageIssue.value = ReaderStorageIssue.STATE_FILE_DAMAGED
                    }
                }
            }
            StateCandidate.Invalid -> {
                // Never turn damaged data into an apparently writable empty library. Keep both
                // the committed file and any staging file untouched for a future recovery path.
                _storageIssue.value = ReaderStorageIssue.STATE_FILE_DAMAGED
            }
            is StateCandidate.Valid -> {
                _state.value = committed.state
                // A committed valid file remains authoritative. A stale staging file belongs to
                // an operation that never reported success and is safe to discard best-effort.
                runCatching { if (pendingFile.exists()) pendingFile.delete() }
            }
        }
        initialized = true
    }

    private fun prepareForMutationLocked() {
        ensureInitializedLocked()
        check(_storageIssue.value == null) { "Reader state is not writable" }
    }

    private sealed interface StateCandidate {
        data object Missing : StateCandidate
        data object Invalid : StateCandidate
        data class Valid(val state: ReaderLibraryState) : StateCandidate
    }

    private fun readStateCandidate(target: File): StateCandidate {
        if (!target.exists()) return StateCandidate.Missing
        if (!target.isFile || target.length() !in 1..MAX_STATE_BYTES) return StateCandidate.Invalid
        return runCatching { ReaderStateCodec.decode(readBoundedStateFile(target)) }
            .fold(
                onSuccess = StateCandidate::Valid,
                onFailure = { StateCandidate.Invalid },
            )
    }

    private fun readBoundedStateFile(target: File): String {
        val bytes = FileInputStream(target).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_STATE_BYTES) { "Reader state is too large" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeVerified(value: ReaderLibraryState) {
        check(directory.isDirectory || directory.mkdirs()) { "Could not prepare reader storage" }
        val encoded = ReaderStateCodec.encode(value)
        val encodedBytes = encoded.toByteArray(Charsets.UTF_8)
        require(encodedBytes.size <= MAX_STATE_BYTES)
        check(ReaderStateCodec.decode(encoded) == value) { "Reader state encoding failed" }
        FileOutputStream(pendingFile).use { output ->
            output.write(encodedBytes)
            output.fd.sync()
        }
        check(ReaderStateCodec.decode(readBoundedStateFile(pendingFile)) == value) {
            "Reader state verification failed"
        }
        // POSIX rename on Android is an atomic same-directory replacement. If it fails, keep the
        // previously committed JSON intact instead of falling back to a destructive overwrite.
        try {
            Os.rename(pendingFile.absolutePath, file.absolutePath)
            check(ReaderStateCodec.decode(readBoundedStateFile(file)) == value) {
                "Reader state commit verification failed"
            }
        } catch (error: Exception) {
            // Keep a verified pending file available and block later writes from replacing it.
            _storageIssue.value = ReaderStorageIssue.COMMIT_FAILED
            throw error
        }
    }

    companion object {
        const val DIRECTORY_NAME = "reader"
        const val STATE_FILE_NAME = "reader-state-v1.json"
        internal const val MAX_STATE_BYTES = 2 * 1024 * 1024
        private const val MAX_TEXT_BYTES = 32 * 1024 * 1024
        private const val MAX_PARAGRAPHS = 250_000
        internal const val MAX_TEXT_PAGES = 50_000
        private const val MAX_PDF_PAGES = 20_000
        private const val MAX_PDF_WIDTH_PX = 2_048
        private const val MAX_PDF_PIXELS = 8_000_000L
    }
}

internal fun normalizeReaderPreferences(value: ReaderPreferences): ReaderPreferences = value.copy(
    customBackgroundArgb = value.customBackgroundArgb or 0xFF000000.toInt(),
    fontSizeSp = value.fontSizeSp.takeIf(Float::isFinite)?.coerceIn(12f, 38f) ?: 19f,
    lineHeightMultiplier = value.lineHeightMultiplier.takeIf(Float::isFinite)
        ?.coerceIn(1f, 2.4f) ?: 1.6f,
    paragraphSpacingDp = value.paragraphSpacingDp.takeIf(Float::isFinite)
        ?.coerceIn(0f, 36f) ?: 10f,
    customChapterRegex = value.customChapterRegex.trim().take(MAX_READER_CUSTOM_REGEX_CHARS),
    chapterHeadingMaxChars = value.chapterHeadingMaxChars.coerceIn(
        MIN_READER_CHAPTER_HEADING_CHARS,
        MAX_READER_CHAPTER_TITLE_CHARS,
    ),
)

internal const val READER_TEXT_PAGE_TARGET_CHARS = 1_800

/**
 * Builds deterministic logical TXT pages and a best-effort chapter index without changing the
 * original file. Chapter headings start a fresh logical page so drawer jumps are stable even when
 * font size or device width changes.
 */
internal fun paginateReaderText(
    paragraphs: List<String>,
    targetChars: Int = READER_TEXT_PAGE_TARGET_CHARS,
    preferences: ReaderPreferences = ReaderPreferences(),
): ReaderTextLayout {
    require(targetChars in 200..20_000)
    val source = paragraphs.ifEmpty { listOf("") }
    val detectionPreferences = normalizeReaderPreferences(preferences)
    val customChapterRegex = detectionPreferences.customChapterRegex
        .takeIf(String::isNotBlank)
        ?.let { runCatching { Regex(it) }.getOrNull() }
    val pages = ArrayList<ReaderTextPage>()
    val chapters = ArrayList<ReaderChapter>()
    val buffer = StringBuilder(targetChars + 128)
    var firstParagraphIndex = 0

    fun flush() {
        if (buffer.isEmpty()) return
        check(pages.size < ReaderRepository.MAX_TEXT_PAGES) { "TXT logical page count is too large" }
        pages += ReaderTextPage(buffer.toString(), firstParagraphIndex)
        buffer.setLength(0)
    }

    source.forEachIndexed { paragraphIndex, rawParagraph ->
        val paragraph = rawParagraph.trimEnd()
        val chapterTitle = paragraph.trim().takeIf { value ->
            isReaderChapterHeading(value, detectionPreferences, customChapterRegex)
        }
        if (chapterTitle != null) {
            flush()
            if (chapters.size < MAX_READER_CHAPTERS) {
                chapters += ReaderChapter(
                    title = chapterTitle.take(MAX_READER_CHAPTER_TITLE_CHARS),
                    pageIndex = pages.size,
                    paragraphIndex = paragraphIndex,
                )
            }
        }

        var remaining = paragraph
        var firstChunk = true
        do {
            if (buffer.isEmpty()) firstParagraphIndex = paragraphIndex
            val separatorLength = if (buffer.isEmpty()) 0 else 2
            val available = targetChars - buffer.length - separatorLength
            if (available <= 0 || remaining.length > available && buffer.isNotEmpty()) {
                flush()
                continue
            }
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            if (remaining.length <= targetChars) {
                buffer.append(remaining)
                remaining = ""
            } else {
                val splitAt = safeReaderTextBoundary(remaining, targetChars)
                buffer.append(remaining.substring(0, splitAt))
                remaining = remaining.substring(splitAt).trimStart()
                flush()
            }
            firstChunk = false
        } while (remaining.isNotEmpty() || firstChunk)
    }
    flush()
    if (pages.isEmpty()) pages += ReaderTextPage("", 0)
    return ReaderTextLayout(pages, chapters)
}

internal fun textPageForParagraph(
    pages: List<ReaderTextPage>,
    paragraphIndex: Int,
): Int {
    if (pages.isEmpty()) return 0
    val target = paragraphIndex.coerceAtLeast(0)
    val match = pages.binarySearchBy(target) { it.firstParagraphIndex }
    return if (match >= 0) {
        // A single long paragraph can span several logical pages; resume at its first page when
        // migrating schema-v1 progress because the old state had no finer offset.
        pages.indexOfFirst { it.firstParagraphIndex == target }.coerceAtLeast(0)
    } else {
        (-match - 2).coerceIn(0, pages.lastIndex)
    }
}

internal fun isReaderChapterHeading(
    raw: String,
    preferences: ReaderPreferences = ReaderPreferences(),
): Boolean {
    val normalizedPreferences = normalizeReaderPreferences(preferences)
    val value = raw.trim()
    val customRegex = normalizedPreferences.customChapterRegex
        .takeIf(String::isNotBlank)
        ?.let { runCatching { Regex(it) }.getOrNull() }
    return isReaderChapterHeading(value, normalizedPreferences, customRegex)
}

private fun isReaderChapterHeading(
    value: String,
    preferences: ReaderPreferences,
    customRegex: Regex?,
): Boolean {
    if (value.isEmpty() || value.length > preferences.chapterHeadingMaxChars) return false
    val smartEnabled = preferences.chapterDetectionMode !=
        ReaderChapterDetectionMode.CUSTOM
    val customEnabled = preferences.chapterDetectionMode !=
        ReaderChapterDetectionMode.SMART
    val smartMatch = smartEnabled && SMART_READER_CHAPTER_REGEXES.any { it.matches(value) }
    val customMatch = customEnabled && customRegex?.matches(value) == true
    return smartMatch || customMatch
}

internal fun isValidReaderChapterRegex(value: String): Boolean {
    val normalized = value.trim()
    if (normalized.isEmpty()) return true
    if (normalized.length > MAX_READER_CUSTOM_REGEX_CHARS) return false
    return runCatching { Regex(normalized) }.isSuccess
}

private fun safeReaderTextBoundary(value: String, requested: Int): Int {
    var boundary = requested.coerceIn(1, value.length)
    if (boundary < value.length && Character.isLowSurrogate(value[boundary]) &&
        Character.isHighSurrogate(value[boundary - 1])
    ) {
        boundary -= 1
    }
    return boundary.coerceAtLeast(1)
}

private const val MAX_READER_CHAPTERS = 20_000
internal const val MAX_READER_CHAPTER_TITLE_CHARS = 240
internal const val MIN_READER_CHAPTER_HEADING_CHARS = 20
internal const val MAX_READER_CUSTOM_REGEX_CHARS = 1_024
private val CHINESE_READER_CHAPTER_REGEX = Regex(
    "^(?:正文\\s*)?第[0-9０-９零〇○一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+[章节卷回部篇集幕](?:\\s+|[:：、.．_-]?)[^\\n]*$",
)
private val ENGLISH_READER_CHAPTER_REGEX = Regex(
    "^(?:chapter|part|book|section|episode)\\s+(?:[0-9０-９]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)(?:\\s*[:：.\\-]?\\s*.*)?$",
    RegexOption.IGNORE_CASE,
)
private val NUMBERED_READER_CHAPTER_REGEX = Regex(
    "^(?:[0-9０-９]{1,5}(?:[.．、]|\\s+-\\s+)|[一二三四五六七八九十百千万]+、)\\s*\\S.*$",
)
private val SPECIAL_READER_CHAPTER_REGEX = Regex(
    "^(?:[【\\[])?(?:序章|序言|前言|楔子|引子|终章|尾声|后记|番外(?:篇)?|上卷|中卷|下卷|prologue|epilogue|preface|introduction|afterword)(?:[】\\]])?(?:\\s*[:：.、\\-]?\\s*.*)?$",
    RegexOption.IGNORE_CASE,
)
private val VOLUME_READER_CHAPTER_REGEX = Regex(
    "^(?:卷|部|篇|集)\\s*[0-9０-９零〇○一二三四五六七八九十百千万两]+(?:\\s*[:：.、\\-]?\\s*.*)?$",
)
private val MARKDOWN_READER_CHAPTER_REGEX = Regex("^#{1,6}\\s+\\S.*$")
private val BRACKETED_READER_CHAPTER_REGEX = Regex(
    "^[【\\[](?:第[0-9０-９零〇○一二三四五六七八九十百千万两]+[章节卷回部篇集幕]|chapter\\s+[^】\\]]+)[】\\]](?:\\s*.*)?$",
    RegexOption.IGNORE_CASE,
)
private val SMART_READER_CHAPTER_REGEXES = listOf(
    CHINESE_READER_CHAPTER_REGEX,
    ENGLISH_READER_CHAPTER_REGEX,
    NUMBERED_READER_CHAPTER_REGEX,
    SPECIAL_READER_CHAPTER_REGEX,
    VOLUME_READER_CHAPTER_REGEX,
    MARKDOWN_READER_CHAPTER_REGEX,
    BRACKETED_READER_CHAPTER_REGEX,
)

internal fun decodeReaderText(bytes: ByteArray): String {
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

internal object ReaderStateCodec {
    private const val SCHEMA_VERSION = 3
    private const val MAX_BOOKS = 500
    private val idPattern = Regex("[A-Za-z0-9._:-]{1,256}")

    fun encode(value: ReaderLibraryState): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put(
            "preferences",
            JSONObject()
                .put("background", value.preferences.background.name)
                .put("customBackgroundArgb", value.preferences.customBackgroundArgb)
                .put("fontSizeSp", value.preferences.fontSizeSp.toDouble())
                .put("lineHeightMultiplier", value.preferences.lineHeightMultiplier.toDouble())
                .put("paragraphSpacingDp", value.preferences.paragraphSpacingDp.toDouble())
                .put("orientation", value.preferences.orientation.name)
                .put("chapterDetectionMode", value.preferences.chapterDetectionMode.name)
                .put("customChapterRegex", value.preferences.customChapterRegex)
                .put("chapterHeadingMaxChars", value.preferences.chapterHeadingMaxChars),
        )
        .put(
            "books",
            JSONArray().apply {
                value.books.forEach { book ->
                    put(
                        JSONObject()
                            .put("id", book.id)
                            .put("uri", book.uri)
                            .put("title", book.title)
                            .put("type", book.type.name)
                            .put("addedAt", book.addedAt)
                            .put("lastOpenedAt", book.lastOpenedAt)
                            .put("textParagraphIndex", book.textParagraphIndex)
                            .put("textPageIndex", book.textPageIndex)
                            .put("pdfPageIndex", book.pdfPageIndex),
                    )
                }
            },
        )
        .toString()

    fun decode(raw: String): ReaderLibraryState {
        require(raw.toByteArray(Charsets.UTF_8).size <= ReaderRepository.MAX_STATE_BYTES)
        val root = JSONObject(raw)
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion in 1..SCHEMA_VERSION)
        val preferencesJson = root.getJSONObject("preferences")
        val preferences = normalizeReaderPreferences(
            ReaderPreferences(
                background = enumValueOr(
                    preferencesJson.getString("background"),
                    ReaderBackground.PAPER,
                ),
                customBackgroundArgb = if (schemaVersion >= 2) {
                    preferencesJson.getInt("customBackgroundArgb")
                } else {
                    ReaderPreferences().customBackgroundArgb
                },
                fontSizeSp = preferencesJson.getDouble("fontSizeSp").toFloat(),
                lineHeightMultiplier = preferencesJson.getDouble("lineHeightMultiplier").toFloat(),
                paragraphSpacingDp = preferencesJson.getDouble("paragraphSpacingDp").toFloat(),
                orientation = enumValueOr(
                    preferencesJson.getString("orientation"),
                    ReaderOrientation.FOLLOW_SYSTEM,
                ),
                chapterDetectionMode = if (schemaVersion >= 3) {
                    enumValueOr(
                        preferencesJson.getString("chapterDetectionMode"),
                        ReaderChapterDetectionMode.SMART_AND_CUSTOM,
                    )
                } else {
                    ReaderChapterDetectionMode.SMART_AND_CUSTOM
                },
                customChapterRegex = if (schemaVersion >= 3) {
                    preferencesJson.getString("customChapterRegex")
                        .take(MAX_READER_CUSTOM_REGEX_CHARS)
                } else {
                    ""
                },
                chapterHeadingMaxChars = if (schemaVersion >= 3) {
                    preferencesJson.getInt("chapterHeadingMaxChars")
                } else {
                    ReaderPreferences().chapterHeadingMaxChars
                },
            ),
        )
        val booksJson = root.getJSONArray("books")
        require(booksJson.length() <= MAX_BOOKS)
        val ids = hashSetOf<String>()
        val uris = hashSetOf<String>()
        val books = buildList {
            repeat(booksJson.length()) { index ->
                val item = booksJson.getJSONObject(index)
                val id = item.getString("id")
                val uri = item.getString("uri")
                val title = item.getString("title")
                require(ids.add(id) && idPattern.matches(id))
                require(uri.startsWith("content://") && uri.length <= 8_192 && uris.add(uri))
                require(title.length <= 240)
                add(
                    ReaderBook(
                        id = id,
                        uri = uri,
                        title = title,
                        type = enumValueOr(item.getString("type"), ReaderBookType.TXT),
                        addedAt = item.getLong("addedAt").coerceAtLeast(0L),
                        lastOpenedAt = item.getLong("lastOpenedAt").coerceAtLeast(0L),
                        textParagraphIndex = item.optInt("textParagraphIndex", 0).coerceAtLeast(0),
                        textPageIndex = if (schemaVersion >= 2) {
                            item.getInt("textPageIndex")
                                .coerceIn(-1, ReaderRepository.MAX_TEXT_PAGES - 1)
                        } else {
                            -1
                        },
                        pdfPageIndex = item.optInt("pdfPageIndex", 0).coerceAtLeast(0),
                    ),
                )
            }
        }.sortedByDescending(ReaderBook::lastOpenedAt)
        return ReaderLibraryState(books, preferences)
    }

    private inline fun <reified T : Enum<T>> enumValueOr(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
