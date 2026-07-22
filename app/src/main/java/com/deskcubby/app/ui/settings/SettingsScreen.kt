package com.deskcubby.app.ui.settings

import android.net.Uri
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.takeCodePoints
import com.deskcubby.app.data.backup.AutoBackupStatus
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.ThoughtReopenMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.repository.buildAiRequestPreviewJson
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.components.OrganicSplitActionRow
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.tr
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

private enum class SettingsPage {
    MAIN,
    APPEARANCE,
    SUBPAGES,
    HOME,
    BACKUP,
    DIARY,
    BLOG,
    THOUGHT,
    RSS,
    AI,
    AI_DETAIL,
    NAVIGATION,
}

enum class SettingsStartPage { MAIN, NAVIGATION, RSS, AI }

private fun SettingsStartPage.toSettingsPage(): SettingsPage = when (this) {
    SettingsStartPage.MAIN -> SettingsPage.MAIN
    SettingsStartPage.NAVIGATION -> SettingsPage.NAVIGATION
    SettingsStartPage.RSS -> SettingsPage.RSS
    SettingsStartPage.AI -> SettingsPage.AI
}

private class SettingsSaveCoordinator {
    var available by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(false)
        private set
    private var saveAction: (() -> Unit)? = null

    fun register(dirty: Boolean, enabled: Boolean, action: () -> Unit) {
        available = true
        this.dirty = dirty
        this.enabled = enabled
        saveAction = action
    }

    fun clear() {
        available = false
        dirty = false
        enabled = false
        saveAction = null
    }

    fun save() {
        if (available && dirty && enabled) saveAction?.invoke()
    }
}

@Composable
private fun RegisterSettingsSave(
    coordinator: SettingsSaveCoordinator,
    dirty: Boolean,
    enabled: Boolean = true,
    onSave: () -> Unit,
) {
    val currentOnSave by rememberUpdatedState(onSave)
    val stableAction = remember(coordinator) { { currentOnSave() } }
    SideEffect { coordinator.register(dirty = dirty, enabled = enabled, action = stableAction) }
}

private data class HomeWidgetOption(
    val id: String,
    val chinese: String,
    val english: String,
)

private data class MealButtonOption(
    val chinese: String,
    val english: String,
    val defaultIcon: String,
)

private data class HomeSettingsDraft(
    val userName: String,
    val widgetBordersEnabled: Boolean,
    val widgets: List<String>,
    val visibleWidgetTitles: List<String>,
    val mealButtonsUseIcons: Boolean,
    val mealButtonIcons: List<String>,
)

private val homeWidgetOptions = listOf(
    HomeWidgetOption("calendar", "日历", "Calendar"),
    HomeWidgetOption("weather", "天气缓存", "Weather cache"),
    HomeWidgetOption("poem", "每日诗词", "Daily poem"),
    HomeWidgetOption("today", "今天日期", "Today"),
    HomeWidgetOption("date_records", "日期记录", "Date records"),
    HomeWidgetOption("streak", "连续记录天数", "Writing streak"),
    HomeWidgetOption("month_diaries", "本月日记数量", "Diaries this month"),
    HomeWidgetOption("total_words", "日记总字数", "Total diary words"),
    HomeWidgetOption("recent_diary", "最近日记", "Recent diary"),
    HomeWidgetOption("recent_thought", "最近小巧思", "Recent thought"),
    HomeWidgetOption("quick_input", "快速输入", "Quick input"),
    HomeWidgetOption("daily_records", "日常记录", "Daily records"),
    HomeWidgetOption("meal_photos", "饮食图片", "Meal photos"),
    HomeWidgetOption("random_diary", "随机旧日记", "Random old diary"),
    HomeWidgetOption("year_progress", "年度进度", "Year progress"),
    HomeWidgetOption("website", "网站快捷入口", "Website shortcut"),
)

private val mealButtonOptions = listOf(
    MealButtonOption("早餐", "Breakfast", "🥪"),
    MealButtonOption("午餐", "Lunch", "🍱"),
    MealButtonOption("下午茶", "Afternoon tea", "🍹"),
    MealButtonOption("晚餐", "Dinner", "🍜"),
    MealButtonOption("水果", "Fruit", "🍊"),
    MealButtonOption("夜宵", "Late snack", "🍤"),
)

private data class DiarySettingsDraft(
    val diaryTreeUri: String?,
    val mediaTreeUri: String?,
    val filePattern: String,
    val template: String,
    val imagePattern: String,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val mealImageCompressionEnabled: Boolean,
    val mealImageCompressionQuality: Int,
    val mealCalendarImageMaxHeightDp: Int,
    val mealCalendarShowCaptions: Boolean,
    val calorieEstimationEnabled: Boolean,
    val calorieTextConfigId: String?,
    val calorieImageConfigId: String?,
    val calorieVisionPrompt: String,
    val calorieTextPrompt: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    padding: PaddingValues,
    viewModel: SettingsViewModel,
    startPage: SettingsStartPage = SettingsStartPage.MAIN,
    onExit: (() -> Unit)? = null,
    onSubpageOpenChanged: (Boolean) -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backupOperation by viewModel.backupOperation.collectAsStateWithLifecycle()
    val autoBackupStatus by viewModel.autoBackupStatus.collectAsStateWithLifecycle()
    val settingsError by viewModel.settingsError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val rootPage = remember(startPage) { startPage.toSettingsPage() }
    var page by rememberSaveable(startPage) { mutableStateOf(rootPage) }
    val saveCoordinator = remember { SettingsSaveCoordinator() }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var editingAiConfig by remember { mutableStateOf<AiModelConfig?>(null) }

    LaunchedEffect(page) {
        saveCoordinator.clear()
        onSubpageOpenChanged(page != SettingsPage.MAIN)
    }
    DisposableEffect(Unit) {
        onDispose { onSubpageOpenChanged(false) }
    }

    fun exitOrOpenParent() {
        saveCoordinator.clear()
        if (startPage != SettingsStartPage.MAIN && page == rootPage && onExit != null) {
            onExit()
        } else {
            page = parentSettingsPage(page)
        }
    }

    fun completeSave(parent: SettingsPage) {
        saveCoordinator.clear()
        if (startPage != SettingsStartPage.MAIN && page == rootPage && onExit != null) {
            onExit()
        } else {
            page = parent
        }
    }

    fun leaveCurrentPage() {
        if (saveCoordinator.dirty) {
            showUnsavedDialog = true
        } else {
            exitOrOpenParent()
        }
    }

    LaunchedEffect(settingsError) {
        settingsError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSettingsError()
        }
    }

    BackHandler(enabled = page != SettingsPage.MAIN) { leaveCurrentPage() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(pageTitle(page)) },
                navigationIcon = {
                    if (page != SettingsPage.MAIN) {
                        IconButton(onClick = ::leaveCurrentPage) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = if (parentSettingsPage(page) == SettingsPage.SUBPAGES) {
                                    tr("返回子页面设置", "Back to subpage settings")
                                } else {
                                    tr("返回设置", "Back to settings")
                                },
                            )
                        }
                    }
                },
                actions = {
                    if (saveCoordinator.available) {
                        TextButton(
                            enabled = saveCoordinator.dirty && saveCoordinator.enabled,
                            onClick = saveCoordinator::save,
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(tr("保存", "Save"))
                        }
                    }
                },
            )
        },
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        when (page) {
            SettingsPage.MAIN -> SettingsMainPage(
                settings = settings,
                contentPadding = inner,
                onOpen = { page = it },
            )

            SettingsPage.APPEARANCE -> AppearanceSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { visualStyle, darkMode, language, themeColor, secondaryColors, fontScale ->
                    viewModel.setVisualStyle(visualStyle)
                    viewModel.setDarkMode(darkMode)
                    viewModel.setAppLanguage(language)
                    viewModel.setThemeColor(themeColor)
                    viewModel.setThemeSecondaryColors(secondaryColors)
                    viewModel.setFontScale(fontScale)
                    completeSave(SettingsPage.MAIN)
                },
            )

            SettingsPage.SUBPAGES -> SubpageSettingsPage(
                settings = settings,
                contentPadding = inner,
                onOpen = { page = it },
            )

            SettingsPage.HOME -> HomeSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { draft ->
                    viewModel.setHomePageSettings(
                        userName = draft.userName,
                        widgetBordersEnabled = draft.widgetBordersEnabled,
                        widgets = draft.widgets,
                        visibleWidgetTitles = draft.visibleWidgetTitles,
                        mealButtonsUseIcons = draft.mealButtonsUseIcons,
                        mealButtonIcons = draft.mealButtonIcons,
                    )
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.BACKUP -> BackupSettingsPage(
                backupTreeUri = settings.backupTreeUri,
                operation = backupOperation,
                autoBackupStatus = autoBackupStatus,
                contentPadding = inner,
                onSelectFolder = viewModel::selectBackupFolder,
                onImportExistingBackup = viewModel::importExistingBackup,
                onOverwriteExistingBackup = viewModel::overwriteExistingBackup,
                onCancelFolderConflict = viewModel::cancelBackupFolderConflict,
                onSaveNow = viewModel::saveBackupNow,
                onDisableAutoBackup = viewModel::disableAutoBackup,
                onExport = viewModel::exportBackup,
                onImport = viewModel::importBackup,
            )

            SettingsPage.DIARY -> DiarySettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { draft ->
                    if (draft.diaryTreeUri != null && draft.diaryTreeUri != settings.diaryTreeUri) {
                        viewModel.persistFolder(Uri.parse(draft.diaryTreeUri), diary = true)
                    }
                    if (draft.mediaTreeUri != null && draft.mediaTreeUri != settings.mediaTreeUri) {
                        viewModel.persistFolder(Uri.parse(draft.mediaTreeUri), diary = false)
                    }
                    viewModel.setFileNamePattern(draft.filePattern)
                    viewModel.setTemplate(draft.template)
                    viewModel.setImageNamePattern(draft.imagePattern)
                    draft.imageWidth?.let(viewModel::setImageMaxWidth)
                    draft.imageHeight?.let(viewModel::setImageMaxHeight)
                    viewModel.setMealImageCompressionEnabled(draft.mealImageCompressionEnabled)
                    viewModel.setMealImageCompressionQuality(draft.mealImageCompressionQuality)
                    viewModel.setMealCalendarImageMaxHeight(draft.mealCalendarImageMaxHeightDp)
                    viewModel.setMealCalendarShowCaptions(draft.mealCalendarShowCaptions)
                    viewModel.setCalorieEstimationSettings(
                        draft.calorieEstimationEnabled, draft.calorieTextConfigId,
                        draft.calorieImageConfigId, draft.calorieVisionPrompt, draft.calorieTextPrompt,
                    )
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.BLOG -> BlogSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { browserHome, browserTheme, browserDesktopMode ->
                    viewModel.setBrowserHome(browserHome)
                    viewModel.setBrowserTheme(browserTheme)
                    viewModel.setBrowserDesktopMode(browserDesktopMode)
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.THOUGHT -> ThoughtSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { rowHeight, reopenMode, displayMode ->
                    viewModel.setThoughtSettings(rowHeight, reopenMode, displayMode)
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.RSS -> RssSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { maxItems, showSummaries ->
                    viewModel.setRssSettings(maxItems, showSummaries)
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.AI -> AiConfigurationsSettingsPage(
                settings = settings,
                contentPadding = inner,
                onAdd = {
                    editingAiConfig = AiModelConfig(
                        id = UUID.randomUUID().toString(), name = "", type = AiModelType.TEXT,
                        endpointUrl = "https://api.openai.com/v1/chat/completions", model = "",
                        systemPrompt = "你是一个有帮助的助手。",
                    )
                    page = SettingsPage.AI_DETAIL
                },
                onOpen = { config ->
                    editingAiConfig = config
                    page = SettingsPage.AI_DETAIL
                },
                onCopy = viewModel::copyAiConfig,
                onDelete = viewModel::deleteAiConfig,
            )

            SettingsPage.AI_DETAIL -> editingAiConfig?.let { config ->
                AiConfigurationDetailPage(
                    initial = config,
                    contentPadding = inner,
                    saveCoordinator = saveCoordinator,
                    onSave = { changed ->
                        viewModel.saveAiConfig(changed) { success ->
                            if (success) completeSave(SettingsPage.AI)
                        }
                    },
                )
            }

            SettingsPage.NAVIGATION -> NavigationSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { defaultPage, navItems, showLabels ->
                    viewModel.setNavigationSettings(defaultPage, navItems, showLabels)
                    completeSave(SettingsPage.MAIN)
                },
            )
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(tr("设置尚未保存", "Unsaved settings")) },
            text = { Text(tr("返回会丢失刚才的修改。", "Going back will discard your changes.")) },
            confirmButton = {
                TextButton(
                    enabled = saveCoordinator.enabled,
                    onClick = {
                        showUnsavedDialog = false
                        saveCoordinator.save()
                    },
                ) { Text(tr("保存", "Save")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showUnsavedDialog = false }) {
                        Text(tr("继续编辑", "Keep editing"))
                    }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            exitOrOpenParent()
                        },
                    ) { Text(tr("放弃", "Discard")) }
                }
            },
        )
    }
}

@Composable
private fun SettingsMainPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onOpen: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsMenuItem(
                title = tr("外观与语言", "Appearance & language"),
                description = tr("界面风格、多色主题、字号、明暗模式和语言", "Style, multi-color theme, type size, dark mode and language"),
                icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                accentColor = settings.menuAccentColor(0),
                onClick = { onOpen(SettingsPage.APPEARANCE) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("子页面设置", "Subpage settings"),
                description = tr("主页、日记、浏览器、小巧思、RSS 与 AI", "Home, diary, browser, thoughts, RSS and AI"),
                icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                accentColor = settings.menuAccentColor(1),
                onClick = { onOpen(SettingsPage.SUBPAGES) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("应用数据与备份", "App data & backup"),
                description = if (settings.backupTreeUri == null) {
                    tr("自动保存文件夹、导入与导出 JSON", "Auto-save folder and JSON import/export")
                } else {
                    tr("应用内容会在更改后自动保存", "App data is saved automatically after changes")
                },
                icon = { Icon(Icons.Outlined.Backup, contentDescription = null) },
                accentColor = settings.menuAccentColor(2),
                onClick = { onOpen(SettingsPage.BACKUP) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("底部导航", "Bottom navigation"),
                description = tr("显示方式、默认页、排序、名称与图标", "Display, default page, order, labels and icons"),
                icon = { Icon(Icons.Outlined.ViewWeek, contentDescription = null) },
                accentColor = settings.menuAccentColor(3),
                onClick = { onOpen(SettingsPage.NAVIGATION) },
            )
        }
        item {
            SettingsMenuItem(
                title = "About",
                description = tr("查看 DeskCubby GitHub 仓库", "Open the DeskCubby GitHub repository"),
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                accentColor = settings.menuAccentColor(4),
                onClick = { openUrl(context, GITHUB_URL) },
            )
        }
    }
}

@Composable
private fun SubpageSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onOpen: (SettingsPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsMenuItem(
                title = tr("主页", "Home"),
                description = tr("问候语、模块、标题、排序与饮食按钮", "Greeting, widgets, titles, order and meal buttons"),
                icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                accentColor = settings.menuAccentColor(0),
                onClick = { onOpen(SettingsPage.HOME) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("日记与媒体", "Diary & media"),
                description = if (settings.diaryTreeUri == null) {
                    tr("目录、文件名与图片规则", "Folders, file names and image rules")
                } else {
                    tr("日记目录已配置", "Diary folder configured")
                },
                icon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
                accentColor = settings.menuAccentColor(1),
                onClick = { onOpen(SettingsPage.DIARY) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("浏览器", "Browser"),
                description = tr("默认主页、主题和电脑模式", "Home page, theme and desktop mode"),
                icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                accentColor = settings.menuAccentColor(2),
                onClick = { onOpen(SettingsPage.BLOG) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("小巧思", "Thoughts"),
                description = tr("打开位置、内容显示与行高", "Reopen page, content display and row height"),
                icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                accentColor = settings.menuAccentColor(3),
                onClick = { onOpen(SettingsPage.THOUGHT) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("RSS 订阅", "RSS"),
                description = tr("每个订阅的文章数量与摘要显示", "Article limit and summary display"),
                icon = { Icon(Icons.Outlined.RssFeed, contentDescription = null) },
                accentColor = settings.menuAccentColor(4),
                onClick = { onOpen(SettingsPage.RSS) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("AI 配置", "AI configurations"),
                description = tr("兼容接口、模型、系统提示词与 API 密钥", "Endpoint, model, system prompt and API key"),
                icon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
                accentColor = settings.menuAccentColor(5),
                onClick = { onOpen(SettingsPage.AI) },
            )
        }
    }
}

@Composable
private fun SettingsMenuItem(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    accentColor: Color,
    onClick: () -> Unit,
) {
    if (LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE) {
        OrganicSettingsMenuItem(
            title = title,
            description = description,
            icon = icon,
            accentColor = accentColor,
            onClick = onClick,
        )
    } else {
        GlassPanel(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            padding = PaddingValues(0.dp),
        ) {
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(description) },
                leadingContent = icon,
                trailingContent = {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = tr("进入$title", "Open $title"))
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun OrganicSettingsMenuItem(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    accentColor: Color,
    onClick: () -> Unit,
) {
    OrganicSplitActionRow(
        modifier = Modifier.fillMaxWidth(),
        bodyColor = accentColor,
        actionColor = MaterialTheme.colorScheme.primary,
        bodyClickLabel = tr("进入$title", "Open $title"),
        actionClickLabel = tr("进入$title", "Open $title"),
        onBodyClick = onClick,
        onActionClick = onClick,
        body = {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        action = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = tr("进入$title", "Open $title"),
            )
        },
    )
}

private fun AppSettings.menuAccentColor(index: Int): Color {
    val colors = themeSecondaryColorsArgb.ifEmpty { listOf(themeColorArgb) }
    return Color(colors[index.mod(colors.size)])
}

@Composable
private fun BackupSettingsPage(
    backupTreeUri: String?,
    operation: BackupOperationState,
    autoBackupStatus: AutoBackupStatus,
    contentPadding: PaddingValues,
    onSelectFolder: (Uri) -> Unit,
    onImportExistingBackup: () -> Unit,
    onOverwriteExistingBackup: () -> Unit,
    onCancelFolderConflict: () -> Unit,
    onSaveNow: () -> Unit,
    onDisableAutoBackup: () -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
) {
    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onSelectFolder)
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(onExport)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImportUri = uri?.toString()
    }
    val busy = operation.busy || autoBackupStatus.isSaving

    operation.folderConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = onCancelFolderConflict,
            title = { Text(tr("发现已有备份", "Existing backup found")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr(
                            "所选文件夹中已有 DeskCubby 备份。请选择导入它，或明确使用当前数据覆盖。",
                            "The selected folder already contains a DeskCubby backup. Import it or explicitly replace it with current data.",
                        ),
                    )
                    Text(
                        tr("导出时间：", "Exported: ") + formatBackupTime(conflict.summary.exportedAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        tr(
                            "${conflict.summary.thoughtCount} 条小巧思，${conflict.summary.categoryCount} 个分类，${conflict.summary.favoriteCount} 个浏览器收藏，${conflict.summary.dateRecordCount} 个日期记录，${conflict.summary.poemCount} 首诗词",
                            "${conflict.summary.thoughtCount} thoughts, ${conflict.summary.categoryCount} categories, ${conflict.summary.favoriteCount} browser bookmarks, ${conflict.summary.dateRecordCount} date records, ${conflict.summary.poemCount} poems",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = onImportExistingBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("导入已有备份", "Import existing backup")) }
                    OutlinedButton(
                        onClick = onOverwriteExistingBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("用当前数据覆盖", "Replace with current data")) }
                    TextButton(
                        onClick = onCancelFolderConflict,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(tr("取消", "Cancel")) }
                }
            },
        )
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(tr("导入应用数据？", "Import app data?")) },
            text = {
                Text(
                    tr(
                        "导入会替换当前设置、小巧思及其分类、浏览器收藏夹、日期记录和诗词本。日记正文和媒体文件不会被修改。确定继续吗？",
                        "Importing replaces the current settings, thoughts and categories, browser bookmarks, date records, and poetry book. Diary entries and media files are not changed. Continue?",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri = null
                        onImport(Uri.parse(uri))
                    },
                ) { Text(tr("导入并替换", "Import and replace")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("自动保存文件夹", "Auto-save folder")) {
                Text(
                    tr(
                        "选择一个独立文件夹后，每次应用内容发生更改都会自动保存到 DeskCubby.json。",
                        "Choose a dedicated folder to save automatically to DeskCubby.json whenever app data changes.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                FolderButton(
                    title = if (backupTreeUri == null) {
                        tr("选择文件夹", "Choose folder")
                    } else {
                        tr("更换文件夹", "Change folder")
                    },
                    uri = backupTreeUri,
                    enabled = !busy,
                    onClick = { folderPicker.launch(backupTreeUri?.let(Uri::parse)) },
                )
                if (backupTreeUri != null) {
                    Button(
                        onClick = onSaveNow,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("立即保存", "Save now"))
                    }
                    OutlinedButton(
                        onClick = onDisableAutoBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("停止自动保存", "Stop auto-save")) }
                }
            }
        }
        item {
            SettingsSection(tr("备份内容", "Backup contents")) {
                Text(
                    tr(
                        "包含应用设置（含 AI API Key）、小巧思及其分类、浏览器收藏夹、日期记录、诗词本，以及日记和媒体目录路径；不包含日记正文或媒体文件。",
                        "Includes app settings (including AI API keys), thoughts and categories, browser bookmarks, date records, the poetry book, and diary/media folder paths; diary entries and media files are not included.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    tr(
                        "跨设备导入后需重新选择日记和媒体目录；没有本机授权的目录路径不会覆盖当前目录。",
                        "After importing on another device, reselect diary and media folders; paths without local access do not replace current folders.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SettingsSection(tr("JSON 导入与导出", "JSON import & export")) {
                Text(
                    tr(
                        "可将应用内容保存为单个 JSON 文件，或从之前的备份恢复。",
                        "Save app data as one JSON file or restore it from an earlier backup.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    tr(
                        "JSON 未加密，并包含明文 AI API Key、记录内容、收藏网址和目录信息，请勿存入公开或共享目录。",
                        "JSON is not encrypted and includes plain-text AI API keys, recorded content, bookmarked URLs and folder information. Do not store it in a public or shared folder.",
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { exportPicker.launch(defaultBackupFileName()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("导出 JSON", "Export JSON")) }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("导入 JSON", "Import JSON")) }
            }
        }
        item {
            SettingsSection(tr("保存状态", "Save status")) {
                if (busy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                        Text(tr("正在处理…", "Working…"))
                    }
                }
                autoBackupStatus.lastSavedAt?.let { savedAt ->
                    Text(
                        tr("上次自动保存：", "Last auto-save: ") + formatBackupTime(savedAt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                autoBackupStatus.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        tr("自动保存错误：", "Auto-save error: ") + error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                operation.message?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                operation.error?.let { error ->
                    Text(
                        tr("操作失败：", "Operation failed: ") + error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!busy && autoBackupStatus.lastSavedAt == null && autoBackupStatus.error == null &&
                    operation.message == null && operation.error == null
                ) {
                    Text(
                        if (backupTreeUri == null) tr("尚未开启自动保存", "Auto-save is off")
                        else tr("等待首次自动保存", "Waiting for the first auto-save"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (VisualStyle, DarkMode, AppLanguage, Int, List<Int>, Float) -> Unit,
) {
    val presets = listOf(
        0xFF42664D.toInt(),
        0xFF4C63A6.toInt(),
        0xFFC44B75.toInt(),
        0xFFE57C23.toInt(),
        0xFF7B5EA7.toInt(),
        0xFF00897B.toInt(),
    )
    var visualStyle by rememberSaveable(settings.visualStyle) { mutableStateOf(settings.visualStyle) }
    var darkMode by rememberSaveable(settings.darkMode) { mutableStateOf(settings.darkMode) }
    var language by rememberSaveable(settings.appLanguage) { mutableStateOf(settings.appLanguage) }
    var themeHex by rememberSaveable(settings.themeColorArgb) { mutableStateOf(colorToHex(settings.themeColorArgb)) }
    var secondaryHexes by rememberSaveable(settings.themeSecondaryColorsArgb) {
        mutableStateOf(
            settings.themeSecondaryColorsArgb
                .take(5)
                .map(::colorToHex)
                .let { saved ->
                    if (saved.size >= 2) saved else {
                        (saved + presets.map(::colorToHex)).distinct().take(2)
                    }
                },
        )
    }
    var fontScale by rememberSaveable(settings.fontScale) {
        mutableStateOf(settings.fontScale.coerceIn(0.8f, 1.3f))
    }
    val parsedThemeColor = parseThemeColor(themeHex)
    val parsedSecondaryColors = secondaryHexes.map(::parseThemeColor)
    val validSecondaryValues = parsedSecondaryColors.filterNotNull()
    val secondaryColorsUnique = validSecondaryValues.distinct().size == secondaryHexes.size
    val secondaryColorsValid = secondaryHexes.size in 2..5 &&
        parsedSecondaryColors.all { it != null } && secondaryColorsUnique
    val appearanceDirty = visualStyle != settings.visualStyle ||
        darkMode != settings.darkMode || language != settings.appLanguage ||
        parsedThemeColor != settings.themeColorArgb ||
        validSecondaryValues != settings.themeSecondaryColorsArgb ||
        fontScale != settings.fontScale
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = appearanceDirty,
        enabled = parsedThemeColor != null && secondaryColorsValid,
    ) {
        onSave(
            visualStyle,
            darkMode,
            language,
            parsedThemeColor ?: settings.themeColorArgb,
            parsedSecondaryColors.filterNotNull(),
            fontScale,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("界面风格", "Visual style")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    VisualStyle.entries.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = visualStyle == style,
                            onClick = { visualStyle = style },
                            shape = SegmentedButtonDefaults.itemShape(index, VisualStyle.entries.size),
                        ) {
                            Text(
                                when (style) {
                                    VisualStyle.MATERIAL -> tr("原生", "Material")
                                    VisualStyle.LIQUID_GLASS -> tr("玻璃", "Glass")
                                    VisualStyle.ORGANIC_FUTURE -> tr("有机未来", "Organic")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    when (visualStyle) {
                        VisualStyle.MATERIAL -> tr(
                            "安卓原生 · 清晰、直接的 Material 界面",
                            "Material · Clear, direct Android UI",
                        )
                        VisualStyle.LIQUID_GLASS -> tr(
                            "透明玻璃 · 轻盈的半透明层次",
                            "Liquid Glass · Light translucent layers",
                        )
                        VisualStyle.ORGANIC_FUTURE -> tr(
                            "有机未来 · 森林色、哑光有机面板与杂志式层级",
                            "Organic Future · Forest tones, matte organic panels, and editorial type",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("明暗模式", "Dark mode")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DarkMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = darkMode == mode,
                            onClick = { darkMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                        ) {
                            Text(
                                when (mode) {
                                    DarkMode.SYSTEM -> tr("跟随", "System")
                                    DarkMode.LIGHT -> tr("浅色", "Light")
                                    DarkMode.DARK -> tr("深色", "Dark")
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("软件语言", "App language")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AppLanguage.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = language == item,
                            onClick = { language = item },
                            shape = SegmentedButtonDefaults.itemShape(index, AppLanguage.entries.size),
                        ) { Text(if (item == AppLanguage.CHINESE) "中文" else "English") }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("主题颜色", "Theme colors")) {
                Text(
                    tr("主颜色", "Primary color"),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    presets.forEach { value ->
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(value))
                                .clickable { themeHex = colorToHex(value) },
                        )
                    }
                }
                OutlinedTextField(
                    value = themeHex,
                    onValueChange = { themeHex = it.take(7) },
                    label = { Text(tr("主颜色 Hex", "Primary color hex")) },
                    supportingText = { Text(tr("输入 #RRGGBB，例如 #42664D", "Enter #RRGGBB, for example #42664D")) },
                    isError = parsedThemeColor == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("副颜色", "Secondary colors"),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            tr("设置 2–5 个，有机未来会轮换使用", "Choose 2–5; Organic Future rotates through them"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        enabled = secondaryHexes.size < 5,
                        onClick = {
                            val used = parsedSecondaryColors.filterNotNull().toSet()
                            val next = presets.firstOrNull { it !in used }
                                ?: presets[(secondaryHexes.size + 1) % presets.size]
                            secondaryHexes = secondaryHexes + colorToHex(next)
                        },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(tr("添加", "Add"))
                    }
                }
                secondaryHexes.forEachIndexed { index, value ->
                    val parsed = parsedSecondaryColors[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(parsed?.let(::Color) ?: MaterialTheme.colorScheme.errorContainer),
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = value,
                            onValueChange = { changed ->
                                secondaryHexes = secondaryHexes.toMutableList().apply {
                                    this[index] = changed.take(7)
                                }
                            },
                            label = { Text(tr("副颜色 ${index + 1}", "Secondary ${index + 1}")) },
                            isError = parsed == null,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            enabled = secondaryHexes.size > 2,
                            onClick = {
                                secondaryHexes = secondaryHexes.toMutableList().apply { removeAt(index) }
                            },
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = tr("删除副颜色", "Remove secondary color"))
                        }
                    }
                }
                if (!secondaryColorsValid) {
                    Text(
                        if (!secondaryColorsUnique) {
                            tr("副颜色不能重复", "Secondary colors must be unique")
                        } else {
                            tr("每个颜色都必须使用 #RRGGBB 格式", "Every color must use #RRGGBB format")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SettingsSection(tr("字体大小", "Font size")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("全局字号", "Global type size"))
                    Text("${(fontScale * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = fontScale,
                    onValueChange = { raw ->
                        fontScale = (raw * 20f).roundToInt().div(20f).coerceIn(0.8f, 1.3f)
                    },
                    valueRange = 0.8f..1.3f,
                    steps = 9,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("小 80%", "Small 80%"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("大 130%", "Large 130%"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HomeSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (HomeSettingsDraft) -> Unit,
) {
    var userName by rememberSaveable(settings.userName) { mutableStateOf(settings.userName) }
    var widgetBordersEnabled by rememberSaveable(settings.homeWidgetBordersEnabled) {
        mutableStateOf(settings.homeWidgetBordersEnabled)
    }
    var widgets by remember(settings.homeWidgets) { mutableStateOf(settings.homeWidgets.distinct()) }
    var visibleWidgetTitles by remember(settings.homeWidgetTitles) {
        mutableStateOf(settings.homeWidgetTitles.distinct())
    }
    var mealButtonsUseIcons by rememberSaveable(settings.mealButtonsUseIcons) {
        mutableStateOf(settings.mealButtonsUseIcons)
    }
    var mealButtonIcons by remember(settings.mealButtonIcons) {
        mutableStateOf(
            mealButtonOptions.mapIndexed { index, option ->
                settings.mealButtonIcons.getOrNull(index)?.trim()?.takeIf(String::isNotBlank)
                    ?: option.defaultIcon
            },
        )
    }
    val widgetCenters = remember { mutableStateMapOf<String, Float>() }
    var draggingWidgetId by remember { mutableStateOf<String?>(null) }
    var widgetDragDistancePx by remember { mutableStateOf(0f) }
    var widgetDragOriginY by remember { mutableStateOf<Float?>(null) }
    var widgetDragTargetIndex by remember { mutableStateOf<Int?>(null) }
    val trimmedName = userName.trim()
    val greetingPreview = if (trimmedName.isBlank()) {
        tr("你好！", "Hello!")
    } else {
        tr("你好，$trimmedName！", "Hello, $trimmedName!")
    }
    val widgetDragSourceIndex = draggingWidgetId?.let(widgets::indexOf)?.takeIf { it >= 0 }
    val widgetInsertionSlot = widgetDragSourceIndex?.let { sourceIndex ->
        widgetDragTargetIndex?.let { targetIndex ->
            if (targetIndex > sourceIndex) targetIndex + 1 else targetIndex
        }
    }
    val homeDraft = HomeSettingsDraft(
        userName = trimmedName,
        widgetBordersEnabled = widgetBordersEnabled,
        widgets = widgets,
        visibleWidgetTitles = visibleWidgetTitles,
        mealButtonsUseIcons = mealButtonsUseIcons,
        mealButtonIcons = mealButtonIcons.map(String::trim),
    )
    val homeDirty = homeDraft != HomeSettingsDraft(
        userName = settings.userName,
        widgetBordersEnabled = settings.homeWidgetBordersEnabled,
        widgets = settings.homeWidgets.distinct(),
        visibleWidgetTitles = settings.homeWidgetTitles.distinct(),
        mealButtonsUseIcons = settings.mealButtonsUseIcons,
        mealButtonIcons = settings.mealButtonIcons,
    )
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = homeDirty,
        enabled = mealButtonIcons.all { it.isNotBlank() },
    ) { onSave(homeDraft) }

    fun widgetTargetIndex(distancePx: Float): Int? {
        val origin = widgetDragOriginY ?: return null
        val targetId = widgetCenters.entries
            .asSequence()
            .filter { (id, _) -> id in widgets }
            .minByOrNull { (_, center) -> kotlin.math.abs(center - (origin + distancePx)) }
            ?.key
        return widgets.indexOf(targetId).takeIf { it >= 0 }
    }

    fun clearWidgetDrag() {
        draggingWidgetId = null
        widgetDragDistancePx = 0f
        widgetDragOriginY = null
        widgetDragTargetIndex = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("主页问候", "Home greeting")) {
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it.takeCodePoints(32) },
                    label = { Text(tr("用户名", "User name")) },
                    supportingText = { Text(tr("主页将显示：$greetingPreview", "Home will show: $greetingPreview")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsSection(tr("模块样式", "Widget style")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示模块边框", "Show widget borders"))
                        Text(
                            tr(
                                "关闭后主页模块会更自然地连成一体",
                                "Turn off for a more continuous home layout",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = widgetBordersEnabled,
                        onCheckedChange = { widgetBordersEnabled = it },
                    )
                }
            }
        }
        item {
            SettingsSection(tr("主页模块", "Home widgets")) {
                Text(
                    tr("拖动四点按钮排序，并可单独隐藏标题或移除模块。", "Drag the four-dot handle to reorder, hide individual titles, or remove widgets."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                widgets.forEachIndexed { index, widgetId ->
                    key(widgetId) {
                        val option = homeWidgetOptions.firstOrNull { it.id == widgetId }
                        val isDragging = draggingWidgetId == widgetId
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned {
                                        widgetCenters[widgetId] = it.boundsInRoot().center.y
                                    }
                                    .graphicsLayer {
                                        translationY = if (isDragging) widgetDragDistancePx else 0f
                                        alpha = if (isDragging) 0.62f else 1f
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = option?.let { tr(it.chinese, it.english) } ?: widgetId,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FourDotDragHandle(
                                        translateSelf = false,
                                        onDragStarted = {
                                            draggingWidgetId = widgetId
                                            widgetDragDistancePx = 0f
                                            widgetDragOriginY = widgetCenters[widgetId]
                                            widgetDragTargetIndex = index
                                        },
                                        onDragChanged = { distance ->
                                            widgetDragDistancePx = distance
                                            widgetDragTargetIndex = widgetTargetIndex(distance)
                                        },
                                        onDragCancelled = ::clearWidgetDrag,
                                        onDragFinished = { distance ->
                                            val target = widgetTargetIndex(distance)
                                                ?: widgetDragTargetIndex
                                            clearWidgetDrag()
                                            if (target != null && target in widgets.indices && target != index) {
                                                widgets = widgets.toMutableList().apply {
                                                    val moved = removeAt(index)
                                                    add(target, moved)
                                                }
                                            }
                                        },
                                    )
                                    TextButton(
                                        onClick = {
                                            widgets = widgets - widgetId
                                            visibleWidgetTitles = visibleWidgetTitles - widgetId
                                            widgetCenters.remove(widgetId)
                                        },
                                    ) { Text(tr("移除", "Remove")) }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        tr("显示标题", "Show title"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = widgetId in visibleWidgetTitles,
                                        onCheckedChange = { visible ->
                                            visibleWidgetTitles = if (visible) {
                                                (visibleWidgetTitles + widgetId).distinct()
                                            } else {
                                                visibleWidgetTitles - widgetId
                                            }
                                        },
                                    )
                                }
                            }
                            if (draggingWidgetId != null && widgetInsertionSlot == index) {
                                HorizontalDivider(
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (index == widgets.lastIndex && widgetInsertionSlot == widgets.size) {
                                HorizontalDivider(
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (index != widgets.lastIndex) HorizontalDivider()
                    }
                }
                if (widgets.isEmpty()) {
                    Text(
                        tr("主页暂无模块，可从下方添加。", "The home page has no widgets; add one below."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            val missingWidgets = homeWidgetOptions.filterNot { it.id in widgets }
            SettingsSection(tr("添加模块", "Add widgets")) {
                if (missingWidgets.isEmpty()) {
                    Text(tr("所有模块都已添加", "All widgets have been added"))
                } else {
                    missingWidgets.forEach { option ->
                        OutlinedButton(
                            onClick = {
                                widgets = widgets + option.id
                                visibleWidgetTitles = (visibleWidgetTitles + option.id).distinct()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(tr(option.chinese, option.english))
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("饮食按钮", "Meal buttons")) {
                Text(
                    tr("选择按钮显示文字还是自定义图标。", "Choose text labels or custom icons for the meal buttons."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false, true).forEachIndexed { index, useIcons ->
                        SegmentedButton(
                            selected = mealButtonsUseIcons == useIcons,
                            onClick = { mealButtonsUseIcons = useIcons },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) {
                            Text(if (useIcons) tr("图标", "Icons") else tr("文字", "Text"))
                        }
                    }
                }
                mealButtonOptions.forEachIndexed { index, option ->
                    OutlinedTextField(
                        value = mealButtonIcons[index],
                        onValueChange = { value ->
                            mealButtonIcons = mealButtonIcons.toMutableList().apply {
                                this[index] = value.takeCodePoints(16)
                            }
                        },
                        label = { Text(tr("${option.chinese}图标", "${option.english} icon")) },
                        supportingText = { Text(tr("可输入 emoji 或简短符号", "Enter an emoji or short symbol")) },
                        isError = mealButtonIcons[index].isBlank(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiarySettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (DiarySettingsDraft) -> Unit,
) {
    var diaryTreeUri by remember(settings.diaryTreeUri) { mutableStateOf(settings.diaryTreeUri) }
    var mediaTreeUri by remember(settings.mediaTreeUri) { mutableStateOf(settings.mediaTreeUri) }
    var filePattern by remember(settings.fileNamePattern) { mutableStateOf(settings.fileNamePattern) }
    var template by remember(settings.markdownTemplate) { mutableStateOf(settings.markdownTemplate) }
    var imagePattern by remember(settings.imageNamePattern) { mutableStateOf(settings.imageNamePattern) }
    var imageWidth by remember(settings.imageMaxWidthDp) { mutableStateOf(settings.imageMaxWidthDp.toString()) }
    var imageHeight by remember(settings.imageMaxHeightDp) { mutableStateOf(settings.imageMaxHeightDp.toString()) }
    var mealImageCompressionEnabled by rememberSaveable(settings.mealImageCompressionEnabled) {
        mutableStateOf(settings.mealImageCompressionEnabled)
    }
    var mealImageCompressionQuality by rememberSaveable(settings.mealImageCompressionQuality) {
        mutableIntStateOf(settings.mealImageCompressionQuality)
    }
    var mealCalendarImageMaxHeight by rememberSaveable(settings.mealCalendarImageMaxHeightDp) {
        mutableIntStateOf(settings.mealCalendarImageMaxHeightDp)
    }
    var mealCalendarShowCaptions by rememberSaveable(settings.mealCalendarShowCaptions) {
        mutableStateOf(settings.mealCalendarShowCaptions)
    }
    val textConfigs = settings.aiConfigs.filter { it.type == AiModelType.TEXT }
    val imageConfigs = settings.aiConfigs.filter { it.type == AiModelType.IMAGE }
    var calorieEnabled by rememberSaveable(settings.calorieEstimationEnabled) {
        mutableStateOf(settings.calorieEstimationEnabled)
    }
    var calorieTextConfigId by rememberSaveable(settings.calorieTextConfigId) {
        mutableStateOf(settings.calorieTextConfigId)
    }
    var calorieImageConfigId by rememberSaveable(settings.calorieImageConfigId) {
        mutableStateOf(settings.calorieImageConfigId)
    }
    var calorieVisionPrompt by remember(settings.calorieVisionPrompt) { mutableStateOf(settings.calorieVisionPrompt) }
    var calorieTextPrompt by remember(settings.calorieTextPrompt) { mutableStateOf(settings.calorieTextPrompt) }

    val diaryFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { diaryTreeUri = it.toString() }
    }
    val mediaFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { mediaTreeUri = it.toString() }
    }
    val diaryDraft = DiarySettingsDraft(
        diaryTreeUri = diaryTreeUri,
        mediaTreeUri = mediaTreeUri,
        filePattern = filePattern,
        template = template,
        imagePattern = imagePattern,
        imageWidth = imageWidth.toIntOrNull(),
        imageHeight = imageHeight.toIntOrNull(),
        mealImageCompressionEnabled = mealImageCompressionEnabled,
        mealImageCompressionQuality = mealImageCompressionQuality,
        mealCalendarImageMaxHeightDp = mealCalendarImageMaxHeight,
        mealCalendarShowCaptions = mealCalendarShowCaptions,
        calorieEstimationEnabled = calorieEnabled,
        calorieTextConfigId = calorieTextConfigId,
        calorieImageConfigId = calorieImageConfigId,
        calorieVisionPrompt = calorieVisionPrompt,
        calorieTextPrompt = calorieTextPrompt,
    )
    val diaryDirty = diaryTreeUri != settings.diaryTreeUri || mediaTreeUri != settings.mediaTreeUri ||
        filePattern != settings.fileNamePattern || template != settings.markdownTemplate ||
        imagePattern != settings.imageNamePattern || diaryDraft.imageWidth != settings.imageMaxWidthDp ||
        diaryDraft.imageHeight != settings.imageMaxHeightDp ||
        mealImageCompressionEnabled != settings.mealImageCompressionEnabled ||
        mealImageCompressionQuality != settings.mealImageCompressionQuality ||
        mealCalendarImageMaxHeight != settings.mealCalendarImageMaxHeightDp ||
        mealCalendarShowCaptions != settings.mealCalendarShowCaptions ||
        calorieEnabled != settings.calorieEstimationEnabled ||
        calorieTextConfigId != settings.calorieTextConfigId ||
        calorieImageConfigId != settings.calorieImageConfigId ||
        calorieVisionPrompt != settings.calorieVisionPrompt || calorieTextPrompt != settings.calorieTextPrompt
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = diaryDirty,
        enabled = filePattern.isNotBlank() && imagePattern.isNotBlank() &&
            diaryDraft.imageWidth != null && diaryDraft.imageHeight != null &&
            (!calorieEnabled || textConfigs.any { it.id == calorieTextConfigId } && imageConfigs.any { it.id == calorieImageConfigId }),
    ) { onSave(diaryDraft) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("本地文件", "Local files")) {
                FolderButton(
                    title = tr("日记目录", "Diary folder"),
                    uri = diaryTreeUri,
                    onClick = { diaryFolderPicker.launch(diaryTreeUri?.let(Uri::parse)) },
                )
                Spacer(Modifier.height(8.dp))
                FolderButton(
                    title = tr("媒体目录", "Media folder"),
                    uri = mediaTreeUri,
                    onClick = { mediaFolderPicker.launch(mediaTreeUri?.let(Uri::parse)) },
                )
            }
        }
        item {
            SettingsSection(tr("日记与图片格式", "Diary and image format")) {
                SettingField(filePattern, { filePattern = it }, tr("今日日记文件名格式", "Today's diary filename format"), "yyyy-MM-dd")
                SettingField(imagePattern, { imagePattern = it }, tr("图片命名格式", "Image filename format"), "{date}_{category}_{seq}")
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(tr("默认 Markdown 模板", "Default Markdown template")) },
                    supportingText = { Text(tr("支持 {title} 与 {date}", "Supports {title} and {date}")) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = imageWidth,
                        onValueChange = { imageWidth = it.filter(Char::isDigit) },
                        label = { Text(tr("图片最大宽度 dp", "Max image width (dp)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = imageHeight,
                        onValueChange = { imageHeight = it.filter(Char::isDigit) },
                        label = { Text(tr("图片最大高度 dp", "Max image height (dp)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            SettingsSection(tr("饮食图片压缩", "Meal image compression")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("自动压缩饮食图片", "Compress meal images automatically"))
                        Text(
                            tr(
                                "适用于主页拍照、选图和日记中的餐别图片",
                                "Applies to home camera/gallery photos and categorized meal images in diaries",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = mealImageCompressionEnabled,
                        onCheckedChange = { mealImageCompressionEnabled = it },
                    )
                }
                Text(
                    tr(
                        "压缩质量：$mealImageCompressionQuality%",
                        "Compression quality: $mealImageCompressionQuality%",
                    ),
                    color = if (mealImageCompressionEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Slider(
                    value = mealImageCompressionQuality.toFloat(),
                    onValueChange = {
                        mealImageCompressionQuality = (it / 5f).roundToInt().times(5).coerceIn(30, 95)
                    },
                    enabled = mealImageCompressionEnabled,
                    valueRange = 30f..95f,
                    steps = 12,
                )
                Text(
                    tr(
                        "数值越低文件越小；压缩时最长边同时限制为 2560 像素。",
                        "Lower values create smaller files; the longest edge is also limited to 2560 px.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("吃历显示", "Meal calendar display")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("图片高度上限", "Maximum image height"))
                    Text("$mealCalendarImageMaxHeight dp", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = mealCalendarImageMaxHeight.toFloat(),
                    onValueChange = {
                        mealCalendarImageMaxHeight = (it / 8f).roundToInt().times(8).coerceIn(80, 320)
                    },
                    valueRange = 80f..320f,
                    steps = 29,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示餐别文字", "Show meal captions"))
                        Text(
                            tr("关闭后只显示图片，仍按早餐、午餐、晚餐的顺序排列。", "When off, only photos are shown; meal order stays fixed."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = mealCalendarShowCaptions,
                        onCheckedChange = { mealCalendarShowCaptions = it },
                    )
                }
            }
        }
        item {
            SettingsSection(tr("AI 热量估算", "AI calorie estimation")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("上传饮食图片后自动估算", "Estimate after uploading meal images"))
                        Text(
                            if (textConfigs.isEmpty() || imageConfigs.isEmpty()) tr(
                                "需要先在 AI 配置中添加文字模型和图片模型。",
                                "Add a text model and an image model in AI configurations first.",
                            ) else tr("结果会写入图片标题并显示在吃历。", "Results are written to captions and shown in the meal calendar."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = calorieEnabled,
                        onCheckedChange = { calorieEnabled = it },
                        enabled = textConfigs.isNotEmpty() && imageConfigs.isNotEmpty(),
                    )
                }
                AiConfigurationPicker(
                    label = tr("热量计算文字模型", "Calorie text model"),
                    configs = textConfigs,
                    selectedId = calorieTextConfigId,
                    onSelected = { calorieTextConfigId = it },
                )
                AiConfigurationPicker(
                    label = tr("食物图片识别模型", "Food image model"),
                    configs = imageConfigs,
                    selectedId = calorieImageConfigId,
                    onSelected = { calorieImageConfigId = it },
                )
                OutlinedTextField(
                    value = calorieVisionPrompt,
                    onValueChange = { calorieVisionPrompt = it.take(20_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("图片识别提示词", "Vision prompt")) },
                    minLines = 4,
                )
                OutlinedTextField(
                    value = calorieTextPrompt,
                    onValueChange = { calorieTextPrompt = it.take(20_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("热量计算提示词", "Calorie prompt")) },
                    minLines = 4,
                )
            }
        }
        item {
            if (diaryDraft.imageWidth == null || diaryDraft.imageHeight == null) {
                Text(
                    tr("图片宽度和高度必须填写数字。", "Image width and height must be numbers."),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AiConfigurationPicker(
    label: String,
    configs: List<AiModelConfig>,
    selectedId: String?,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = configs.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = configs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(selected?.name ?: tr("请选择配置", "Select a configuration"), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            configs.forEach { config -> DropdownMenuItem(
                text = { Text(config.name) },
                onClick = { expanded = false; onSelected(config.id) },
            ) }
        }
    }
}

@Composable
private fun BlogSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (String, BrowserTheme, Boolean) -> Unit,
) {
    var browserHome by remember(settings.browserHomeUrl) { mutableStateOf(settings.browserHomeUrl) }
    var browserTheme by remember(settings.browserTheme) { mutableStateOf(settings.browserTheme) }
    var browserDesktopMode by remember(settings.browserDesktopMode) { mutableStateOf(settings.browserDesktopMode) }
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = browserHome != settings.browserHomeUrl || browserTheme != settings.browserTheme ||
            browserDesktopMode != settings.browserDesktopMode,
        enabled = browserHome.isNotBlank(),
    ) { onSave(browserHome, browserTheme, browserDesktopMode) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("默认主页", "Default home page")) {
                OutlinedTextField(
                    value = browserHome,
                    onValueChange = { browserHome = it },
                    label = { Text(tr("网址", "URL")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsSection(tr("浏览器主题", "Browser theme")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BrowserTheme.entries.forEachIndexed { index, theme ->
                        SegmentedButton(
                            selected = browserTheme == theme,
                            onClick = { browserTheme = theme },
                            shape = SegmentedButtonDefaults.itemShape(index, BrowserTheme.entries.size),
                        ) {
                            Text(
                                when (theme) {
                                    BrowserTheme.SYSTEM -> tr("跟随", "System")
                                    BrowserTheme.LIGHT -> tr("浅色", "Light")
                                    BrowserTheme.DARK -> tr("深色", "Dark")
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("网页模式", "Web page mode")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("电脑模式", "Desktop mode"))
                        Text(
                            tr("优先请求网页的桌面版布局", "Prefer the desktop layout of websites"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = browserDesktopMode,
                        onCheckedChange = { browserDesktopMode = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThoughtSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (Int, ThoughtReopenMode, ThoughtDisplayMode) -> Unit,
) {
    var rowHeight by remember(settings.thoughtRowHeightDp) {
        mutableIntStateOf(settings.thoughtRowHeightDp.coerceIn(48, 120))
    }
    var reopenMode by remember(settings.thoughtReopenMode) { mutableStateOf(settings.thoughtReopenMode) }
    var displayMode by remember(settings.thoughtDisplayMode) { mutableStateOf(settings.thoughtDisplayMode) }
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = rowHeight != settings.thoughtRowHeightDp || reopenMode != settings.thoughtReopenMode ||
            displayMode != settings.thoughtDisplayMode,
    ) { onSave(rowHeight, reopenMode, displayMode) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("重新打开", "Reopen behavior")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThoughtReopenMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = reopenMode == mode,
                            onClick = { reopenMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, ThoughtReopenMode.entries.size),
                        ) {
                            Text(
                                if (mode == ThoughtReopenMode.LAST_VISITED) {
                                    tr("上次停留", "Last visited")
                                } else {
                                    tr("全部页面", "All page")
                                },
                            )
                        }
                    }
                }
                Text(
                    tr("记住关闭前所在的分类页，或每次都从“全部”开始。", "Return to the last category, or always start from All."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("内容显示", "Content display")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThoughtDisplayMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = displayMode == mode,
                            onClick = { displayMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, ThoughtDisplayMode.entries.size),
                        ) {
                            Text(
                                if (mode == ThoughtDisplayMode.SINGLE_LINE) tr("一行", "One line")
                                else tr("完整", "Full"),
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("每行高度", "Row height")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("小巧思列表", "Thoughts list"))
                    Text("$rowHeight dp", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = rowHeight.toFloat(),
                    onValueChange = { rowHeight = it.roundToInt().coerceIn(48, 120) },
                    valueRange = 48f..120f,
                    steps = 71,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("紧凑 48 dp", "Compact 48 dp"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("宽松 120 dp", "Spacious 120 dp"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RssSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (Int, Boolean) -> Unit,
) {
    var maxItems by rememberSaveable(settings.rssMaxItemsPerFeed) {
        mutableIntStateOf(settings.rssMaxItemsPerFeed)
    }
    var showSummaries by rememberSaveable(settings.rssShowSummaries) {
        mutableStateOf(settings.rssShowSummaries)
    }
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = maxItems != settings.rssMaxItemsPerFeed || showSummaries != settings.rssShowSummaries,
    ) { onSave(maxItems, showSummaries) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("文章数量", "Article count")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("每个订阅最多显示", "Maximum per feed"))
                    Text(maxItems.toString(), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = maxItems.toFloat(),
                    onValueChange = { maxItems = (it / 10f).roundToInt().times(10).coerceIn(10, 200) },
                    valueRange = 10f..200f,
                    steps = 18,
                )
            }
        }
        item {
            SettingsSection(tr("文章列表", "Article list")) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示摘要", "Show summaries"))
                        Text(
                            tr("关闭后列表只保留标题、订阅名和时间。", "When off, the list keeps only titles, feed names and dates."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = showSummaries, onCheckedChange = { showSummaries = it })
                }
            }
        }
        item {
            Text(
                tr("订阅地址请在 RSS 页面右上角添加和管理。", "Add and manage feed URLs from the RSS page."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiConfigurationsSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onOpen: (AiModelConfig) -> Unit,
    onCopy: (AiModelConfig) -> Unit,
    onDelete: (AiModelConfig) -> Unit,
) {
    var longPressed by remember { mutableStateOf<AiModelConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<AiModelConfig?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (settings.aiConfigs.isEmpty()) item {
            GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.SmartToy, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(tr("还没有 AI 配置", "No AI configurations"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("添加文字模型或图片识别模型。", "Add a text or image-recognition model."),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(settings.aiConfigs, key = AiModelConfig::id) { config ->
            Surface(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onOpen(config) }, onLongClick = { longPressed = config },
                ),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (config.type == AiModelType.TEXT) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                            if (config.type == AiModelType.TEXT) {
                                Text("文", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                            } else {
                                Icon(Icons.Outlined.Image, null, Modifier.size(21.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(config.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (config.type == AiModelType.TEXT) tr("文字", "Text") else tr("图片", "Image"),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("添加配置", "Add configuration"))
            }
        }
    }
    longPressed?.let { config -> AlertDialog(
        onDismissRequest = { longPressed = null },
        title = { Text(config.name) },
        text = { Text(tr("选择要对这项配置执行的操作。", "Choose an action for this configuration.")) },
        confirmButton = { TextButton(onClick = { longPressed = null; onCopy(config) }) { Text(tr("复制配置", "Duplicate")) } },
        dismissButton = { TextButton(onClick = { longPressed = null; pendingDelete = config }) { Text(tr("删除配置", "Delete")) } },
    ) }
    pendingDelete?.let { config -> AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text(tr("删除配置？", "Delete configuration?")) },
        text = { Text(tr("将删除“${config.name}”及其 API Key。", "This deletes “${config.name}” and its API key.")) },
        confirmButton = { TextButton(onClick = { pendingDelete = null; onDelete(config) }) { Text(tr("删除", "Delete")) } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(tr("取消", "Cancel")) } },
    ) }
}

@Composable
private fun AiConfigurationDetailPage(
    initial: AiModelConfig,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (AiModelConfig) -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var type by rememberSaveable(initial.id) { mutableStateOf(initial.type) }
    var endpoint by rememberSaveable(initial.id) { mutableStateOf(initial.endpointUrl) }
    var model by rememberSaveable(initial.id) { mutableStateOf(initial.model) }
    var allowHttp by rememberSaveable(initial.id) { mutableStateOf(initial.allowInsecureHttp) }
    var temperature by rememberSaveable(initial.id) { mutableStateOf(initial.temperature) }
    var systemPrompt by rememberSaveable(initial.id) { mutableStateOf(initial.systemPrompt) }
    var apiKey by rememberSaveable(initial.id) { mutableStateOf(initial.apiKey) }
    var requestPreview by remember(initial.id) { mutableStateOf<String?>(null) }
    val changed = initial.copy(name = name, type = type, endpointUrl = endpoint, model = model,
        allowInsecureHttp = allowHttp, temperature = temperature, systemPrompt = systemPrompt,
        apiKey = apiKey, enabled = true)
    val endpointUri = remember(endpoint) { runCatching { Uri.parse(endpoint.trim()) }.getOrNull() }
    val endpointValid = endpointUri?.host?.isNotBlank() == true && when (endpointUri.scheme?.lowercase()) {
        "https" -> true; "http" -> allowHttp; else -> false
    }
    val dirty = changed != initial.copy(enabled = true)
    RegisterSettingsSave(saveCoordinator, dirty, name.isNotBlank() && model.isNotBlank() && endpointValid) {
        onSave(changed)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(contentPadding), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SettingsSection(tr("配置类型", "Configuration type")) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AiModelType.entries.forEachIndexed { index, option -> SegmentedButton(
                    selected = type == option, onClick = { type = option },
                    shape = SegmentedButtonDefaults.itemShape(index, AiModelType.entries.size),
                ) { Text(if (option == AiModelType.TEXT) tr("文字模型", "Text model") else tr("图片模型", "Image model")) } }
            }
        } }
        item { SettingsSection(tr("模型配置", "Model configuration")) {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(),
                label = { Text(tr("配置名称", "Configuration name")) }, singleLine = true)
            OutlinedTextField(endpoint, { endpoint = it.take(4096) }, Modifier.fillMaxWidth(),
                label = { Text(tr("API 地址", "API endpoint")) }, singleLine = true, isError = !endpointValid)
            OutlinedTextField(model, { model = it.take(512) }, Modifier.fillMaxWidth(),
                label = { Text(tr("模型名称", "Model name")) }, singleLine = true)
            OutlinedTextField(apiKey, { apiKey = it.take(8192) }, Modifier.fillMaxWidth(),
                label = { Text("API Key") }, singleLine = true,
                supportingText = { Text(tr(
                    "明文显示并随配置保存，也会包含在设置备份中。",
                    "Shown and stored as plain text, including in settings backups.",
                )) })
            if (type == AiModelType.TEXT) OutlinedTextField(
                systemPrompt, { systemPrompt = it.take(20_000) }, Modifier.fillMaxWidth(),
                label = { Text(tr("系统提示词", "System prompt")) }, minLines = 4,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("允许 HTTP", "Allow HTTP"))
                    Text(tr("仅用于可信局域网接口。", "Only for trusted local endpoints."), style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(allowHttp, { allowHttp = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("温度", "Temperature")); Text(String.format(Locale.ROOT, "%.1f", temperature))
            }
            Slider(temperature, { temperature = (it * 10).roundToInt() / 10f }, valueRange = 0f..2f, steps = 19)
            OutlinedButton(
                onClick = { requestPreview = buildAiRequestPreviewJson(changed) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("预览请求 JSON", "Preview request JSON"))
            }
        } }
    }

    requestPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { requestPreview = null },
            title = { Text(tr("请求 JSON 预览", "Request JSON preview")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        tr(
                            "占位内容会在实际调用时替换。API Key 位于请求头，不属于 JSON，因此不会显示在这里。",
                            "Placeholders are replaced for real calls. The API key is sent in a header, not in JSON, so it is not shown here.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text = preview,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { requestPreview = null }) { Text(tr("关闭", "Close")) }
            },
        )
    }
}

@Composable
private fun LegacyAiConfigurationsSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (List<AiModelConfig>, String, Boolean, String, String, Map<String, String>, Set<String>) -> Unit,
) {
    var configs by remember(settings.aiConfigs) { mutableStateOf(settings.aiConfigs.map { it.copy() }) }
    var systemPrompt by remember(settings.aiSystemPrompt) { mutableStateOf(settings.aiSystemPrompt) }
    var calorieEnabled by remember(settings.calorieEstimationEnabled) { mutableStateOf(settings.calorieEstimationEnabled) }
    var visionPrompt by remember(settings.calorieVisionPrompt) { mutableStateOf(settings.calorieVisionPrompt) }
    var textPrompt by remember(settings.calorieTextPrompt) { mutableStateOf(settings.calorieTextPrompt) }
    val apiKeys = remember { mutableStateMapOf<String, String>() }
    var deletedIds by remember { mutableStateOf(emptySet<String>()) }
    val hasText = configs.any { it.enabled && it.type == AiModelType.TEXT }
    val hasImage = configs.any { it.enabled && it.type == AiModelType.IMAGE }
    val newConfigName = tr("新配置", "New configuration")
    val dirty = configs != settings.aiConfigs || systemPrompt != settings.aiSystemPrompt ||
        calorieEnabled != settings.calorieEstimationEnabled || visionPrompt != settings.calorieVisionPrompt ||
        textPrompt != settings.calorieTextPrompt || apiKeys.values.any(String::isNotBlank) || deletedIds.isNotEmpty()
    RegisterSettingsSave(saveCoordinator, dirty,
        configs.all { it.name.isNotBlank() && it.endpointUrl.isNotBlank() && it.model.isNotBlank() } &&
            (!calorieEnabled || hasText && hasImage)) {
        onSave(configs, systemPrompt, calorieEnabled, visionPrompt, textPrompt, apiKeys.toMap(), deletedIds)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("AI 配置", "AI configurations")) {
                Text(tr("可分别添加文字与图片识别模型；同类型可保留多套配置并单独启用。",
                    "Add separate text and vision models; each configuration can be enabled independently."),
                    style = MaterialTheme.typography.bodySmall)
                configs.forEachIndexed { index, config ->
                    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(config.name.ifBlank { tr("未命名配置", "Unnamed") }, Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall)
                                Switch(config.enabled, { enabled ->
                                    configs = configs.mapIndexed { i, item ->
                                        if (i == index) item.copy(enabled = enabled) else if (enabled && item.type == config.type) item.copy(enabled = false) else item
                                    }
                                })
                                IconButton(onClick = {
                                    deletedIds += config.id
                                    configs = configs.filterIndexed { i, _ -> i != index }
                                }) { Icon(Icons.Outlined.Close, tr("删除配置", "Delete configuration")) }
                            }
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                AiModelType.entries.forEachIndexed { typeIndex, type ->
                                    SegmentedButton(selected = config.type == type,
                                        onClick = { configs = configs.toMutableList().apply { set(index, config.copy(type = type)) } },
                                        shape = SegmentedButtonDefaults.itemShape(typeIndex, AiModelType.entries.size)) {
                                        Text(if (type == AiModelType.TEXT) tr("文字", "Text") else tr("图片", "Image"))
                                    }
                                }
                            }
                            fun update(changed: AiModelConfig) { configs = configs.toMutableList().apply { set(index, changed) } }
                            OutlinedTextField(config.name, { update(config.copy(name = it.take(80))) },
                                Modifier.fillMaxWidth(), label = { Text(tr("配置名称", "Configuration name")) }, singleLine = true)
                            OutlinedTextField(config.endpointUrl, { update(config.copy(endpointUrl = it.take(4096))) },
                                Modifier.fillMaxWidth(), label = { Text(tr("API 地址", "API endpoint")) }, singleLine = true)
                            OutlinedTextField(config.model, { update(config.copy(model = it.take(512))) },
                                Modifier.fillMaxWidth(), label = { Text(tr("模型名称", "Model name")) }, singleLine = true)
                            OutlinedTextField(apiKeys[config.id].orEmpty(), { apiKeys[config.id] = it.take(8192) },
                                Modifier.fillMaxWidth(), label = { Text(tr("新 API Key（留空则保留）", "New API key (blank keeps existing)")) },
                                singleLine = true)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("允许 HTTP", "Allow HTTP"), Modifier.weight(1f))
                                Checkbox(config.allowInsecureHttp, { update(config.copy(allowInsecureHttp = it)) })
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    configs = configs + AiModelConfig(UUID.randomUUID().toString(),
                        newConfigName, AiModelType.TEXT,
                        "https://api.openai.com/v1/chat/completions", "", enabled = false)
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(tr("添加配置", "Add configuration"))
                }
            }
        }
        item {
            SettingsSection(tr("对话与热量估算", "Chat and calorie estimation")) {
                OutlinedTextField(systemPrompt, { systemPrompt = it.take(20_000) }, Modifier.fillMaxWidth(),
                    label = { Text(tr("聊天系统提示词", "Chat system prompt")) }, minLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("AI 估算热量", "AI calorie estimation"))
                        Text(if (hasText && hasImage) tr("上传饮食图片后自动计算", "Calculate after a food image is uploaded")
                            else tr("请先启用一套文字模型和图片模型", "Enable one text and one image model first"),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(calorieEnabled, { calorieEnabled = it }, enabled = hasText && hasImage)
                }
                OutlinedTextField(visionPrompt, { visionPrompt = it.take(20_000) }, Modifier.fillMaxWidth(),
                    label = { Text(tr("图片识别提示词", "Vision prompt")) }, minLines = 4)
                OutlinedTextField(textPrompt, { textPrompt = it.take(20_000) }, Modifier.fillMaxWidth(),
                    label = { Text(tr("热量计算提示词", "Calorie prompt")) }, minLines = 4)
            }
        }
    }
}

@Composable
private fun LegacySingleAiSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (String, String, String, Float, Boolean, String, Boolean) -> Unit,
) {
    var endpoint by remember(settings.aiEndpointUrl) { mutableStateOf(settings.aiEndpointUrl) }
    var model by remember(settings.aiModel) { mutableStateOf(settings.aiModel) }
    var systemPrompt by remember(settings.aiSystemPrompt) { mutableStateOf(settings.aiSystemPrompt) }
    var temperature by remember(settings.aiTemperature) { mutableStateOf(settings.aiTemperature) }
    var allowInsecureHttp by remember(settings.aiAllowInsecureHttp) {
        mutableStateOf(settings.aiAllowInsecureHttp)
    }
    var apiKey by remember { mutableStateOf("") }
    var clearApiKey by remember { mutableStateOf(false) }
    val endpointUri = remember(endpoint) { runCatching { Uri.parse(endpoint.trim()) }.getOrNull() }
    val endpointValid = endpointUri?.host?.isNotBlank() == true && when (endpointUri.scheme?.lowercase()) {
        "https" -> true
        "http" -> allowInsecureHttp
        else -> false
    }
    val dirty = endpoint.trim() != settings.aiEndpointUrl || model.trim() != settings.aiModel ||
        systemPrompt != settings.aiSystemPrompt || temperature != settings.aiTemperature ||
        allowInsecureHttp != settings.aiAllowInsecureHttp || apiKey.isNotBlank() || clearApiKey
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = dirty,
        enabled = endpointValid && model.isNotBlank() && systemPrompt.length <= 20_000,
    ) {
        onSave(
            endpoint.trim(),
            model.trim(),
            systemPrompt,
            temperature,
            allowInsecureHttp,
            apiKey.trim(),
            clearApiKey,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("兼容 API", "Compatible API")) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it.take(4_096) },
                    label = { Text(tr("聊天接口地址", "Chat endpoint URL")) },
                    supportingText = {
                        Text(tr("填写完整的 /v1/chat/completions 地址", "Enter the full /v1/chat/completions URL"))
                    },
                    isError = !endpointValid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it.take(512) },
                    label = { Text(tr("模型", "Model")) },
                    supportingText = { Text(tr("例如服务商提供的模型 ID", "Use the model ID from your provider")) },
                    isError = model.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("允许 HTTP", "Allow HTTP"))
                        Text(
                            tr("仅用于可信局域网服务；公网 API 应使用 HTTPS。", "Only for trusted local services; public APIs should use HTTPS."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = allowInsecureHttp, onCheckedChange = { allowInsecureHttp = it })
                }
            }
        }
        item {
            SettingsSection(tr("API 密钥", "API key")) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it.take(8_192)
                        if (it.isNotEmpty()) clearApiKey = false
                    },
                    label = { Text(tr("新密钥", "New key")) },
                    supportingText = {
                        Text(
                            tr(
                                "接口地址不变时留空会保留密钥；更换地址时请重新输入。本地服务可以不设密钥。",
                                "Leave blank to keep the key for the same endpoint; re-enter it after changing endpoints. Local services may not need one.",
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = clearApiKey,
                        onCheckedChange = {
                            clearApiKey = it
                            if (it) apiKey = ""
                        },
                    )
                    Text(tr("清除已保存的密钥", "Clear saved key"))
                }
                Text(
                    tr("密钥会以明文随配置保存，并写入 DeskCubby JSON 备份。", "The key is stored as plain text with the configuration and included in DeskCubby JSON backups."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("对话行为", "Chat behavior")) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it.take(20_000) },
                    label = { Text(tr("系统提示词", "System prompt")) },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("温度", "Temperature"))
                    Text(String.format(Locale.ROOT, "%.1f", temperature), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = (it * 10).roundToInt() / 10f },
                    valueRange = 0f..2f,
                    steps = 19,
                )
            }
        }
    }
}

@Composable
private fun NavigationSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (NavItemId, List<NavItemConfig>, Boolean) -> Unit,
) {
    var defaultPage by remember(settings.defaultPage) { mutableStateOf(settings.defaultPage) }
    var navItems by remember(settings.navItems) { mutableStateOf(settings.navItems.map { it.copy() }) }
    var showLabels by remember(settings.bottomNavShowLabels) { mutableStateOf(settings.bottomNavShowLabels) }
    val navCenters = remember { mutableStateMapOf<NavItemId, Float>() }
    var draggingNavId by remember { mutableStateOf<NavItemId?>(null) }
    var navDragDistancePx by remember { mutableStateOf(0f) }
    var navDragOriginY by remember { mutableStateOf<Float?>(null) }
    var navDragTargetIndex by remember { mutableStateOf<Int?>(null) }
    val navDragSourceIndex = draggingNavId?.let { id ->
        navItems.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }
    val navInsertionSlot = navDragSourceIndex?.let { sourceIndex ->
        navDragTargetIndex?.let { targetIndex ->
            if (targetIndex > sourceIndex) targetIndex + 1 else targetIndex
        }
    }

    fun navTargetIndex(distancePx: Float): Int? {
        val origin = navDragOriginY ?: return null
        val targetId = navCenters.entries.filterNot { it.key == draggingNavId }.minByOrNull { (_, center) ->
            kotlin.math.abs(center - (origin + distancePx))
        }?.key
        return navItems.indexOfFirst { it.id == targetId }.takeIf { it >= 0 }
    }

    fun clearNavDrag() {
        draggingNavId = null
        navDragDistancePx = 0f
        navDragOriginY = null
        navDragTargetIndex = null
    }

    fun moveNavItem(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in navItems.indices || toIndex !in navItems.indices || fromIndex == toIndex) {
            return false
        }
        navItems = navItems.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        return true
    }
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = defaultPage != settings.defaultPage || navItems != settings.navItems ||
            showLabels != settings.bottomNavShowLabels,
    ) { onSave(defaultPage, navItems, showLabels) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("导航栏样式", "Navigation bar style")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示文字", "Show labels"))
                        Text(
                            tr("关闭后仅显示图标，导航栏占用高度更低", "Turn off to show icons only and use a shorter navigation bar"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = showLabels, onCheckedChange = { showLabels = it })
                }
            }
        }
        item {
            SettingsSection(tr("默认启动页面", "Default start page")) {
                DefaultPagePicker(defaultPage, navItems) { defaultPage = it }
            }
        }
        item {
            SettingsSection(tr("导航项目", "Navigation items")) {
                Text(
                    tr("设置入口始终保留；其余页面可隐藏、改名、换图标和排序。", "Settings is always available; other items can be hidden, renamed, reordered or given new icons."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                navItems.forEachIndexed { index, item ->
                    key(item.id) {
                        val isDragging = draggingNavId == item.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f),
                        ) {
                            NavConfigRow(
                                modifier = Modifier.graphicsLayer {
                                    translationY = if (isDragging) navDragDistancePx else 0f
                                    alpha = if (isDragging) 0.62f else 1f
                                },
                                item = item,
                                onChange = { changed ->
                                    val changedItems = navItems.toMutableList().apply { set(index, changed) }
                                    navItems = changedItems
                                    if (defaultPage == changed.id && !changed.visible && changed.id != NavItemId.SETTINGS) {
                                        defaultPage = changedItems.firstOrNull {
                                            it.visible || it.id == NavItemId.SETTINGS
                                        }?.id ?: NavItemId.SETTINGS
                                    }
                                },
                                onCenterChanged = { navCenters[item.id] = it },
                                onDragStarted = {
                                    draggingNavId = item.id
                                    navDragDistancePx = 0f
                                    navDragOriginY = navCenters[item.id]
                                    navDragTargetIndex = index
                                },
                                onDragChanged = { distance ->
                                    navDragDistancePx = distance
                                    navDragTargetIndex = navTargetIndex(distance)
                                },
                                onDragCancelled = ::clearNavDrag,
                                onMoveUp = { moveNavItem(index, index - 1) },
                                onMoveDown = { moveNavItem(index, index + 1) },
                                onMove = { distance ->
                                    val target = navTargetIndex(distance) ?: navDragTargetIndex
                                    clearNavDrag()
                                    if (target != null) moveNavItem(index, target)
                                },
                            )
                            if (draggingNavId != null && navInsertionSlot == index) {
                                HorizontalDivider(
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (index == navItems.lastIndex && navInsertionSlot == navItems.size) {
                                HorizontalDivider(
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    thickness = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (index != navItems.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val organic = LocalVisualStyle.current == VisualStyle.ORGANIC_FUTURE
    GlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (organic) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun FolderButton(title: String, uri: String?, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                uri?.let(::displayFolderName) ?: tr("尚未选择", "Not selected"),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

private fun displayFolderName(rawUri: String): String = runCatching {
    val documentId = Uri.decode(Uri.parse(rawUri).lastPathSegment ?: rawUri)
    documentId.substringAfter(':', documentId).takeLast(42)
}.getOrDefault(rawUri.takeLast(42))

@Composable
private fun SettingField(value: String, onValueChange: (String) -> Unit, label: String, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultPagePicker(current: NavItemId, items: List<NavItemConfig>, onSelected: (NavItemId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val visible = items.filter { it.visible || it.id == NavItemId.SETTINGS }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = visible.firstOrNull { it.id == current }?.let { localizedNavLabel(it) }
                ?: tr(current.defaultLabel, current.englishLabel),
            onValueChange = {},
            readOnly = true,
            label = { Text(tr("默认启动页面", "Default start page")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            visible.forEach { item ->
                DropdownMenuItem(
                    text = { Text(localizedNavLabel(item)) },
                    leadingIcon = { Icon(iconFor(item.iconKey), contentDescription = null) },
                    onClick = {
                        onSelected(item.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NavConfigRow(
    modifier: Modifier = Modifier,
    item: NavItemConfig,
    onChange: (NavItemConfig) -> Unit,
    onCenterChanged: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onDragChanged: (Float) -> Unit,
    onDragCancelled: () -> Unit,
    onMoveUp: () -> Boolean,
    onMoveDown: () -> Boolean,
    onMove: (Float) -> Unit,
) {
    var iconMenu by remember { mutableStateOf(false) }
    val icons = listOf(
        "home", "book", "poetry", "language", "bolt", "settings", "calendar",
        "event", "rss", "ai", "star", "write", "sparkle", "day",
    )
    val visibilityDescription = tr(
        "${item.id.defaultLabel}是否显示",
        "Show ${item.id.englishLabel}",
    )

    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { onCenterChanged(it.boundsInRoot().center.y) }
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            IconButton(onClick = { iconMenu = true }) {
                Icon(iconFor(item.iconKey), tr("选择图标", "Choose icon"))
            }
            DropdownMenu(expanded = iconMenu, onDismissRequest = { iconMenu = false }) {
                icons.chunked(5).forEach { row ->
                    Row {
                        row.forEach { key ->
                            IconButton(
                                onClick = {
                                    onChange(item.copy(iconKey = key))
                                    iconMenu = false
                                },
                            ) { Icon(iconFor(key), key) }
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            value = item.label,
            onValueChange = { onChange(item.copy(label = it.take(8))) },
            singleLine = true,
            label = { Text(tr(item.id.defaultLabel, item.id.englishLabel)) },
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = item.visible || item.id == NavItemId.SETTINGS,
            enabled = item.id != NavItemId.SETTINGS,
            onCheckedChange = { onChange(item.copy(visible = it)) },
            modifier = Modifier.semantics {
                contentDescription = visibilityDescription
            },
        )
        FourDotDragHandle(
            translateSelf = false,
            onDragStarted = onDragStarted,
            onDragChanged = onDragChanged,
            onDragCancelled = onDragCancelled,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onDragFinished = onMove,
        )
    }
}

@Composable
private fun pageTitle(page: SettingsPage): String = when (page) {
    SettingsPage.MAIN -> tr("设置", "Settings")
    SettingsPage.APPEARANCE -> tr("外观与语言", "Appearance & language")
    SettingsPage.SUBPAGES -> tr("子页面设置", "Subpage settings")
    SettingsPage.HOME -> tr("主页", "Home")
    SettingsPage.BACKUP -> tr("应用数据与备份", "App data & backup")
    SettingsPage.DIARY -> tr("日记与媒体", "Diary & media")
    SettingsPage.BLOG -> tr("浏览器", "Browser")
    SettingsPage.THOUGHT -> tr("小巧思", "Thoughts")
    SettingsPage.RSS -> tr("RSS 订阅", "RSS")
    SettingsPage.AI -> tr("AI 配置", "AI configurations")
    SettingsPage.AI_DETAIL -> tr("AI 配置详情", "AI configuration")
    SettingsPage.NAVIGATION -> tr("底部导航", "Bottom navigation")
}

private fun parentSettingsPage(page: SettingsPage): SettingsPage = when (page) {
    SettingsPage.HOME,
    SettingsPage.DIARY,
    SettingsPage.BLOG,
    SettingsPage.THOUGHT,
    SettingsPage.RSS,
    SettingsPage.AI,
    -> SettingsPage.SUBPAGES

    SettingsPage.AI_DETAIL -> SettingsPage.AI

    SettingsPage.MAIN,
    SettingsPage.APPEARANCE,
    SettingsPage.SUBPAGES,
    SettingsPage.BACKUP,
    SettingsPage.NAVIGATION,
    -> SettingsPage.MAIN
}

private fun defaultBackupFileName(): String =
    "DeskCubby-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.json"

private fun formatBackupTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun parseThemeColor(raw: String): Int? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
    return (0xFF000000L or hex.toLong(16)).toInt()
}

private fun colorToHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private const val GITHUB_URL = "https://github.com/vexpaer/DeskCubby"

@Composable
private fun localizedNavLabel(item: NavItemConfig): String =
    if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == AppLanguage.ENGLISH && item.label.isDefaultLabelFor(item.id)) {
        item.id.englishLabel
    } else {
        item.label
    }

private fun String.isDefaultLabelFor(id: NavItemId): Boolean =
    this == id.defaultLabel || (id == NavItemId.BLOG && this == "博客") || (id == NavItemId.THOUGHT && this == "闪思")
