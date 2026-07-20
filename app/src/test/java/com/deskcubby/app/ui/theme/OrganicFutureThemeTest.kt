package com.deskcubby.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.deskcubby.app.data.model.VisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganicFutureThemeTest {
    @Test
    fun organicFutureKeepsEmeraldAccentInsteadOfSavedCustomColor() {
        val savedPurple = 0xFF7B5EA7.toInt()

        assertEquals(
            Color(0xFF087A43),
            resolveColorScheme(
                VisualStyle.ORGANIC_FUTURE,
                dark = false,
                themeColorArgb = savedPurple,
            ).primary,
        )
        assertEquals(
            Color(0xFF72F28F),
            resolveColorScheme(
                VisualStyle.ORGANIC_FUTURE,
                dark = true,
                themeColorArgb = savedPurple,
            ).primary,
        )
    }

    @Test
    fun existingStylesStillUseSavedCustomColor() {
        val savedOrange = 0xFFE57C23.toInt()

        assertEquals(
            Color(savedOrange),
            resolveColorScheme(VisualStyle.MATERIAL, dark = false, themeColorArgb = savedOrange).primary,
        )
        assertEquals(
            Color(savedOrange),
            resolveColorScheme(VisualStyle.LIQUID_GLASS, dark = true, themeColorArgb = savedOrange).primary,
        )
    }

    @Test
    fun organicBodyTextMeetsLongReadingContrastTarget() {
        val light = resolveColorScheme(VisualStyle.ORGANIC_FUTURE, false, 0)
        val dark = resolveColorScheme(VisualStyle.ORGANIC_FUTURE, true, 0)

        assertTrue(contrastRatio(light.onBackground, light.background) >= 4.5f)
        assertTrue(contrastRatio(dark.onBackground, dark.background) >= 4.5f)
        assertTrue(contrastRatio(light.onSurface, light.surface) >= 4.5f)
        assertTrue(contrastRatio(dark.onSurface, dark.surface) >= 4.5f)
    }

    @Test
    fun organicFixedColorRolesStayGreenAndReadableInBothModes() {
        val light = resolveColorScheme(VisualStyle.ORGANIC_FUTURE, false, 0)
        val dark = resolveColorScheme(VisualStyle.ORGANIC_FUTURE, true, 0)

        assertEquals(light.primaryFixed, dark.primaryFixed)
        assertEquals(light.secondaryFixed, dark.secondaryFixed)
        assertEquals(light.tertiaryFixed, dark.tertiaryFixed)
        assertEquals(Color(0xFFB8F4CA), light.primaryFixed)
        assertEquals(Color(0xFFD2E8D6), light.secondaryFixed)
        assertEquals(Color(0xFFDDF2A0), light.tertiaryFixed)
        assertTrue(contrastRatio(light.onPrimaryFixed, light.primaryFixed) >= 4.5f)
        assertTrue(contrastRatio(light.onSecondaryFixed, light.secondaryFixed) >= 4.5f)
        assertTrue(contrastRatio(light.onTertiaryFixed, light.tertiaryFixed) >= 4.5f)
    }

    @Test
    fun organicTokensProvideEditorialHierarchyAndOrganicShapes() {
        val tokens = visualTokensFor(VisualStyle.ORGANIC_FUTURE)

        assertTrue(tokens.organic)
        assertEquals(340, tokens.transitionMillis)
        assertTrue(OrganicFutureTypography.headlineMedium.fontSize > OrganicFutureTypography.bodyLarge.fontSize)
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val lighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
