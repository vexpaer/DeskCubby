package com.deskcubby.app.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.ui.components.CountUpNumber
import com.deskcubby.app.ui.theme.LocalReducedMotion
import com.deskcubby.app.ui.theme.tr
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Part of the day that tints the Home hero's ambient light. */
internal enum class DayPhase { DAWN, DAY, DUSK, NIGHT }

internal fun dayPhaseFor(time: LocalTime): DayPhase = when (time.hour) {
    in 5..7 -> DayPhase.DAWN
    in 8..16 -> DayPhase.DAY
    in 17..19 -> DayPhase.DUSK
    else -> DayPhase.NIGHT
}

/**
 * Horizontal position (0 = start, 1 = end) of the light source, following the sun from 06:00 to
 * 18:00. At night the "lamp" sits near the end edge.
 */
internal fun lightSourceFraction(time: LocalTime): Float {
    val minutes = time.hour * 60 + time.minute
    val dayStart = 6 * 60
    val dayEnd = 18 * 60
    return if (minutes in dayStart..dayEnd) {
        0.08f + 0.84f * (minutes - dayStart) / (dayEnd - dayStart).toFloat()
    } else {
        0.86f
    }
}

internal fun AppLanguage.javaLocale(): Locale = when (this) {
    AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
    AppLanguage.ENGLISH -> Locale.ENGLISH
    AppLanguage.KOREAN -> Locale.KOREAN
    AppLanguage.JAPANESE -> Locale.JAPANESE
}

private fun phaseColors(phase: DayPhase, scheme: ColorScheme): Pair<Color, Color> = when (phase) {
    DayPhase.DAWN -> scheme.tertiaryContainer to scheme.secondaryContainer
    DayPhase.DAY -> scheme.primaryContainer to scheme.tertiaryContainer
    DayPhase.DUSK -> scheme.secondaryContainer to scheme.tertiary
    DayPhase.NIGHT -> scheme.primary to scheme.inversePrimary
}

/**
 * Home's hero: an oversized day-of-month numeral beside the weekday, month and greeting, lit by a
 * soft light whose hue and position follow the local time of day. All colors come from the active
 * theme roles, so Material, Liquid Glass, Organic Future and Custom palettes each get their own
 * light. The light breathes slowly unless the system asks to remove animations.
 */
@Composable
internal fun HomeHeroHeader(
    greeting: String,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    now: LocalTime = LocalTime.now(),
) {
    val scheme = MaterialTheme.colorScheme
    val light = rememberDaylight(now)
    val phase = light.phase
    val locale = remember(language) { language.javaLocale() }
    val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    val month = today.month.getDisplayName(TextStyle.FULL, locale)
    val phaseLabel = when (phase) {
        DayPhase.DAWN -> tr("清晨", "Early morning")
        DayPhase.DAY -> tr("白天", "Daytime")
        DayPhase.DUSK -> tr("黄昏", "Dusk")
        DayPhase.NIGHT -> tr("夜晚", "Night")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .daylight(light)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CountUpNumber(
            target = today.dayOfMonth,
            style = MaterialTheme.typography.displayLarge,
            color = scheme.onSurface,
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "$weekday · $month · $phaseLabel".uppercase(locale),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = greeting,
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/** Theme-derived ambient light for the current local time, shared by Home's hero and the Desk. */
@Stable
internal class Daylight(
    val phase: DayPhase,
    val glow: Color,
    val halo: Color,
    val sourceX: Float,
    val breath: State<Float>?,
)

@Composable
internal fun rememberDaylight(now: LocalTime = LocalTime.now()): Daylight {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val phase = remember(now.hour) { dayPhaseFor(now) }
    val sourceX = remember(now.hour, now.minute / 10) { lightSourceFraction(now) }
    val (glow, halo) = phaseColors(phase, scheme)
    val breath = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "daylight").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "daylightBreath",
        )
    }
    return Daylight(phase, glow, halo, sourceX, breath)
}

/**
 * Paints [light] as a soft radial glow behind the content. [anchorY] places the source vertically
 * (fraction of the height) and [strength] scales its opacity. Only the draw phase reads the
 * breathing animation, so it never recomposes the content.
 */
internal fun Modifier.daylight(
    light: Daylight,
    anchorY: Float = 0.15f,
    strength: Float = 1f,
): Modifier = drawBehind {
    val pulse = light.breath?.value ?: 0.5f
    val night = light.phase == DayPhase.NIGHT
    val center = Offset(size.width * light.sourceX, size.height * (anchorY + 0.1f * pulse))
    val radius = size.maxDimension * (0.62f + 0.08f * pulse)
    drawRect(
        Brush.radialGradient(
            colors = listOf(
                light.glow.copy(alpha = (if (night) 0.30f else 0.70f) * strength),
                light.halo.copy(alpha = (if (night) 0.12f else 0.28f) * strength),
                Color.Transparent,
            ),
            center = center,
            radius = radius,
        ),
    )
}
