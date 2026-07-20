@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.diary

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import kotlin.math.roundToInt

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
    val expandedMonth by viewModel.expandedMonth.collectAsStateWithLifecycle()
    val operationMessage by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    var createDialog by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf<DiaryDocument?>(null) }
    var renameItem by remember { mutableStateOf<DiaryDocument?>(null) }
    var deleteItem by remember { mutableStateOf<DiaryDocument?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var permanentlyDeleting by remember { mutableStateOf<com.deskcubby.app.data.model.DiaryTrashItem?>(null) }

    LaunchedEffect(operationMessage) {
        operationMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("日记", "Diary")) },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Outlined.Refresh, tr("刷新", "Refresh")) }
                    IconButton(onClick = { createDialog = true }) { Icon(Icons.Outlined.Add, tr("新建", "New")) }
                    IconButton(onClick = { viewModel.refreshTrash(); showTrash = true }) { Icon(Icons.Outlined.DeleteSweep, tr("日记回收站", "Diary trash")) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, tr("设置", "Settings")) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenToday,
                icon = { Icon(Icons.Outlined.Today, null) },
                text = { Text(tr("进入今日日记", "Open today's diary")) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        when {
            settings.diaryTreeUri == null -> EmptyDiary(onOpenSettings, Modifier.padding(inner))
            state.loading && state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { AppLoadingIndicator() }
            state.items.isEmpty() -> AppEmptyState(
                icon = Icons.Outlined.MenuBook,
                title = if (state.error == null) {
                    tr("这里还没有日记", "No diaries here yet")
                } else {
                    tr("无法读取日记", "Could not load diaries")
                },
                description = state.error ?: tr(
                    "可以从今日日记开始记录。",
                    "Start writing with today's diary.",
                ),
                actionLabel = if (state.error == null) tr("进入今日日记", "Open today's diary")
                else tr("重试", "Retry"),
                onAction = if (state.error == null) onOpenToday else viewModel::refresh,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.byMonth.forEach { (month, diaries) ->
                    item(key = month) {
                        GlassPanel(
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = { viewModel.toggleExpandedMonth(month) },
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
                                shape = if (organic) visuals.listShape else MaterialTheme.shapes.medium,
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(diary.name.removeSuffix(".md"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == com.deskcubby.app.data.model.AppLanguage.ENGLISH) "${diary.wordCount} words" else "${diary.wordCount} 字", style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { selectedItem = diary }) { Icon(Icons.Outlined.MoreVert, tr("更多", "More")) }
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
            title = { Text(item.name.removeSuffix(".md")) },
            text = { Text(item.name) },
            confirmButton = {
                TextButton(onClick = { renameItem = item; selectedItem = null }) {
                    Icon(Icons.Outlined.Edit, null); Text(tr("重命名", "Rename"))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteItem = item; selectedItem = null }) {
                    Icon(Icons.Outlined.Delete, null); Text(tr("删除", "Delete"))
                }
            },
        )
    }
    renameItem?.let { item ->
        TextInputDialog(
            title = tr("重命名文件", "Rename file"),
            initial = item.name.removeSuffix(".md"),
            onDismiss = { renameItem = null },
            onConfirm = { viewModel.rename(item.uri, it); renameItem = null },
        )
    }
    deleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteItem = null },
            title = { Text(tr("删除 ${item.name.removeSuffix(".md")}？", "Delete ${item.name.removeSuffix(".md")}?")) },
            text = { Text(tr("文件将安全复制到日记目录内的回收站，校验成功后才删除原文件。", "The file is copied and verified in the diary trash before the original is removed.")) },
            confirmButton = { TextButton(onClick = { viewModel.delete(item.uri); deleteItem = null }) { Text(tr("移入回收站", "Move to trash")) } },
            dismissButton = { TextButton(onClick = { deleteItem = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
    if (createDialog) {
        TextInputDialog(
            title = tr("新建日记", "New diary"),
            initial = "",
            onDismiss = { createDialog = false },
            onConfirm = { title -> viewModel.create(title) { createDialog = false; onOpen(viewModel.editorState.value.document?.uri.orEmpty()) } },
        )
    }
    if (showTrash) {
        AlertDialog(
            onDismissRequest = { showTrash = false },
            title = { Text(tr("日记回收站", "Diary trash")) },
            text = {
                if (trash.isEmpty()) Text(tr("回收站为空", "Trash is empty")) else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(trash, key = { it.uri }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = if (organic) visuals.listShape else MaterialTheme.shapes.medium,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.originalName, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { viewModel.restoreTrash(item.uri) }) { Icon(Icons.Outlined.Restore, null); Text(tr("恢复", "Restore")) }
                                IconButton(onClick = { permanentlyDeleting = item; showTrash = false }) { Icon(Icons.Outlined.DeleteForever, tr("永久删除", "Delete forever")) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTrash = false }) { Text(tr("完成", "Done")) } },
        )
    }
    permanentlyDeleting?.let { item ->
        AlertDialog(
            onDismissRequest = { permanentlyDeleting = null },
            title = { Text(tr("永久删除？", "Delete forever?")) },
            text = { Text(tr(item.originalName + " 将无法恢复。", "${item.originalName} cannot be recovered.")) },
            confirmButton = {
                TextButton(onClick = { viewModel.permanentlyDeleteTrash(item.uri); permanentlyDeleting = null; showTrash = true }) { Text(tr("永久删除", "Delete forever")) }
            },
            dismissButton = { TextButton(onClick = { permanentlyDeleting = null; showTrash = true }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun EmptyDiary(onSettings: () -> Unit, modifier: Modifier = Modifier) {
    AppEmptyState(
        icon = Icons.Outlined.CreateNewFolder,
        title = tr("选择日记目录", "Choose a diary folder"),
        description = tr(
            "选择一个包含 Markdown 文件的目录，日记会按月份整理。",
            "Choose a folder containing Markdown files; diaries will be organized by month.",
        ),
        actionLabel = tr("前往设置", "Open settings"),
        onAction = onSettings,
        modifier = modifier.fillMaxSize(),
        iconSize = 64.dp,
    )
}

@Composable
fun DiaryEditorScreen(
    viewModel: DiaryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.editorState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    var editorValue by remember { mutableStateOf(TextFieldValue(state.content)) }
    var categoryMenu by remember { mutableStateOf(false) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    var captionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.importImage(it, pendingCategory) }
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
                        Text(state.document?.name ?: tr("日记编辑器", "Diary editor"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when { state.conflict != null -> tr("发现外部修改", "External changes found"); state.saving -> tr("正在保存…", "Saving…"); state.dirty -> tr("未保存", "Unsaved"); else -> tr("已保存", "Saved") },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { viewModel.saveNow(); onBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back")) } },
                actions = {
                    IconButton(onClick = viewModel::togglePreview) {
                        Icon(if (state.preview) Icons.Outlined.Source else Icons.Outlined.MenuBook, if (state.preview) tr("源码", "Source") else tr("预览", "Preview"))
                    }
                    IconButton(onClick = { viewModel.saveNow() }) { Icon(Icons.Outlined.Save, tr("保存", "Save")) }
                },
            )
        },
        bottomBar = {
            GlassPanel(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                cornerRadius = 0.dp,
                role = PanelRole.TOOLBAR,
                padding = PaddingValues(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Surface(
                            shape = if (organic) visuals.badgeShape else MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                modifier = Modifier.combinedClickable(
                                onClick = {
                                    pendingCategory = null
                                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                                onLongClick = { categoryMenu = true },
                                ).padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.CloudUpload, null)
                                Spacer(Modifier.width(8.dp))
                                Text(tr("上传媒体", "Upload media"))
                            }
                        }
                        DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            listOf(
                                "早餐" to "Breakfast",
                                "午餐" to "Lunch",
                                "晚餐" to "Dinner",
                                "水果" to "Fruit",
                                "夜宵" to "Late-night snack",
                            ).forEach { (category, english) ->
                                DropdownMenuItem(
                                    text = { Text(tr(category, english)) },
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
                    Text(if (state.preview) tr("阅读预览", "Preview") else tr("Markdown 源码", "Markdown source"), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                state.loading -> AppLoadingIndicator(Modifier.align(Alignment.Center))
                state.preview -> MarkdownPreview(
                    content = state.content,
                    maxWidth = settings.imageMaxWidthDp,
                    maxHeight = settings.imageMaxHeightDp,
                    resolveMedia = viewModel::resolveMedia,
                    onEditCaption = { markdown, caption -> captionTarget = markdown to caption },
                )
                else -> MarkdownSourceEditor(
                    value = editorValue,
                    onValueChange = { value -> editorValue = value; viewModel.onContentChanged(value.text) },
                    onMoveMediaLine = viewModel::moveSourceLine,
                )
            }
            if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    state.conflict?.let { disk ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("文件已在外部修改", "File changed externally")) },
            text = { Text(tr("${disk.name} 的磁盘内容与打开时不同。自动保存已暂停，避免覆盖 Obsidian 的修改。", "${disk.name} changed on disk. Autosave is paused to avoid overwriting changes from Obsidian.")) },
            confirmButton = { TextButton(onClick = viewModel::reloadConflict) { Text(tr("加载磁盘版本", "Load disk version")) } },
            dismissButton = { TextButton(onClick = { viewModel.saveNow(force = true) }) { Text(tr("强制覆盖", "Overwrite")) } },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(tr("操作失败", "Operation failed")) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(tr("知道了", "OK")) } },
        )
    }
    captionTarget?.let { (markdown, caption) ->
        TextInputDialog(
            title = tr("修改图片说明", "Edit image caption"),
            initial = caption,
            onDismiss = { captionTarget = null },
            onConfirm = { viewModel.updateImageCaption(markdown, it); captionTarget = null },
        )
    }
}

private data class MediaSourceLine(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
)

private val markdownMediaLinePattern = Regex("""^\s*!\[[^\]]*\]\((?:<[^>]+>|[^)]*)\)\s*$""")

@Composable
private fun MarkdownSourceEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onMoveMediaLine: (fromIndex: Int, toIndex: Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val mediaLines = remember(value.text) { findMediaSourceLines(value.text) }
    val topPadding = 16.dp
    // The handle is an overlay and must never participate in text measurement. Keeping it
    // close to the editor's normal line height also prevents adjacent media rows overlapping.
    val handleSize = 24.dp

    Surface(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        // Keep the writing plane geometrically regular in every visual style so decoration never
        // changes cursor, selection, scrolling, or media-line drag behavior.
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        BoxWithConstraints {
            val viewportHeight = maxHeight
            val viewportHeightPx = constraints.maxHeight.toFloat()
            val topPaddingPx = with(density) { topPadding.toPx() }
            val cursorMarginPx = with(density) { 24.dp.toPx() }

            LaunchedEffect(value.selection, value.text, textLayout, constraints.maxHeight) {
                withFrameNanos { }
                val layout = textLayout?.takeIf { it.layoutInput.text.text == value.text }
                    ?: return@LaunchedEffect
                if (viewportHeightPx <= 0f) return@LaunchedEffect

                val cursorOffset = value.selection.end.coerceIn(0, value.text.length)
                val cursorRect = layout.getCursorRect(cursorOffset)
                val cursorTop = topPaddingPx + cursorRect.top
                val cursorBottom = topPaddingPx + cursorRect.bottom
                val visibleTop = scrollState.value.toFloat()
                val visibleBottom = visibleTop + viewportHeightPx
                val target = when {
                    cursorBottom + cursorMarginPx > visibleBottom ->
                        cursorBottom + cursorMarginPx - viewportHeightPx
                    cursorTop - cursorMarginPx < visibleTop ->
                        cursorTop - cursorMarginPx
                    else -> null
                }
                target?.let {
                    scrollState.scrollTo(it.roundToInt().coerceIn(0, scrollState.maxValue))
                }
            }

            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                Box(Modifier.fillMaxWidth().heightIn(min = viewportHeight)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = viewportHeight)
                            .padding(start = 16.dp, top = topPadding, end = 40.dp, bottom = 40.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { textLayout = it },
                    )

                    if (value.text.isEmpty()) {
                        Text(
                            text = tr("开始记录…", "Start writing…"),
                            modifier = Modifier.padding(start = 16.dp, top = topPadding, end = 40.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                        )
                    }

                    val currentLayout = textLayout
                    if (currentLayout != null && currentLayout.layoutInput.text.text == value.text) {
                        mediaLines.forEach { mediaLine ->
                            val visualLine = currentLayout.getLineForOffset(mediaLine.startOffset)
                            val lineTop = currentLayout.getLineTop(visualLine)
                            val lineHeight = currentLayout.getLineBottom(visualLine) - lineTop
                            val handleTopPx = with(density) { topPadding.toPx() } +
                                lineTop +
                                (lineHeight - with(density) { handleSize.toPx() }) / 2f

                            FourDotDragHandle(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(handleSize)
                                    .offset { IntOffset(x = 0, y = handleTopPx.coerceAtLeast(0f).roundToInt()) },
                                onDragFinished = { verticalDistance ->
                                    val maxY = (currentLayout.size.height - 1).coerceAtLeast(0).toFloat()
                                    val targetY = (lineTop + lineHeight / 2f + verticalDistance).coerceIn(0f, maxY)
                                    val targetVisualLine = currentLayout.getLineForVerticalPosition(targetY)
                                    val targetOffset = currentLayout.getLineStart(targetVisualLine)
                                    val targetSourceLine = value.text
                                        .take(targetOffset.coerceIn(0, value.text.length))
                                        .count { it == '\n' }
                                    onMoveMediaLine(mediaLine.index, targetSourceLine)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun findMediaSourceLines(source: String): List<MediaSourceLine> = buildList {
    var startOffset = 0
    source.split('\n').forEachIndexed { index, line ->
        if (markdownMediaLinePattern.matches(line)) {
            add(MediaSourceLine(index = index, startOffset = startOffset, endOffset = startOffset + line.length))
        }
        startOffset += line.length + 1
    }
}

@Composable
private fun MarkdownPreview(
    content: String,
    maxWidth: Int,
    maxHeight: Int,
    resolveMedia: suspend (String) -> Uri?,
    onEditCaption: (String, String) -> Unit,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
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
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        role = PanelRole.MEDIA,
                        padding = PaddingValues(10.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = uri,
                                contentDescription = part.caption,
                                modifier = Modifier
                                    .widthIn(max = maxWidth.dp)
                                    .fillMaxWidth()
                                    .heightIn(max = maxHeight.dp)
                                    .then(if (organic) Modifier.clip(visuals.mediaShape) else Modifier),
                                contentScale = ContentScale.Fit,
                            )
                            TextButton(onClick = { onEditCaption(part.fullMarkdown, part.caption) }) {
                                Text(part.caption.ifBlank { tr("点击添加图片说明", "Tap to add a caption") })
                            }
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
        confirmButton = { TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(tr("确定", "OK")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}
