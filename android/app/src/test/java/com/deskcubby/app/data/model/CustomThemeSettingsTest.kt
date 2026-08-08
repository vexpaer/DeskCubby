package com.deskcubby.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeSettingsTest {
    @Test
    fun normalizationClampsEveryNumericTokenAndRejectsNonFiniteValues() {
        val normalized = CustomThemeSettings(
            cornerRadiusDp = 100f,
            borderWidthDp = -2f,
            elevationDp = Float.NaN,
            panelOpacity = 0.1f,
            spacingScale = 9f,
            animationScale = Float.POSITIVE_INFINITY,
        ).normalized()

        assertEquals(MAX_CUSTOM_THEME_CORNER_RADIUS_DP, normalized.cornerRadiusDp)
        assertEquals(MIN_CUSTOM_THEME_BORDER_WIDTH_DP, normalized.borderWidthDp)
        assertEquals(2f, normalized.elevationDp)
        assertEquals(MIN_CUSTOM_THEME_PANEL_OPACITY, normalized.panelOpacity)
        assertEquals(MAX_CUSTOM_THEME_SPACING_SCALE, normalized.spacingScale)
        assertEquals(1f, normalized.animationScale)
    }

    @Test
    fun normalizationMakesColorsOpaqueAndRepairsUnreadableTextRoles() {
        val unsafe = CustomThemePalette(
            backgroundArgb = 0x00FFFFFF,
            onBackgroundArgb = 0x00FFFFFF,
            surfaceArgb = 0x00FFFFFF,
            onSurfaceArgb = 0x00EEEEEE,
            surfaceContainerArgb = 0x00FDFDFD,
            surfaceVariantArgb = 0x00EEEEEE,
            onSurfaceVariantArgb = 0x00EEEEEE,
            outlineArgb = 0x00FFFFFF,
        )

        val normalized = CustomThemeSettings(
            lightPalette = unsafe,
            darkPalette = unsafe,
        ).normalized().lightPalette

        assertTrue(normalized.backgroundArgb ushr 24 == 0xFF)
        assertTrue(contrastRatio(normalized.onBackgroundArgb, normalized.backgroundArgb) >= 4.5)
        assertTrue(contrastRatio(normalized.onSurfaceArgb, normalized.surfaceArgb) >= 4.5)
        assertTrue(
            contrastRatio(normalized.onSurfaceVariantArgb, normalized.surfaceVariantArgb) >= 4.5,
        )
        assertTrue(contrastRatio(normalized.outlineArgb, normalized.surfaceArgb) >= 1.5)
    }

    @Test
    fun conflictingSurfaceLuminanceCollapsesContainerToKeepOneTextRoleReadable() {
        val palette = DEFAULT_CUSTOM_THEME_LIGHT_PALETTE.copy(
            surfaceArgb = 0xFFFFFFFF.toInt(),
            surfaceContainerArgb = 0xFF000000.toInt(),
            onSurfaceArgb = 0xFF777777.toInt(),
        )

        val normalized = CustomThemeSettings(lightPalette = palette).normalized().lightPalette

        assertEquals(normalized.surfaceArgb, normalized.surfaceContainerArgb)
        assertTrue(contrastRatio(normalized.onSurfaceArgb, normalized.surfaceArgb) >= 4.5)
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val encoded = ((argb ushr shift) and 0xFF) / 255.0
                return if (encoded <= 0.04045) encoded / 12.92
                else Math.pow((encoded + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }
}
