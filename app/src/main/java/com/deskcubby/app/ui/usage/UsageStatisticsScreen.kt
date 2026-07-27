@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.usage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.statistics.StatisticsCollectionPhase
import com.deskcubby.app.data.statistics.StatisticsDayState
import com.deskcubby.app.ui.statistics.StatisticsChart
import com.deskcubby.app.ui.statistics.StatisticsChartControls
import com.deskcubby.app.ui.statistics.StatisticsMessagePanel
import com.deskcubby.app.ui.statistics.StatisticsOverviewPanel
import com.deskcubby.app.ui.statistics.formatUsageDuration
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr

@Composable
fun UsageStatisticsScreen(
    padding: PaddingValues,
    viewModel: UsageStatisticsViewModel,
    onRequestUsageAccess: () -> Unit,
    onOpenTrackingSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("手机使用时间", "Screen time")) },
                actions = {
                    IconButton(
                        enabled = state.enabled &&
                            state.collection.phase != StatisticsCollectionPhase.REFRESHING,
                        onClick = viewModel::refresh,
                    ) {
                        Icon(Icons.Outlined.Refresh, tr("刷新", "Refresh"))
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                UsageCollectionMessage(
                    state = state,
                    onRequestUsageAccess = onRequestUsageAccess,
                    onOpenTrackingSettings = onOpenTrackingSettings,
                    onRetry = viewModel::refresh,
                )
            }
            if (state.history.days.isNotEmpty()) {
                item {
                    StatisticsOverviewPanel(
                        overview = state.overview,
                        totalText = formatUsageDuration(state.overview.total),
                        averageText = formatUsageDuration(state.overview.averagePerDataDay),
                    )
                }
                item {
                    UsageAppSelector(
                        selectedPackage = state.selectedPackage,
                        choices = state.appChoices,
                        onSelect = viewModel::selectPackage,
                    )
                }
                item {
                    StatisticsChartControls(
                        range = state.range,
                        chartType = state.chartType,
                        onRangeChange = viewModel::selectRange,
                        onChartTypeChange = viewModel::selectChartType,
                    )
                }
                item {
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 22.dp,
                        padding = PaddingValues(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                tr("按本地自然日", "By local civil day"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            StatisticsChart(
                                points = state.points,
                                chartType = state.chartType,
                                valueDescription = { value ->
                                    value?.let { "${(it / 60_000.0).toLong()} min" } ?: "—"
                                },
                            )
                        }
                    }
                }
                item {
                    Text(
                        tr("每日明细", "Daily details"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(
                    items = state.history.days.sortedByDescending { it.date },
                    key = { it.date.toString() },
                ) { day ->
                    val value = if (state.selectedPackage == null) {
                        day.totalForegroundMillis.toDouble()
                    } else {
                        day.apps.firstOrNull { it.packageName == state.selectedPackage }
                            ?.foregroundMillis
                            ?.toDouble()
                            ?: 0.0
                    }
                    StatisticsDayRow(
                        date = day.date.toString(),
                        value = formatUsageDuration(value),
                        open = day.state == StatisticsDayState.OPEN,
                    )
                }
            } else if (
                state.enabled &&
                state.collection.phase == StatisticsCollectionPhase.READY
            ) {
                item {
                    StatisticsMessagePanel(
                        title = tr("还没有使用记录", "No usage records yet"),
                        message = tr(
                            "今天的数据会保持实时状态，过去日期完整读取成功后才会日结。",
                            "Today stays live; a past day is finalized only after a complete successful read.",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageCollectionMessage(
    state: UsageStatisticsUiState,
    onRequestUsageAccess: () -> Unit,
    onOpenTrackingSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        !state.enabled -> StatisticsMessagePanel(
            title = tr("统计已关闭", "Tracking is off"),
            message = tr(
                "开启后，统计只保存在应用私有的 usage-statistics.json 中；关闭不会删除已有历史。",
                "When enabled, data stays in the private usage-statistics.json file. Turning it off keeps existing history.",
            ),
            actionLabel = tr("打开使用时间设置", "Open tracking settings"),
            onAction = onOpenTrackingSettings,
        )

        state.collection.phase == StatisticsCollectionPhase.PERMISSION_REQUIRED ->
            StatisticsMessagePanel(
                title = tr("需要“使用情况访问权限”", "Usage access required"),
                message = tr(
                    "Android 不会弹出普通权限框。请在系统“使用情况访问权限”中允许 DeskCubby，然后返回此页。",
                    "Android uses a special settings page for Usage Access. Allow DeskCubby there, then return.",
                ),
                actionLabel = tr("打开系统设置", "Open system settings"),
                onAction = onRequestUsageAccess,
            )

        state.collection.phase == StatisticsCollectionPhase.ERROR ->
            StatisticsMessagePanel(
                title = tr("本次读取失败", "This refresh failed"),
                message = tr(
                    "失败日期不会被标记为已日结，稍后可以安全重试。",
                    "The failed date was not finalized and can be retried safely.",
                ),
                actionLabel = tr("重试", "Retry"),
                onAction = onRetry,
            )

        state.collection.phase == StatisticsCollectionPhase.REFRESHING ->
            StatisticsMessagePanel(
                title = tr("正在刷新", "Refreshing"),
                message = tr(
                    "正在按本地自然日整理前台使用事件。",
                    "Foreground events are being grouped by local civil day.",
                ),
            )

        else -> StatisticsMessagePanel(
            title = tr("本机私有统计", "Private on-device statistics"),
            message = tr(
                "今天的数据可刷新；过去日期成功日结后不会重复计算。统计与权限不会进入应用备份或云同步。",
                "Today can refresh. Finalized past dates are never recalculated. Statistics and permissions are excluded from backups and cloud sync.",
            ),
        )
    }
}

@Composable
private fun UsageAppSelector(
    selectedPackage: String?,
    choices: List<UsageAppChoice>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.packageName == selectedPackage }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("统计对象", "Statistic"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    selected?.label ?: tr("全部应用总时长", "All apps total"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                selected?.let {
                    if (it.label != it.packageName) {
                        Text(
                            it.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Icon(Icons.Outlined.ArrowDropDown, null)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(tr("全部应用总时长", "All apps total")) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    },
                )
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (choice.label != choice.packageName) {
                                    Text(
                                        choice.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onSelect(choice.packageName)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatisticsDayRow(
    date: String,
    value: String,
    open: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(date, fontWeight = FontWeight.Medium)
                if (open) {
                    Text(
                        tr("实时，可刷新", "Live, refreshable"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}
