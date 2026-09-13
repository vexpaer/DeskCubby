@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.statistics.SleepNight
import com.deskcubby.app.data.statistics.SleepRecordSource
import com.deskcubby.app.data.statistics.StatisticsRange
import com.deskcubby.app.ui.theme.tr
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SleepStatisticsScreen(
    padding: PaddingValues,
    viewModel: SleepStatisticsViewModel,
    onRequestUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state.enabled) viewModel.refresh()
    }
    SleepStatisticsScreen(
        padding = padding,
        state = state,
        onRequestUsageAccess = onRequestUsageAccess,
        onEnable = { viewModel.setTrackingEnabled(true) },
        onDisable = { viewModel.setTrackingEnabled(false) },
        onRefresh = viewModel::refresh,
        onSelectDevice = viewModel::selectDevice,
        onRange = viewModel::setRange,
        onSaveManual = viewModel::saveManual,
        modifier = modifier,
    )
}

@Composable
private fun SleepStatisticsScreen(
    padding: PaddingValues,
    state: SleepStatisticsUiState,
    onRequestUsageAccess: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onRefresh: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onRange: (StatisticsRange) -> Unit,
    onSaveManual: (LocalDate, LocalTime, LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deviceMenu by remember { mutableStateOf(false) }
    var editingNight by remember { mutableStateOf<SleepNight?>(null) }
    val selectedDevice = state.devices.firstOrNull { it.deviceId == state.selectedDeviceId }
    val latest = selectedDevice?.nights?.maxByOrNull(SleepNight::wakeDate)

    Scaffold(
        modifier = modifier.padding(bottom = padding.calculateBottomPadding()),
        topBar = {
            TopAppBar(
                title = { Text(tr("睡眠", "Sleep")) },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.refreshing && state.enabled) {
                        Icon(Icons.Outlined.Refresh, contentDescription = tr("刷新", "Refresh"))
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box {
                    OutlinedButton(onClick = { deviceMenu = true }) {
                        Icon(Icons.Outlined.Bedtime, null)
                        Spacer(Modifier.size(8.dp))
                        Text(selectedDevice?.deviceName ?: tr("当前设备", "This device"))
                    }
                    DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                        state.devices.forEach { device ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(device.deviceName)
                                        Text(
                                            tr(
                                                "${device.nights.size} 晚记录",
                                                "${device.nights.size} recorded nights",
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectDevice(device.deviceId)
                                    deviceMenu = false
                                },
                            )
                        }
                    }
                }
            }

            if (!state.enabled) {
                item {
                    MessageCard(
                        title = tr("睡眠估算未开启", "Sleep estimation is off"),
                        body = tr(
                            "开启后，DeskCubby 会在这台设备上根据夜间手机使用间隔估算睡眠，并把结果长期保存在本机。",
                            "When enabled, DeskCubby estimates sleep from nighttime phone-use gaps on this device and keeps the results locally.",
                        ),
                        action = tr("开启", "Enable"),
                        onAction = onEnable,
                    )
                }
            } else if (!state.hasUsageAccess) {
                item {
                    MessageCard(
                        title = tr("需要“使用情况访问”权限", "Usage access is required"),
                        body = tr(
                            "睡眠估算和手机使用时间共用 Android 的使用情况权限；不需要定位、麦克风或穿戴设备。",
                            "Sleep estimation shares Android usage access with screen time; it does not need location, microphone, or wearable permissions.",
                        ),
                        action = tr("去授权", "Grant access"),
                        onAction = onRequestUsageAccess,
                    )
                }
            }

            item { SleepHero(latest) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        StatisticsRange.LAST_7_DAYS to tr("7天", "7d"),
                        StatisticsRange.LAST_30_DAYS to tr("30天", "30d"),
                        StatisticsRange.LAST_90_DAYS to tr("90天", "90d"),
                        StatisticsRange.ALL to tr("全部", "All"),
                    ).forEach { (range, label) ->
                        FilterChip(
                            selected = state.range == range,
                            onClick = { onRange(range) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard(
                        tr("平均睡眠", "Average"),
                        state.averageDurationMinutes?.let(::formatDuration) ?: "—",
                        Modifier.weight(1f),
                    )
                    MetricCard(
                        tr("平均入睡", "Bedtime"),
                        state.averageBedtimeMinutes?.let(::formatClockMinutes) ?: "—",
                        Modifier.weight(1f),
                    )
                    MetricCard(
                        tr("平均起床", "Wake"),
                        state.averageWakeMinutes?.let(::formatClockMinutes) ?: "—",
                        Modifier.weight(1f),
                    )
                }
            }

            item { SleepTrendCard(state.nights, state.range) }

            item {
                Text(
                    tr(
                        "睡眠记录 · ${state.recordedNights} 晚",
                        "Sleep history · ${state.recordedNights} nights",
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            items(state.nights.asReversed(), key = { it.wakeDate.toString() }) { night ->
                SleepNightRow(
                    night = night,
                    editable = state.selectedDeviceId == state.localDeviceId,
                    onEdit = { editingNight = night },
                )
            }

            if (state.enabled) {
                item {
                    TextButton(onClick = onDisable) {
                        Text(tr("停止在此设备估算睡眠", "Stop estimating sleep on this device"))
                    }
                }
            }
        }
    }

    editingNight?.let { night ->
        EditSleepDialog(
            night = night,
            onDismiss = { editingNight = null },
            onSave = { bedtime, wake ->
                onSaveManual(night.wakeDate, bedtime, wake)
                editingNight = null
            },
        )
    }
}

@Composable
private fun SleepHero(night: SleepNight?) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                val color = MaterialTheme.colorScheme.primary
                val track = MaterialTheme.colorScheme.surfaceVariant
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(
                        color = track,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        style = Stroke(12.dp.toPx(), cap = StrokeCap.Round),
                    )
                    val fraction = ((night?.durationMinutes ?: 0) / 480f).coerceIn(0f, 1f)
                    drawArc(
                        color = color,
                        startAngle = 135f,
                        sweepAngle = 270f * fraction,
                        useCenter = false,
                        style = Stroke(12.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        night?.let { formatDuration(it.durationMinutes) } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(tr("最近一晚", "Latest night"), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (night != null) {
                Text(
                    "${formatEpochTime(night.bedtimeEpochMillis, night.zoneId)} → ${formatEpochTime(night.wakeEpochMillis, night.zoneId)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (night.source == SleepRecordSource.MANUAL) tr("手动修正", "Manual")
                    else tr("根据手机使用情况估算", "Estimated from phone usage"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    tr("还没有可用的睡眠估算", "No sleep estimate yet"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SleepTrendCard(nights: List<SleepNight>, range: StatisticsRange) {
    val values = remember(nights, range) {
        when (range) {
            StatisticsRange.LAST_7_DAYS,
            StatisticsRange.LAST_30_DAYS -> nights.map { it.durationMinutes.toFloat() }

            StatisticsRange.LAST_90_DAYS -> nights
                .groupBy { night ->
                    night.wakeDate.with(
                        java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY),
                    )
                }
                .toSortedMap()
                .values
                .map { week -> week.map(SleepNight::durationMinutes).average().toFloat() }

            StatisticsRange.ALL -> nights
                .groupBy { night -> java.time.YearMonth.from(night.wakeDate) }
                .toSortedMap()
                .values
                .map { month -> month.map(SleepNight::durationMinutes).average().toFloat() }
        }
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("睡眠时长趋势", "Sleep duration trend"), fontWeight = FontWeight.SemiBold)
                Text(
                    when (range) {
                        StatisticsRange.LAST_7_DAYS,
                        StatisticsRange.LAST_30_DAYS -> tr("按晚", "Nightly")
                        StatisticsRange.LAST_90_DAYS -> tr("按周平均", "Weekly avg")
                        StatisticsRange.ALL -> tr("按月平均", "Monthly avg")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (values.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val primary = MaterialTheme.colorScheme.primary
                val guide = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    val min = 4f * 60f
                    val max = 10f * 60f
                    drawLine(guide, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
                    if (values.size == 1) {
                        val normalized = ((values[0] - min) / (max - min)).coerceIn(0f, 1f)
                        drawCircle(primary, 5.dp.toPx(), Offset(size.width / 2, size.height * (1f - normalized)))
                    } else {
                        val points = values.mapIndexed { index, minutes ->
                            val x = size.width * index / (values.size - 1).toFloat()
                            val normalized = ((minutes - min) / (max - min)).coerceIn(0f, 1f)
                            Offset(x, size.height * (1f - normalized))
                        }
                        points.zipWithNext().forEach { (a, b) ->
                            drawLine(primary, a, b, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                        }
                        points.forEach { drawCircle(primary, 3.dp.toPx(), it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SleepNightRow(night: SleepNight, editable: Boolean, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (editable) Modifier.clickable(onClick = onEdit) else Modifier,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(night.wakeDate.toString(), fontWeight = FontWeight.SemiBold)
                Text(
                    "${formatEpochTime(night.bedtimeEpochMillis, night.zoneId)} → ${formatEpochTime(night.wakeEpochMillis, night.zoneId)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDuration(night.durationMinutes), fontWeight = FontWeight.SemiBold)
                Text(
                    if (night.source == SleepRecordSource.MANUAL) tr("手动", "Manual") else tr("估算", "Estimated"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (editable) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = tr("修改", "Edit"),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EditSleepDialog(
    night: SleepNight,
    onDismiss: () -> Unit,
    onSave: (LocalTime, LocalTime) -> Unit,
) {
    var bedtime by remember(night) {
        mutableStateOf(formatEpochTime(night.bedtimeEpochMillis, night.zoneId))
    }
    var wake by remember(night) {
        mutableStateOf(formatEpochTime(night.wakeEpochMillis, night.zoneId))
    }
    val bedtimeValue = runCatching { LocalTime.parse(bedtime) }.getOrNull()
    val wakeValue = runCatching { LocalTime.parse(wake) }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("修正睡眠", "Edit sleep")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(night.wakeDate.toString())
                OutlinedTextField(
                    value = bedtime,
                    onValueChange = { bedtime = it },
                    label = { Text(tr("入睡时间 HH:mm", "Bedtime HH:mm")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = wake,
                    onValueChange = { wake = it },
                    label = { Text(tr("起床时间 HH:mm", "Wake time HH:mm")) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = bedtimeValue != null && wakeValue != null,
                onClick = { onSave(checkNotNull(bedtimeValue), checkNotNull(wakeValue)) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

private fun formatDuration(minutes: Int): String = "${minutes / 60}h ${minutes % 60}m"

private fun formatClockMinutes(minutes: Int): String =
    "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

private fun formatEpochTime(epochMillis: Long, zoneId: String): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault()))
        .toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))
