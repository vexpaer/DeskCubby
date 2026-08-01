@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.steps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedPoint by remember { mutableStateOf<StatisticsPoint?>(null) }
    LaunchedEffect(state.range, state.chartType, state.metric) { selectedPoint = null }
    val permissionContract = remember { StepHealthConnectAccess.permissionContract() }
    val permissionLauncher = rememberLauncherForActivityResult(permissionContract) {
        viewModel.onPermissionResult()
    }
    val deviceSensorPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.onPermissionResult()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("健康", "Health")) },
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
                    onRequestDeviceSensorPermission = {
                        state.deviceStepCounterPermission?.let(
                            deviceSensorPermissionLauncher::launch,
                        )
                    },
                    onRetry = viewModel::refresh,
                )
            }
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
                        message = if (state.usingDeviceStepCounter) {
                            tr(
                                "本机计步传感器首次采样只建立基线，再次采样后才会显示实测差额。",
                                "The first on-device sensor sample only establishes a baseline. A measured difference appears after a later sample.",
                            )
                        } else {
                            tr(
                                "Health Connect 没有返回步数、距离或热量聚合值时会显示“—”，不会伪造为 0。",
                                "Missing Health Connect aggregates for steps, distance, or calories are shown as “—”, never fabricated as zero.",
                            )
                        },
                    )
                }
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
    onRequestDeviceSensorPermission: () -> Unit,
    onRetry: () -> Unit,
) {
    when {
        !state.enabled -> StatisticsMessagePanel(
            title = tr("统计已关闭", "Tracking is off"),
            message = tr(
                "开启后，步数、距离和活动热量只保存在应用私有的 step-statistics.json 中；关闭不会删除已有历史。",
                "When enabled, steps, distance, and active calories stay in the private step-statistics.json file. Turning it off keeps existing history.",
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
                        "请从 Google Play 安装或更新 Health Connect。若 Play 商店无法打开，会改用安全的 HTTPS 页面。",
                        "Install or update Health Connect from Google Play. If the Play Store cannot open, a secure HTTPS page is used.",
                    )
                    StepHealthConnectAction.OPEN_SYSTEM_UPDATE -> tr(
                        "Android 14 及以上由系统提供 Health Connect，请先安装可用的系统更新。",
                        "On Android 14 and newer, Health Connect is provided by the system. Install an available system update first.",
                    )
                    else -> tr(
                        "此设备既没有可用的 Health Connect，也没有系统计步传感器；不会写入伪造步数。",
                        "Neither Health Connect nor a system step counter is available. No steps are fabricated.",
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
                title = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_DEVICE_SENSOR_PERMISSION
                ) {
                    tr("需要活动识别权限", "Activity recognition required")
                } else {
                    tr("需要健康数据读取权限", "Health permissions required")
                },
                message = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_DEVICE_SENSOR_PERMISSION
                ) {
                    tr(
                        "此手机可直接提供系统累计步数。授权后会从首次采样开始统计；传感器无法恢复授权前的逐日历史。",
                        "This phone exposes a system step counter. Tracking starts with the first sample; the sensor cannot reconstruct daily history from before permission.",
                    )
                } else {
                    tr(
                        "请求读取步数、距离和活动热量；设备支持时也会请求后台读取，以便每 6 小时补充日结。拒绝后不会伪造数据。",
                        "Read access is requested for steps, distance, and active calories. Background reading is also requested when supported for six-hour catch-up. Denial never fabricates data.",
                    )
                },
                actionLabel = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_DEVICE_SENSOR_PERMISSION
                ) {
                    tr("授权系统计步", "Allow step counter")
                } else {
                    tr("授权读取健康数据", "Grant health access")
                },
                onAction = if (
                    state.collection.technicalDetail ==
                    StepStatisticsRepository.DETAIL_DEVICE_SENSOR_PERMISSION
                ) {
                    onRequestDeviceSensorPermission
                } else {
                    onRequestPermissions
                },
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
                        "系统没有可处理此操作的页面。请确认 Health Connect 或 Google Play 可用后重试。",
                        "No system page could handle this action. Check that Health Connect or Google Play is available, then retry.",
                    )
                } else {
                    tr(
                        "失败日期不会被标记为已日结，稍后可以安全重试。",
                        "The failed date was not finalized and can be retried safely.",
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
                    "正在读取 Health Connect 的步数、距离和热量，或读取本机计步传感器。",
                    "Reading steps, distance, and calories from Health Connect, or the on-device step counter.",
                ),
            )

        else -> StatisticsMessagePanel(
            title = if (state.usingDeviceStepCounter) {
                tr("本机计步传感器", "On-device step counter")
            } else {
                tr("Health Connect 数据源", "Health Connect source")
            },
            message = if (state.usingDeviceStepCounter) {
                tr(
                    "无需 Health Connect。应用按两次系统累计值的差额记录步数；传感器不提供距离和热量，首次采样建立基线，跨重启或跨日时不会猜测缺失值。",
                    "Health Connect is not required. Steps are recorded from differences between cumulative sensor samples; the sensor provides no distance or calories, and missing values across reboot or midnight are not guessed.",
                )
            } else {
                tr(
                    "今天的数据可刷新；过去日期成功日结后不会重复计算。健康数据和权限不会进入应用备份或云同步。",
                    "Today can refresh. Finalized past dates are never recalculated. Health data and permissions are excluded from backups and cloud sync.",
                )
            },
            actionLabel = if (
                !state.usingDeviceStepCounter &&
                state.healthConnectAction == StepHealthConnectAction.MANAGE_OR_PERMISSIONS
            ) {
                tr("管理 Health Connect", "Manage Health Connect")
            } else {
                null
            },
            onAction = if (
                !state.usingDeviceStepCounter &&
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
