package com.deskcubby.app.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.repository.MAX_READER_PDF_ZOOM_PERCENT
import com.deskcubby.app.data.repository.MIN_READER_PDF_ZOOM_PERCENT
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One continuous PDF viewport shared by PDFium and the system PdfRenderer fallback.
 *
 * This layer reads the raw path before LazyColumn applies its vertical direction lock. For one
 * finger it applies the independent X component while leaving Y to LazyColumn, so diagonal paths
 * move both axes without sacrificing native vertical fling. Multi-touch owns both axes and adds
 * zoom around the gesture centroid.
 */
@Composable
internal fun ReaderPdfContinuousViewport(
    viewportKey: Any,
    listState: LazyListState,
    pageCount: Int,
    requestedZoomPercent: Int,
    onZoomPercentChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    pageContent: @Composable LazyItemScope.(
        pageIndex: Int,
        displayWidth: Dp,
        targetWidthPx: Int,
        horizontalOffsetPx: Float,
    ) -> Unit,
) {
    require(pageCount > 0)
    var localZoomPercent by remember(viewportKey) {
        mutableIntStateOf(
            requestedZoomPercent.coerceIn(
                MIN_READER_PDF_ZOOM_PERCENT,
                MAX_READER_PDF_ZOOM_PERCENT,
            ),
        )
    }
    var horizontalOffsetPx by remember(viewportKey) { mutableFloatStateOf(0f) }
    var transientScale by remember(viewportKey) { mutableFloatStateOf(1f) }
    var transientOrigin by remember(viewportKey) { mutableStateOf(TransformOrigin.Center) }
    val viewportScope = rememberCoroutineScope()
    val currentOnZoomPercentChanged by rememberUpdatedState(onZoomPercentChanged)

    LaunchedEffect(requestedZoomPercent) {
        if (transientScale == 1f) {
            localZoomPercent = requestedZoomPercent.coerceIn(
                MIN_READER_PDF_ZOOM_PERCENT,
                MAX_READER_PDF_ZOOM_PERCENT,
            )
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val horizontalPadding = 12.dp
        val verticalPadding = 8.dp
        val pageViewportWidth = (maxWidth - horizontalPadding * 2).coerceAtLeast(1.dp)
        val pageViewportWidthPx = with(density) { pageViewportWidth.roundToPx() }
        val minimumPageWidthPx = with(density) { MIN_READER_PDF_PAGE_WIDTH.roundToPx() }
        val targetWidthPx = readerPdfContentWidthPx(
            viewportWidthPx = pageViewportWidthPx,
            minimumPageWidthPx = minimumPageWidthPx,
            zoomPercent = localZoomPercent,
        )
        val pageWidth = with(density) { targetWidthPx.toDp() }
        val maxHorizontalOffsetPx = readerPdfMaxHorizontalOffset(
            viewportWidthPx = pageViewportWidthPx,
            contentWidthPx = targetWidthPx,
        ).toFloat()
        val currentZoom by rememberUpdatedState(localZoomPercent)
        val currentTargetWidthPx by rememberUpdatedState(targetWidthPx)
        val currentMaximumOffsetPx by rememberUpdatedState(maxHorizontalOffsetPx)

        LaunchedEffect(viewportKey, maxHorizontalOffsetPx) {
            horizontalOffsetPx = horizontalOffsetPx.coerceIn(0f, maxHorizontalOffsetPx)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = transientScale
                    scaleY = transientScale
                    transformOrigin = transientOrigin
                }
                .pointerInput(viewportKey, pageViewportWidthPx, minimumPageWidthPx) {
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var singleFingerDragStarted = false
                        var accumulatedSingleFingerPan = Offset.Zero
                        var pinching = false
                        var zoomAtPinchStart = currentZoom
                        var widthAtPinchStart = currentTargetWidthPx
                        var lastCentroid = Offset(size.width / 2f, size.height / 2f)

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val centroid = pressed.fold(Offset.Zero) { value, change ->
                                    value + change.position
                                } / pressed.size.toFloat()
                                if (!pinching) {
                                    pinching = true
                                    zoomAtPinchStart = currentZoom
                                    widthAtPinchStart = currentTargetWidthPx
                                    transientScale = 1f
                                    transientOrigin = TransformOrigin(
                                        (centroid.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                                        (centroid.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                                    )
                                }
                                lastCentroid = centroid
                                val zoomChange = event.calculateZoom()
                                if (zoomChange.isFinite() && zoomChange > 0f) {
                                    val minimumScale =
                                        MIN_READER_PDF_ZOOM_PERCENT / zoomAtPinchStart.toFloat()
                                    val maximumScale =
                                        MAX_READER_PDF_ZOOM_PERCENT / zoomAtPinchStart.toFloat()
                                    transientScale = (transientScale * zoomChange)
                                        .coerceIn(minimumScale, maximumScale)
                                }
                                val pan = event.calculatePan()
                                val scaleCompensation = transientScale.coerceAtLeast(0.01f)
                                val panUpdate = readerPdfPanUpdate(
                                    currentHorizontalOffsetPx = horizontalOffsetPx,
                                    maxHorizontalOffsetPx = currentMaximumOffsetPx,
                                    pointerPanXPx = pan.x / scaleCompensation,
                                    pointerPanYPx = pan.y / scaleCompensation,
                                )
                                horizontalOffsetPx = panUpdate.horizontalOffsetPx
                                listState.dispatchRawDelta(panUpdate.verticalScrollDeltaPx)
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && !pinching) {
                                val change = pressed.single()
                                val pointerPan = change.position - change.previousPosition
                                val appliedPan = if (!singleFingerDragStarted) {
                                    accumulatedSingleFingerPan += pointerPan
                                    val distance = accumulatedSingleFingerPan.getDistance()
                                    if (distance > viewConfiguration.touchSlop) {
                                        singleFingerDragStarted = true
                                        accumulatedSingleFingerPan *
                                            (1f - viewConfiguration.touchSlop / distance)
                                    } else {
                                        Offset.Zero
                                    }
                                } else {
                                    pointerPan
                                }
                                if (singleFingerDragStarted) {
                                    horizontalOffsetPx = readerPdfHorizontalOffsetAfterPan(
                                        currentOffsetPx = horizontalOffsetPx,
                                        maxOffsetPx = currentMaximumOffsetPx,
                                        pointerPanXPx = appliedPan.x,
                                    )
                                    // Do not consume the one-finger change: LazyColumn still sees
                                    // the Y component and retains its normal drag/fling behavior.
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }

                        if (pinching) {
                            val committedZoom = (zoomAtPinchStart * transientScale)
                                .roundToInt()
                                .coerceIn(
                                    MIN_READER_PDF_ZOOM_PERCENT,
                                    MAX_READER_PDF_ZOOM_PERCENT,
                                )
                            if (committedZoom != zoomAtPinchStart) {
                                val anchorItem = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { item ->
                                        lastCentroid.y >= item.offset &&
                                            lastCentroid.y <= item.offset + item.size
                                    }
                                val anchorPage = anchorItem?.index
                                val anchorFractionY = anchorItem?.let { item ->
                                    ((lastCentroid.y - item.offset) / item.size.coerceAtLeast(1))
                                        .coerceIn(0f, 1f)
                                }
                                val newContentWidthPx = readerPdfContentWidthPx(
                                    viewportWidthPx = pageViewportWidthPx,
                                    minimumPageWidthPx = minimumPageWidthPx,
                                    zoomPercent = committedZoom,
                                )
                                horizontalOffsetPx = readerPdfHorizontalOffsetAfterZoom(
                                    viewportWidthPx = pageViewportWidthPx,
                                    oldContentWidthPx = widthAtPinchStart,
                                    newContentWidthPx = newContentWidthPx,
                                    oldOffsetPx = horizontalOffsetPx,
                                    anchorViewportXPx = lastCentroid.x - horizontalPadding.toPx(),
                                )
                                localZoomPercent = committedZoom
                                currentOnZoomPercentChanged(committedZoom)
                                transientScale = 1f
                                transientOrigin = TransformOrigin.Center

                                if (anchorPage != null && anchorFractionY != null) {
                                    // The page layout changes immediately with zoom. Re-anchor the
                                    // same relative point after that layout is committed.
                                    val targetY = lastCentroid.y
                                    viewportScope.launch {
                                        withFrameNanos { }
                                        listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == anchorPage }
                                            ?.let { item ->
                                                val movedPointY =
                                                    item.offset + item.size * anchorFractionY
                                                listState.dispatchRawDelta(movedPointY - targetY)
                                            }
                                    }
                                }
                            } else {
                                transientScale = 1f
                                transientOrigin = TransformOrigin.Center
                            }
                        }
                    }
                },
            contentPadding = PaddingValues(
                horizontal = horizontalPadding,
                vertical = verticalPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pageCount, key = { it }) { pageIndex ->
                pageContent(
                    pageIndex,
                    pageWidth,
                    targetWidthPx,
                    horizontalOffsetPx,
                )
            }
        }
    }
}

private val MIN_READER_PDF_PAGE_WIDTH = 160.dp
