package com.deskcubby.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskcubby.app.data.model.VisualStyle

internal val OrganicFutureLight = lightColorScheme(
    primary = Color(0xFF087A43),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F4CA),
    onPrimaryContainer = Color(0xFF00210E),
    inversePrimary = Color(0xFF72F28F),
    primaryFixed = Color(0xFFB8F4CA),
    primaryFixedDim = Color(0xFF72F28F),
    onPrimaryFixed = Color(0xFF00210E),
    onPrimaryFixedVariant = Color(0xFF07542E),
    secondary = Color(0xFF506456),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D6),
    onSecondaryContainer = Color(0xFF0D2014),
    secondaryFixed = Color(0xFFD2E8D6),
    secondaryFixedDim = Color(0xFFB7CBB9),
    onSecondaryFixed = Color(0xFF0D2014),
    onSecondaryFixedVariant = Color(0xFF384C3D),
    tertiary = Color(0xFF5D701E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF2A0),
    onTertiaryContainer = Color(0xFF1A2400),
    tertiaryFixed = Color(0xFFDDF2A0),
    tertiaryFixedDim = Color(0xFFC1D87C),
    onTertiaryFixed = Color(0xFF1A2400),
    onTertiaryFixedVariant = Color(0xFF445308),
    background = Color(0xFFF3F1E7),
    onBackground = Color(0xFF152119),
    surface = Color(0xFFF8F6ED),
    onSurface = Color(0xFF152119),
    surfaceVariant = Color(0xFFDCE7DC),
    onSurfaceVariant = Color(0xFF404D43),
    surfaceTint = Color(0xFF087A43),
    inverseSurface = Color(0xFF29352D),
    inverseOnSurface = Color(0xFFF0F2E9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7C71),
    outlineVariant = Color(0xFFC1CDC2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF9F1),
    surfaceDim = Color(0xFFD7DAD1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F0E7),
    surfaceContainer = Color(0xFFE9ECE3),
    surfaceContainerHigh = Color(0xFFE3E6DD),
    surfaceContainerHighest = Color(0xFFDDE1D8),
)

internal val OrganicFutureDark = darkColorScheme(
    primary = Color(0xFF72F28F),
    onPrimary = Color(0xFF00210B),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFC3F8CF),
    inversePrimary = Color(0xFF087A43),
    primaryFixed = Color(0xFFB8F4CA),
    primaryFixedDim = Color(0xFF72F28F),
    onPrimaryFixed = Color(0xFF00210E),
    onPrimaryFixedVariant = Color(0xFF07542E),
    secondary = Color(0xFFB7CBB9),
    onSecondary = Color(0xFF223528),
    secondaryContainer = Color(0xFF304537),
    onSecondaryContainer = Color(0xFFD3E8D5),
    secondaryFixed = Color(0xFFD2E8D6),
    secondaryFixedDim = Color(0xFFB7CBB9),
    onSecondaryFixed = Color(0xFF0D2014),
    onSecondaryFixedVariant = Color(0xFF384C3D),
    tertiary = Color(0xFFD0EF83),
    onTertiary = Color(0xFF293500),
    tertiaryContainer = Color(0xFF3E510F),
    onTertiaryContainer = Color(0xFFE3F9A3),
    tertiaryFixed = Color(0xFFDDF2A0),
    tertiaryFixedDim = Color(0xFFC1D87C),
    onTertiaryFixed = Color(0xFF1A2400),
    onTertiaryFixedVariant = Color(0xFF445308),
    background = Color(0xFF07110B),
    onBackground = Color(0xFFF0EEE4),
    surface = Color(0xFF0A1510),
    onSurface = Color(0xFFF0EEE4),
    surfaceVariant = Color(0xFF2B3930),
    onSurfaceVariant = Color(0xFFC0CCC1),
    surfaceTint = Color(0xFF72F28F),
    inverseSurface = Color(0xFFE1E6DE),
    inverseOnSurface = Color(0xFF1C2A21),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF7C8D81),
    outlineVariant = Color(0xFF34443A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B3930),
    surfaceDim = Color(0xFF07110B),
    surfaceContainerLowest = Color(0xFF040A06),
    surfaceContainerLow = Color(0xFF0C1812),
    surfaceContainer = Color(0xFF101D16),
    surfaceContainerHigh = Color(0xFF17251C),
    surfaceContainerHighest = Color(0xFF203026),
)

internal fun organicFutureColorScheme(dark: Boolean) =
    if (dark) OrganicFutureDark else OrganicFutureLight

private val DefaultTypography = Typography()

internal val OrganicFutureTypography = DefaultTypography.copy(
    displayLarge = DefaultTypography.displayLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 52.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1.4).sp,
    ),
    displayMedium = DefaultTypography.displayMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp,
        lineHeight = 49.sp,
        letterSpacing = (-1.1).sp,
    ),
    displaySmall = DefaultTypography.displaySmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 39.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = DefaultTypography.headlineLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 33.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = DefaultTypography.headlineMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = DefaultTypography.headlineSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = DefaultTypography.titleLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = DefaultTypography.titleMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.05).sp,
    ),
    titleSmall = DefaultTypography.titleSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = DefaultTypography.bodyLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = DefaultTypography.bodyMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.12.sp,
    ),
    bodySmall = DefaultTypography.bodySmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.18.sp,
    ),
    labelLarge = DefaultTypography.labelLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.35.sp,
    ),
    labelMedium = DefaultTypography.labelMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = DefaultTypography.labelSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.55.sp,
    ),
)

internal val OrganicFutureShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 13.dp,
        bottomEnd = 20.dp,
        bottomStart = 15.dp,
    ),
    large = RoundedCornerShape(
        topStart = 24.dp,
        topEnd = 17.dp,
        bottomEnd = 27.dp,
        bottomStart = 20.dp,
    ),
    extraLarge = RoundedCornerShape(
        topStart = 30.dp,
        topEnd = 22.dp,
        bottomEnd = 34.dp,
        bottomStart = 25.dp,
    ),
)

enum class PanelRole {
    STANDARD,
    FEATURE,
    MEDIA,
    TOOLBAR,
}

@Immutable
data class DeskCubbyVisualTokens(
    val organic: Boolean,
    val featureShape: Shape,
    val listShape: Shape,
    val mediaShape: Shape,
    val badgeShape: Shape,
    val toolbarShape: Shape,
    val borderWidth: Dp,
    val panelElevation: Dp,
    val spaceSmall: Dp,
    val spaceMedium: Dp,
    val contentPadding: Dp,
    val transitionMillis: Int,
)

private val StandardVisualTokens = DeskCubbyVisualTokens(
    organic = false,
    featureShape = RoundedCornerShape(24.dp),
    listShape = RoundedCornerShape(16.dp),
    mediaShape = RoundedCornerShape(20.dp),
    badgeShape = RoundedCornerShape(50),
    toolbarShape = RoundedCornerShape(0.dp),
    borderWidth = 1.dp,
    panelElevation = 1.dp,
    spaceSmall = 8.dp,
    spaceMedium = 12.dp,
    contentPadding = 16.dp,
    transitionMillis = 300,
)

private val OrganicVisualTokens = DeskCubbyVisualTokens(
    organic = true,
    featureShape = RoundedCornerShape(
        topStart = 30.dp,
        topEnd = 18.dp,
        bottomEnd = 34.dp,
        bottomStart = 22.dp,
    ),
    listShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 13.dp,
        bottomEnd = 22.dp,
        bottomStart = 16.dp,
    ),
    mediaShape = RoundedCornerShape(
        topStart = 34.dp,
        topEnd = 19.dp,
        bottomEnd = 38.dp,
        bottomStart = 24.dp,
    ),
    badgeShape = RoundedCornerShape(
        topStartPercent = 48,
        topEndPercent = 34,
        bottomEndPercent = 52,
        bottomStartPercent = 39,
    ),
    toolbarShape = RoundedCornerShape(0.dp),
    borderWidth = 1.dp,
    panelElevation = 2.dp,
    spaceSmall = 8.dp,
    spaceMedium = 12.dp,
    contentPadding = 16.dp,
    transitionMillis = 340,
)

val LocalDeskCubbyVisuals: ProvidableCompositionLocal<DeskCubbyVisualTokens> =
    staticCompositionLocalOf { StandardVisualTokens }

val deskCubbyVisuals: DeskCubbyVisualTokens
    @Composable get() = LocalDeskCubbyVisuals.current

internal fun visualTokensFor(style: VisualStyle): DeskCubbyVisualTokens = when (style) {
    VisualStyle.MATERIAL,
    VisualStyle.LIQUID_GLASS,
    -> StandardVisualTokens
    VisualStyle.ORGANIC_FUTURE -> OrganicVisualTokens
}

internal fun organicPanelShape(cornerRadius: Dp, role: PanelRole): Shape {
    if (cornerRadius == 0.dp || role == PanelRole.TOOLBAR) return OrganicVisualTokens.toolbarShape
    return when (role) {
        PanelRole.FEATURE -> OrganicVisualTokens.featureShape
        PanelRole.MEDIA -> OrganicVisualTokens.mediaShape
        PanelRole.STANDARD -> RoundedCornerShape(
            topStart = cornerRadius * 1.18f,
            topEnd = cornerRadius * 0.72f,
            bottomEnd = cornerRadius * 1.28f,
            bottomStart = cornerRadius * 0.88f,
        )
        PanelRole.TOOLBAR -> OrganicVisualTokens.toolbarShape
    }
}
