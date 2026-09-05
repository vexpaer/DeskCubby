package com.deskcubby.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/*
 * DeskCubby type scale.
 *
 * The default Material scale was tuned for Latin marketing surfaces: a 57sp display nobody
 * renders, and a 12sp `bodySmall` that ended up carrying most of the app's supporting text.
 * This scale inverts that. Display sizes come down to something a diary app actually uses,
 * body sizes come up, and line height is set to ~1.6 so Simplified Chinese, Traditional
 * Chinese, Korean and Japanese stay legible instead of colliding.
 *
 * Letter spacing is deliberately near zero for CJK body copy: positive tracking pulls Chinese
 * glyphs apart. Wide tracking is reserved for the uppercase Latin eyebrow/label roles.
 */

private val CenteredLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun deskText(
    size: Float,
    lineHeight: Float,
    weight: FontWeight = FontWeight.Normal,
    tracking: Float = 0f,
): TextStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = CenteredLineHeight,
)

val AppTypography = Typography(
    displayLarge = deskText(40f, 44f, FontWeight.SemiBold, -1f),
    displayMedium = deskText(34f, 38f, FontWeight.SemiBold, -0.8f),
    displaySmall = deskText(28f, 33f, FontWeight.SemiBold, -0.6f),
    headlineLarge = deskText(26f, 32f, FontWeight.SemiBold, -0.4f),
    headlineMedium = deskText(22f, 28f, FontWeight.SemiBold, -0.3f),
    headlineSmall = deskText(19f, 25f, FontWeight.SemiBold, -0.2f),
    titleLarge = deskText(19f, 26f, FontWeight.SemiBold, -0.15f),
    titleMedium = deskText(16f, 22f, FontWeight.SemiBold, 0f),
    titleSmall = deskText(14f, 19f, FontWeight.SemiBold, 0.05f),
    bodyLarge = deskText(16f, 26f, FontWeight.Normal, 0f),
    bodyMedium = deskText(15f, 23f, FontWeight.Normal, 0f),
    bodySmall = deskText(13f, 19f, FontWeight.Normal, 0.05f),
    labelLarge = deskText(13f, 18f, FontWeight.SemiBold, 0.15f),
    labelMedium = deskText(11.5f, 16f, FontWeight.Medium, 0.5f),
    labelSmall = deskText(10.5f, 14f, FontWeight.Medium, 0.6f),
)

/**
 * Roles Material does not name. Kept beside the scale so a section header on Statistics and a
 * section header on Settings are provably the same style.
 */
object DeskCubbyType {
    /** Uppercase section eyebrow: "TODAY", "THIS MONTH". */
    val eyebrow: TextStyle = deskText(11f, 14f, FontWeight.SemiBold, 0.9f)

    /** Large numeric readout (streaks, totals). Tabular figures stop digits from jittering. */
    val metric: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = "tnum",
        lineHeightStyle = CenteredLineHeight,
    )

    val metricLabel: TextStyle = deskText(12f, 16f, FontWeight.Medium, 0.2f)

    /** Monospace for code, diffs and raw JSON in the AI review surface. */
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeight,
    )

    /** Long-form reading body: diary and note previews. */
    val reading: TextStyle = deskText(16.5f, 28f, FontWeight.Normal, 0f)
}
