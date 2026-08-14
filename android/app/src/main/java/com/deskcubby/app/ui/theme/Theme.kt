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

private val MaterialLight = lightColorScheme(
    primary = Color(0xFF42664D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4ECCD),
    onPrimaryContainer = Color(0xFF00210D),
    secondary = Color(0xFF526359),
    background = Color(0xFFF7FBF5),
    surface = Color(0xFFF7FBF5),
    surfaceVariant = Color(0xFFDDE5DD),
)

private val MaterialDark = darkColorScheme(
    primary = Color(0xFFA8D0B1),
    onPrimary = Color(0xFF123722),
    primaryContainer = Color(0xFF2B4E37),
    onPrimaryContainer = Color(0xFFC4ECCD),
    secondary = Color(0xFFB9CCBF),
    background = Color(0xFF101511),
    surface = Color(0xFF101511),
    surfaceVariant = Color(0xFF414943),
)

private val GlassLight = lightColorScheme(
    primary = Color(0xFF4C63A6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF071A52),
    secondary = Color(0xFF5D5F72),
    background = Color(0xFFF0F4FF),
    surface = Color(0xFFF8F9FF),
    surfaceVariant = Color(0xFFE1E5F4),
)

private val GlassDark = darkColorScheme(
    primary = Color(0xFFB6C4FF),
    onPrimary = Color(0xFF1B326F),
    primaryContainer = Color(0xFF344A88),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFC5C6DC),
    background = Color(0xFF0D1020),
    surface = Color(0xFF151827),
    surfaceVariant = Color(0xFF444654),
)

private val DefaultShapes = Shapes()

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
        else -> if (effectiveStyle == VisualStyle.ORGANIC_FUTURE) OrganicFutureShapes else DefaultShapes
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
    cornerRadius: Dp = 24.dp,
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

        VisualStyle.MATERIAL -> modifier
                .shadow(if (visuals.customized) visuals.panelElevation else 1.dp, shape)
                .clip(shape)
                .background(scheme.surfaceContainer.copy(alpha = visuals.panelOpacity))
                .then(
                    if (visuals.customized && visuals.borderWidth > 0.dp) {
                        Modifier.border(visuals.borderWidth, scheme.outlineVariant, shape)
                    } else {
                        Modifier
                    },
                )

        VisualStyle.CUSTOM -> error("CUSTOM is resolved before it reaches LocalVisualStyle")
    }
    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Box(panelModifier.padding(scaledPanelPadding(padding)), content = content)
    }
}
