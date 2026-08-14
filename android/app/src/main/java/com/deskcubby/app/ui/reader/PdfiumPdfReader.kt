package com.deskcubby.app.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.repository.MAX_READER_CHAPTERS
import com.deskcubby.app.data.repository.MAX_READER_SEARCH_QUERY_CHARS
import com.deskcubby.app.data.repository.MAX_READER_SEARCH_RESULTS
import com.deskcubby.app.data.repository.ReaderChapter
import com.deskcubby.app.data.repository.ReaderPreferences
import com.deskcubby.app.data.repository.collapseReaderChapterDuplicates
import com.deskcubby.app.data.repository.detectReaderChaptersInTextBlocks
import com.deskcubby.app.ui.theme.tr
import io.legere.pdfiumandroid.PdfDocument
import io.legere.pdfiumandroid.PdfiumCore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private class PdfiumDocumentSession(
    val document: PdfDocument,
    val pageCount: Int,
) {
    private val operationMutex = Mutex()
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    suspend fun <T> access(block: (PdfDocument) -> T): T {
        if (closing.get()) throw CancellationException("PDF session is closing")
        return withContext(Dispatchers.IO) {
            operationMutex.withLock {
                if (closing.get()) throw CancellationException("PDF session is closing")
                block(document)
            }
        }
    }

    suspend fun close() {
        if (closing.compareAndSet(false, true)) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    operationMutex.withLock { runCatching { document.close() } }
                }
            } finally {
                closed.complete(Unit)
            }
        } else {
            withContext(NonCancellable) { closed.await() }
        }
    }
}

private data class PdfiumRenderedPage(
    val bitmap: Bitmap,
)

/**
 * Content point that was pinned under the pinch centroid while the transient matrix was live.
 * After the zoom commits and the page re-renders, the reader scrolls both axes so this same
 * content point lands back under [targetX]/[targetY].
 */
private val pdfiumWorkerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val pdfiumOpenMutex = Mutex()

@Composable
internal fun PdfiumPdfReader(
    uri: Uri,
    initialPosition: ReaderPagePosition,
    preferences: ReaderPreferences,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    requestedPage: Int?,
    searchQuery: String,
    selectedSearchIndex: Int,
    onRequestedPageConsumed: () -> Unit,
    onCurrentPositionChanged: (ReaderPagePosition) -> Unit,
    onSearchResultCountChanged: (Int) -> Unit,
    onChaptersChanged: (List<ReaderChapter>) -> Unit,
    onChapterScanRunningChanged: (Boolean) -> Unit,
    onEnhancedReaderUnavailable: () -> Unit,
    onZoomPercentChanged: (Int) -> Unit = {},
) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val sessionResult by key(uri) {
        produceState<Result<PdfiumDocumentSession>?>(null, uri) {
            val acquisition = ReaderPdfDetachedResource(
                workerScope = pdfiumWorkerScope,
                release = { it.close() },
            ) { guard ->
                var descriptor: android.os.ParcelFileDescriptor? = null
                var document: PdfDocument? = null
                try {
                    pdfiumOpenMutex.withLock {
                        guard.ensureWanted()
                        val pdfium = PdfiumCore()
                        guard.ensureWanted()
                        descriptor = resolver.openFileDescriptor(uri, "r")
                            ?: throw IllegalArgumentException("PDF descriptor unavailable")
                        guard.ensureWanted()
                        document = pdfium.newDocument(requireNotNull(descriptor))
                        val openedDocument = requireNotNull(document)
                        val pageCount = openedDocument.getPageCount()
                        require(pageCount in 1..MAX_PDFIUM_PAGES) {
                            "PDF page count is invalid"
                        }
                        PdfiumDocumentSession(openedDocument, pageCount)
                    }
                } catch (error: Throwable) {
                    runCatching { document?.close() ?: descriptor?.close() }
                    throw error
                }
            }
            var ownedSession: PdfiumDocumentSession? = null
            try {
                val result = acquisition.await(READER_PDF_DOCUMENT_OPEN_TIMEOUT_MILLIS)
                ownedSession = result.getOrNull()
                value = result
                awaitCancellation()
            } finally {
                acquisition.abandon()
                ownedSession?.close()
            }
        }
    }
    val session = sessionResult?.getOrNull()
    val currentOnEnhancedReaderUnavailable by rememberUpdatedState(onEnhancedReaderUnavailable)
    LaunchedEffect(sessionResult) {
        if (sessionResult?.isFailure == true) currentOnEnhancedReaderUnavailable()
    }

    Box(modifier = modifier.background(background), contentAlignment = Alignment.Center) {
        when {
            sessionResult == null -> CircularProgressIndicator()
            session == null -> Text(
                tr("PDF 无法打开", "The PDF could not be opened"),
                color = MaterialTheme.colorScheme.error,
            )
            else -> PdfiumDocumentView(
                session = session,
                initialPosition = initialPosition,
                preferences = preferences,
                background = background,
                foreground = foreground,
                requestedPage = requestedPage,
                searchQuery = searchQuery,
                selectedSearchIndex = selectedSearchIndex,
                onRequestedPageConsumed = onRequestedPageConsumed,
                onCurrentPositionChanged = onCurrentPositionChanged,
                onSearchResultCountChanged = onSearchResultCountChanged,
                onChaptersChanged = onChaptersChanged,
                onChapterScanRunningChanged = onChapterScanRunningChanged,
                onEnhancedReaderUnavailable = onEnhancedReaderUnavailable,
                onZoomPercentChanged = onZoomPercentChanged,
            )
        }
    }
}

@Composable
private fun PdfiumDocumentView(
    session: PdfiumDocumentSession,
    initialPosition: ReaderPagePosition,
    preferences: ReaderPreferences,
    background: Color,
    foreground: Color,
    requestedPage: Int?,
    searchQuery: String,
    selectedSearchIndex: Int,
    onRequestedPageConsumed: () -> Unit,
    onCurrentPositionChanged: (ReaderPagePosition) -> Unit,
    onSearchResultCountChanged: (Int) -> Unit,
    onChaptersChanged: (List<ReaderChapter>) -> Unit,
    onChapterScanRunningChanged: (Boolean) -> Unit,
    onEnhancedReaderUnavailable: () -> Unit,
    onZoomPercentChanged: (Int) -> Unit = {},
) {
    val restoredPosition = remember(session) {
        ReaderPagePosition(
            pageIndex = initialPosition.pageIndex.coerceIn(0, session.pageCount - 1),
            pageOffsetPercent = initialPosition.pageOffsetPercent,
        )
    }
    val safeInitialPage = restoredPosition.pageIndex
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeInitialPage)
    var firstContentLoaded by remember(session) { mutableStateOf(false) }
    var searchMatches by remember(session) { mutableStateOf<List<Int>>(emptyList()) }
    var initialPositionRestored by rememberSaveable { mutableStateOf(false) }
    val currentOnPositionChanged by rememberUpdatedState(onCurrentPositionChanged)
    val currentOnEnhancedReaderUnavailable by rememberUpdatedState(onEnhancedReaderUnavailable)
    LaunchedEffect(session, listState) {
        if (!initialPositionRestored) {
            listState.restoreReaderPagePosition(restoredPosition, session.pageCount)
            initialPositionRestored = true
        }
    }
    LaunchedEffect(requestedPage) {
        requestedPage?.let { page ->
            listState.scrollToItem(page.coerceIn(0, session.pageCount - 1))
            onRequestedPageConsumed()
        }
    }
    LaunchedEffect(listState, initialPositionRestored, session.pageCount) {
        if (!initialPositionRestored) return@LaunchedEffect
        snapshotFlow { listState.currentReaderPagePosition(session.pageCount) }
            .distinctUntilChanged()
            .collect(currentOnPositionChanged)
    }
    LaunchedEffect(session, firstContentLoaded) {
        if (firstContentLoaded) return@LaunchedEffect
        delay(READER_PDF_FIRST_CONTENT_TIMEOUT_MILLIS)
        currentOnEnhancedReaderUnavailable()
    }

    LaunchedEffect(session, firstContentLoaded, searchQuery) {
        val query = searchQuery.trim().take(MAX_READER_SEARCH_QUERY_CHARS)
        if (!firstContentLoaded || query.isEmpty()) {
            searchMatches = emptyList()
            onSearchResultCountChanged(0)
            return@LaunchedEffect
        }
        delay(PDF_SEARCH_DEBOUNCE_MILLIS)
        val matches = ArrayList<Int>()
        var extractedChars = 0L
        for (pageIndex in 0 until session.pageCount) {
            val remaining = (MAX_PDF_TEXT_SCAN_CHARS - extractedChars).coerceAtLeast(0L)
            if (remaining == 0L || matches.size >= MAX_READER_SEARCH_RESULTS) break
            val text = extractPdfiumPageText(
                session,
                pageIndex,
                remaining.coerceAtMost(MAX_PDF_PAGE_TEXT_CHARS.toLong()).toInt(),
            )
            extractedChars += text.length
            var start = 0
            while (start < text.length && matches.size < MAX_READER_SEARCH_RESULTS) {
                val found = text.indexOf(query, startIndex = start, ignoreCase = true)
                if (found < 0) break
                matches += pageIndex
                start = found + query.length.coerceAtLeast(1)
            }
            if (pageIndex % PDF_TEXT_SCAN_YIELD_BATCH == 0) yield()
        }
        searchMatches = matches
        onSearchResultCountChanged(matches.size)
    }
    LaunchedEffect(searchMatches, selectedSearchIndex) {
        searchMatches.getOrNull(selectedSearchIndex)?.let { page ->
            listState.scrollToItem(page)
        }
    }

    LaunchedEffect(
        session,
        firstContentLoaded,
        preferences.chapterDetectionMode,
        preferences.customChapterRegex,
        preferences.chapterHeadingMaxChars,
    ) {
        if (!firstContentLoaded) {
            onChapterScanRunningChanged(false)
            onChaptersChanged(emptyList())
            return@LaunchedEffect
        }
        onChapterScanRunningChanged(true)
        onChaptersChanged(emptyList())
        try {
            val chapters = ArrayList<ReaderChapter>()
            var extractedChars = 0L
            for (pageIndex in 0 until session.pageCount) {
                val remaining = (MAX_PDF_TEXT_SCAN_CHARS - extractedChars).coerceAtLeast(0L)
                if (remaining == 0L || chapters.size >= MAX_READER_CHAPTERS) break
                val text = extractPdfiumPageText(
                    session,
                    pageIndex,
                    remaining.coerceAtMost(MAX_PDF_PAGE_TEXT_CHARS.toLong()).toInt(),
                )
                extractedChars += text.length
                chapters += detectReaderChaptersInTextBlocks(
                    pageIndex = pageIndex,
                    textBlocks = listOf(text),
                    preferences = preferences,
                ).take((MAX_READER_CHAPTERS - chapters.size).coerceAtLeast(0))
                if (pageIndex % PDF_TEXT_SCAN_YIELD_BATCH == 0 ||
                    pageIndex == session.pageCount - 1
                ) {
                    onChaptersChanged(collapseReaderChapterDuplicates(chapters))
                    yield()
                }
            }
            onChaptersChanged(collapseReaderChapterDuplicates(chapters))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            onChaptersChanged(emptyList())
        } finally {
            onChapterScanRunningChanged(false)
        }
    }

    ReaderPdfContinuousViewport(
        viewportKey = session,
        listState = listState,
        pageCount = session.pageCount,
        requestedZoomPercent = preferences.pdfZoomPercent,
        onZoomPercentChanged = onZoomPercentChanged,
        modifier = Modifier.fillMaxSize(),
    ) { pageIndex, pageWidth, targetWidthPx, horizontalOffsetPx ->
        PdfiumPage(
            session = session,
            pageIndex = pageIndex,
            displayWidth = pageWidth,
            targetWidthPx = targetWidthPx,
            horizontalOffsetPx = horizontalOffsetPx,
            background = background,
            foreground = foreground,
            enforceRenderTimeout = pageIndex == safeInitialPage,
            onRendered = {
                if (!firstContentLoaded) firstContentLoaded = true
            },
            onInitialRenderFailed = {
                if (pageIndex == safeInitialPage) {
                    currentOnEnhancedReaderUnavailable()
                }
            },
        )
    }
}

@Composable
private fun PdfiumPage(
    session: PdfiumDocumentSession,
    pageIndex: Int,
    displayWidth: androidx.compose.ui.unit.Dp,
    targetWidthPx: Int,
    horizontalOffsetPx: Float,
    background: Color,
    foreground: Color,
    enforceRenderTimeout: Boolean,
    onRendered: () -> Unit,
    onInitialRenderFailed: () -> Unit,
) {
    var lastImageWidthPx by remember(session, pageIndex) { mutableIntStateOf(0) }
    var lastImageHeightPx by remember(session, pageIndex) { mutableIntStateOf(0) }
    val rendered by key(session, pageIndex, targetWidthPx) {
        produceState<Result<PdfiumRenderedPage>?>(null, session, pageIndex, targetWidthPx) {
            val acquisition = ReaderPdfDetachedResource(
                workerScope = pdfiumWorkerScope,
                release = { it.recycle() },
            ) { guard ->
                renderPdfiumPage(session, pageIndex, targetWidthPx, guard)
            }
            var ownedPage: PdfiumRenderedPage? = null
            try {
                val result = acquisition.await(
                    if (enforceRenderTimeout) READER_PDF_FIRST_CONTENT_TIMEOUT_MILLIS else null,
                )
                ownedPage = result.getOrNull()
                value = result
                awaitCancellation()
            } finally {
                acquisition.abandon()
                withContext(NonCancellable + Dispatchers.IO) { ownedPage?.recycle() }
            }
        }
    }
    val bitmap = rendered?.getOrNull()?.bitmap
    LaunchedEffect(rendered) {
        when {
            bitmap != null -> {
                lastImageWidthPx = bitmap.width
                lastImageHeightPx = bitmap.height
                onRendered()
            }
            rendered?.isFailure == true -> onInitialRenderFailed()
        }
    }
    when {
        rendered == null && lastImageWidthPx > 0 && lastImageHeightPx > 0 ->
            ReaderPdfPageViewport(
                displayWidth = displayWidth,
                imageWidthPx = lastImageWidthPx,
                imageHeightPx = lastImageHeightPx,
                horizontalOffsetPx = horizontalOffsetPx,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        rendered == null -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxWidth().height(360.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        bitmap != null -> ReaderPdfPageViewport(
            displayWidth = displayWidth,
            imageWidthPx = bitmap.width,
            imageHeightPx = bitmap.height,
            horizontalOffsetPx = horizontalOffsetPx,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = tr("PDF 第 ${pageIndex + 1} 页", "PDF page ${pageIndex + 1}"),
                contentScale = ContentScale.FillBounds,
                colorFilter = pdfiumColorFilter(background, foreground),
            )
        }
        else -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tr("这一页无法显示", "This page could not be rendered"),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private suspend fun renderPdfiumPage(
    session: PdfiumDocumentSession,
    pageIndex: Int,
    targetWidthPx: Int,
    guard: ReaderPdfAcquisitionGuard,
): PdfiumRenderedPage {
    val bitmapOwner = ReaderPdfResourceOwner<Bitmap> { bitmap ->
        if (!bitmap.isRecycled) bitmap.recycle()
    }
    return try {
        val rendered = session.access { document ->
            guard.ensureWanted()
            val page = document.openPage(pageIndex)
            try {
                val renderSize = readerPdfRenderSize(
                    pageWidthPoints = page.getPageWidthPoint(),
                    pageHeightPoints = page.getPageHeightPoint(),
                    targetWidthPx = targetWidthPx,
                )
                val bitmap = bitmapOwner.own(
                    Bitmap.createBitmap(
                        renderSize.width,
                        renderSize.height,
                        Bitmap.Config.ARGB_8888,
                    ),
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.renderPageBitmap(
                    bitmap = bitmap,
                    startX = 0,
                    startY = 0,
                    drawSizeX = renderSize.width,
                    drawSizeY = renderSize.height,
                    renderAnnot = true,
                    textMask = false,
                )
                PdfiumRenderedPage(bitmap)
            } finally {
                page.close()
            }
        }
        bitmapOwner.transfer(rendered.bitmap)
        rendered
    } finally {
        bitmapOwner.releaseOwned()
    }
}

private fun PdfiumRenderedPage.recycle() {
    if (!bitmap.isRecycled) bitmap.recycle()
}

private suspend fun extractPdfiumPageText(
    session: PdfiumDocumentSession,
    pageIndex: Int,
    maximumChars: Int,
): String {
    if (maximumChars <= 0) return ""
    return try {
        session.access { document ->
            val page = document.openPage(pageIndex)
            try {
                val textPage = page.openTextPage()
                try {
                    val count = textPage.textPageCountChars().coerceIn(0, maximumChars)
                    if (count == 0) "" else textPage.textPageGetText(0, count).orEmpty()
                } finally {
                    textPage.close()
                }
            } finally {
                page.close()
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ""
    } catch (_: LinkageError) {
        ""
    }
}

private fun pdfiumColorFilter(background: Color, foreground: Color): ColorFilter? {
    val backgroundArgb = background.toArgb()
    val foregroundArgb = foreground.toArgb()
    return if (readerPdfColorTransformRequired(backgroundArgb, foregroundArgb)) {
        ColorFilter.colorMatrix(
            ColorMatrix(readerPdfColorMatrixValues(backgroundArgb, foregroundArgb)),
        )
    } else {
        null
    }
}

private const val MAX_PDFIUM_PAGES = 20_000
private const val MAX_PDF_PAGE_TEXT_CHARS = 1_000_000
private const val MAX_PDF_TEXT_SCAN_CHARS = 32L * 1024L * 1024L
private const val PDF_TEXT_SCAN_YIELD_BATCH = 24
private const val PDF_SEARCH_DEBOUNCE_MILLIS = 250L
