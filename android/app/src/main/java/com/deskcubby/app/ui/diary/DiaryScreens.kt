@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.diary

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.text.HtmlCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.MealCategory
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.mealPhotoRowSizes
import com.deskcubby.app.data.repository.MealCalendarPhoto
import com.deskcubby.app.data.repository.MealCalendarDay
import com.deskcubby.app.data.repository.MAX_MEAL_ENERGY_KJ
import com.deskcubby.app.data.repository.MAX_MEAL_NOTE_CHARS
import com.deskcubby.app.data.repository.DiaryPreviewMedia
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.components.OrganicSplitActionRow
import com.deskcubby.app.ui.components.OrganicSplitActionRowSize
import com.deskcubby.app.ui.components.ZoomableImageDialog
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.organicFutureAccentColors
import com.deskcubby.app.ui.theme.tr
import com.deskcubby.app.ui.diary.filter.asComposeColorFilter
import com.deskcubby.app.ui.diary.filter.mealCalendarExportLayout
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiaryListScreen(
    padding: PaddingValues,
    viewModel: DiaryViewModel,
    onOpen: (String) -> Unit,
    onOpenToday: () -> Unit,
    onOpenMealCalendar: () -> Unit,
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
                    IconButton(onClick = onOpenMealCalendar) {
                        Icon(Icons.Outlined.CalendarMonth, tr("吃历", "Meal calendar"))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenToday,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
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
                state.byMonth.entries.forEachIndexed { monthIndex, (month, diaries) ->
                    item(key = month) {
                        if (organic) {
                            val accentColors = organicFutureAccentColors
                            val accent = accentColors[monthIndex.mod(accentColors.size)]
                            OrganicSplitActionRow(
                                modifier = Modifier.fillMaxWidth(),
                                size = OrganicSplitActionRowSize.COMPACT,
                                bodyColor = accent,
                                actionColor = MaterialTheme.colorScheme.primary,
                                onBodyClick = { viewModel.toggleExpandedMonth(month) },
                                onActionClick = { viewModel.toggleExpandedMonth(month) },
                                bodyClickLabel = tr("展开或收起 $month", "Expand or collapse $month"),
                                actionClickLabel = tr("展开或收起 $month", "Expand or collapse $month"),
                                body = {
                                    Text(month, style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        tr("${diaries.size} 篇", "${diaries.size} entries"),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                },
                                action = {
                                    Icon(
                                        if (expandedMonth == month) Icons.Outlined.ExpandLess
                                        else Icons.Outlined.ExpandMore,
                                        contentDescription = null,
                                    )
                                },
                            )
                        } else {
                            // Secondary colors rotate through month rows in every style,
                            // not just Organic Future.
                            val accentColors = organicFutureAccentColors
                            val accent = accentColors[monthIndex.mod(accentColors.size)]
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
                                        color = accent,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Outlined.MenuBook, null, tint = accent)
                                }
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
fun MealCalendarScreen(
    viewModel: DiaryViewModel,
    onBack: () -> Unit,
    filterSettings: MealPhotoFilterSettings = MealPhotoFilterSettings(),
    onFilterEnabledChange: (Boolean) -> Unit = {},
    onOpenFilterSettings: () -> Unit = {},
    onOpenCalorieProgress: () -> Unit = {},
) {
    val state by viewModel.mealCalendarState.collectAsStateWithLifecycle()
    val exporting by viewModel.mealCalendarExporting.collectAsStateWithLifecycle()
    val calorieQueueState by viewModel.calorieEstimationQueueState.collectAsStateWithLifecycle()
    val operationMessage by viewModel.message.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mealOperationsEnabled = !exporting && !state.loading
    val snackbarHostState = remember { SnackbarHostState() }
    val normalizedFilterSettings = remember(filterSettings) { filterSettings.normalized() }
    val mealPhotoColorFilter = remember(normalizedFilterSettings) {
        normalizedFilterSettings.asComposeColorFilter()
    }
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    var calculateAllDialog by remember { mutableStateOf(false) }
    var calculateDateDialog by remember { mutableStateOf<String?>(null) }
    var energyDetailsDate by rememberSaveable { mutableStateOf<String?>(null) }
    var zoomedPhoto by remember { mutableStateOf<MealCalendarPhoto?>(null) }
    var showCategoryFilter by remember { mutableStateOf(false) }
    var showExportRange by remember { mutableStateOf(false) }
    // The system document picker may recreate the Activity. Keep the pending request in
    // Bundle-safe primitives so its result never becomes an unexplained empty document.
    var pendingExportStartIso by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportEndIso by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportCategories by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleMealCategories by remember { mutableStateOf(MealCategory.entries.toSet()) }
    val categoryFilterActive = visibleMealCategories.size < MealCategory.entries.size
    val filteredItems = remember(state.items, visibleMealCategories) {
        if (!categoryFilterActive) {
            state.items
        } else {
            state.items.mapNotNull { day ->
                day.photos
                    .filter { it.category in visibleMealCategories }
                    .takeIf(List<MealCalendarPhoto>::isNotEmpty)
                    ?.let { day.copy(photos = it) }
            }
        }
    }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val start = pendingExportStartIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val end = pendingExportEndIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val categoryNames = pendingExportCategories
            ?.split(',')
            ?.filter(String::isNotBlank)
            ?.toSet()
            .orEmpty()
        val categories = MealCategory.entries.filterTo(mutableSetOf()) {
            it.name in categoryNames
        }
        pendingExportStartIso = null
        pendingExportEndIso = null
        pendingExportCategories = null
        if (uri != null && start != null && end != null && categories.isNotEmpty()) {
            viewModel.exportMealCalendar(
                destinationUri = uri,
                startInclusive = start,
                endInclusive = end,
                categories = categories,
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshMealCalendar()
    }
    LaunchedEffect(operationMessage) {
        operationMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(tr("吃历", "Meal calendar")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
                    }
                },
                actions = {
                    if (settings.calorieEstimationEnabled || calorieQueueState.items.isNotEmpty()) {
                        CalorieEstimateToolbarButton(
                            calculationEnabled = mealOperationsEnabled &&
                                settings.calorieEstimationEnabled,
                            queueState = calorieQueueState,
                            onCalculate = { calculateAllDialog = true },
                            onOpenProgress = onOpenCalorieProgress,
                        )
                    }
                    IconButton(
                        enabled = mealOperationsEnabled && filteredItems.isNotEmpty(),
                        onClick = { showExportRange = true },
                    ) {
                        Icon(Icons.Outlined.FileDownload, tr("导出长图", "Export long image"))
                    }
                    IconButton(onClick = { showCategoryFilter = true }) {
                        Icon(
                            Icons.Outlined.FilterAlt,
                            tr("筛选餐别", "Filter meal categories"),
                            tint = if (categoryFilterActive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    MealFilterToolbarButton(
                        enabled = normalizedFilterSettings.enabled,
                        onToggle = {
                            onFilterEnabledChange(!normalizedFilterSettings.enabled)
                        },
                        onOpenSettings = onOpenFilterSettings,
                    )
                    IconButton(
                        enabled = mealOperationsEnabled,
                        onClick = viewModel::forceRefreshMealCalendar,
                    ) {
                        Icon(Icons.Outlined.Refresh, tr("刷新", "Refresh"))
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner).navigationBarsPadding()) {
            when {
                state.loading && state.items.isEmpty() -> {
                    AppLoadingIndicator(Modifier.align(Alignment.Center))
                }
                state.error != null && state.items.isEmpty() -> {
                    AppEmptyState(
                        icon = Icons.Outlined.Image,
                        title = tr("无法读取吃历", "Could not load meal calendar"),
                        description = state.error.orEmpty(),
                        actionLabel = tr("重试", "Retry"),
                        onAction = viewModel::forceRefreshMealCalendar,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.items.isEmpty() -> {
                    AppEmptyState(
                        icon = Icons.Outlined.Image,
                        title = tr("还没有饮食照片", "No meal photos yet"),
                        description = tr(
                            "日记中标注为早餐、午餐、晚餐、水果或夜宵的照片会出现在这里。",
                            "Photos captioned Breakfast, Lunch, Dinner, Fruit, or Late snack will appear here.",
                        ),
                        actionLabel = tr("刷新", "Refresh"),
                        onAction = viewModel::forceRefreshMealCalendar,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        state.error?.let { error ->
                            item(key = "meal-calendar-error") {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    Text(error, modifier = Modifier.padding(12.dp))
                                }
                            }
                        }
                        if (categoryFilterActive && filteredItems.isEmpty()) {
                            item(key = "meal-calendar-filter-empty") {
                                Text(
                                    tr("当前筛选下没有照片", "No photos match the current filter"),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(filteredItems, key = { it.dateIso }) { day ->
                            // Category filtering only changes the photo wall. The date-level
                            // override, note, and details always belong to the complete day.
                            val canonicalDay = state.items.firstOrNull { it.dateIso == day.dateIso }
                                ?: day
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(day.dateIso, style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    canonicalDay.totalEnergyKj?.let { energy ->
                                        TextButton(
                                            enabled = mealOperationsEnabled,
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            onClick = { energyDetailsDate = canonicalDay.dateIso },
                                        ) {
                                            Text(
                                                "·  $energy kJ",
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    if (settings.calorieEstimationEnabled) {
                                        IconButton(
                                            enabled = mealOperationsEnabled,
                                            onClick = { calculateDateDialog = day.dateIso },
                                        ) {
                                            Icon(
                                                Icons.Outlined.Calculate,
                                                tr(
                                                    "重新计算 ${day.dateIso} 的热量",
                                                    "Recalculate ${day.dateIso}",
                                                ),
                                            )
                                        }
                                    }
                                }
                                if (settings.mealCalendarWrapEnabled) {
                                    // Wrapped rows live inside the outer LazyColumn, so plain
                                    // Rows are required here instead of a nested LazyRow.
                                    val rowSizes = mealPhotoRowSizes(
                                        count = day.photos.size,
                                        mode = settings.mealCalendarPhotosPerRow,
                                    )
                                    val slotsPerRow = when (settings.mealCalendarPhotosPerRow) {
                                        MealPhotosPerRow.TWO -> 2
                                        MealPhotosPerRow.THREE -> 3
                                        MealPhotosPerRow.SMART -> 0
                                    }
                                    var photoOffset = 0
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        rowSizes.forEach { rowSize ->
                                            val rowPhotos = day.photos.subList(photoOffset, photoOffset + rowSize)
                                            photoOffset += rowSize
                                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                rowPhotos.forEach { photo ->
                                                    MealPhotoCard(
                                                        photo = photo,
                                                        settings = settings,
                                                        colorFilter = mealPhotoColorFilter,
                                                        organic = organic,
                                                        mediaShape = visuals.mediaShape,
                                                        modifier = Modifier.weight(1f),
                                                        onClick = { zoomedPhoto = photo },
                                                    )
                                                }
                                                repeat(
                                                    (slotsPerRow - rowSize).coerceAtLeast(0),
                                                ) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    }
                                } else {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        itemsIndexed(
                                            items = day.photos,
                                            key = { index, photo -> "${photo.uri}#$index" },
                                        ) { _, photo ->
                                            MealPhotoCard(
                                                photo = photo,
                                                settings = settings,
                                                colorFilter = mealPhotoColorFilter,
                                                organic = organic,
                                                mediaShape = visuals.mediaShape,
                                                modifier = Modifier.width(148.dp),
                                                onClick = { zoomedPhoto = photo },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                    }
                }
            }
            if (exporting) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
    if (showCategoryFilter) {
        AlertDialog(
            onDismissRequest = { showCategoryFilter = false },
            title = { Text(tr("筛选餐别", "Filter meal categories")) },
            text = {
                Column {
                    MealCategory.entries.forEach { category ->
                        val checked = category in visibleMealCategories
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = {
                                    visibleMealCategories = if (checked) {
                                        visibleMealCategories - category
                                    } else {
                                        visibleMealCategories + category
                                    }
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    visibleMealCategories = if (isChecked) {
                                        visibleMealCategories + category
                                    } else {
                                        visibleMealCategories - category
                                    }
                                },
                            )
                            Text(tr(category.chineseLabel, category.englishLabel))
                        }
                    }
                    Text(
                        tr("全部取消时不显示任何照片。", "Unchecking everything hides all photos."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryFilter = false }) { Text(tr("完成", "Done")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { visibleMealCategories = MealCategory.entries.toSet() },
                ) { Text(tr("全部显示", "Show all")) }
            },
        )
    }
    if (showExportRange) {
        val availableDates = filteredItems.mapNotNull { day ->
            runCatching { LocalDate.parse(day.dateIso) }.getOrNull()
        }
        val defaultStart = availableDates.minOrNull()
        val defaultEnd = availableDates.maxOrNull()
        val noPhotosError = tr(
            "所选日期和餐别下没有照片",
            "No photos match this range and category filter",
        )
        val tooTallError = tr(
            "所选范围生成的长图过高，请缩短日期范围",
            "The resulting image is too tall; choose a shorter range",
        )
        if (defaultStart != null && defaultEnd != null) {
            MealCalendarExportRangeDialog(
                initialStart = defaultStart,
                initialEnd = defaultEnd,
                selectedCategoryCount = visibleMealCategories.size,
                actionsEnabled = mealOperationsEnabled,
                onDismiss = { showExportRange = false },
                onConfirm = { start, end ->
                    val selectedDays = filteredItems.filter { day ->
                        val date = runCatching { LocalDate.parse(day.dateIso) }.getOrNull()
                        date != null && !date.isBefore(start) && !date.isAfter(end)
                    }
                    val preflightError = when {
                        selectedDays.isEmpty() -> noPhotosError
                        runCatching {
                            mealCalendarExportLayout(
                                photoCounts = selectedDays.map { it.photos.size },
                                imageMaxHeight = settings.mealCalendarImageMaxHeightDp,
                                showCaptions = settings.mealCalendarShowCaptions,
                                photosPerRow = settings.mealCalendarPhotosPerRow,
                            )
                        }.isFailure -> tooTallError
                        else -> null
                    }
                    if (preflightError == null) {
                        showExportRange = false
                        pendingExportStartIso = start.toString()
                        pendingExportEndIso = end.toString()
                        pendingExportCategories = visibleMealCategories
                            .sortedBy(MealCategory::sortOrder)
                            .joinToString(",") { it.name }
                        exportDocumentLauncher.launch(
                            "DeskCubby-meals-${start}_to_${end}.png",
                        )
                    }
                    preflightError
                },
            )
        } else {
            LaunchedEffect(Unit) { showExportRange = false }
        }
    }
    if (calculateAllDialog) AlertDialog(
        onDismissRequest = { calculateAllDialog = false },
        title = { Text(tr("计算热量", "Calculate calories")) },
        text = { Text(tr("是否计算所有未计算过的热量", "Calculate all calories not calculated yet?")) },
        confirmButton = {
            TextButton(
                enabled = mealOperationsEnabled,
                onClick = {
                    calculateAllDialog = false
                    viewModel.calculateUncalculatedCalories()
                },
            ) { Text(tr("计算", "Calculate")) }
        },
        dismissButton = { TextButton(onClick = { calculateAllDialog = false }) { Text(tr("取消", "Cancel")) } },
    )
    calculateDateDialog?.let { date -> AlertDialog(
        onDismissRequest = { calculateDateDialog = null },
        title = { Text(tr("重新计算热量", "Recalculate calories")) },
        text = { Text(tr("是否重新计算${date}的食物热量", "Recalculate food calories for $date?")) },
        confirmButton = {
            TextButton(
                enabled = mealOperationsEnabled,
                onClick = {
                    calculateDateDialog = null
                    viewModel.calculateUncalculatedCalories(date, true)
                },
            ) { Text(tr("重新计算", "Recalculate")) }
        },
        dismissButton = { TextButton(onClick = { calculateDateDialog = null }) { Text(tr("取消", "Cancel")) } },
    ) }
    energyDetailsDate?.let { dateIso ->
        val completeDay = state.items.firstOrNull { it.dateIso == dateIso }
        if (completeDay == null) {
            LaunchedEffect(dateIso) { energyDetailsDate = null }
        } else {
            MealEnergyDetailsDialog(
                day = completeDay,
                calorieEstimationEnabled = settings.calorieEstimationEnabled,
                actionsEnabled = mealOperationsEnabled && calorieQueueState.items.none {
                    it.dateIso == dateIso && !it.isTerminal
                },
                onDismiss = { energyDetailsDate = null },
                onSave = { totalOverride, note ->
                    energyDetailsDate = null
                    viewModel.saveMealDayDetails(dateIso, totalOverride, note)
                },
                onRecalculate = { note ->
                    energyDetailsDate = null
                    viewModel.calculateUncalculatedCalories(
                        dateIso = dateIso,
                        force = true,
                        noteOverride = note,
                    )
                },
            )
        }
    }
    zoomedPhoto?.let { photo ->
        val zoomCaption = photo.caption.ifBlank {
            tr(photo.category.chineseLabel, photo.category.englishLabel)
        }
        ZoomableImageDialog(
            model = photo.uri,
            contentDescription = zoomCaption,
            colorFilter = mealPhotoColorFilter,
            caption = listOfNotNull(
                zoomCaption,
                photo.energyKj?.let { "$it kJ" },
                photo.locationName,
            ).joinToString(" · "),
            onDismiss = { zoomedPhoto = null },
        )
    }
}

@Composable
private fun MealCalendarExportRangeDialog(
    initialStart: LocalDate,
    initialEnd: LocalDate,
    selectedCategoryCount: Int,
    actionsEnabled: Boolean,
    onDismiss: () -> Unit,
    /** Returns a user-facing validation error, or null after launching CreateDocument. */
    onConfirm: (LocalDate, LocalDate) -> String?,
) {
    var startIso by rememberSaveable { mutableStateOf(initialStart.toString()) }
    var endIso by rememberSaveable { mutableStateOf(initialEnd.toString()) }
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    var selectionError by rememberSaveable { mutableStateOf<String?>(null) }
    val start = remember(startIso) { LocalDate.parse(startIso) }
    val end = remember(endIso) { LocalDate.parse(endIso) }
    val reversed = start.isAfter(end)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("导出吃历长图", "Export meal-calendar image")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr(
                        "选择按天计算的时间范围，首尾日期都会包含。导出会沿用当前餐别筛选、照片滤镜、每行数量和说明显示设置。",
                        "Choose an inclusive day range. The export uses the current meal-category filter, photo filter, row count, and caption setting.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { pickingStart = true },
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("开始：$start", "Start: $start"))
                }
                OutlinedButton(
                    onClick = { pickingEnd = true },
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("结束：$end", "End: $end"))
                }
                Text(
                    tr(
                        "当前已选择 $selectedCategoryCount 个餐别",
                        "$selectedCategoryCount meal categories selected",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (reversed) {
                    Text(
                        tr("开始日期不能晚于结束日期", "The start date cannot be after the end date"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    selectionError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = actionsEnabled && !reversed,
                onClick = { selectionError = onConfirm(start, end) },
            ) { Text(tr("选择保存位置", "Choose destination")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )

    if (pickingStart) {
        MealCalendarDatePickerDialog(
            initialDate = start,
            onDismiss = { pickingStart = false },
            onConfirm = {
                startIso = it.toString()
                selectionError = null
                pickingStart = false
            },
        )
    }
    if (pickingEnd) {
        MealCalendarDatePickerDialog(
            initialDate = end,
            onDismiss = { pickingEnd = false },
            onConfirm = {
                endIso = it.toString()
                selectionError = null
                pickingEnd = false
            },
        )
    }
}

@Composable
private fun MealCalendarDatePickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = pickerState.selectedDateMillis
                        ?.let { millis ->
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        ?: initialDate
                    onConfirm(selected)
                },
            ) { Text(tr("确定", "OK")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    ) {
        DatePicker(state = pickerState, showModeToggle = true)
    }
}

@Composable
private fun MealPhotoCard(
    photo: MealCalendarPhoto,
    settings: AppSettings,
    colorFilter: ColorFilter?,
    organic: Boolean,
    mediaShape: Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val displayCaption = photo.caption.ifBlank {
        tr(photo.category.chineseLabel, photo.category.englishLabel)
    }
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = if (organic) mediaShape else MaterialTheme.shapes.medium,
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = displayCaption,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = settings.mealCalendarImageMaxHeightDp.dp),
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
        )
        if (settings.mealCalendarShowCaptions) {
            Text(
                text = displayCaption,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MealEnergyDetailsDialog(
    day: MealCalendarDay,
    calorieEstimationEnabled: Boolean,
    actionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (totalEnergyKjOverride: Int?, note: String) -> Unit,
    onRecalculate: (note: String) -> Unit,
) {
    var editingTotal by remember(day.dateIso) { mutableStateOf(false) }
    var totalEdited by remember(day.dateIso) { mutableStateOf(false) }
    var totalDraft by remember(day.dateIso) {
        mutableStateOf(
            (day.details.totalEnergyKjOverride ?: day.calculatedEnergyKj)
                ?.toString()
                .orEmpty(),
        )
    }
    var noteDraft by remember(day.dateIso) { mutableStateOf(day.details.note) }
    val parsedTotal = totalDraft.toIntOrNull()
    val totalValid = !totalEdited || totalDraft.isBlank() ||
        parsedTotal != null && parsedTotal in 0..MAX_MEAL_ENERGY_KJ
    val totalToSave = if (totalEdited) parsedTotal else day.details.totalEnergyKjOverride
    val numberedPhotos = remember(day.photos) {
        val counts = mutableMapOf<MealCategory, Int>()
        day.photos.map { photo ->
            val number = counts.getOrDefault(photo.category, 0) + 1
            counts[photo.category] = number
            photo to number
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("热量详情 · ${day.dateIso}", "Energy details · ${day.dateIso}")) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "total") {
                    if (editingTotal) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = totalDraft,
                                onValueChange = { value ->
                                    totalDraft = value.filter(Char::isDigit).take(7)
                                    totalEdited = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = actionsEnabled,
                                singleLine = true,
                                isError = !totalValid,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                label = { Text(tr("总热量", "Total energy")) },
                                suffix = { Text("kJ") },
                                supportingText = {
                                    Text(
                                        if (totalValid) {
                                            tr(
                                                "用于多人聚餐或同一餐多次拍摄；可恢复为估算小计。",
                                                "Useful for shared meals or duplicate photos; you can restore the estimated subtotal.",
                                            )
                                        } else {
                                            tr(
                                                "请输入 0–$MAX_MEAL_ENERGY_KJ",
                                                "Enter a value from 0 to $MAX_MEAL_ENERGY_KJ",
                                            )
                                        },
                                    )
                                },
                            )
                            TextButton(
                                enabled = actionsEnabled,
                                onClick = {
                                    totalDraft = ""
                                    totalEdited = true
                                },
                            ) {
                                Text(tr("恢复估算小计", "Use estimated subtotal"))
                            }
                        }
                    } else {
                        Card(
                            enabled = actionsEnabled,
                            onClick = {
                                editingTotal = true
                                totalDraft = (day.details.totalEnergyKjOverride
                                    ?: day.calculatedEnergyKj)?.toString().orEmpty()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        tr("总热量", "Total energy"),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    day.details.totalEnergyKjOverride?.let {
                                        Text(
                                            tr(
                                                "手动值 · 估算小计 ${day.calculatedEnergyKj ?: 0} kJ",
                                                "Manual value · estimated subtotal ${day.calculatedEnergyKj ?: 0} kJ",
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    "${day.totalEnergyKj ?: 0} kJ",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Outlined.Edit, tr("修改总热量", "Edit total energy"))
                            }
                        }
                    }
                }
                item(key = "note") {
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it.take(MAX_MEAL_NOTE_CHARS) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = actionsEnabled,
                        label = { Text(tr("备注", "Note")) },
                        placeholder = {
                            Text(
                                tr(
                                    "例如：午餐是两人分享；午餐 1 和午餐 2 是同一份饭。",
                                    "For example: lunch was shared by two people; Lunch 1 and Lunch 2 show the same meal.",
                                ),
                            )
                        },
                        supportingText = {
                            Text(
                                tr(
                                    "备注只在此详情中显示；重新计算时会发送给文字模型。",
                                    "This note is only shown here and is sent to the text model when recalculating.",
                                ),
                            )
                        },
                        minLines = 3,
                        maxLines = 6,
                    )
                }
                numberedPhotos.forEachIndexed { photoIndex, (photo, number) ->
                    item(key = "photo-${photo.fileName}-$photoIndex") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${tr(photo.category.chineseLabel, photo.category.englishLabel)} $number",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    photo.energyKj?.let {
                                        Text(
                                            "$it kJ",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                if (photo.foods.isEmpty()) {
                                    Text(
                                        tr(
                                            "此旧估算只有总量；重新计算后可生成食物明细。",
                                            "This older estimate only has a total; recalculate to create an itemized breakdown.",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    photo.foods.forEachIndexed { index, food ->
                                        if (index > 0) HorizontalDivider()
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(food.name, style = MaterialTheme.typography.bodyMedium)
                                                listOfNotNull(food.amount, food.unit)
                                                    .joinToString(" ")
                                                    .takeIf(String::isNotBlank)
                                                    ?.let { portion ->
                                                        Text(
                                                            portion,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                            }
                                            Text(
                                                food.energyKj?.let { "$it kJ" } ?: "— kJ",
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (calorieEstimationEnabled) {
                    item(key = "recalculate-note") {
                        Text(
                            tr(
                                "重新计算会保留当前备注、更新全部食物明细，并清除手动总热量。",
                                "Recalculating keeps this note, refreshes every food item, and clears the manual total.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = actionsEnabled && totalValid,
                onClick = { onSave(totalToSave, noteDraft) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = {
            if (calorieEstimationEnabled) {
                TextButton(
                    enabled = actionsEnabled,
                    onClick = { onRecalculate(noteDraft) },
                ) { Text(tr("重新计算", "Recalculate")) }
            }
        },
    )
}

@Composable
private fun CalorieEstimateToolbarButton(
    calculationEnabled: Boolean,
    queueState: CalorieEstimationQueueState,
    onCalculate: () -> Unit,
    onOpenProgress: () -> Unit,
) {
    val queueLabel = when {
        queueState.active != null -> tr("热量估算正在进行", "Calorie estimation in progress")
        queueState.queued.isNotEmpty() -> tr("热量估算正在排队", "Calorie estimation queued")
        else -> tr("热量估算空闲", "Calorie estimation idle")
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (queueState.isRunning) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            .combinedClickable(
                enabled = calculationEnabled || queueState.items.isNotEmpty(),
                role = Role.Button,
                onClickLabel = if (calculationEnabled) {
                    tr("计算未计算的热量", "Calculate missing calories")
                } else {
                    tr("查看热量估算进度", "View calorie estimation progress")
                },
                onLongClickLabel = tr("查看热量估算进度", "View calorie estimation progress"),
                onLongClick = onOpenProgress,
                onClick = {
                    if (calculationEnabled) onCalculate() else onOpenProgress()
                },
            )
            .semantics { stateDescription = queueLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Calculate,
            contentDescription = tr("计算未计算的热量", "Calculate missing calories"),
            tint = when {
                queueState.isRunning -> MaterialTheme.colorScheme.onPrimaryContainer
                calculationEnabled -> LocalContentColor.current
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun MealFilterToolbarButton(
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stateLabel = if (enabled) tr("滤镜已开启", "Filter on")
    else tr("滤镜已关闭", "Filter off")
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (enabled) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (enabled) tr("关闭滤镜", "Turn filter off")
                else tr("开启滤镜", "Turn filter on"),
                onLongClickLabel = tr("打开滤镜设置", "Open filter settings"),
                onLongClick = onOpenSettings,
                onClick = onToggle,
            )
            .semantics { stateDescription = stateLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoFixHigh,
            contentDescription = tr("照片滤镜", "Photo filter"),
            tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun DiaryEditorScreen(
    viewModel: DiaryViewModel,
    onBack: () -> Unit,
    onOpenDailyRecords: () -> Unit,
) {
    val state by viewModel.editorState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    var editorValue by remember { mutableStateOf(TextFieldValue(state.content)) }
    var categoryMenu by remember { mutableStateOf(false) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    var captionTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var mediaDeleteTarget by remember { mutableStateOf<String?>(null) }
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
                            listOf(
                                when {
                                    state.conflict != null -> tr("发现外部修改", "External changes found")
                                    state.saving -> tr("正在保存…", "Saving…")
                                    state.dirty -> tr("未保存", "Unsaved")
                                    else -> tr("已保存", "Saved")
                                },
                                if (state.preview) tr("阅读预览", "Preview")
                                else tr("Markdown 源码", "Markdown source"),
                            ).joinToString(" - "),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                                "下午茶" to "Afternoon tea",
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
                    Surface(
                        onClick = onOpenDailyRecords,
                        shape = if (organic) visuals.badgeShape else MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.EventNote, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("日常记录", "Daily records"))
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
                    maxWidth = settings.imageMaxWidthDp,
                    maxHeight = settings.imageMaxHeightDp,
                    mediaTreeUri = settings.mediaTreeUri,
                    resolveMediaBatch = viewModel::resolveDiaryPreviewMedia,
                    onEditCaption = { markdown, caption -> captionTarget = markdown to caption },
                    onDeleteMedia = { target -> mediaDeleteTarget = target },
                )
                else -> MarkdownSourceEditor(
                    value = editorValue,
                    onValueChange = { value -> editorValue = value; viewModel.onContentChanged(value.text) },
                    onMoveMediaLine = viewModel::moveSourceLine,
                    onDeleteMedia = { target -> mediaDeleteTarget = target },
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
    mediaDeleteTarget?.let { target ->
        val displayName = target.trim().trim('<', '>').replace('\\', '/').substringAfterLast('/')
        AlertDialog(
            onDismissRequest = { mediaDeleteTarget = null },
            title = { Text(tr("删除媒体？", "Delete media?")) },
            text = {
                Text(
                    tr(
                        "将从当前日记移除对“$displayName”的全部引用，并删除媒体目录中的对应文件。此操作无法撤回。",
                        "All references to “$displayName” will be removed from this diary, and the matching file in the media folder will be deleted. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mediaDeleteTarget = null
                        viewModel.deleteMedia(target)
                    },
                ) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { mediaDeleteTarget = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
}

private data class MediaSourceLine(
    val index: Int,
    val startOffset: Int,
    val endOffset: Int,
    val target: String,
)

private val markdownMediaPattern = Regex(
    """!\[[^\]\r\n]*]\(\s*(?:<([^>\r\n]+)>|([^\s)\r\n]+))(?:\s+(?:\"[^\"\r\n]*\"|'[^'\r\n]*'|\([^\)\r\n]*\)))?\s*\)""",
)
private val markdownMediaLinePattern = Regex("""^\s*${markdownMediaPattern.pattern}\s*$""")

@Composable
private fun MarkdownSourceEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onMoveMediaLine: (fromIndex: Int, toIndex: Int) -> Unit,
    onDeleteMedia: (target: String) -> Unit,
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var draggingMediaLineIndex by remember { mutableStateOf<Int?>(null) }
    var mediaDragDistancePx by remember { mutableStateOf(0f) }
    var mediaDragTargetVisualLine by remember { mutableStateOf<Int?>(null) }
    var mediaDragTargetSourceLine by remember { mutableStateOf<Int?>(null) }
    val mediaLines = remember(value.text) { findMediaSourceLines(value.text) }
    val topPadding = 16.dp
    // The handle is an overlay and must never participate in text measurement. Keeping it
    // close to the editor's normal line height also prevents adjacent media rows overlapping.
    val handleSize = 36.dp

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
                            .padding(start = 16.dp, top = topPadding, end = 88.dp, bottom = 40.dp),
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
                            modifier = Modifier.padding(start = 16.dp, top = topPadding, end = 88.dp),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                        )
                    }

                    val currentLayout = textLayout
                    if (currentLayout != null && currentLayout.layoutInput.text.text == value.text) {
                        mediaLines.forEach { mediaLine ->
                            val startVisualLine = currentLayout.getLineForOffset(mediaLine.startOffset)
                            val endVisualLine = currentLayout.getLineForOffset(
                                (mediaLine.endOffset - 1).coerceAtLeast(mediaLine.startOffset),
                            )
                            val blockTop = currentLayout.getLineTop(startVisualLine)
                            val blockBottom = currentLayout.getLineBottom(endVisualLine)
                            val blockHeight = (blockBottom - blockTop).coerceAtLeast(
                                with(density) { handleSize.toPx() },
                            )
                            val blockTopWithPadding = with(density) { topPadding.toPx() } + blockTop
                            val isDragging = draggingMediaLineIndex == mediaLine.index

                            fun dragTarget(verticalDistance: Float): Pair<Int, Int> {
                                val maxY = (currentLayout.size.height - 1).coerceAtLeast(0).toFloat()
                                val targetY = (blockTop + blockHeight / 2f + verticalDistance)
                                    .coerceIn(0f, maxY)
                                val targetVisualLine = currentLayout.getLineForVerticalPosition(targetY)
                                val targetOffset = currentLayout.getLineStart(targetVisualLine)
                                val targetSourceLine = value.text
                                    .take(targetOffset.coerceIn(0, value.text.length))
                                    .count { it == '\n' }
                                return targetVisualLine to targetSourceLine
                            }

                            // The stationary mask creates a real gap while the translucent source
                            // block follows the finger above it.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(with(density) { blockHeight.toDp() })
                                    .offset {
                                        IntOffset(x = 0, y = blockTopWithPadding.coerceAtLeast(0f).roundToInt())
                                    }
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.surface else Color.Transparent,
                                    ),
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(with(density) { blockHeight.toDp() })
                                    .offset {
                                        IntOffset(x = 0, y = blockTopWithPadding.coerceAtLeast(0f).roundToInt())
                                    }
                                    .zIndex(if (isDragging) 2f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) mediaDragDistancePx else 0f
                                        alpha = if (isDragging) 0.62f else 1f
                                    }
                                    .background(
                                        if (isDragging) MaterialTheme.colorScheme.surface else Color.Transparent,
                                    )
                                    .padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (isDragging) {
                                    Text(
                                        text = value.text.substring(mediaLine.startOffset, mediaLine.endOffset),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        ),
                                        maxLines = endVisualLine - startVisualLine + 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                IconButton(
                                    modifier = Modifier.size(handleSize),
                                    enabled = !isDragging,
                                    onClick = { onDeleteMedia(mediaLine.target) },
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = tr("删除媒体", "Delete media"),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                FourDotDragHandle(
                                    modifier = Modifier.size(handleSize),
                                    translateSelf = false,
                                    onDragStarted = {
                                        draggingMediaLineIndex = mediaLine.index
                                        mediaDragDistancePx = 0f
                                        mediaDragTargetVisualLine = startVisualLine
                                        mediaDragTargetSourceLine = mediaLine.index
                                    },
                                    onDragChanged = { verticalDistance ->
                                        mediaDragDistancePx = verticalDistance
                                        val (targetVisualLine, targetSourceLine) = dragTarget(verticalDistance)
                                        mediaDragTargetVisualLine = targetVisualLine
                                        mediaDragTargetSourceLine = targetSourceLine
                                    },
                                    onDragCancelled = {
                                        draggingMediaLineIndex = null
                                        mediaDragDistancePx = 0f
                                        mediaDragTargetVisualLine = null
                                        mediaDragTargetSourceLine = null
                                    },
                                    onDragFinished = { verticalDistance ->
                                        val (_, calculatedTargetSourceLine) = dragTarget(verticalDistance)
                                        val targetSourceLine = mediaDragTargetSourceLine
                                            ?: calculatedTargetSourceLine
                                        draggingMediaLineIndex = null
                                        mediaDragDistancePx = 0f
                                        mediaDragTargetVisualLine = null
                                        mediaDragTargetSourceLine = null
                                        onMoveMediaLine(mediaLine.index, targetSourceLine)
                                    },
                                )
                            }
                        }

                        if (draggingMediaLineIndex != null) {
                            mediaDragTargetVisualLine?.let { targetVisualLine ->
                                val insertionTop = with(density) { topPadding.toPx() } +
                                    currentLayout.getLineTop(targetVisualLine)
                                HorizontalDivider(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .offset {
                                            IntOffset(
                                                x = 0,
                                                y = insertionTop.coerceAtLeast(0f).roundToInt(),
                                            )
                                        }
                                        .padding(horizontal = 8.dp)
                                        .zIndex(3f),
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
}

private fun findMediaSourceLines(source: String): List<MediaSourceLine> = buildList {
    var startOffset = 0
    source.split('\n').forEachIndexed { index, line ->
        val match = markdownMediaLinePattern.matchEntire(line)
        if (match != null) {
            val target = match.groupValues[1].ifBlank { match.groupValues[2] }
            add(
                MediaSourceLine(
                    index = index,
                    startOffset = startOffset,
                    endOffset = startOffset + line.length,
                    target = target,
                ),
            )
        }
        startOffset += line.length + 1
    }
}

@Composable
private fun MarkdownPreview(
    content: String,
    maxWidth: Int,
    maxHeight: Int,
    mediaTreeUri: String?,
    resolveMediaBatch: suspend (Collection<String>) -> Map<String, DiaryPreviewMedia>,
    onEditCaption: (String, String) -> Unit,
    onDeleteMedia: (String) -> Unit,
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
    val mediaTargets = remember(parts) {
        parts.filterIsInstance<PreviewPart.Image>().map(PreviewPart.Image::target).distinct()
    }
    val resolvedMedia by produceState<Map<String, DiaryPreviewMedia>>(
        initialValue = emptyMap(),
        mediaTargets,
        mediaTreeUri,
    ) {
        value = try {
            resolveMediaBatch(mediaTargets)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
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
                    val media = resolvedMedia[part.target]
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth(),
                        role = PanelRole.MEDIA,
                        padding = PaddingValues(10.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = media?.uri,
                                contentDescription = part.caption,
                                modifier = Modifier
                                    .widthIn(max = maxWidth.dp)
                                    .fillMaxWidth()
                                    .heightIn(max = maxHeight.dp)
                                    .then(if (organic) Modifier.clip(visuals.mediaShape) else Modifier),
                                contentScale = ContentScale.Fit,
                            )
                            media?.locationName?.let { location ->
                                val locationDescription = tr(
                                    "拍摄地点：$location",
                                    "Photo location: $location",
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .clearAndSetSemantics {
                                            contentDescription = locationDescription
                                        },
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Outlined.Place,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        location,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = { onEditCaption(part.fullMarkdown, part.caption) },
                                ) {
                                    Text(part.caption.ifBlank { tr("点击添加图片说明", "Tap to add a caption") })
                                }
                                IconButton(onClick = { onDeleteMedia(part.target) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = tr("删除媒体", "Delete media"),
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
