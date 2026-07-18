@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.deskcubby.app.ui.thought

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.theme.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThoughtScreen(
    padding: PaddingValues,
    viewModel: ThoughtViewModel,
    onTrash: () -> Unit,
) {
    val activeState by viewModel.activeState.collectAsStateWithLifecycle()
    val items = activeState.items
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    var editor by remember { mutableStateOf("") }
    var ratio by remember(settings.thoughtSplitRatio) { mutableFloatStateOf(settings.thoughtSplitRatio) }
    var actionItem by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val thoughtDateFormatter = remember { DateTimeFormatter.ofPattern("M/d") }

    LaunchedEffect(activeState.pendingScrollItemId, items) {
        activeState.pendingScrollItemId?.let { itemId ->
            val itemIndex = items.indexOfFirst { it.id == itemId }
            if (itemIndex >= 0) {
                listState.animateScrollToItem(itemIndex)
                viewModel.consumeScrollRequest(itemId)
            }
        }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("小巧思", "Thoughts")) },
                actions = { IconButton(onClick = onTrash) { Icon(Icons.Outlined.Unarchive, tr("回收站", "Trash")) } },
            )
        },
    ) { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner).imePadding().imeNestedScroll()) {
            val totalHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(ratio)) {
                    if (items.isEmpty()) {
                        Text(tr("还没有小巧思，在下方快速写一条吧。", "No thoughts yet. Write one below."), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().combinedClickable(
                                        onClick = { selected = item; editor = item.content },
                                        onLongClick = { actionItem = item },
                                    ),
                                ) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (item.pinned) Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary)
                                        if (item.pinned) Spacer(Modifier.width(8.dp))
                                        Text(item.content, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Text(
                                            Instant.ofEpochMilli(item.updatedAt).atZone(ZoneId.systemDefault()).format(thoughtDateFormatter),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        IconButton(
                                            onClick = {
                                                if (selected?.id == item.id) {
                                                    selected = null
                                                    editor = ""
                                                }
                                                viewModel.delete(item.id)
                                            },
                                        ) { Icon(Icons.Outlined.Delete, tr("删除", "Delete")) }
                                        FourDotDragHandle(
                                            enabled = items.size > 1,
                                            onDragFinished = { distance ->
                                                val visibleItems = listState.layoutInfo.visibleItemsInfo
                                                val sourceInfo = visibleItems.firstOrNull { it.key == item.id }
                                                if (sourceInfo != null) {
                                                    val targetCenter = sourceInfo.offset + sourceInfo.size / 2f + distance
                                                    val targetInfo = visibleItems.firstOrNull { info ->
                                                        targetCenter >= info.offset && targetCenter <= info.offset + info.size
                                                    } ?: visibleItems.minByOrNull { info ->
                                                        abs(targetCenter - (info.offset + info.size / 2f))
                                                    }
                                                    if (targetInfo != null && targetInfo.index != sourceInfo.index) {
                                                        viewModel.move(item.id, targetInfo.index)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .pointerInput(totalHeightPx) {
                            detectDragGestures(
                                onDragEnd = { viewModel.setSplitRatio(ratio) },
                                onDragCancel = { viewModel.setSplitRatio(ratio) },
                            ) { change, drag ->
                                change.consume()
                                ratio = (ratio + drag.y / totalHeightPx).coerceIn(0.25f, 0.8f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    HorizontalDivider()
                    Icon(Icons.Outlined.DragHandle, tr("拖动调整分界线", "Drag to resize"), tint = MaterialTheme.colorScheme.primary)
                }

                Column(Modifier.fillMaxWidth().weight(1f - ratio).heightIn(min = 160.dp).padding(12.dp)) {
                    if (selected != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("正在编辑一条小巧思", "Editing an existing thought"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selected = null; editor = "" }) {
                                Icon(Icons.Outlined.Cancel, null); Text(tr("取消", "Cancel"))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = editor,
                        onValueChange = { editor = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        placeholder = { Text(tr("此刻在想什么？", "What's on your mind?")) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FilledIconButton(
                            onClick = {
                                viewModel.submit(selected?.id, editor) {
                                    selected = null
                                    editor = ""
                                }
                            },
                            enabled = editor.isNotBlank(),
                        ) { Icon(Icons.Outlined.Send, tr("发送", "Send")) }
                    }
                }
            }
        }
    }

    actionItem?.let { item ->
        AlertDialog(
            onDismissRequest = { actionItem = null },
            title = { Text(item.content, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { viewModel.togglePinned(item.id); actionItem = null }) {
                        Icon(Icons.Outlined.PushPin, null); Spacer(Modifier.width(8.dp)); Text(if (item.pinned) tr("取消置顶", "Unpin") else tr("置顶", "Pin"))
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(item.content)); actionItem = null }) {
                        Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(tr("复制", "Copy"))
                    }
                    TextButton(onClick = { viewModel.delete(item.id); actionItem = null }) {
                        Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(8.dp)); Text(tr("移入回收站", "Move to trash"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionItem = null }) { Text(tr("关闭", "Close")) } },
        )
    }
}

@Composable
fun ThoughtTrashScreen(viewModel: ThoughtViewModel, onBack: () -> Unit) {
    val items by viewModel.trash.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("小巧思回收站", "Thought trash")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back")) } },
            )
        },
    ) { inner ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { Text(tr("回收站为空", "Trash is empty")) }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.content, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.restore(item.id) }) { Icon(Icons.Outlined.Restore, tr("恢复", "Restore")) }
                            IconButton(onClick = { deleting = item }) { Icon(Icons.Outlined.DeleteForever, tr("永久删除", "Delete forever")) }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("永久删除？", "Delete forever?")) },
            text = { Text(tr("此操作无法恢复。", "This cannot be undone.")) },
            confirmButton = { TextButton(onClick = { viewModel.permanentlyDelete(item.id); deleting = null }) { Text(tr("永久删除", "Delete forever")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}
