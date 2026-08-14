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

/** Returns a launcher-sized bitmap while keeping RemoteViews binder payloads bounded. */
internal fun desktopWidgetBitmapSize(
    context: Context,
    appWidgetId: Int,
    config: DesktopWidgetConfig,
): DesktopWidgetBitmapSize {
    val options = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    }.getOrDefault(android.os.Bundle.EMPTY)
    val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        .takeIf { it > 0 }
        ?: config.widthCells * APPROX_WIDGET_CELL_DP
    val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        .takeIf { it > 0 }
        ?: config.heightCells * APPROX_WIDGET_CELL_DP
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
