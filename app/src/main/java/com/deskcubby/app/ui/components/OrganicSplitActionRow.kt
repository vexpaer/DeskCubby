package com.deskcubby.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Size presets for [OrganicSplitActionRow]. */
enum class OrganicSplitActionRowSize {
    /** A two-line row suitable for settings menus and feature-level navigation. */
    REGULAR,

    /** A denser row suitable for month lists and other repeated actions. */
    COMPACT,
}

/** Public dimensions shared by organic split-action rows. */
object OrganicSplitActionRowDefaults {
    val Gap: Dp = 5.dp
    val RegularMinHeight: Dp = 80.dp
    val RegularActionWidth: Dp = 64.dp
    val CompactMinHeight: Dp = 56.dp
    val CompactActionWidth: Dp = 52.dp
}

/**
 * A matte, two-part organic row with independently clickable body and action regions.
 *
 * [body] receives a [RowScope], while [action] is centered in a [BoxScope]. The two
 * surfaces always share a height, but remain separate accessibility nodes and hit targets.
 * Their matching diagonal edges and directional padding are mirrored automatically in RTL.
 */
@Composable
fun OrganicSplitActionRow(
    body: @Composable RowScope.() -> Unit,
    action: @Composable BoxScope.() -> Unit,
    onBodyClick: () -> Unit,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: OrganicSplitActionRowSize = OrganicSplitActionRowSize.REGULAR,
    bodyColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    actionColor: Color = MaterialTheme.colorScheme.primary,
    bodyContentColor: Color = organicReadableContentColor(bodyColor),
    actionContentColor: Color = organicReadableContentColor(actionColor),
    bodyClickLabel: String? = null,
    actionClickLabel: String? = null,
) {
    val metrics = metricsFor(size)
    val bodyShape = SlantedBodyShape(
        cornerRadius = metrics.cornerRadius,
        cut = metrics.cut,
        joinRadius = metrics.joinRadius,
    )
    val actionShape = SlantedActionShape(
        cornerRadius = metrics.cornerRadius,
        cut = metrics.cut,
        joinRadius = metrics.joinRadius,
    )

    Row(
        modifier = modifier
            .heightIn(min = metrics.minHeight)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(OrganicSplitActionRowDefaults.Gap),
    ) {
        MatteClickableSurface(
            onClick = onBodyClick,
            onClickLabel = bodyClickLabel,
            shape = bodyShape,
            color = bodyColor,
            contentColor = bodyContentColor,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(metrics.bodyPadding),
                verticalAlignment = Alignment.CenterVertically,
                content = body,
            )
        }

        MatteClickableSurface(
            onClick = onActionClick,
            onClickLabel = actionClickLabel,
            shape = actionShape,
            color = actionColor,
            contentColor = actionContentColor,
            modifier = Modifier
                .width(metrics.actionWidth)
                .fillMaxHeight(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(metrics.actionPadding),
                contentAlignment = Alignment.Center,
                content = action,
            )
        }
    }
}

@Composable
private fun MatteClickableSurface(
    onClick: () -> Unit,
    onClickLabel: String?,
    shape: Shape,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .clip(shape)
            .clickable(
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = shape,
        color = color,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

private data class OrganicSplitActionRowMetrics(
    val minHeight: Dp,
    val actionWidth: Dp,
    val cornerRadius: Dp,
    val cut: Dp,
    val joinRadius: Dp,
    val bodyPadding: PaddingValues,
    val actionPadding: PaddingValues,
)

private fun metricsFor(size: OrganicSplitActionRowSize): OrganicSplitActionRowMetrics = when (size) {
    OrganicSplitActionRowSize.REGULAR -> OrganicSplitActionRowMetrics(
        minHeight = OrganicSplitActionRowDefaults.RegularMinHeight,
        actionWidth = OrganicSplitActionRowDefaults.RegularActionWidth,
        cornerRadius = 16.dp,
        cut = 18.dp,
        joinRadius = 4.dp,
        bodyPadding = PaddingValues(start = 16.dp, top = 10.dp, end = 28.dp, bottom = 10.dp),
        actionPadding = PaddingValues(start = 8.dp),
    )

    OrganicSplitActionRowSize.COMPACT -> OrganicSplitActionRowMetrics(
        minHeight = OrganicSplitActionRowDefaults.CompactMinHeight,
        actionWidth = OrganicSplitActionRowDefaults.CompactActionWidth,
        cornerRadius = 13.dp,
        cut = 15.dp,
        joinRadius = 3.dp,
        bodyPadding = PaddingValues(start = 14.dp, top = 7.dp, end = 23.dp, bottom = 7.dp),
        actionPadding = PaddingValues(start = 6.dp),
    )
}

private class SlantedBodyShape(
    private val cornerRadius: Dp,
    private val cut: Dp,
    private val joinRadius: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = slantedBodyOutline(
        size = size,
        layoutDirection = layoutDirection,
        cornerRadiusPx = with(density) { cornerRadius.toPx() },
        cutPx = with(density) { cut.toPx() },
        joinRadiusPx = with(density) { joinRadius.toPx() },
    )
}

private class SlantedActionShape(
    private val cornerRadius: Dp,
    private val cut: Dp,
    private val joinRadius: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = slantedActionOutline(
        size = size,
        layoutDirection = layoutDirection,
        cornerRadiusPx = with(density) { cornerRadius.toPx() },
        cutPx = with(density) { cut.toPx() },
        joinRadiusPx = with(density) { joinRadius.toPx() },
    )
}

private fun slantedBodyOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    cornerRadiusPx: Float,
    cutPx: Float,
    joinRadiusPx: Float,
): Outline {
    val radius = cornerRadiusPx.coerceIn(0f, minOf(size.width, size.height) / 2f)
    val cut = cutPx.coerceIn(0f, (size.width - radius).coerceAtLeast(0f))
    val join = joinRadiusPx.coerceIn(0f, minOf(cut / 3f, size.height / 4f))
    val path = LogicalPath(size.width, layoutDirection).apply {
        moveTo(radius, 0f)
        lineTo(size.width - join, 0f)
        quadraticBezierTo(size.width, 0f, size.width - join * 0.5f, join)
        lineTo(size.width - cut + join * 0.5f, size.height - join)
        quadraticBezierTo(
            size.width - cut,
            size.height,
            size.width - cut - join,
            size.height,
        )
        lineTo(radius, size.height)
        quadraticBezierTo(0f, size.height, 0f, size.height - radius)
        lineTo(0f, radius)
        quadraticBezierTo(0f, 0f, radius, 0f)
        close()
    }.path
    return Outline.Generic(path)
}

private fun slantedActionOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    cornerRadiusPx: Float,
    cutPx: Float,
    joinRadiusPx: Float,
): Outline {
    val radius = cornerRadiusPx.coerceIn(0f, minOf(size.width, size.height) / 2f)
    val cut = cutPx.coerceIn(0f, (size.width - radius).coerceAtLeast(0f))
    val join = joinRadiusPx.coerceIn(0f, minOf(cut / 3f, size.height / 4f))
    val path = LogicalPath(size.width, layoutDirection).apply {
        moveTo(cut + join, 0f)
        lineTo(size.width - radius, 0f)
        quadraticBezierTo(size.width, 0f, size.width, radius)
        lineTo(size.width, size.height - radius)
        quadraticBezierTo(size.width, size.height, size.width - radius, size.height)
        lineTo(join, size.height)
        quadraticBezierTo(0f, size.height, join * 0.5f, size.height - join)
        lineTo(cut - join * 0.5f, join)
        quadraticBezierTo(cut, 0f, cut + join, 0f)
        close()
    }.path
    return Outline.Generic(path)
}

/** Builds in logical start-to-end coordinates so the full path mirrors in RTL. */
private class LogicalPath(
    private val width: Float,
    private val layoutDirection: LayoutDirection,
) {
    val path = Path()

    fun moveTo(x: Float, y: Float) {
        path.moveTo(resolveX(x), y)
    }

    fun lineTo(x: Float, y: Float) {
        path.lineTo(resolveX(x), y)
    }

    fun quadraticBezierTo(controlX: Float, controlY: Float, endX: Float, endY: Float) {
        path.quadraticBezierTo(resolveX(controlX), controlY, resolveX(endX), endY)
    }

    fun close() {
        path.close()
    }

    private fun resolveX(x: Float): Float =
        if (layoutDirection == LayoutDirection.Ltr) x else width - x
}

private fun organicReadableContentColor(background: Color): Color {
    val dark = Color(0xFF151713)
    val light = Color.White

    fun contrast(foreground: Color): Float {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    return if (contrast(dark) >= contrast(light)) dark else light
}
