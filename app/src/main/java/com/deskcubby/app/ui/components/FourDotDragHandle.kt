package com.deskcubby.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
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
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null,
    translateSelf: Boolean = true,
    onDragFinished: (verticalDistancePx: Float) -> Unit,
) {
    val description = tr("拖动排序", "Drag to reorder")
    val moveUpDescription = tr("上移", "Move up")
    val moveDownDescription = tr("下移", "Move down")
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
    val dragState = rememberDraggableState { delta ->
        distance += delta
        currentOnDragChanged(distance)
    }

    Canvas(
        modifier = modifier
            .size(48.dp)
            .graphicsLayer {
                translationY = if (translateSelf) distance else 0f
                scaleX = if (dragging) 1.12f else 1f
                scaleY = if (dragging) 1.12f else 1f
            }
            .semantics {
                contentDescription = description
                customActions = buildList {
                    onMoveUp?.let { action ->
                        add(CustomAccessibilityAction(moveUpDescription, action))
                    }
                    onMoveDown?.let { action ->
                        add(CustomAccessibilityAction(moveDownDescription, action))
                    }
                }
            }
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                enabled = enabled,
                startDragImmediately = true,
                onDragStarted = {
                    distance = 0f
                    dragging = true
                    currentOnDragStarted()
                    currentOnDragChanged(0f)
                },
                onDragStopped = {
                    val finalDistance = distance
                    distance = 0f
                    dragging = false
                    currentOnDragFinished(finalDistance)
                },
            ),
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
