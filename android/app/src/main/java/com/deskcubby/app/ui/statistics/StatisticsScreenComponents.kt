package com.deskcubby.app.ui.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.statistics.StatisticsOverview
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun StatisticsOverviewPanel(
    overview: StatisticsOverview,
    totalText: String,
    averageText: String,
    highestDayText: String? = null,
    lastSevenAverageText: String? = null,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatisticsSummaryItem(
                    label = tr("开始统计", "Tracking since"),
                    value = overview.trackingStartedOn?.toString() ?: tr("尚未开始", "Not started"),
                    modifier = Modifier.weight(1f),
                )
                StatisticsSummaryItem(
                    label = tr("已统计", "Recorded"),
                    value = tr(
                        "${overview.recordedDays} 天",
                        "${overview.recordedDays} days",
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            if (highestDayText != null && lastSevenAverageText != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatisticsSummaryItem(
                        label = tr("单日最高", "Highest day"),
                        value = highestDayText,
                        modifier = Modifier.weight(1f),
                    )
                    StatisticsSummaryItem(
                        label = tr("过去 7 天平均", "Last 7-day average"),
                        value = lastSevenAverageText,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatisticsSummaryItem(
                    label = tr("总计", "Total"),
                    value = totalText,
                    modifier = Modifier.weight(1f),
                )
                StatisticsSummaryItem(
                    label = tr("日均", "Daily average"),
                    value = averageText,
                    modifier = Modifier.weight(1f),
                )
            }
            if (overview.daysWithData != overview.recordedDays) {
                Text(
                    tr(
                        "${overview.recordedDays} 个日期中有 ${overview.daysWithData} 天含数据；“—”不会按 0 计入平均值。",
                        "${overview.daysWithData} of ${overview.recordedDays} dates contain data; “—” is not averaged as zero.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StatisticsMessagePanel(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun StatisticsSummaryItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

fun formatUsageDuration(milliseconds: Double): String {
    val totalMinutes = (milliseconds / 60_000.0).roundToLong().coerceAtLeast(0)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        "${hours}H ${minutes}M"
    } else {
        "${minutes}M"
    }
}

fun formatStepCount(steps: Double): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(steps.roundToLong())
