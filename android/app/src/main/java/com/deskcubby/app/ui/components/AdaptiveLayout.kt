package com.deskcubby.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.LayoutMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr

/**
 * Immutable snapshot of the window geometry used to derive a [LayoutMode].
 *
 * Orientation (rotation) and layout (structure) are intentionally separated across the
 * whole codebase: orientation decides how the activity rotates, LayoutMode decides how
 * many panes exist. A portrait tablet can still be wide, and a landscape phone may not
 * fit three columns.
 */
@Immutable
data class WindowInfo(
    val widthDp: Dp,
    val heightDp: Dp,
    val isLandscape: Boolean,
    val smallestWidthDp: Dp,
)

/**
 * CompositionLocal exposing the resolved [LayoutMode] so individual screens can branch to
 * their workspace layout without duplicating window math or hard-coding the orientation flag.
 * Defaults to COMPACT so previews and un-themed callers stay safe.
 */
val LocalLayoutMode: ProvidableCompositionLocal<LayoutMode> =
    compositionLocalOf { LayoutMode.COMPACT }

/** Below this width a single-pane compact layout is always used. */
val MEDIUM_WIDTH_DP: Dp = 600.dp

/** At or above this width a three-pane expanded workspace becomes available. */
val EXPANDED_WIDTH_DP: Dp = 840.dp

/**
 * Resolve the UI structure tier from actual window geometry (not the bare orientation flag).
 *
 * - COMPACT: phones and small windows — single pane, bottom navigation.
 * - MEDIUM: wider phones, small tablets, split-screen — navigation rail + main content.
 * - EXPANDED: large tablets in landscape — navigation rail + workspace + context panel.
 */
fun resolveLayoutMode(info: WindowInfo): LayoutMode = when {
    info.widthDp < MEDIUM_WIDTH_DP -> LayoutMode.COMPACT
    info.widthDp < EXPANDED_WIDTH_DP || !info.isLandscape -> LayoutMode.MEDIUM
    else -> LayoutMode.EXPANDED
}

/** Observe the current window geometry as a stable [WindowInfo]. */
@Stable
@Composable
fun rememberWindowInfo(): WindowInfo {
    val configuration = LocalConfiguration.current
    return remember(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.orientation,
    ) {
        val widthDp = configuration.screenWidthDp.dp
        val heightDp = configuration.screenHeightDp.dp
        WindowInfo(
            widthDp = widthDp,
            heightDp = heightDp,
            isLandscape = widthDp > heightDp,
            smallestWidthDp = minOf(widthDp, heightDp),
        )
    }
}

/**
 * A left navigation rail (instead of the bottom bar) rendered when a wider [LayoutMode]
 * is active. It keeps the exact same destination set and selection logic as the bottom
 * bar; only the visual form and placement change.
 */
@Composable
fun DeskCubbyNavigationRail(
    items: List<NavItemConfig>,
    selectedRoute: String?,
    onSelected: (NavItemConfig) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    railWidth: Dp = 84.dp,
) {
    val language = LocalAppLanguage.current
    val glass = LocalVisualStyle.current == VisualStyle.LIQUID_GLASS
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE

    val primaryItems = items.filter { it.id != NavItemId.SETTINGS }
    val backgroundColor = when {
        glass || organic -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    Box(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight(),
    ) {
        val surface: @Composable BoxScope.() -> Unit = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Safe-area awareness: keep the logo and destination icons clear of the
                    // status bar and the side/bottom system navigation bars on edge-to-edge
                    // landscape devices.
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                NavigationRail(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    header = { Spacer(Modifier.height(4.dp)) },
                ) {
                    primaryItems.forEach { item ->
                        val label = resolveNavLabel(item, language)
                        NavigationRailItem(
                            selected = selectedRoute == item.id.route,
                            onClick = { onSelected(item) },
                            icon = { Icon(iconFor(item.iconKey), label) },
                            label = { Text(label, maxLines = 1) },
                            alwaysShowLabel = true,
                            colors = navigationRailItemColors(),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Settings / profile entry pinned to the bottom.
                    NavigationRailItem(
                        selected = selectedRoute == NavItemId.SETTINGS.route,
                        onClick = onOpenSettings,
                        icon = { Icon(Icons.Outlined.Settings, tr("设置", "Settings")) },
                        label = { Text(tr("设置", "Settings"), maxLines = 1) },
                        alwaysShowLabel = true,
                        colors = navigationRailItemColors(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        if (glass || organic) {
            GlassPanel(
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                role = if (organic) PanelRole.FEATURE else PanelRole.STANDARD,
                padding = PaddingValues(0.dp),
            ) { surface() }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor),
            ) { surface() }
        }
    }
}

@Composable
private fun navigationRailItemColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

private fun resolveNavLabel(item: NavItemConfig, language: AppLanguage): String =
    if (language == AppLanguage.ENGLISH && item.label == item.id.defaultLabel) {
        item.id.englishLabel
    } else {
        item.label
    }

/**
 * A reusable right-hand context panel with a consistent width, divider, enter/exit
 * animation and content slot. Individual pages supply their own body; nothing renders
 * when [content] is null so an unnecessary empty pane is never forced on screen.
 */
@Composable
fun ContextPanel(
    content: (@Composable ColumnScope.() -> Unit)?,
    modifier: Modifier = Modifier,
    panelWidth: Dp = 320.dp,
    topInset: Dp = 12.dp,
) {
    AnimatedVisibility(
        visible = content != null,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 3 },
        exit = fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { it / 3 },
        modifier = modifier.fillMaxHeight(),
    ) {
        if (content != null) {
            Row(Modifier.fillMaxHeight().clipToBounds()) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxHeight().width(1.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
                )
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .padding(top = topInset),
                    content = content,
                )
            }
        }
    }
}

/** Optional helper so a page can cap its reading column width on wide screens. */
fun readingContentMaxWidthDp(availableWidthDp: Float): Dp =
    availableWidthDp.coerceIn(650f, 850f).dp
