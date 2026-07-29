package com.deskcubby.app.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deskcubby.app.data.repository.AiContextCandidate
import com.deskcubby.app.data.repository.AiContextCodec
import com.deskcubby.app.data.repository.AiContextItemPreview
import com.deskcubby.app.data.repository.AiContextSource
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.theme.tr

@Composable
internal fun AiContextPickerDialog(
    source: AiContextSource,
    candidates: List<AiContextCandidate>,
    selectedKeys: Set<String>,
    errorMessage: String?,
    isLoading: Boolean,
    isLoadingPreview: Boolean,
    preview: AiContextItemPreview?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleGroup: (Collection<String>) -> Unit,
    onPreview: (String) -> Unit,
    onDismissPreview: () -> Unit,
) {
    val visibleCandidates = remember(candidates, source) {
        candidates.filter { it.source == source }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (source) {
                            AiContextSource.DIARY -> tr("选择日记上下文", "Choose diary context")
                            AiContextSource.THOUGHT -> tr("选择小巧思上下文", "Choose thought context")
                            else -> tr("选择上下文", "Choose context")
                        },
                    )
                    Text(
                        tr(
                            "已选择 ${selectedKeys.size}/${AiContextCodec.MAX_ITEMS} 项",
                            "${selectedKeys.size}/${AiContextCodec.MAX_ITEMS} selected",
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(enabled = !isLoading, onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, tr("刷新", "Refresh"))
                }
            }
        },
        text = {
            Column {
                Text(
                    tr(
                        "发送前会读取并冻结完整快照。单项上限 64 KiB、总计 256 KiB；超限会拒绝发送，不会截断。",
                        "Full snapshots are read and frozen just before sending. The limits are 64 KiB per item and 256 KiB total; oversized data is rejected, never truncated.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                errorMessage?.takeIf(String::isNotBlank)?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                when {
                    isLoading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppLoadingIndicator(size = 24.dp, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(tr("正在读取可选条目…", "Loading available items…"))
                        }
                    }

                    visibleCandidates.isEmpty() -> {
                        Text(
                            when (source) {
                                AiContextSource.DIARY -> tr(
                                    "没有可导入的日记。请先确认日记目录已授权并完成扫描。",
                                    "There are no diaries to import. Check the diary directory permission and scan it first.",
                                )

                                AiContextSource.THOUGHT -> tr(
                                    "没有可导入的小巧思。",
                                    "There are no thoughts to import.",
                                )

                                else -> tr("没有可导入的条目。", "There are no items to import.")
                            },
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (source == AiContextSource.THOUGHT) {
                                val groups = visibleCandidates
                                    .groupBy(AiContextCandidate::groupKey)
                                    .values
                                    .sortedWith(
                                        compareBy<List<AiContextCandidate>> {
                                            it.firstOrNull()?.groupSortOrder ?: Long.MAX_VALUE
                                        }.thenBy {
                                            it.firstOrNull()?.groupTitle.orEmpty()
                                        },
                                    )
                                groups.forEachIndexed { index, group ->
                                    val groupKeys = group.map(AiContextCandidate::selectionKey)
                                    val allSelected = groupKeys.all { it in selectedKeys }
                                    item(key = "thought-group-${group.first().groupKey}-$index") {
                                        ThoughtCategoryHeader(
                                            title = group.first().groupTitle,
                                            itemCount = group.size,
                                            allSelected = allSelected,
                                            onToggleGroup = { onToggleGroup(groupKeys) },
                                        )
                                    }
                                    items(
                                        items = group,
                                        key = AiContextCandidate::selectionKey,
                                    ) { candidate ->
                                        ContextCandidateRow(
                                            candidate = candidate,
                                            selected = candidate.selectionKey in selectedKeys,
                                            enabled = selectedKeys.size < AiContextCodec.MAX_ITEMS ||
                                                candidate.selectionKey in selectedKeys,
                                            onToggle = { onToggle(candidate.selectionKey) },
                                            onPreview = { onPreview(candidate.selectionKey) },
                                        )
                                    }
                                }
                            } else {
                                items(
                                    items = visibleCandidates,
                                    key = AiContextCandidate::selectionKey,
                                ) { candidate ->
                                    ContextCandidateRow(
                                        candidate = candidate,
                                        selected = candidate.selectionKey in selectedKeys,
                                        enabled = selectedKeys.size < AiContextCodec.MAX_ITEMS ||
                                            candidate.selectionKey in selectedKeys,
                                        onToggle = { onToggle(candidate.selectionKey) },
                                        onPreview = { onPreview(candidate.selectionKey) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (isLoadingPreview) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tr("正在读取预览…", "Loading preview…"),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("完成", "Done"))
            }
        },
        dismissButton = {
            if (selectedKeys.isNotEmpty()) {
                TextButton(
                    onClick = {
                        selectedKeys.toList().forEach(onToggle)
                    },
                ) {
                    Text(tr("清空选择", "Clear selection"))
                }
            }
        },
    )

    preview?.let {
        AiContextPreviewDialog(preview = it, onDismiss = onDismissPreview)
    }
}

@Composable
private fun ThoughtCategoryHeader(
    title: String,
    itemCount: Int,
    allSelected: Boolean,
    onToggleGroup: () -> Unit,
) {
    val displayTitle = title.ifBlank { tr("未分类", "Uncategorized") }
    val actionDescription = if (allSelected) {
        tr("取消整个分类：$displayTitle", "Clear entire category: $displayTitle")
    } else {
        tr("导入整个分类：$displayTitle", "Import entire category: $displayTitle")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lightbulb,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "$displayTitle · $itemCount",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(
            onClick = onToggleGroup,
            modifier = Modifier.semantics {
                contentDescription = actionDescription
            },
        ) {
            Text(
                if (allSelected) {
                    tr("取消整类", "Clear category")
                } else {
                    tr("导入整类", "Import category")
                },
            )
        }
    }
}

@Composable
private fun ContextCandidateRow(
    candidate: AiContextCandidate,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(start = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                enabled = enabled,
                onCheckedChange = { onToggle() },
            )
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.title.ifBlank { tr("未命名条目", "Untitled item") },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                val details = buildList {
                    candidate.subtitle.takeIf(String::isNotBlank)?.let(::add)
                    candidate.estimatedBytes?.let {
                        add(estimatedSizeLabel(it))
                    }
                }.joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(
                        details,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidate.previewExcerpt.isNotBlank()) {
                    Text(
                        candidate.previewExcerpt +
                            if (candidate.previewIsExcerpt) tr("…（摘要）", "… (excerpt)") else "",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onPreview) {
                Icon(Icons.Outlined.Visibility, tr("预览完整条目", "Preview full item"))
            }
        }
    }
}

@Composable
private fun AiContextPreviewDialog(
    preview: AiContextItemPreview,
    onDismiss: () -> Unit,
) {
    val sourceName = sourceLabel(preview.source)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(preview.title.ifBlank { tr("未命名条目", "Untitled item") })
                Text(
                    buildList {
                        add(sourceName)
                        preview.date.takeIf(String::isNotBlank)?.let(::add)
                        preview.attribution.takeIf(String::isNotBlank)?.let(::add)
                        add(estimatedSizeLabel(preview.encodedBytes.toLong()))
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (preview.exceedsItemLimit) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            tr(
                                "该条目超过 64 KiB，发送时会被拒绝，不会截断。",
                                "This item exceeds 64 KiB and will be rejected at send time, not truncated.",
                            ),
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (preview.contentExcerpt.isBlank()) {
                    Text(tr("此条目没有额外正文。", "This item has no additional body text."))
                } else {
                    Text(preview.contentExcerpt, style = MaterialTheme.typography.bodyMedium)
                    if (preview.contentIsExcerpt) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr(
                                "预览只显示前 12,000 个字符；发送时仍校验完整内容，且不会截断。",
                                "The preview shows only the first 12,000 characters. The full content is still validated at send time and is never truncated.",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
internal fun PendingContextSummary(
    count: Int,
    onClear: () -> Unit,
) {
    val clearDescription = tr("清空已选择的上下文", "Clear selected context")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("已选择 $count 项上下文", "$count context items selected"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    tr("将在发送前读取并冻结快照", "Snapshots will be read and frozen before sending"),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = onClear,
                modifier = Modifier.semantics {
                    contentDescription = clearDescription
                },
            ) {
                Text(tr("清空", "Clear"))
            }
        }
    }
}

@Composable
internal fun ContextMessageCard(messageId: Long, encodedSnapshot: String) {
    val snapshot = remember(encodedSnapshot) { AiContextCodec.decodeOrNull(encodedSnapshot) }
    val expanded = rememberSaveable(messageId) {
        mutableStateOf(false)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded.value = !expanded.value },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Description, null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("已冻结的参考上下文", "Frozen reference context"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (snapshot == null) {
                            tr("快照无法解析，但仍保留在会话中", "Snapshot could not be parsed but remains in the conversation")
                        } else {
                            tr("${snapshot.items.size} 项 · 随会话继续发送", "${snapshot.items.size} items · reused with this conversation")
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Icon(
                    if (expanded.value) {
                        Icons.Outlined.ExpandLess
                    } else {
                        Icons.Outlined.ExpandMore
                    },
                    if (expanded.value) tr("收起", "Collapse") else tr("展开", "Expand"),
                )
            }
            if (expanded.value && snapshot != null) {
                Spacer(Modifier.height(10.dp))
                var remainingPreviewChars = CONTEXT_CARD_TOTAL_PREVIEW_CHARS
                snapshot.items.forEachIndexed { index, item ->
                    if (index > 0) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.22f))
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        item.title.ifBlank { sourceLabel(item.source) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val metadata = listOf(
                        sourceLabel(item.source),
                        item.date,
                        item.attribution,
                    ).filter(String::isNotBlank).joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Text(metadata, style = MaterialTheme.typography.labelSmall)
                    }
                    if (item.content.isNotBlank() && remainingPreviewChars > 0) {
                        val visibleContent = item.content.take(
                            minOf(CONTEXT_CARD_ITEM_PREVIEW_CHARS, remainingPreviewChars),
                        )
                        remainingPreviewChars -= visibleContent.length
                        Spacer(Modifier.height(5.dp))
                        Text(visibleContent, style = MaterialTheme.typography.bodySmall)
                        if (visibleContent.length < item.content.length) {
                            Text(
                                tr(
                                    "…卡片仅显示摘要；完整快照仍保存在会话中并随请求发送。",
                                    "…The card shows an excerpt; the complete snapshot remains saved and is sent with requests.",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    } else if (item.content.isNotBlank()) {
                        Text(
                            tr(
                                "正文未在卡片中展开；完整快照仍保存在会话中并随请求发送。",
                                "Body omitted from this card; the complete snapshot remains saved and is sent with requests.",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sourceLabel(source: AiContextSource): String = when (source) {
    AiContextSource.DIARY -> tr("日记", "Diary")
    AiContextSource.THOUGHT -> tr("小巧思", "Thought")
    AiContextSource.DATE_RECORD -> tr("日期记录", "Date record")
    AiContextSource.POEM -> tr("诗词本", "Poetry")
}

private fun estimatedSizeLabel(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val tenths = bytes * 10L / 1024L
    return "${tenths / 10}.${tenths % 10} KiB"
}

private const val CONTEXT_CARD_ITEM_PREVIEW_CHARS = 2_000
private const val CONTEXT_CARD_TOTAL_PREVIEW_CHARS = 12_000
