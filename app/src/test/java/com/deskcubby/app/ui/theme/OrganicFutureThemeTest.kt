package com.deskcubby.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.deskcubby.app.data.model.VisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganicFutureThemeTest {
    @Test
    fun organicFutureAdaptsSavedColorsForReadableLightAndDarkRoles() {
        val savedPurple = 0xFF7B5EA7.toInt()
        val savedSecondaryColors = listOf(
            0xFFCF6A47.toInt(),
            0xFF3D7C89.toInt(),
            0xFFE2B13C.toInt(),
        )

        listOf(false, true).forEach { dark ->
            val scheme = resolveColorScheme(
                visualStyle = VisualStyle.ORGANIC_FUTURE,
                dark = dark,
                themeColorArgb = savedPurple,
                themeSecondaryColorsArgb = savedSecondaryColors,
            )

            assertTrue(contrastRatio(scheme.primary, scheme.surface) >= 3f)
            assertTrue(contrastRatio(scheme.secondary, scheme.surface) >= 3f)
            assertTrue(contrastRatio(scheme.tertiary, scheme.surface) >= 3f)
            assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5f)
            assertTrue(contrastRatio(scheme.onSecondary, scheme.secondary) >= 4.5f)
            assertTrue(contrastRatio(scheme.onTertiary, scheme.tertiary) >= 4.5f)
        }
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
    fun organicFixedColorRolesStayStableAndReadableInBothModes() {
        val light = resolveColorScheme(VisualStyle.ORGANIC_FUTURE, false)
        val dark = resolveColorScheme(VisualStyle.ORGANIC_FUTURE, true)

        assertEquals(light.primaryFixed, dark.primaryFixed)
        assertEquals(light.secondaryFixed, dark.secondaryFixed)
        assertEquals(light.tertiaryFixed, dark.tertiaryFixed)
        assertTrue(contrastRatio(light.onPrimaryFixed, light.primaryFixed) >= 4.5f)
        assertTrue(contrastRatio(light.onSecondaryFixed, light.secondaryFixed) >= 4.5f)
        assertTrue(contrastRatio(light.onTertiaryFixed, light.tertiaryFixed) >= 4.5f)
        assertTrue(contrastRatio(light.onPrimaryFixedVariant, light.primaryFixed) >= 4.5f)
        assertTrue(contrastRatio(light.onSecondaryFixedVariant, light.secondaryFixed) >= 4.5f)
        assertTrue(contrastRatio(light.onTertiaryFixedVariant, light.tertiaryFixed) >= 4.5f)
    }

    @Test
    fun organicCustomRolePairsMeetContrastInLightAndDarkModes() {
        val customColors = listOf(
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF777777.toInt(),
        )

        customColors.forEach { mainColor ->
            listOf(false, true).forEach { dark ->
                val scheme = resolveColorScheme(
                    visualStyle = VisualStyle.ORGANIC_FUTURE,
                    dark = dark,
                    themeColorArgb = mainColor,
                    themeSecondaryColorsArgb = customColors.filterNot { it == mainColor }
                        .ifEmpty { customColors.take(2) },
                )

                assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5f)
                assertTrue(contrastRatio(scheme.onSecondary, scheme.secondary) >= 4.5f)
                assertTrue(contrastRatio(scheme.onTertiary, scheme.tertiary) >= 4.5f)
                assertTrue(contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5f)
                assertTrue(contrastRatio(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5f)
                assertTrue(contrastRatio(scheme.onTertiaryContainer, scheme.tertiaryContainer) >= 4.5f)
            }
        }
    }

    @Test
    fun organicAccentPaletteKeepsEveryConfiguredColor() {
        val configured = listOf(
            0x00112233,
            0xFF445566.toInt(),
            0xFF778899.toInt(),
            0xFFAABBCC.toInt(),
            0xFFDDEEFF.toInt(),
        )

        assertEquals(
            configured.map { Color(it or 0xFF000000.toInt()) },
            organicFutureAccentColors(configured),
        )
    }

    @Test
    fun typographyScaleAppliesGloballyAndClampsUnsafeValues() {
        val scaled = scaledTypography(OrganicFutureTypography, 1.2f)
        val clamped = scaledTypography(OrganicFutureTypography, 9f)
        val invalid = scaledTypography(OrganicFutureTypography, Float.NaN)

        assertEquals(
            OrganicFutureTypography.bodyLarge.fontSize.value * 1.2f,
            scaled.bodyLarge.fontSize.value,
            0.001f,
        )
        assertEquals(
            OrganicFutureTypography.headlineLarge.lineHeight.value * 1.2f,
            scaled.headlineLarge.lineHeight.value,
            0.001f,
        )
        assertEquals(
            OrganicFutureTypography.bodyLarge.fontSize.value * 1.3f,
            clamped.bodyLarge.fontSize.value,
            0.001f,
        )
        assertEquals(OrganicFutureTypography, invalid)
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
