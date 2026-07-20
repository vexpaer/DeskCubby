@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.poetry

import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr

@Composable
fun PoetryBookScreen(
    padding: PaddingValues,
    viewModel: PoetryBookViewModel,
) {
    val poems by viewModel.poems.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val operationFailedLabel = tr("操作失败", "Operation failed")
    var showNewEditor by remember { mutableStateOf(false) }
    var editorPoem by remember { mutableStateOf<SavedPoemEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedPoemEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var updatingId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar("$operationFailedLabel: $it")
            viewModel.consumeError()
        }
    }

    Scaffold(
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = { TopAppBar(title = { Text(tr("诗词本", "Poetry book")) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewEditor = true }) {
                Icon(Icons.Outlined.Add, tr("添加诗词", "Add poem"))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        if (poems.isEmpty()) {
            EmptyPoetryBook(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                onAdd = { showNewEditor = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(poems, key = { it.id }) { poem ->
                    SavedPoemCard(
                        poem = poem,
                        onEdit = { editorPoem = poem },
                        onDelete = { pendingDelete = poem },
                    )
                }
            }
        }
    }

    if (showNewEditor) {
        PoemEditorDialog(
            poem = null,
            saving = creating,
            onDismiss = { if (!creating) showNewEditor = false },
            onConfirm = { content, source ->
                if (!creating) {
                    creating = true
                    viewModel.create(content, source) { success ->
                        creating = false
                        if (success) showNewEditor = false
                    }
                }
            },
        )
    }

    editorPoem?.let { poem ->
        PoemEditorDialog(
            poem = poem,
            saving = updatingId == poem.id,
            onDismiss = { if (updatingId == null) editorPoem = null },
            onConfirm = { content, source ->
                if (updatingId == null) {
                    updatingId = poem.id
                    viewModel.update(poem.id, content, source) { success ->
                        updatingId = null
                        if (success) editorPoem = null
                    }
                }
            },
        )
    }

    pendingDelete?.let { poem ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("删除这首诗词？", "Delete this poem?")) },
            text = {
                Text(
                    tr(
                        "删除后无法恢复。\n\n${poem.content.take(80)}",
                        "This cannot be undone.\n\n${poem.content.take(80)}",
                    ),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(poem.id)
                        pendingDelete = null
                    },
                ) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun EmptyPoetryBook(modifier: Modifier, onAdd: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(tr("诗词本还是空的", "Your poetry book is empty"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("收藏每日诗词，或手动写下喜欢的句子", "Save the daily poem or add a favorite verse"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            Button(onClick = onAdd) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("添加诗词", "Add poem"))
            }
        }
    }
}

@Composable
private fun SavedPoemCard(
    poem: SavedPoemEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onEdit, onLongClick = onEdit),
        cornerRadius = 20.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = "“",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = poem.content,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (poem.source.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "—— ${poem.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    )
                }
            }
            Column {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, tr("删除", "Delete"))
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, tr("编辑", "Edit"))
                }
            }
        }
    }
}

@Composable
private fun PoemEditorDialog(
    poem: SavedPoemEntity?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (content: String, source: String) -> Unit,
) {
    var content by remember(poem?.id) { mutableStateOf(poem?.content.orEmpty()) }
    var source by remember(poem?.id) { mutableStateOf(poem?.source.orEmpty()) }
    val focusManager = LocalFocusManager.current
    val sourceFocusRequester = remember { FocusRequester() }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (poem == null) tr("添加诗词", "Add poem") else tr("编辑诗词", "Edit poem")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(MAX_POEM_CONTENT_CHARS) },
                    label = { Text(tr("诗句", "Verse")) },
                    placeholder = { Text(tr("输入喜欢的诗句", "Enter a favorite verse")) },
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { sourceFocusRequester.requestFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it.take(MAX_POEM_SOURCE_CHARS) },
                    label = { Text(tr("出处（可选）", "Source (optional)")) },
                    placeholder = { Text(tr("例如：李白《静夜思》", "e.g. Li Bai, Quiet Night Thought")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(sourceFocusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank() && !saving,
                onClick = { onConfirm(content, source) },
            ) { Text(if (saving) tr("保存中…", "Saving…") else tr("保存", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(tr("取消", "Cancel")) }
        },
    )
}

private const val MAX_POEM_CONTENT_CHARS = 4_000
private const val MAX_POEM_SOURCE_CHARS = 512
