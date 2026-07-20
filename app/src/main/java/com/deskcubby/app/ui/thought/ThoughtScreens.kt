@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.deskcubby.app.ui.thought

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThoughtScreen(
    padding: PaddingValues,
    viewModel: ThoughtViewModel,
    onTrash: () -> Unit,
) {
    val activeState by viewModel.activeState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val items = remember(activeState.items, selectedCategory) {
        when (val category = selectedCategory) {
            ThoughtCategoryFilter.All -> activeState.items
            ThoughtCategoryFilter.Uncategorized -> activeState.items.filter { it.categoryId == null }
            is ThoughtCategoryFilter.Category -> activeState.items.filter { it.categoryId == category.id }
        }
    }
    val categoriesById = remember(categories) { categories.associateBy { it.id } }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    var selected by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    var editor by remember { mutableStateOf("") }
    var actionItem by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    var categorizingItem by remember { mutableStateOf<FlashThoughtEntity?>(null) }
    var showSendCategoryPicker by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<com.deskcubby.app.data.local.ThoughtCategoryEntity?>(null) }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboardOnListScroll = remember(focusManager, keyboardController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                }
                return Offset.Zero
            }
        }
    }
    val thoughtDateFormatter = remember { DateTimeFormatter.ofPattern("yyyy/M/d HH:mm") }

    LaunchedEffect(activeState.pendingScrollItemId, items) {
        activeState.pendingScrollItemId?.let { itemId ->
            val itemIndex = items.indexOfFirst { it.id == itemId }
            if (itemIndex >= 0) {
                listState.animateScrollToItem(itemIndex)
                viewModel.consumeScrollRequest(itemId)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ThoughtCategoryDrawer(
                categories = categories,
                thoughts = activeState.items,
                selected = selectedCategory,
                bottomPadding = padding.calculateBottomPadding(),
                onSelect = { category ->
                    viewModel.selectCategory(category)
                    selected = null
                    editor = ""
                    scope.launch { drawerState.close() }
                },
                onAdd = {
                    showAddCategory = true
                    scope.launch { drawerState.close() }
                },
                onEdit = { category ->
                    editingCategory = category
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            categoryLabel(
                                selected = selectedCategory,
                                categories = categories,
                                allLabel = tr("全部", "All"),
                                uncategorizedLabel = tr("未分类", "Uncategorized"),
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, tr("查看分类", "View categories"))
                        }
                        IconButton(onClick = onTrash) {
                            Icon(Icons.Outlined.Unarchive, tr("回收站", "Trash"))
                        }
                    },
                )
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner).imePadding().imeNestedScroll()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (items.isEmpty()) {
                            AppEmptyState(
                                icon = Icons.Outlined.Bolt,
                                title = if (selectedCategory == ThoughtCategoryFilter.All) {
                                    tr("记录此刻的想法", "Capture what is on your mind")
                                } else {
                                    tr("这个分类还是空的", "This category is empty")
                                },
                                description = if (selectedCategory == ThoughtCategoryFilter.All) {
                                    tr("在下方快速写一条小巧思。", "Write a quick thought below.")
                                } else {
                                    tr("可以在下方输入，长按发送按钮选择分类。", "Write below, then hold Send to choose a category.")
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().nestedScroll(dismissKeyboardOnListScroll),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().combinedClickable(
                                            onClick = { selected = item; editor = item.content },
                                            onLongClick = { actionItem = item },
                                        ),
                                    ) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .height(settings.thoughtRowHeightDp.dp)
                                                .padding(start = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (item.pinned) {
                                                Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary)
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            categoriesById[item.categoryId]?.let { category ->
                                                CategoryColorDot(category.colorArgb)
                                                Spacer(Modifier.width(8.dp))
                                            }
                                            Text(
                                                item.content,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
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
                                                onDragStarted = {
                                                    focusManager.clearFocus(force = true)
                                                    keyboardController?.hide()
                                                },
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

                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        if (selected != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tr("正在编辑一条小巧思", "Editing an existing thought"),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { selected = null; editor = "" }) {
                                    Icon(Icons.Outlined.Cancel, null)
                                    Text(tr("取消", "Cancel"))
                                }
                            }
                        }
                        OutlinedTextField(
                            value = editor,
                            onValueChange = { editor = it },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp),
                            placeholder = { Text(tr("此刻在想什么？", "What's on your mind?")) },
                            minLines = 1,
                            maxLines = 6,
                            shape = if (organic) visuals.featureShape else RoundedCornerShape(28.dp),
                            trailingIcon = {
                                ThoughtSendButton(
                                    enabled = editor.isNotBlank(),
                                    onClick = {
                                        viewModel.submit(selected?.id, editor) {
                                            selected = null
                                            editor = ""
                                        }
                                    },
                                    onLongClick = { showSendCategoryPicker = true },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    actionItem?.let { item ->
        val categoryName = categoriesById[item.categoryId]?.name ?: tr("未分类", "Uncategorized")
        val createdAt = Instant.ofEpochMilli(item.createdAt)
            .atZone(ZoneId.systemDefault())
            .format(thoughtDateFormatter)
        val updatedAt = Instant.ofEpochMilli(item.updatedAt)
            .atZone(ZoneId.systemDefault())
            .format(thoughtDateFormatter)
        AlertDialog(
            onDismissRequest = { actionItem = null },
            title = { Text(item.content, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text(tr("分类：", "Category: ") + categoryName)
                    Text(tr("创建：", "Created: ") + createdAt)
                    if (item.updatedAt != item.createdAt) {
                        Text(tr("更新：", "Updated: ") + updatedAt)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    TextButton(onClick = { viewModel.togglePinned(item.id); actionItem = null }) {
                        Icon(Icons.Outlined.PushPin, null); Spacer(Modifier.width(8.dp)); Text(if (item.pinned) tr("取消置顶", "Unpin") else tr("置顶", "Pin"))
                    }
                    TextButton(onClick = { categorizingItem = item; actionItem = null }) {
                        Icon(Icons.Outlined.DriveFileMove, null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("切换分类", "Change category"))
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

    categorizingItem?.let { item ->
        ThoughtCategoryPickerDialog(
            title = tr("切换分类", "Change category"),
            categories = categories,
            currentCategoryId = item.categoryId,
            onDismiss = { categorizingItem = null },
            onSelect = { category ->
                viewModel.setCategory(item.id, category)
                if (selected?.id == item.id) {
                    selected = null
                    editor = ""
                }
                categorizingItem = null
            },
        )
    }

    if (showSendCategoryPicker) {
        ThoughtCategoryPickerDialog(
            title = tr("发送到分类", "Send to category"),
            categories = categories,
            currentCategoryId = if (selected != null) {
                selected?.categoryId
            } else {
                selectedCategory.categoryIdOrNullForUi()
            },
            onDismiss = { showSendCategoryPicker = false },
            onSelect = { category ->
                showSendCategoryPicker = false
                viewModel.selectCategory(category)
                viewModel.submit(selected?.id, editor, category) {
                    selected = null
                    editor = ""
                }
            },
        )
    }

    if (showAddCategory) {
        ThoughtCategoryEditorDialog(
            category = null,
            categories = categories,
            onDismiss = { showAddCategory = false },
            onSave = { name, color, onResult -> viewModel.createCategory(name, color, onResult) },
        )
    }

    editingCategory?.let { category ->
        ThoughtCategoryEditorDialog(
            category = category,
            categories = categories,
            onDismiss = { editingCategory = null },
            onSave = { name, color, onResult ->
                viewModel.updateCategory(category.id, name, color, onResult)
            },
            onDelete = {
                viewModel.deleteCategory(it.id)
                editingCategory = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThoughtSendButton(
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    Surface(
        modifier = Modifier
            .size(48.dp)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        shape = if (organic) visuals.badgeShape else CircleShape,
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Send, tr("发送；长按选择分类", "Send; hold to choose category"))
        }
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
            AppEmptyState(
                icon = Icons.Outlined.DeleteSweep,
                title = tr("回收站为空", "Trash is empty"),
                description = tr("删除的小巧思会暂时保存在这里。", "Deleted thoughts will be kept here temporarily."),
                modifier = Modifier.fillMaxSize().padding(inner),
            )
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
