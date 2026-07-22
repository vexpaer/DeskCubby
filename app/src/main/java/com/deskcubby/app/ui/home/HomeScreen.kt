@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.repository.DailyPoem
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.daily.DailyEventRecorder
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr
import com.deskcubby.app.ui.thought.ThoughtCategoryFilter
import com.deskcubby.app.ui.thought.ThoughtCategoryPickerDialog
import com.deskcubby.app.ui.thought.ThoughtSendButton
import com.deskcubby.app.ui.thought.categoryIdOrNullForUi
import java.io.File
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

@Composable
fun HomeScreen(
    padding: PaddingValues,
    settings: AppSettings,
    viewModel: HomeViewModel,
    onOpenDiary: (String) -> Unit,
    onOpenThoughts: () -> Unit,
    onOpenDateRecords: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenDailyRecords: () -> Unit,
) {
    val context = LocalContext.current
    val organic = settings.visualStyle == VisualStyle.ORGANIC_FUTURE
    val diaries by viewModel.diaries.collectAsStateWithLifecycle()
    val thoughts by viewModel.thoughts.collectAsStateWithLifecycle()
    val thoughtCategories by viewModel.thoughtCategories.collectAsStateWithLifecycle()
    val dateRecords by viewModel.dateRecords.collectAsStateWithLifecycle()
    val poem by viewModel.poem.collectAsStateWithLifecycle()
    val mealUploadInProgress by viewModel.mealUploadInProgress.collectAsStateWithLifecycle()
    val dailyRecordInProgress by viewModel.dailyRecordInProgress.collectAsStateWithLifecycle()
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
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = homeGreeting(settings),
                            style = if (organic) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "DeskCubby",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(
                if (settings.homeWidgetBordersEnabled) 12.dp else 0.dp,
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
                    onRefreshPoem = { viewModel.refreshPoem() },
                    onSavePoem = { viewModel.savePoem(poem, settings.appLanguage) },
                    mealUploadInProgress = mealInteractionBusy,
                    onChooseMealPhoto = chooseMealPhoto,
                    onCaptureMealPhoto = captureMealPhoto,
                    dailyRecordInProgress = dailyRecordInProgress,
                    onAddDailyRecord = { templateId, entry, onDone ->
                        viewModel.addDailyRecordToToday(templateId, entry, settings, onDone)
                    },
                    onOpenDailyRecords = onOpenDailyRecords,
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(poem.content, style = MaterialTheme.typography.titleMedium)
                    Text(poem.source, style = MaterialTheme.typography.bodySmall)
                }
                Column {
                    IconButton(onClick = onRefreshPoem) {
                        Icon(Icons.Outlined.Refresh, tr("换一句", "Refresh poem"))
                    }
                    IconButton(onClick = onSavePoem) {
                        Icon(Icons.Outlined.Send, tr("加入诗词本", "Save to poetry book"))
                    }
                }
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

@Composable
private fun DailyRecordsWidget(
    showTitle: Boolean,
    showBorder: Boolean,
    templates: List<DailyEventTemplate>,
    sendingIds: Set<String>,
    onSubmit: (String, String, (Boolean) -> Unit) -> Unit,
    onOpenAll: () -> Unit,
) {
    WidgetCard(tr("日常记录", "Daily records"), showTitle, showBorder) {
        if (templates.isEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EventNote, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("还没有日常事件", "No daily events yet"),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenAll) { Text(tr("添加", "Add")) }
            }
            return@WidgetCard
        }

        templates.take(HOME_DAILY_EVENT_LIMIT).forEach { template ->
            var clearKey by rememberSaveable(template.id) { mutableStateOf(0L) }
            DailyEventRecorder(
                template = template,
                isSending = template.id in sendingIds,
                clearInputsKey = clearKey,
                onRecord = { entry ->
                    onSubmit(template.id, entry) { success ->
                        if (success) clearKey += 1
                    }
                },
            )
        }
        TextButton(onClick = onOpenAll, modifier = Modifier.align(Alignment.End)) {
            Text(
                if (templates.size > HOME_DAILY_EVENT_LIMIT) tr("查看全部", "View all")
                else tr("管理日常事件", "Manage daily events"),
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
                val chooseLabel = if (language == AppLanguage.ENGLISH) "Choose $label photo" else "选择${label}图片"
                val cameraLabel = if (language == AppLanguage.ENGLISH) "Take $label photo" else "拍摄${label}图片"
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .semantics { contentDescription = label }
                        .combinedClickable(
                            enabled = !uploading,
                            onClickLabel = chooseLabel,
                            role = Role.Button,
                            onLongClickLabel = cameraLabel,
                            onClick = { onChoosePhoto(action) },
                            onLongClick = { onTakePhoto(action) },
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
        Text(
            text = if (uploading) {
                tr("正在加入今日日记…", "Adding to today's diary…")
            } else {
                tr("单击选图，长按拍照", "Tap to choose; hold to take a photo")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun homeGreeting(settings: AppSettings): String {
    val name = settings.userName.trim()
    return when {
        settings.appLanguage == AppLanguage.ENGLISH && name.isBlank() -> "Hello!"
        settings.appLanguage == AppLanguage.ENGLISH -> "Hello, $name!"
        name.isBlank() -> "你好！"
        else -> "你好，$name！"
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
