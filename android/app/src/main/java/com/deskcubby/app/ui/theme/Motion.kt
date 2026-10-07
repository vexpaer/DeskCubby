package com.deskcubby.app.ui.theme

import android.animation.ValueAnimator
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import com.deskcubby.app.data.model.VisualStyle

/**
 * True when the system "Remove animations" / animator duration scale 0 is active. Decorative
 * motion (count-ups, entrances, ambient light, indicator stretch) must snap instead of animating.
 */
val LocalReducedMotion: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

internal fun systemReducedMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

/**
 * One motion language per visual style:
 * - Material: crisp, critically damped ("ink" that lands without bounce).
 * - Liquid Glass: springy with a visible overshoot, like a droplet settling.
 * - Organic Future: slow, soft and fluid.
 */
object DeskMotion {
    /** Leading edge of a moving selection indicator. */
    fun <T> leading(style: VisualStyle): SpringSpec<T> = when (style) {
        VisualStyle.LIQUID_GLASS -> spring(dampingRatio = 0.62f, stiffness = 900f)
        VisualStyle.ORGANIC_FUTURE -> spring(dampingRatio = 0.78f, stiffness = 520f)
        else -> spring(dampingRatio = 1f, stiffness = 700f)
    }

    /** Trailing edge; slower than [leading] so the indicator stretches while travelling. */
    fun <T> trailing(style: VisualStyle): SpringSpec<T> = when (style) {
        VisualStyle.LIQUID_GLASS -> spring(dampingRatio = 0.7f, stiffness = 240f)
        VisualStyle.ORGANIC_FUTURE -> spring(dampingRatio = 0.85f, stiffness = 150f)
        else -> spring(dampingRatio = 1f, stiffness = 700f)
    }

    /** Content entering the screen (staggered cards, hero numerals). */
    fun <T> entrance(style: VisualStyle): SpringSpec<T> = when (style) {
        VisualStyle.LIQUID_GLASS -> spring(dampingRatio = 0.72f, stiffness = 260f)
        VisualStyle.ORGANIC_FUTURE -> spring(dampingRatio = 0.9f, stiffness = 120f)
        else -> spring(dampingRatio = 0.95f, stiffness = 380f)
    }

    /** Small pops such as the selected navigation icon. */
    fun <T> pop(style: VisualStyle): SpringSpec<T> = when (style) {
        VisualStyle.LIQUID_GLASS -> spring(dampingRatio = 0.42f, stiffness = 520f)
        VisualStyle.ORGANIC_FUTURE -> spring(dampingRatio = 0.6f, stiffness = 260f)
        else -> spring(dampingRatio = 0.8f, stiffness = 700f)
    }

    /** Delay between consecutive items of a staggered entrance. */
    fun staggerMillis(style: VisualStyle): Long = when (style) {
        VisualStyle.ORGANIC_FUTURE -> 70L
        VisualStyle.LIQUID_GLASS -> 50L
        else -> 35L
    }
}

/**
 * Haptic vocabulary. Feedback goes through [View.performHapticFeedback], so the user's system
 * touch-feedback setting is always honored.
 */
class DeskHaptics internal constructor(private val view: View) {
    /** A meaningful action has really completed (for example a verified save). */
    fun confirm() {
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    /** A light selection change such as switching tabs. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

@Composable
fun rememberDeskHaptics(): DeskHaptics {
    val view = LocalView.current
    return remember(view) { DeskHaptics(view) }
}
