package com.deskcubby.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
    onDragOffsetChanged: ((Offset) -> Unit)? = null,
    onDragCancelled: () -> Unit = {},
    onMoveUp: (() -> Boolean)? = null,
    onMoveDown: (() -> Boolean)? = null,
    translateSelf: Boolean = true,
    onDragFinished: (verticalDistancePx: Float) -> Unit,
    onDragOffsetFinished: ((Offset) -> Unit)? = null,
) {
    val description = tr("拖动排序", "Drag to reorder")
    val moveUpDescription = tr("上移", "Move up")
    val moveDownDescription = tr("下移", "Move down")
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)
    val currentOnDragChanged by rememberUpdatedState(onDragChanged)
    val currentOnDragOffsetChanged by rememberUpdatedState(onDragOffsetChanged)
    val currentOnDragCancelled by rememberUpdatedState(onDragCancelled)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    val currentOnDragOffsetFinished by rememberUpdatedState(onDragOffsetFinished)
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
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
                translationY = if (translateSelf) dragOffset.y else 0f
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
            .pointerInput(enabled) {
                awaitEachGesture {
                    // Consume the initial press so a tap on the handle cannot become a click on
                    // the enclosing navigation card, while still waiting for touch slop to drag.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (!enabled) return@awaitEachGesture

                    var overSlop = Offset.Zero
                    val dragStart = awaitTouchSlopOrCancellation(down.id) { change, amount ->
                        change.consume()
                        overSlop = amount
                    } ?: return@awaitEachGesture

                    dragOffset = Offset.Zero
                    dragging = true
                    currentOnDragStarted()
                    currentOnDragChanged(0f)
                    currentOnDragOffsetChanged?.invoke(Offset.Zero)

                    fun applyDrag(amount: Offset) {
                        dragOffset += amount
                        currentOnDragChanged(dragOffset.y)
                        currentOnDragOffsetChanged?.invoke(dragOffset)
                    }
                    applyDrag(overSlop)

                    val completed = drag(dragStart.id) { change ->
                        val amount = change.positionChange()
                        change.consume()
                        applyDrag(amount)
                    }
                    if (completed) {
                        val finalOffset = dragOffset
                        dragOffset = Offset.Zero
                        dragging = false
                        currentOnDragFinished(finalOffset.y)
                        currentOnDragOffsetFinished?.invoke(finalOffset)
                    } else {
                        dragOffset = Offset.Zero
                        dragging = false
                        currentOnDragCancelled()
                    }
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
