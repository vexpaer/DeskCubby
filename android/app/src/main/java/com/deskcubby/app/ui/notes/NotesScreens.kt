@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.notes

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.repository.NoteEntry
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.components.MarkdownPreview
import com.deskcubby.app.ui.components.MarkdownResolvedMedia
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr

@Composable
fun NotesScreen(
    padding: PaddingValues,
    viewModel: NotesViewModel,
    onOpenNote: () -> Unit,
) {
    val state by viewModel.browserState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var createFolder by remember { mutableStateOf(false) }
    var createNote by remember { mutableStateOf(false) }
    var actionEntry by remember { mutableStateOf<NoteEntry?>(null) }
    var renameEntry by remember { mutableStateOf<NoteEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<NoteEntry?>(null) }
    val rootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        uri -> uri?.let(viewModel::selectRoot)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val canGoUp = state.breadcrumbs.size > 1
    BackHandler(enabled = canGoUp) {
        viewModel.openBreadcrumb(state.breadcrumbs.lastIndex - 1)
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tr("笔记", "Notes"))
                        state.snapshot?.location?.name?.let { name ->
                            Text(
                                name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (canGoUp) {
                        IconButton(
                            onClick = {
                                viewModel.openBreadcrumb(state.breadcrumbs.lastIndex - 1)
                            },
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("上一级", "Up"))
                        }
                    }
                },
                actions = {
                    IconButton(
                        enabled = state.snapshot != null && !state.mutating,
                        onClick = { createFolder = true },
                    ) {
                        Icon(Icons.Outlined.CreateNewFolder, tr("新建文件夹", "New folder"))
                    }
                    IconButton(
                        enabled = state.snapshot != null && !state.mutating,
                        onClick = { createNote = true },
                    ) {
                        Icon(Icons.Outlined.Add, tr("新建笔记", "New note"))
                    }
                    IconButton(
                        enabled = !state.mutating,
                        onClick = {
                            rootPicker.launch(settings.notesTreeUri?.let(Uri::parse))
                        },
                    ) {
                        Icon(Icons.Outlined.FolderOpen, tr("选择笔记库", "Choose notes vault"))
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).navigationBarsPadding()) {
            when {
                settings.notesTreeUri == null -> AppEmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = tr("选择笔记库", "Choose a notes vault"),
                    description = tr(
                        "选择 Obsidian 仓库或普通文件夹。Markdown 正文和媒体仍是目录中的真实文件。",
                        "Choose an Obsidian vault or a regular folder. Markdown and media remain real files in that folder.",
                    ),
                    actionLabel = tr("选择文件夹", "Choose folder"),
                    onAction = { rootPicker.launch(null) },
                    modifier = Modifier.fillMaxSize(),
                )

                state.loading && state.snapshot == null ->
                    AppLoadingIndicator(Modifier.align(Alignment.Center))

                state.snapshot != null -> Column(Modifier.fillMaxSize()) {
                    if (state.breadcrumbs.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            state.breadcrumbs.forEachIndexed { index, location ->
                                AssistChip(
                                    onClick = { viewModel.openBreadcrumb(index) },
                                    label = {
                                        Text(
                                            location.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    val entries = state.snapshot?.entries.orEmpty()
                    if (entries.isEmpty()) {
                        AppEmptyState(
                            icon = Icons.Outlined.Description,
                            title = tr("这个文件夹是空的", "This folder is empty"),
                            description = tr(
                                "可新建 Markdown 笔记或子文件夹。非 Markdown 文件会保留在磁盘上，但不出现在列表中。",
                                "Create a Markdown note or subfolder. Non-Markdown files remain on disk but are not listed here.",
                            ),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(entries, key = NoteEntry::uri) { entry ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        if (entry.isFolder) viewModel.openFolder(entry)
                                        else viewModel.openNote(entry, onOpenNote)
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            if (entry.isFolder) Icons.Outlined.Folder
                                            else Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                entry.name.removeSuffix(".md"),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                if (entry.isFolder) tr("文件夹", "Folder")
                                                else formatNoteSize(entry.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Box {
                                            IconButton(
                                                enabled = !state.mutating,
                                                onClick = { actionEntry = entry },
                                            ) {
                                                Icon(Icons.Outlined.MoreVert, tr("更多操作", "More actions"))
                                            }
                                            DropdownMenu(
                                                expanded = actionEntry?.uri == entry.uri,
                                                onDismissRequest = { actionEntry = null },
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text(tr("重命名", "Rename")) },
                                                    leadingIcon = {
                                                        Icon(Icons.Outlined.DriveFileRenameOutline, null)
                                                    },
                                                    onClick = {
                                                        actionEntry = null
                                                        renameEntry = entry
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(tr("删除", "Delete")) },
                                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                                    onClick = {
                                                        actionEntry = null
                                                        deleteEntry = entry
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.loading || state.mutating) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (createFolder) {
        NoteNameDialog(
            title = tr("新建文件夹", "New folder"),
            initial = "",
            onDismiss = { createFolder = false },
            onConfirm = {
                createFolder = false
                viewModel.createFolder(it)
            },
        )
    }
    if (createNote) {
        NoteNameDialog(
            title = tr("新建 Markdown 笔记", "New Markdown note"),
            initial = "",
            onDismiss = { createNote = false },
            onConfirm = {
                createNote = false
                viewModel.createNote(it, onOpenNote)
            },
        )
    }
    renameEntry?.let { entry ->
        NoteNameDialog(
            title = tr("重命名", "Rename"),
            initial = entry.name,
            onDismiss = { renameEntry = null },
            onConfirm = {
                renameEntry = null
                viewModel.rename(entry, it)
            },
        )
    }
    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = {
                Text(
                    if (entry.isFolder) tr("删除文件夹？", "Delete folder?")
                    else tr("删除笔记？", "Delete note?"),
                )
            },
            text = {
                Text(
                    if (entry.isFolder) {
                        tr(
                            "“${entry.name}”及其中全部文件会由存储服务删除。此操作无法撤回。",
                            "“${entry.name}” and all files inside it will be deleted by the storage provider. This cannot be undone.",
                        )
                    } else {
                        tr(
                            "“${entry.name}”会被删除。此操作无法撤回。",
                            "“${entry.name}” will be deleted. This cannot be undone.",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteEntry = null
                        viewModel.delete(entry)
                    },
                ) { Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(tr("笔记操作失败", "Notes operation failed")) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text(tr("知道了", "OK")) }
            },
        )
    }
}

@Composable
fun NoteEditorScreen(
    viewModel: NotesViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.editorState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editorValue by remember { mutableStateOf(TextFieldValue(state.content)) }
    var pendingMediaUri by rememberSaveable { mutableStateOf<String?>(null) }
    val destinationPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { destination ->
        val source = pendingMediaUri?.let(Uri::parse)
        pendingMediaUri = null
        if (source != null && destination != null) viewModel.importMedia(source, destination)
    }
    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { source ->
        if (source != null) {
            pendingMediaUri = source.toString()
            destinationPicker.launch(settings.notesTreeUri?.let(Uri::parse))
        }
    }

    LaunchedEffect(state.content) {
        if (editorValue.text != state.content) {
            val cursor = editorValue.selection.end.coerceIn(0, state.content.length)
            editorValue = TextFieldValue(state.content, TextRange(cursor))
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.saveNow() } }
    BackHandler {
        viewModel.saveNow()
        onBack()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.document?.name ?: tr("笔记编辑器", "Note editor"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(
                                when {
                                    state.conflict != null -> tr("发现外部修改", "External changes found")
                                    state.saving -> tr("正在保存…", "Saving…")
                                    state.dirty -> tr("未保存", "Unsaved")
                                    else -> tr("已保存", "Saved")
                                },
                                if (state.preview) tr("阅读预览", "Preview")
                                else tr("Markdown 源码", "Markdown source"),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveNow(); onBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::togglePreview) {
                        Icon(
                            if (state.preview) {
                                Icons.Outlined.Source
                            } else {
                                Icons.AutoMirrored.Outlined.MenuBook
                            },
                            if (state.preview) tr("源码", "Source") else tr("预览", "Preview"),
                        )
                    }
                    IconButton(onClick = { viewModel.saveNow() }) {
                        Icon(Icons.Outlined.Save, tr("保存", "Save"))
                    }
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = state.document != null && !state.saving,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(tr("上传媒体", "Upload media"))
                            Text(
                                tr(
                                    "每次上传都选择笔记库内的存储位置",
                                    "Choose a location inside the vault every time",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                state.loading -> AppLoadingIndicator(Modifier.align(Alignment.Center))
                state.preview -> MarkdownPreview(
                    content = state.content,
                    headingSizesSp = settings.markdownHeadingSizesSp,
                    maxWidthDp = settings.imageMaxWidthDp,
                    maxHeightDp = settings.imageMaxHeightDp,
                    mediaScopeKey = state.document?.folderRelativePath,
                    resolveMediaBatch = { targets ->
                        viewModel.resolvePreviewMedia(targets).mapValues { (_, uri) ->
                            MarkdownResolvedMedia(model = uri)
                        }
                    },
                )
                else -> Surface(
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        BasicTextField(
                            value = editorValue,
                            onValueChange = {
                                editorValue = it
                                viewModel.onContentChanged(it.text)
                            },
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        )
                        if (editorValue.text.isEmpty()) {
                            Text(
                                tr("开始写 Markdown…", "Start writing Markdown…"),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (state.saving) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    state.conflict?.let { disk ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("文件已在外部修改", "File changed externally")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr(
                            "${disk.name} 在 Obsidian 或其他应用中发生了变化。自动保存已暂停。",
                            "${disk.name} changed in Obsidian or another app. Autosave is paused.",
                        ),
                    )
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = viewModel::saveConflictCopy,
                    ) { Text(tr("另存 DeskCubby 冲突副本", "Save a DeskCubby conflict copy")) }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::reloadConflict) {
                    Text(tr("加载磁盘版本", "Load disk version"))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.saveNow(force = true) }) {
                    Text(tr("明确覆盖", "Overwrite"))
                }
            },
        )
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(tr("笔记操作失败", "Notes operation failed")) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissError) { Text(tr("知道了", "OK")) }
            },
        )
    }
}

@Composable
private fun NoteNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(220) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(tr("名称", "Name")) },
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value) },
            ) { Text(tr("确定", "OK")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

private fun formatNoteSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KiB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)
}
