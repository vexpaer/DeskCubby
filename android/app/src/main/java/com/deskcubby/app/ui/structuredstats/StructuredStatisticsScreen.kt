@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.structuredstats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.statistics.StatisticsChartType
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.structuredrecords.StructuredFieldType
import com.deskcubby.app.data.structuredrecords.StructuredSeriesPoint
import com.deskcubby.app.ui.statistics.StatisticsChart
import com.deskcubby.app.ui.statistics.StatisticsMessagePanel
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun StructuredStatisticsScreen(
    padding: PaddingValues,
    viewModel: StructuredStatisticsViewModel,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showNewMetric by remember { mutableStateOf(false) }
    var pendingMetricDelete by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = Modifier.padding(top = padding.calculateTopPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("结构化记录统计", "Structured record statistics")) },
                navigationIcon = {
                    onBack?.let { goBack ->
                        IconButton(onClick = goBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showNewMetric = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(tr("添加统计", "Add metric"), modifier = Modifier.padding(start = 4.dp))
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> {
                Row(
                    Modifier.fillMaxSize().padding(innerPadding),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.padding(top = 48.dp))
                }
            }
            !state.available -> {
                Column(
                    Modifier.fillMaxSize().padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                ) {
                    StatisticsMessagePanel(
                        title = tr("还没有可用的日记目录", "No diary folder configured"),
                        message = state.message ?: tr("请先在设置中选择日记目录。", "Choose a diary folder in settings first."),
                    )
                }
            }
            !state.hasAny -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { StructuredStatisticsContent(state = state, onRangeChange = viewModel::setRange) }
                    item {
                        StatisticsMessagePanel(
                            title = tr("还没有结构化记录", "No structured records yet"),
                            message = tr(
                                "填写一条结构化记录后，字段与指标统计会显示在这里。",
                                "Field and metric statistics appear here after you record a structured entry.",
                            ),
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { StructuredStatisticsContent(state = state, onRangeChange = viewModel::setRange) }
                    items(state.fieldCards, key = { "field:${it.field.id}" }) { card ->
                        FieldStatCard(card = card)
                    }
                    items(state.metricCards, key = { "metric:${it.metric.id}" }) { card ->
                        MetricStatCard(card = card, onDelete = { pendingMetricDelete = card.metric.id })
                    }
                }
            }
        }
    }

    if (showNewMetric) {
        NewMetricDialog(
            fields = state.fields,
            onDismiss = { showNewMetric = false },
            onCreateSleepDuration = {
                viewModel.createSleepDurationMetric()
                showNewMetric = false
            },
            onCreateTimeDiff = { name, endFieldId, endOffset, startFieldId, startOffset ->
                viewModel.createTimeDiffMetric(name, endFieldId, endOffset, startFieldId, startOffset)
                showNewMetric = false
            },
        )
    }

    pendingMetricDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingMetricDelete = null },
            title = { Text(tr("删除指标？", "Delete metric?")) },
            text = { Text(tr("将删除这个自定义指标，历史字段统计不受影响。", "This deletes the custom metric; field statistics are unaffected.")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteMetric(id)
                    pendingMetricDelete = null
                }) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMetricDelete = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun StructuredStatisticsContent(
    state: StructuredStatisticsUiState,
    onRangeChange: (startIso: String, endIso: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("一处查看所有结构化字段与自定义指标。", "All structured fields and custom metrics in one place."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val today = LocalDate.now()
            RangeChip(tr("近 7 天", "7 days"), today.minusDays(6).toString(), today.toString(), state.startIso, onRangeChange)
            RangeChip(tr("近 30 天", "30 days"), today.minusDays(29).toString(), today.toString(), state.startIso, onRangeChange)
            RangeChip(tr("近 90 天", "90 days"), today.minusDays(89).toString(), today.toString(), state.startIso, onRangeChange)
        }
    }
}

@Composable
private fun RangeChip(
    label: String,
    start: String,
    end: String,
    currentStart: String,
    onSelect: (String, String) -> Unit,
) {
    FilterChip(
        selected = currentStart == start,
        onClick = { onSelect(start, end) },
        label = { Text(label) },
    )
}

@Composable
private fun FieldStatCard(card: StructuredFieldCard) {
    val field = card.field
    val stats = card.stats
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ShowChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    field.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("${stats.count} 条", "×${stats.count}"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (field.type) {
                StructuredFieldType.NUMBER -> {
                    StatRow(
                        listOfNotNull(
                            stats.latest?.let { it to tr("最新", "Latest") },
                            stats.average?.let { it to tr("平均", "Average") },
                            stats.total?.let { it to tr("合计", "Total") },
                        ),
                    )
                }
                StructuredFieldType.TIME -> {
                    StatRow(
                        listOfNotNull(
                            stats.latest?.let { it to tr("最新", "Latest") },
                            stats.earliest?.let { it to tr("最早", "Earliest") },
                            stats.average?.let { it to tr("平均", "Average") },
                        ),
                    )
                }
                StructuredFieldType.DURATION -> {
                    StatRow(
                        listOfNotNull(
                            stats.latest?.let { it to tr("最新", "Latest") },
                            stats.average?.let { it to tr("平均", "Average") },
                            stats.total?.let { it to tr("合计", "Total") },
                        ),
                    )
                }
                StructuredFieldType.TYPE -> Unit
                StructuredFieldType.WORD -> Unit
            }
            when (field.type) {
                StructuredFieldType.TYPE -> CategoryBars(card.stats.categoryCounts.map { it.category to it.count })
                StructuredFieldType.WORD -> WordTimeline(card.stats.series.takeLast(6))
                else -> SeriesChart(card.stats.series)
            }
        }
    }
}

@Composable
private fun StatRow(items: List<Pair<String, String>>) {
    if (items.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items.forEach { (value, label) ->
            if (value.isNotBlank()) {
                Column {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SeriesChart(series: List<StructuredSeriesPoint>) {
    if (series.isEmpty()) return
    var selected by remember { mutableStateOf<StatisticsPoint?>(null) }
    val points = series.map { StatisticsPoint(it.journalDay, it.chartValue) }
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        padding = PaddingValues(10.dp),
    ) {
        StatisticsChart(
            points = points,
            chartType = StatisticsChartType.LINE,
            valueDescription = { value ->
                val index = points.indexOfFirst { it.value == value }.takeIf { it >= 0 }
                index?.let { series.getOrNull(it)?.display ?: "—" } ?: "—"
            },
            selectedPoint = selected,
            onPointSelected = { it.let { p -> selected = p } },
            modifier = Modifier.height(150.dp),
        )
    }
}

@Composable
private fun CategoryBars(counts: List<Pair<String, Int>>) {
    if (counts.isEmpty()) return
    val max = counts.maxOf { it.second }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        counts.take(8).forEach { (category, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    category,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Box(
                    Modifier
                        .size(width = (120 + 120 * count.toFloat() / max).dp.coerceAtMost(240.dp), height = 10.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp)),
                )
            }
        }
    }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("MM-dd")

@Composable
private fun WordTimeline(series: List<StructuredSeriesPoint>) {
    if (series.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        series.forEach { point ->
            Text(
                "${point.journalDay.format(DAY_FORMAT)} · ${point.display ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetricStatCard(card: StructuredMetricCard, onDelete: () -> Unit) {
    val empty = card.points.all { it.display == null }
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.BarChart,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    card.metric.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, tr("删除 ${card.metric.name}", "Delete ${card.metric.name}"))
                }
            }
            val latest = card.points.lastOrNull { it.display != null }
            if (latest != null) {
                Text(
                    tr("最新：${latest.journalDay} · ${latest.display}", "Latest: ${latest.journalDay} · ${latest.display}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (empty) {
                Text(
                    tr("所选范围内暂无数据（缺失值不会当作 0）。", "No data in range (missing values are never treated as zero)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SeriesChart(card.points)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NewMetricDialog(
    fields: List<com.deskcubby.app.data.structuredrecords.StructuredField>,
    onDismiss: () -> Unit,
    onCreateSleepDuration: () -> Unit,
    onCreateTimeDiff: (name: String, endFieldId: String, endOffset: Int, startFieldId: String, startOffset: Int) -> Unit,
) {
    val timeFields = fields.filter { it.type == StructuredFieldType.TIME }
    var name by remember { mutableStateOf("") }
    var endFieldId by remember { mutableStateOf(timeFields.firstOrNull()?.id.orEmpty()) }
    var startFieldId by remember { mutableStateOf(timeFields.firstOrNull()?.id.orEmpty()) }
    var endOffset by remember { mutableIntStateOf(0) }
    var startOffset by remember { mutableIntStateOf(-1) }
    var endExpanded by remember { mutableStateOf(false) }
    var startExpanded by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("添加自定义指标", "Add a custom metric")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("指标名称", "Metric name")) },
                    placeholder = { Text(tr("例如：睡眠时长", "e.g. Sleep duration")) },
                    singleLine = true,
                )
                Text(tr("时间差（结束值 − 开始值）", "Time difference (end − start)"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(tr("结束值", "End value"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MetricFieldOffsetRow(
                    fields = timeFields,
                    selectedFieldId = endFieldId,
                    offset = endOffset,
                    expanded = endExpanded,
                    onFieldSelect = { endFieldId = it },
                    onOffsetChange = { endOffset = it },
                    onExpandedChange = { endExpanded = it },
                )
                Text(tr("开始值", "Start value"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MetricFieldOffsetRow(
                    fields = timeFields,
                    selectedFieldId = startFieldId,
                    offset = startOffset,
                    expanded = startExpanded,
                    onFieldSelect = { startFieldId = it },
                    onOffsetChange = { startOffset = it },
                    onExpandedChange = { startExpanded = it },
                )
                Text(
                    tr("结果类型为时长。缺失任一端值时该日结果为 null，不会当作 0。", "Result type is duration. Missing either end yields null for that day, never 0."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (timeFields.any { it.id == com.deskcubby.app.data.structuredrecords.SYSTEM_FIELD_WAKE_TIME } &&
                    timeFields.any { it.id == com.deskcubby.app.data.structuredrecords.SYSTEM_FIELD_SLEEP_TIME }
                ) {
                    TextButton(onClick = onCreateSleepDuration, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("一键添加“睡眠时长”（起床 − 前一天睡觉）", "Add “sleep duration” (wake − previous sleep) in one tap"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && endFieldId.isNotEmpty() && startFieldId.isNotEmpty(),
                onClick = { onCreateTimeDiff(name.trim(), endFieldId, endOffset, startFieldId, startOffset) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun MetricFieldOffsetRow(
    fields: List<com.deskcubby.app.data.structuredrecords.StructuredField>,
    selectedFieldId: String,
    offset: Int,
    expanded: Boolean,
    onFieldSelect: (String) -> Unit,
    onOffsetChange: (Int) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
        ) {
            val selected = fields.firstOrNull { it.id == selectedFieldId }
            OutlinedTextField(
                value = selected?.name ?: tr("选择时间字段", "Select time field"),
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.weight(1f).menuAnchor(),
                singleLine = true,
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
                fields.forEach { field ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(field.name) },
                        onClick = {
                            onFieldSelect(field.id)
                            onExpandedChange(false)
                        },
                    )
                }
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = when (offset) {
                0 -> tr("当天", "Today")
                1 -> tr("后一天", "Next day")
                -1 -> tr("前一天", "Previous day")
                else -> if (offset > 0) "D+$offset" else "D$offset"
            },
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.width(96.dp),
            singleLine = true,
        )
        androidx.compose.material3.IconButton(onClick = { onOffsetChange((offset + 1).coerceIn(-3, 3)) }) {
            Text("+1")
        }
    }
}
