package com.deskcubby.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * DeskCubby design tokens.
 *
 * Every screen consumes these values instead of declaring its own numbers, so the spacing,
 * corner, elevation and motion language stays identical across diary, notes, thoughts,
 * statistics, AI, Desk and Settings. Semantic names describe the *role* of a value
 * (`cardPadding`, `listRowMinHeight`), never a component, which keeps pages that were
 * authored independently aligned on the same 4dp grid.
 */

/** Spatial rhythm. All page-level spacing is a multiple of 4dp. */
object DeskCubbySpacing {
    val xxxs: Dp = 2.dp
    val xxs: Dp = 4.dp
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val base: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 48.dp

    /** Distance between a screen edge and its content. Grows with the window, never with the device. */
    val gutterCompact: Dp = 16.dp
    val gutterMedium: Dp = 20.dp
    val gutterExpanded: Dp = 24.dp

    /** Inside a card or panel. */
    val cardPadding: Dp = 16.dp
    val cardPaddingCompact: Dp = 12.dp

    /** Vertical rhythm between stacked sections. */
    val sectionGap: Dp = 24.dp
    val cardGap: Dp = 12.dp

    /** Text next to an icon keeps a constant optical gap. */
    val iconGap: Dp = 12.dp
    val iconGapTight: Dp = 8.dp

    /** Touch targets stay at least 48dp; dense rows may compress to 52dp of total height. */
    val touchTarget: Dp = 48.dp
    val listRowMinHeight: Dp = 52.dp
    val listRowComfortable: Dp = 60.dp
    val controlHeight: Dp = 40.dp

    /** Bottom navigation and rail geometry. */
    val bottomBarHeight: Dp = 64.dp
    val railWidth: Dp = 88.dp
}

/** Corner language. One scale, referenced by role. */
object DeskCubbyRadius {
    val xs: Dp = 8.dp
    val sm: Dp = 10.dp
    val md: Dp = 14.dp
    val lg: Dp = 20.dp
    val xl: Dp = 26.dp
    val pill: Dp = 999.dp

    /** Small controls: chips, toggles, icon buttons. */
    val control: Shape = RoundedCornerShape(sm)

    /** Text fields and search bars. */
    val field: Shape = RoundedCornerShape(12.dp)

    /** Cards, list groups, inline panels. */
    val card: Shape = RoundedCornerShape(md)

    /** Hero panels, dialogs, floating chrome. */
    val panel: Shape = RoundedCornerShape(lg)

    /** Modal sheets anchored to an edge. */
    val sheet: Shape = RoundedCornerShape(topStart = xl, topEnd = xl)

    val pillShape: Shape = RoundedCornerShape(pill)
}

/**
 * Depth is expressed with contrast and hairlines, not shadow. Shadow is reserved for chrome
 * that genuinely floats above the content plane.
 */
object DeskCubbyElevation {
    val flat: Dp = 0.dp
    val hairline: Dp = 1.dp
    val raised: Dp = 2.dp
    val floating: Dp = 8.dp
    val overlay: Dp = 16.dp
}

/** Durations stay inside 120-340ms so motion never delays the next tap. */
object DeskCubbyMotion {
    const val INSTANT = 80
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 280
    const val EMPHASIS = 340

    /** Decelerate hard: elements arrive quickly and settle, which reads as responsive. */
    val EaseOut: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Accelerate for exits. */
    val EaseIn: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Symmetric curve for value animation (progress, selection). */
    val EaseStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Damped, never bouncy. */
    fun <T> settle(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> press(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )
}

/**
 * Motion budget resolved once per composition. It respects the system animator switch and the
 * user's custom-theme animation scale, so a single check replaces per-screen `remember`s and
 * keeps `transitionMillis == 0` a real "reduce motion" opt-out.
 */
@Immutable
data class AppMotion(val enabled: Boolean, val scale: Float) {
    fun millis(base: Int): Int =
        if (!enabled) 0 else (base * scale).roundToInt().coerceAtLeast(0)

    fun <T> tween(
        base: Int = DeskCubbyMotion.BASE,
        easing: Easing = DeskCubbyMotion.EaseStandard,
        delay: Int = 0,
    ): FiniteAnimationSpec<T> = if (!enabled) {
        snap()
    } else {
        tween(millis(base), delayMillis = millis(delay), easing = easing)
    }

    fun <T> tweenOut(
        base: Int = DeskCubbyMotion.BASE,
        delay: Int = 0,
    ): FiniteAnimationSpec<T> = tween(base, DeskCubbyMotion.EaseIn, delay)
}

@Composable
fun rememberAppMotion(): AppMotion {
    val visuals = deskCubbyVisuals
    val systemAnimators = remember { ValueAnimator.areAnimatorsEnabled() }
    return remember(visuals.transitionMillis, systemAnimators) {
        val scale = (visuals.transitionMillis / 300f).takeIf(Float::isFinite) ?: 1f
        AppMotion(
            enabled = systemAnimators && visuals.transitionMillis > 0,
            scale = scale.coerceIn(0f, 3f),
        )
    }
}

/**
 * Colour roles that Material's [ColorScheme] does not name but every DeskCubby surface needs.
 * Declared as extensions so screens read `colorScheme.hairline` rather than repeating
 * `outlineVariant.copy(alpha = ...)` with slightly different alphas on every page.
 */
object DeskCubbyColors {
    /** 1dp separator that never competes with content. */
    val ColorScheme.hairline: Color get() = outlineVariant

    /** Stronger separator used sparingly, e.g. under a sticky header. */
    val ColorScheme.hairlineStrong: Color get() = outline.copy(alpha = 0.28f)

    /** Fill behind a selected navigation destination or list row. */
    val ColorScheme.selectedContainer: Color get() = primary.copy(alpha = 0.12f)

    /** Pressed overlay shared by every interactive surface. */
    val ColorScheme.pressedOverlay: Color get() = onSurface.copy(alpha = 0.10f)

    /** The plane a card sits on. */
    val ColorScheme.cardSurface: Color
        get() = if (background == Color.Transparent) surfaceContainerLowest else surface

    /** Inset plane inside a card (code blocks, image wells, disabled rows). */
    val ColorScheme.insetSurface: Color get() = surfaceContainer

    /** Quiet accent wash used behind metrics and status chips. */
    val ColorScheme.accentWash: Color get() = primaryContainer.copy(alpha = 0.55f)

    val ColorScheme.textPrimary: Color get() = onSurface
    val ColorScheme.textSecondary: Color get() = onSurfaceVariant
}
