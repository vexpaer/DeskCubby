package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

enum class ReaderLibraryLayout { LIST, GRID }

enum class ReaderChapterDetectionMode { SMART, CUSTOM, SMART_AND_CUSTOM }

data class ReaderPreferences(
    val background: ReaderBackground = ReaderBackground.PAPER,
    val customBackgroundArgb: Int = 0xFFF4F0E6.toInt(),
    val fontSizeSp: Float = 19f,
    val lineHeightMultiplier: Float = 1.6f,
    val paragraphSpacingDp: Float = 10f,
    val pdfZoomPercent: Int = 100,
    val orientation: ReaderOrientation = ReaderOrientation.FOLLOW_SYSTEM,
    val libraryLayout: ReaderLibraryLayout = ReaderLibraryLayout.LIST,
    val showGridBookTitles: Boolean = true,
    val showProgressPercentage: Boolean = false,
    val immersiveMode: Boolean = false,
    /** Null follows the foreground chosen for the active reader background. */
    val customForegroundArgb: Int? = null,
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
    /** Persisted SAF image URI. Never convert this value into a filesystem path. */
    val coverUri: String? = null,
    /** Full-file SHA-256 used to match the same book imported through another provider/device. */
    val fingerprint: String? = null,
    /** Logical TXT page count or physical PDF page count; zero means not measured yet. */
    val totalPages: Int = 0,
    val progressUpdatedAt: Long = 0L,
    /**
     * Local-only per-page offset in 5% increments (0, 5, 10, …, 95).
     * The field is reset to zero when book content changes. External export deliberately
     * projects it to zero so encode/decode snapshots remain semantically consistent.
     */
    val pageOffsetPercent: Int = 0,
)

data class ReaderLibraryState(
    val books: List<ReaderBook> = emptyList(),
    val preferences: ReaderPreferences = ReaderPreferences(),
    /** URI-free bounded records retained so sync may arrive before the matching book is imported. */
    val progressLedger: List<ReaderProgressRecord> = emptyList(),
)

data class ReaderProgressRecord(
    val fingerprint: String,
    val type: ReaderBookType,
    val textPageIndex: Int = 0,
    val textParagraphIndex: Int = 0,
    val pdfPageIndex: Int = 0,
    val totalPages: Int = 0,
    val updatedAt: Long = 0L,
    /**
     * Local-only per-page offset in 5% increments (0, 5, 10, …, 95).
     * This field is used for internal merging only; external codecs project it to zero.
     */
    val pageOffsetPercent: Int = 0,
)

data class ReaderProgressImportResult(
    val matchedBooks: Int,
    val updatedBooks: Int,
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

data class ReaderTextSearchMatch(
    val pageIndex: Int,
    val startIndex: Int,
    val endIndex: Int,
)

internal data class ReaderTextLayout(
    val pages: List<ReaderTextPage>,
    val chapters: List<ReaderChapter>,
)

private data class ReaderFingerprintResult(
    val fingerprint: String,
    val byteCount: Long,
)

private data class MeasuredReaderFingerprint(
    val uri: String,
    val fingerprint: String,
)

@Singleton
class ReaderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val file = File(directory, STATE_FILE_NAME)
    private val pendingFile = File(directory, "$STATE_FILE_NAME.pending")
    private val coverDirectory = File(context.cacheDir, COVER_DIRECTORY_NAME)
    private val mutex = Mutex()
    private val coverMutex = Mutex()
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
            // Validate that the provider can still be read before adding a library entry and keep
            // a stable page-count denominator for shelf progress. The fingerprint is best-effort:
            // unusual providers may support a seekable descriptor but not an InputStream.
            val totalPages = when (type) {
                ReaderBookType.TXT -> readText(uri, _state.value.preferences).pages.size
                ReaderBookType.PDF -> readPdfPageCount(uri)
            }
            val measuredFingerprint = fingerprintOrNull(uri, type)
            val alreadyPersisted = resolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.uri == uri
            }
            if (!alreadyPersisted) {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val now = System.currentTimeMillis()
            val existing = _state.value.books.firstOrNull { it.uri == uri.toString() }
            val contentChanged = measuredFingerprint != null &&
                existing?.fingerprint != null &&
                measuredFingerprint != existing.fingerprint
            val book = existing?.copy(
                title = displayName.substringBeforeLast('.').trim().ifBlank { "Untitled" }.take(240),
                type = type,
                lastOpenedAt = now,
                textParagraphIndex = if (contentChanged) 0 else existing.textParagraphIndex,
                textPageIndex = if (contentChanged) 0 else existing.textPageIndex,
                pdfPageIndex = if (contentChanged) 0 else existing.pdfPageIndex,
                pageOffsetPercent = if (contentChanged) 0 else existing.pageOffsetPercent,
                fingerprint = measuredFingerprint ?: existing.fingerprint,
                totalPages = totalPages,
                progressUpdatedAt = if (contentChanged) 0L else existing.progressUpdatedAt,
            ) ?: ReaderBook(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                title = displayName.substringBeforeLast('.').trim().ifBlank { "Untitled" }.take(240),
                type = type,
                addedAt = now,
                lastOpenedAt = now,
                fingerprint = measuredFingerprint,
                totalPages = totalPages,
            )
            val baseState = _state.value.copy(
                books = (_state.value.books.filterNot { it.id == book.id } + book)
                    .sortedByDescending(ReaderBook::lastOpenedAt),
            )
            val updated = mergeReaderProgress(baseState, baseState.progressLedger).state
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
        val content = when (book.type) {
            ReaderBookType.TXT -> readText(uri, _state.value.preferences).let { layout ->
                ReaderContent.TextBook(layout.pages, layout.chapters)
            }
            ReaderBookType.PDF -> ReaderContent.PdfBook(readPdfPageCount(uri))
        }
        val totalPages = when (content) {
            is ReaderContent.TextBook -> content.pages.size
            is ReaderContent.PdfBook -> content.pageCount
        }
        val fingerprint = book.fingerprint ?: fingerprintOrNull(uri, book.type)
        try {
            updateBook(book.id) { current ->
                current.copy(
                    fingerprint = fingerprint ?: current.fingerprint,
                    totalPages = totalPages,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Reading may continue when optional metadata cannot be committed. The repository's
            // storageIssue flow still reports a damaged/failed state to the existing UI.
        }
        content
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

    suspend fun saveTextProgress(
        bookId: String,
        pageIndex: Int,
        paragraphIndex: Int,
        pageOffsetPercent: Int = 0,
    ) = updateBookProgress(bookId) { book ->
        val normalizedPage = pageIndex.coerceIn(0, MAX_TEXT_PAGES - 1)
        val normalizedParagraph = paragraphIndex.coerceIn(0, MAX_PARAGRAPHS - 1)
        val normalizedOffset = normalizePageOffsetPercent(pageOffsetPercent)
        if (book.textParagraphIndex == normalizedParagraph) {
            if (book.textPageIndex == normalizedPage) {
                if (book.pageOffsetPercent == normalizedOffset) book else {
                    book.copy(
                        pageOffsetPercent = normalizedOffset,
                        progressUpdatedAt = nextReaderProgressTimestamp(book.progressUpdatedAt),
                    )
                }
            } else {
                // Recomputing the local logical page for the same canonical paragraph is not
                // a new reading action unless the user also moved within that page.
                book.copy(
                    textPageIndex = normalizedPage,
                    pageOffsetPercent = normalizedOffset,
                    progressUpdatedAt = if (book.pageOffsetPercent != normalizedOffset) {
                        nextReaderProgressTimestamp(book.progressUpdatedAt)
                    } else {
                        book.progressUpdatedAt
                    },
                )
            }
        } else {
            book.copy(
                textPageIndex = normalizedPage,
                textParagraphIndex = normalizedParagraph,
                pageOffsetPercent = normalizedOffset,
                progressUpdatedAt = nextReaderProgressTimestamp(book.progressUpdatedAt),
            )
        }
    }

    suspend fun savePdfProgress(
        bookId: String,
        pageIndex: Int,
        pageOffsetPercent: Int = 0,
    ) = updateBookProgress(bookId) { book ->
        val normalizedPage = pageIndex.coerceIn(
            0,
            (book.totalPages - 1).takeIf { it >= 0 } ?: (MAX_PDF_PAGES - 1),
        )
        val normalizedOffset = normalizePageOffsetPercent(pageOffsetPercent)
        if (book.pdfPageIndex == normalizedPage) {
            if (book.pageOffsetPercent == normalizedOffset) book else {
                book.copy(
                    pageOffsetPercent = normalizedOffset,
                    progressUpdatedAt = nextReaderProgressTimestamp(book.progressUpdatedAt),
                )
            }
        } else {
            book.copy(
                pdfPageIndex = normalizedPage,
                pageOffsetPercent = normalizedOffset,
                progressUpdatedAt = nextReaderProgressTimestamp(book.progressUpdatedAt),
            )
        }
    }

    suspend fun updatePreferences(value: ReaderPreferences) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareForMutationLocked()
            val updated = _state.value.copy(preferences = normalizeReaderPreferences(value))
            writeVerified(updated)
            _state.value = updated
        }
    }

    /**
     * Returns one strictly bounded shelf bitmap. A custom SAF URI wins. For a PDF, an existing
     * verified cache is reused; otherwise Android 10+ may ask the document provider for a bounded
     * thumbnail. We deliberately do not open [PdfRenderer] here: entering a two-column shelf must
     * never parse and render several arbitrary PDFs inside the application process.
     */
    suspend fun loadCover(book: ReaderBook, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val targetSize = readerCoverTargetSize(widthPx)
        coverMutex.withLock {
            book.coverUri?.let { rawUri ->
                val custom = decodeCoverUri(Uri.parse(rawUri), targetSize.width)
                if (custom != null) {
                    return@withLock normalizeCoverBitmap(custom, targetSize)
                }
            }
            if (book.type != ReaderBookType.PDF) return@withLock null
            val cacheFile = autoCoverFile(book)
            decodeCoverFile(cacheFile, targetSize.width)?.let { cached ->
                return@withLock normalizeCoverBitmap(cached, targetSize)
            }

            val generated = loadProviderPdfThumbnail(book) ?: return@withLock null
            try {
                writeVerifiedCoverCache(cacheFile, generated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A cache failure must not discard a thumbnail that is already safe to display.
            }
            normalizeCoverBitmap(generated, targetSize)
        }
    }

    /** Stores only an opaque persistable content URI. Passing null restores the generated cover. */
    suspend fun setCustomCover(bookId: String, uri: Uri?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareForMutationLocked()
            val existing = _state.value.books.firstOrNull { it.id == bookId } ?: return@withContext
            val normalized = uri?.also { selected ->
                require(selected.scheme == ContentResolver.SCHEME_CONTENT) {
                    "Only document image URIs are supported"
                }
                val validation = decodeCoverUri(selected, MIN_COVER_WIDTH_PX)
                    ?: throw IllegalArgumentException("无法读取所选封面图片")
                validation.recycle()
            }?.toString()
            if (normalized == existing.coverUri) return@withContext

            val alreadyPersisted = uri == null || resolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.uri == uri
            }
            if (uri != null && !alreadyPersisted) {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val updated = _state.value.copy(
                books = _state.value.books.map { book ->
                    if (book.id == bookId) book.copy(coverUri = normalized) else book
                },
            )
            try {
                writeVerified(updated)
                _state.value = updated
            } catch (error: Exception) {
                if (uri != null && !alreadyPersisted) releasePersistedReadPermission(uri)
                throw error
            }
            existing.coverUri?.let { old -> releaseIfUnused(Uri.parse(old), updated) }
        }
    }

    /**
     * Exports URI-free progress keyed by full-file SHA-256. Legacy fingerprints are filled only
     * for books with meaningful progress, newest first, under one cumulative I/O budget. Hashing
     * happens without holding the state mutex so normal reading progress is not blocked.
     */
    suspend fun exportProgressRecords(): List<ReaderProgressRecord> {
        val candidates = mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureInitializedLocked()
                _state.value.books
                    .filter { it.fingerprint == null && it.hasMeaningfulProgress() }
                    .sortedWith(
                        compareByDescending<ReaderBook>(ReaderBook::progressUpdatedAt)
                            .thenByDescending(ReaderBook::lastOpenedAt),
                    )
            }
        }
        val measured = withContext(Dispatchers.IO) {
            val values = linkedMapOf<String, MeasuredReaderFingerprint>()
            var remainingBytes = MAX_EXPORT_FINGERPRINT_BYTES
            for (book in candidates) {
                currentCoroutineContext().ensureActive()
                if (remainingBytes <= 0L) break
                val uri = Uri.parse(book.uri)
                val declaredSize = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull()
                if (declaredSize != null && declaredSize >= 0L && declaredSize > remainingBytes) {
                    continue
                }
                val result = fingerprintAndSizeOrNull(
                    uri = uri,
                    type = book.type,
                    maxBytes = minOf(remainingBytes, MAX_BOOK_FINGERPRINT_BYTES),
                )
                if (result != null) {
                    remainingBytes -= result.byteCount
                    values[book.id] = MeasuredReaderFingerprint(
                        uri = book.uri,
                        fingerprint = result.fingerprint,
                    )
                } else if (declaredSize == null || declaredSize < 0L) {
                    // An unknown-length provider may have consumed the entire bounded attempt.
                    remainingBytes = 0L
                }
            }
            values
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                ensureInitializedLocked()
                val measuredBooks = _state.value.books.map { book ->
                    val result = measured[book.id]
                    if (book.fingerprint != null || result == null || result.uri != book.uri) {
                        book
                    } else {
                        book.copy(fingerprint = result.fingerprint)
                    }
                }
                val base = _state.value.copy(books = measuredBooks)
                val normalized = mergeReaderProgress(base, base.progressLedger).state
                if (normalized != _state.value && _storageIssue.value == null) {
                    try {
                        writeVerified(normalized)
                        _state.value = normalized
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Use the verified in-memory snapshot for this export; COMMIT_FAILED remains
                        // visible and the previously committed state remains authoritative on disk.
                    }
                }
                mergeReaderProgressLedger(
                    normalized.books.mapNotNull(ReaderBook::toProgressRecordOrNull) +
                        normalized.progressLedger,
                ).sortedWith(
                    compareBy(ReaderProgressRecord::fingerprint, ReaderProgressRecord::type),
                ).map { record -> record.copy(pageOffsetPercent = 0) }
            }
        }
    }

    suspend fun importProgressRecords(
        records: List<ReaderProgressRecord>,
    ): ReaderProgressImportResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareForMutationLocked()
            require(records.size <= MAX_PROGRESS_RECORDS) { "Too many reader progress records" }
            val merge = mergeReaderProgress(_state.value, records)
            if (merge.state != _state.value) {
                writeVerified(merge.state)
                _state.value = merge.state
            }
            merge.result
        }
    }

    /**
     * Transaction rollback hook for a larger backup restore. Unlike importProgressRecords this is
     * an exact replacement and deliberately ignores LWW timestamps. It never replaces book URIs,
     * titles, covers, metadata, or reader preferences.
     */
    suspend fun replaceProgressRecordsForRollback(records: List<ReaderProgressRecord>) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                prepareForMutationLocked()
                require(records.size <= MAX_PROGRESS_RECORDS) {
                    "Too many reader progress records"
                }
                val updated = replaceReaderProgress(_state.value, records)
                if (updated == _state.value) return@withContext
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
            releaseIfUnused(Uri.parse(removed.uri), updated)
            removed.coverUri?.let { releaseIfUnused(Uri.parse(it), updated) }
            if (removed.fingerprint == null ||
                updated.books.none { it.fingerprint == removed.fingerprint }
            ) {
                coverMutex.withLock {
                    runCatching { autoCoverFile(removed).delete() }
                }
            }
        }
    }

    private suspend fun updateBook(bookId: String, transform: (ReaderBook) -> ReaderBook) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                prepareForMutationLocked()
                if (_state.value.books.none { it.id == bookId }) return@withContext
                val books = _state.value.books.map { if (it.id == bookId) transform(it) else it }
                    .sortedByDescending(ReaderBook::lastOpenedAt)
                val base = _state.value.copy(books = books)
                val updated = mergeReaderProgress(base, base.progressLedger).state
                if (updated == _state.value) return@withContext
                writeVerified(updated)
                _state.value = updated
            }
        }

    private suspend fun updateBookProgress(
        bookId: String,
        transform: (ReaderBook) -> ReaderBook,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            prepareForMutationLocked()
            val current = _state.value.books.firstOrNull { it.id == bookId } ?: return@withContext
            val changed = transform(current)
            if (changed == current) return@withContext
            val ledger = changed.toProgressRecordOrNull()?.let { record ->
                mergeReaderProgressLedger(_state.value.progressLedger + record)
            } ?: _state.value.progressLedger
            val updated = _state.value.copy(
                books = _state.value.books.map { if (it.id == bookId) changed else it }
                    .sortedByDescending(ReaderBook::lastOpenedAt),
                progressLedger = ledger,
            )
            writeVerified(updated)
            _state.value = updated
        }
    }

    private suspend fun fingerprintOrNull(uri: Uri, type: ReaderBookType): String? =
        fingerprintAndSizeOrNull(uri, type, MAX_BOOK_FINGERPRINT_BYTES)?.fingerprint

    private suspend fun fingerprintAndSizeOrNull(
        uri: Uri,
        type: ReaderBookType,
        maxBytes: Long,
    ): ReaderFingerprintResult? = try {
        require(maxBytes >= 0L)
        resolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(READER_FINGERPRINT_DOMAIN.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(type.name.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                currentCoroutineContext().ensureActive()
                if (count < 0) break
                total += count
                require(total <= maxBytes) {
                    "Book is too large to fingerprint safely"
                }
                digest.update(buffer, 0, count)
            }
            ReaderFingerprintResult(
                fingerprint = digest.digest().toLowerHex(),
                byteCount = total,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun releaseIfUnused(uri: Uri, state: ReaderLibraryState) {
        val raw = uri.toString()
        if (state.books.none { book -> book.uri == raw || book.coverUri == raw }) {
            releasePersistedReadPermission(uri)
        }
    }

    private fun releasePersistedReadPermission(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun autoCoverFile(book: ReaderBook): File {
        val key = book.fingerprint?.takeIf(READER_FINGERPRINT_REGEX::matches)
            ?: MessageDigest.getInstance("SHA-256")
                .digest(book.id.toByteArray(StandardCharsets.UTF_8))
                .toLowerHex()
        return File(coverDirectory, "$key.png")
    }

    private suspend fun decodeCoverUri(uri: Uri, targetWidth: Int): Bitmap? {
        val declaredLength = runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (declaredLength != null && declaredLength > MAX_CUSTOM_COVER_BYTES) return null
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    currentCoroutineContext().ensureActive()
                    if (count < 0) break
                    total += count
                    require(total <= MAX_CUSTOM_COVER_BYTES) { "Reader cover is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        } ?: return null
        return decodeCoverBytes(bytes, targetWidth)
    }

    private fun decodeCoverFile(target: File, targetWidth: Int): Bitmap? {
        if (!target.isFile || target.length() !in 1..MAX_COVER_CACHE_BYTES) return null
        val decoded = decodeCover(
            targetWidth = targetWidth,
            open = { FileInputStream(target) },
        )
        if (decoded == null) runCatching { target.delete() }
        return decoded
    }

    private fun decodeCover(
        targetWidth: Int,
        open: () -> java.io.InputStream?,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            open().use { input ->
                requireNotNull(input)
                BitmapFactory.decodeStream(input, null, bounds)
            }
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth !in 1..MAX_COVER_SOURCE_EDGE_PX ||
            bounds.outHeight !in 1..MAX_COVER_SOURCE_EDGE_PX
        ) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = readerCoverSampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            open().use { input ->
                requireNotNull(input)
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
        if (decoded != null && decoded.width.toLong() * decoded.height > MAX_COVER_DECODE_PIXELS) {
            decoded.recycle()
            return null
        }
        return decoded
    }

    private fun decodeCoverBytes(bytes: ByteArray, targetWidth: Int): Bitmap? {
        if (bytes.isEmpty() || bytes.size > MAX_CUSTOM_COVER_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (_: OutOfMemoryError) {
            return null
        } catch (_: Exception) {
            return null
        }
        if (bounds.outWidth !in 1..MAX_COVER_SOURCE_EDGE_PX ||
            bounds.outHeight !in 1..MAX_COVER_SOURCE_EDGE_PX
        ) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = readerCoverSampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
        if (decoded != null && decoded.width.toLong() * decoded.height > MAX_COVER_DECODE_PIXELS) {
            decoded.recycle()
            return null
        }
        return decoded
    }

    private fun writeVerifiedCoverCache(target: File, bitmap: Bitmap) {
        require(bitmap.width > 0 && bitmap.height > 0)
        require(bitmap.width.toLong() * bitmap.height <= MAX_COVER_DECODE_PIXELS)
        check(coverDirectory.isDirectory || coverDirectory.mkdirs()) {
            "Could not prepare reader cover cache"
        }
        val pending = File(coverDirectory, "${target.name}.${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not encode reader cover"
                }
                output.flush()
                output.fd.sync()
            }
            check(pending.length() in 1..MAX_COVER_CACHE_BYTES) {
                "Reader cover cache is too large"
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(pending).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
            check(bounds.outWidth == bitmap.width && bounds.outHeight == bitmap.height) {
                "Reader cover cache verification failed"
            }
            check(bounds.outMimeType.equals("image/png", ignoreCase = true)) {
                "Reader cover cache format verification failed"
            }
            Os.rename(pending.absolutePath, target.absolutePath)
            check(target.isFile && target.length() in 1..MAX_COVER_CACHE_BYTES) {
                "Reader cover cache commit verification failed"
            }
        } finally {
            runCatching { if (pending.exists()) pending.delete() }
        }
    }

    private fun loadProviderPdfThumbnail(book: ReaderBook): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = Uri.parse(book.uri)
        if (!documentProviderSupportsThumbnails(uri)) return null
        val cacheSize = readerCoverTargetSize(AUTO_COVER_WIDTH_PX)
        val thumbnail = try {
            resolver.loadThumbnail(uri, Size(cacheSize.width, cacheSize.height), null)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        } ?: return null
        if (thumbnail.width.toLong() * thumbnail.height > MAX_COVER_DECODE_PIXELS) {
            thumbnail.recycle()
            return null
        }
        return normalizeCoverBitmap(thumbnail, cacheSize)
    }

    private fun documentProviderSupportsThumbnails(uri: Uri): Boolean = try {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            flagsIndex >= 0 &&
                (cursor.getLong(flagsIndex) and
                    DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL.toLong()) != 0L
        } ?: false
    } catch (_: Exception) {
        false
    }

    private fun normalizeCoverBitmap(
        bitmap: Bitmap,
        requestedSize: ReaderCoverDimensions,
    ): Bitmap? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val outputSize = readerCoverOutputSize(
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
            requestedWidth = requestedSize.width,
        )
        if (bitmap.width == outputSize.width && bitmap.height == outputSize.height) return bitmap

        val sourceAspect = bitmap.width.toDouble() / bitmap.height
        val desiredAspect = outputSize.width.toDouble() / outputSize.height
        val source = if (sourceAspect > desiredAspect) {
            val cropWidth = (bitmap.height * desiredAspect).toInt().coerceIn(1, bitmap.width)
            val left = (bitmap.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, bitmap.height)
        } else {
            val cropHeight = (bitmap.width / desiredAspect).toInt().coerceIn(1, bitmap.height)
            val top = (bitmap.height - cropHeight) / 2
            Rect(0, top, bitmap.width, top + cropHeight)
        }
        val normalized = try {
            Bitmap.createBitmap(outputSize.width, outputSize.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(
                    bitmap,
                    source,
                    RectF(0f, 0f, outputSize.width.toFloat(), outputSize.height.toFloat()),
                    COVER_BITMAP_PAINT,
                )
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
        if (normalized !== bitmap) bitmap.recycle()
        return normalized
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
        const val COVER_DIRECTORY_NAME = "reader-covers"
        internal const val MAX_STATE_BYTES = 2 * 1024 * 1024
        private const val MAX_TEXT_BYTES = 32 * 1024 * 1024
        private const val MAX_PARAGRAPHS = 250_000
        internal const val MAX_TEXT_PAGES = 50_000
        private const val MAX_PDF_PAGES = 20_000
        private const val MAX_PDF_WIDTH_PX = 2_048
        private const val MAX_PDF_PIXELS = 8_000_000L
        private const val MAX_BOOK_FINGERPRINT_BYTES = 512L * 1024L * 1024L
        private const val MAX_EXPORT_FINGERPRINT_BYTES = 512L * 1024L * 1024L
        private const val MIN_COVER_WIDTH_PX = READER_COVER_MIN_WIDTH_PX
        private const val AUTO_COVER_WIDTH_PX = READER_COVER_MAX_WIDTH_PX
        private const val MAX_COVER_SOURCE_EDGE_PX = 100_000
        private const val MAX_COVER_DECODE_PIXELS = READER_COVER_MAX_INTERMEDIATE_PIXELS
        private const val MAX_COVER_CACHE_BYTES = 4L * 1024L * 1024L
        private const val MAX_CUSTOM_COVER_BYTES = 16 * 1024 * 1024
        private const val MAX_PROGRESS_RECORDS = 500

        private val COVER_BITMAP_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
}

/**
 * Floors an observed scroll percentage to the previous 5% checkpoint so resume never skips
 * unread content. Valid persisted values are 0, 5, 10, …, 95.
 */
internal fun quantizePageOffsetPercent(rawPercent: Int): Int =
    (rawPercent / 5 * 5).coerceIn(0, 95)

/**
 * Normalizes a hostile value to a safe 5% checkpoint. Negative values, values above 100, and
 * values that are not exact multiples of 5 are all floored to the previous valid checkpoint.
 */
internal fun normalizePageOffsetPercent(value: Int): Int = quantizePageOffsetPercent(value)

internal fun normalizeReaderPreferences(value: ReaderPreferences): ReaderPreferences = value.copy(
    customBackgroundArgb = value.customBackgroundArgb or 0xFF000000.toInt(),
    customForegroundArgb = value.customForegroundArgb?.or(0xFF000000.toInt()),
    fontSizeSp = value.fontSizeSp.takeIf(Float::isFinite)?.coerceIn(12f, 38f) ?: 19f,
    lineHeightMultiplier = value.lineHeightMultiplier.takeIf(Float::isFinite)
        ?.coerceIn(1f, 2.4f) ?: 1.6f,
    paragraphSpacingDp = value.paragraphSpacingDp.takeIf(Float::isFinite)
        ?.coerceIn(0f, 36f) ?: 10f,
    pdfZoomPercent = value.pdfZoomPercent.coerceIn(
        MIN_READER_PDF_ZOOM_PERCENT,
        MAX_READER_PDF_ZOOM_PERCENT,
    ),
    customChapterRegex = value.customChapterRegex.trim().take(MAX_READER_CUSTOM_REGEX_CHARS),
    chapterHeadingMaxChars = value.chapterHeadingMaxChars.coerceIn(
        MIN_READER_CHAPTER_HEADING_CHARS,
        MAX_READER_CHAPTER_TITLE_CHARS,
    ),
)

internal data class ReaderProgressMerge(
    val state: ReaderLibraryState,
    val result: ReaderProgressImportResult,
)

internal fun mergeReaderProgress(
    state: ReaderLibraryState,
    records: List<ReaderProgressRecord>,
): ReaderProgressMerge {
    val ledger = mergeReaderProgressLedger(
        state.progressLedger + records.mapNotNull(ReaderProgressRecord::normalizedOrNull),
    )
    val newestByBook = ledger
        .groupBy { it.fingerprint to it.type }
        .mapValues { (_, matches) ->
            matches.maxWithOrNull(
                compareBy<ReaderProgressRecord>(ReaderProgressRecord::updatedAt)
                    .thenBy(ReaderProgressRecord::progressSortIndex),
            )!!
        }
    var matched = 0
    var updated = 0
    val books = state.books.map { book ->
        val fingerprint = book.fingerprint ?: return@map book
        val record = newestByBook[fingerprint to book.type] ?: return@map book
        matched += 1
        val candidate = when (book.type) {
            ReaderBookType.TXT -> book.copy(
                // Logical TXT pages depend on chapter-detection preferences. Paragraph index is
                // the cross-device canonical position; -1 reuses the existing migration/remap path.
                textPageIndex = -1,
                textParagraphIndex = record.textParagraphIndex.coerceIn(0, 249_999),
                pageOffsetPercent = normalizePageOffsetPercent(record.pageOffsetPercent),
                progressUpdatedAt = maxOf(book.progressUpdatedAt, record.updatedAt),
            )
            ReaderBookType.PDF -> book.copy(
                pdfPageIndex = mapReaderProgressPage(
                    sourceIndex = record.pdfPageIndex,
                    sourceTotal = record.totalPages,
                    destinationTotal = book.totalPages,
                    maximum = 20_000,
                ),
                pageOffsetPercent = normalizePageOffsetPercent(record.pageOffsetPercent),
                progressUpdatedAt = maxOf(book.progressUpdatedAt, record.updatedAt),
            )
        }
        val shouldUseCandidate = record.updatedAt > book.progressUpdatedAt ||
            (record.updatedAt == book.progressUpdatedAt &&
                candidate.progressSortIndex() > book.progressSortIndex())
        if (!shouldUseCandidate || candidate == book) return@map book
        updated += 1
        candidate
    }
    val stateChanged = updated > 0 || ledger != state.progressLedger
    return ReaderProgressMerge(
        state = if (!stateChanged) state else state.copy(books = books, progressLedger = ledger),
        result = ReaderProgressImportResult(matchedBooks = matched, updatedBooks = updated),
    )
}

internal fun replaceReaderProgress(
    state: ReaderLibraryState,
    records: List<ReaderProgressRecord>,
): ReaderLibraryState {
    val ledger = mergeReaderProgressLedger(records)
    val byBook = ledger.associateBy { it.fingerprint to it.type }
    val books = state.books.map { book ->
        val fingerprint = book.fingerprint ?: return@map book
        val record = byBook[fingerprint to book.type] ?: return@map book
        when (book.type) {
            ReaderBookType.TXT -> book.copy(
                textPageIndex = record.textPageIndex.coerceIn(
                    -1,
                    ReaderRepository.MAX_TEXT_PAGES - 1,
                ),
                textParagraphIndex = record.textParagraphIndex.coerceIn(0, 249_999),
                pageOffsetPercent = normalizePageOffsetPercent(record.pageOffsetPercent),
                progressUpdatedAt = record.updatedAt,
            )
            ReaderBookType.PDF -> book.copy(
                pdfPageIndex = record.pdfPageIndex.coerceIn(
                    0,
                    if (book.totalPages > 0) minOf(book.totalPages, 20_000) - 1 else 19_999,
                ),
                pageOffsetPercent = normalizePageOffsetPercent(record.pageOffsetPercent),
                progressUpdatedAt = record.updatedAt,
            )
        }
    }
    return state.copy(books = books, progressLedger = ledger)
}

private fun mergeReaderProgressLedger(
    records: List<ReaderProgressRecord>,
): List<ReaderProgressRecord> = records
    .mapNotNull(ReaderProgressRecord::normalizedOrNull)
    .groupBy { it.fingerprint to it.type }
    .values
    .map { matches ->
        matches.maxWithOrNull(
            compareBy<ReaderProgressRecord>(ReaderProgressRecord::updatedAt)
                .thenBy(ReaderProgressRecord::progressSortIndex),
        )!!
    }
    .sortedWith(
        compareByDescending<ReaderProgressRecord>(ReaderProgressRecord::updatedAt)
            .thenBy(ReaderProgressRecord::fingerprint)
            .thenBy(ReaderProgressRecord::type),
    )
    .take(500)

private fun ReaderBook.toProgressRecordOrNull(): ReaderProgressRecord? {
    val stableFingerprint = fingerprint?.takeIf(READER_FINGERPRINT_REGEX::matches) ?: return null
    return ReaderProgressRecord(
        fingerprint = stableFingerprint,
        type = type,
        textPageIndex = textPageIndex.coerceIn(-1, ReaderRepository.MAX_TEXT_PAGES - 1),
        textParagraphIndex = textParagraphIndex.coerceAtLeast(0),
        pdfPageIndex = pdfPageIndex.coerceAtLeast(0),
        totalPages = totalPages.coerceAtLeast(0),
        updatedAt = progressUpdatedAt.coerceAtLeast(0L),
        pageOffsetPercent = normalizePageOffsetPercent(pageOffsetPercent),
    )
}

private fun ReaderProgressRecord.normalizedOrNull(): ReaderProgressRecord? {
    val normalizedFingerprint = fingerprint.lowercase(Locale.ROOT)
    if (!READER_FINGERPRINT_REGEX.matches(normalizedFingerprint)) return null
    return copy(
        fingerprint = normalizedFingerprint,
        textPageIndex = textPageIndex.coerceIn(-1, ReaderRepository.MAX_TEXT_PAGES - 1),
        textParagraphIndex = textParagraphIndex.coerceIn(0, 249_999),
        pdfPageIndex = pdfPageIndex.coerceIn(0, 19_999),
        totalPages = totalPages.coerceIn(
            0,
            if (type == ReaderBookType.PDF) 20_000 else ReaderRepository.MAX_TEXT_PAGES,
        ),
        updatedAt = updatedAt.coerceAtLeast(0L),
        pageOffsetPercent = normalizePageOffsetPercent(pageOffsetPercent),
    )
}

private fun ReaderProgressRecord.progressSortIndex(): Int = when (type) {
    ReaderBookType.TXT -> textParagraphIndex * 100 + pageOffsetPercent
    ReaderBookType.PDF -> pdfPageIndex * 100 + pageOffsetPercent
}

private fun ReaderBook.progressSortIndex(): Int = when (type) {
    ReaderBookType.TXT -> textParagraphIndex.coerceAtLeast(0) * 100 +
        normalizePageOffsetPercent(pageOffsetPercent)
    ReaderBookType.PDF -> pdfPageIndex.coerceAtLeast(0) * 100 +
        normalizePageOffsetPercent(pageOffsetPercent)
}

private fun ReaderBook.hasMeaningfulProgress(): Boolean = progressUpdatedAt > 0L || when (type) {
    ReaderBookType.TXT -> textParagraphIndex > 0 || textPageIndex > 0 ||
        pageOffsetPercent > 0
    ReaderBookType.PDF -> pdfPageIndex > 0 || pageOffsetPercent > 0
}

private fun nextReaderProgressTimestamp(previous: Long): Long {
    val now = System.currentTimeMillis().coerceAtLeast(0L)
    return if (previous == Long.MAX_VALUE) previous else maxOf(now, previous + 1L)
}

private fun mapReaderProgressPage(
    sourceIndex: Int,
    sourceTotal: Int,
    destinationTotal: Int,
    maximum: Int,
): Int {
    val destinationLimit = if (destinationTotal > 0) {
        minOf(destinationTotal, maximum) - 1
    } else {
        maximum - 1
    }
    if (sourceTotal <= 1 || destinationTotal <= 1 || sourceTotal == destinationTotal) {
        return sourceIndex.coerceIn(0, destinationLimit)
    }
    val sourceLimit = sourceTotal - 1L
    val destinationLast = destinationTotal.coerceAtMost(maximum) - 1L
    return ((sourceIndex.coerceAtMost(sourceTotal - 1).toLong() * destinationLast +
        sourceLimit / 2L) / sourceLimit).toInt().coerceIn(0, destinationLimit)
}

internal data class ReaderCoverDimensions(
    val width: Int,
    val height: Int,
)

internal fun readerCoverTargetSize(widthPx: Int): ReaderCoverDimensions {
    val width = widthPx.coerceIn(READER_COVER_MIN_WIDTH_PX, READER_COVER_MAX_WIDTH_PX)
    return ReaderCoverDimensions(
        width = width,
        height = kotlin.math.ceil(width / READER_COVER_ASPECT_RATIO).toInt(),
    )
}

internal fun readerCoverOutputSize(
    sourceWidth: Int,
    sourceHeight: Int,
    requestedWidth: Int,
): ReaderCoverDimensions {
    require(sourceWidth > 0 && sourceHeight > 0)
    val target = readerCoverTargetSize(requestedWidth)
    val width = minOf(target.width, sourceWidth).coerceAtLeast(1)
    return ReaderCoverDimensions(
        width = width,
        height = kotlin.math.ceil(width / READER_COVER_ASPECT_RATIO).toInt(),
    )
}

internal fun readerCoverSampleSize(width: Int, height: Int, targetWidth: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    val desiredDecodedWidth = (readerCoverTargetSize(targetWidth).width * 2)
    while (sample <= Int.MAX_VALUE / 2) {
        val sampledWidth = (width + sample - 1L) / sample
        val sampledHeight = (height + sample - 1L) / sample
        if (sampledWidth <= desiredDecodedWidth &&
            sampledWidth * sampledHeight <= READER_COVER_MAX_INTERMEDIATE_PIXELS
        ) break
        sample *= 2
    }
    return sample
}

private const val READER_COVER_MIN_WIDTH_PX = 96
private const val READER_COVER_MAX_WIDTH_PX = 512
private const val READER_COVER_MAX_INTERMEDIATE_PIXELS = 1_500_000L
private const val READER_COVER_ASPECT_RATIO = 0.7

private fun ByteArray.toLowerHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (value in this@toLowerHex) {
            val unsigned = value.toInt() and 0xFF
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 0x0F])
        }
    }
}

private val READER_FINGERPRINT_REGEX = Regex("[a-f0-9]{64}")
private const val READER_FINGERPRINT_DOMAIN = "DeskCubby.ReaderBook.v1"

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
        val chapterTitle = normalizeReaderChapterCandidate(paragraph).takeIf { value ->
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
    return ReaderTextLayout(pages, collapseReaderChapterDuplicates(chapters))
}

internal fun collapseReaderChapterDuplicates(
    chapters: List<ReaderChapter>,
): List<ReaderChapter> {
    if (chapters.size < 2) return chapters
    val densePages = chapters.groupingBy(ReaderChapter::pageIndex)
        .eachCount()
        .filterValues { it >= MIN_TOC_HEADINGS_ON_PAGE }
        .keys
    val likelyTocEntries = HashSet<ReaderChapter>()
    chapters.windowed(MIN_TOC_HEADINGS_ON_PAGE).forEach { window ->
        if (window.last().paragraphIndex - window.first().paragraphIndex <=
            MAX_TOC_PARAGRAPH_SPAN
        ) {
            likelyTocEntries += window
        }
    }
    val grouped = LinkedHashMap<String, MutableList<ReaderChapter>>()
    chapters.forEach { chapter ->
        val withoutPageNumber = chapter.title
            .lowercase()
            .replace(READER_CHAPTER_TRAILING_PAGE_REGEX, "")
        val key = READER_CHINESE_CHAPTER_KEY_REGEX.replace(withoutPageNumber) { match ->
            val prefix = match.groupValues[1]
            val number = readerChapterNumberKey(match.groupValues[2])
            val unit = match.groupValues[3]
            val suffix = match.groupValues[4]
            "$prefix 第$number$unit$suffix"
        }
            .replace(READER_CHAPTER_KEY_DECORATION_REGEX, "")
            .trim()
        grouped.getOrPut(key) { ArrayList() } += chapter
    }
    return grouped.values
        .map { matches ->
            val first = matches.first()
            if (first.pageIndex in densePages || first in likelyTocEntries) {
                matches.firstOrNull {
                    it.pageIndex !in densePages && it !in likelyTocEntries
                } ?: first
            } else {
                first
            }
        }
        .sortedWith(compareBy(ReaderChapter::pageIndex, ReaderChapter::paragraphIndex))
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
    val value = normalizeReaderChapterCandidate(raw)
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
    if (value.isEmpty() || value.length > preferences.chapterHeadingMaxChars ||
        READER_TOC_ENTRY_REGEX.containsMatchIn(value)
    ) return false
    val smartEnabled = preferences.chapterDetectionMode !=
        ReaderChapterDetectionMode.CUSTOM
    val customEnabled = preferences.chapterDetectionMode !=
        ReaderChapterDetectionMode.SMART
    val smartMatch = smartEnabled && SMART_READER_CHAPTER_REGEXES.any { it.matches(value) }
    val customMatch = customEnabled && customRegex?.matches(value) == true
    return smartMatch || customMatch
}

internal fun findReaderTextMatches(
    pages: List<ReaderTextPage>,
    rawQuery: String,
    maxResults: Int = MAX_READER_SEARCH_RESULTS,
): List<ReaderTextSearchMatch> {
    require(maxResults in 1..MAX_READER_SEARCH_RESULTS)
    val query = rawQuery.trim().take(MAX_READER_SEARCH_QUERY_CHARS)
    if (query.isEmpty()) return emptyList()
    return buildList {
        pages.forEachIndexed { pageIndex, page ->
            var fromIndex = 0
            while (size < maxResults) {
                val match = page.text.indexOf(query, fromIndex, ignoreCase = true)
                if (match < 0) break
                add(
                    ReaderTextSearchMatch(
                        pageIndex = pageIndex,
                        startIndex = match,
                        endIndex = match + query.length,
                    ),
                )
                fromIndex = match + query.length.coerceAtLeast(1)
            }
            if (size >= maxResults) return@buildList
        }
    }
}

internal fun detectReaderChaptersInTextBlocks(
    pageIndex: Int,
    textBlocks: List<String>,
    preferences: ReaderPreferences = ReaderPreferences(),
): List<ReaderChapter> {
    if (pageIndex < 0) return emptyList()
    return textBlocks
        .asSequence()
        .flatMap { it.lineSequence() }
        .map(::normalizeReaderChapterCandidate)
        .filter(String::isNotEmpty)
        .filter { isReaderChapterHeading(it, preferences) }
        .distinct()
        .take(MAX_READER_CHAPTERS)
        .map { title ->
            ReaderChapter(
                title = title.take(MAX_READER_CHAPTER_TITLE_CHARS),
                pageIndex = pageIndex,
                paragraphIndex = pageIndex,
            )
        }
        .toList()
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

internal const val MAX_READER_CHAPTERS = 20_000
internal const val MAX_READER_CHAPTER_TITLE_CHARS = 240
internal const val MIN_READER_CHAPTER_HEADING_CHARS = 20
internal const val MAX_READER_CUSTOM_REGEX_CHARS = 1_024
internal const val MIN_READER_PDF_ZOOM_PERCENT = 50
internal const val MAX_READER_PDF_ZOOM_PERCENT = 300
internal const val MAX_READER_SEARCH_QUERY_CHARS = 128
internal const val MAX_READER_SEARCH_RESULTS = 5_000
private val READER_INVISIBLE_HEADING_CHARS = setOf('\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060')
private val READER_HEADING_WHITESPACE_REGEX = Regex("[\\t\\u00A0\\u3000 ]+")
private val READER_TOC_ENTRY_REGEX = Regex(
    "(?:\\.{3,}|…{2,}|·{3,}|_{3,})\\s*(?:[0-9０-９]+|[ivxlcdm]+)\\s*$",
    RegexOption.IGNORE_CASE,
)
private val READER_CHAPTER_TRAILING_PAGE_REGEX = Regex(
    "(?:\\s+|[.．…·_]{2,})(?:[0-9０-９]+|[ivxlcdm]+)\\s*$",
    RegexOption.IGNORE_CASE,
)
private val READER_CHAPTER_KEY_DECORATION_REGEX = Regex(
    "[\\s:：、.．_\\-—【】\\[\\]〈〉《》（）()☆★◎◇◆•·]+",
)
private val READER_CHINESE_CHAPTER_KEY_REGEX = Regex(
    "^(正文)?\\s*第\\s*([0-9０-９零〇○一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+)\\s*([章节卷回部篇集幕])(.*)$",
)
private const val MIN_TOC_HEADINGS_ON_PAGE = 3
private const val MAX_TOC_PARAGRAPH_SPAN = 2

private fun readerChapterNumberKey(raw: String): String {
    val asciiDigits = buildString(raw.length) {
        raw.forEach { value ->
            append(if (value in '０'..'９') '0' + (value - '０') else value)
        }
    }
    asciiDigits.toLongOrNull()?.let { return it.toString() }
    val digits = mapOf(
        '零' to 0, '〇' to 0, '○' to 0,
        '一' to 1, '壹' to 1,
        '二' to 2, '贰' to 2, '两' to 2,
        '三' to 3, '叁' to 3,
        '四' to 4, '肆' to 4,
        '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6,
        '七' to 7, '柒' to 7,
        '八' to 8, '捌' to 8,
        '九' to 9, '玖' to 9,
    )
    val units = mapOf(
        '十' to 10, '拾' to 10,
        '百' to 100, '佰' to 100,
        '千' to 1_000, '仟' to 1_000,
    )
    var total = 0L
    var section = 0L
    var number = 0L
    raw.forEach { value ->
        when {
            value in digits -> number = digits.getValue(value).toLong()
            value in units -> {
                section += (if (number == 0L) 1L else number) * units.getValue(value)
                number = 0L
            }
            value == '万' -> {
                total += (section + number).coerceAtLeast(1L) * 10_000L
                section = 0L
                number = 0L
            }
            else -> return raw
        }
    }
    return (total + section + number).toString()
}

private fun normalizeReaderChapterCandidate(raw: String): String = raw
    .filterNot(READER_INVISIBLE_HEADING_CHARS::contains)
    .trim()
    .replace(READER_HEADING_WHITESPACE_REGEX, " ")

private val CHINESE_READER_CHAPTER_REGEX = Regex(
    "^(?:正文\\s*)?[【\\[〈《（(]?[☆★◎◇◆•·\\s]*第\\s*[0-9０-９零〇○一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+\\s*[章节卷回部篇集幕]\\s*[】\\]〉》）)]?(?:\\s+|[:：、.．_\\-—]?)[^\\n]*$",
)
private val ENGLISH_READER_CHAPTER_REGEX = Regex(
    "^(?:chapter|part|book|section|episode)\\s*(?:[0-9０-９]+|[ivxlcdm]+|" +
        "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|" +
        "thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|" +
        "thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[ -](?:one|two|three|" +
        "four|five|six|seven|eight|nine))?)(?:\\s*[:：.\\-—]?\\s*.*)?$",
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
    private const val SCHEMA_VERSION = 7
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
                .put("pdfZoomPercent", value.preferences.pdfZoomPercent)
                .put("orientation", value.preferences.orientation.name)
                .put("libraryLayout", value.preferences.libraryLayout.name)
                .put("showGridBookTitles", value.preferences.showGridBookTitles)
                .put("showProgressPercentage", value.preferences.showProgressPercentage)
                .put("immersiveMode", value.preferences.immersiveMode)
                .put(
                    "customForegroundArgb",
                    value.preferences.customForegroundArgb ?: JSONObject.NULL,
                )
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
                            .put("pdfPageIndex", book.pdfPageIndex)
                            .put("coverUri", book.coverUri ?: JSONObject.NULL)
                            .put("fingerprint", book.fingerprint ?: JSONObject.NULL)
                            .put("totalPages", book.totalPages)
                            .put("progressUpdatedAt", book.progressUpdatedAt)
                            .put(
                                "pageOffsetPercent",
                                normalizePageOffsetPercent(book.pageOffsetPercent),
                            ),
                    )
                }
            },
        )
        .put(
            "progressLedger",
            JSONArray().apply {
                value.progressLedger.forEach { record -> put(record.toJson()) }
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
                pdfZoomPercent = if (schemaVersion >= 4) {
                    preferencesJson.getInt("pdfZoomPercent")
                } else {
                    ReaderPreferences().pdfZoomPercent
                },
                orientation = enumValueOr(
                    preferencesJson.getString("orientation"),
                    ReaderOrientation.FOLLOW_SYSTEM,
                ),
                libraryLayout = if (schemaVersion >= 5) {
                    enumValueOr(
                        preferencesJson.optString("libraryLayout"),
                        ReaderLibraryLayout.LIST,
                    )
                } else {
                    ReaderLibraryLayout.LIST
                },
                showGridBookTitles = if (schemaVersion >= 6) {
                    preferencesJson.optBoolean("showGridBookTitles", true)
                } else {
                    true
                },
                showProgressPercentage = schemaVersion >= 5 &&
                    preferencesJson.optBoolean("showProgressPercentage", false),
                immersiveMode = schemaVersion >= 5 &&
                    preferencesJson.optBoolean("immersiveMode", false),
                customForegroundArgb = if (schemaVersion >= 5 &&
                    preferencesJson.has("customForegroundArgb") &&
                    !preferencesJson.isNull("customForegroundArgb")
                ) {
                    preferencesJson.getInt("customForegroundArgb")
                } else {
                    null
                },
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
                val type = enumValueOr(item.getString("type"), ReaderBookType.TXT)
                val coverUri = if (schemaVersion >= 5 &&
                    item.has("coverUri") && !item.isNull("coverUri")
                ) {
                    item.getString("coverUri").also { raw ->
                        require(raw.startsWith("content://") && raw.length <= 8_192)
                    }
                } else {
                    null
                }
                val fingerprint = if (schemaVersion >= 5 &&
                    item.has("fingerprint") && !item.isNull("fingerprint")
                ) {
                    item.getString("fingerprint").also { raw ->
                        require(READER_FINGERPRINT_REGEX.matches(raw))
                    }
                } else {
                    null
                }
                add(
                    ReaderBook(
                        id = id,
                        uri = uri,
                        title = title,
                        type = type,
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
                        coverUri = coverUri,
                        fingerprint = fingerprint,
                        totalPages = if (schemaVersion >= 5) {
                            item.optInt("totalPages", 0).coerceIn(
                                0,
                                if (type == ReaderBookType.PDF) 20_000 else {
                                    ReaderRepository.MAX_TEXT_PAGES
                                },
                            )
                        } else {
                            0
                        },
                        progressUpdatedAt = if (schemaVersion >= 5) {
                            item.optLong("progressUpdatedAt", 0L).coerceAtLeast(0L)
                        } else {
                            0L
                        },
                        pageOffsetPercent = if (schemaVersion >= 7) {
                            normalizePageOffsetPercent(item.optInt("pageOffsetPercent", 0))
                        } else {
                            0
                        },
                    ),
                )
            }
        }.sortedByDescending(ReaderBook::lastOpenedAt)
        val ledger = if (schemaVersion >= 5) {
            val array = root.optJSONArray("progressLedger") ?: JSONArray()
            require(array.length() <= MAX_BOOKS)
            mergeReaderProgressLedger(
                buildList {
                    repeat(array.length()) { index ->
                        add(
                            array.getJSONObject(index).toProgressRecord(
                                includePageOffset = schemaVersion >= 7,
                            ),
                        )
                    }
                },
            )
        } else {
            emptyList()
        }
        val decoded = ReaderLibraryState(
            books = books,
            preferences = preferences,
            progressLedger = ledger,
        )
        return mergeReaderProgress(decoded, ledger).state
    }

    private fun ReaderProgressRecord.toJson(): JSONObject = JSONObject()
        .put("fingerprint", fingerprint)
        .put("type", type.name)
        .put("textPageIndex", textPageIndex)
        .put("textParagraphIndex", textParagraphIndex)
        .put("pdfPageIndex", pdfPageIndex)
        .put("totalPages", totalPages)
        .put("updatedAt", updatedAt)
        .put("pageOffsetPercent", normalizePageOffsetPercent(pageOffsetPercent))

    private fun JSONObject.toProgressRecord(includePageOffset: Boolean): ReaderProgressRecord {
        val fingerprint = getString("fingerprint").lowercase(Locale.ROOT)
        require(READER_FINGERPRINT_REGEX.matches(fingerprint))
        return ReaderProgressRecord(
            fingerprint = fingerprint,
            type = enumValueOr(getString("type"), ReaderBookType.TXT),
            textPageIndex = optInt("textPageIndex", 0),
            textParagraphIndex = optInt("textParagraphIndex", 0),
            pdfPageIndex = optInt("pdfPageIndex", 0),
            totalPages = optInt("totalPages", 0),
            updatedAt = optLong("updatedAt", 0L),
            pageOffsetPercent = if (includePageOffset) {
                normalizePageOffsetPercent(optInt("pageOffsetPercent", 0))
            } else {
                0
            },
        ).normalizedOrNull() ?: error("Invalid reader progress record")
    }

    private inline fun <reified T : Enum<T>> enumValueOr(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
