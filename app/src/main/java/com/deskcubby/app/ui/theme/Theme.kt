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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.VisualStyle
import androidx.core.view.WindowCompat

val LocalVisualStyle: ProvidableCompositionLocal<VisualStyle> =
    staticCompositionLocalOf { VisualStyle.MATERIAL }

val LocalAppLanguage: ProvidableCompositionLocal<AppLanguage> =
    staticCompositionLocalOf { AppLanguage.CHINESE }

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

@Composable
fun DeskCubbyTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val baseScheme = when (settings.visualStyle) {
        VisualStyle.MATERIAL -> if (dark) MaterialDark else MaterialLight
        VisualStyle.LIQUID_GLASS -> if (dark) GlassDark else GlassLight
    }
    val accent = Color(settings.themeColorArgb)
    val onAccent = if (accent.luminance() > 0.48f) Color.Black else Color.White
    val scheme = baseScheme.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = lerp(accent, if (dark) Color.Black else Color.White, if (dark) 0.48f else 0.72f),
        onPrimaryContainer = if (dark) Color.White else Color.Black,
        secondary = lerp(accent, baseScheme.onSurface, 0.35f),
    )
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
        LocalVisualStyle provides settings.visualStyle,
        LocalAppLanguage provides settings.appLanguage,
    ) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}

@Composable
fun tr(chinese: String, english: String): String =
    if (LocalAppLanguage.current == AppLanguage.ENGLISH) english else chinese

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalVisualStyle.current == VisualStyle.LIQUID_GLASS
    val shape = RoundedCornerShape(cornerRadius)
    val scheme = MaterialTheme.colorScheme
    val panelModifier = if (glass) {
        modifier
            .shadow(10.dp, shape, ambientColor = scheme.primary.copy(alpha = 0.16f))
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.surface.copy(alpha = 0.86f),
                        scheme.primaryContainer.copy(alpha = 0.52f),
                        scheme.surface.copy(alpha = 0.72f),
                    ),
                ),
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.58f), scheme.primary.copy(alpha = 0.18f)),
                    ),
                ),
                shape,
            )
    } else {
        modifier
            .shadow(1.dp, shape)
            .clip(shape)
            .background(scheme.surfaceContainer)
    }
    Box(panelModifier.padding(padding), content = content)
}
