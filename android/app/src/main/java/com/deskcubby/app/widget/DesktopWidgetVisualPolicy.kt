package com.deskcubby.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.deskcubby.app.data.model.DesktopWidgetConfig
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class DesktopWidgetBitmapSize(
    val width: Int,
    val height: Int,
)

internal data class DesktopWidgetBoundsDp(
    val width: Int,
    val height: Int,
)

internal fun desktopWidgetBoundsDp(
    minWidthDp: Int,
    minHeightDp: Int,
    config: DesktopWidgetConfig,
): DesktopWidgetBoundsDp = DesktopWidgetBoundsDp(
    width = minWidthDp.takeIf { it > 0 } ?: config.widthCells * APPROX_WIDGET_CELL_DP,
    height = minHeightDp.takeIf { it > 0 } ?: config.heightCells * APPROX_WIDGET_CELL_DP,
)

internal data class DesktopWidgetSurfaceInsetsDp(
    val horizontal: Int,
    val vertical: Int,
)

internal fun desktopWidgetSurfaceInsetsDp(
    widthDp: Int,
    heightDp: Int,
    scalePercent: Int,
): DesktopWidgetSurfaceInsetsDp {
    val normalizedScale = scalePercent.coerceIn(70, 100) / 100f
    return DesktopWidgetSurfaceInsetsDp(
        horizontal = (((1f - normalizedScale) * widthDp.coerceAtLeast(1)) / 2f).roundToInt(),
        vertical = (((1f - normalizedScale) * heightDp.coerceAtLeast(1)) / 2f).roundToInt(),
    )
}

/** Returns a launcher-sized bitmap while keeping RemoteViews binder payloads bounded. */
internal fun desktopWidgetBitmapSize(
    context: Context,
    appWidgetId: Int,
    config: DesktopWidgetConfig,
): DesktopWidgetBitmapSize {
    val options = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    }.getOrDefault(android.os.Bundle.EMPTY)
    val bounds = desktopWidgetBoundsDp(
        minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
        minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
        config = config,
    )
    val widthDp = bounds.width
    val heightDp = bounds.height
    val density = context.resources.displayMetrics.density.coerceIn(1f, 2f)
    var width = (widthDp * density).roundToInt().coerceIn(MIN_BITMAP_EDGE_PX, MAX_BITMAP_EDGE_PX)
    var height = (heightDp * density).roundToInt().coerceIn(MIN_BITMAP_EDGE_PX, MAX_BITMAP_EDGE_PX)
    val pixels = width.toLong() * height
    if (pixels > MAX_BITMAP_PIXELS) {
        val scale = sqrt(MAX_BITMAP_PIXELS.toDouble() / pixels.toDouble())
        width = (width * scale).roundToInt().coerceAtLeast(MIN_BITMAP_EDGE_PX)
        height = (height * scale).roundToInt().coerceAtLeast(MIN_BITMAP_EDGE_PX)
    }
    return DesktopWidgetBitmapSize(width, height)
}

private const val APPROX_WIDGET_CELL_DP = 72
private const val MIN_BITMAP_EDGE_PX = 96
private const val MAX_BITMAP_EDGE_PX = 960
private const val MAX_BITMAP_PIXELS = 480_000L
