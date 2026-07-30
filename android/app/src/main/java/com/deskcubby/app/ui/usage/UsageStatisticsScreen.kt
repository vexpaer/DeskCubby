@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.usage

import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.Build
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.statistics.StatisticsCollectionPhase
import com.deskcubby.app.data.statistics.StatisticsDayState
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.statistics.USAGE_STATISTICS_EXPORT_FILE_NAME
import com.deskcubby.app.ui.statistics.StatisticsChart
import com.deskcubby.app.ui.statistics.StatisticsChartControls
import com.deskcubby.app.ui.statistics.StatisticsMessagePanel
import com.deskcubby.app.ui.statistics.StatisticsOverviewPanel
import com.deskcubby.app.ui.statistics.formatUsageDuration
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun UsageStatisticsScreen(
    padding: PaddingValues,
    viewModel: UsageStatisticsViewModel,
    onRequestUsageAccess: () -> Unit,
    onOpenTrackingSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    var selectedPoint by remember { mutableStateOf<StatisticsPoint?>(null) }
    var showExportWarning by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(viewModel::exportHistory)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(state.range, state.chartType, state.selectedPackage) {
        selectedPoint = null
    }
    LaunchedEffect(exportState) {
        when (exportState) {
            UsageStatisticsExportState.SUCCEEDED -> {
                try {
                    snackbarHostState.showSnackbar(
                        tr(
                            "使用时间已导出并通过回读校验",
                            "Screen time was exported and verified",
                        ),
                    )
                } finally {
                    viewModel.consumeExportResult()
                }
            }

            UsageStatisticsExportState.FAILED -> {
                try {
                    snackbarHostState.showSnackbar(
                        tr(
                            "导出失败，目标文件未通过完整校验",
                            "Export failed because the target did not pass full verification",
                        ),
                    )
                } finally {
                    viewModel.consumeExportResult()
                }
            }

            UsageStatisticsExportState.IDLE,
            UsageStatisticsExportState.EXPORTING,
            -> Unit
        }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(tr("手机使用时间", "Screen time")) },
                actions = {
                    IconButton(
                        enabled = !state.initializing &&
                            exportState != UsageStatisticsExportState.EXPORTING,
                        onClick = { showExportWarning = true },
                    ) {
                        Icon(Icons.Outlined.Download, tr("导出给 Windows", "Export for Windows"))
                    }
                    IconButton(
                        enabled = !state.initializing &&
                            state.enabled &&
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
            if (state.initializing) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        FilledTonalButton(
                            enabled = false,
                            onClick = {},
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                            Text(tr("加载中", "Loading"))
                        }
                    }
                }
            } else if (!state.enabled || state.collection.phase != StatisticsCollectionPhase.READY) {
                item {
                    UsageCollectionMessage(
                        state = state,
                        onRequestUsageAccess = onRequestUsageAccess,
                        onOpenTrackingSettings = onOpenTrackingSettings,
                        onRetry = viewModel::refresh,
                    )
                }
            }
            item {
                UsageExportPanel(
                    exporting = exportState == UsageStatisticsExportState.EXPORTING,
                    onExport = { showExportWarning = true },
                )
            }
            if (state.history.days.isNotEmpty()) {
                item {
                    StatisticsOverviewPanel(
                        overview = state.overview,
                        totalText = formatUsageDuration(state.overview.total),
                        averageText = formatUsageDuration(state.overview.averagePerDataDay),
                        highestDayText = formatUsageDuration(
                            state.highestDayForegroundMillis,
                        ),
                        lastSevenAverageText = formatUsageDuration(
                            state.lastSevenDayAverageForegroundMillis,
                        ),
                    )
                }
                item {
                    UsageAppSelector(
                        selectedPackage = state.selectedPackage,
                        choices = state.appChoices,
                        allAppsMillis = state.rangeTotalForegroundMillis.toDouble(),
                        onSelect = viewModel::selectPackage,
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
                                value?.let { formatUsageDuration(it) } ?: "—"
                            },
                            selectedPoint = selectedPoint,
                            onPointSelected = { selectedPoint = it },
                        )
                    }
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
                    Text(
                        tr("每日明细", "Daily details"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(
                    items = state.points.sortedByDescending { it.date },
                    key = { it.date.toString() },
                ) { point ->
                    StatisticsDayRow(
                        date = point.date.toString(),
                        value = formatUsageDuration(point.value ?: 0.0),
                        open = state.history.days.firstOrNull { it.date == point.date }?.state ==
                            StatisticsDayState.OPEN,
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

    if (showExportWarning) {
        AlertDialog(
            onDismissRequest = { showExportWarning = false },
            title = { Text(tr("导出手机使用时间？", "Export phone screen time?")) },
            text = {
                Text(
                    tr(
                        "导出的 JSON 包含应用包名、每天的前台时长、日期和时区，可能反映你的使用习惯。导出前会重新读取应用私有统计文件，但不会开启统计或申请使用情况权限；应用也不会自动把该文件加入 v18 备份或云同步。保存位置由你选择，请避免公开或会自动同步的目录。",
                        "The JSON contains app package names, daily foreground durations, dates, and time zones, which may reveal usage habits. The private statistics file is re-read before export, but tracking is not enabled and Usage Access is not requested. The app does not automatically add this file to v18 backups or cloud sync. Choose a private location and avoid public or automatically synced folders.",
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportWarning = false
                        exportLauncher.launch(USAGE_STATISTICS_EXPORT_FILE_NAME)
                    },
                ) {
                    Text(tr("继续选择位置", "Choose a location"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportWarning = false }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun UsageExportPanel(
    exporting: Boolean,
    onExport: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("导出给 Windows", "Export for Windows"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                tr(
                    "手动导出规范 v4 JSON。导出前会重新读取已有私有历史，不查询系统数据源或开启统计；应用不会自动把文件加入 v18 备份或云同步。请保存到可信的私有位置。",
                    "Manually exports canonical v4 JSON after re-reading the existing private history. It does not query the system source or enable tracking, and the app does not automatically add the file to v18 backups or cloud sync. Save it in a trusted private location.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Text(
                    if (exporting) tr("正在写入并校验", "Writing and verifying")
                    else tr("选择位置并导出", "Choose location and export"),
                )
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
                    "正在读取系统按天汇总的使用记录。",
                    "Reading Android's daily usage summaries.",
                ),
            )

        else -> Unit
    }
}

@Composable
private fun UsageAppSelector(
    selectedPackage: String?,
    choices: List<UsageAppChoice>,
    allAppsMillis: Double,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.packageName == selectedPackage }
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true },
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UsageAppIcon(
                packageName = selected?.packageName,
                modifier = Modifier.size(42.dp),
            )
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
                Text(
                    formatUsageDuration(selected?.foregroundMillis?.toDouble() ?: allAppsMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
            Icon(Icons.Outlined.ArrowDropDown, null)
        }
    }
    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                Text(
                    tr("选择统计应用", "Choose an app"),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    tr(
                        "按当前时间范围内的使用时长排序",
                        "Sorted by usage in the selected time range",
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .padding(top = 8.dp),
                ) {
                    item(key = "all-apps") {
                        UsageAppChoiceRow(
                            packageName = null,
                            label = tr("全部应用总时长", "All apps total"),
                            foregroundMillis = allAppsMillis,
                            selected = selectedPackage == null,
                            onClick = {
                                onSelect(null)
                                expanded = false
                            },
                        )
                    }
                    if (choices.isNotEmpty()) {
                        item(key = "divider") {
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        }
                    }
                    items(
                        items = choices,
                        key = UsageAppChoice::packageName,
                    ) { choice ->
                        UsageAppChoiceRow(
                            packageName = choice.packageName,
                            label = choice.label,
                            foregroundMillis = choice.foregroundMillis.toDouble(),
                            selected = selectedPackage == choice.packageName,
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
}

@Composable
private fun UsageAppChoiceRow(
    packageName: String?,
    label: String,
    foregroundMillis: Double,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UsageAppIcon(packageName = packageName, modifier = Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatUsageDuration(foregroundMillis),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = tr("已选择", "Selected"),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun UsageAppIcon(
    packageName: String?,
    modifier: Modifier = Modifier,
) {
    if (packageName == null) {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp),
                )
            }
        }
        return
    }
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = packageName,
    ) {
        value = withContext(Dispatchers.IO) {
            loadUsageAppIcon(context, packageName)
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(MaterialTheme.shapes.small),
        )
    } else {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp),
                )
            }
        }
    }
}

private fun loadUsageAppIcon(
    context: android.content.Context,
    packageName: String,
): ImageBitmap? {
    usageAppIconCache.get(packageName)?.let { return it.asImageBitmap() }
    val applicationIcon = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()
    val queriedLauncherIcon = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        matches.firstOrNull()?.loadIcon(context.packageManager)
    }.getOrNull()
    val launcherAppsIcon = runCatching {
        context.getSystemService(LauncherApps::class.java)
            .getActivityList(packageName, Process.myUserHandle())
            .firstOrNull()
            ?.getIcon(context.resources.displayMetrics.densityDpi)
    }.getOrNull()
    val drawable = applicationIcon ?: queriedLauncherIcon ?: launcherAppsIcon ?: return null
    return drawable.renderBoundedIcon()?.also { bitmap ->
        usageAppIconCache.put(packageName, bitmap)
    }?.asImageBitmap()
}

private fun Drawable.renderBoundedIcon(): Bitmap? = runCatching {
    val width = intrinsicWidth.takeIf { it > 0 } ?: APP_ICON_RENDER_SIZE_PX
    val height = intrinsicHeight.takeIf { it > 0 } ?: APP_ICON_RENDER_SIZE_PX
    val scale = minOf(
        1f,
        APP_ICON_RENDER_SIZE_PX.toFloat() / width,
        APP_ICON_RENDER_SIZE_PX.toFloat() / height,
    )
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val previousBounds = Rect(bounds)
        setBounds(0, 0, targetWidth, targetHeight)
        draw(canvas)
        setBounds(previousBounds)
    }
}.getOrNull()

private val usageAppIconCache = LruCache<String, Bitmap>(64)
private const val APP_ICON_RENDER_SIZE_PX = 128

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
