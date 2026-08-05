@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.diary

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.theme.tr
import kotlinx.coroutines.delay

@Composable
fun CalorieEstimationProgressScreen(
    viewModel: DiaryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.calorieEstimationQueueState.collectAsStateWithLifecycle()
    val active = state.active
    val queued = state.queued
    val finished = state.items.filter(CalorieEstimationDayProgress::isTerminal).asReversed()
    var inspectedWorkId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("热量估算进度", "Calorie estimation progress")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(
                        enabled = finished.isNotEmpty(),
                        onClick = viewModel::clearFinishedCalorieEstimationProgress,
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, tr("清除已完成记录", "Clear finished entries"))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.items.isEmpty()) {
            AppEmptyState(
                icon = Icons.Outlined.Calculate,
                title = tr("还没有估算任务", "No estimation tasks yet"),
                description = tr(
                    "在吃历中点按计算按钮加入任务；长按该按钮可随时回到这里。",
                    "Tap the calculate button in the meal calendar to add work; long-press it to return here.",
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") {
                    CalorieQueueSummary(state)
                }
                active?.let { progress ->
                    item(key = "active-header") {
                        ProgressSectionTitle(tr("正在处理", "In progress"))
                    }
                    item(key = "active-${progress.id}") {
                        CalorieDayProgressCard(
                            progress = progress,
                            emphasized = true,
                            onClick = { inspectedWorkId = progress.id },
                        )
                    }
                }
                if (queued.isNotEmpty()) {
                    item(key = "queued-header") {
                        ProgressSectionTitle(
                            tr("排队（${queued.size} 天）", "Queued (${queued.size} day(s))"),
                        )
                    }
                    itemsIndexed(
                        items = queued,
                        key = { _, item -> "queued-${item.id}" },
                    ) { index, progress ->
                        CalorieDayProgressCard(
                            progress = progress,
                            queuePosition = index + 1,
                        )
                    }
                }
                if (finished.isNotEmpty()) {
                    item(key = "finished-header") {
                        ProgressSectionTitle(
                            tr("本轮结果", "Current run results"),
                        )
                    }
                    items(finished, key = { "finished-${it.id}" }) { progress ->
                        CalorieDayProgressCard(progress = progress)
                    }
                }
            }
        }
    }

    inspectedWorkId?.let { workId ->
        state.items.firstOrNull { it.id == workId }?.let { progress ->
            CalorieModelTraceDialog(
                progress = progress,
                onDismiss = { inspectedWorkId = null },
            )
        }
    }
}

@Composable
private fun CalorieQueueSummary(state: CalorieEstimationQueueState) {
    val total = state.items.size.coerceAtLeast(1)
    val finished = state.finishedDayCount
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.isRunning) tr("队列处理中", "Queue in progress")
                    else tr("本轮处理完毕", "Current run finished"),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    tr("$finished / ${state.items.size} 天", "$finished / ${state.items.size} days"),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            LinearProgressIndicator(
                progress = { finished.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.failedDayCount > 0) {
                Text(
                    tr(
                        "其中 ${state.failedDayCount} 天失败；其他日期仍会继续处理。",
                        "${state.failedDayCount} day(s) failed; the remaining dates still continue.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    tr(
                        "同一天最多 3 张图片并行识别，再统一计算并只保存一次；中途结果不会部分写入。",
                        "Up to 3 photos per date are recognized in parallel, then calculated " +
                            "together and saved once; intermediate results are not partially written.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProgressSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CalorieDayProgressCard(
    progress: CalorieEstimationDayProgress,
    emphasized: Boolean = false,
    queuePosition: Int? = null,
    onClick: (() -> Unit)? = null,
) {
    val status = calorieProgressStatus(progress)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = onClick != null,
                role = Role.Button,
                onClick = { onClick?.invoke() },
            ),
        shape = MaterialTheme.shapes.large,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (emphasized) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = status.icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (progress.status == CalorieEstimationQueueStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(progress.dateIso, style = MaterialTheme.typography.titleMedium)
                        if (progress.forceRecalculation) {
                            Text(
                                tr(" · 重新估算", " · Recalculate"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        if (progress.status == CalorieEstimationQueueStatus.QUEUED &&
                            queuePosition != null
                        ) {
                            tr("排队第 $queuePosition 位", "Queue position $queuePosition")
                        } else {
                            status.label
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (progress.status == CalorieEstimationQueueStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            when (progress.status) {
                CalorieEstimationQueueStatus.IMAGE_RECOGNITION -> Text(
                    tr(
                        "并行识别中：${progress.activePhotoCount} 张正在处理，" +
                            "已完成 ${progress.completedPhotoCount} / ${progress.selectedPhotoCount} 张",
                        "Parallel recognition: ${progress.activePhotoCount} active, " +
                            "${progress.completedPhotoCount} / ${progress.selectedPhotoCount} complete",
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                CalorieEstimationQueueStatus.TEXT_ESTIMATION -> Text(
                    tr(
                        "${progress.selectedPhotoCount} 张图片均已识别，正在统一计算当天热量",
                        "All ${progress.selectedPhotoCount} photos are recognized; " +
                            "calculating this date together",
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                else -> Unit
            }

            if (!progress.isTerminal && progress.status != CalorieEstimationQueueStatus.QUEUED) {
                if (progress.status == CalorieEstimationQueueStatus.IMAGE_RECOGNITION) {
                    LinearProgressIndicator(
                        progress = {
                            progress.completedPhotoCount.toFloat() /
                                progress.selectedPhotoCount.coerceAtLeast(1).toFloat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (onClick != null) {
                    Text(
                        tr(
                            "点按查看模型实时思考、回复与用时",
                            "Tap to view live model reasoning, response, and elapsed time",
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            when (progress.status) {
                CalorieEstimationQueueStatus.QUEUED -> Text(
                    tr(
                        "${progress.selectedPhotoCount} 张图片等待处理",
                        "${progress.selectedPhotoCount} photo(s) waiting",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CalorieEstimationQueueStatus.COMPLETED -> Text(
                    tr(
                        "${progress.selectedPhotoCount} 张图片已完成并按天保存",
                        "${progress.selectedPhotoCount} photo(s) completed and saved for this date",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CalorieEstimationQueueStatus.FAILED -> Text(
                    progress.error ?: tr("估算失败", "Estimation failed"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun CalorieModelTraceDialog(
    progress: CalorieEstimationDayProgress,
    onDismiss: () -> Unit,
) {
    var nowElapsedRealtime by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val hasRunningTrace = progress.modelTraces.any(CalorieModelTrace::isRunning)
    LaunchedEffect(hasRunningTrace) {
        while (hasRunningTrace) {
            nowElapsedRealtime = SystemClock.elapsedRealtime()
            delay(200L)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("模型实时进度", "Live model progress")) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "summary") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(progress.dateIso, style = MaterialTheme.typography.titleMedium)
                        progress.currentPhotoLabel?.let { label ->
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (progress.modelTraces.isEmpty()) {
                    item(key = "waiting") {
                        Text(
                            tr("正在准备模型请求…", "Preparing the model request…"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    itemsIndexed(
                        items = progress.modelTraces,
                        key = { index, trace ->
                            "${trace.stage}-${trace.selectedPhotoIndex}-${trace.startedAtElapsedRealtime}-$index"
                        },
                    ) { _, trace ->
                        CalorieModelTraceCard(trace, nowElapsedRealtime)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) }
        },
    )
}

@Composable
private fun CalorieModelTraceCard(
    trace: CalorieModelTrace,
    nowElapsedRealtime: Long,
) {
    var reasoningExpanded by rememberSaveable(
        trace.stage.name,
        trace.selectedPhotoIndex,
        trace.startedAtElapsedRealtime,
    ) { mutableStateOf(trace.isRunning) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (trace.isRunning) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (trace.stage) {
                            com.deskcubby.app.data.repository.MealCalorieEstimationStage.IMAGE_RECOGNITION ->
                                tr("图片识别模型", "Image recognition model")
                            com.deskcubby.app.data.repository.MealCalorieEstimationStage.TEXT_ESTIMATION ->
                                tr("文字估算模型", "Text estimation model")
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        trace.modelName.ifBlank { tr("未命名模型", "Unnamed model") },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (trace.selectedPhotoIndex > 0) {
                            tr(
                                "第 ${trace.selectedPhotoIndex} 张 · ${trace.photoLabel}",
                                "Photo ${trace.selectedPhotoIndex} · ${trace.photoLabel}",
                            )
                        } else {
                            trace.photoLabel
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    tr(
                        "${formatCalorieTraceElapsed(trace.elapsedMillis(nowElapsedRealtime))} 秒",
                        "${formatCalorieTraceElapsed(trace.elapsedMillis(nowElapsedRealtime))} s",
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(onClick = { reasoningExpanded = !reasoningExpanded }) {
                Text(
                    if (reasoningExpanded) tr("收起思考", "Collapse reasoning")
                    else tr("展开思考", "Expand reasoning"),
                )
            }
            if (reasoningExpanded) {
                Text(
                    trace.reasoning.ifBlank {
                        if (trace.isRunning) {
                            tr("等待模型输出思考内容…", "Waiting for model reasoning…")
                        } else {
                            tr(
                                "模型没有提供独立的思考内容。",
                                "The model did not provide separate reasoning.",
                            )
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Text(tr("回复", "Response"), style = MaterialTheme.typography.labelLarge)
            Text(
                trace.response.ifBlank {
                    if (trace.isRunning) tr("等待模型回复…", "Waiting for model response…")
                    else tr("模型没有返回回复。", "The model returned no response.")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

internal fun formatCalorieTraceElapsed(elapsedMillis: Long): String {
    val tenths = elapsedMillis.coerceAtLeast(0L) / 100L
    return "${tenths / 10}.${tenths % 10}"
}

private data class CalorieProgressStatusUi(
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun calorieProgressStatus(
    progress: CalorieEstimationDayProgress,
): CalorieProgressStatusUi = when (progress.status) {
    CalorieEstimationQueueStatus.QUEUED -> CalorieProgressStatusUi(
        Icons.Outlined.Schedule,
        tr("排队中", "Queued"),
    )
    CalorieEstimationQueueStatus.IMAGE_RECOGNITION -> CalorieProgressStatusUi(
        Icons.Outlined.Image,
        tr("图片模型识别", "Image model recognition"),
    )
    CalorieEstimationQueueStatus.TEXT_ESTIMATION -> CalorieProgressStatusUi(
        Icons.Outlined.TextFields,
        tr("文字模型估算", "Text model estimation"),
    )
    CalorieEstimationQueueStatus.SAVING -> CalorieProgressStatusUi(
        Icons.Outlined.Save,
        tr("正在保存当天结果", "Saving this date"),
    )
    CalorieEstimationQueueStatus.COMPLETED -> CalorieProgressStatusUi(
        Icons.Outlined.CheckCircle,
        tr("已完成并保存", "Completed and saved"),
    )
    CalorieEstimationQueueStatus.FAILED -> CalorieProgressStatusUi(
        Icons.Outlined.ErrorOutline,
        when (progress.failedAtStatus) {
            CalorieEstimationQueueStatus.IMAGE_RECOGNITION ->
                tr("图片模型识别失败，队列继续", "Image-model recognition failed; queue continues")
            CalorieEstimationQueueStatus.TEXT_ESTIMATION ->
                tr("文字模型估算失败，队列继续", "Text-model estimation failed; queue continues")
            CalorieEstimationQueueStatus.SAVING ->
                tr("保存当天结果失败，队列继续", "Saving this date failed; queue continues")
            else -> tr("当天失败，队列继续", "Date failed; queue continues")
        },
    )
}
