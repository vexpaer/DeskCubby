package com.deskcubby.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BaseTypography = Typography()

/**
 * Material ("paper") type: editorial serif display/headline roles over the platform sans body.
 * Only system families are used, so CJK glyphs resolve through the device's Noto Serif/Sans CJK
 * fallbacks without bundling font files into the APK.
 */
val AppTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-1.2).sp,
    ),
    displayMedium = BaseTypography.displayMedium.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.9).sp,
    ),
    displaySmall = BaseTypography.displaySmall.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/**
 * Liquid Glass type: airy light-weight display numerals with tight tracking, confident medium
 * headlines, so large values read like instrument faces behind the glass.
 */
val GlassTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        letterSpacing = (-2.0).sp,
    ),
    displayMedium = BaseTypography.displayMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        letterSpacing = (-1.5).sp,
    ),
    displaySmall = BaseTypography.displaySmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        letterSpacing = (-1.0).sp,
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelLarge = BaseTypography.labelLarge.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
)

/** Tabular figures keep animated counters from jittering horizontally while digits change. */
fun TextStyle.withTabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
