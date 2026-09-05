package com.deskcubby.app.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.theme.DeskCubbyColors.accentWash
import com.deskcubby.app.ui.theme.DeskCubbySpacing
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals

/*
 * The three states every list surface needs: loading, empty and failed.
 *
 * They share one geometry so a screen reads the same whether it is waiting, has nothing, or
 * could not reach the data. Empty states stay quiet on purpose - a personal journal with no
 * entries yet should invite the first entry, not apologise for being empty.
 */

@Composable
fun AppLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    strokeWidth: Dp = 2.5.dp,
) {
    if (LocalVisualStyle.current != VisualStyle.ORGANIC_FUTURE) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            strokeWidth = strokeWidth,
        )
        return
    }

    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val visuals = deskCubbyVisuals
    val scale = if (animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "organic-loading")
        transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(2_600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "organic-loading-breath",
        ).value
    } else {
        1f
    }

    Box(
        modifier = modifier
            .progressSemantics()
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (animationsEnabled) 0.88f + (scale - 0.94f) else 1f
            }
            .background(MaterialTheme.colorScheme.primaryContainer, visuals.badgeShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.3f)
                .background(MaterialTheme.colorScheme.primary, visuals.badgeShape),
        )
    }
}

/** Full-area centred loading state for a page that has nothing to show yet. */
@Composable
fun AppLoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(DeskCubbySpacing.xxl),
        ) {
            AppLoadingIndicator()
            if (!label.isNullOrBlank()) {
                Spacer(Modifier.height(DeskCubbySpacing.base))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    iconSize: Dp = 40.dp,
    actionEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val content: @Composable () -> Unit = {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(horizontal = DeskCubbySpacing.base),
        ) {
            Box(
                modifier = Modifier
                    .size(iconSize + DeskCubbySpacing.xxl)
                    .background(
                        if (organic) scheme.primaryContainer else scheme.accentWash,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(iconSize),
                )
            }
            Spacer(Modifier.height(DeskCubbySpacing.lg))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(DeskCubbySpacing.sm))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                Spacer(Modifier.height(DeskCubbySpacing.xl))
                FilledTonalButton(onClick = onAction, enabled = actionEnabled) {
                    Text(actionLabel)
                }
            }
            if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                Spacer(Modifier.height(DeskCubbySpacing.xxs))
                TextButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
            }
        }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (organic) {
            GlassPanel(
                modifier = Modifier
                    .padding(DeskCubbySpacing.xxl)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
                role = PanelRole.FEATURE,
                padding = PaddingValues(
                    horizontal = DeskCubbySpacing.xl,
                    vertical = DeskCubbySpacing.xxl,
                ),
            ) { content() }
        } else {
            Box(Modifier.padding(vertical = DeskCubbySpacing.xxl)) { content() }
        }
    }
}

/**
 * Failure state. Distinct from empty: it names what failed, keeps the detail in the secondary
 * colour so an error never reads as a shout, and offers a retry.
 */
@Composable
fun AppErrorState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(DeskCubbySpacing.xxl),
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(DeskCubbySpacing.xxxl)
                        .background(scheme.errorContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.height(DeskCubbySpacing.base))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(DeskCubbySpacing.sm))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (!retryLabel.isNullOrBlank() && onRetry != null) {
                Spacer(Modifier.height(DeskCubbySpacing.xl))
                FilledTonalButton(onClick = onRetry) { Text(retryLabel) }
            }
        }
    }
}

/** Quiet inline hint for surfaces that are configured but have nothing to report yet. */
@Composable
fun AppInlineHint(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
