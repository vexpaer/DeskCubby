package com.deskcubby.app.widget

import kotlin.math.roundToInt

/** Launcher icons use a 48 dp visual container; smaller hosts fall back to text instead of crop. */
internal const val DESKTOP_APP_ICON_SIZE_DP = 48

internal fun shouldShowDesktopAppIcon(
    requested: Boolean,
    availableWidthDp: Int,
    availableHeightDp: Int,
    iconLoaded: Boolean,
): Boolean = requested &&
    iconLoaded &&
    availableWidthDp >= DESKTOP_APP_ICON_SIZE_DP &&
    availableHeightDp >= DESKTOP_APP_ICON_SIZE_DP

/** Rasterize at the device-density equivalent of 48 dp, bounded against hostile densities. */
internal fun desktopAppIconBitmapEdgePx(density: Float): Int =
    (DESKTOP_APP_ICON_SIZE_DP * density.takeIf { it.isFinite() && it > 0f }.orDefaultDensity())
        .roundToInt()
        .coerceIn(MIN_APP_ICON_BITMAP_EDGE_PX, MAX_APP_ICON_BITMAP_EDGE_PX)

private fun Float?.orDefaultDensity(): Float = this ?: 1f

private const val MIN_APP_ICON_BITMAP_EDGE_PX = 48
private const val MAX_APP_ICON_BITMAP_EDGE_PX = 256
