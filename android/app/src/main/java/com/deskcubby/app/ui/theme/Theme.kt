package com.deskcubby.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.CustomThemeBaseStyle
import com.deskcubby.app.data.model.CustomThemeSettings
import com.deskcubby.app.data.model.DEFAULT_THEME_COLOR_ARGB
import com.deskcubby.app.data.model.DEFAULT_THEME_SECONDARY_COLORS_ARGB
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.MAX_APP_FONT_SCALE
import com.deskcubby.app.data.model.MIN_APP_FONT_SCALE
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.normalized
import androidx.core.view.WindowCompat

val LocalVisualStyle: ProvidableCompositionLocal<VisualStyle> =
    staticCompositionLocalOf { VisualStyle.MATERIAL }

val LocalAppLanguage: ProvidableCompositionLocal<AppLanguage> =
    staticCompositionLocalOf { AppLanguage.CHINESE }

/** Compact mode tightens list/settings paddings across the app. */
val LocalCompactMode: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/*
 * Base palettes.
 *
 * Each style declares its full surface ladder and text roles rather than relying on Material's
 * neutral defaults. Before this, Material and Liquid Glass only overrode primary/surface, so
 * `surfaceContainer`, `onSurface` and `outlineVariant` fell back to Material's cool
 * purple-grey (#F3EDF7, #1C1B1F, #CAC4D0) and read as a visible colour mismatch on top of the
 * green paper canvas. Every role is now derived from the same hue family as the canvas.
 */

private val MaterialLight = lightColorScheme(
    primary = Color(0xFF3D6B4E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E8DC),
    onPrimaryContainer = Color(0xFF14301F),
    inversePrimary = Color(0xFF93D3A2),
    secondary = Color(0xFF5C7263),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE9E0),
    onSecondaryContainer = Color(0xFF1C2A20),
    tertiary = Color(0xFF7A6544),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF0E4CE),
    onTertiaryContainer = Color(0xFF2E2411),
    background = Color(0xFFF4F5F1),
    onBackground = Color(0xFF161A15),
    surface = Color(0xFFFAFBF8),
    onSurface = Color(0xFF161A15),
    surfaceVariant = Color(0xFFE3E7DF),
    onSurfaceVariant = Color(0xFF55604F),
    surfaceTint = Color(0xFF3D6B4E),
    inverseSurface = Color(0xFF2E332D),
    inverseOnSurface = Color(0xFFF1F3EE),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF78827A),
    outlineVariant = Color(0xFFDCE0D8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAFBF8),
    surfaceDim = Color(0xFFDADFD7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F6F2),
    surfaceContainer = Color(0xFFEEF0EB),
    surfaceContainerHigh = Color(0xFFE7EAE4),
    surfaceContainerHighest = Color(0xFFE0E4DD),
)

private val MaterialDark = darkColorScheme(
    primary = Color(0xFF93D3A2),
    onPrimary = Color(0xFF0C2D18),
    primaryContainer = Color(0xFF23462E),
    onPrimaryContainer = Color(0xFFD2E9D8),
    inversePrimary = Color(0xFF3D6B4E),
    secondary = Color(0xFFB9CCBE),
    onSecondary = Color(0xFF22332A),
    secondaryContainer = Color(0xFF2E4034),
    onSecondaryContainer = Color(0xFFD5E7DA),
    tertiary = Color(0xFFDCC39A),
    onTertiary = Color(0xFF3B2E12),
    tertiaryContainer = Color(0xFF4E4022),
    onTertiaryContainer = Color(0xFFF0E1C6),
    background = Color(0xFF0E110E),
    onBackground = Color(0xFFE3E7E1),
    surface = Color(0xFF141814),
    onSurface = Color(0xFFE3E7E1),
    surfaceVariant = Color(0xFF2A312A),
    onSurfaceVariant = Color(0xFFA9B3A6),
    surfaceTint = Color(0xFF93D3A2),
    inverseSurface = Color(0xFFE3E7E1),
    inverseOnSurface = Color(0xFF2B312B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF7E887C),
    outlineVariant = Color(0xFF2B312B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF333933),
    surfaceDim = Color(0xFF0E110E),
    surfaceContainerLowest = Color(0xFF090B09),
    surfaceContainerLow = Color(0xFF111511),
    surfaceContainer = Color(0xFF171B17),
    surfaceContainerHigh = Color(0xFF1F241F),
    surfaceContainerHighest = Color(0xFF282E28),
)

private val GlassLight = lightColorScheme(
    primary = Color(0xFF4160A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E1F7),
    onPrimaryContainer = Color(0xFF102348),
    inversePrimary = Color(0xFFA9C0F5),
    secondary = Color(0xFF5A6478),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE3EE),
    onSecondaryContainer = Color(0xFF18202F),
    tertiary = Color(0xFF75598C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEBDDF6),
    onTertiaryContainer = Color(0xFF2C1442),
    background = Color(0xFFEFF2F9),
    onBackground = Color(0xFF161A22),
    surface = Color(0xFFF8F9FD),
    onSurface = Color(0xFF161A22),
    surfaceVariant = Color(0xFFE1E6F1),
    onSurfaceVariant = Color(0xFF4E5866),
    surfaceTint = Color(0xFF4160A6),
    inverseSurface = Color(0xFF2C313B),
    inverseOnSurface = Color(0xFFF0F2F7),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF747F8D),
    outlineVariant = Color(0xFFD9DFEA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8F9FD),
    surfaceDim = Color(0xFFD7DCE8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5FB),
    surfaceContainer = Color(0xFFEBEEF7),
    surfaceContainerHigh = Color(0xFFE4E8F2),
    surfaceContainerHighest = Color(0xFFDDE2EE),
)

private val GlassDark = darkColorScheme(
    primary = Color(0xFFA9C0F5),
    onPrimary = Color(0xFF122450),
    primaryContainer = Color(0xFF2B4480),
    onPrimaryContainer = Color(0xFFD8E2FA),
    inversePrimary = Color(0xFF4160A6),
    secondary = Color(0xFFBCC5D8),
    onSecondary = Color(0xFF262E3E),
    secondaryContainer = Color(0xFF343D50),
    onSecondaryContainer = Color(0xFFD9E0EC),
    tertiary = Color(0xFFD6BCEA),
    onTertiary = Color(0xFF3E2454),
    tertiaryContainer = Color(0xFF573E6E),
    onTertiaryContainer = Color(0xFFF0E1FB),
    background = Color(0xFF0A0D14),
    onBackground = Color(0xFFE2E6EF),
    surface = Color(0xFF11151E),
    onSurface = Color(0xFFE2E6EF),
    surfaceVariant = Color(0xFF283040),
    onSurfaceVariant = Color(0xFFA7B0C0),
    surfaceTint = Color(0xFFA9C0F5),
    inverseSurface = Color(0xFFE2E6EF),
    inverseOnSurface = Color(0xFF2A3140),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF78828F),
    outlineVariant = Color(0xFF283040),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF303849),
    surfaceDim = Color(0xFF0A0D14),
    surfaceContainerLowest = Color(0xFF06080D),
    surfaceContainerLow = Color(0xFF0E121A),
    surfaceContainer = Color(0xFF141924),
    surfaceContainerHigh = Color(0xFF1C2230),
    surfaceContainerHighest = Color(0xFF252C3C),
)

/** Standard corner language for Material and Liquid Glass. Organic Future keeps its own. */
private val StandardShapes = Shapes(
    extraSmall = RoundedCornerShape(DeskCubbyRadius.xs),
    small = RoundedCornerShape(DeskCubbyRadius.sm),
    medium = RoundedCornerShape(DeskCubbyRadius.md),
    large = RoundedCornerShape(DeskCubbyRadius.lg),
    extraLarge = RoundedCornerShape(DeskCubbyRadius.xl),
)


@Composable
fun DeskCubbyTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val customTheme = settings.customTheme.normalized()
    val effectiveStyle = settings.visualStyle.effectiveBaseStyle(customTheme)
    val baseScheme = resolveColorScheme(
        visualStyle = settings.visualStyle,
        dark = dark,
        themeColorArgb = settings.themeColorArgb,
        themeSecondaryColorsArgb = settings.themeSecondaryColorsArgb,
        customTheme = customTheme,
    )
    // The app background layer lives below every navigation destination. Making only the
    // page-background role transparent keeps cards, dialogs, and controls readable while allowing
    // Scaffold canvases to reveal the user-selected image.
    val scheme = if (settings.backgroundImageUri != null) {
        baseScheme.copy(background = Color.Transparent)
    } else {
        baseScheme
    }
    val baseTypography = if (effectiveStyle == VisualStyle.ORGANIC_FUTURE) {
        OrganicFutureTypography
    } else {
        AppTypography
    }
    val typography = scaledTypography(baseTypography, settings.fontScale)
    val shapes = when (settings.visualStyle) {
        VisualStyle.CUSTOM -> customShapes(customTheme.cornerRadiusDp.dp)
        else -> if (effectiveStyle == VisualStyle.ORGANIC_FUTURE) OrganicFutureShapes else StandardShapes
    }
    val visualTokens = if (settings.visualStyle == VisualStyle.CUSTOM) {
        customVisualTokens(effectiveStyle, customTheme)
    } else {
        visualTokensFor(effectiveStyle)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                    window.isStatusBarContrastEnforced = false
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        // Existing screens only need the rendering behavior. Keeping CUSTOM out of this local
        // lets every established Material/Glass/Organic branch continue to work unchanged.
        LocalVisualStyle provides effectiveStyle,
        LocalAppLanguage provides settings.appLanguage,
        LocalCompactMode provides settings.compactMode,
        LocalDeskCubbyVisuals provides visualTokens,
        LocalOrganicFuturePrimaryColor provides Color(settings.themeColorArgb or 0xFF000000.toInt()),
        LocalOrganicFutureAccentColors provides organicFutureAccentColors(
            settings.themeSecondaryColorsArgb,
        ),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

internal fun resolveColorScheme(
    visualStyle: VisualStyle,
    dark: Boolean,
    themeColorArgb: Int = DEFAULT_THEME_COLOR_ARGB,
    themeSecondaryColorsArgb: List<Int> = DEFAULT_THEME_SECONDARY_COLORS_ARGB,
    customTheme: CustomThemeSettings = CustomThemeSettings(),
): ColorScheme {
    val normalizedCustomTheme = customTheme.normalized()
    val effectiveStyle = visualStyle.effectiveBaseStyle(normalizedCustomTheme)
    val baseScheme = when (effectiveStyle) {
        VisualStyle.MATERIAL -> if (dark) MaterialDark else MaterialLight
        VisualStyle.LIQUID_GLASS -> if (dark) GlassDark else GlassLight
        VisualStyle.ORGANIC_FUTURE -> organicFutureColorScheme(
            dark = dark,
            themeColorArgb = themeColorArgb,
            secondaryColorsArgb = themeSecondaryColorsArgb,
        )
        VisualStyle.CUSTOM -> error("CUSTOM must resolve to a concrete base style")
    }
    val accentedScheme = if (effectiveStyle == VisualStyle.ORGANIC_FUTURE) {
        baseScheme
    } else {
        // Material and Liquid Glass share the same primary + secondary color settings as
        // Organic Future: the first two secondary colors drive the secondary/tertiary roles.
        val accent = Color(themeColorArgb)
        val accents = organicFutureAccentColors(themeSecondaryColorsArgb)
        val secondary = accents[0]
        val tertiary = accents[1]
        fun onColor(color: Color) = if (color.luminance() > 0.48f) Color.Black else Color.White
        fun container(color: Color) = lerp(
            color,
            if (dark) Color.Black else Color.White,
            if (dark) 0.48f else 0.72f,
        )
        baseScheme.copy(
            primary = accent,
            onPrimary = onColor(accent),
            primaryContainer = container(accent),
            onPrimaryContainer = if (dark) Color.White else Color.Black,
            secondary = secondary,
            onSecondary = onColor(secondary),
            secondaryContainer = container(secondary),
            onSecondaryContainer = if (dark) Color.White else Color.Black,
            tertiary = tertiary,
            onTertiary = onColor(tertiary),
            tertiaryContainer = container(tertiary),
            onTertiaryContainer = if (dark) Color.White else Color.Black,
        )
    }
    return if (visualStyle == VisualStyle.CUSTOM) {
        accentedScheme.withCustomPalette(normalizedCustomTheme, dark)
    } else {
        accentedScheme
    }
}

private fun VisualStyle.effectiveBaseStyle(customTheme: CustomThemeSettings): VisualStyle =
    if (this != VisualStyle.CUSTOM) this else when (customTheme.baseStyle) {
        CustomThemeBaseStyle.MATERIAL -> VisualStyle.MATERIAL
        CustomThemeBaseStyle.LIQUID_GLASS -> VisualStyle.LIQUID_GLASS
        CustomThemeBaseStyle.ORGANIC_FUTURE -> VisualStyle.ORGANIC_FUTURE
    }

private fun ColorScheme.withCustomPalette(
    customTheme: CustomThemeSettings,
    dark: Boolean,
): ColorScheme {
    val palette = if (dark) customTheme.darkPalette else customTheme.lightPalette
    val background = Color(palette.backgroundArgb)
    val onBackground = Color(palette.onBackgroundArgb)
    val surface = Color(palette.surfaceArgb)
    val onSurface = Color(palette.onSurfaceArgb)
    val container = Color(palette.surfaceContainerArgb)
    val variant = Color(palette.surfaceVariantArgb)
    val onVariant = Color(palette.onSurfaceVariantArgb)
    val outline = Color(palette.outlineArgb)
    val primary = customRoleColor(this.primary, surface)
    val secondary = customRoleColor(this.secondary, surface)
    val tertiary = customRoleColor(this.tertiary, surface)
    val primaryContainer = lerp(primary, surface, if (dark) 0.58f else 0.72f)
    val secondaryContainer = lerp(secondary, surface, if (dark) 0.58f else 0.72f)
    val tertiaryContainer = lerp(tertiary, surface, if (dark) 0.58f else 0.72f)
    return copy(
        primary = primary,
        onPrimary = readableCustomContent(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableCustomContent(primaryContainer),
        secondary = secondary,
        onSecondary = readableCustomContent(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableCustomContent(secondaryContainer),
        tertiary = tertiary,
        onTertiary = readableCustomContent(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = readableCustomContent(tertiaryContainer),
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = variant,
        onSurfaceVariant = onVariant,
        outline = outline,
        outlineVariant = lerp(outline, surface, 0.58f),
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        surfaceBright = lerp(surface, Color.White, if (dark) 0.12f else 0.02f),
        surfaceDim = lerp(surface, Color.Black, if (dark) 0.10f else 0.08f),
        surfaceContainerLowest = lerp(container, surface, 0.78f),
        surfaceContainerLow = lerp(container, surface, 0.48f),
        surfaceContainer = container,
        surfaceContainerHigh = lerp(container, variant, 0.34f),
        surfaceContainerHighest = lerp(container, variant, 0.62f),
    )
}

private fun customRoleColor(seed: Color, surface: Color): Color {
    if (customContrastRatio(seed, surface) >= 3f) return seed
    val target = if (surface.luminance() > 0.5f) Color.Black else Color.White
    for (step in 1..20) {
        val candidate = lerp(seed, target, step / 20f)
        if (customContrastRatio(candidate, surface) >= 3f) return candidate
    }
    return target
}

private fun readableCustomContent(background: Color): Color {
    val blackContrast = customContrastRatio(Color.Black, background)
    val whiteContrast = customContrastRatio(Color.White, background)
    return if (blackContrast >= whiteContrast) Color.Black else Color.White
}

private fun customContrastRatio(first: Color, second: Color): Float {
    val lighter = maxOf(first.luminance(), second.luminance())
    val darker = minOf(first.luminance(), second.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun customShapes(radius: Dp): Shapes {
    val safeRadius = radius.coerceIn(0.dp, 40.dp)
    return Shapes(
        extraSmall = RoundedCornerShape(safeRadius * 0.45f),
        small = RoundedCornerShape(safeRadius * 0.68f),
        medium = RoundedCornerShape(safeRadius),
        large = RoundedCornerShape((safeRadius * 1.35f).coerceAtMost(48.dp)),
        extraLarge = RoundedCornerShape((safeRadius * 1.7f).coerceAtMost(56.dp)),
    )
}

internal fun scaledTypography(typography: Typography, fontScale: Float): Typography {
    val scale = fontScale.takeIf(Float::isFinite)
        ?.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE)
        ?: 1f
    if (scale == 1f) return typography
    return typography.copy(
        displayLarge = typography.displayLarge.scaledBy(scale),
        displayMedium = typography.displayMedium.scaledBy(scale),
        displaySmall = typography.displaySmall.scaledBy(scale),
        headlineLarge = typography.headlineLarge.scaledBy(scale),
        headlineMedium = typography.headlineMedium.scaledBy(scale),
        headlineSmall = typography.headlineSmall.scaledBy(scale),
        titleLarge = typography.titleLarge.scaledBy(scale),
        titleMedium = typography.titleMedium.scaledBy(scale),
        titleSmall = typography.titleSmall.scaledBy(scale),
        bodyLarge = typography.bodyLarge.scaledBy(scale),
        bodyMedium = typography.bodyMedium.scaledBy(scale),
        bodySmall = typography.bodySmall.scaledBy(scale),
        labelLarge = typography.labelLarge.scaledBy(scale),
        labelMedium = typography.labelMedium.scaledBy(scale),
        labelSmall = typography.labelSmall.scaledBy(scale),
    )
}

private fun TextStyle.scaledBy(scale: Float): TextStyle = copy(
    fontSize = fontSize.scaledBy(scale),
    lineHeight = lineHeight.scaledBy(scale),
    letterSpacing = letterSpacing.scaledBy(scale),
)

private fun TextUnit.scaledBy(scale: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else this * scale

@Composable
fun tr(chinese: String, english: String): String =
    translate(chinese, english, LocalAppLanguage.current)

/**
 * Language resolution shared by Compose screens ([tr]) and widget/RemoteViews rendering that
 * cannot read CompositionLocals. Strings are keyed by the Simplified Chinese source text; when a
 * target language has no translation yet it falls back to Simplified Chinese.
 */
fun translate(chinese: String, english: String, language: AppLanguage): String = when (language) {
    AppLanguage.CHINESE -> chinese
    AppLanguage.ENGLISH -> english
    AppLanguage.TRADITIONAL_CHINESE -> AppTranslations.TRADITIONAL[chinese] ?: chinese
    AppLanguage.KOREAN -> AppTranslations.KOREAN[chinese] ?: chinese
    AppLanguage.JAPANESE -> AppTranslations.JAPANESE[chinese] ?: chinese
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = DeskCubbyRadius.lg,
    role: PanelRole = PanelRole.STANDARD,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val style = LocalVisualStyle.current
    val visuals = LocalDeskCubbyVisuals.current
    val organicAccents = LocalOrganicFutureAccentColors.current
    val organicAccent = organicAccents.getOrElse(role.ordinal % organicAccents.size.coerceAtLeast(1)) {
        MaterialTheme.colorScheme.secondary
    }
    val shape = when {
        visuals.customized -> when (role) {
            PanelRole.FEATURE -> visuals.featureShape
            PanelRole.MEDIA -> visuals.mediaShape
            PanelRole.TOOLBAR -> visuals.toolbarShape
            PanelRole.STANDARD -> visuals.listShape
        }
        style == VisualStyle.ORGANIC_FUTURE -> organicPanelShape(cornerRadius, role)
        else -> RoundedCornerShape(cornerRadius)
    }
    val scheme = MaterialTheme.colorScheme
    val layoutDirection = LocalLayoutDirection.current
    val paddingScale = (visuals.contentPadding.value / 16f).coerceIn(0.75f, 1.35f)
    fun scaledPanelPadding(values: PaddingValues): PaddingValues = PaddingValues(
        start = when (layoutDirection) {
            LayoutDirection.Ltr -> values.calculateLeftPadding(layoutDirection)
            LayoutDirection.Rtl -> values.calculateRightPadding(layoutDirection)
        } * paddingScale,
        top = values.calculateTopPadding() * paddingScale,
        end = when (layoutDirection) {
            LayoutDirection.Ltr -> values.calculateRightPadding(layoutDirection)
            LayoutDirection.Rtl -> values.calculateLeftPadding(layoutDirection)
        } * paddingScale,
        bottom = values.calculateBottomPadding() * paddingScale,
    )
    val panelModifier = when (style) {
        VisualStyle.LIQUID_GLASS -> modifier
                .shadow(
                    if (visuals.customized) visuals.panelElevation else 10.dp,
                    shape,
                    ambientColor = scheme.primary.copy(alpha = 0.16f),
                )
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.surface.copy(alpha = 0.86f * visuals.panelOpacity),
                            scheme.primaryContainer.copy(alpha = 0.52f * visuals.panelOpacity),
                            scheme.surface.copy(alpha = 0.72f * visuals.panelOpacity),
                        ),
                    ),
                )
                .border(
                    BorderStroke(
                        if (visuals.customized) visuals.borderWidth else 1.dp,
                        Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.58f), scheme.primary.copy(alpha = 0.18f)),
                        ),
                    ),
                    shape,
                )

        VisualStyle.ORGANIC_FUTURE -> modifier
                .shadow(
                    elevation = visuals.panelElevation,
                    shape = shape,
                    ambientColor = scheme.primary.copy(alpha = 0.08f),
                    spotColor = scheme.primary.copy(alpha = 0.05f),
                )
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            scheme.surfaceContainer.copy(alpha = visuals.panelOpacity),
                            lerp(scheme.surfaceContainer, organicAccent, 0.10f)
                                .copy(alpha = visuals.panelOpacity),
                            scheme.surfaceContainerHigh.copy(alpha = visuals.panelOpacity),
                        ),
                    ),
                )
                .drawBehind {
                    drawOval(
                        color = organicAccent.copy(alpha = 0.075f),
                        topLeft = Offset(size.width * 0.64f, -size.height * 0.22f),
                        size = Size(size.width * 0.48f, size.height * 0.72f),
                    )
                    organicAccents.forEachIndexed { index, accent ->
                        val column = index % 3
                        val row = index / 3
                        drawOval(
                            color = accent.copy(alpha = 0.04f),
                            topLeft = Offset(
                                x = size.width * (0.08f + column * 0.29f),
                                y = size.height * (0.62f + row * 0.12f),
                            ),
                            size = Size(size.width * 0.22f, size.height * 0.34f),
                        )
                    }
                }
                .border(
                    BorderStroke(visuals.borderWidth, scheme.outlineVariant.copy(alpha = 0.82f)),
                    shape,
                )

        VisualStyle.MATERIAL -> {
            // Quiet surface language: a card sits one step above the canvas and is separated by
            // a hairline rather than a drop shadow. Shadow is reserved for chrome that really
            // floats (bottom bar, sheets, dialogs). When a wallpaper replaces the opaque canvas
            // the fill steps up to the brightest surface so panels stay legible over the image.
            val fill = if (scheme.background == Color.Transparent) {
                scheme.surfaceContainerLowest
            } else {
                scheme.surface
            }
            modifier
                .then(
                    if (visuals.customized && visuals.panelElevation > 0.dp) {
                        Modifier.shadow(visuals.panelElevation, shape)
                    } else {
                        Modifier
                    },
                )
                .clip(shape)
                .background(fill.copy(alpha = visuals.panelOpacity))
                .then(
                    if (visuals.borderWidth > 0.dp) {
                        Modifier.border(visuals.borderWidth, scheme.outlineVariant, shape)
                    } else {
                        Modifier
                    },
                )
        }

        VisualStyle.CUSTOM -> error("CUSTOM is resolved before it reaches LocalVisualStyle")
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Box(panelModifier.padding(scaledPanelPadding(padding)), content = content)
    }
}
