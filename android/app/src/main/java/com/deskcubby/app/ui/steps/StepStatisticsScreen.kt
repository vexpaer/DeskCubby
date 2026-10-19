@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.steps

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.statistics.StatisticsCollectionPhase
import com.deskcubby.app.data.statistics.StatisticsDayState
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.statistics.HealthMetric
import com.deskcubby.app.data.statistics.StepHealthConnectAction
import com.deskcubby.app.data.statistics.StepHealthConnectAccess
import com.deskcubby.app.data.statistics.StepStatisticsRepository
import com.deskcubby.app.data.statistics.value
import com.deskcubby.app.ui.statistics.StatisticsChart
import com.deskcubby.app.ui.statistics.StatisticsChartControls
import com.deskcubby.app.ui.statistics.StatisticsMessagePanel
import com.deskcubby.app.ui.statistics.StatisticsOverviewPanel
import com.deskcubby.app.ui.statistics.formatStepCount
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import java.util.Locale

@Composable
fun StepStatisticsScreen(
    padding: PaddingValues,
    viewModel: StepStatisticsViewModel,
    onOpenTrackingSettings: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedPoint by remember { mutableStateOf<StatisticsPoint?>(null) }
    LaunchedEffect(state.range, state.chartType, state.metric) { selectedPoint = null }
    val permissionContract = remember { StepHealthConnectAccess.permissionContract() }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) {
        viewModel.onPermissionResult()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    onBack?.let { BackHandler(onBack = it) }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(
            if (onBack == null) WindowInsetsSides.Horizontal
            else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
        topBar = {
            TopAppBar(
                title = { Text(tr("健康", "Health")) },
                navigationIcon = {
                    onBack?.let { navigateBack ->
                        IconButton(onClick = navigateBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = tr("返回统计", "Back to statistics"),
                            )
                        }
                    }
                },
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
            if (state.history.days.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HealthMetric.entries.forEach { metric ->
                            FilterChip(
                                selected = state.metric == metric,
                                onClick = { viewModel.selectMetric(metric) },
                                label = {
                                    Text(
                                        when (metric) {
                                            HealthMetric.STEPS -> tr("步数", "Steps")
                                            HealthMetric.DISTANCE -> tr("距离", "Distance")
                                            HealthMetric.ACTIVE_CALORIES -> tr("热量", "Calories")
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
                item {
                    StatisticsOverviewPanel(
                        overview = state.overview,
                        totalText = formatHealthValue(state.metric, state.overview.total),
                        averageText = formatHealthValue(
                            state.metric,
                            state.overview.averagePerDataDay,
                        ),
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
                        StatisticsChart(
                            points = state.points,
                            chartType = state.chartType,
                            valueDescription = { value ->
                                value?.let { formatHealthValue(state.metric, it) } ?: "—"
                            },
                            selectedPoint = selectedPoint,
                            onPointSelected = { selectedPoint = it },
                        )
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
                    HealthDayRow(
                        date = day.date.toString(),
                        value = day.value(state.metric)?.let {
                            formatHealthValue(state.metric, it)
                        } ?: "—",
                        open = day.state == StatisticsDayState.OPEN,
                    )
                }
            } else if (
                state.enabled &&
                state.collection.phase == StatisticsCollectionPhase.READY
            ) {
                item {
                    StatisticsMessagePanel(
                        title = tr("还没有健康记录", "No health records yet"),
                        message = tr(
                            "没有数据时显示“—”，不会记为 0。",
                            "Missing data shows as “—” and is never recorded as 0.",
                        ),
                    )
                }
            }
            item {
                StepCollectionMessage(
                    state = state,
                    onOpenTrackingSettings = onOpenTrackingSettings,
                    onOpenHealthConnect = onOpenHealthConnect,
                    onRequestPermissions = {
                        permissionLauncher.launch(
                            state.permissionsToRequest.ifEmpty {
                                StepHealthConnectAccess.healthReadPermissions
                            },
                        )
                    },
                    onRetry = viewModel::refresh,
                )
            }
        }
    }

}

@Composable
private fun StepCollectionMessage(
    state: StepStatisticsUiState,
    onOpenTrackingSettings: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        !state.enabled -> StatisticsMessagePanel(
            title = tr("统计已关闭", "Tracking is off"),
            message = tr(
                "开启后数据仅保存在本机；关闭不删除历史。",
                "Data stays on this device; turning it off keeps history.",
            ),
            actionLabel = tr("打开健康设置", "Open health settings"),
            onAction = onOpenTrackingSettings,
        )

        state.collection.phase == StatisticsCollectionPhase.UNAVAILABLE ->
            StatisticsMessagePanel(
                title = when (state.healthConnectAction) {
                    StepHealthConnectAction.UPDATE_PROVIDER_IN_PLAY_STORE ->
                        tr("需要安装或更新 Health Connect", "Install or update Health Connect")
                    StepHealthConnectAction.OPEN_SYSTEM_UPDATE ->
                        tr("需要系统更新", "System update required")
                    else ->
                        tr(
                            "没有可用的健康数据源",
                            "No health source is available",
                        )
                },
                message = when (state.healthConnectAction) {
                    StepHealthConnectAction.UPDATE_PROVIDER_IN_PLAY_STORE -> tr(
                        "请从 Google Play 安装或更新 Health Connect。",
                        "Install or update Health Connect from Google Play.",
                    )
                    StepHealthConnectAction.OPEN_SYSTEM_UPDATE -> tr(
                        "Android 14 及以上由系统提供 Health Connect，请先安装可用的系统更新。",
                        "On Android 14 and newer, Health Connect is provided by the system. Install an available system update first.",
                    )
                    else -> tr(
                        "此设备没有可用的 Health Connect。",
                        "Health Connect is not available on this device.",
                    )
                },
                actionLabel = when (state.healthConnectAction) {
                    StepHealthConnectAction.UPDATE_PROVIDER_IN_PLAY_STORE ->
                        tr("前往 Google Play", "Open Google Play")
                    StepHealthConnectAction.OPEN_SYSTEM_UPDATE ->
                        tr("打开系统更新", "Open system update")
                    else -> null
                },
                onAction = if (
                    state.healthConnectAction == StepHealthConnectAction.UNSUPPORTED
                ) {
                    null
                } else {
                    onOpenHealthConnect
                },
            )

        state.collection.phase == StatisticsCollectionPhase.PERMISSION_REQUIRED ->
            StatisticsMessagePanel(
                title = tr("需要健康数据读取权限", "Health permissions required"),
                message = tr(
                    "需要读取步数、距离和活动热量；设备支持时还会请求后台读取。",
                    "Grants read access to steps, distance, and active calories, plus background reads where supported.",
                ),
                actionLabel = tr("授权读取健康数据", "Grant health access"),
                onAction = onRequestPermissions,
            )

        state.collection.phase == StatisticsCollectionPhase.ERROR ->
            StatisticsMessagePanel(
                title = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_OPEN_HEALTH_CONNECT_FAILED
                ) {
                    tr("无法打开 Health Connect", "Could not open Health Connect")
                } else {
                    tr("本次读取失败", "This refresh failed")
                },
                message = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_OPEN_HEALTH_CONNECT_FAILED
                ) {
                    tr(
                        "无法打开系统页面，请确认 Health Connect 或 Google Play 可用。",
                        "Couldn't open the system page; check that Health Connect or Google Play is available.",
                    )
                } else {
                    tr(
                        "失败的日期稍后可重试。",
                        "Failed days can be retried later.",
                    )
                },
                actionLabel = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_OPEN_HEALTH_CONNECT_FAILED &&
                    state.healthConnectAction != StepHealthConnectAction.UNSUPPORTED
                ) {
                    tr("重试打开", "Try opening again")
                } else {
                    tr("重试", "Retry")
                },
                onAction = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_OPEN_HEALTH_CONNECT_FAILED &&
                    state.healthConnectAction != StepHealthConnectAction.UNSUPPORTED
                ) {
                    onOpenHealthConnect
                } else {
                    onRetry
                },
            )

        state.collection.phase == StatisticsCollectionPhase.REFRESHING ->
            StatisticsMessagePanel(
                title = tr("正在刷新", "Refreshing"),
                message = tr(
                    "正在从 Health Connect 读取步数、距离和活动热量。",
                    "Reading steps, distance, and active calories from Health Connect.",
                ),
            )

        else -> StatisticsMessagePanel(
            title = tr("Health Connect 数据源", "Health Connect source"),
            message = tr(
                "打开或刷新时会核对最近 30 天的数据；健康数据不进入备份或云同步。",
                "Opening or refreshing rechecks the last 30 days; health data is never backed up or synced.",
            ),
            actionLabel = if (
                state.healthConnectAction == StepHealthConnectAction.MANAGE_OR_PERMISSIONS
            ) {
                tr("管理 Health Connect", "Manage Health Connect")
            } else {
                null
            },
            onAction = if (
                state.healthConnectAction == StepHealthConnectAction.MANAGE_OR_PERMISSIONS
            ) {
                onOpenHealthConnect
            } else {
                null
            },
        )
    }
}

@Composable
private fun HealthDayRow(
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

private fun formatHealthValue(metric: HealthMetric, value: Double): String = when (metric) {
    HealthMetric.STEPS -> formatStepCount(value)
    HealthMetric.DISTANCE -> if (value >= 1_000.0) {
        String.format(Locale.getDefault(), "%.2f km", value / 1_000.0)
    } else {
        String.format(Locale.getDefault(), "%.0f m", value)
    }
    HealthMetric.ACTIVE_CALORIES ->
        String.format(Locale.getDefault(), "%.0f kcal", value)
}
