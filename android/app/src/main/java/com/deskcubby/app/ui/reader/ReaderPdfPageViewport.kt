package com.deskcubby.app.ui.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Measures the PDF page at its requested zoom width, even when that width exceeds the viewport. */
@Composable
internal fun ReaderPdfPageViewport(
    displayWidth: Dp,
    imageWidthPx: Int,
    imageHeightPx: Int,
    horizontalOffsetPx: Float,
    content: @Composable () -> Unit,
) {
    require(imageWidthPx > 0 && imageHeightPx > 0)
    val density = LocalDensity.current
    val requestedWidthPx = with(density) { displayWidth.roundToPx() }.coerceAtLeast(1)
    val requestedHeightPx = (
        requestedWidthPx.toDouble() * imageHeightPx.toDouble() / imageWidthPx.toDouble()
    ).roundToInt().coerceAtLeast(1)

    Layout(
        content = content,
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .padding(vertical = 2.dp),
    ) { measurables, constraints ->
        val viewportWidth = constraints.maxWidth.coerceAtLeast(1)
        val placement = readerPdfPagePlacement(
            viewportWidthPx = viewportWidth,
            contentWidthPx = requestedWidthPx,
            requestedHorizontalOffsetPx = horizontalOffsetPx,
        )
        val placeable = measurables.single().measure(
            Constraints.fixed(requestedWidthPx, requestedHeightPx),
        )
        layout(viewportWidth, requestedHeightPx) {
            placeable.placeRelative(placement.contentX, 0)
        }
    }
}
