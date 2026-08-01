@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.poetry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.PoetryCategoryEntity
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.PoetryTextAlignment
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.repository.PoemEditContentStatus
import com.deskcubby.app.data.repository.PoetryPresetCategorySummary
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.ColorPickerDialog
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.tr
import kotlin.math.abs

@Composable
fun PoetryBookScreen(
    padding: PaddingValues,
    viewModel: PoetryBookViewModel,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
) {
    val poems by viewModel.poems.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val presetCategories by viewModel.presetCategories.collectAsStateWithLifecycle()
    val importingPresetId by viewModel.importingPresetId.collectAsStateWithLifecycle()
    val presetImportResult by viewModel.presetImportResult.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = when (error) {
        PoetryOperationFailure.LOAD_FOR_EDIT ->
            tr("无法加载完整诗词，编辑器未打开", "Could not load the full poem")
        PoetryOperationFailure.CREATE ->
            tr("诗词保存失败，内容未添加", "Could not save the poem")
        PoetryOperationFailure.UPDATE ->
            tr("诗词保存失败，原内容未被更新", "Could not update the poem")
        PoetryOperationFailure.DELETE ->
            tr("诗词删除失败，条目仍然保留", "Could not delete the poem")
        PoetryOperationFailure.CATEGORY ->
            tr("分类操作失败；同名分类不会重复创建", "Category operation failed; names must be unique")
        PoetryOperationFailure.PRESET_IMPORT ->
            tr("预设分类加载或导入失败", "Could not load or import the preset category")
        null -> null
    }
    val visiblePoems = remember(poems, selectedCategory) {
        when (val selected = selectedCategory) {
            PoetryCategoryFilter.All -> poems
            PoetryCategoryFilter.Uncategorized -> poems.filter { it.categoryId == null }
            is PoetryCategoryFilter.Category -> poems.filter { it.categoryId == selected.id }
        }
    }
    val categoriesById = remember(categories) { categories.associateBy(PoetryCategoryEntity::id) }
    var showNewEditor by remember { mutableStateOf(false) }
    var pendingActions by remember { mutableStateOf<SavedPoemEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedPoemEntity?>(null) }
    var pendingCategoryChange by remember { mutableStateOf<SavedPoemEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showAddCategoryChoice by remember { mutableStateOf(false) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<PoetryCategoryEntity?>(null) }
    var creatingCustomCategory by remember { mutableStateOf(false) }
    var pendingCategoryDelete by remember { mutableStateOf<PoetryCategoryEntity?>(null) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var draggingPoemId by remember { mutableStateOf<Long?>(null) }
    var draggingDistancePx by remember { mutableStateOf(0f) }
    var dragTargetIndex by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val poetryFontFamily = rememberPoetryFontFamily(settings.poetryFontUri)
    val presetImportMessage = presetImportResult?.let { result ->
        tr(
            "预设导入完成：新增 ${result.addedCount} 篇，跳过 ${result.existingCount} 篇重复内容",
            "Preset imported: ${result.addedCount} added, ${result.existingCount} duplicates skipped",
        )
    }

    LaunchedEffect(error, errorMessage) {
        if (error != null && errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(presetImportResult, presetImportMessage) {
        if (presetImportResult != null && presetImportMessage != null) {
            snackbarHostState.showSnackbar(presetImportMessage)
            viewModel.consumePresetImportResult()
        }
    }

    fun findDragTargetIndex(itemId: Long, verticalDistancePx: Float): Int? {
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val sourceInfo = visibleItems.firstOrNull { it.key == itemId } ?: return null
        val targetCenter = sourceInfo.offset + sourceInfo.size / 2f + verticalDistancePx
        val targetInfo = visibleItems.firstOrNull { info ->
            targetCenter >= info.offset && targetCenter <= info.offset + info.size
        } ?: visibleItems.minByOrNull { info ->
            abs(targetCenter - (info.offset + info.size / 2f))
        } ?: return null
        // LazyList layout information can briefly describe the previous order after Room emits a
        // reorder. Resolve its stable key back into the current list instead of trusting that
        // transient index; this is especially important for moves from or to index zero.
        val targetKey = targetInfo.key as? Long ?: return null
        return visiblePoems.indexOfFirst { it.id == targetKey }.takeIf { it >= 0 }
    }

    val dragSourceIndex = draggingPoemId?.let { id ->
        visiblePoems.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }
    val insertionSlot = dragSourceIndex?.let { sourceIndex ->
        dragTargetIndex?.let { targetIndex ->
            if (targetIndex > sourceIndex) targetIndex + 1 else targetIndex
        }
    }

    Scaffold(
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("诗词本", "Poetry book")) },
                actions = {
                    TextButton(
                        onClick = {
                            sorting = !sorting
                            draggingPoemId = null
                            draggingDistancePx = 0f
                            dragTargetIndex = null
                        },
                    ) {
                        Icon(
                            imageVector = if (sorting) Icons.Outlined.Check else Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (sorting) tr("完成", "Done") else tr("排序", "Sort"))
                    }
                    IconButton(onClick = { showCategoryManager = true }) {
                        Icon(Icons.Outlined.FolderOpen, tr("管理诗词分类", "Manage poetry categories"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, tr("诗词本设置", "Poetry book settings"))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.dismissEditor()
                    showNewEditor = true
                },
            ) {
                Icon(Icons.Outlined.Add, tr("添加诗词", "Add poem"))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            PoetryCategoryFilterBar(
                categories = categories,
                selected = selectedCategory,
                onSelect = viewModel::selectCategory,
                onManage = { showCategoryManager = true },
            )
            if (visiblePoems.isEmpty()) {
                EmptyPoetryBook(
                    filtered = poems.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onAdd = {
                        viewModel.dismissEditor()
                        showNewEditor = true
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 100.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(visiblePoems, key = { _, poem -> poem.id }) { index, poem ->
                        val isDragging = draggingPoemId == poem.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f),
                        ) {
                            SavedPoemCard(
                                poem = poem,
                                category = categoriesById[poem.categoryId],
                                loadingForEdit =
                                    (editorState as? PoetryEditorState.Loading)?.poemId == poem.id,
                                settings = settings,
                                fontFamily = poetryFontFamily,
                                sorting = sorting,
                                dragging = isDragging,
                                dragDistancePx = draggingDistancePx,
                                onShowActions = { pendingActions = poem },
                                onDragStarted = {
                                    draggingPoemId = poem.id
                                    draggingDistancePx = 0f
                                    dragTargetIndex = index
                                },
                                onDragChanged = { distance ->
                                    draggingDistancePx = distance
                                    dragTargetIndex = findDragTargetIndex(poem.id, distance)
                                },
                                onDragCancelled = {
                                    draggingPoemId = null
                                    draggingDistancePx = 0f
                                    dragTargetIndex = null
                                },
                                onDragFinished = { distance ->
                                    val targetIndex = findDragTargetIndex(poem.id, distance)
                                        ?: dragTargetIndex
                                    draggingPoemId = null
                                    draggingDistancePx = 0f
                                    dragTargetIndex = null
                                    if (targetIndex != null && targetIndex != index) {
                                        viewModel.move(poem.id, targetIndex, selectedCategory)
                                    }
                                },
                                onMoveUp = {
                                    if (index <= 0) false else {
                                        viewModel.move(poem.id, index - 1, selectedCategory)
                                        true
                                    }
                                },
                                onMoveDown = {
                                    if (index >= visiblePoems.lastIndex) false else {
                                        viewModel.move(poem.id, index + 1, selectedCategory)
                                        true
                                    }
                                },
                            )
                            if (draggingPoemId != null && insertionSlot == index) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(horizontal = 8.dp),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (index == visiblePoems.lastIndex && insertionSlot == visiblePoems.size) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(horizontal = 8.dp),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    pendingActions?.let { poem ->
        AlertDialog(
            onDismissRequest = { pendingActions = null },
            title = { Text(tr("诗词操作", "Poem actions")) },
            text = {
                Column {
                    DialogAction(
                        icon = Icons.Outlined.Edit,
                        label = tr("编辑", "Edit"),
                        onClick = {
                            pendingActions = null
                            viewModel.beginEdit(poem.id)
                        },
                    )
                    DialogAction(
                        icon = Icons.Outlined.Label,
                        label = tr("切换分类", "Change category"),
                        onClick = {
                            pendingActions = null
                            pendingCategoryChange = poem
                        },
                    )
                    DialogAction(
                        icon = Icons.Outlined.Delete,
                        label = tr("删除", "Delete"),
                        color = MaterialTheme.colorScheme.error,
                        onClick = {
                            pendingActions = null
                            pendingDelete = poem
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingActions = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }

    if (showNewEditor) {
        PoemEditorDialog(
            poem = null,
            categories = categories,
            initialCategoryId = selectedCategory.categoryIdOrNull(),
            saving = creating,
            onDismiss = { if (!creating) showNewEditor = false },
            onConfirm = { content, source, categoryId ->
                if (!creating) {
                    creating = true
                    viewModel.create(content, source, categoryId) { success ->
                        creating = false
                        if (success) showNewEditor = false
                    }
                }
            },
        )
    }

    (editorState as? PoetryEditorState.Ready)?.let { ready ->
        val notice = when (ready.draft.contentStatus) {
            PoemEditContentStatus.STORED_CONTENT -> null
            PoemEditContentStatus.EXPANDED_FROM_DAILY_CACHE -> tr(
                "已根据匹配的每日诗词缓存展开并载入完整正文。",
                "The complete poem was expanded from the daily-poetry cache.",
            )
            PoemEditContentStatus.LEGACY_CACHE_WITHOUT_FULL_CONTENT -> tr(
                "旧版缓存无法确认完整原文；请确认当前内容后再保存。",
                "The legacy cache could not confirm the full text; review before saving.",
            )
            PoemEditContentStatus.DAILY_CACHE_UNAVAILABLE -> tr(
                "每日诗词缓存暂时无法读取；已载入诗词本中现有内容。",
                "The daily-poetry cache is unavailable; the saved text was loaded.",
            )
            PoemEditContentStatus.CACHED_FULL_CONTENT_TOO_LONG -> tr(
                "缓存原文超过支持长度，未自动替换。",
                "The cached full text exceeds the supported length.",
            )
        }
        PoemEditorDialog(
            poem = ready.draft.poem,
            categories = categories,
            initialCategoryId = ready.draft.poem.categoryId,
            saving = ready.saving,
            notice = notice,
            noticeIsInformational =
                ready.draft.contentStatus == PoemEditContentStatus.EXPANDED_FROM_DAILY_CACHE,
            onDismiss = viewModel::dismissEditor,
            onConfirm = viewModel::saveEditor,
        )
    }

    pendingCategoryChange?.let { poem ->
        PoetryCategoryPickerDialog(
            categories = categories,
            currentCategoryId = poem.categoryId,
            onDismiss = { pendingCategoryChange = null },
            onSelect = { categoryId ->
                viewModel.setCategory(poem.id, categoryId)
                pendingCategoryChange = null
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

    if (showCategoryManager) {
        PoetryCategoryManagerDialog(
            categories = categories,
            onDismiss = { showCategoryManager = false },
            onAdd = {
                showCategoryManager = false
                showAddCategoryChoice = true
            },
            onEdit = {
                showCategoryManager = false
                editingCategory = it
            },
        )
    }
    if (showAddCategoryChoice) {
        AlertDialog(
            onDismissRequest = { showAddCategoryChoice = false },
            title = { Text(tr("添加分类", "Add category")) },
            text = {
                Column {
                    DialogAction(
                        icon = Icons.Outlined.Add,
                        label = tr("新建自定义分类", "Create custom category"),
                        onClick = {
                            showAddCategoryChoice = false
                            creatingCustomCategory = true
                        },
                    )
                    DialogAction(
                        icon = Icons.Outlined.MenuBook,
                        label = tr("选择初高中古诗文预设", "Choose a school poetry preset"),
                        onClick = {
                            showAddCategoryChoice = false
                            showPresetPicker = true
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddCategoryChoice = false }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
    if (showPresetPicker) {
        PoetryPresetPickerDialog(
            presets = presetCategories,
            importingPresetId = importingPresetId,
            onDismiss = { if (importingPresetId == null) showPresetPicker = false },
            onImport = { presetId ->
                viewModel.importPresetCategory(presetId)
                showPresetPicker = false
            },
        )
    }
    if (creatingCustomCategory) {
        PoetryCategoryEditorDialog(
            category = null,
            categories = categories,
            onDismiss = { creatingCustomCategory = false },
            onSave = { name, color, onResult ->
                viewModel.createCategory(name, color) { success ->
                    onResult(success)
                    if (success) creatingCustomCategory = false
                }
            },
        )
    }
    editingCategory?.let { category ->
        PoetryCategoryEditorDialog(
            category = category,
            categories = categories,
            onDismiss = { editingCategory = null },
            onSave = { name, color, onResult ->
                viewModel.updateCategory(category.id, name, color) { success ->
                    onResult(success)
                    if (success) editingCategory = null
                }
            },
            onDelete = {
                editingCategory = null
                pendingCategoryDelete = category
            },
        )
    }
    pendingCategoryDelete?.let { category ->
        val poemCount = poems.count { it.categoryId == category.id }
        AlertDialog(
            onDismissRequest = { pendingCategoryDelete = null },
            title = { Text(tr("删除分类？", "Delete category?")) },
            text = {
                Text(
                    tr(
                        "“${category.name}”中有 $poemCount 首诗词。请选择保留诗词并归入“未分类”，或将分类和其中诗词一起永久删除。",
                        "“${category.name}” contains $poemCount poems. Keep them as uncategorized, or permanently delete the category and its poems.",
                    ),
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = {
                            viewModel.deleteCategory(category.id, deletePoems = false)
                            pendingCategoryDelete = null
                        },
                    ) {
                        Text(tr("仅删除分类（诗词变为未分类）", "Delete category only"))
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteCategory(category.id, deletePoems = true)
                            pendingCategoryDelete = null
                        },
                    ) {
                        Text(tr("分类和诗词一起删除", "Delete category and poems"))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCategoryDelete = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun PoetryCategoryFilterBar(
    categories: List<PoetryCategoryEntity>,
    selected: PoetryCategoryFilter,
    onSelect: (PoetryCategoryFilter) -> Unit,
    onManage: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selected == PoetryCategoryFilter.All,
            onClick = { onSelect(PoetryCategoryFilter.All) },
            label = { Text(tr("全部", "All")) },
        )
        FilterChip(
            selected = selected == PoetryCategoryFilter.Uncategorized,
            onClick = { onSelect(PoetryCategoryFilter.Uncategorized) },
            label = { Text(tr("未分类", "Uncategorized")) },
        )
        categories.forEach { category ->
            FilterChip(
                selected = selected == PoetryCategoryFilter.Category(category.id),
                onClick = { onSelect(PoetryCategoryFilter.Category(category.id)) },
                leadingIcon = { CategoryColorDot(category.colorArgb) },
                label = { Text(category.name) },
            )
        }
        FilterChip(
            selected = false,
            onClick = onManage,
            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            label = { Text(tr("分类", "Categories")) },
        )
    }
}

@Composable
private fun EmptyPoetryBook(
    filtered: Boolean,
    modifier: Modifier,
    onAdd: () -> Unit,
) {
    AppEmptyState(
        icon = Icons.Outlined.MenuBook,
        title = if (filtered) {
            tr("这个分类还是空的", "This category is empty")
        } else {
            tr("诗词本还是空的", "Your poetry book is empty")
        },
        description = if (filtered) {
            tr("添加诗词，或切换到其他分类", "Add a poem or choose another category")
        } else {
            tr(
                "收藏每日诗词、手动添加，或从初高中预设导入",
                "Save a daily poem, add one manually, or import a school preset",
            )
        },
        actionLabel = tr("添加诗词", "Add poem"),
        onAction = onAdd,
        modifier = modifier,
    )
}

@Composable
private fun SavedPoemCard(
    poem: SavedPoemEntity,
    category: PoetryCategoryEntity?,
    loadingForEdit: Boolean,
    settings: AppSettings,
    fontFamily: FontFamily?,
    sorting: Boolean,
    dragging: Boolean,
    dragDistancePx: Float,
    onShowActions: () -> Unit,
    onDragStarted: () -> Unit,
    onDragChanged: (Float) -> Unit,
    onDragCancelled: () -> Unit,
    onDragFinished: (Float) -> Unit,
    onMoveUp: () -> Boolean,
    onMoveDown: () -> Boolean,
) {
    val textAlignment = when (settings.poetryTextAlignment) {
        PoetryTextAlignment.START -> TextAlign.Start
        PoetryTextAlignment.CENTER -> TextAlign.Center
    }
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = if (dragging) dragDistancePx else 0f
                alpha = if (dragging) 0.62f else 1f
            }
            .combinedClickable(
                enabled = !loadingForEdit && !sorting,
                onLongClickLabel = tr("显示编辑、分类和删除操作", "Show edit, category, and delete actions"),
                onClick = {},
                onLongClick = onShowActions,
            ),
        cornerRadius = 20.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
    ) {
        if (sorting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val title = poetryTitleFromSource(poem.source)
                Text(
                    text = buildString {
                        if (title.isNotBlank()) append("《$title》 ")
                        append(poem.content.replace(Regex("\\s+"), " ").trim())
                    },
                    modifier = Modifier.weight(1f),
                    fontFamily = fontFamily,
                    fontSize = settings.poetryFontSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FourDotDragHandle(
                    enabled = !loadingForEdit,
                    translateSelf = false,
                    onDragStarted = onDragStarted,
                    onDragChanged = onDragChanged,
                    onDragCancelled = onDragCancelled,
                    onMoveUp = onMoveUp,
                    onMoveDown = onMoveDown,
                    onDragFinished = onDragFinished,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                if (settings.poetryShowQuoteMark) {
                    Text(
                        text = "“",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    category?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryColorDot(it.colorArgb)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                it.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    Text(
                        text = if (
                            settings.poetrySevenCharacterWrapEnabled &&
                            isSevenCharacterPoem(poem.content)
                        ) {
                            wrapSevenCharacterVerse(poem.content)
                        } else {
                            poem.content
                        },
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = fontFamily,
                        fontSize = settings.poetryFontSizeSp.sp,
                        lineHeight = (settings.poetryFontSizeSp * settings.poetryLineSpacing).sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = textAlignment,
                    )
                    if (settings.poetryShowSource && poem.source.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "—— ${poem.source}",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = fontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = FontStyle.Italic,
                            textAlign = textAlignment,
                        )
                    }
                    if (loadingForEdit) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.End),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PoemEditorDialog(
    poem: SavedPoemEntity?,
    categories: List<PoetryCategoryEntity>,
    initialCategoryId: Long?,
    saving: Boolean,
    notice: String? = null,
    noticeIsInformational: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (content: String, source: String, categoryId: Long?) -> Unit,
) {
    var content by remember(poem?.id, poem?.content) { mutableStateOf(poem?.content.orEmpty()) }
    var source by remember(poem?.id, poem?.source) { mutableStateOf(poem?.source.orEmpty()) }
    var categoryId by remember(poem?.id, initialCategoryId) { mutableStateOf(initialCategoryId) }
    var showCategoryPicker by remember(poem?.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val sourceFocusRequester = remember { FocusRequester() }
    if (showCategoryPicker) {
        PoetryCategoryPickerDialog(
            categories = categories,
            currentCategoryId = categoryId,
            onDismiss = { showCategoryPicker = false },
            onSelect = {
                categoryId = it
                showCategoryPicker = false
            },
        )
        return
    }
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
                    label = { Text(tr("诗词正文", "Poem text")) },
                    placeholder = { Text(tr("输入完整诗词正文", "Enter the complete poem text")) },
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
                TextButton(
                    onClick = { showCategoryPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Label, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tr("分类：", "Category: ") +
                            (categories.firstOrNull { it.id == categoryId }?.name
                                ?: tr("未分类", "Uncategorized")),
                    )
                }
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (noticeIsInformational) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank() && !saving,
                onClick = { onConfirm(content, source, categoryId) },
            ) { Text(if (saving) tr("保存中…", "Saving…") else tr("保存", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(tr("取消", "Cancel")) }
        },
    )
}

@Composable
private fun PoetryCategoryPickerDialog(
    categories: List<PoetryCategoryEntity>,
    currentCategoryId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("选择分类", "Choose category")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CategoryPickerRow(
                    label = tr("未分类", "Uncategorized"),
                    selected = currentCategoryId == null,
                    onClick = { onSelect(null) },
                )
                categories.forEach { category ->
                    CategoryPickerRow(
                        label = category.name,
                        selected = currentCategoryId == category.id,
                        colorArgb = category.colorArgb,
                        onClick = { onSelect(category.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

@Composable
private fun PoetryCategoryManagerDialog(
    categories: List<PoetryCategoryEntity>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (PoetryCategoryEntity) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("诗词分类", "Poetry categories")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                DialogAction(
                    icon = Icons.Outlined.Add,
                    label = tr("添加分类", "Add category"),
                    onClick = onAdd,
                )
                if (categories.isEmpty()) {
                    Text(
                        tr("还没有分类", "No categories yet"),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(category) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryColorDot(category.colorArgb)
                        Spacer(Modifier.width(10.dp))
                        Text(category.name, Modifier.weight(1f))
                        Icon(Icons.Outlined.Edit, contentDescription = tr("编辑", "Edit"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("完成", "Done")) }
        },
    )
}

@Composable
private fun PoetryCategoryEditorDialog(
    category: PoetryCategoryEntity?,
    categories: List<PoetryCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, Int, (Boolean) -> Unit) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val palette = if (organic) POETRY_ORGANIC_CATEGORY_COLORS else POETRY_CATEGORY_COLORS
    var name by remember(category?.id) { mutableStateOf(category?.name.orEmpty()) }
    var colorArgb by remember(category?.id, organic) {
        mutableIntStateOf(category?.colorArgb ?: palette.first())
    }
    var saving by remember(category?.id) { mutableStateOf(false) }
    var showColorPicker by remember(category?.id) { mutableStateOf(false) }
    val duplicate = categories.any {
        it.id != category?.id && it.name.equals(name.trim(), ignoreCase = true)
    }
    val availableColors = remember(category?.colorArgb, organic, colorArgb) {
        listOfNotNull(category?.colorArgb).plus(palette).plus(colorArgb).distinct()
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(if (category == null) tr("新增分类", "New category") else tr("编辑分类", "Edit category"))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text(tr("分类名称", "Category name")) },
                    supportingText = if (duplicate) {
                        { Text(tr("已有同名分类", "A category with this name already exists")) }
                    } else {
                        null
                    },
                    isError = duplicate,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("分类颜色", "Category color"), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    availableColors.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(color), CircleShape)
                                .border(
                                    width = if (colorArgb == color) 3.dp else 1.dp,
                                    color = if (colorArgb == color) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .combinedClickable(onClick = { colorArgb = color }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (colorArgb == color) {
                                val swatch = Color(color)
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = tr("已选择", "Selected"),
                                    tint = if (swatch.luminance() > 0.42f) {
                                        Color.Black
                                    } else {
                                        Color.White
                                    },
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .combinedClickable(onClick = { showColorPicker = true }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Palette,
                            tr("自定义颜色", "Custom color"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (category != null && onDelete != null) {
                    TextButton(onClick = onDelete, enabled = !saving) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("删除分类", "Delete category"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !duplicate && !saving,
                onClick = {
                    saving = true
                    onSave(name, colorArgb) { success ->
                        saving = false
                        if (!success) return@onSave
                    }
                },
            ) { Text(if (saving) tr("保存中…", "Saving…") else tr("保存", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(tr("取消", "Cancel")) }
        },
    )
    if (showColorPicker) {
        ColorPickerDialog(
            initialColorArgb = colorArgb,
            onDismiss = { showColorPicker = false },
            onConfirm = { picked ->
                colorArgb = picked
                showColorPicker = false
            },
            title = tr("分类颜色", "Category color"),
        )
    }
}

@Composable
private fun PoetryPresetPickerDialog(
    presets: List<PoetryPresetCategorySummary>,
    importingPresetId: String?,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("初高中古诗文预设", "School poetry presets")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    tr(
                        "按教材册次导入；重复导入会跳过已有内容。教材版本调整时篇目可能有差异。",
                        "Import by textbook volume. Existing entries are skipped; selections can vary by edition.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (presets.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
                presets.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                enabled = importingPresetId == null,
                                onClick = { onImport(preset.id) },
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CategoryColorDot(preset.colorArgb)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(preset.nameZh, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${preset.nameEn} · ${preset.itemCount} " + tr("篇", "items"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (importingPresetId == preset.id) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Add, contentDescription = tr("导入", "Import"))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = importingPresetId == null) {
                Text(tr("取消", "Cancel"))
            }
        },
    )
}

@Composable
private fun CategoryPickerRow(
    label: String,
    selected: Boolean,
    colorArgb: Int? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colorArgb?.let {
            CategoryColorDot(it)
            Spacer(Modifier.width(9.dp))
        }
        Text(label, Modifier.weight(1f))
        if (selected) Icon(Icons.Outlined.Check, tr("当前分类", "Current category"))
    }
}

@Composable
private fun CategoryColorDot(colorArgb: Int) {
    Box(
        Modifier
            .size(12.dp)
            .background(Color(colorArgb), CircleShape),
    )
}

@Composable
private fun DialogAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, modifier = Modifier.weight(1f))
    }
}

private fun PoetryCategoryFilter.categoryIdOrNull(): Long? =
    (this as? PoetryCategoryFilter.Category)?.id

private val POETRY_CATEGORY_COLORS = listOf(
    0xFFE05252.toInt(),
    0xFFEB8C3A.toInt(),
    0xFFE0B72F.toInt(),
    0xFF4E9A62.toInt(),
    0xFF3C9A9A.toInt(),
    0xFF4C78C2.toInt(),
    0xFF8166C2.toInt(),
    0xFFC45E91.toInt(),
    0xFF7B716A.toInt(),
)
private val POETRY_ORGANIC_CATEGORY_COLORS = listOf(
    0xFFC76B5C.toInt(),
    0xFFCA8B45.toInt(),
    0xFF9D8A45.toInt(),
    0xFF5D9168.toInt(),
    0xFF4F8F8A.toInt(),
    0xFF4F76A1.toInt(),
    0xFF7166A4.toInt(),
    0xFF98639A.toInt(),
)
private const val MAX_POEM_CONTENT_CHARS = 4_000
private const val MAX_POEM_SOURCE_CHARS = 512
