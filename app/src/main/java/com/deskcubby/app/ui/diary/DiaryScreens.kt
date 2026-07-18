@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.diary

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.DiaryEditorDocument
import com.deskcubby.app.ui.theme.GlassPanel
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiaryListScreen(
    padding: PaddingValues,
    viewModel: DiaryViewModel,
    onOpen: (String) -> Unit,
    onOpenToday: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    var expandedMonth by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<DiaryDocument?>(null) }
    var renameItem by remember { mutableStateOf<DiaryDocument?>(null) }
    var deleteItem by remember { mutableStateOf<DiaryDocument?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var permanentlyDeleting by remember { mutableStateOf<com.deskcubby.app.data.model.DiaryTrashItem?>(null) }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("日记") },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, "刷新") }
                    IconButton(onClick = { createDialog = true }) { Icon(Icons.Outlined.Add, "新建") }
                    IconButton(onClick = { viewModel.refreshTrash(); showTrash = true }) { Icon(Icons.Outlined.DeleteSweep, "日记回收站") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "设置") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenToday,
                icon = { Icon(Icons.Outlined.Today, null) },
                text = { Text("进入今日日记") },
            )
        },
    ) { inner ->
        when {
            settings.diaryTreeUri == null -> EmptyDiary(onOpenSettings, Modifier.padding(inner))
            state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(state.error ?: "目录中还没有 Markdown 日记")
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.byMonth.forEach { (month, diaries) ->
                    item(key = month) {
                        GlassPanel(
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = { expandedMonth = if (expandedMonth == month) null else month },
                                onLongClick = {},
                            ),
                            cornerRadius = 18.dp,
                            padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(month, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(
                                    diaries.size.toString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (expandedMonth == month) {
                        items(diaries, key = { it.uri }) { diary ->
                            Card(
                                modifier = Modifier.fillMaxWidth().combinedClickable(
                                    onClick = { onOpen(diary.uri) },
                                    onLongClick = { selectedItem = diary },
                                ),
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(diary.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${diary.dateIso} · ${diary.wordCount} 字", style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { selectedItem = diary }) { Icon(Icons.Outlined.MoreVert, "更多") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedItem?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedItem = null },
            title = { Text(item.title) },
            text = { Text(item.name) },
            confirmButton = {
                TextButton(onClick = { renameItem = item; selectedItem = null }) {
                    Icon(Icons.Outlined.Edit, null); Text("重命名")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = item; selectedItem = null }) {
                    Icon(Icons.Outlined.Delete, null); Text("删除")
                }
            },
        )
    }
    renameItem?.let { item ->
        TextInputDialog(
            title = "修改日记标题",
            initial = item.title,
            onDismiss = { renameItem = null },
            onConfirm = { viewModel.rename(item.uri, it); renameItem = null },
        )
    }
    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text("删除 ${item.title}？") },
            text = { Text("文件会改名后移入 DeskCubby 回收状态，可从日记页右上角恢复。") },
            confirmButton = { TextButton(onClick = { viewModel.delete(item.uri); deleteItem = null }) { Text("移入回收站") } },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("取消") } },
        )
    }
    if (createDialog) {
        TextInputDialog(
            title = "新建日记",
            initial = "",
            onDismiss = { createDialog = false },
            onConfirm = { title -> viewModel.create(title) { createDialog = false; onOpen(viewModel.editorState.value.document?.uri.orEmpty()) } },
        )
    }
    if (showTrash) {
        AlertDialog(
            onDismissRequest = { showTrash = false },
            title = { Text("日记回收站") },
            text = {
                if (trash.isEmpty()) Text("回收站为空") else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(trash, key = { it.uri }) { item ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.originalName, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { viewModel.restoreTrash(item.uri) }) { Icon(Icons.Outlined.Restore, "恢复") }
                                IconButton(onClick = { permanentlyDeleting = item }) { Icon(Icons.Outlined.DeleteForever, "永久删除") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTrash = false }) { Text("完成") } },
        )
    }
    permanentlyDeleting?.let { item ->
        AlertDialog(
            onDismissRequest = { permanentlyDeleting = null },
            title = { Text("永久删除？") },
            text = { Text(item.originalName + " 将无法恢复。") },
            confirmButton = {
                TextButton(onClick = { viewModel.permanentlyDeleteTrash(item.uri); permanentlyDeleting = null }) { Text("永久删除") }
            },
            dismissButton = { TextButton(onClick = { permanentlyDeleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyDiary(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.CreateNewFolder, null, modifier = Modifier.width(64.dp).height(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("选择一个包含 Markdown 文件的日记目录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onSettings) { Text("前往设置") }
    }
}

@Composable
fun DiaryEditorScreen(
    viewModel: DiaryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.editorState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editorValue by remember { mutableStateOf(TextFieldValue(state.content)) }
    var categoryMenu by remember { mutableStateOf(false) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    var captionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.importImage(it, pendingCategory, editorValue.selection.start) }
        pendingCategory = null
    }

    LaunchedEffect(state.content) {
        if (editorValue.text != state.content) {
            val cursor = editorValue.selection.start.coerceIn(0, state.content.length)
            editorValue = TextFieldValue(state.content, androidx.compose.ui.text.TextRange(cursor))
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.saveNow() } }
    BackHandler {
        viewModel.saveNow()
        onBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.document?.name ?: "日记编辑器", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when { state.conflict != null -> "发现外部修改"; state.saving -> "正在保存…"; state.dirty -> "未保存"; else -> "已保存" },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { viewModel.saveNow(); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = viewModel::undo) { Icon(Icons.Outlined.Undo, "撤销") }
                    IconButton(onClick = viewModel::redo) { Icon(Icons.Outlined.Redo, "重做") }
                    IconButton(onClick = viewModel::togglePreview) {
                        Icon(if (state.preview) Icons.Outlined.Source else Icons.Outlined.MenuBook, if (state.preview) "源码" else "预览")
                    }
                    IconButton(onClick = { viewModel.saveNow() }) { Icon(Icons.Outlined.Save, "保存") }
                },
            )
        },
        bottomBar = {
            GlassPanel(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                cornerRadius = 0.dp,
                padding = PaddingValues(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        FilledTonalButton(
                            onClick = {
                                pendingCategory = null
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    pendingCategory = null
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onLongClick = { categoryMenu = true },
                            ),
                        ) {
                            Icon(Icons.Outlined.CloudUpload, null)
                            Spacer(Modifier.width(8.dp))
                            Text("上传媒体")
                        }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            listOf("早餐", "午餐", "晚餐", "水果", "夜宵").forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category) },
                                    leadingIcon = { Icon(Icons.Outlined.Image, null) },
                                    onClick = {
                                        categoryMenu = false
                                        pendingCategory = category
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                )
                            }
                        }
                    }
                    Text(if (state.preview) "阅读预览" else "Markdown 源码", style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.preview -> MarkdownPreview(
                    content = state.content,
                    maxWidth = settings.imageMaxWidthDp,
                    maxHeight = settings.imageMaxHeightDp,
                    resolveMedia = viewModel::resolveMedia,
                    onEditCaption = { markdown, caption -> captionTarget = markdown to caption },
                    onMoveImage = viewModel::moveImageBlock,
                )
                else -> OutlinedTextField(
                    value = editorValue,
                    onValueChange = { value -> editorValue = value; viewModel.onContentChanged(value.text) },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    placeholder = { Text("开始记录…") },
                )
            }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    state.conflict?.let { disk ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("文件已在外部修改") },
            text = { Text("${disk.name} 的磁盘内容与打开时不同。自动保存已暂停，避免覆盖 Obsidian 的修改。") },
            confirmButton = { TextButton(onClick = viewModel::reloadConflict) { Text("加载磁盘版本") } },
            dismissButton = { TextButton(onClick = { viewModel.saveNow(force = true) }) { Text("强制覆盖") } },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text("操作失败") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("知道了") } },
        )
    }
    captionTarget?.let { (markdown, caption) ->
        TextInputDialog(
            title = "修改图片说明",
            initial = caption,
            onDismiss = { captionTarget = null },
            onConfirm = { viewModel.updateImageCaption(markdown, it); captionTarget = null },
        )
    }
}

@Composable
private fun MarkdownPreview(
    content: String,
    maxWidth: Int,
    maxHeight: Int,
    resolveMedia: suspend (String) -> Uri?,
    onEditCaption: (String, String) -> Unit,
    onMoveImage: (String, Int) -> Unit,
) {
    val imageRegex = remember { Regex("!\\[([^]]*)]\\((?:<([^>]+)>|([^\\s)]+))\\)") }
    val parser = remember { Parser.builder().build() }
    val renderer = remember { HtmlRenderer.builder().build() }
    val parts = remember(content) {
        buildList {
            var cursor = 0
            imageRegex.findAll(content).forEach { match ->
                if (match.range.first > cursor) add(PreviewPart.Text(content.substring(cursor, match.range.first)))
                add(PreviewPart.Image(match.value, match.groupValues[1], match.groupValues[2].ifBlank { match.groupValues[3] }))
                cursor = match.range.last + 1
            }
            if (cursor < content.length) add(PreviewPart.Text(content.substring(cursor)))
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(parts) { part ->
            when (part) {
                is PreviewPart.Text -> {
                    val html = renderer.render(parser.parse(part.markdown))
                    val plain = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
                    if (plain.isNotBlank()) Text(plain, style = MaterialTheme.typography.bodyLarge)
                }
                is PreviewPart.Image -> {
                    val uri by produceState<Uri?>(initialValue = null, part.target) { value = resolveMedia(part.target) }
                    var dragOffset by remember(part.fullMarkdown) { mutableFloatStateOf(0f) }
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { translationY = dragOffset }
                            .pointerInput(part.fullMarkdown) {
                                detectDragGesturesAfterLongPress(
                                    onDrag = { change, amount -> change.consume(); dragOffset += amount.y },
                                    onDragCancel = { dragOffset = 0f },
                                    onDragEnd = {
                                        if (abs(dragOffset) > 28f) onMoveImage(part.fullMarkdown, if (dragOffset < 0f) -1 else 1)
                                        dragOffset = 0f
                                    },
                                )
                            },
                        padding = PaddingValues(10.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = uri,
                                contentDescription = part.caption,
                                modifier = Modifier.fillMaxWidth().widthIn(max = maxWidth.dp).heightIn(max = maxHeight.dp),
                            )
                            TextButton(onClick = { onEditCaption(part.fullMarkdown, part.caption) }) {
                                Text(part.caption.ifBlank { "点击添加图片说明" })
                            }
                            Text("长按图片并上下拖动可调整位置", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private sealed interface PreviewPart {
    data class Text(val markdown: String) : PreviewPart
    data class Image(val fullMarkdown: String, val caption: String, val target: String) : PreviewPart
}

@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
