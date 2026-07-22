package com.deskcubby.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.deskcubby.app.ui.theme.tr

/** A compact, touch-friendly four-dot handle used by all reorderable lists. */
@Composable
fun FourDotDragHandle(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDragStarted: () -> Unit = {},
    onDragChanged: (verticalDistancePx: Float) -> Unit = {},
    onDragCancelled: () -> Unit = {},
    translateSelf: Boolean = true,
    onDragFinished: (verticalDistancePx: Float) -> Unit,
) {
    val description = tr("拖动排序", "Drag to reorder")
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)
    val currentOnDragChanged by rememberUpdatedState(onDragChanged)
    val currentOnDragCancelled by rememberUpdatedState(onDragCancelled)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    var distance by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val dotColor = if (dragging) {
        MaterialTheme.colorScheme.primary
    } else if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Canvas(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                translationY = if (translateSelf) distance else 0f
                scaleX = if (dragging) 1.12f else 1f
                scaleY = if (dragging) 1.12f else 1f
            }
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (enabled) {
                    detectDragGestures(
                        onDragStart = {
                            distance = 0f
                            dragging = true
                            currentOnDragStarted()
                            currentOnDragChanged(0f)
                        },
                        onDragCancel = {
                            distance = 0f
                            dragging = false
                            currentOnDragCancelled()
                        },
                        onDragEnd = {
                            val completedDistance = distance
                            distance = 0f
                            dragging = false
                            currentOnDragFinished(completedDistance)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            distance += dragAmount.y
                            currentOnDragChanged(distance)
                        },
                    )
                }
            },
    ) {
        drawFourDots(dotColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFourDots(color: Color) {
    val gap = 8.dp.toPx()
    val radius = 2.4.dp.toPx()
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    listOf(-0.5f, 0.5f).forEach { x ->
        listOf(-0.5f, 0.5f).forEach { y ->
            drawCircle(color = color, radius = radius, center = Offset(centerX + x * gap, centerY + y * gap))
        }
    }
}
