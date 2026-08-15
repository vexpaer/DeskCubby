package com.deskcubby.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWidgetAppShortcutPolicyTest {
    @Test
    fun launcherSizedIconRequiresToggleBitmapAndEnoughSpaceOnBothAxes() {
        assertTrue(shouldShowDesktopAppIcon(true, 48, 48, true))
        assertFalse(shouldShowDesktopAppIcon(false, 200, 200, true))
        assertFalse(shouldShowDesktopAppIcon(true, 47, 200, true))
        assertFalse(shouldShowDesktopAppIcon(true, 200, 47, true))
        assertFalse(shouldShowDesktopAppIcon(true, 200, 200, false))
        assertFalse(shouldShowDesktopAppIcon(true, 71, 200, true, iconSizeDp = 72))
        assertTrue(shouldShowDesktopAppIcon(true, 72, 72, true, iconSizeDp = 72))
    }

    @Test
    fun iconScaleUsesFixedFortyEightDpBaselineAcrossWidgetAreas() {
        assertEquals(24, desktopAppIconSizeDp(50))
        assertEquals(48, desktopAppIconSizeDp(100))
        assertEquals(72, desktopAppIconSizeDp(150))
        assertEquals(72, desktopAppIconSizeDp(1000))
    }

    @Test
    fun bitmapRasterizationTracksDensityAndConfiguredScaleWithDefensiveBounds() {
        assertEquals(48, desktopAppIconBitmapEdgePx(1f))
        assertEquals(96, desktopAppIconBitmapEdgePx(2f))
        assertEquals(192, desktopAppIconBitmapEdgePx(4f))
        assertEquals(48, desktopAppIconBitmapEdgePx(0f))
        assertEquals(48, desktopAppIconBitmapEdgePx(Float.NaN))
        assertEquals(256, desktopAppIconBitmapEdgePx(20f))
        assertEquals(144, desktopAppIconBitmapEdgePx(2f, 150))
    }
}
