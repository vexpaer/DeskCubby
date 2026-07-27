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
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MenuBook
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    candidates: List<AiContextCandidate>,
    selectedKeys: Set<String>,
    isLoading: Boolean,
    isLoadingPreview: Boolean,
    preview: AiContextItemPreview?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (String) -> Unit,
    onPreview: (String) -> Unit,
    onDismissPreview: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("导入 AI 上下文", "Import AI context"))
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

                    candidates.isEmpty() -> {
                        Text(
                            tr(
                                "没有可导入的日记、小巧思、日期记录或诗词。",
                                "There are no diaries, thoughts, date records, or poems to import.",
                            ),
                            modifier = Modifier.padding(vertical = 28.dp),
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AiContextSource.entries.forEach { source ->
                                val sourceItems = candidates.filter { it.source == source }
                                if (sourceItems.isNotEmpty()) {
                                    item(key = "header-${source.wireValue}") {
                                        ContextSourceHeader(source, sourceItems.size)
                                    }
                                    items(
                                        items = sourceItems,
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
private fun ContextSourceHeader(source: AiContextSource, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = sourceIcon(source),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "${sourceLabel(source)} · $count",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
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
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
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
            TextButton(onClick = onClear) { Text(tr("清空", "Clear")) }
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

private fun sourceIcon(source: AiContextSource): ImageVector = when (source) {
    AiContextSource.DIARY -> Icons.Outlined.Article
    AiContextSource.THOUGHT -> Icons.Outlined.Lightbulb
    AiContextSource.DATE_RECORD -> Icons.Outlined.CalendarMonth
    AiContextSource.POEM -> Icons.Outlined.MenuBook
}

private fun estimatedSizeLabel(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val tenths = bytes * 10L / 1024L
    return "${tenths / 10}.${tenths % 10} KiB"
}

private const val CONTEXT_CARD_ITEM_PREVIEW_CHARS = 2_000
private const val CONTEXT_CARD_TOTAL_PREVIEW_CHARS = 12_000
