@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.ai

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.agent.AgentReviewMutation
import com.deskcubby.app.agent.AgentReviewRun
import com.deskcubby.app.agent.AgentReviewToolEvent
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.theme.tr
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
fun AgentReviewScreen(
    padding: PaddingValues,
    viewModel: AgentReviewViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var undoTarget by remember { mutableStateOf<AgentReviewMutation?>(null) }
    LaunchedEffect(state.message, state.errorMessage) {
        (state.errorMessage ?: state.message)?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("Agent Review") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回 Agent", "Back to Agent"))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        if (state.runs.isEmpty()) {
            AppEmptyState(
                icon = Icons.Outlined.Restore,
                title = tr("还没有 Agent 运行记录", "No Agent runs yet"),
                description = tr(
                    "读取与网络工具会出现在详细记录中；真正改变数据的操作会显示修改前后内容和 Undo 状态。",
                    "Reads and web tools appear in details. Data-changing operations show before/after content and Undo status.",
                ),
                modifier = Modifier.fillMaxSize().padding(inner),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(tr("按运行 / 会话查看", "Browse by run / conversation"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.runs, key = AgentReviewRun::runId) { run ->
                            RunRow(run, run.runId == state.selectedRunId) { viewModel.selectRun(run.runId) }
                        }
                    }
                }
                item {
                    Text(tr("实际修改", "Actual changes"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("Review 只在修改真实执行后标记为可撤回。", "Review marks Undo available only after a mutation actually succeeds."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.mutations.isEmpty()) {
                    item {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text(tr("本次运行没有改变用户数据。", "This run did not change user data."), modifier = Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                } else {
                    items(state.mutations, key = AgentReviewMutation::id) { mutation ->
                        MutationCard(
                            mutation,
                            undoing = state.undoingMutationId == mutation.id,
                            onUndo = { undoTarget = mutation },
                        )
                    }
                }
                item {
                    DetailedExecutionLog(state.events)
                }
            }
        }
    }
    undoTarget?.let { mutation ->
        AlertDialog(
            onDismissRequest = { undoTarget = null },
            title = { Text(tr("撤回这项修改？", "Undo this change?")) },
            text = {
                Text(
                    tr(
                        "DeskCubby 会在确认当前数据仍与 Agent 修改后的版本一致后，真正恢复修改前状态：${mutation.target}",
                        "DeskCubby will restore the prior state only after confirming the current data still matches the Agent result: ${mutation.target}",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { undoTarget = null; viewModel.undo(mutation.id) }) { Text("Undo") }
            },
            dismissButton = { TextButton(onClick = { undoTarget = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun RunRow(run: AgentReviewRun, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.conversationTitle.ifBlank { tr("未命名会话", "Untitled conversation") }, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AssistChip(onClick = onClick, label = { Text(run.status) })
            }
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.startedAt)), style = MaterialTheme.typography.labelSmall)
            Text(run.userRequestSummary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            if (run.usage.modelCallCount > 0) {
                Text(
                    if (run.usage.reportedCallCount > 0) {
                        val total = run.usage.totalTokens.takeIf { run.usage.totalTokensReported }
                            ?.let(NumberFormat.getIntegerInstance()::format) ?: "—"
                        tr(
                            "$total Token · ${run.usage.modelCallCount} 次调用",
                            "$total tokens · ${run.usage.modelCallCount} calls",
                        )
                    } else {
                        tr("${run.usage.modelCallCount} 次调用 · Provider 未报告 Token", "${run.usage.modelCallCount} calls · tokens unreported")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MutationCard(mutation: AgentReviewMutation, undoing: Boolean, onUndo: () -> Unit) {
    var expanded by rememberSaveable(mutation.id) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${mutation.operation} · ${mutation.toolName}", fontWeight = FontWeight.SemiBold)
                    Text(mutation.target, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(tr("详情", "Details"))
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }
            Text(mutation.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(mutation.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                if (mutation.before.isNotBlank()) ReviewSnapshot(tr("修改前", "Before"), mutation.before)
                if (mutation.after.isNotBlank()) ReviewSnapshot(tr("修改后", "After"), mutation.after)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mutation.canUndo) tr("当前可撤回", "Undo available") else tr("不可撤回 / 已撤回", "Unavailable / undone"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Button(enabled = mutation.canUndo && !undoing, onClick = onUndo) {
                    if (undoing) AppLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Restore, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Undo")
                }
            }
        }
    }
}

@Composable
private fun ReviewSnapshot(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        SelectionContainer {
            Text(value, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun DetailedExecutionLog(events: List<AgentReviewToolEvent>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("详细工具执行记录", "Detailed tool execution log"), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("${events.size}", style = MaterialTheme.typography.labelMedium)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            Text(
                tr("包含只读查询和网络搜索；不包含隐藏推理。", "Includes read-only queries and web searches; hidden reasoning is never shown."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) events.forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider()
                Text("${event.toolName} · ${event.status}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (event.target.isNotBlank()) Text(event.target, style = MaterialTheme.typography.labelSmall)
                if (event.argumentsSummary.isNotBlank()) Text(event.argumentsSummary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                if (event.resultSummary.isNotBlank()) Text(event.resultSummary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
