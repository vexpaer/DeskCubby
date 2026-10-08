package com.deskcubby.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.theme.DeskMotion
import com.deskcubby.app.ui.theme.LocalReducedMotion
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.withTabularFigures
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A numeral that counts up to [target] when it first appears and glides between later values.
 * Accessibility services always read the final value, never the intermediate frames.
 */
@Composable
fun CountUpNumber(
    target: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val reduced = LocalReducedMotion.current
    val value = remember { Animatable(if (reduced) target.toFloat() else 0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) {
            value.snapTo(target.toFloat())
        } else {
            value.animateTo(
                target.toFloat(),
                tween(durationMillis = countUpDurationMillis(target), easing = FastOutSlowInEasing),
            )
        }
    }
    Text(
        text = value.value.roundToInt().toString(),
        style = style.withTabularFigures(),
        color = color,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { contentDescription = target.toString() },
    )
}

/** Larger totals count for a little longer, but a counter never holds the eye past ~1.2s. */
internal fun countUpDurationMillis(target: Int): Int =
    (520 + 180 * kotlin.math.log10(kotlin.math.abs(target.toDouble()) + 1.0)).roundToInt()
        .coerceIn(520, 1_200)

/**
 * Rises and fades its content in, offset by [index] so a column of cards cascades into place.
 * When [alreadyShown] is true the content renders immediately (for example a card that scrolled
 * back into a lazy list after it already made its entrance).
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    alreadyShown: Boolean,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val style = LocalVisualStyle.current
    val progress = remember { Animatable(if (alreadyShown || reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(DeskMotion.staggerMillis(style) * index.coerceIn(0, MAX_STAGGERED_ITEMS))
            progress.animateTo(1f, DeskMotion.entrance<Float>(style))
        }
        onShown()
    }
    Box(
        modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 36.dp.toPx()
            val scale = 0.96f + 0.04f * p
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
    }
}

private const val MAX_STAGGERED_ITEMS = 8

/**
 * Horizontal extent of one navigation item, in px, relative to the indicator layer.
 */
data class NavItemSpan(val left: Float, val right: Float)

/**
 * Selection indicator drawn behind (Liquid Glass, Organic Future) or above (Material) a bottom
 * navigation bar. Its two edges travel on separate springs, so the shape stretches toward the new
 * destination and then settles; each visual style draws a different material:
 * - Material: a crisp ink rule along the top edge.
 * - Liquid Glass: a refractive capsule with a specular rim.
 * - Organic Future: a soft blob that squashes as it stretches, preserving its area.
 */
@Composable
fun NavSelectionIndicator(
    target: NavItemSpan?,
    modifier: Modifier = Modifier,
) {
    val style = LocalVisualStyle.current
    val reduced = LocalReducedMotion.current
    val scheme = MaterialTheme.colorScheme
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val visibility by animateFloatAsState(
        targetValue = if (target != null) 1f else 0f,
        animationSpec = tween(if (reduced) 0 else 180),
        label = "navIndicatorVisibility",
    )
    LaunchedEffect(target, reduced, style) {
        val span = target ?: return@LaunchedEffect
        if (!placed || reduced) {
            left.snapTo(span.left)
            right.snapTo(span.right)
            placed = true
            return@LaunchedEffect
        }
        val movingEnd = span.left > left.value
        launch {
            left.animateTo(
                span.left,
                if (movingEnd) DeskMotion.trailing<Float>(style) else DeskMotion.leading<Float>(style),
            )
        }
        launch {
            right.animateTo(
                span.right,
                if (movingEnd) DeskMotion.leading<Float>(style) else DeskMotion.trailing<Float>(style),
            )
        }
    }
    val darkSurface = scheme.surface.luminance() < 0.4f
    Canvas(modifier) {
        if (!placed || visibility <= 0f) return@Canvas
        val l = left.value
        val r = right.value
        val restingWidth = target?.let { it.right - it.left } ?: (r - l)
        when (style) {
            VisualStyle.LIQUID_GLASS -> {
                val inset = 6.dp.toPx()
                val top = inset
                val height = size.height - inset * 2
                val width = (r - l - inset * 2).coerceAtLeast(height * 0.6f)
                val radius = CornerRadius(height / 2f, height / 2f)
                val origin = Offset(l + inset, top)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = (if (darkSurface) 0.16f else 0.55f) * visibility),
                            scheme.primary.copy(alpha = 0.16f * visibility),
                        ),
                        startY = top,
                        endY = top + height,
                    ),
                    topLeft = origin,
                    size = Size(width, height),
                    cornerRadius = radius,
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = (if (darkSurface) 0.38f else 0.85f) * visibility),
                            scheme.primary.copy(alpha = 0.22f * visibility),
                        ),
                        start = origin,
                        end = Offset(origin.x + width, origin.y + height),
                    ),
                    topLeft = origin,
                    size = Size(width, height),
                    cornerRadius = radius,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            VisualStyle.ORGANIC_FUTURE -> {
                val inset = 8.dp.toPx()
                val baseHeight = size.height - inset * 2
                val baseWidth = (restingWidth - inset * 2).coerceAtLeast(1f)
                val width = (r - l - inset * 2).coerceAtLeast(baseHeight * 0.6f)
                // Area-preserving squash: a stretched blob gets thinner, then rebounds.
                val height = (baseHeight * (baseWidth / width).coerceIn(0.62f, 1.12f))
                val top = (size.height - height) / 2f
                drawRoundRect(
                    color = scheme.primaryContainer.copy(alpha = 0.92f * visibility),
                    topLeft = Offset(l + inset, top),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(height / 2f, height / 2f),
                )
            }

            else -> {
                val inkWidth = (r - l) * 0.42f
                val center = (l + r) / 2f
                val thickness = 3.dp.toPx()
                drawRoundRect(
                    color = scheme.primary.copy(alpha = visibility),
                    topLeft = Offset(center - inkWidth / 2f, 0f),
                    size = Size(inkWidth, thickness),
                    cornerRadius = CornerRadius(thickness, thickness),
                )
            }
        }
    }
}

/**
 * Tactile press feedback: while [interactionSource] is pressed the returned scale dips slightly,
 * then springs back with the active style's "pop" character on release. Apply it in a
 * graphicsLayer so it never triggers relayout. Stays at 1 when animations are removed.
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.965f,
): State<Float> {
    val reduced = LocalReducedMotion.current
    val style = LocalVisualStyle.current
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = DeskMotion.pop<Float>(style),
        label = "pressScale",
    )
}
