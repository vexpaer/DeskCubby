package com.deskcubby.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.LayoutMode
import com.deskcubby.app.ui.theme.DeskCubbyColors.hairline
import com.deskcubby.app.ui.theme.DeskCubbyColors.insetSurface
import com.deskcubby.app.ui.theme.DeskCubbyColors.selectedContainer
import com.deskcubby.app.ui.theme.DeskCubbyRadius
import com.deskcubby.app.ui.theme.DeskCubbySpacing
import com.deskcubby.app.ui.theme.DeskCubbyType
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalCompactMode
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.rememberAppMotion

/*
 * The shared component layer.
 *
 * Before this file existed, every screen assembled its own card, list row and section header
 * out of raw `GlassPanel` + `Column` + `Text`, which is why padding drifted between 10dp and
 * 20dp, list rows ranged from 44dp to 68dp tall, and section titles were sometimes accent
 * coloured and sometimes not. These components are the single place that decides those values,
 * and they resolve the visual style (Material / Liquid Glass / Organic Future) through
 * [GlassPanel] so the three themes keep working from one implementation.
 */

/** Edge padding for a page body, resolved from the window tier rather than a device name. */
@Composable
fun currentGutter(): Dp {
    val compact = LocalCompactMode.current
    return when (LocalLayoutMode.current) {
        LayoutMode.COMPACT -> if (compact) 12.dp else DeskCubbySpacing.gutterCompact
        LayoutMode.MEDIUM -> DeskCubbySpacing.gutterMedium
        LayoutMode.EXPANDED -> DeskCubbySpacing.gutterExpanded
    }
}

/**
 * Scale-down feedback for tappable surfaces. One spring, shared by every card and row, so a tap
 * feels the same on Home, Notes and Settings. Never bounces, always settles inside ~120ms.
 */
@Composable
fun rememberPressScale(interactionSource: MutableInteractionSource, enabled: Boolean): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val motion = rememberAppMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = motion.tween(DeskCubbyPressMillis),
        label = "desk-cubby-press",
    )
    return scale
}

private const val DeskCubbyPressMillis = 110

/**
 * The standard DeskCubby card: one step above the canvas, hairline separated, with clipped
 * ripple and press feedback. Use it instead of hand-rolling a panel so corner radius, inner
 * padding and touch behaviour cannot drift between pages.
 */
@Composable
fun DcCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    role: PanelRole = PanelRole.STANDARD,
    cornerRadius: Dp = DeskCubbyRadius.lg,
    contentPadding: PaddingValues? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val resolvedPadding = contentPadding ?: DcCardContentPadding
    val pressScale = rememberPressScale(interactionSource, enabled && onClick != null)
    val clickable = onClick != null
    GlassPanel(
        modifier = modifier.graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        },
        cornerRadius = cornerRadius,
        role = role,
        padding = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selected) {
                        Modifier.background(scheme.selectedContainer)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (clickable) {
                        Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(resolvedPadding),
            content = content,
        )
    }
}

/** Card padding that tightens with the user's compact-mode preference. */
val DcCardContentPadding: PaddingValues
    @Composable get() = if (LocalCompactMode.current) {
        PaddingValues(DeskCubbySpacing.cardPaddingCompact)
    } else {
        PaddingValues(DeskCubbySpacing.cardPadding)
    }

/**
 * Section label. Deliberately quiet: an uppercase eyebrow in the secondary colour, never the
 * accent. Sections are separated by space, not by a coloured heading competing with content.
 */
@Composable
fun DcSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = DeskCubbySpacing.xxs,
                end = DeskCubbySpacing.xxs,
                top = DeskCubbySpacing.xxs,
                bottom = DeskCubbySpacing.sm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = DeskCubbyType.eyebrow,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (action != null) {
            Spacer(Modifier.width(DeskCubbySpacing.md))
            action()
        }
    }
}

/**
 * The list row used by every navigable list in the app: a 52dp minimum touch target, a fixed
 * leading slot so titles align down the column, and one secondary line.
 */
@Composable
fun DcListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = DeskCubbySpacing.listRowMinHeight,
    shape: Shape = DeskCubbyRadius.md,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val scheme = MaterialTheme.colorScheme
    val pressScale = rememberPressScale(interactionSource, enabled && onClick != null)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .background(if (selected) scheme.selectedContainer else Color.Transparent, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = minHeight)
            .padding(
                horizontal = DeskCubbySpacing.md,
                vertical = DeskCubbySpacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.size(DeskCubbySpacing.touchTarget - 8.dp),
                contentAlignment = Alignment.Center,
            ) { leading() }
            Spacer(Modifier.width(DeskCubbySpacing.iconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = DeskCubbyType.listTitle,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(DeskCubbySpacing.xxxs))
                Text(
                    text = subtitle,
                    style = DeskCubbyType.listSubtitle,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(DeskCubbySpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/**
 * Numeric readout. Tabular figures keep digits from shifting width as counts change, which is
 * the difference between a statistics page that feels engineered and one that jitters.
 */
@Composable
fun DcStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    caption: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = DeskCubbyType.metric,
            color = if (accent) scheme.primary else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DeskCubbySpacing.xxs))
        Text(
            text = label,
            style = DeskCubbyType.metricLabel,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!caption.isNullOrBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Hairline separator. One thickness, one colour, everywhere. */
@Composable
fun DcDivider(modifier: Modifier = Modifier, indent: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = indent),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.hairline,
    )
}

/**
 * Small status label: sync state, permission mode, "unsaved". Uses the container roles so it
 * inherits the theme's accent without hard-coding a colour.
 */
@Composable
fun DcTag(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor ?: scheme.onPrimaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                color = containerColor ?: scheme.primaryContainer,
                shape = DeskCubbyRadius.control,
            )
            .padding(
                horizontal = DeskCubbySpacing.sm,
                vertical = DeskCubbySpacing.xxs,
            ),
    )
}

/**
 * Inset plane used for wells inside a card: code blocks, image backdrops, disabled previews.
 * Keeps nesting legible without inventing a new shadow.
 */
@Composable
fun DcInset(
    modifier: Modifier = Modifier,
    shape: Shape = DeskCubbyRadius.md,
    contentPadding: PaddingValues = PaddingValues(DeskCubbySpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.insetSurface, shape)
            .padding(contentPadding),
        content = content,
    )
}
