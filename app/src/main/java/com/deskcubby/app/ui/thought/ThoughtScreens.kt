@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
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
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThoughtScreen(
    padding: PaddingValues,
    viewModel: ThoughtViewModel,
    onTrash: () -> Unit,
) {
    val items by viewModel.active.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    var editor by remember { mutableStateOf("") }
    var ratio by remember(settings.thoughtSplitRatio) { mutableFloatStateOf(settings.thoughtSplitRatio) }
    var actionItem by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("闪思") },
                actions = { IconButton(onClick = onTrash) { Icon(Icons.Outlined.Unarchive, "回收站") } },
            )
        },
    ) { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
            val totalHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(ratio)) {
                    if (items.isEmpty()) {
                        Text("还没有闪思，在下方快速写一条吧。", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(items, key = { it.id }) { item ->
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
                                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.updatedAt)),
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
                                        ) { Icon(Icons.Outlined.Delete, "删除") }
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
                    Icon(Icons.Outlined.DragHandle, "拖动调整分界线", tint = MaterialTheme.colorScheme.primary)
                }

                Column(Modifier.fillMaxWidth().weight(1f - ratio).padding(12.dp)) {
                    if (selected != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("正在编辑一条旧闪思", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selected = null; editor = "" }) {
                                Icon(Icons.Outlined.Cancel, null); Text("取消")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = editor,
                        onValueChange = { editor = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        placeholder = { Text("此刻在想什么？") },
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
                        ) { Icon(Icons.Outlined.Send, "发送") }
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
                        Icon(Icons.Outlined.PushPin, null); Spacer(Modifier.width(8.dp)); Text(if (item.pinned) "取消置顶" else "置顶")
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(item.content)); actionItem = null }) {
                        Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("复制")
                    }
                    TextButton(onClick = { viewModel.delete(item.id); actionItem = null }) {
                        Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(8.dp)); Text("移入回收站")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { actionItem = null }) { Text("关闭") } },
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
                title = { Text("闪思回收站") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            )
        },
    ) { inner ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { Text("回收站为空") }
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
                            IconButton(onClick = { viewModel.restore(item.id) }) { Icon(Icons.Outlined.Restore, "恢复") }
                            IconButton(onClick = { deleting = item }) { Icon(Icons.Outlined.DeleteForever, "永久删除") }
                        }
                    }
                }
            }
        }
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("永久删除？") },
            text = { Text("此操作无法恢复。") },
            confirmButton = { TextButton(onClick = { viewModel.permanentlyDelete(item.id); deleting = null }) { Text("永久删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}
