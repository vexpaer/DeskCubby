package com.deskcubby.app.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.repository.MAX_READER_CHAPTERS
import com.deskcubby.app.data.repository.MAX_READER_PDF_ZOOM_PERCENT
import com.deskcubby.app.data.repository.MAX_READER_SEARCH_QUERY_CHARS
import com.deskcubby.app.data.repository.MAX_READER_SEARCH_RESULTS
import com.deskcubby.app.data.repository.MIN_READER_PDF_ZOOM_PERCENT
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
import kotlin.math.roundToInt

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
private data class PdfZoomAnchor(
    val pageIndex: Int,
    val contentX: Float,
    val contentY: Float,
    val targetX: Float,
    val targetY: Float,
    val scale: Float,
)

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
    var gestureZoom by remember(session) { mutableFloatStateOf(1f) }
    // Live pinch transform: the content follows the fingers through a matrix scale around the
    // pinch centroid without re-rendering. When the gesture ends the final scale is committed to
    // gestureZoom, which re-renders pages at the new resolution, and the transform resets.
    var gestureScale by remember(session) { mutableFloatStateOf(1f) }
    var gestureTransformOrigin by remember(session) { mutableStateOf(TransformOrigin.Center) }
    // The same transform origin kept as plain floats: the anchor math inside the pointer block
    // reads these (the TransformOrigin component accessors are not resolvable there).
    var gestureTransformOriginX by remember(session) { mutableFloatStateOf(0.5f) }
    var gestureTransformOriginY by remember(session) { mutableFloatStateOf(0.5f) }
    // While the transient matrix is live, the pinch keeps the content under the centroid pinned
    // on screen. Committing the zoom re-renders the pages at the new width, so the same content
    // point must be scrolled back under the centroid afterwards, otherwise the reloaded pages
    // appear at a different size/position than what the user saw when the fingers lifted.
    var pendingZoomAnchor by remember(session) { mutableStateOf<PdfZoomAnchor?>(null) }
    var renderedPages by remember(session) { mutableStateOf<Set<Int>>(emptySet()) }
    var firstContentLoaded by remember(session) { mutableStateOf(false) }
    var searchMatches by remember(session) { mutableStateOf<List<Int>>(emptyList()) }
    var initialPositionRestored by rememberSaveable { mutableStateOf(false) }
    val currentOnPositionChanged by rememberUpdatedState(onCurrentPositionChanged)
    val currentOnEnhancedReaderUnavailable by rememberUpdatedState(onEnhancedReaderUnavailable)
    // Free 2D pan: instead of classifying a drag as purely horizontal or vertical, every drag
    // moves both axes at once. The vertical axis keeps going through the LazyColumn scrollable;
    // the horizontal axis is forwarded here in the pre-scroll phase so diagonal/circular drags
    // follow the finger on both axes simultaneously.
    var horizontalOffsetPx by remember(session) { mutableFloatStateOf(0f) }
    var maxHorizontalPx by remember(session) { mutableIntStateOf(0) }
    val twoDimensionalPan = remember(session) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.x == 0f || maxHorizontalPx <= 0) return Offset.Zero
                val previous = horizontalOffsetPx
                horizontalOffsetPx = (previous - available.x)
                    .coerceIn(0f, maxHorizontalPx.toFloat())
                return Offset(previous - horizontalOffsetPx, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (available.x == 0f || maxHorizontalPx <= 0) return Velocity.Zero
                val start = horizontalOffsetPx
                val end = (start - available.x * 0.12f)
                    .coerceIn(0f, maxHorizontalPx.toFloat())
                if (kotlin.math.abs(end - start) < 1f) return Velocity.Zero
                val animatable = Animatable(start)
                animatable.animateTo(end, animationSpec = tween(220)) {
                    horizontalOffsetPx = value
                }
                return Velocity(available.x, 0f)
            }
        }
    }

    LaunchedEffect(session, listState) {
        if (!initialPositionRestored) {
            listState.restoreReaderPagePosition(restoredPosition, session.pageCount)
            initialPositionRestored = true
        }
    }
    // After a pinch commits, wait until the anchor page has re-rendered at the new width, then
    // scroll both axes so the content that was under the centroid when the fingers lifted stays
    // under that same screen point. This keeps the reloaded PDF matching the pre-reload matrix.
    LaunchedEffect(session, pendingZoomAnchor, renderedPages) {
        val anchor = pendingZoomAnchor ?: return@LaunchedEffect
        if (anchor.pageIndex !in renderedPages) return@LaunchedEffect
        withFrameNanos { }
        horizontalOffsetPx = (anchor.contentX - anchor.targetX)
            .coerceIn(0f, maxHorizontalPx.coerceAtLeast(1).toFloat())
        val itemOffset = (anchor.targetY - anchor.contentY * anchor.scale).roundToInt()
        listState.scrollToItem(anchor.pageIndex, itemOffset)
        pendingZoomAnchor = null
    }
    LaunchedEffect(preferences.pdfZoomPercent) { gestureZoom = 1f }
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val effectiveZoomPercent = (preferences.pdfZoomPercent * gestureZoom)
            .roundToInt()
            .coerceIn(MIN_READER_PDF_ZOOM_PERCENT, MAX_READER_PDF_ZOOM_PERCENT)
        val pageViewportWidth = (maxWidth - 24.dp).coerceAtLeast(1.dp)
        val pageWidth = (pageViewportWidth * effectiveZoomPercent / 100f)
            .coerceAtLeast(160.dp)
        val targetWidthPx = with(density) { pageWidth.roundToPx() }
        val pageViewportWidthPx = with(density) { pageViewportWidth.roundToPx() }
        val calculatedMaxHorizontalPx = readerPdfMaxHorizontalOffset(
            viewportWidthPx = pageViewportWidthPx,
            contentWidthPx = targetWidthPx,
        )
        LaunchedEffect(session, calculatedMaxHorizontalPx) {
            maxHorizontalPx = calculatedMaxHorizontalPx
            horizontalOffsetPx = horizontalOffsetPx.coerceIn(
                0f,
                calculatedMaxHorizontalPx.toFloat(),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(twoDimensionalPan)
                .graphicsLayer {
                    scaleX = gestureScale
                    scaleY = gestureScale
                    transformOrigin = gestureTransformOrigin
                }
                .pointerInput(preferences.pdfZoomPercent) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoomChanged = false
                        var lastCentroid = Offset.Zero
                        var lastAnchorPage = -1
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val zoomChange = event.calculateZoom()
                                if (zoomChange.isFinite() && zoomChange > 0f) {
                                    val currentEffectiveZoom =
                                        preferences.pdfZoomPercent * gestureZoom
                                    val minimum = MIN_READER_PDF_ZOOM_PERCENT /
                                        currentEffectiveZoom
                                    val maximum = MAX_READER_PDF_ZOOM_PERCENT /
                                        currentEffectiveZoom
                                    gestureScale = (gestureScale * zoomChange)
                                        .coerceIn(minimum, maximum)
                                    val centroid = pressed.fold(Offset.Zero) { acc, change ->
                                        acc + change.position
                                    } / pressed.size.toFloat()
                                    gestureTransformOriginX =
                                        (centroid.x / size.width).coerceIn(0f, 1f)
                                    gestureTransformOriginY =
                                        (centroid.y / size.height).coerceIn(0f, 1f)
                                    gestureTransformOrigin = TransformOrigin(
                                        gestureTransformOriginX,
                                        gestureTransformOriginY,
                                    )
                                    lastCentroid = centroid
                                    lastAnchorPage = listState.layoutInfo
                                        .visibleItemsInfo
                                        .firstOrNull { item ->
                                            centroid.y >= item.offset &&
                                                centroid.y < item.offset + item.size
                                        }
                                        ?.index
                                        ?: lastAnchorPage
                                    zoomChanged = true
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (zoomChanged) {
                            // Commit the final scale: pages re-render at the new width, then the
                            // transient matrix resets so the rendered content matches the scale.
                            gestureZoom = (gestureZoom * gestureScale).coerceIn(
                                MIN_READER_PDF_ZOOM_PERCENT /
                                    preferences.pdfZoomPercent.toFloat(),
                                MAX_READER_PDF_ZOOM_PERCENT /
                                    preferences.pdfZoomPercent.toFloat(),
                            )
                            // Persist the committed percentage so a later reload keeps the zoom.
                            if (gestureScale != 1f) {
                                onZoomPercentChanged(
                                    (preferences.pdfZoomPercent * gestureZoom)
                                        .roundToInt()
                                        .coerceIn(
                                            MIN_READER_PDF_ZOOM_PERCENT,
                                            MAX_READER_PDF_ZOOM_PERCENT,
                                        ),
                                )
                            }
                            val anchorPage = lastAnchorPage
                            if (anchorPage >= 0 && gestureScale != 1f) {
                                val item = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == anchorPage }
                                if (item != null) {
                                    val originX = gestureTransformOriginX * size.width
                                    val originY = gestureTransformOriginY * item.size
                                    val unzoomedX = originX +
                                        (lastCentroid.x - originX) / gestureScale
                                    val unzoomedY = originY +
                                        (lastCentroid.y - originY) / gestureScale
                                    pendingZoomAnchor = PdfZoomAnchor(
                                        pageIndex = anchorPage,
                                        contentX = unzoomedX - horizontalOffsetPx,
                                        contentY = unzoomedY - item.offset,
                                        targetX = lastCentroid.x,
                                        targetY = lastCentroid.y,
                                        scale = gestureScale,
                                    )
                                }
                            }
                        }
                        gestureScale = 1f
                        gestureTransformOriginX = 0.5f
                        gestureTransformOriginY = 0.5f
                        gestureTransformOrigin = TransformOrigin.Center
                    }
                },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(session.pageCount, key = { it }) { pageIndex ->
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
                        renderedPages = renderedPages + pageIndex
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
            bitmap != null -> onRendered()
            rendered?.isFailure == true -> onInitialRenderFailed()
        }
    }
    when {
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
