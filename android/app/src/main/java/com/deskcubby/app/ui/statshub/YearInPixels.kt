package com.deskcubby.app.ui.statshub

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deskcubby.app.ui.home.javaLocale
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.LocalReducedMotion
import com.deskcubby.app.ui.theme.tr
import com.deskcubby.app.ui.theme.withTabularFigures
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle

/** Day-of-year index for a (month, day) grid cell, or null when that date does not exist. */
internal fun yearPixelIndex(year: Int, month: Int, day: Int): Int? {
    if (month !in 1..12 || day < 1) return null
    val yearMonth = java.time.YearMonth.of(year, month)
    if (day > yearMonth.lengthOfMonth()) return null
    return yearMonth.atDay(day).dayOfYear - 1
}

/**
 * A whole year of diary writing as a 12 × 31 grid of pixels: one row per month, one cell per day,
 * shaded by how much was written relative to the busiest day. Cells fill in along the calendar
 * when the panel appears; tapping a cell reads out that day. Future days stay hollow.
 */
@Composable
internal fun YearInPixelsPanel(
    pixels: DiaryYearPixels,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val locale = LocalAppLanguage.current.javaLocale()
    val progress = remember(pixels.year) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(pixels.year, reduced) {
        if (reduced) progress.snapTo(1f) else progress.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing))
    }
    var selected by remember(pixels.year) { mutableStateOf<Int?>(null) }
    val summary = tr(
        "${pixels.year} 年已写 ${pixels.writtenDays} 天",
        "${pixels.writtenDays} days written in ${pixels.year}",
    )
    val days = pixels.dailyWords.size.coerceAtLeast(1)
    val emptyCell = scheme.surfaceVariant.copy(alpha = 0.6f)
    val futureCell = scheme.outlineVariant.copy(alpha = 0.35f)
    val lowCell = scheme.primaryContainer
    val highCell = scheme.primary
    val todayRing = scheme.onSurface

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val labelWidth = 22.dp
                val cell = (maxWidth - labelWidth) / 31
                // Month initials must fit inside one cell row, whatever the screen width.
                val labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = with(LocalDensity.current) { (cell * 0.72f).toSp() },
                    lineHeight = with(LocalDensity.current) { cell.toSp() },
                )
                Row {
                    Column(Modifier.width(labelWidth)) {
                        Month.entries.forEach { month ->
                            Text(
                                text = month.getDisplayName(TextStyle.NARROW, locale),
                                style = labelStyle,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.height(cell),
                            )
                        }
                    }
                    Canvas(
                        modifier = Modifier
                            .size(width = cell * 31, height = cell * 12)
                            .semantics { contentDescription = summary }
                            .pointerInput(pixels) {
                                detectTapGestures { offset ->
                                    val side = size.width / 31f
                                    val day = (offset.x / side).toInt() + 1
                                    val month = (offset.y / side).toInt() + 1
                                    selected = yearPixelIndex(pixels.year, month, day)
                                }
                            },
                    ) {
                        val side = size.width / 31f
                        val gap = (side * 0.16f).coerceAtLeast(1f)
                        val corner = CornerRadius(side * 0.22f)
                        for (month in 1..12) {
                            for (day in 1..31) {
                                val index = yearPixelIndex(pixels.year, month, day) ?: continue
                                if (index.toFloat() / days > progress.value) continue
                                val topLeft = Offset((day - 1) * side + gap / 2, (month - 1) * side + gap / 2)
                                val cellSize = Size(side - gap, side - gap)
                                val color = when {
                                    index > pixels.todayIndex -> futureCell
                                    else -> when (val level = pixels.levelAt(index)) {
                                        0 -> emptyCell
                                        else -> lerp(lowCell, highCell, (level - 1) / 3f)
                                    }
                                }
                                drawRoundRect(color = color, topLeft = topLeft, size = cellSize, cornerRadius = corner)
                                if (index == pixels.todayIndex || index == selected) {
                                    drawRoundRect(
                                        color = if (index == selected) highCell else todayRing,
                                        topLeft = topLeft,
                                        size = cellSize,
                                        cornerRadius = corner,
                                        style = Stroke(width = (side * 0.12f).coerceAtLeast(1.5f)),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selected?.let { index ->
                        val date = LocalDate.ofYearDay(pixels.year, index + 1)
                        val words = pixels.dailyWords.getOrElse(index) { 0L }
                        val label = date.month.getDisplayName(TextStyle.SHORT, locale) + " " + date.dayOfMonth
                        when {
                            index > pixels.todayIndex -> tr("$label · 还没到", "$label · not yet")
                            pixels.written.getOrNull(index) == true -> tr("$label · $words 字", "$label · $words words")
                            else -> tr("$label · 没有日记", "$label · no diary")
                        }
                    } ?: summary,
                    style = MaterialTheme.typography.bodyMedium.withTabularFigures(),
                    color = scheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                PixelLegend(emptyCell, lowCell, highCell)
            }
        }
    }
}

@Composable
private fun PixelLegend(empty: Color, low: Color, high: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            tr("少", "Less"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(4.dp))
        listOf(empty, low, lerp(low, high, 1f / 3f), lerp(low, high, 2f / 3f), high).forEach { color ->
            Canvas(Modifier.size(10.dp)) {
                drawRoundRect(color = color, cornerRadius = CornerRadius(size.width * 0.22f))
            }
            Spacer(Modifier.width(2.dp))
        }
        Spacer(Modifier.width(2.dp))
        Text(
            tr("多", "More"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
