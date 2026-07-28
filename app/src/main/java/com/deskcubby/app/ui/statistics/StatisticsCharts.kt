@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.deskcubby.app.ui.statistics

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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
    selectedPoint: StatisticsPoint?,
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
        StatisticsChartType.BARS -> BarStatisticsChart(
            points = points,
            selectedPoint = selectedPoint,
            valueDescription = valueDescription,
            onPointSelected = onPointSelected,
            modifier = chartModifier,
        )
        StatisticsChartType.LINE -> LineStatisticsChart(
            points = points,
            selectedPoint = selectedPoint,
            valueDescription = valueDescription,
            onPointSelected = onPointSelected,
            modifier = chartModifier,
        )
        StatisticsChartType.CALENDAR -> CalendarStatisticsChart(
            points = points,
            selectedPoint = selectedPoint,
            valueDescription = valueDescription,
            onPointSelected = onPointSelected,
            modifier = chartModifier,
        )
    }
}

@Composable
private fun BarStatisticsChart(
    points: List<StatisticsPoint>,
    selectedPoint: StatisticsPoint?,
    valueDescription: (Double?) -> String,
    onPointSelected: (StatisticsPoint) -> Unit,
    modifier: Modifier,
) {
    val highColor = MaterialTheme.colorScheme.primary
    val lowColor = MaterialTheme.colorScheme.tertiaryContainer
    val empty = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBackground = MaterialTheme.colorScheme.inverseSurface
    val tooltipForeground = MaterialTheme.colorScheme.inverseOnSurface
    val maxValue = points.mapNotNull(StatisticsPoint::value)
        .maxOrNull()
        ?.coerceAtLeast(1.0)
        ?: 1.0
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
        val chartTop = CHART_TOP_INSET.toPx()
        val chartBottom = size.height - CHART_BOTTOM_INSET.toPx()
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
        val gap = 3.dp.toPx()
        val slot = size.width / points.size
        val barWidth = (slot - gap).coerceAtLeast(1.dp.toPx())
        var selectedAnchor: Offset? = null
        points.forEachIndexed { index, point ->
            val value = point.value
            val left = index * slot + (slot - barWidth) / 2f
            val selected = point.date == selectedPoint?.date
            if (value == null) {
                drawRect(
                    color = empty,
                    topLeft = Offset(left, chartBottom - 2.dp.toPx()),
                    size = Size(barWidth, 2.dp.toPx()),
                )
                if (selected) selectedAnchor = Offset(left + barWidth / 2f, chartBottom)
            } else {
                val normalized = (value / maxValue).toFloat().coerceIn(0f, 1f)
                val height = (normalized * chartHeight)
                    .coerceAtLeast(if (value > 0.0) 2.dp.toPx() else 0f)
                val top = chartBottom - height
                drawRoundRect(
                    color = lerp(lowColor, highColor, normalized.coerceIn(0.12f, 1f)),
                    topLeft = Offset(left, top),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
                if (selected) {
                    drawRoundRect(
                        color = tooltipBackground,
                        topLeft = Offset(left - 1.dp.toPx(), top - 1.dp.toPx()),
                        size = Size(barWidth + 2.dp.toPx(), height + 2.dp.toPx()),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    selectedAnchor = Offset(left + barWidth / 2f, top)
                }
            }
        }
        drawAxisExtremes(
            maximum = valueDescription(maxValue),
            minimum = valueDescription(0.0),
            chartTop = chartTop,
            chartBottom = chartBottom,
            color = axisColor,
        )
        selectedPoint?.let { point ->
            selectedAnchor?.let { anchor ->
                drawTooltip(
                    text = "${point.date} · ${valueDescription(point.value)}",
                    anchor = anchor,
                    background = tooltipBackground,
                    foreground = tooltipForeground,
                )
            }
        }
    }
}

@Composable
private fun LineStatisticsChart(
    points: List<StatisticsPoint>,
    selectedPoint: StatisticsPoint?,
    valueDescription: (Double?) -> String,
    onPointSelected: (StatisticsPoint) -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBackground = MaterialTheme.colorScheme.inverseSurface
    val tooltipForeground = MaterialTheme.colorScheme.inverseOnSurface
    val maxValue = points.mapNotNull(StatisticsPoint::value)
        .maxOrNull()
        ?.coerceAtLeast(1.0)
        ?: 1.0
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
        val chartTop = CHART_TOP_INSET.toPx()
        val chartBottom = size.height - CHART_BOTTOM_INSET.toPx()
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
        val horizontalPadding = 5.dp.toPx()
        val usableWidth = (size.width - horizontalPadding * 2f).coerceAtLeast(1f)
        val denominator = (points.size - 1).coerceAtLeast(1)
        val path = Path()
        var pathStarted = false
        var selectedAnchor: Offset? = null
        points.forEachIndexed { index, point ->
            val x = horizontalPadding + usableWidth * index / denominator
            val value = point.value
            if (value == null) {
                pathStarted = false
                drawCircle(empty, radius = 2.dp.toPx(), center = Offset(x, chartBottom))
                if (point.date == selectedPoint?.date) {
                    selectedAnchor = Offset(x, chartBottom)
                }
            } else {
                val y = chartBottom -
                    ((value / maxValue) * chartHeight).toFloat().coerceIn(0f, chartHeight)
                if (!pathStarted) {
                    path.moveTo(x, y)
                    pathStarted = true
                } else {
                    path.lineTo(x, y)
                }
                drawCircle(
                    color = primary,
                    radius = if (point.date == selectedPoint?.date) 5.dp.toPx() else 2.5.dp.toPx(),
                    center = Offset(x, y),
                )
                if (point.date == selectedPoint?.date) selectedAnchor = Offset(x, y)
            }
        }
        drawPath(
            path = path,
            color = primary,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        drawAxisExtremes(
            maximum = valueDescription(maxValue),
            minimum = valueDescription(0.0),
            chartTop = chartTop,
            chartBottom = chartBottom,
            color = axisColor,
        )
        selectedPoint?.let { point ->
            selectedAnchor?.let { anchor ->
                drawTooltip(
                    text = "${point.date} · ${valueDescription(point.value)}",
                    anchor = anchor,
                    background = tooltipBackground,
                    foreground = tooltipForeground,
                )
            }
        }
    }
}

@Composable
private fun CalendarStatisticsChart(
    points: List<StatisticsPoint>,
    selectedPoint: StatisticsPoint?,
    valueDescription: (Double?) -> String,
    onPointSelected: (StatisticsPoint) -> Unit,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val missing = MaterialTheme.colorScheme.outlineVariant
    val tooltipBackground = MaterialTheme.colorScheme.inverseSurface
    val tooltipForeground = MaterialTheme.colorScheme.inverseOnSurface
    val normalized = remember(points) { fillCalendarRange(points) }
    val maxValue = normalized.mapNotNull(StatisticsPoint::value)
        .maxOrNull()
        ?.coerceAtLeast(1.0)
        ?: 1.0
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
                        val row = floor(
                            (position.y - CALENDAR_TOP_INSET.toPx()) / cell,
                        ).toInt()
                        if (row in 0..6) {
                            normalized.getOrNull(column * 7 + row)?.let(onPointSelected)
                        }
                    }
                },
        ) {
            val cell = CALENDAR_CELL.toPx()
            val square = (cell - 3.dp.toPx()).coerceAtLeast(2.dp.toPx())
            val topInset = CALENDAR_TOP_INSET.toPx()
            var selectedAnchor: Offset? = null
            normalized.forEachIndexed { index, point ->
                val column = index / 7
                val row = index % 7
                val color = when (val value = point.value) {
                    null -> missing
                    0.0 -> empty
                    else -> lerp(
                        empty,
                        primary,
                        (value / maxValue).toFloat().coerceIn(0.18f, 1f),
                    )
                }
                val topLeft = Offset(column * cell, topInset + row * cell)
                drawRoundRect(
                    color = color,
                    topLeft = topLeft,
                    size = Size(square, square),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                if (point.date == selectedPoint?.date) {
                    drawRoundRect(
                        color = tooltipBackground,
                        topLeft = topLeft - Offset(1.dp.toPx(), 1.dp.toPx()),
                        size = Size(square + 2.dp.toPx(), square + 2.dp.toPx()),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    selectedAnchor = Offset(topLeft.x + square / 2f, topLeft.y)
                }
            }
            selectedPoint?.let { point ->
                selectedAnchor?.let { anchor ->
                    drawTooltip(
                        text = "${point.date} · ${valueDescription(point.value)}",
                        anchor = anchor,
                        background = tooltipBackground,
                        foreground = tooltipForeground,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawAxisExtremes(
    maximum: String,
    minimum: String,
    chartTop: Float,
    chartBottom: Float,
    color: Color,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        textSize = 10.dp.toPx()
    }
    val padding = 3.dp.toPx()
    drawContext.canvas.nativeCanvas.drawText(
        maximum,
        padding,
        chartTop + paint.textSize,
        paint,
    )
    drawContext.canvas.nativeCanvas.drawText(
        minimum,
        padding,
        chartBottom - padding,
        paint,
    )
}

private fun DrawScope.drawTooltip(
    text: String,
    anchor: Offset,
    background: Color,
    foreground: Color,
) {
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foreground.toArgb()
        textSize = 11.dp.toPx()
    }
    val horizontalPadding = 8.dp.toPx()
    val verticalPadding = 5.dp.toPx()
    val bubbleWidth = textPaint.measureText(text) + horizontalPadding * 2f
    val bubbleHeight = textPaint.fontMetrics.run {
        bottom - top + verticalPadding * 2f
    }
    val left = (anchor.x - bubbleWidth / 2f)
        .coerceIn(0f, (size.width - bubbleWidth).coerceAtLeast(0f))
    val preferredTop = anchor.y - bubbleHeight - 6.dp.toPx()
    val top = preferredTop.coerceIn(0f, (size.height - bubbleHeight).coerceAtLeast(0f))
    val rect = RectF(left, top, left + bubbleWidth, top + bubbleHeight)
    val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = background.toArgb()
    }
    val radius = 7.dp.toPx()
    drawContext.canvas.nativeCanvas.drawRoundRect(rect, radius, radius, backgroundPaint)
    val baseline = top + verticalPadding - textPaint.fontMetrics.top
    drawContext.canvas.nativeCanvas.drawText(
        text,
        left + horizontalPadding,
        baseline,
        textPaint,
    )
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

private val CHART_HEIGHT: Dp = 232.dp
private val CHART_TOP_INSET: Dp = 18.dp
private val CHART_BOTTOM_INSET: Dp = 8.dp
private val CALENDAR_CELL: Dp = 18.dp
private val CALENDAR_TOP_INSET: Dp = 38.dp
private val CALENDAR_HEIGHT: Dp = 164.dp
