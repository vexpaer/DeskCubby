package com.deskcubby.app.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals

@Composable
fun AppLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    strokeWidth: Dp = 4.dp,
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

@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    iconSize: Dp = 52.dp,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val content: @Composable (Modifier) -> Unit = { contentModifier ->
        Column(
            modifier = contentModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                title,
                style = if (organic) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (!actionLabel.isNullOrBlank() && onAction != null) {
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (organic) {
            GlassPanel(
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                role = PanelRole.FEATURE,
                padding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
            ) {
                content(Modifier.fillMaxWidth())
            }
        } else {
            content(Modifier)
        }
    }
}
