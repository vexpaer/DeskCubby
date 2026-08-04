@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.diary

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.theme.tr

@Composable
fun CalorieEstimationProgressScreen(
    viewModel: DiaryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.calorieEstimationQueueState.collectAsStateWithLifecycle()
    val active = state.active
    val queued = state.queued
    val finished = state.items.filter(CalorieEstimationDayProgress::isTerminal).asReversed()

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
                        CalorieDayProgressCard(progress = progress, emphasized = true)
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
                        "每一天的全部图片完成后才保存一次，中途结果不会部分写入。",
                        "Each date is saved once only after all of its photos finish; intermediate results are not partially written.",
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
) {
    val status = calorieProgressStatus(progress)
    Card(
        modifier = Modifier.fillMaxWidth(),
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

            progress.currentSelectedPhotoIndex?.let { selectedIndex ->
                val dayIndex = progress.currentDayPhotoIndex ?: selectedIndex
                val position = if (progress.selectedPhotoCount == progress.dayPhotoCount) {
                    tr(
                        "第 $dayIndex / ${progress.dayPhotoCount} 张图片",
                        "Photo $dayIndex / ${progress.dayPhotoCount}",
                    )
                } else {
                    tr(
                        "待估算第 $selectedIndex / ${progress.selectedPhotoCount} 张（当天第 $dayIndex / ${progress.dayPhotoCount} 张）",
                        "Pending photo $selectedIndex / ${progress.selectedPhotoCount} (day photo $dayIndex / ${progress.dayPhotoCount})",
                    )
                }
                Text(position, style = MaterialTheme.typography.labelLarge)
                progress.currentPhotoLabel?.let { label ->
                    Text(
                        label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!progress.isTerminal && progress.status != CalorieEstimationQueueStatus.QUEUED) {
                LinearProgressIndicator(
                    progress = {
                        progress.completedPhotoCount.toFloat() /
                            progress.selectedPhotoCount.coerceAtLeast(1).toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
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
