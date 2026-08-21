@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.LayoutMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.repository.DailyPoem
import com.deskcubby.app.data.sync.AppCloudSyncStatus
import com.deskcubby.app.data.sync.CloudSyncItemOutcome
import com.deskcubby.app.data.sync.CloudSyncManualScheduler
import com.deskcubby.app.data.sync.CloudSyncRunMode
import com.deskcubby.app.ui.components.LocalLayoutMode
import com.deskcubby.app.ui.structuredrecords.StructuredRecordRecorder
import com.deskcubby.app.ui.structuredrecords.StructuredRecordsViewModel
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalCompactMode
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr
import com.deskcubby.app.ui.thought.ThoughtCategoryFilter
import com.deskcubby.app.ui.thought.ThoughtCategoryPickerDialog
import com.deskcubby.app.ui.thought.ThoughtSendButton
import com.deskcubby.app.ui.thought.categoryIdOrNullForUi
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private data class MealQuickAction(
    val key: String,
    val chinese: String,
    val english: String,
    val symbol: String,
) {
    fun label(language: AppLanguage): String = if (language == AppLanguage.ENGLISH) english else chinese
}

private val mealQuickActions = listOf(
    MealQuickAction("breakfast", "早餐", "Breakfast", "🥪"),
    MealQuickAction("lunch", "午餐", "Lunch", "🍱"),
    MealQuickAction("afternoon_tea", "下午茶", "Afternoon tea", "🍹"),
    MealQuickAction("dinner", "晚餐", "Dinner", "🍜"),
    MealQuickAction("fruit", "水果", "Fruit", "🍊"),
    MealQuickAction("late_snack", "夜宵", "Late snack", "🍤"),
)

private data class HomeGameShortcut(
    val id: String,
    val chinese: String,
    val english: String,
)

private val homeGameShortcuts = listOf(
    HomeGameShortcut("2048", "2048 · 4×4", "2048 · 4×4"),
    HomeGameShortcut("2048_5", "2048 · 5×5", "2048 · 5×5"),
    HomeGameShortcut("2048_6", "2048 · 6×6", "2048 · 6×6"),
    HomeGameShortcut("snake", "贪吃蛇", "Snake"),
    HomeGameShortcut("tetris", "俄罗斯方块", "Tetris"),
    HomeGameShortcut("minesweeper", "扫雷", "Minesweeper"),
    HomeGameShortcut("spider", "蜘蛛纸牌", "Spider Solitaire"),
    HomeGameShortcut("go", "围棋", "Go"),
)

@Composable
fun HomeScreen(
    padding: PaddingValues,
    settings: AppSettings,
    cloudSyncStatus: AppCloudSyncStatus,
    viewModel: HomeViewModel,
    onOpenDiary: (String) -> Unit,
    onOpenThoughts: () -> Unit,
    onOpenDateRecords: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenDailyRecords: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenStatistics: () -> Unit,
) {
    val context = LocalContext.current
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val diaries by viewModel.diaries.collectAsStateWithLifecycle()
    val thoughts by viewModel.thoughts.collectAsStateWithLifecycle()
    val thoughtCategories by viewModel.thoughtCategories.collectAsStateWithLifecycle()
    val dateRecords by viewModel.dateRecords.collectAsStateWithLifecycle()
    val poem by viewModel.poem.collectAsStateWithLifecycle()
    val poemRefreshing by viewModel.poemRefreshing.collectAsStateWithLifecycle()
    val mealUploadInProgress by viewModel.mealUploadInProgress.collectAsStateWithLifecycle()
    val dailyRecordInProgress by viewModel.dailyRecordInProgress.collectAsStateWithLifecycle()
    val cloudSyncActionState by viewModel.cloudSyncActionState.collectAsStateWithLifecycle()
    val cloudSyncUndoAvailable by viewModel.cloudSyncUndoAvailable.collectAsStateWithLifecycle()
    LaunchedEffect(cloudSyncStatus) {
        viewModel.refreshCloudSyncUndoAvailable()
    }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingMealKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val foldersMissingMessage = tr(
        "请先在设置中选择日记目录和媒体目录",
        "Choose both diary and media folders in Settings first",
    )
    val pickerFailedMessage = tr("无法打开照片选择器", "Could not open the photo picker")
    val cameraFailedMessage = tr("无法打开相机", "Could not open the camera")

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(
        cloudSyncStatus.running,
        cloudSyncStatus.lastFinishedAt,
        cloudSyncStatus.message,
        cloudSyncStatus.error,
    ) {
        viewModel.reconcileCloudSyncStatus(cloudSyncStatus)
    }
    LaunchedEffect(context.cacheDir) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - CAMERA_CACHE_MAX_AGE_MS
            File(context.cacheDir, "meal-camera").listFiles()
                ?.filter { it.isFile && it.lastModified() in 1 until cutoff }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val action = mealQuickActions.firstOrNull { it.key == pendingMealKey }
        if (uri != null && action != null) {
            viewModel.addMealPhoto(
                uri,
                action.label(settings.appLanguage),
                settings,
                onDone = { pendingMealKey = null },
            )
        } else {
            pendingMealKey = null
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { captured ->
        val action = mealQuickActions.firstOrNull { it.key == pendingMealKey }
        val cameraFile = pendingCameraPath?.let(::File)
        if (captured && action != null && cameraFile?.isFile == true) {
            val cameraUri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    cameraFile,
                )
            }.getOrNull()
            if (cameraUri != null) {
                viewModel.addMealPhoto(
                    cameraUri,
                    action.label(settings.appLanguage),
                    settings,
                    onDone = {
                        cameraFile.delete()
                        pendingMealKey = null
                        pendingCameraPath = null
                    },
                )
            } else {
                cameraFile.delete()
                pendingMealKey = null
                pendingCameraPath = null
                viewModel.showMessage(cameraFailedMessage)
            }
        } else {
            cameraFile?.delete()
            pendingMealKey = null
            pendingCameraPath = null
        }
    }
    val mealInteractionBusy = mealUploadInProgress || pendingMealKey != null
    val chooseMealPhoto: (MealQuickAction) -> Unit = { action ->
        if (settings.diaryTreeUri == null || settings.mediaTreeUri == null) {
            viewModel.showMessage(foldersMissingMessage)
        } else if (!mealInteractionBusy) {
            pendingMealKey = action.key
            runCatching {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }.onFailure {
                pendingMealKey = null
                viewModel.showMessage(pickerFailedMessage)
            }
        }
    }
    val captureMealPhoto: (MealQuickAction) -> Unit = { action ->
        if (settings.diaryTreeUri == null || settings.mediaTreeUri == null) {
            viewModel.showMessage(foldersMissingMessage)
        } else if (!mealInteractionBusy) {
            var cameraFile: File? = null
            runCatching {
                val directory = File(context.cacheDir, "meal-camera").apply {
                    check(exists() || mkdirs()) { "Could not create the camera cache" }
                }
                cameraFile = File.createTempFile("meal-", ".jpg", directory)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    requireNotNull(cameraFile),
                )
                pendingMealKey = action.key
                pendingCameraPath = cameraFile?.absolutePath
                cameraLauncher.launch(uri)
            }.onFailure {
                cameraFile?.delete()
                pendingMealKey = null
                pendingCameraPath = null
                viewModel.showMessage(cameraFailedMessage)
            }
        }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Text(
                    text = HomeGreeting.forDate(
                        date = LocalDate.now(),
                        language = settings.appLanguage,
                        userName = settings.userName,
                        templates = settings.homeGreetings,
                    ),
                    style = if (organic) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        },
    ) { inner ->
        val compact = LocalCompactMode.current
        val layoutMode = LocalLayoutMode.current
        if (layoutMode == LayoutMode.EXPANDED) {
            HomeWorkspaceContent(
                padding = inner,
                settings = settings,
                diaries = diaries,
                thoughts = thoughts,
                thoughtCategories = thoughtCategories,
                dateRecords = dateRecords,
                poem = poem,
                onOpenDiary = onOpenDiary,
                onOpenThoughts = onOpenThoughts,
                onOpenDateRecords = onOpenDateRecords,
                onOpenWebsite = onOpenWebsite,
                onQuickThought = viewModel::addThought,
                poemRefreshing = poemRefreshing,
                onRefreshPoem = { viewModel.refreshPoem(settings.appLanguage) },
                onSavePoem = { viewModel.savePoem(poem, settings.appLanguage) },
                mealUploadInProgress = mealInteractionBusy,
                onChooseMealPhoto = chooseMealPhoto,
                onCaptureMealPhoto = captureMealPhoto,
                dailyRecordInProgress = dailyRecordInProgress,
                onAddDailyRecord = { templateId, entry, onDone ->
                    viewModel.addDailyRecordToToday(templateId, entry, settings, onDone)
                },
                onOpenDailyRecords = onOpenDailyRecords,
                onOpenNotes = onOpenNotes,
                onOpenGame = onOpenGame,
                onOpenStatistics = onOpenStatistics,
                cloudSyncStatus = cloudSyncStatus,
                cloudSyncActionState = cloudSyncActionState,
                cloudSyncUndoAvailable = cloudSyncUndoAvailable,
                onRunCloudSync = { mode ->
                    val accepted = CloudSyncManualScheduler.enqueue(context, mode)
                    viewModel.recordCloudSyncEnqueue(mode, accepted)
                    accepted
                },
                onUndoCloudSync = { viewModel.undoLastCloudSync() },
            )
        } else {
            LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(if (compact) 10.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(
                when {
                    !settings.homeWidgetBordersEnabled -> 0.dp
                    compact -> 6.dp
                    else -> 12.dp
                },
            ),
        ) {
            items(settings.homeWidgets, key = { it }) { id ->
                HomeWidget(
                    id = id,
                    settings = settings,
                    diaries = diaries,
                    thoughts = thoughts,
                    thoughtCategories = thoughtCategories,
                    dateRecords = dateRecords,
                    poem = poem,
                    onOpenDiary = onOpenDiary,
                    onOpenThoughts = onOpenThoughts,
                    onOpenDateRecords = onOpenDateRecords,
                    onOpenWebsite = onOpenWebsite,
                    onQuickThought = viewModel::addThought,
                    poemRefreshing = poemRefreshing,
                    onRefreshPoem = {
                        viewModel.refreshPoem(settings.appLanguage)
                    },
                    onSavePoem = { viewModel.savePoem(poem, settings.appLanguage) },
                    mealUploadInProgress = mealInteractionBusy,
                    onChooseMealPhoto = chooseMealPhoto,
                    onCaptureMealPhoto = captureMealPhoto,
                    dailyRecordInProgress = dailyRecordInProgress,
                    onAddDailyRecord = { templateId, entry, onDone ->
                        viewModel.addDailyRecordToToday(templateId, entry, settings, onDone)
                    },
                    onOpenDailyRecords = onOpenDailyRecords,
                    onOpenNotes = onOpenNotes,
                    onOpenGame = onOpenGame,
                    onOpenStatistics = onOpenStatistics,
                    cloudSyncStatus = cloudSyncStatus,
                    cloudSyncActionState = cloudSyncActionState,
                    cloudSyncUndoAvailable = cloudSyncUndoAvailable,
                    onRunCloudSync = { mode ->
                        val accepted = CloudSyncManualScheduler.enqueue(context, mode)
                        viewModel.recordCloudSyncEnqueue(mode, accepted)
                        accepted
                    },
                    onUndoCloudSync = { viewModel.undoLastCloudSync() },
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
        }
    }
}

@Composable
private fun HomeWidget(
    id: String,
    settings: AppSettings,
    diaries: List<DiaryIndexEntity>,
    thoughts: List<FlashThoughtEntity>,
    thoughtCategories: List<ThoughtCategoryEntity>,
    dateRecords: List<DateRecordEntity>,
    poem: DailyPoem,
    poemRefreshing: Boolean,
    onOpenDiary: (String) -> Unit,
    onOpenThoughts: () -> Unit,
    onOpenDateRecords: () -> Unit,
    onOpenWebsite: () -> Unit,
    onQuickThought: (String, Long?, (Boolean) -> Unit) -> Unit,
    onRefreshPoem: () -> Unit,
    onSavePoem: () -> Unit,
    mealUploadInProgress: Boolean,
    onChooseMealPhoto: (MealQuickAction) -> Unit,
    onCaptureMealPhoto: (MealQuickAction) -> Unit,
    dailyRecordInProgress: Set<String>,
    onAddDailyRecord: (String, String, (Boolean) -> Unit) -> Unit,
    onOpenDailyRecords: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenStatistics: () -> Unit,
    cloudSyncStatus: AppCloudSyncStatus,
    cloudSyncActionState: HomeCloudSyncActionState,
    cloudSyncUndoAvailable: Boolean,
    onRunCloudSync: (CloudSyncRunMode) -> Boolean,
    onUndoCloudSync: () -> Unit,
) {
    val today = LocalDate.now()
    val locale = if (settings.appLanguage == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
    val showTitle = id in settings.homeWidgetTitles
    when (id) {
        "calendar" -> WidgetCard(tr("日历", "Calendar"), showTitle, settings.homeWidgetBordersEnabled) { MonthCalendar(today) }
        "weather" -> WidgetCard(tr("天气", "Weather"), showTitle, settings.homeWidgetBordersEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WbSunny, null)
                Spacer(Modifier.width(10.dp))
                Column { Text(tr("离线模式", "Offline")); Text(tr("暂无上次天气缓存", "No cached weather"), style = MaterialTheme.typography.bodySmall) }
            }
        }
        "poem" -> WidgetCard(tr("每日诗词", "Daily poem"), showTitle, settings.homeWidgetBordersEnabled) {
            var showFullPoem by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { showFullPoem = true },
                ) {
                    Text(poem.content, style = MaterialTheme.typography.titleMedium)
                    Text(poem.source, style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    IconButton(
                        onClick = onRefreshPoem,
                        enabled = !poemRefreshing,
                    ) {
                        Icon(Icons.Outlined.Refresh, tr("换一句", "Refresh poem"))
                    }
                    IconButton(onClick = onSavePoem) {
                        Icon(Icons.Outlined.Send, tr("加入诗词本", "Save to poetry book"))
                    }
                }
            }
            if (showFullPoem) {
                val sourceLine = if (poem.dynasty.isBlank()) {
                    poem.source
                } else {
                    "${poem.source}（${poem.dynasty}）"
                }
                AlertDialog(
                    onDismissRequest = { showFullPoem = false },
                    title = {
                        Text(poem.title.ifBlank { tr("诗词", "Poem") })
                    },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                poem.fullContent.ifBlank { poem.content },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(sourceLine, style = MaterialTheme.typography.bodySmall)
                            if (poem.fullContent.isBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    tr(
                                        "完整内容会在下次刷新诗词时获取。",
                                        "The full poem is fetched the next time the poem refreshes.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onSavePoem()
                                showFullPoem = false
                            },
                        ) { Text(tr("加入诗词本", "Save to poetry book")) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showFullPoem = false }) { Text(tr("关闭", "Close")) }
                    },
                )
            }
        }
        "today" -> WidgetCard(tr("今天", "Today"), showTitle, settings.homeWidgetBordersEnabled) {
            val pattern = if (settings.appLanguage == AppLanguage.ENGLISH) "EEEE, MMMM d, yyyy" else "yyyy年M月d日 EEEE"
            Text(today.format(DateTimeFormatter.ofPattern(pattern, locale)), style = MaterialTheme.typography.headlineSmall)
        }
        "date_records" -> WidgetCard(tr("日期记录", "Date records"), showTitle, settings.homeWidgetBordersEnabled) {
            DateRecordsWidget(dateRecords, today, onOpenDateRecords)
        }
        "streak" -> WidgetCard(tr("连续记录", "Writing streak"), showTitle, settings.homeWidgetBordersEnabled) {
            Text(if (settings.appLanguage == AppLanguage.ENGLISH) "${streakDays(diaries, today)} days" else "${streakDays(diaries, today)} 天", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        "month_diaries" -> WidgetCard(tr("本月日记", "Diaries this month"), showTitle, settings.homeWidgetBordersEnabled) {
            val prefix = today.toString().take(7)
            val count = diaries.count { it.dateIso.startsWith(prefix) }
            Text(if (settings.appLanguage == AppLanguage.ENGLISH) "$count entries" else "$count 篇", style = MaterialTheme.typography.headlineMedium)
        }
        "total_words" -> WidgetCard(tr("日记总字数", "Total diary words"), showTitle, settings.homeWidgetBordersEnabled) {
            Text("${diaries.sumOf { it.wordCount }}", style = MaterialTheme.typography.headlineMedium)
        }
        "recent_diary" -> WidgetCard(tr("最近日记", "Recent diary"), showTitle, settings.homeWidgetBordersEnabled) {
            diaries.take(3).forEach { item ->
                TextButton(onClick = { onOpenDiary(item.uri) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                            Text(item.name.removeSuffix(".md"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.dateIso, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (diaries.isEmpty()) Text(tr("还没有日记", "No diaries yet"))
        }
        "recent_thought" -> WidgetCard(tr("最近小巧思", "Recent thoughts"), showTitle, settings.homeWidgetBordersEnabled) {
            thoughts.take(3).forEach { Text(it.content, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = onOpenThoughts) { Text(tr("查看全部", "View all")) }
        }
        "quick_input" -> QuickInputWidget(
            showTitle = showTitle,
            showBorder = settings.homeWidgetBordersEnabled,
            categories = thoughtCategories,
            onSubmit = onQuickThought,
        )
        "daily_records" -> DailyRecordsWidget(
            showTitle = showTitle,
            showBorder = settings.homeWidgetBordersEnabled,
            templates = settings.dailyEventTemplates,
            sendingIds = dailyRecordInProgress,
            onSubmit = onAddDailyRecord,
            onOpenAll = onOpenDailyRecords,
        )
        "meal_photos" -> MealPhotosWidget(
            showTitle = showTitle,
            showBorder = settings.homeWidgetBordersEnabled,
            useIcons = settings.mealButtonsUseIcons,
            icons = settings.mealButtonIcons,
            language = settings.appLanguage,
            uploading = mealUploadInProgress,
            onChoosePhoto = onChooseMealPhoto,
            onTakePhoto = onCaptureMealPhoto,
        )
        "random_diary" -> WidgetCard(tr("随机旧日记", "Random old diary"), showTitle, settings.homeWidgetBordersEnabled) {
            val item = remember(diaries) { diaries.takeIf { it.isNotEmpty() }?.get(Random.nextInt(diaries.size)) }
            if (item == null) Text(tr("还没有可回顾的日记", "No diary to revisit")) else TextButton(onClick = { onOpenDiary(item.uri) }) { Text(item.name.removeSuffix(".md")) }
        }
        "year_progress" -> WidgetCard(tr("年度进度", "Year progress"), showTitle, settings.homeWidgetBordersEnabled) {
            val total = if (today.isLeapYear) 366 else 365
            val progress = today.dayOfYear / total.toFloat()
            Text(if (settings.appLanguage == AppLanguage.ENGLISH) "${(progress * 100).toInt()}% · day ${today.dayOfYear} / $total" else "${(progress * 100).toInt()}% · 第 ${today.dayOfYear} / $total 天")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        "website" -> WidgetCard(tr("网站快捷入口", "Website shortcut"), showTitle, settings.homeWidgetBordersEnabled) {
            AssistChip(onClick = onOpenWebsite, label = { Text(settings.browserHomeUrl) }, leadingIcon = { Icon(Icons.Outlined.Language, null) })
        }
        "notes" -> WidgetCard(tr("笔记", "Notes"), showTitle, settings.homeWidgetBordersEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("打开 Obsidian 兼容的 Markdown 笔记库", "Open your Obsidian-compatible Markdown vault"),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenNotes) { Text(tr("打开", "Open")) }
            }
        }
        "game_shortcuts" -> WidgetCard(tr("小游戏", "Mini games"), showTitle, settings.homeWidgetBordersEnabled) {
            val shortcuts = homeGameShortcuts.filter { it.id in settings.homeGameShortcuts }
            if (shortcuts.isEmpty()) {
                Text(
                    tr(
                        "可在“设置 → 子页面设置 → 主页”选择快捷入口",
                        "Choose shortcuts in Settings → Subpage settings → Home",
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    shortcuts.forEach { shortcut ->
                        TextButton(
                            onClick = { onOpenGame(shortcut.id) },
                        ) {
                            Text(
                                if (settings.appLanguage == AppLanguage.ENGLISH) {
                                    shortcut.english
                                } else {
                                    shortcut.chinese
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        "record_overview" -> WidgetCard(tr("记录概览", "Record overview"), showTitle, settings.homeWidgetBordersEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                HomeMetric(diaries.size.toString(), tr("日记", "Diaries"))
                HomeMetric(thoughts.size.toString(), tr("小巧思", "Thoughts"))
                HomeMetric(dateRecords.size.toString(), tr("日期", "Dates"))
            }
            TextButton(onClick = onOpenStatistics, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Insights, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(tr("查看统计", "View statistics"))
            }
        }
        "cloud_sync_now" -> CloudSyncHomeWidget(
            forceActions = false,
            settings = settings,
            status = cloudSyncStatus,
            actionState = cloudSyncActionState,
            undoAvailable = cloudSyncUndoAvailable,
            showTitle = showTitle,
            showBorder = settings.homeWidgetBordersEnabled,
            onRun = onRunCloudSync,
            onUndo = onUndoCloudSync,
        )
        "cloud_sync_force" -> CloudSyncHomeWidget(
            forceActions = true,
            settings = settings,
            status = cloudSyncStatus,
            actionState = cloudSyncActionState,
            undoAvailable = cloudSyncUndoAvailable,
            showTitle = showTitle,
            showBorder = settings.homeWidgetBordersEnabled,
            onRun = onRunCloudSync,
            onUndo = onUndoCloudSync,
        )
    }
}

@Composable
private fun CloudSyncHomeWidget(
    forceActions: Boolean,
    settings: AppSettings,
    status: AppCloudSyncStatus,
    actionState: HomeCloudSyncActionState,
    undoAvailable: Boolean,
    showTitle: Boolean,
    showBorder: Boolean,
    onRun: (CloudSyncRunMode) -> Boolean,
    onUndo: () -> Unit,
) {
    val enabledSourceCount = settings.cloudSyncConfigs.count { it.enabled }
    val canRun = settings.cloudSyncEnabled && enabledSourceCount > 0 && !status.running
    var confirmationMode by remember { mutableStateOf<CloudSyncRunMode?>(null) }
    var showLastRunDetails by remember { mutableStateOf(false) }

    WidgetCard(
        if (forceActions) tr("强制上传 / 下载", "Force upload / download")
        else tr("立即同步", "Sync now"),
        showTitle,
        showBorder,
    ) {
        Text(
            if (forceActions) {
                tr(
                    "仅在明确需要以本机或云端为准时使用；不会传播删除，仍会保护并发修改。",
                    "Use only when explicitly choosing local or cloud data; deletions are not propagated and concurrent edits remain protected.",
                )
            } else {
                tr(
                    "按已保存的同步方向安全合并所有已启用来源。",
                    "Safely merge every enabled source using its saved sync direction.",
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val progress = status.progress
        val statusText = when {
            status.running -> if (progress != null && progress.totalObjects > 0) {
                tr(
                    "正在同步 ${progress.completedObjects}/${progress.totalObjects}",
                    "Syncing ${progress.completedObjects}/${progress.totalObjects}",
                )
            } else {
                tr("正在同步", "Syncing")
            }
            actionState.queuedMode != null -> cloudSyncQueuedLabel(actionState.queuedMode)
            actionState.enqueueFailed -> tr("无法加入同步队列，请稍后重试", "Could not queue the sync; try again")
            status.error != null -> localizedCloudSyncStatus(status.error, settings.appLanguage)
            !settings.cloudSyncEnabled -> tr("云端同步尚未开启", "Cloud sync is turned off")
            enabledSourceCount == 0 -> tr("没有已启用的同步服务", "No sync service is enabled")
            status.message != null -> localizedCloudSyncStatus(status.message, settings.appLanguage)
            else -> tr("已就绪", "Ready")
        }
        Text(
            text = statusText,
            color = if (actionState.enqueueFailed || status.error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (status.running) {
            if (progress != null && progress.totalObjects > 0) {
                LinearProgressIndicator(
                    progress = {
                        progress.completedObjects.toFloat()
                            .div(progress.totalObjects.toFloat())
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        status.lastFinishedAt?.let { finishedAt ->
            val locale = if (settings.appLanguage == AppLanguage.ENGLISH) {
                Locale.ENGLISH
            } else {
                Locale.SIMPLIFIED_CHINESE
            }
            val formatted = remember(finishedAt, locale) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
                    .format(java.util.Date(finishedAt))
            }
            Text(
                tr("上次完成：$formatted", "Last completed: $formatted"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val uploaded = status.lastUploadedCount
        val downloaded = status.lastDownloadedCount
        val conflicts = status.lastConflictCount
        if (uploaded != null && downloaded != null && conflicts != null) {
            TextButton(
                onClick = { showLastRunDetails = true },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    tr(
                        "上次：上传 $uploaded，下载 $downloaded，冲突 $conflicts",
                        "Last: $uploaded uploaded, $downloaded downloaded, $conflicts conflicts",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (forceActions) {
                OutlinedButton(
                    onClick = { confirmationMode = CloudSyncRunMode.FORCE_UPLOAD },
                    enabled = canRun && actionState.queuedMode == null,
                ) {
                    Text(tr("强制上传", "Force upload"))
                }
                OutlinedButton(
                    onClick = { confirmationMode = CloudSyncRunMode.FORCE_DOWNLOAD },
                    enabled = canRun && actionState.queuedMode == null && enabledSourceCount == 1,
                ) {
                    Text(tr("强制下载", "Force download"))
                }
            } else {
                Button(
                    onClick = { onRun(CloudSyncRunMode.NORMAL) },
                    enabled = canRun && actionState.queuedMode == null,
                ) {
                    Text(tr("立即同步", "Sync now"))
                }
                OutlinedButton(
                    onClick = onUndo,
                    enabled = undoAvailable && !status.running && actionState.queuedMode == null,
                ) {
                    Text(tr("撤回一次", "Undo last"))
                }
            }
        }
        if (forceActions && settings.cloudSyncEnabled && enabledSourceCount != 1) {
            Text(
                tr(
                    "强制下载需要恰好一个已启用的云端来源",
                    "Force download requires exactly one enabled cloud source",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    confirmationMode?.takeIf { forceActions }?.let { mode ->
        val upload = mode == CloudSyncRunMode.FORCE_UPLOAD
        AlertDialog(
            onDismissRequest = { confirmationMode = null },
            title = {
                Text(
                    if (upload) tr("确认强制上传？", "Force upload?")
                    else tr("确认强制下载？", "Force download?"),
                )
            },
            text = {
                Text(
                    if (upload) {
                        tr(
                            "同路径内容不同时将以本机版本覆盖远端，但不会删除远端独有项目；并发远端修改仍会阻止覆盖。",
                            "Different items at the same path use the local version. Remote-only items are kept, and concurrent remote edits still stop the overwrite.",
                        )
                    } else {
                        tr(
                            "同路径内容不同时将采用唯一云端来源的版本，但不会删除本机独有项目；并发本机修改仍会保留。",
                            "Different items at the same path use the single cloud source. Local-only items are kept, and concurrent local edits are still preserved.",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmationMode = null
                        onRun(mode)
                    },
                ) {
                    Text(
                        if (upload) tr("强制上传", "Force upload")
                        else tr("强制下载", "Force download"),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationMode = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }

    if (showLastRunDetails) {
        Dialog(
            onDismissRequest = { showLastRunDetails = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(tr("上次同步详情", "Last sync details"), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { showLastRunDetails = false }) {
                            Text(tr("关闭", "Close"))
                        }
                    }
                    status.lastFinishedAt?.let { finishedAt ->
                        val locale = if (settings.appLanguage == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
                        val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM, locale)
                            .format(java.util.Date(finishedAt))
                        Text(tr("完成时间：$formatted", "Finished: $formatted"))
                    }
                    if (uploaded != null && downloaded != null && conflicts != null) {
                        Text(
                            tr(
                                "合计：上传 $uploaded，下载 $downloaded，冲突 $conflicts",
                                "Total: $uploaded uploaded, $downloaded downloaded, $conflicts conflicts",
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (status.lastRuns.isEmpty()) {
                        Text(
                            tr(
                                "本机重启后只保留上次同步的时间和汇总数量；下一次同步完成后，这里会显示各来源和逐项结果。",
                                "After an app restart only the last time and totals are persisted. The next completed sync will show per-source and per-item results here.",
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        status.lastRuns.forEachIndexed { index, run ->
                            GlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        tr("同步来源 ${index + 1}", "Sync source ${index + 1}"),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(run.configId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    run.errorMessage?.let { error ->
                                        Text(
                                            localizedCloudSyncStatus(error, settings.appLanguage),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    run.result?.let { result ->
                                        Text(
                                            tr(
                                                "上传 ${result.uploadedCount} · 下载 ${result.downloadedCount} · 冲突 ${result.conflictCount} · 传输 ${result.transferredBytes} B",
                                                "${result.uploadedCount} uploaded · ${result.downloadedCount} downloaded · ${result.conflictCount} conflicts · ${result.transferredBytes} B transferred",
                                            ),
                                        )
                                        result.reports.forEach { report ->
                                            Text(
                                                "${cloudSyncOutcomeLabel(report.outcome)}  ${report.key}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}

@Composable
private fun cloudSyncOutcomeLabel(outcome: CloudSyncItemOutcome): String = when (outcome) {
    CloudSyncItemOutcome.UNCHANGED -> tr("未变化", "Unchanged")
    CloudSyncItemOutcome.UPLOADED -> tr("上传", "Uploaded")
    CloudSyncItemOutcome.DOWNLOADED -> tr("下载", "Downloaded")
    CloudSyncItemOutcome.CONFLICT_COPY_SAVED -> tr("冲突副本", "Conflict copy")
    CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED -> tr("跳过远端变化", "Remote change skipped")
}

@Composable
private fun cloudSyncQueuedLabel(mode: CloudSyncRunMode): String = when (mode) {
    CloudSyncRunMode.NORMAL -> tr("同步已加入队列", "Sync queued")
    CloudSyncRunMode.FORCE_UPLOAD -> tr("强制上传已加入队列", "Forced upload queued")
    CloudSyncRunMode.FORCE_DOWNLOAD -> tr("强制下载已加入队列", "Forced download queued")
}

private fun localizedCloudSyncStatus(value: String, language: AppLanguage): String {
    val parts = value.split(" / ", limit = 2)
    return if (language == AppLanguage.ENGLISH && parts.size == 2) parts[1] else parts[0]
}

@Composable
private fun HomeMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WidgetCard(
    title: String,
    showTitle: Boolean,
    showBorder: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    if (showBorder) {
        GlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showTitle) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (organic) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                }
                content()
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showTitle) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (organic) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    )
                }
                content()
            }
        }
    }
}

private data class ParsedDateRecord(
    val record: DateRecordEntity,
    val date: LocalDate,
)

@Composable
private fun DateRecordsWidget(
    records: List<DateRecordEntity>,
    today: LocalDate,
    onOpenDateRecords: () -> Unit,
) {
    val nearest = remember(records, today) { nearestDateRecords(records, today) }
    if (nearest.isEmpty()) {
        Text(tr("还没有日期记录", "No date records yet"))
        TextButton(onClick = onOpenDateRecords) {
            Text(tr("添加目标日期", "Add a target date"))
        }
        return
    }

    nearest.forEach { item ->
        TextButton(
            onClick = onOpenDateRecords,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.record.icon.ifBlank { "🎯" },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.Center,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = dateDistanceText(item.record.name, item.date, today),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = item.record.dateIso,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    TextButton(onClick = onOpenDateRecords) {
        Text(tr("查看全部", "View all"))
    }
}

private fun nearestDateRecords(
    records: List<DateRecordEntity>,
    today: LocalDate,
): List<ParsedDateRecord> {
    val parsed = records.mapNotNull { record ->
        runCatching { ParsedDateRecord(record, LocalDate.parse(record.dateIso)) }.getOrNull()
    }
    val upcoming = parsed
        .filter { !it.date.isBefore(today) }
        .sortedWith(compareBy<ParsedDateRecord> { it.date }.thenBy { it.record.id })
        .take(2)
    val past = parsed
        .filter { it.date.isBefore(today) }
        .sortedWith(compareByDescending<ParsedDateRecord> { it.date }.thenBy { it.record.id })
        .take(2)
    return upcoming + past
}

@Composable
private fun dateDistanceText(name: String, date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> tr(
            "距离 $name 已经过去 ${-days} 天",
            "${-days} days since $name",
        )
        days > 0 -> tr(
            "还有 $days 天到 $name",
            "$days days until $name",
        )
        else -> tr("今天就是 $name", "$name is today")
    }
}

@Composable
private fun QuickInputWidget(
    showTitle: Boolean,
    showBorder: Boolean,
    categories: List<ThoughtCategoryEntity>,
    onSubmit: (String, Long?, (Boolean) -> Unit) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    var categoryPickerThought by rememberSaveable { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun submit(snapshot: String, categoryId: Long?) {
        if (snapshot.isBlank() || submitting) return
        submitting = true
        onSubmit(snapshot, categoryId) { success ->
            if (success) {
                if (value == snapshot) value = ""
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            submitting = false
        }
    }

    WidgetCard(tr("快速输入", "Quick input"), showTitle, showBorder) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.weight(1f), placeholder = { Text(tr("记一条小巧思", "Write a thought")) })
            Spacer(Modifier.width(8.dp))
            ThoughtSendButton(
                enabled = value.isNotBlank() && !submitting,
                onClick = { submit(value, null) },
                onLongClick = { categoryPickerThought = value.takeIf(String::isNotBlank) },
            )
        }
    }

    categoryPickerThought?.let { snapshot ->
        ThoughtCategoryPickerDialog(
            title = tr("选择分类并发送", "Choose a category and send"),
            categories = categories,
            currentCategoryId = null,
            onDismiss = { categoryPickerThought = null },
            onSelect = { filter ->
                categoryPickerThought = null
                submit(snapshot, filter.categoryIdOrNullForUi())
            },
        )
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun DailyRecordsWidget(
    showTitle: Boolean,
    showBorder: Boolean,
    templates: List<DailyEventTemplate>,
    sendingIds: Set<String>,
    onSubmit: (String, String, (Boolean) -> Unit) -> Unit,
    onOpenAll: () -> Unit,
) {
    val viewModel: StructuredRecordsViewModel = hiltViewModel()
    val structuredTemplates by viewModel.templates.collectAsStateWithLifecycle()
    val fields by viewModel.fields.collectAsStateWithLifecycle()
    val structuredSendingIds by viewModel.sendingTemplateIds.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val fieldsById = remember(fields) { fields.associateBy { it.id } }
    val visibleTemplates = structuredTemplates.filterNot { it.archived }

    LaunchedEffect(Unit) {
        viewModel.touchNow()
        viewModel.refreshWorkspaceFromUi()
    }

    WidgetCard(tr("结构化记录", "Structured records"), showTitle, showBorder) {
        if (visibleTemplates.isEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EventNote, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("还没有结构化记录", "No structured records yet"),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenAll) { Text(tr("添加", "Add")) }
            }
            return@WidgetCard
        }

        visibleTemplates.take(HOME_DAILY_EVENT_LIMIT).forEach { template ->
            StructuredRecordRecorder(
                template = template,
                fieldsById = fieldsById,
                now = now,
                isSending = template.id in structuredSendingIds,
                clearInputsKey = feedback
                    ?.takeIf { !it.isError && it.recordedTemplateId == template.id }
                    ?.key,
                onRecord = { draft -> viewModel.record(template, draft) },
                onEdit = null,
                onDelete = null,
            )
        }
        TextButton(onClick = onOpenAll, modifier = Modifier.align(Alignment.End)) {
            Text(
                if (visibleTemplates.size > HOME_DAILY_EVENT_LIMIT) tr("查看全部", "View all")
                else tr("管理结构化记录", "Manage structured records"),
            )
        }
    }
}

@Composable
private fun MealPhotosWidget(
    showTitle: Boolean,
    showBorder: Boolean,
    useIcons: Boolean,
    icons: List<String>,
    language: AppLanguage,
    uploading: Boolean,
    onChoosePhoto: (MealQuickAction) -> Unit,
    onTakePhoto: (MealQuickAction) -> Unit,
) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    val displayedIcons = mealQuickActions.mapIndexed { index, action ->
        icons.getOrNull(index)?.trim()?.takeIf(String::isNotBlank) ?: action.symbol
    }

    WidgetCard(tr("饮食图片", "Meal photos"), showTitle, showBorder) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            mealQuickActions.forEachIndexed { index, action ->
                val label = action.label(language)
                val cameraLabel = if (language == AppLanguage.ENGLISH) "Take $label photo" else "拍摄${label}图片"
                val chooseLabel = if (language == AppLanguage.ENGLISH) "Choose $label photo" else "选择${label}图片"
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics { contentDescription = label }
                        .combinedClickable(
                            enabled = !uploading,
                            onClickLabel = cameraLabel,
                            role = Role.Button,
                            onLongClickLabel = chooseLabel,
                            onClick = { onTakePhoto(action) },
                            onLongClick = { onChoosePhoto(action) },
                        ),
                    shape = if (organic) visuals.badgeShape else MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (useIcons) displayedIcons[index] else label,
                            style = if (useIcons) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendar(today: LocalDate) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    val visuals = deskCubbyVisuals
    val month = YearMonth.from(today)
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = List(firstOffset) { 0 } + (1..month.lengthOfMonth()).toList()
    Text(if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == AppLanguage.ENGLISH) "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}" else "${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth()) {
        val weekdays = if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == AppLanguage.ENGLISH) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("一", "二", "三", "四", "五", "六", "日")
        weekdays.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center) }
    }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { day ->
                Text(
                    text = if (day == 0) "" else day.toString(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(5.dp)
                        .then(
                            if (organic && day == today.dayOfMonth) {
                                Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    visuals.badgeShape,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    textAlign = TextAlign.Center,
                    color = when {
                        organic && day == today.dayOfMonth -> MaterialTheme.colorScheme.onPrimaryContainer
                        day == today.dayOfMonth -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun streakDays(diaries: List<DiaryIndexEntity>, today: LocalDate): Int {
    val dates = diaries.mapNotNull { runCatching { LocalDate.parse(it.dateIso) }.getOrNull() }.toSet()
    var cursor = if (today in dates) today else today.minusDays(1)
    var count = 0
    while (cursor in dates) { count++; cursor = cursor.minusDays(1) }
    return count
}

private const val CAMERA_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
private const val HOME_DAILY_EVENT_LIMIT = 4
@Composable
private fun HomeWorkspaceContent(
    padding: PaddingValues,
    settings: AppSettings,
    diaries: List<DiaryIndexEntity>,
    thoughts: List<FlashThoughtEntity>,
    thoughtCategories: List<ThoughtCategoryEntity>,
    dateRecords: List<DateRecordEntity>,
    poem: DailyPoem,
    onOpenDiary: (String) -> Unit,
    onOpenThoughts: () -> Unit,
    onOpenDateRecords: () -> Unit,
    onOpenWebsite: () -> Unit,
    onQuickThought: (String, Long?, (Boolean) -> Unit) -> Unit,
    poemRefreshing: Boolean,
    onRefreshPoem: () -> Unit,
    onSavePoem: () -> Unit,
    mealUploadInProgress: Boolean,
    onChooseMealPhoto: (MealQuickAction) -> Unit,
    onCaptureMealPhoto: (MealQuickAction) -> Unit,
    dailyRecordInProgress: Set<String>,
    onAddDailyRecord: (String, String, (Boolean) -> Unit) -> Unit,
    onOpenDailyRecords: () -> Unit,
    onOpenNotes: () -> Unit,
    onOpenGame: (String) -> Unit,
    onOpenStatistics: () -> Unit,
    cloudSyncStatus: AppCloudSyncStatus,
    cloudSyncActionState: HomeCloudSyncActionState,
    cloudSyncUndoAvailable: Boolean,
    onRunCloudSync: (CloudSyncRunMode) -> Boolean,
    onUndoCloudSync: () -> Unit,
) {
    // Landscape "Workspace" re-composition of the same home widgets: a primary (today/diary)
    // reading column plus a secondary (ideas/events/stats) column, instead of one tall list.
    val configured = settings.homeWidgets
    val primaryIds = listOf(
        "today", "record_overview", "recent_diary", "meal_photos",
        "quick_input", "daily_records", "calendar",
    ).filter { it in configured }.ifEmpty { listOf("today") }
    val secondaryIds = listOf(
        "poem", "streak", "total_words", "recent_thought", "year_progress",
        "date_records", "month_diaries", "notes", "cloud_sync_now", "cloud_sync_force",
        "random_diary", "website", "game_shortcuts",
    ).filter { it in configured }

    val render: @Composable (String) -> Unit = { id ->
        HomeWidget(
            id = id,
            settings = settings,
            diaries = diaries,
            thoughts = thoughts,
            thoughtCategories = thoughtCategories,
            dateRecords = dateRecords,
            poem = poem,
            onOpenDiary = onOpenDiary,
            onOpenThoughts = onOpenThoughts,
            onOpenDateRecords = onOpenDateRecords,
            onOpenWebsite = onOpenWebsite,
            onQuickThought = onQuickThought,
            poemRefreshing = poemRefreshing,
            onRefreshPoem = onRefreshPoem,
            onSavePoem = onSavePoem,
            mealUploadInProgress = mealUploadInProgress,
            onChooseMealPhoto = onChooseMealPhoto,
            onCaptureMealPhoto = onCaptureMealPhoto,
            dailyRecordInProgress = dailyRecordInProgress,
            onAddDailyRecord = onAddDailyRecord,
            onOpenDailyRecords = onOpenDailyRecords,
            onOpenNotes = onOpenNotes,
            onOpenGame = onOpenGame,
            onOpenStatistics = onOpenStatistics,
            cloudSyncStatus = cloudSyncStatus,
            cloudSyncActionState = cloudSyncActionState,
            cloudSyncUndoAvailable = cloudSyncUndoAvailable,
            onRunCloudSync = onRunCloudSync,
            onUndoCloudSync = onUndoCloudSync,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (id in primaryIds) render(id)
        }
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (id in secondaryIds) render(id)
        }
    }
}
