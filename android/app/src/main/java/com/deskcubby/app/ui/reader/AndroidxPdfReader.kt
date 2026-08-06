package com.deskcubby.app.ui.reader

import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.pdf.Highlight
import androidx.pdf.PdfDocument
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.content.PageMatchBounds
import androidx.pdf.view.PdfView
import com.deskcubby.app.data.repository.MAX_READER_CHAPTERS
import com.deskcubby.app.data.repository.MAX_READER_SEARCH_QUERY_CHARS
import com.deskcubby.app.data.repository.MAX_READER_SEARCH_RESULTS
import com.deskcubby.app.data.repository.ReaderChapter
import com.deskcubby.app.data.repository.ReaderPreferences
import com.deskcubby.app.data.repository.collapseReaderChapterDuplicates
import com.deskcubby.app.data.repository.detectReaderChaptersInTextBlocks
import com.deskcubby.app.ui.theme.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

private data class AndroidxPdfSearchMatch(
    val pageIndex: Int,
    val bounds: List<RectF>,
)

@RequiresApi(Build.VERSION_CODES.P)
@Composable
internal fun AndroidxPdfReader(
    uri: Uri,
    initialPage: Int,
    preferences: ReaderPreferences,
    textFeaturesAvailable: Boolean,
    background: Color,
    modifier: Modifier = Modifier,
    requestedPage: Int?,
    searchQuery: String,
    selectedSearchIndex: Int,
    onRequestedPageConsumed: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onSearchResultCountChanged: (Int) -> Unit,
    onChaptersChanged: (List<ReaderChapter>) -> Unit,
    onChapterScanRunningChanged: (Boolean) -> Unit,
    onEnhancedReaderUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val documentResult by produceState<Result<PdfDocument>?>(null, uri) {
        value = runReaderPdfLoadWithTimeout(READER_PDF_DOCUMENT_OPEN_TIMEOUT_MILLIS) {
            SandboxedPdfLoader(context).openDocument(uri)
        }
    }
    val document = documentResult?.getOrNull()
    val currentOnEnhancedReaderUnavailable by rememberUpdatedState(onEnhancedReaderUnavailable)
    DisposableEffect(document) {
        onDispose { document?.close() }
    }
    LaunchedEffect(documentResult) {
        if (documentResult?.isFailure == true) currentOnEnhancedReaderUnavailable()
    }

    Box(modifier = modifier.background(background), contentAlignment = Alignment.Center) {
        when {
            documentResult == null -> CircularProgressIndicator()
            document == null -> Text(
                tr("PDF 无法打开", "The PDF could not be opened"),
                color = Color.Red,
            )
            else -> AndroidxPdfDocumentView(
                document = document,
                initialPage = initialPage,
                preferences = preferences,
                textFeaturesAvailable = textFeaturesAvailable,
                background = background,
                requestedPage = requestedPage,
                searchQuery = searchQuery,
                selectedSearchIndex = selectedSearchIndex,
                onRequestedPageConsumed = onRequestedPageConsumed,
                onCurrentPageChanged = onCurrentPageChanged,
                onSearchResultCountChanged = onSearchResultCountChanged,
                onChaptersChanged = onChaptersChanged,
                onChapterScanRunningChanged = onChapterScanRunningChanged,
                onEnhancedReaderUnavailable = onEnhancedReaderUnavailable,
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun AndroidxPdfDocumentView(
    document: PdfDocument,
    initialPage: Int,
    preferences: ReaderPreferences,
    textFeaturesAvailable: Boolean,
    background: Color,
    requestedPage: Int?,
    searchQuery: String,
    selectedSearchIndex: Int,
    onRequestedPageConsumed: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onSearchResultCountChanged: (Int) -> Unit,
    onChaptersChanged: (List<ReaderChapter>) -> Unit,
    onChapterScanRunningChanged: (Boolean) -> Unit,
    onEnhancedReaderUnavailable: () -> Unit,
) {
    var pdfView by remember(document) { mutableStateOf<PdfView?>(null) }
    var fitWidthZoom by remember(document) { mutableStateOf<Float?>(null) }
    var firstContentLoaded by remember(document) { mutableStateOf(false) }
    var searchMatches by remember(document) {
        mutableStateOf<List<AndroidxPdfSearchMatch>>(emptyList())
    }
    val currentOnPageChanged by rememberUpdatedState(onCurrentPageChanged)
    val currentOnEnhancedReaderUnavailable by rememberUpdatedState(onEnhancedReaderUnavailable)
    val currentPdfZoomPercent by rememberUpdatedState(preferences.pdfZoomPercent)
    val safeInitialPage = initialPage.coerceIn(0, document.pageCount - 1)

    AndroidView(
        factory = { viewContext ->
            PdfView(viewContext).apply {
                pagesPerRow = 1
                verticalAlignment = PdfView.VERTICAL_ALIGNMENT_TOP
                setBackgroundColor(background.toArgb())
                var lastReportedPage = -1
                addOnViewportChangedListener(
                    object : PdfView.OnViewportChangedListener {
                        override fun onViewportChanged(
                            firstVisiblePage: Int,
                            visiblePagesCount: Int,
                            pageLocations: android.util.SparseArray<RectF>,
                            zoomLevel: Float,
                        ) {
                            if (visiblePagesCount > 0 && firstVisiblePage != lastReportedPage) {
                                lastReportedPage = firstVisiblePage
                                currentOnPageChanged(firstVisiblePage)
                            }
                        }
                    },
                )
                addOnFirstContentLoadListener {
                    firstContentLoaded = true
                    if (fitWidthZoom == null) fitWidthZoom = zoom
                    fitWidthZoom?.let { fit ->
                        zoom = (fit * currentPdfZoomPercent / 100f)
                            .coerceIn(minZoom, maxZoom)
                    }
                    scrollToPage(safeInitialPage)
                }
                pdfView = this
            }
        },
        update = { view ->
            view.setBackgroundColor(background.toArgb())
            if (view.pdfDocument !== document) view.pdfDocument = document
        },
        modifier = Modifier.fillMaxSize(),
    )

    if (!firstContentLoaded) {
        CircularProgressIndicator()
    }

    LaunchedEffect(document, pdfView, firstContentLoaded) {
        if (pdfView == null || firstContentLoaded) return@LaunchedEffect
        delay(READER_PDF_FIRST_CONTENT_TIMEOUT_MILLIS)
        currentOnEnhancedReaderUnavailable()
    }

    LaunchedEffect(pdfView, fitWidthZoom, preferences.pdfZoomPercent) {
        val view = pdfView ?: return@LaunchedEffect
        val fit = fitWidthZoom ?: return@LaunchedEffect
        view.zoom = (fit * preferences.pdfZoomPercent / 100f)
            .coerceIn(view.minZoom, view.maxZoom)
    }

    LaunchedEffect(pdfView, requestedPage) {
        val page = requestedPage ?: return@LaunchedEffect
        pdfView?.scrollToPage(page.coerceIn(0, document.pageCount - 1))
        onRequestedPageConsumed()
    }

    LaunchedEffect(document, firstContentLoaded, textFeaturesAvailable, searchQuery) {
        val query = searchQuery.trim().take(MAX_READER_SEARCH_QUERY_CHARS)
        if (!firstContentLoaded || !textFeaturesAvailable || query.isEmpty()) {
            searchMatches = emptyList()
            onSearchResultCountChanged(0)
            pdfView?.let { runCatching { it.setHighlights(emptyList()) } }
            return@LaunchedEffect
        }
        delay(250L)
        val result = runCatching {
            document.searchDocument(query, 0 until document.pageCount)
        }.getOrNull()
        searchMatches = if (result == null) {
            emptyList()
        } else {
            buildList {
                for (sparseIndex in 0 until result.size()) {
                    val pageIndex = result.keyAt(sparseIndex)
                    result.valueAt(sparseIndex).forEach { match: PageMatchBounds ->
                        if (size >= MAX_READER_SEARCH_RESULTS) return@buildList
                        add(
                            AndroidxPdfSearchMatch(
                                pageIndex = pageIndex,
                                bounds = match.bounds.map(::RectF),
                            ),
                        )
                    }
                }
            }
        }
        onSearchResultCountChanged(searchMatches.size)
    }

    LaunchedEffect(pdfView, searchMatches, selectedSearchIndex) {
        val view = pdfView ?: return@LaunchedEffect
        val selected = searchMatches.getOrNull(
            selectedSearchIndex.coerceIn(0, (searchMatches.size - 1).coerceAtLeast(0)),
        )
        val normalColor = 0x66FFD54F
        val selectedColor = 0xAAFF9800.toInt()
        val highlights = buildList {
            searchMatches.forEachIndexed { index, match ->
                match.bounds.forEach { bounds ->
                    if (size < MAX_PDF_SEARCH_HIGHLIGHT_RECTS) {
                        add(
                            Highlight(
                                PdfRect(match.pageIndex, bounds),
                                if (index == selectedSearchIndex) selectedColor else normalColor,
                            ),
                        )
                    }
                }
            }
        }
        runCatching { view.setHighlights(highlights) }
        selected?.let { view.scrollToPage(it.pageIndex) }
    }

    LaunchedEffect(
        document,
        firstContentLoaded,
        textFeaturesAvailable,
        preferences.chapterDetectionMode,
        preferences.customChapterRegex,
        preferences.chapterHeadingMaxChars,
    ) {
        if (!firstContentLoaded || !textFeaturesAvailable) {
            onChapterScanRunningChanged(false)
            onChaptersChanged(emptyList())
            return@LaunchedEffect
        }
        onChapterScanRunningChanged(true)
        onChaptersChanged(emptyList())
        try {
            val chapters = ArrayList<ReaderChapter>()
            var extractedChars = 0L
            for (pageIndex in 0 until document.pageCount) {
                val blocks = runCatching {
                    document.getPageContent(pageIndex)?.textContents.orEmpty().map { it.text }
                }.getOrDefault(emptyList())
                extractedChars += blocks.sumOf(String::length)
                if (extractedChars > MAX_PDF_TOC_EXTRACTED_CHARS) break
                chapters += detectReaderChaptersInTextBlocks(
                    pageIndex = pageIndex,
                    textBlocks = blocks,
                    preferences = preferences,
                ).take((MAX_READER_CHAPTERS - chapters.size).coerceAtLeast(0))
                if (pageIndex % PDF_TOC_PROGRESS_BATCH == 0 ||
                    pageIndex == document.pageCount - 1
                ) {
                    onChaptersChanged(collapseReaderChapterDuplicates(chapters))
                    yield()
                }
                if (chapters.size >= MAX_READER_CHAPTERS) break
            }
            onChaptersChanged(collapseReaderChapterDuplicates(chapters))
        } finally {
            onChapterScanRunningChanged(false)
        }
    }
}

private const val MAX_PDF_SEARCH_HIGHLIGHT_RECTS = 10_000
private const val MAX_PDF_TOC_EXTRACTED_CHARS = 32L * 1024L * 1024L
private const val PDF_TOC_PROGRESS_BATCH = 24
