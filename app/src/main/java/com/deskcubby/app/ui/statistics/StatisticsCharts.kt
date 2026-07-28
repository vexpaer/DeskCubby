@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.deskcubby.app.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.statistics.StatisticsChartType
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.statistics.StatisticsRange
import com.deskcubby.app.ui.theme.tr
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun StatisticsChartControls(
    range: StatisticsRange,
    chartType: StatisticsChartType,
    onRangeChange: (StatisticsRange) -> Unit,
    onChartTypeChange: (StatisticsChartType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatisticsRange.entries.forEach { item ->
                FilterChip(
                    selected = range == item,
                    onClick = { onRangeChange(item) },
                    label = {
                        Text(
                            when (item) {
                                StatisticsRange.LAST_7_DAYS -> tr("7 天", "7 days")
                                StatisticsRange.LAST_30_DAYS -> tr("30 天", "30 days")
                                StatisticsRange.LAST_90_DAYS -> tr("90 天", "90 days")
                                StatisticsRange.ALL -> tr("全部", "All")
                            },
                        )
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatisticsChartType.entries.forEach { item ->
                val label = when (item) {
                    StatisticsChartType.BARS -> tr("直方图", "Bars")
                    StatisticsChartType.LINE -> tr("曲线", "Line")
                    StatisticsChartType.CALENDAR -> tr("格子图", "Grid")
                }
                FilterChip(
                    selected = chartType == item,
                    onClick = { onChartTypeChange(item) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Icon(
                            imageVector = when (item) {
                                StatisticsChartType.BARS -> Icons.Outlined.BarChart
                                StatisticsChartType.LINE -> Icons.Outlined.ShowChart
                                StatisticsChartType.CALENDAR -> Icons.Outlined.GridOn
                            },
                            contentDescription = label,
                        )
                    },
                )
            }
        }
    }
}

@Composable
fun StatisticsChart(
    points: List<StatisticsPoint>,
    chartType: StatisticsChartType,
    valueDescription: (Double?) -> String,
    onPointSelected: (StatisticsPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = remember(points, valueDescription) {
        points.joinToString(separator = ", ") {
            "${it.date}: ${valueDescription(it.value)}"
        }
    }
    val chartModifier = modifier
        .fillMaxWidth()
        .semantics { contentDescription = description }
    when (chartType) {
        StatisticsChartType.BARS ->
            BarStatisticsChart(points, onPointSelected, chartModifier)
        StatisticsChartType.LINE ->
            LineStatisticsChart(points, onPointSelected, chartModifier)
        StatisticsChartType.CALENDAR ->
            CalendarStatisticsChart(points, onPointSelected, chartModifier)
    }
}

@Composable
private fun BarStatisticsChart(
    points: List<StatisticsPoint>,
    onPointSelected: (StatisticsPoint) -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    val maxValue = points.mapNotNull(StatisticsPoint::value).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Canvas(
        modifier
            .height(CHART_HEIGHT)
            .pointerInput(points) {
                detectTapGestures { position ->
                    if (points.isNotEmpty() && size.width > 0) {
                        val index = floor(position.x / size.width * points.size)
                            .toInt()
                            .coerceIn(points.indices)
                        onPointSelected(points[index])
                    }
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val slot = size.width / points.size
        val barWidth = (slot - gap).coerceAtLeast(1.dp.toPx())
        points.forEachIndexed { index, point ->
            val value = point.value
            val left = index * slot + (slot - barWidth) / 2f
            if (value == null) {
                drawRect(
                    color = empty,
                    topLeft = Offset(left, size.height - 2.dp.toPx()),
                    size = Size(barWidth, 2.dp.toPx()),
                )
            } else {
                val height = ((value / maxValue) * size.height).toFloat()
                    .coerceAtLeast(if (value > 0.0) 2.dp.toPx() else 0f)
                drawRoundRect(
                    color = primary,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun LineStatisticsChart(
    points: List<StatisticsPoint>,
    onPointSelected: (StatisticsPoint) -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    val maxValue = points.mapNotNull(StatisticsPoint::value).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Canvas(
        modifier
            .height(CHART_HEIGHT)
            .pointerInput(points) {
                detectTapGestures { position ->
                    if (points.isNotEmpty() && size.width > 0) {
                        val index = if (points.size == 1) {
                            0
                        } else {
                            (position.x / size.width * (points.size - 1))
                                .roundToInt()
                                .coerceIn(points.indices)
                        }
                        onPointSelected(points[index])
                    }
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val horizontalPadding = 5.dp.toPx()
        val usableWidth = (size.width - horizontalPadding * 2f).coerceAtLeast(1f)
        val denominator = (points.size - 1).coerceAtLeast(1)
        val path = Path()
        var pathStarted = false
        points.forEachIndexed { index, point ->
            val x = horizontalPadding + usableWidth * index / denominator
            val value = point.value
            if (value == null) {
                pathStarted = false
                drawCircle(empty, radius = 2.dp.toPx(), center = Offset(x, size.height))
            } else {
                val y = size.height - ((value / maxValue) * size.height).toFloat()
                if (!pathStarted) {
                    path.moveTo(x, y)
                    pathStarted = true
                } else {
                    path.lineTo(x, y)
                }
                drawCircle(primary, radius = 2.5.dp.toPx(), center = Offset(x, y))
            }
        }
        drawPath(
            path = path,
            color = primary,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun CalendarStatisticsChart(
    points: List<StatisticsPoint>,
    onPointSelected: (StatisticsPoint) -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val missing = MaterialTheme.colorScheme.outlineVariant
    val normalized = remember(points) { fillCalendarRange(points) }
    val maxValue = normalized.mapNotNull(StatisticsPoint::value).maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val weeks = normalized.firstOrNull()?.date?.let { first ->
        val last = normalized.last().date
        ceil((ChronoUnit.DAYS.between(first, last) + 1) / 7.0).toInt()
    } ?: 1
    val scroll = rememberScrollState()

    BoxWithConstraints(modifier) {
        val desiredWidth = (weeks * CALENDAR_CELL.value).dp
        val canvasWidth = if (desiredWidth > maxWidth) desiredWidth else maxWidth
        Canvas(
            Modifier
                .horizontalScroll(scroll)
                .width(canvasWidth)
                .height(CALENDAR_HEIGHT)
                .pointerInput(normalized) {
                    detectTapGestures { position ->
                        val cell = CALENDAR_CELL.toPx()
                        val column = floor(position.x / cell).toInt()
                        val row = floor(position.y / cell).toInt()
                        val index = column * 7 + row
                        normalized.getOrNull(index)?.let(onPointSelected)
                    }
                }
                .padding(vertical = 2.dp),
        ) {
            val cell = CALENDAR_CELL.toPx()
            val square = (cell - 3.dp.toPx()).coerceAtLeast(2.dp.toPx())
            normalized.forEachIndexed { index, point ->
                val column = index / 7
                val row = index % 7
                val color = when (val value = point.value) {
                    null -> missing
                    0.0 -> empty
                    else -> lerp(empty, primary, (value / maxValue).toFloat().coerceIn(0.18f, 1f))
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(column * cell, row * cell),
                    size = Size(square, square),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

private fun fillCalendarRange(points: List<StatisticsPoint>): List<StatisticsPoint> {
    if (points.isEmpty()) return emptyList()
    val byDate = points.associateBy(StatisticsPoint::date)
    var date = points.minOf(StatisticsPoint::date)
    while (date.dayOfWeek != DayOfWeek.MONDAY) date = date.minusDays(1)
    var end = points.maxOf(StatisticsPoint::date)
    while (end.dayOfWeek != DayOfWeek.SUNDAY) end = end.plusDays(1)
    return buildList {
        while (!date.isAfter(end)) {
            add(byDate[date] ?: StatisticsPoint(date, null))
            date = date.plusDays(1)
        }
    }
}

private val CHART_HEIGHT: Dp = 210.dp
private val CALENDAR_CELL: Dp = 18.dp
private val CALENDAR_HEIGHT: Dp = 126.dp
