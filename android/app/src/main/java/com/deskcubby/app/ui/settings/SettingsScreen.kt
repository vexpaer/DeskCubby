package com.deskcubby.app.ui.settings

import android.Manifest
import android.net.Uri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Sync
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import androidx.core.content.ContextCompat
import com.deskcubby.app.BuildConfig
import com.deskcubby.app.R
import com.deskcubby.app.takeCodePoints
import com.deskcubby.app.data.backup.BackupSummary
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.CustomThemeBaseStyle
import com.deskcubby.app.data.model.CustomThemePalette
import com.deskcubby.app.data.model.CustomThemeSettings
import com.deskcubby.app.data.model.DEFAULT_AGENT_PROMPT
import com.deskcubby.app.data.model.DEFAULT_AI_PAGE_FONT_SIZE_SP
import com.deskcubby.app.data.model.DEFAULT_AI_REPLY_BOX_WIDTH_DP
import com.deskcubby.app.data.model.MAX_AI_PAGE_FONT_SIZE_SP
import com.deskcubby.app.data.model.MAX_AI_REPLY_BOX_WIDTH_DP
import com.deskcubby.app.data.model.MAX_MORE_PAGE_COLUMNS
import com.deskcubby.app.data.model.MIN_AI_PAGE_FONT_SIZE_SP
import com.deskcubby.app.data.model.MIN_AI_REPLY_BOX_WIDTH_DP
import com.deskcubby.app.data.model.MIN_MORE_PAGE_COLUMNS
import com.deskcubby.app.data.model.DEFAULT_CLOUD_SYNC_USER_AGENT
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.OrientationPreference
import com.deskcubby.app.data.model.MAX_CUSTOM_THEME_ANIMATION_SCALE
import com.deskcubby.app.data.model.MAX_CUSTOM_THEME_BORDER_WIDTH_DP
import com.deskcubby.app.data.model.MAX_CUSTOM_THEME_CORNER_RADIUS_DP
import com.deskcubby.app.data.model.MAX_CUSTOM_THEME_ELEVATION_DP
import com.deskcubby.app.data.model.MAX_CUSTOM_THEME_PANEL_OPACITY
import com.deskcubby.app.data.model.MAX_CUSTOM_THEME_SPACING_SCALE
import com.deskcubby.app.data.model.MIN_CUSTOM_THEME_ANIMATION_SCALE
import com.deskcubby.app.data.model.MIN_CUSTOM_THEME_BORDER_WIDTH_DP
import com.deskcubby.app.data.model.MIN_CUSTOM_THEME_CORNER_RADIUS_DP
import com.deskcubby.app.data.model.MIN_CUSTOM_THEME_ELEVATION_DP
import com.deskcubby.app.data.model.MIN_CUSTOM_THEME_PANEL_OPACITY
import com.deskcubby.app.data.model.MIN_CUSTOM_THEME_SPACING_SCALE
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.LauncherIcon
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.MAX_POETRY_FONT_SIZE_SP
import com.deskcubby.app.data.model.MAX_POETRY_LINE_SPACING
import com.deskcubby.app.data.model.MAX_MARKDOWN_HEADING_SIZE_SP
import com.deskcubby.app.data.model.MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MIN_POETRY_FONT_SIZE_SP
import com.deskcubby.app.data.model.MIN_POETRY_LINE_SPACING
import com.deskcubby.app.data.model.MIN_MARKDOWN_HEADING_SIZE_SP
import com.deskcubby.app.data.model.MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MAX_VAULT_ROW_HEIGHT_DP
import com.deskcubby.app.data.model.normalizeMarkdownHeadingSizes
import com.deskcubby.app.data.model.MIN_VAULT_ROW_HEIGHT_DP
import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.PoetryTextAlignment
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.ThoughtReopenMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.normalized
import com.deskcubby.app.data.repository.UpdateCheckResult
import com.deskcubby.app.data.repository.UpdateDownloadFailure
import com.deskcubby.app.data.repository.buildAiRequestPreviewJson
import com.deskcubby.app.data.preferences.MAX_HOME_GREETINGS
import com.deskcubby.app.data.preferences.MAX_HOME_GREETING_CODE_POINTS
import com.deskcubby.app.data.sync.AppCloudSyncStatus
import com.deskcubby.app.data.sync.CloudSyncRunMode
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.components.ColorPickerDialog
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.components.OrganicSplitActionRow
import com.deskcubby.app.ui.components.PageTutorialTarget
import com.deskcubby.app.ui.home.HomeGreeting
import com.deskcubby.app.ui.poetry.rememberPoetryFontFamily
import com.deskcubby.app.ui.poetry.isSevenCharacterPoem
import com.deskcubby.app.ui.poetry.wrapSevenCharacterVerse
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.LocalCompactMode
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.tr
import java.text.DateFormat
import java.time.Clock
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.exp
import kotlin.math.ln

private enum class SettingsPage {
    MAIN,
    APPEARANCE,
    SUBPAGES,
    HOME,
    HOME_GREETING,
    BACKUP,
    SYNC,
    SYNC_DETAIL,
    DIARY,
    BLOG,
    THOUGHT,
    VAULT,
    POETRY,
    RSS,
    AI,
    AI_CONFIGS,
    AI_DETAIL,
    NAVIGATION,
    MORE_PAGE,
    USAGE,
    STEPS,
    ABOUT,
}

enum class SettingsStartPage { MAIN, NAVIGATION, MORE_PAGE, USAGE, STEPS, RSS, AI, POETRY }

private fun SettingsStartPage.toSettingsPage(): SettingsPage = when (this) {
    SettingsStartPage.MAIN -> SettingsPage.MAIN
    SettingsStartPage.NAVIGATION -> SettingsPage.NAVIGATION
    SettingsStartPage.MORE_PAGE -> SettingsPage.MORE_PAGE
    SettingsStartPage.USAGE -> SettingsPage.USAGE
    SettingsStartPage.STEPS -> SettingsPage.STEPS
    SettingsStartPage.RSS -> SettingsPage.RSS
    SettingsStartPage.AI -> SettingsPage.AI
    SettingsStartPage.POETRY -> SettingsPage.POETRY
}

private class SettingsSaveCoordinator {
    var available by mutableStateOf(false)
        private set
    var dirty by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(false)
        private set
    var resetAvailable by mutableStateOf(false)
        private set
    private var saveAction: (() -> Unit)? = null
    private var resetAction: (() -> Unit)? = null

    fun register(
        dirty: Boolean,
        enabled: Boolean,
        action: () -> Unit,
        resetAction: (() -> Unit)?,
    ) {
        available = true
        this.dirty = dirty
        this.enabled = enabled
        saveAction = action
        this.resetAction = resetAction
        resetAvailable = resetAction != null
    }

    fun clear() {
        available = false
        dirty = false
        enabled = false
        resetAvailable = false
        saveAction = null
        resetAction = null
    }

    fun save() {
        if (available && dirty && enabled) saveAction?.invoke()
    }

    fun reset() {
        if (resetAvailable) resetAction?.invoke()
    }
}

@Composable
private fun RegisterSettingsSave(
    coordinator: SettingsSaveCoordinator,
    dirty: Boolean,
    enabled: Boolean = true,
    onReset: (() -> Unit)? = null,
    onSave: () -> Unit,
) {
    val currentOnSave by rememberUpdatedState(onSave)
    val currentOnReset by rememberUpdatedState(onReset)
    val stableAction = remember(coordinator) { { currentOnSave() } }
    val stableReset: () -> Unit = remember(coordinator) {
        {
            currentOnReset?.invoke()
            Unit
        }
    }
    SideEffect {
        coordinator.register(
            dirty = dirty,
            enabled = enabled,
            action = stableAction,
            resetAction = stableReset.takeIf { onReset != null },
        )
    }
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
    val gameShortcuts: List<String>,
    val visibleWidgetTitles: List<String>,
    val mealButtonsUseIcons: Boolean,
    val mealButtonIcons: List<String>,
)

private val HomeGreetingTemplateListSaver =
    Saver<List<HomeGreetingTemplate>, ArrayList<String>>(
        save = { items ->
            ArrayList<String>(items.size * 2).apply {
                items.forEach { item ->
                    add(item.chinese)
                    add(item.english)
                }
            }
        },
        restore = { values ->
            values.chunked(2).mapNotNull { pair ->
                pair.getOrNull(1)?.let { english ->
                    HomeGreetingTemplate(
                        chinese = pair[0],
                        english = english,
                    )
                }
            }
        },
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
    HomeWidgetOption("notes", "笔记入口", "Notes shortcut"),
    HomeWidgetOption("game_shortcuts", "小游戏快捷入口", "Mini-game shortcuts"),
    HomeWidgetOption("record_overview", "记录概览", "Record overview"),
    HomeWidgetOption("cloud_sync_now", "立即同步", "Sync now"),
    HomeWidgetOption("cloud_sync_force", "强制上传/下载", "Force upload/download"),
)

private val homeGameShortcutOptions = listOf(
    HomeWidgetOption("2048", "2048 · 4×4", "2048 · 4×4"),
    HomeWidgetOption("2048_5", "2048 · 5×5", "2048 · 5×5"),
    HomeWidgetOption("2048_6", "2048 · 6×6", "2048 · 6×6"),
    HomeWidgetOption("snake", "贪吃蛇", "Snake"),
    HomeWidgetOption("tetris", "俄罗斯方块", "Tetris"),
    HomeWidgetOption("minesweeper", "扫雷", "Minesweeper"),
    HomeWidgetOption("spider", "蜘蛛纸牌", "Spider Solitaire"),
    HomeWidgetOption("go", "围棋", "Go"),
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
    val markdownHeadingSizesSp: List<Float>,
    val mealImageCompressionEnabled: Boolean,
    val mealImageCompressionQuality: Int,
    val saveOriginalToGallery: Boolean,
    val photoLocationEnabled: Boolean,
    val mealCalendarImageMaxHeightDp: Int,
    val mealCalendarShowCaptions: Boolean,
    val mealCalendarWrapEnabled: Boolean,
    val mealCalendarPhotosPerRow: MealPhotosPerRow,
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
    onTutorialTargetChanged: (PageTutorialTarget?) -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backupOperation by viewModel.backupOperation.collectAsStateWithLifecycle()
    val backupImportPreview by viewModel.backupImportPreview.collectAsStateWithLifecycle()
    val cloudSyncStatus by viewModel.cloudSyncStatus.collectAsStateWithLifecycle()
    val cloudSyncUndoAvailable by viewModel.cloudSyncUndoAvailable.collectAsStateWithLifecycle()
    val appDataUsage by viewModel.appDataUsage.collectAsStateWithLifecycle()
    val settingsError by viewModel.settingsError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val rootPage = remember(startPage) { startPage.toSettingsPage() }
    var page by rememberSaveable(startPage) { mutableStateOf(rootPage) }
    val saveCoordinator = remember { SettingsSaveCoordinator() }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var pendingNavigationAfterUnsaved by remember { mutableStateOf<(() -> Unit)?>(null) }
    var editingAiConfig by remember { mutableStateOf<AiModelConfig?>(null) }
    var editingCloudSyncConfig by remember { mutableStateOf<CloudSyncConfig?>(null) }
    val tutorialTarget = settingsPageTutorialTarget(page)

    LaunchedEffect(page) {
        saveCoordinator.clear()
        onSubpageOpenChanged(page != SettingsPage.MAIN)
        if (page == SettingsPage.BACKUP && appDataUsage.snapshot == null &&
            !appDataUsage.loading
        ) {
            viewModel.refreshAppDataUsage()
        }
    }
    LaunchedEffect(tutorialTarget) {
        onTutorialTargetChanged(tutorialTarget)
    }
    DisposableEffect(Unit) {
        onDispose {
            onSubpageOpenChanged(false)
            onTutorialTargetChanged(null)
        }
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
        pendingNavigationAfterUnsaved = null
        if (saveCoordinator.dirty) {
            showUnsavedDialog = true
        } else {
            exitOrOpenParent()
        }
    }

    fun navigateAfterHandlingUnsaved(action: () -> Unit) {
        if (saveCoordinator.dirty) {
            pendingNavigationAfterUnsaved = action
            showUnsavedDialog = true
        } else {
            action()
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
                    if (saveCoordinator.resetAvailable) {
                        IconButton(onClick = saveCoordinator::reset) {
                            Icon(
                                Icons.Outlined.Restore,
                                contentDescription = tr(
                                    "重置本页所有设置",
                                    "Reset all settings on this page",
                                ),
                            )
                        }
                    }
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
                onPersistBackground = viewModel::persistAppBackground,
                onOrientationChange = viewModel::setOrientationPreference,
                onSave = { visualStyle, customTheme, darkMode, language, themeColor, secondaryColors,
                        fontScale, compactMode, backgroundUri, backgroundOpacity,
                        backgroundBlur, onDone ->
                    viewModel.setAppearanceSettings(
                        visualStyle = visualStyle,
                        customTheme = customTheme,
                        darkMode = darkMode,
                        appLanguage = language,
                        themeColorArgb = themeColor,
                        themeSecondaryColorsArgb = secondaryColors,
                        fontScale = fontScale,
                        compactMode = compactMode,
                        backgroundImageUri = backgroundUri,
                        backgroundImageOpacity = backgroundOpacity,
                        backgroundImageBlurDp = backgroundBlur,
                    ) { saved ->
                        onDone(saved)
                        if (saved) completeSave(SettingsPage.MAIN)
                    }
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
                onOpenGreeting = {
                    if (saveCoordinator.dirty) {
                        showUnsavedDialog = true
                    } else {
                        page = SettingsPage.HOME_GREETING
                    }
                },
                onSave = { draft ->
                    viewModel.setHomePageSettings(
                        userName = draft.userName,
                        widgetBordersEnabled = draft.widgetBordersEnabled,
                        widgets = draft.widgets,
                        gameShortcuts = draft.gameShortcuts,
                        visibleWidgetTitles = draft.visibleWidgetTitles,
                        mealButtonsUseIcons = draft.mealButtonsUseIcons,
                        mealButtonIcons = draft.mealButtonIcons,
                    )
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.HOME_GREETING -> HomeGreetingSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { userName, greetings ->
                    viewModel.setHomeGreetingSettings(userName, greetings) { saved ->
                        if (saved) completeSave(SettingsPage.HOME)
                    }
                },
            )

            SettingsPage.SYNC -> CloudSyncSettingsPage(
                settings = settings,
                status = cloudSyncStatus,
                undoAvailable = cloudSyncUndoAvailable,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSaveEnabled = { enabled ->
                    viewModel.setCloudSyncEnabled(enabled) { saved ->
                        if (saved) {
                            val pendingNavigation = pendingNavigationAfterUnsaved
                            pendingNavigationAfterUnsaved = null
                            if (pendingNavigation == null) {
                                completeSave(SettingsPage.BACKUP)
                            } else {
                                saveCoordinator.clear()
                                pendingNavigation()
                            }
                        }
                    }
                },
                onAdd = {
                    navigateAfterHandlingUnsaved {
                        editingCloudSyncConfig = CloudSyncConfig(
                            id = UUID.randomUUID().toString(),
                            name = "",
                        )
                        page = SettingsPage.SYNC_DETAIL
                    }
                },
                onEdit = { config ->
                    navigateAfterHandlingUnsaved {
                        editingCloudSyncConfig = viewModel.cloudSyncConfigForEdit(config)
                        page = SettingsPage.SYNC_DETAIL
                    }
                },
                onCopy = { config ->
                    navigateAfterHandlingUnsaved {
                        viewModel.copyCloudSyncConfig(config)
                    }
                },
                onDelete = { config ->
                    navigateAfterHandlingUnsaved {
                        viewModel.deleteCloudSyncConfig(config)
                    }
                },
                onSyncNow = viewModel::syncCloudNow,
                onUndo = viewModel::undoLastCloudSync,
                onForceUpload = viewModel::forceUploadCloudNow,
                onForceDownload = viewModel::forceDownloadCloudNow,
            )

            SettingsPage.SYNC_DETAIL -> {
                val config = editingCloudSyncConfig
                if (config == null) {
                    LaunchedEffect(Unit) { page = SettingsPage.SYNC }
                } else {
                    CloudSyncConfigDetailPage(
                        initial = config,
                        hasStoredCredentials = viewModel.hasCloudSyncCredentials(config),
                        contentPadding = inner,
                        saveCoordinator = saveCoordinator,
                        onSave = { changed, clearCredentials ->
                            viewModel.saveCloudSyncConfig(
                                config = changed,
                                clearExistingCredentials = clearCredentials,
                            ) { saved ->
                                if (saved) {
                                    editingCloudSyncConfig = null
                                    completeSave(SettingsPage.SYNC)
                                }
                            }
                        },
                    )
                }
            }

            SettingsPage.BACKUP -> BackupSettingsPage(
                settings = settings,
                dataUsage = appDataUsage,
                operation = backupOperation,
                importPreview = backupImportPreview,
                contentPadding = inner,
                onExport = viewModel::exportBackup,
                onImportPreview = viewModel::previewBackupImport,
                onConfirmImport = viewModel::confirmBackupImport,
                onCloseImportPreview = viewModel::closeBackupImportPreview,
                onOpenCloudSync = { page = SettingsPage.SYNC },
                onRefreshDataUsage = viewModel::refreshAppDataUsage,
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
                    viewModel.setMarkdownHeadingSizes(draft.markdownHeadingSizesSp)
                    viewModel.setMealImageCompressionEnabled(draft.mealImageCompressionEnabled)
                    viewModel.setMealImageCompressionQuality(draft.mealImageCompressionQuality)
                    viewModel.setSaveOriginalToGallery(draft.saveOriginalToGallery)
                    viewModel.setPhotoLocationEnabled(draft.photoLocationEnabled)
                    viewModel.setMealCalendarImageMaxHeight(draft.mealCalendarImageMaxHeightDp)
                    viewModel.setMealCalendarShowCaptions(draft.mealCalendarShowCaptions)
                    viewModel.setMealCalendarWrap(
                        enabled = draft.mealCalendarWrapEnabled,
                        photosPerRow = draft.mealCalendarPhotosPerRow,
                    )
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
                onSave = { rowHeight, reopenMode, displayMode, highlightColor, editorMaxHeight ->
                    viewModel.setThoughtSettings(
                        rowHeightDp = rowHeight,
                        reopenMode = reopenMode,
                        displayMode = displayMode,
                        highlightColorArgb = highlightColor,
                        editorMaxHeightDp = editorMaxHeight,
                    )
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.VAULT -> VaultSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { rowHeight ->
                    viewModel.setVaultRowHeight(rowHeight)
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.POETRY -> PoetrySettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onImportFont = viewModel::persistPoetryFont,
                onSave = {
                        fontUri,
                        fontSizeSp,
                        lineSpacing,
                        textAlignment,
                        showSource,
                        showQuoteMark,
                        sevenCharacterWrapEnabled,
                    ->
                    viewModel.setPoetryDisplaySettings(
                        fontUri = fontUri,
                        fontSizeSp = fontSizeSp,
                        lineSpacing = lineSpacing,
                        textAlignment = textAlignment,
                        showSource = showSource,
                        showQuoteMark = showQuoteMark,
                        sevenCharacterWrapEnabled = sevenCharacterWrapEnabled,
                    ) { saved ->
                        if (saved) completeSave(SettingsPage.SUBPAGES)
                    }
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

            SettingsPage.AI -> AiSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onOpenConfigs = {
                    navigateAfterHandlingUnsaved { page = SettingsPage.AI_CONFIGS }
                },
                onSave = { fontSizeSp, replyBoxWidthDp, agentPrompt, onDone ->
                    viewModel.setAiPageSettings(
                        fontSizeSp = fontSizeSp,
                        replyBoxWidthDp = replyBoxWidthDp,
                        agentPrompt = agentPrompt,
                    ) { success ->
                        onDone(success)
                        if (success) completeSave(SettingsPage.SUBPAGES)
                    }
                },
            )

            SettingsPage.AI_CONFIGS -> AiConfigurationsSettingsPage(
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
                            if (success) completeSave(SettingsPage.AI_CONFIGS)
                        }
                    },
                )
            }

            SettingsPage.NAVIGATION -> NavigationSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { defaultPage, navItems, showLabels, visualizerEnabled, visualizerStyle,
                        frequencyMode, minimumFrequencyHz, maximumFrequencyHz, onDone ->
                    viewModel.setNavigationSettings(
                        defaultPage,
                        navItems,
                        showLabels,
                        visualizerEnabled,
                        visualizerStyle,
                        frequencyMode,
                        minimumFrequencyHz,
                        maximumFrequencyHz,
                    ) { success ->
                        onDone(success)
                        if (success) completeSave(SettingsPage.MAIN)
                    }
                },
            )

            SettingsPage.MORE_PAGE -> MorePageSettingsPage(
                settings = settings,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { showDescriptions, columns, navItems, onDone ->
                    viewModel.setMorePageSettings(
                        showDescriptions = showDescriptions,
                        columns = columns,
                        items = navItems,
                    ) { success ->
                        onDone(success)
                        if (success) completeSave(SettingsPage.SUBPAGES)
                    }
                },
            )

            SettingsPage.USAGE -> DeviceTrackingSettingsPage(
                title = tr("手机使用时间", "Screen time"),
                explanation = tr(
                    "读取系统提供的应用使用事件，按天写入本机 Room 数据库。统计明细不会进入 Android 系统备份；多设备历史只通过显式应用备份或云同步传递。",
                    "Reads system app-usage events into the on-device Room database. Details are excluded from Android system backup; multi-device history moves only through explicit app backup or cloud sync.",
                ),
                enabled = settings.usageTrackingEnabled,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { enabled ->
                    viewModel.setUsageTrackingEnabled(enabled)
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.STEPS -> DeviceTrackingSettingsPage(
                title = tr("健康", "Health"),
                explanation = tr(
                    "只从已授权的 Health Connect 读取步数、距离和活动热量，不再改用手机计步传感器。结果写入本机 Room 数据库，不进入应用备份、云同步或 Android 系统备份。",
                    "Reads steps, distance, and active calories only from authorized Health Connect and no longer falls back to the phone's step counter. Results stay in the on-device Room database and are excluded from app backup, cloud sync, and Android system backup.",
                ),
                enabled = settings.stepTrackingEnabled,
                contentPadding = inner,
                saveCoordinator = saveCoordinator,
                onSave = { enabled ->
                    viewModel.setStepTrackingEnabled(enabled)
                    completeSave(SettingsPage.SUBPAGES)
                },
            )

            SettingsPage.ABOUT -> AboutSettingsPage(
                settings = settings,
                contentPadding = inner,
                viewModel = viewModel,
            )
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = {
                showUnsavedDialog = false
                pendingNavigationAfterUnsaved = null
            },
            title = { Text(tr("设置尚未保存", "Unsaved settings")) },
            text = { Text(tr("继续会丢失刚才的修改。", "Continuing will discard your changes.")) },
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
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            pendingNavigationAfterUnsaved = null
                        },
                    ) {
                        Text(tr("继续编辑", "Keep editing"))
                    }
                    TextButton(
                        onClick = {
                            showUnsavedDialog = false
                            val pendingNavigation = pendingNavigationAfterUnsaved
                            pendingNavigationAfterUnsaved = null
                            saveCoordinator.clear()
                            if (pendingNavigation == null) {
                                exitOrOpenParent()
                            } else {
                                pendingNavigation()
                            }
                        },
                    ) { Text(tr("放弃", "Discard")) }
                }
            },
        )
    }
}

private data class SettingsSearchEntry(
    val title: String,
    val description: String,
    val keywords: String,
    val page: SettingsPage,
)

@Composable
private fun settingsSearchIndex(): List<SettingsSearchEntry> = listOf(
    SettingsSearchEntry(
        tr("外观与语言", "Appearance & language"),
        tr("风格、自定义主题、颜色、字号、明暗、紧凑模式", "Style, custom theme, colors, font size, dark mode, compact mode"),
        "appearance style custom theme color secondary font dark compact opacity radius border shadow spacing motion 外观 风格 自定义 主题 颜色 辅色 副色 字号 明暗 紧凑 透明度 圆角 边框 阴影 间距 动效 material glass organic",
        SettingsPage.APPEARANCE,
    ),
    SettingsSearchEntry(
        tr("主页", "Home"),
        tr("模块、标题、排序与饮食按钮", "Widgets, titles, order and meal buttons"),
        "home widget 主页 模块 组件 饮食按钮",
        SettingsPage.HOME,
    ),
    SettingsSearchEntry(
        tr("主页问候", "Home greeting"),
        tr("用户名以及问候语的增加、修改和删除", "User name and greeting add, edit and delete"),
        "home greeting 主页 问候 用户名 增加 修改 删除",
        SettingsPage.HOME_GREETING,
    ),
    SettingsSearchEntry(
        tr("日记与媒体", "Diary & media"),
        tr("目录、文件名、图片压缩、相册、吃历显示与热量", "Folders, file names, compression, gallery, meal calendar and calories"),
        "diary media image compress gallery meal calendar calorie 日记 媒体 图片 压缩 相册 原图 吃历 换行 每行 热量",
        SettingsPage.DIARY,
    ),
    SettingsSearchEntry(
        tr("浏览器", "Browser"),
        tr("默认主页、主题和电脑模式", "Home page, theme and desktop mode"),
        "browser 浏览器 主页 电脑模式 desktop",
        SettingsPage.BLOG,
    ),
    SettingsSearchEntry(
        tr("小巧思", "Thoughts"),
        tr("打开位置、内容显示、行高、重点颜色与输入框高度", "Reopen page, display, row height, highlight color and editor height"),
        "thought 小巧思 行高 重点 高亮 颜色 输入框 高度 编辑框",
        SettingsPage.THOUGHT,
    ),
    SettingsSearchEntry(
        tr("收藏夹", "Vault"),
        tr("收藏夹条目高度", "Vault entry height"),
        "vault favorites 收藏夹 收藏 高度 行高",
        SettingsPage.VAULT,
    ),
    SettingsSearchEntry(
        tr("诗词本", "Poetry book"),
        tr("导入字体、字号、行距、对齐与出处显示", "Imported font, size, spacing, alignment and source"),
        "poetry font size spacing align source 诗词 字体 字号 行距 对齐 出处",
        SettingsPage.POETRY,
    ),
    SettingsSearchEntry(
        tr("RSS 订阅", "RSS"),
        tr("每个订阅的文章数量与摘要显示", "Article limit and summary display"),
        "rss feed 订阅 摘要",
        SettingsPage.RSS,
    ),
    SettingsSearchEntry(
        tr("AI 设置", "AI settings"),
        tr("页面字体、回复框宽度、Agent 提示词与模型配置", "Page font size, reply box width, Agent prompt, and model configurations"),
        "ai api key model prompt agent font width 模型 密钥 提示词 字体 宽度 恢复",
        SettingsPage.AI,
    ),
    SettingsSearchEntry(
        tr("AI 配置", "AI configurations"),
        tr("兼容接口、模型、系统提示词与 API 密钥", "Endpoint, model, system prompt and API key"),
        "ai config endpoint model system prompt api key 配置 接口 模型 密钥",
        SettingsPage.AI_CONFIGS,
    ),
    SettingsSearchEntry(
        tr("应用数据", "App data"),
        tr(
            "手动备份与恢复、云同步、存储占用",
            "Manual backup & restore, cloud sync, and storage usage",
        ),
        "backup json cloud sync webdav s3 备份 导入 导出 云端 同步",
        SettingsPage.BACKUP,
    ),
    SettingsSearchEntry(
        tr("底部导航", "Bottom navigation"),
        tr("显示方式、默认页、排序、名称与图标", "Display, default page, order, labels and icons"),
        "navigation bottom bar music visualizer spectrum frequency 导航 底栏 图标 默认页 音乐 可视化 频谱 频率",
        SettingsPage.NAVIGATION,
    ),
    SettingsSearchEntry(
        tr("导航页", "Navigation page"),
        tr("收纳页面、列数、模块名称与颜色", "Collected pages, column count, module names and colors"),
        "more navigation page description columns color 导航页 描述 列 一列 两列 三列 收纳 颜色 底色 名称",
        SettingsPage.MORE_PAGE,
    ),
    SettingsSearchEntry(
        tr("手机使用时间", "Screen time"),
        tr("应用时长统计、系统授权与本机 JSON", "App usage, system access and on-device JSON"),
        "usage screen time statistics 手机 使用 时间 时长 统计",
        SettingsPage.USAGE,
    ),
    SettingsSearchEntry(
        tr("健康", "Health"),
        tr("Health Connect 的步数、距离与活动热量", "Health Connect steps, distance, and active calories"),
        "steps distance calories health connect 步数 距离 热量 健康 统计",
        SettingsPage.STEPS,
    ),
    SettingsSearchEntry(
        tr("关于", "About"),
        tr("版本、更新检查与应用显示名称", "Version, update check and app display name"),
        "about version update github 关于 版本 更新 名称 桌洞",
        SettingsPage.ABOUT,
    ),
)

@Composable
private fun SettingsMainPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onOpen: (SettingsPage) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchIndex = settingsSearchIndex()
    val query = searchQuery.trim()
    val searchResults = if (query.isEmpty()) {
        emptyList()
    } else {
        searchIndex.filter { entry ->
            entry.title.contains(query, ignoreCase = true) ||
                entry.description.contains(query, ignoreCase = true) ||
                entry.keywords.contains(query, ignoreCase = true)
        }
    }
    val compact = LocalCompactMode.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
    ) {
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(tr("搜索设置", "Search settings")) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, tr("清除搜索", "Clear search"))
                        }
                    }
                } else {
                    null
                },
            )
        }
        if (query.isNotEmpty()) {
            if (searchResults.isEmpty()) {
                item {
                    Text(
                        tr("没有匹配的设置", "No matching settings"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                itemsIndexed(searchResults) { index, entry ->
                    SettingsMenuItem(
                        title = entry.title,
                        description = entry.description,
                        icon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        accentColor = settings.menuAccentColor(index),
                        onClick = {
                            searchQuery = ""
                            onOpen(entry.page)
                        },
                    )
                }
            }
            return@LazyColumn
        }
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
                description = tr(
                    "主页、日记、浏览器、小巧思、RSS、AI 与导航页",
                    "Home, diary, browser, thoughts, RSS, AI and the navigation page",
                ),
                icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                accentColor = settings.menuAccentColor(1),
                onClick = { onOpen(SettingsPage.SUBPAGES) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("应用数据", "App data"),
                description = if (settings.cloudSyncEnabled) {
                    tr("云同步已开启；手动备份与恢复", "Cloud sync enabled; manual backup & restore")
                } else {
                    tr("云同步与手动备份、恢复", "Cloud sync and manual backup & restore")
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
                title = tr("关于", "About"),
                description = tr("版本、检查更新与应用显示名称", "Version, update check and app display name"),
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                accentColor = settings.menuAccentColor(4),
                onClick = { onOpen(SettingsPage.ABOUT) },
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
    val compact = LocalCompactMode.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
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
                icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
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
                title = tr("收藏夹", "Vault"),
                description = tr("调整收藏夹条目高度", "Adjust vault entry height"),
                icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                accentColor = settings.menuAccentColor(4),
                onClick = { onOpen(SettingsPage.VAULT) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("诗词本", "Poetry book"),
                description = tr(
                    "字体、字号、行距、对齐与出处显示",
                    "Font, size, spacing, alignment and source display",
                ),
                icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null) },
                accentColor = settings.menuAccentColor(5),
                onClick = { onOpen(SettingsPage.POETRY) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("RSS 订阅", "RSS"),
                description = tr("每个订阅的文章数量与摘要显示", "Article limit and summary display"),
                icon = { Icon(Icons.Outlined.RssFeed, contentDescription = null) },
                accentColor = settings.menuAccentColor(6),
                onClick = { onOpen(SettingsPage.RSS) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("AI 配置", "AI configurations"),
                description = tr("兼容接口、模型、系统提示词与 API 密钥", "Endpoint, model, system prompt and API key"),
                icon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
                accentColor = settings.menuAccentColor(7),
                onClick = { onOpen(SettingsPage.AI) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("导航页", "Navigation page"),
                description = tr(
                    "收纳页面、双列卡片与自定义描述",
                    "Collected pages, two-column cards and custom descriptions",
                ),
                icon = { Icon(Icons.Outlined.Apps, contentDescription = null) },
                accentColor = settings.menuAccentColor(8),
                onClick = { onOpen(SettingsPage.MORE_PAGE) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("手机使用时间", "Screen time"),
                description = if (settings.usageTrackingEnabled) {
                    tr("已开启本机按日统计", "On-device daily tracking is enabled")
                } else {
                    tr("权限、开关与本机 JSON", "Permission, switch and on-device JSON")
                },
                icon = { Icon(Icons.Outlined.AccessTime, contentDescription = null) },
                accentColor = settings.menuAccentColor(9),
                onClick = { onOpen(SettingsPage.USAGE) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("健康", "Health"),
                description = if (settings.stepTrackingEnabled) {
                    tr("已开启健康数据读取", "Health data reading is enabled")
                } else {
                    tr("健康数据权限与本机 JSON", "Health permission and on-device JSON")
                },
                icon = { Icon(Icons.Outlined.MonitorHeart, contentDescription = null) },
                accentColor = settings.menuAccentColor(10),
                onClick = { onOpen(SettingsPage.STEPS) },
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
private fun CloudSyncSettingsPage(
    settings: AppSettings,
    status: AppCloudSyncStatus,
    undoAvailable: Boolean,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSaveEnabled: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (CloudSyncConfig) -> Unit,
    onCopy: (CloudSyncConfig) -> Unit,
    onDelete: (CloudSyncConfig) -> Unit,
    onSyncNow: () -> Unit,
    onUndo: () -> Unit,
    onForceUpload: () -> Unit,
    onForceDownload: () -> Unit,
) {
    var enabled by remember(settings.cloudSyncEnabled) {
        mutableStateOf(settings.cloudSyncEnabled)
    }
    var deleteCandidate by remember { mutableStateOf<CloudSyncConfig?>(null) }
    var forcedRunCandidate by remember { mutableStateOf<CloudSyncRunMode?>(null) }
    val enabledSourceCount = settings.cloudSyncConfigs.count(CloudSyncConfig::enabled)
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = enabled != settings.cloudSyncEnabled,
        enabled = !enabled || settings.cloudSyncConfigs.any(CloudSyncConfig::enabled),
        onReset = { enabled = AppSettings().cloudSyncEnabled },
    ) { onSaveEnabled(enabled) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection(tr("同步开关", "Cloud sync")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("开启云端同步", "Enable cloud sync"))
                        Text(
                            tr(
                                "开启后后台任务会在联网时同步已启用的服务。",
                                "When enabled, background work syncs active services while connected.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text(
                    tr(
                        "WebDAV 密码经 Android Keystore 加密；S3 用户名和 Key 明文保存在应用私有存储中并可在编辑时回显。两者都不进入 JSON 备份或日志。",
                        "WebDAV passwords use Android Keystore. S3 usernames and keys are stored as plaintext in app-private storage and shown when edited. Neither is included in JSON backups or logs.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("同步状态", "Sync status")) {
                status.progress?.let { progress ->
                    Text(
                        tr(
                            "正在处理 ${progress.completedObjects}/${progress.totalObjects} 项",
                            "Processing ${progress.completedObjects}/${progress.totalObjects} items",
                        ),
                    )
                    progress.currentKey?.let {
                        Text(
                            it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                status.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                status.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    status.lastFinishedAt?.let { finishedAt ->
                        tr("上次同步时间：", "Last sync: ") + formatBackupTime(finishedAt)
                    } ?: tr("上次同步时间：尚未同步", "Last sync: Never"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val uploaded = status.lastUploadedCount
                val downloaded = status.lastDownloadedCount
                val conflicts = status.lastConflictCount
                if (uploaded != null && downloaded != null && conflicts != null) {
                    Text(
                        tr(
                            "上次：上传 $uploaded，下载 $downloaded，冲突副本 $conflicts",
                            "Last run: $uploaded uploaded, $downloaded downloaded, $conflicts conflict copies",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Button(
                        onClick = onSyncNow,
                        enabled = enabled &&
                            enabled == settings.cloudSyncEnabled &&
                            !status.running,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) {
                        Icon(Icons.Outlined.Sync, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (status.running) tr("正在同步", "Syncing")
                            else tr("立即同步", "Sync now"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = onUndo,
                        enabled = undoAvailable && !status.running,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) {
                        Text(
                            tr("撤回一次", "Undo last"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Button(
                        onClick = {
                            forcedRunCandidate = CloudSyncRunMode.FORCE_UPLOAD
                        },
                        enabled = enabled &&
                            enabled == settings.cloudSyncEnabled &&
                            !status.running,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) {
                        Text(
                            tr("强制上传", "Force upload"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Button(
                        onClick = {
                            forcedRunCandidate = CloudSyncRunMode.FORCE_DOWNLOAD
                        },
                        enabled = enabled &&
                            enabled == settings.cloudSyncEnabled &&
                            !status.running &&
                            enabledSourceCount == 1,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) {
                        Text(
                            tr("强制下载", "Force download"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    tr(
                        "强制上传以本机为准，强制下载以云端为准；强制下载仅允许一个已启用来源。两者均不传播删除且仍执行条件校验。",
                        "Force upload prefers local data and force download prefers remote data; force download allows only one enabled source. Neither propagates deletions, and conditional checks still apply.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("云端服务", "Cloud services")) {
                if (settings.cloudSyncConfigs.isEmpty()) {
                    Text(
                        tr(
                            "尚未添加服务。可分别配置多个 WebDAV 或 S3 兼容端点。",
                            "No services yet. Add multiple WebDAV or S3-compatible endpoints.",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                settings.cloudSyncConfigs.forEach { config ->
                    ListItem(
                        headlineContent = { Text(config.name) },
                        supportingContent = {
                            val serviceLabel = syncServiceLabel(config.serviceType)
                            val contentsLabel = syncContentsLabel(config.selectedContents)
                            val disabledSuffix = if (config.enabled) {
                                ""
                            } else {
                                tr(" · 已停用", " · Disabled")
                            }
                            Text(
                                "$serviceLabel · $contentsLabel$disabledSuffix",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Outlined.Cloud, contentDescription = null)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onEdit(config) }) {
                                    Icon(Icons.Outlined.Edit, tr("编辑", "Edit"))
                                }
                                IconButton(onClick = { onCopy(config) }) {
                                    Icon(Icons.Outlined.ContentCopy, tr("复制", "Copy"))
                                }
                                IconButton(onClick = { deleteCandidate = config }) {
                                    Icon(Icons.Outlined.Delete, tr("删除", "Delete"))
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    HorizontalDivider()
                }
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("新增同步服务", "Add sync service"))
                }
            }
        }
    }

    deleteCandidate?.let { config ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(tr("删除同步配置？", "Delete sync configuration?")) },
            text = {
                Text(
                    tr(
                        "将删除“${config.name}”及本机加密凭据，不会删除云端文件。",
                        "This removes “${config.name}” and its local encrypted credentials, but not remote files.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        onDelete(config)
                    },
                ) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
    forcedRunCandidate?.let { mode ->
        val upload = mode == CloudSyncRunMode.FORCE_UPLOAD
        AlertDialog(
            onDismissRequest = { forcedRunCandidate = null },
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
                            "本机新增项目会上传；同路径内容不同时，将以扫描到的远端版本为条件覆盖远端。远端独有项目不会删除，扫描后发生的远端修改会阻止覆盖。",
                            "New local items are uploaded. Different items at the same path replace remote data only if its scanned version still matches. Remote-only items are not deleted, and later remote edits stop the overwrite.",
                        )
                    } else {
                        tr(
                            "强制下载仅使用当前唯一的已启用云端来源。云端新增项目会下载；同路径内容不同时，将仅在本机文件仍匹配扫描快照时采用云端版本。本机独有项目不会删除，并发本机修改会保留并产生冲突副本。",
                            "Force download uses the single currently enabled cloud source. New remote items are downloaded. Different items at the same path use remote data only while the local file still matches its scanned snapshot. Local-only items are not deleted, and concurrent local edits are preserved with a conflict copy.",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        forcedRunCandidate = null
                        if (upload) onForceUpload() else onForceDownload()
                    },
                ) {
                    Text(
                        if (upload) tr("强制上传", "Force upload")
                        else tr("强制下载", "Force download"),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { forcedRunCandidate = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun CloudSyncConfigDetailPage(
    initial: CloudSyncConfig,
    hasStoredCredentials: Boolean,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (CloudSyncConfig, Boolean) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var enabled by remember(initial.id) { mutableStateOf(initial.enabled) }
    var serviceType by remember(initial.id) { mutableStateOf(initial.serviceType) }
    var endpointUrl by remember(initial.id) { mutableStateOf(initial.endpointUrl) }
    var remotePath by remember(initial.id) { mutableStateOf(initial.remotePath) }
    var userAgent by remember(initial.id) { mutableStateOf(initial.userAgent) }
    var webDavUsername by remember(initial.id) { mutableStateOf(initial.webDavUsername) }
    var webDavPassword by remember(initial.id) { mutableStateOf("") }
    var s3Bucket by remember(initial.id) { mutableStateOf(initial.s3Bucket) }
    var s3Region by remember(initial.id) { mutableStateOf(initial.s3Region) }
    var s3AccessKey by remember(initial.id) { mutableStateOf(initial.s3AccessKey) }
    var s3SecretKey by remember(initial.id) { mutableStateOf(initial.s3SecretKey) }
    var s3SessionToken by remember(initial.id) { mutableStateOf(initial.s3SessionToken) }
    var s3PathStyle by remember(initial.id) { mutableStateOf(initial.s3PathStyle) }
    var allowInsecureHttp by remember(initial.id) {
        mutableStateOf(initial.allowInsecureHttp)
    }
    var selectedContents by remember(initial.id) {
        mutableStateOf(initial.selectedContents)
    }
    var direction by remember(initial.id) { mutableStateOf(initial.direction) }
    var clearCredentials by remember(initial.id) { mutableStateOf(false) }

    val draft = initial.copy(
        name = name,
        enabled = enabled,
        serviceType = serviceType,
        endpointUrl = endpointUrl,
        remotePath = remotePath,
        userAgent = userAgent,
        webDavUsername = webDavUsername,
        webDavPassword = webDavPassword,
        s3Bucket = s3Bucket,
        s3Region = s3Region,
        s3AccessKey = s3AccessKey,
        s3SecretKey = s3SecretKey,
        s3SessionToken = s3SessionToken,
        s3PathStyle = s3PathStyle,
        allowInsecureHttp = allowInsecureHttp,
        selectedContents = selectedContents,
        direction = direction,
    )
    val dirty = draft != initial || clearCredentials
    val canSave = name.isNotBlank() && endpointUrl.isNotBlank() && userAgent.isNotBlank() &&
        userAgent.none(Char::isISOControl) &&
        selectedContents.isNotEmpty() &&
        (serviceType != CloudSyncServiceType.S3_COMPATIBLE ||
            s3Bucket.isNotBlank() && s3Region.isNotBlank())
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = dirty,
        enabled = canSave,
        onReset = {
            val defaults = CloudSyncConfig(id = initial.id, name = "")
            name = defaults.name
            enabled = defaults.enabled
            serviceType = defaults.serviceType
            endpointUrl = defaults.endpointUrl
            remotePath = defaults.remotePath
            userAgent = defaults.userAgent
            webDavUsername = defaults.webDavUsername
            webDavPassword = ""
            s3Bucket = defaults.s3Bucket
            s3Region = defaults.s3Region
            s3AccessKey = ""
            s3SecretKey = ""
            s3SessionToken = ""
            s3PathStyle = defaults.s3PathStyle
            allowInsecureHttp = defaults.allowInsecureHttp
            selectedContents = defaults.selectedContents
            direction = defaults.direction
            clearCredentials = hasStoredCredentials
        },
    ) { onSave(draft, clearCredentials) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection(tr("服务", "Service")) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("启用此配置", "Enable this configuration"))
                        Text(
                            tr(
                                "全局同步开启时才会自动运行",
                                "Runs automatically only while global sync is enabled",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CloudSyncServiceType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = serviceType == type,
                            onClick = { serviceType = type },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                CloudSyncServiceType.entries.size,
                            ),
                        ) {
                            Text(syncServiceLabel(type))
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(200) },
                    label = { Text(tr("配置名称", "Configuration name")) },
                    singleLine = true,
                    isError = name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = endpointUrl,
                    onValueChange = { endpointUrl = it.take(4_096) },
                    label = { Text(tr("服务地址", "Endpoint URL")) },
                    supportingText = {
                        Text(
                            if (serviceType == CloudSyncServiceType.WEBDAV) {
                                tr(
                                    "例如 https://dav.example.com/remote.php/dav/files/name/",
                                    "For example https://dav.example.com/remote.php/dav/files/name/",
                                )
                            } else {
                                tr(
                                    "可只填接入点（如 s3.example.com），客户端会按 SSL/TLS 选项补全协议。",
                                    "You may enter only the endpoint (such as s3.example.com); the client adds the protocol from the SSL/TLS setting.",
                                )
                            },
                        )
                    },
                    singleLine = true,
                    isError = endpointUrl.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = remotePath,
                    onValueChange = { remotePath = it.take(1_024) },
                    label = { Text(tr("远端目录", "Remote path")) },
                    supportingText = {
                        Text(tr("默认 DeskCubby；不能包含 . 或 ..", "Defaults to DeskCubby; . and .. are not allowed"))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it.take(512) },
                    label = { Text("User-Agent") },
                    supportingText = {
                        Text(
                            tr(
                                "发送到 S3/WebDAV 请求的客户端标识；默认 ${DEFAULT_CLOUD_SYNC_USER_AGENT}",
                                "Client identifier sent with S3/WebDAV requests; default ${DEFAULT_CLOUD_SYNC_USER_AGENT}",
                            ),
                        )
                    },
                    singleLine = true,
                    isError = userAgent.isBlank() || userAgent.any(Char::isISOControl),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (serviceType == CloudSyncServiceType.WEBDAV) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("允许 HTTP", "Allow HTTP"))
                            Text(
                                tr(
                                    "仅用于可信局域网服务",
                                    "Only for trusted local-network services",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = allowInsecureHttp,
                            onCheckedChange = { allowInsecureHttp = it },
                        )
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("SSL/TLS", "SSL/TLS"))
                            Text(
                                tr(
                                    "开启时自动添加 https://；仅可信内网端点可关闭。",
                                    "Adds https:// when enabled; disable only for a trusted local endpoint.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = !allowInsecureHttp,
                            onCheckedChange = { useTls ->
                                allowInsecureHttp = !useTls
                                endpointUrl = when {
                                    useTls && endpointUrl.startsWith("http://", true) ->
                                        "https://" + endpointUrl.substringAfter("://")
                                    !useTls && endpointUrl.startsWith("https://", true) ->
                                        "http://" + endpointUrl.substringAfter("://")
                                    else -> endpointUrl
                                }
                            },
                        )
                    }
                }
            }
        }
        item {
            SettingsSection(tr("同步内容", "Sync contents")) {
                CloudSyncContent.entries.forEach { content ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = content in selectedContents,
                            onCheckedChange = { checked ->
                                selectedContents = if (checked) {
                                    selectedContents + content
                                } else {
                                    selectedContents - content
                                }
                            },
                        )
                        Text(syncContentLabel(content))
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CloudSyncDirection.entries.forEachIndexed { index, value ->
                        SegmentedButton(
                            selected = direction == value,
                            onClick = { direction = value },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                CloudSyncDirection.entries.size,
                            ),
                        ) {
                            Text(
                                if (value == CloudSyncDirection.TWO_WAY) {
                                    tr("双向", "Two-way")
                                } else {
                                    tr("仅上传", "Upload only")
                                },
                            )
                        }
                    }
                }
                Text(
                    tr(
                        "双向同步遇到双方都修改时会保留冲突副本；支持逐条安全合并的内容会按更新时间合并，不会无条件覆盖。",
                        "Two-way sync preserves a conflict copy when both sides changed; content that supports safe record-level merging is merged by update time and is never overwritten unconditionally.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (CloudSyncContent.USAGE_STATISTICS in selectedContents) {
                    Text(
                        tr(
                            "使用时间会按设备 ID 自动上传并合并，包含应用包名、日期、时区与前台时长；请只使用可信云端。",
                            "Screen time is uploaded and merged by device ID. It includes package names, dates, time zones, and foreground durations; use only a trusted cloud service.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (CloudSyncContent.READING_PROGRESS in selectedContents) {
                    Text(
                        tr(
                            "阅读进度会按书籍文件的 SHA-256 指纹自动合并。云端不包含书名、文件地址、封面或正文，但文件指纹仍可能用于识别你持有的特定文件；请只使用可信云端。",
                            "Reading progress is merged using each book file's SHA-256 fingerprint. The cloud object contains no title, file URI, cover, or book text, but a fingerprint can still identify a specific file you possess; use only a trusted cloud service.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (CloudSyncContent.AGENT_CHATS in selectedContents) {
                    Text(
                        tr(
                            "Agent 会话会同步文字、冻结的文档文字、图片占位和 Provider 用量；不会上传本机 URI、图片字节、Review/Undo 载荷或秘密。远端没有端到端加密，请只使用可信云端。",
                            "Agent chats synchronize text, frozen document text, image placeholders, and provider usage. Local URIs, image bytes, Review/Undo payloads, and secrets are excluded. The remote object is not end-to-end encrypted; use only a trusted cloud service.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item {
            SettingsSection(
                if (serviceType == CloudSyncServiceType.WEBDAV) {
                    tr("WebDAV 凭据", "WebDAV credentials")
                } else {
                    tr("S3 配置与凭据", "S3 settings & credentials")
                },
            ) {
                if (serviceType == CloudSyncServiceType.WEBDAV) {
                    OutlinedTextField(
                        value = webDavUsername,
                        onValueChange = { webDavUsername = it.take(512) },
                        label = { Text(tr("用户名", "Username")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecretSettingField(
                        value = webDavPassword,
                        onValueChange = { webDavPassword = it.take(8_192) },
                        label = tr("密码", "Password"),
                        hasStoredValue = hasStoredCredentials && !clearCredentials,
                    )
                } else {
                    OutlinedTextField(
                        value = s3Bucket,
                        onValueChange = { s3Bucket = it.take(255) },
                        label = { Text("Bucket") },
                        singleLine = true,
                        isError = s3Bucket.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s3Region,
                        onValueChange = { s3Region = it.take(128) },
                        label = { Text("Region") },
                        singleLine = true,
                        isError = s3Region.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("路径寻址（Path-Style）", "Path-style addressing"))
                            Text(
                                tr(
                                    "启用后请求路径为 /Bucket/目录；若服务要求 Bucket 子域名（部分 CSTCloud 接入点），请关闭。",
                                    "Uses /bucket/path. Disable when the service requires a bucket subdomain, as some CSTCloud endpoints do.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = s3PathStyle,
                            onCheckedChange = { s3PathStyle = it },
                        )
                    }
                    OutlinedTextField(
                        value = s3AccessKey,
                        onValueChange = { s3AccessKey = it.take(8_192) },
                        label = { Text("Access Key ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s3SecretKey,
                        onValueChange = { s3SecretKey = it.take(8_192) },
                        label = { Text("Secret Access Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s3SessionToken,
                        onValueChange = { s3SessionToken = it.take(8_192) },
                        label = { Text(tr("会话令牌（可选）", "Session token (optional)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (hasStoredCredentials && serviceType == CloudSyncServiceType.WEBDAV) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = clearCredentials,
                            onCheckedChange = {
                                clearCredentials = it
                                if (it) {
                                    webDavPassword = ""
                                    s3AccessKey = ""
                                    s3SecretKey = ""
                                    s3SessionToken = ""
                                }
                            },
                        )
                        Text(tr("清除本机已保存凭据", "Clear saved device credentials"))
                    }
                }
                Text(
                    if (serviceType == CloudSyncServiceType.WEBDAV) {
                        tr(
                            "密码使用 Android Keystore 加密，只在发起请求时读取，不进入日志或 JSON 备份。",
                            "The password uses Android Keystore, is read only for requests, and is excluded from logs and JSON backups.",
                        )
                    } else {
                        tr(
                            "S3 用户名和 Key 明文保存在应用私有存储中，编辑时完整显示；不会写入日志或 DeskCubby JSON 备份。",
                            "S3 usernames and keys are plaintext in app-private storage and fully visible when edited; they are excluded from logs and DeskCubby JSON backups.",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SecretSettingField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hasStoredValue: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = if (hasStoredValue && value.isEmpty()) {
            { Text(tr("已在本机加密保存；留空可保留", "Encrypted on this device; leave blank to keep it")) }
        } else {
            null
        },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun syncServiceLabel(type: CloudSyncServiceType): String = when (type) {
    CloudSyncServiceType.WEBDAV -> "WebDAV"
    CloudSyncServiceType.S3_COMPATIBLE -> tr("S3 兼容", "S3 compatible")
}

@Composable
private fun syncContentLabel(content: CloudSyncContent): String = when (content) {
    CloudSyncContent.DIARIES -> tr("日记", "Diaries")
    CloudSyncContent.NOTES -> tr("笔记", "Notes")
    CloudSyncContent.MEDIA -> tr("媒体", "Media")
    CloudSyncContent.THOUGHTS -> tr("小巧思", "Thoughts")
    CloudSyncContent.THOUGHT_CATEGORIES -> tr("小巧思分类", "Thought categories")
    CloudSyncContent.DATE_RECORDS -> tr("日期记录", "Date records")
    CloudSyncContent.POEMS -> tr("诗词", "Poems")
    CloudSyncContent.POETRY_CATEGORIES -> tr("诗词分类", "Poetry categories")
    CloudSyncContent.FAVORITES -> tr("收藏", "Favorites")
    CloudSyncContent.RSS_SUBSCRIPTIONS -> tr("RSS 订阅", "RSS subscriptions")
    CloudSyncContent.GAME_STATES -> tr("游戏存档", "Game saves")
    CloudSyncContent.GAME_STATISTICS -> tr("游戏统计", "Game statistics")
    CloudSyncContent.USAGE_STATISTICS -> tr("使用统计", "Usage statistics")
    CloudSyncContent.READING_PROGRESS -> tr("阅读进度", "Reading progress")
    CloudSyncContent.READER_PREFERENCES -> tr("阅读偏好", "Reader preferences")
    CloudSyncContent.AGENT_CHATS -> tr("Agent 对话", "Agent chats")
    CloudSyncContent.VAULT -> tr("Vault", "Vault")
    CloudSyncContent.GLOBAL_SETTINGS -> tr("通用设置", "Global settings")
}

@Composable
private fun syncContentsLabel(contents: Set<CloudSyncContent>): String {
    val labels = CloudSyncContent.entries.filter(contents::contains).map { content ->
        if (LocalAppLanguage.current == AppLanguage.ENGLISH) {
            when (content) {
                CloudSyncContent.DIARIES -> "Diaries"
                CloudSyncContent.NOTES -> "Notes"
                CloudSyncContent.MEDIA -> "Media"
                CloudSyncContent.THOUGHTS -> "Thoughts"
                CloudSyncContent.THOUGHT_CATEGORIES -> "Thought categories"
                CloudSyncContent.DATE_RECORDS -> "Date records"
                CloudSyncContent.POEMS -> "Poems"
                CloudSyncContent.POETRY_CATEGORIES -> "Poetry categories"
                CloudSyncContent.FAVORITES -> "Favorites"
                CloudSyncContent.RSS_SUBSCRIPTIONS -> "RSS"
                CloudSyncContent.GAME_STATES -> "Game saves"
                CloudSyncContent.GAME_STATISTICS -> "Game stats"
                CloudSyncContent.USAGE_STATISTICS -> "Usage"
                CloudSyncContent.READING_PROGRESS -> "Reading"
                CloudSyncContent.READER_PREFERENCES -> "Reader prefs"
                CloudSyncContent.AGENT_CHATS -> "Agent chats"
                CloudSyncContent.VAULT -> "Vault"
                CloudSyncContent.GLOBAL_SETTINGS -> "Global settings"
            }
        } else {
            when (content) {
                CloudSyncContent.DIARIES -> "日记"
                CloudSyncContent.NOTES -> "笔记"
                CloudSyncContent.MEDIA -> "媒体"
                CloudSyncContent.THOUGHTS -> "小巧思"
                CloudSyncContent.THOUGHT_CATEGORIES -> "小巧思分类"
                CloudSyncContent.DATE_RECORDS -> "日期记录"
                CloudSyncContent.POEMS -> "诗词"
                CloudSyncContent.POETRY_CATEGORIES -> "诗词分类"
                CloudSyncContent.FAVORITES -> "收藏"
                CloudSyncContent.RSS_SUBSCRIPTIONS -> "RSS"
                CloudSyncContent.GAME_STATES -> "游戏"
                CloudSyncContent.GAME_STATISTICS -> "游戏统计"
                CloudSyncContent.USAGE_STATISTICS -> "使用统计"
                CloudSyncContent.READING_PROGRESS -> "阅读进度"
                CloudSyncContent.READER_PREFERENCES -> "阅读偏好"
                CloudSyncContent.AGENT_CHATS -> "Agent 对话"
                CloudSyncContent.VAULT -> "Vault"
                CloudSyncContent.GLOBAL_SETTINGS -> "通用设置"
            }
        }
    }
    return labels.joinToString(if (LocalAppLanguage.current == AppLanguage.ENGLISH) ", " else "、")
}

@Composable
private fun BackupSettingsPage(
    settings: AppSettings,
    dataUsage: AppDataUsageState,
    operation: BackupOperationState,
    importPreview: BackupImportPreviewState,
    contentPadding: PaddingValues,
    onExport: (Uri) -> Unit,
    onImportPreview: (Uri) -> Unit,
    onConfirmImport: (Uri) -> Unit,
    onCloseImportPreview: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onRefreshDataUsage: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefreshDataUsage() }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(onExport)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportPreview)
    }
    val busy = operation.busy || importPreview.busy

    if (importPreview.busy || importPreview.summary != null || importPreview.error != null) {
        AlertDialog(
            onDismissRequest = onCloseImportPreview,
            title = { Text(tr("导入 DeskCubby 数据", "Import DeskCubby data")) },
            text = {
                when {
                    importPreview.busy -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                        Text(tr("正在校验备份…", "Validating backup…"))
                    }

                    importPreview.error != null -> Text(
                        importPreview.error,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> {
                        val summary = importPreview.summary
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr("此备份将恢复：", "This backup will restore:"))
                            if (summary != null) {
                                Text(backupSummaryLine(summary))
                                Text(
                                    tr(
                                        "导入前已通过格式、版本和完整性校验。日记、笔记、媒体和书籍原文件不会被替换；本地目录与 API Key 不会随备份恢复。",
                                        "Format, version and integrity validation passed. Diary, note, media, and book files are not replaced; local folders and API keys are never restored from this backup.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val uri = importPreview.uri?.let(Uri::parse)
                if (uri != null && importPreview.summary != null) {
                    TextButton(onClick = { onConfirmImport(uri) }) {
                        Text(tr("确认恢复", "Restore"))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCloseImportPreview) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("存储占用", "Storage usage")) {
                val snapshot = dataUsage.snapshot
                if (snapshot == null && dataUsage.loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                        Text(tr("正在统计…", "Calculating…"))
                    }
                } else if (snapshot != null) {
                    val privateTotal = snapshot.entries.filterNot { it.userOwned }.sumOf { it.bytes }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(tr("应用及私有数据合计", "App and private data total"), fontWeight = FontWeight.SemiBold)
                        Text(formatStorageBytes(privateTotal), color = MaterialTheme.colorScheme.primary)
                    }
                    snapshot.entries.forEach { entry ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                dataUsageLabel(entry.key) + if (entry.userOwned) {
                                    tr("（用户目录）", " (user folder)")
                                } else {
                                    ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                (if (entry.partial) "≥ " else "") + formatStorageBytes(entry.bytes),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(
                        tr(
                            "手机使用时间和健康历史已计入结构化数据库；旧统计文件仅显示尚待清理的迁移源。日记和媒体是用户选择目录中的真实文件，不计入应用私有数据合计；“≥”表示内容不可访问、过多或统计超时，只显示已读取部分。",
                            "Screen-time and health histories are included in the structured database; legacy statistics files are migration sources awaiting cleanup. Diary and media are real files in user-selected folders and are excluded from the private-data total. “≥” means content was inaccessible, too large, or timed out, so only the measured portion is shown.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dataUsage.failed) {
                    Text(
                        tr("无法计算应用数据占用空间", "Could not calculate app storage usage"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = onRefreshDataUsage,
                    enabled = !dataUsage.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("重新统计", "Recalculate")) }
            }
        }
        item {
            SettingsMenuItem(
                title = tr("云同步", "Cloud sync"),
                description = if (settings.cloudSyncEnabled) {
                    tr("已开启；管理服务、同步内容与立即同步", "Enabled; manage services, content and sync now")
                } else {
                    tr("在设备之间同步 DeskCubby 数据", "Synchronize DeskCubby data across devices")
                },
                icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                accentColor = settings.menuAccentColor(0),
                onClick = onOpenCloudSync,
            )
        }
        item {
            SettingsSection(tr("备份与恢复", "Backup & restore")) {
                Text(
                    tr(
                        "导出结构化应用数据，用于迁移或恢复。日记、笔记、媒体及书籍原文件不包含在其中。",
                        "Export structured app data for migration or recovery. Diary, note, media, and original book files are not included.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { exportPicker.launch(defaultBackupFileName(Clock.systemDefaultZone())) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Backup, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("导出 DeskCubby 数据", "Export DeskCubby data"))
                }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("导入 DeskCubby 数据", "Import DeskCubby data"))
                }
                Text(
                    tr(
                        "手动备份仅在点击导出时生成 DC-YYYY-MM-DD.json；云同步使用独立的记录同步与文件同步协议，不使用这个 JSON。",
                        "A manual backup is only created when you tap export, as DC-YYYY-MM-DD.json. Cloud sync uses separate record-sync and file-sync protocols and never uses this JSON.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("备份内容", "Backup contents")) {
                Text(
                    tr(
                        "包含小巧思及分类、日期记录、诗词及分类、收藏、Vault 加密数据、游戏存档与统计、阅读进度、RSS 订阅，以及可跨设备迁移的设置。不包含日记/笔记/媒体/书籍原文件、缓存、权限、本地 SAF URI、云凭据或 AI API Key。",
                        "Includes thoughts/categories, date records, poems/categories, favorites, encrypted Vault data, game saves and statistics, reading progress, RSS subscriptions, and portable settings. It excludes diary/note/media/book files, cache, permissions, local SAF URIs, cloud credentials, and AI API keys.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SettingsSection(tr("操作状态", "Operation status")) {
                if (busy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                        Text(tr("正在处理…", "Working…"))
                    }
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
            }
        }
    }
}

@Composable
private fun backupSummaryLine(summary: BackupSummary): String = buildString {
    val zh = "小巧思 ${summary.thoughtCount} 条、日期记录 ${summary.dateRecordCount} 条、" +
        "诗词 ${summary.poemCount} 首、收藏 ${summary.favoriteCount} 条、" +
        "Vault ${summary.vaultItemCount} 条、游戏存档 ${summary.gameStateCount} 个、" +
        "阅读进度 ${summary.readerProgressCount} 本、Agent 对话 ${summary.agentConversationCount} 个"
    val en = "Thoughts: ${summary.thoughtCount}, date records: ${summary.dateRecordCount}, " +
        "poems: ${summary.poemCount}, favorites: ${summary.favoriteCount}, " +
        "Vault items: ${summary.vaultItemCount}, game saves: ${summary.gameStateCount}, " +
        "reading progress: ${summary.readerProgressCount} books, " +
        "Agent conversations: ${summary.agentConversationCount}"
    append(tr(zh, en))
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onPersistBackground: (Uri, (Boolean) -> Unit) -> Unit,
    onOrientationChange: (OrientationPreference) -> Unit,
    onSave: (
        VisualStyle,
        CustomThemeSettings,
        DarkMode,
        AppLanguage,
        Int,
        List<Int>,
        Float,
        Boolean,
        String?,
        Float,
        Float,
        (Boolean) -> Unit,
    ) -> Unit,
) {
    val presets = listOf(
        0xFF42664D.toInt(),
        0xFF4C63A6.toInt(),
        0xFFC44B75.toInt(),
        0xFFE57C23.toInt(),
        0xFF7B5EA7.toInt(),
        0xFF00897B.toInt(),
    )
    // null = closed, -1 = primary, 0..4 = secondary, 100..107 = custom light,
    // 200..207 = custom dark.
    var colorPickerTarget by remember { mutableStateOf<Int?>(null) }
    var visualStyle by rememberSaveable(settings.visualStyle) { mutableStateOf(settings.visualStyle) }
    var customBaseStyle by rememberSaveable(settings.customTheme.baseStyle) {
        mutableStateOf(settings.customTheme.baseStyle)
    }
    var customLightHexes by rememberSaveable(settings.customTheme.lightPalette) {
        mutableStateOf(settings.customTheme.lightPalette.toThemeHexList())
    }
    var customDarkHexes by rememberSaveable(settings.customTheme.darkPalette) {
        mutableStateOf(settings.customTheme.darkPalette.toThemeHexList())
    }
    var customCornerRadiusDp by rememberSaveable(settings.customTheme.cornerRadiusDp) {
        mutableFloatStateOf(settings.customTheme.cornerRadiusDp)
    }
    var customBorderWidthDp by rememberSaveable(settings.customTheme.borderWidthDp) {
        mutableFloatStateOf(settings.customTheme.borderWidthDp)
    }
    var customElevationDp by rememberSaveable(settings.customTheme.elevationDp) {
        mutableFloatStateOf(settings.customTheme.elevationDp)
    }
    var customPanelOpacity by rememberSaveable(settings.customTheme.panelOpacity) {
        mutableFloatStateOf(settings.customTheme.panelOpacity)
    }
    var customSpacingScale by rememberSaveable(settings.customTheme.spacingScale) {
        mutableFloatStateOf(settings.customTheme.spacingScale)
    }
    var customAnimationScale by rememberSaveable(settings.customTheme.animationScale) {
        mutableFloatStateOf(settings.customTheme.animationScale)
    }
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
    var compactMode by rememberSaveable(settings.compactMode) {
        mutableStateOf(settings.compactMode)
    }
    var backgroundImageUri by rememberSaveable(settings.backgroundImageUri) {
        mutableStateOf(settings.backgroundImageUri)
    }
    var backgroundImageOpacity by rememberSaveable(settings.backgroundImageOpacity) {
        mutableFloatStateOf(settings.backgroundImageOpacity.coerceIn(0f, 1f))
    }
    var backgroundImageBlurDp by rememberSaveable(settings.backgroundImageBlurDp) {
        mutableFloatStateOf(settings.backgroundImageBlurDp.coerceIn(0f, 40f))
    }
    var backgroundImporting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { selected ->
            backgroundImporting = true
            onPersistBackground(selected) { success ->
                backgroundImporting = false
                if (success) backgroundImageUri = selected.toString()
            }
        }
    }
    val parsedThemeColor = parseThemeColor(themeHex)
    val parsedSecondaryColors = secondaryHexes.map(::parseThemeColor)
    val validSecondaryValues = parsedSecondaryColors.filterNotNull()
    val secondaryColorsUnique = validSecondaryValues.distinct().size == secondaryHexes.size
    val secondaryColorsValid = secondaryHexes.size in 2..5 &&
        parsedSecondaryColors.all { it != null } && secondaryColorsUnique
    val rawCustomTheme = customThemeFromDraft(
        baseStyle = customBaseStyle,
        lightHexes = customLightHexes,
        darkHexes = customDarkHexes,
        cornerRadiusDp = customCornerRadiusDp,
        borderWidthDp = customBorderWidthDp,
        elevationDp = customElevationDp,
        panelOpacity = customPanelOpacity,
        spacingScale = customSpacingScale,
        animationScale = customAnimationScale,
    )
    val parsedCustomTheme = rawCustomTheme?.normalized()
    val customThemeValid = parsedCustomTheme != null
    val customThemeNeedsSafetyAdjustment = rawCustomTheme != null &&
        rawCustomTheme != parsedCustomTheme
    val customThemeDraftDirty = customBaseStyle != settings.customTheme.baseStyle ||
        customLightHexes != settings.customTheme.lightPalette.toThemeHexList() ||
        customDarkHexes != settings.customTheme.darkPalette.toThemeHexList() ||
        customCornerRadiusDp != settings.customTheme.cornerRadiusDp ||
        customBorderWidthDp != settings.customTheme.borderWidthDp ||
        customElevationDp != settings.customTheme.elevationDp ||
        customPanelOpacity != settings.customTheme.panelOpacity ||
        customSpacingScale != settings.customTheme.spacingScale ||
        customAnimationScale != settings.customTheme.animationScale
    val appearanceDirty = visualStyle != settings.visualStyle ||
        customThemeDraftDirty ||
        darkMode != settings.darkMode || language != settings.appLanguage ||
        parsedThemeColor != settings.themeColorArgb ||
        validSecondaryValues != settings.themeSecondaryColorsArgb ||
        fontScale != settings.fontScale ||
        compactMode != settings.compactMode ||
        backgroundImageUri != settings.backgroundImageUri ||
        backgroundImageOpacity != settings.backgroundImageOpacity ||
        backgroundImageBlurDp != settings.backgroundImageBlurDp
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = appearanceDirty,
        enabled = parsedThemeColor != null && secondaryColorsValid &&
            (visualStyle != VisualStyle.CUSTOM || customThemeValid) &&
            !saving && !backgroundImporting,
        onReset = {
            val defaults = AppSettings()
            visualStyle = defaults.visualStyle
            customBaseStyle = defaults.customTheme.baseStyle
            customLightHexes = defaults.customTheme.lightPalette.toThemeHexList()
            customDarkHexes = defaults.customTheme.darkPalette.toThemeHexList()
            customCornerRadiusDp = defaults.customTheme.cornerRadiusDp
            customBorderWidthDp = defaults.customTheme.borderWidthDp
            customElevationDp = defaults.customTheme.elevationDp
            customPanelOpacity = defaults.customTheme.panelOpacity
            customSpacingScale = defaults.customTheme.spacingScale
            customAnimationScale = defaults.customTheme.animationScale
            darkMode = defaults.darkMode
            language = defaults.appLanguage
            themeHex = colorToHex(defaults.themeColorArgb)
            secondaryHexes = defaults.themeSecondaryColorsArgb.map(::colorToHex)
            fontScale = defaults.fontScale
            compactMode = defaults.compactMode
            backgroundImageUri = defaults.backgroundImageUri
            backgroundImageOpacity = defaults.backgroundImageOpacity
            backgroundImageBlurDp = defaults.backgroundImageBlurDp
        },
    ) {
        saving = true
        onSave(
            visualStyle,
            parsedCustomTheme ?: settings.customTheme,
            darkMode,
            language,
            parsedThemeColor ?: settings.themeColorArgb,
            parsedSecondaryColors.filterNotNull(),
            fontScale,
            compactMode,
            backgroundImageUri,
            backgroundImageOpacity,
            backgroundImageBlurDp,
        ) { saved ->
            if (!saved) saving = false
        }
    }

    colorPickerTarget?.let { target ->
        val initial = when {
            target < 0 -> parsedThemeColor ?: settings.themeColorArgb
            target >= 200 -> customDarkHexes.getOrNull(target - 200)
                ?.let(::parseThemeColor) ?: settings.customTheme.darkPalette.surfaceArgb
            target >= 100 -> customLightHexes.getOrNull(target - 100)
                ?.let(::parseThemeColor) ?: settings.customTheme.lightPalette.surfaceArgb
            else -> secondaryHexes.getOrNull(target)?.let(::parseThemeColor)
                ?: settings.themeSecondaryColorsArgb.firstOrNull()
                ?: settings.themeColorArgb
        }
        ColorPickerDialog(
            initialColorArgb = initial,
            onDismiss = { colorPickerTarget = null },
            onConfirm = { picked ->
                when {
                    target < 0 -> themeHex = colorToHex(picked)
                    target >= 200 -> customDarkHexes = customDarkHexes.toMutableList().apply {
                        val index = target - 200
                        if (index in indices) this[index] = colorToHex(picked)
                    }
                    target >= 100 -> customLightHexes = customLightHexes.toMutableList().apply {
                        val index = target - 100
                        if (index in indices) this[index] = colorToHex(picked)
                    }
                    target < secondaryHexes.size -> {
                        secondaryHexes = secondaryHexes.toMutableList().apply {
                            this[target] = colorToHex(picked)
                        }
                    }
                }
                colorPickerTarget = null
            },
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
                                    VisualStyle.CUSTOM -> tr("自定义", "Custom")
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
                        VisualStyle.CUSTOM -> tr(
                            "自定义 · 使用受控颜色和视觉参数，不执行 CSS、脚本或任意选择器",
                            "Custom · Controlled colors and visual parameters, without CSS, scripts, or arbitrary selectors",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (visualStyle == VisualStyle.CUSTOM) {
            item {
                SettingsSection(tr("自定义主题", "Custom theme")) {
                    Text(
                        tr(
                            "设置只映射到 Compose 的受控主题角色；不会加载 CSS、脚本、网络资源或修改页面结构。",
                            "Settings map only to controlled Compose theme roles; no CSS, scripts, network resources, or page-structure changes are loaded.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(tr("基础渲染", "Base rendering"), style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        CustomThemeBaseStyle.entries.forEachIndexed { index, style ->
                            SegmentedButton(
                                selected = customBaseStyle == style,
                                onClick = { customBaseStyle = style },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index,
                                    CustomThemeBaseStyle.entries.size,
                                ),
                            ) {
                                Text(
                                    when (style) {
                                        CustomThemeBaseStyle.MATERIAL -> tr("原生", "Material")
                                        CustomThemeBaseStyle.LIQUID_GLASS -> tr("玻璃", "Glass")
                                        CustomThemeBaseStyle.ORGANIC_FUTURE -> tr("有机", "Organic")
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(tr("浅色模式颜色", "Light-mode colors"), style = MaterialTheme.typography.labelLarge)
                    CustomThemePaletteEditor(
                        hexes = customLightHexes,
                        onHexChanged = { index, value ->
                            customLightHexes = customLightHexes.toMutableList().apply {
                                this[index] = value.take(7)
                            }
                        },
                        onPickColor = { index -> colorPickerTarget = 100 + index },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(tr("深色模式颜色", "Dark-mode colors"), style = MaterialTheme.typography.labelLarge)
                    CustomThemePaletteEditor(
                        hexes = customDarkHexes,
                        onHexChanged = { index, value ->
                            customDarkHexes = customDarkHexes.toMutableList().apply {
                                this[index] = value.take(7)
                            }
                        },
                        onPickColor = { index -> colorPickerTarget = 200 + index },
                    )
                    if (!customThemeValid) {
                        Text(
                            tr(
                                "所有自定义颜色都必须使用 #RRGGBB 格式。",
                                "Every custom color must use #RRGGBB format.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (customThemeNeedsSafetyAdjustment) {
                        Text(
                            tr(
                                "部分文字或边框颜色对比度不足，保存时会自动调整到可读颜色。",
                                "Some text or border colors have low contrast and will be adjusted to readable colors when saved.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    CustomThemeSlider(
                        label = tr("全局圆角", "Global corners"),
                        valueLabel = "${customCornerRadiusDp.roundToInt()} dp",
                        value = customCornerRadiusDp,
                        valueRange = MIN_CUSTOM_THEME_CORNER_RADIUS_DP..MAX_CUSTOM_THEME_CORNER_RADIUS_DP,
                        steps = 39,
                        onValueChange = { customCornerRadiusDp = it.roundToInt().toFloat() },
                    )
                    CustomThemeSlider(
                        label = tr("面板边框", "Panel border"),
                        valueLabel = "${formatThemeDecimal(customBorderWidthDp)} dp",
                        value = customBorderWidthDp,
                        valueRange = MIN_CUSTOM_THEME_BORDER_WIDTH_DP..MAX_CUSTOM_THEME_BORDER_WIDTH_DP,
                        steps = 15,
                        onValueChange = { customBorderWidthDp = snapThemeValue(it, 0.25f) },
                    )
                    CustomThemeSlider(
                        label = tr("面板阴影", "Panel elevation"),
                        valueLabel = "${customElevationDp.roundToInt()} dp",
                        value = customElevationDp,
                        valueRange = MIN_CUSTOM_THEME_ELEVATION_DP..MAX_CUSTOM_THEME_ELEVATION_DP,
                        steps = 15,
                        onValueChange = { customElevationDp = it.roundToInt().toFloat() },
                    )
                    CustomThemeSlider(
                        label = tr("面板不透明度", "Panel opacity"),
                        valueLabel = "${(customPanelOpacity * 100).roundToInt()}%",
                        value = customPanelOpacity,
                        valueRange = MIN_CUSTOM_THEME_PANEL_OPACITY..MAX_CUSTOM_THEME_PANEL_OPACITY,
                        steps = 6,
                        onValueChange = { customPanelOpacity = snapThemeValue(it, 0.05f) },
                    )
                    CustomThemeSlider(
                        label = tr("面板内容间距", "Panel content spacing"),
                        valueLabel = "${(customSpacingScale * 100).roundToInt()}%",
                        value = customSpacingScale,
                        valueRange = MIN_CUSTOM_THEME_SPACING_SCALE..MAX_CUSTOM_THEME_SPACING_SCALE,
                        steps = 11,
                        onValueChange = { customSpacingScale = snapThemeValue(it, 0.05f) },
                    )
                    CustomThemeSlider(
                        label = tr("页面切换动效", "Page transition motion"),
                        valueLabel = "${(customAnimationScale * 100).roundToInt()}%",
                        value = customAnimationScale,
                        valueRange = MIN_CUSTOM_THEME_ANIMATION_SCALE..MAX_CUSTOM_THEME_ANIMATION_SCALE,
                        steps = 19,
                        onValueChange = { customAnimationScale = snapThemeValue(it, 0.1f) },
                    )
                    Text(
                        tr(
                            "设为 0% 会关闭页面切换动效；系统“移除动画”设置仍具有更高优先级。",
                            "0% disables page transitions; the system Remove animations setting still takes precedence.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            SettingsSection(tr("屏幕方向", "Screen orientation")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    OrientationPreference.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.orientationPreference == mode,
                            onClick = { onOrientationChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, OrientationPreference.entries.size),
                        ) {
                            Text(
                                when (mode) {
                                    OrientationPreference.AUTO -> tr("自动", "Automatic")
                                    OrientationPreference.PORTRAIT -> tr("竖屏", "Portrait")
                                    OrientationPreference.LANDSCAPE -> tr("横屏", "Landscape")
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("仅应用于此设备", "Applies only to this device."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("软件语言", "App language")) {
                var languageMenuExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = languageMenuExpanded,
                    onExpandedChange = { languageMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = appLanguageLabel(language),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(tr("软件语言", "App language")) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = languageMenuExpanded,
                        onDismissRequest = { languageMenuExpanded = false },
                    ) {
                        AppLanguage.entries.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(appLanguageLabel(item)) },
                                onClick = {
                                    language = item
                                    languageMenuExpanded = false
                                },
                            )
                        }
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
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { colorPickerTarget = -1 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Palette,
                            tr("自定义主颜色", "Custom primary color"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            tr(
                                "设置 2–5 个，三种风格都会在不同位置使用",
                                "Choose 2–5; every style uses them in different places",
                            ),
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
                                .background(parsed?.let(::Color) ?: MaterialTheme.colorScheme.errorContainer)
                                .clickable { colorPickerTarget = index },
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
            SettingsSection(tr("背景图片", "Background image")) {
                Text(
                    tr(
                        "图片只从你选择的位置读取，不会复制进应用备份。可调整可见度和模糊，让文字与卡片保持清晰。",
                        "The image is read only from the location you choose and is not copied into app backups. Adjust visibility and blur to keep text and cards readable.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (backgroundImageUri == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                tr("尚未选择背景", "No background selected"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        AsyncImage(
                            model = backgroundImageUri,
                            contentDescription = tr("背景图片预览", "Background preview"),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(backgroundImageBlurDp.dp)
                                .graphicsLayer {
                                    alpha = backgroundImageOpacity
                                    val overscan = 1f + backgroundImageBlurDp / 160f
                                    scaleX = overscan
                                    scaleY = overscan
                                },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { backgroundLauncher.launch(arrayOf("image/*")) },
                        enabled = !backgroundImporting && !saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (backgroundImporting) tr("正在读取…", "Reading…")
                            else if (backgroundImageUri == null) tr("选择图片", "Choose image")
                            else tr("更换图片", "Change image"),
                        )
                    }
                    if (backgroundImageUri != null) {
                        OutlinedButton(
                            onClick = { backgroundImageUri = null },
                            enabled = !backgroundImporting && !saving,
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("移除", "Remove"))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("图片可见度", "Image visibility"))
                    Text(
                        "${(backgroundImageOpacity * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = backgroundImageOpacity,
                    onValueChange = {
                        backgroundImageOpacity = (it * 20f).roundToInt() / 20f
                    },
                    valueRange = 0f..1f,
                    steps = 19,
                    enabled = backgroundImageUri != null,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("背景模糊", "Background blur"))
                    Text(
                        "${backgroundImageBlurDp.roundToInt()} dp",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = backgroundImageBlurDp,
                    onValueChange = { backgroundImageBlurDp = it.roundToInt().toFloat() },
                    valueRange = 0f..40f,
                    steps = 39,
                    enabled = backgroundImageUri != null,
                )
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
        item {
            SettingsSection(tr("紧凑模式", "Compact mode")) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("紧凑显示", "Compact layout"))
                        Text(
                            tr("缩小列表与卡片间距，一屏显示更多内容", "Tighter list spacing to fit more on screen"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = compactMode, onCheckedChange = { compactMode = it })
                }
            }
        }
    }
}

private val customThemeRoleLabels: List<Pair<String, String>> = listOf(
    "页面背景" to "Page background",
    "背景文字" to "Background text",
    "基础表面" to "Base surface",
    "正文文字" to "Body text",
    "卡片表面" to "Card surface",
    "次级表面" to "Secondary surface",
    "次要文字" to "Secondary text",
    "边框" to "Outline",
)

@Composable
private fun CustomThemePaletteEditor(
    hexes: List<String>,
    onHexChanged: (Int, String) -> Unit,
    onPickColor: (Int) -> Unit,
) {
    customThemeRoleLabels.forEachIndexed { index, labels ->
        val value = hexes.getOrElse(index) { "" }
        val parsed = parseThemeColor(value)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(parsed?.let(::Color) ?: MaterialTheme.colorScheme.errorContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { onPickColor(index) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Palette,
                    contentDescription = tr("选择${labels.first}", "Pick ${labels.second}"),
                    modifier = Modifier.size(17.dp),
                    tint = parsed?.let { color ->
                        if (Color(color).luminance() > 0.5f) Color.Black else Color.White
                    } ?: MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { onHexChanged(index, it) },
                label = { Text(tr(labels.first, labels.second)) },
                isError = parsed == null,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CustomThemeSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(valueLabel, color = MaterialTheme.colorScheme.primary)
    }
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun CustomThemePalette.toThemeHexList(): List<String> = listOf(
    backgroundArgb,
    onBackgroundArgb,
    surfaceArgb,
    onSurfaceArgb,
    surfaceContainerArgb,
    surfaceVariantArgb,
    onSurfaceVariantArgb,
    outlineArgb,
).map(::colorToHex)

private fun customThemeFromDraft(
    baseStyle: CustomThemeBaseStyle,
    lightHexes: List<String>,
    darkHexes: List<String>,
    cornerRadiusDp: Float,
    borderWidthDp: Float,
    elevationDp: Float,
    panelOpacity: Float,
    spacingScale: Float,
    animationScale: Float,
): CustomThemeSettings? {
    fun palette(values: List<String>): CustomThemePalette? {
        if (values.size != customThemeRoleLabels.size) return null
        val parsed = values.map(::parseThemeColor)
        if (parsed.any { it == null }) return null
        return CustomThemePalette(
            backgroundArgb = parsed[0]!!,
            onBackgroundArgb = parsed[1]!!,
            surfaceArgb = parsed[2]!!,
            onSurfaceArgb = parsed[3]!!,
            surfaceContainerArgb = parsed[4]!!,
            surfaceVariantArgb = parsed[5]!!,
            onSurfaceVariantArgb = parsed[6]!!,
            outlineArgb = parsed[7]!!,
        )
    }
    return CustomThemeSettings(
        baseStyle = baseStyle,
        lightPalette = palette(lightHexes) ?: return null,
        darkPalette = palette(darkHexes) ?: return null,
        cornerRadiusDp = cornerRadiusDp,
        borderWidthDp = borderWidthDp,
        elevationDp = elevationDp,
        panelOpacity = panelOpacity,
        spacingScale = spacingScale,
        animationScale = animationScale,
    )
}

private fun snapThemeValue(value: Float, step: Float): Float =
    (value / step).roundToInt() * step

private fun formatThemeDecimal(value: Float): String =
    String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

@Composable
private fun HomeSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onOpenGreeting: () -> Unit,
    onSave: (HomeSettingsDraft) -> Unit,
) {
    var widgetBordersEnabled by rememberSaveable(settings.homeWidgetBordersEnabled) {
        mutableStateOf(settings.homeWidgetBordersEnabled)
    }
    var widgets by remember(settings.homeWidgets) { mutableStateOf(settings.homeWidgets.distinct()) }
    var gameShortcuts by remember(settings.homeGameShortcuts) {
        mutableStateOf(settings.homeGameShortcuts.distinct())
    }
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
    val widgetDragSourceIndex = draggingWidgetId?.let(widgets::indexOf)?.takeIf { it >= 0 }
    val widgetInsertionSlot = widgetDragSourceIndex?.let { sourceIndex ->
        widgetDragTargetIndex?.let { targetIndex ->
            if (targetIndex > sourceIndex) targetIndex + 1 else targetIndex
        }
    }
    val homeDraft = HomeSettingsDraft(
        userName = settings.userName,
        widgetBordersEnabled = widgetBordersEnabled,
        widgets = widgets,
        gameShortcuts = gameShortcuts,
        visibleWidgetTitles = visibleWidgetTitles,
        mealButtonsUseIcons = mealButtonsUseIcons,
        mealButtonIcons = mealButtonIcons.map(String::trim),
    )
    val homeDirty = homeDraft != HomeSettingsDraft(
        userName = settings.userName,
        widgetBordersEnabled = settings.homeWidgetBordersEnabled,
        widgets = settings.homeWidgets.distinct(),
        gameShortcuts = settings.homeGameShortcuts.distinct(),
        visibleWidgetTitles = settings.homeWidgetTitles.distinct(),
        mealButtonsUseIcons = settings.mealButtonsUseIcons,
        mealButtonIcons = settings.mealButtonIcons,
    )
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = homeDirty,
        enabled = mealButtonIcons.all { it.isNotBlank() },
        onReset = {
            val defaults = AppSettings()
            widgetBordersEnabled = defaults.homeWidgetBordersEnabled
            widgets = defaults.homeWidgets
            gameShortcuts = defaults.homeGameShortcuts
            visibleWidgetTitles = defaults.homeWidgetTitles
            mealButtonsUseIcons = defaults.mealButtonsUseIcons
            mealButtonIcons = defaults.mealButtonIcons
        },
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
            SettingsMenuItem(
                title = tr("主页问候", "Home greeting"),
                description = tr(
                    "管理用户名和每日问候语，可增加、修改或删除",
                    "Manage your name and add, edit or delete daily greetings",
                ),
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                accentColor = settings.menuAccentColor(0),
                onClick = onOpenGreeting,
            )
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
            SettingsSection(tr("小游戏快捷入口", "Mini-game shortcuts")) {
                Text(
                    tr(
                        "选择主页“小游戏”模块中显示的入口；全部关闭时模块会显示设置提示。",
                        "Choose which entries appear in the Home mini-games widget. With all disabled, the widget shows a settings hint.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                homeGameShortcutOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                gameShortcuts = if (option.id in gameShortcuts) {
                                    gameShortcuts - option.id
                                } else {
                                    homeGameShortcutOptions
                                        .map(HomeWidgetOption::id)
                                        .filter { it in gameShortcuts || it == option.id }
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tr(option.chinese, option.english),
                            modifier = Modifier.weight(1f),
                        )
                        Checkbox(
                            checked = option.id in gameShortcuts,
                            onCheckedChange = { checked ->
                                gameShortcuts = if (checked) {
                                    homeGameShortcutOptions
                                        .map(HomeWidgetOption::id)
                                        .filter { it in gameShortcuts || it == option.id }
                                } else {
                                    gameShortcuts - option.id
                                }
                            },
                        )
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
private fun HomeGreetingSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (String, List<HomeGreetingTemplate>) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var userName by rememberSaveable(settings.userName) { mutableStateOf(settings.userName) }
    var greetings by rememberSaveable(
        settings.homeGreetings,
        stateSaver = HomeGreetingTemplateListSaver,
    ) {
        mutableStateOf(settings.homeGreetings)
    }
    val normalizedName = userName.trim().takeCodePoints(32)
    val valid = greetings.size <= MAX_HOME_GREETINGS && greetings.all { item ->
        item.chinese.isNotBlank() || item.english.isNotBlank()
    }
    val dirty = normalizedName != settings.userName || greetings != settings.homeGreetings

    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = dirty,
        enabled = valid,
        onReset = {
            val defaults = AppSettings()
            userName = defaults.userName
            greetings = defaults.homeGreetings
        },
    ) {
        onSave(normalizedName, greetings)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection(tr("显示方式", "Display")) {
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it.takeCodePoints(32) },
                    label = { Text(tr("用户名", "User name")) },
                    supportingText = {
                        Text(
                            tr(
                                "问候语中的 {name} 会替换为此名称",
                                "{name} in a greeting is replaced with this name",
                            ),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString("{name}")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("复制 {name}", "Copy {name}"))
                }
                Text(
                    text = tr("今日预览", "Today's preview"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = HomeGreeting.forDate(
                        date = LocalDate.now(),
                        language = settings.appLanguage,
                        userName = normalizedName,
                        templates = greetings,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = tr(
                        "每天按列表顺序轮换；全部删除后主页显示“今日概览”。单条最多 $MAX_HOME_GREETING_CODE_POINTS 个字符。",
                        "Greetings rotate daily in list order. If all are deleted, Home shows “Today's overview”. Each field allows up to $MAX_HOME_GREETING_CODE_POINTS characters.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        itemsIndexed(
            items = greetings,
            key = { index, _ -> index },
        ) { index, item ->
            SettingsSection(tr("问候语 ${index + 1}", "Greeting ${index + 1}")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = {
                            greetings = greetings.toMutableList().apply { removeAt(index) }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = tr("删除这条问候语", "Delete this greeting"),
                        )
                    }
                }
                val bothBlank = item.chinese.isBlank() && item.english.isBlank()
                OutlinedTextField(
                    value = item.chinese,
                    onValueChange = { value ->
                        greetings = greetings.toMutableList().apply {
                            this[index] = item.copy(
                                chinese = value.takeCodePoints(MAX_HOME_GREETING_CODE_POINTS),
                            )
                        }
                    },
                    label = { Text(tr("中文", "Chinese")) },
                    supportingText = {
                        Text(
                            if (bothBlank) {
                                tr(
                                    "中文或英文至少填写一项",
                                    "Enter at least Chinese or English",
                                )
                            } else {
                                tr(
                                    "可使用 {name}；留空时使用英文内容",
                                    "You may use {name}; blank falls back to English",
                                )
                            },
                        )
                    },
                    isError = bothBlank,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = item.english,
                    onValueChange = { value ->
                        greetings = greetings.toMutableList().apply {
                            this[index] = item.copy(
                                english = value.takeCodePoints(MAX_HOME_GREETING_CODE_POINTS),
                            )
                        }
                    },
                    label = { Text(tr("英文", "English")) },
                    supportingText = {
                        Text(
                            if (bothBlank) {
                                tr(
                                    "中文或英文至少填写一项",
                                    "Enter at least Chinese or English",
                                )
                            } else {
                                tr(
                                    "可使用 {name}；留空时使用中文内容",
                                    "You may use {name}; blank falls back to Chinese",
                                )
                            },
                        )
                    },
                    isError = bothBlank,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            OutlinedButton(
                onClick = {
                    if (greetings.size < MAX_HOME_GREETINGS) {
                        greetings = greetings + HomeGreetingTemplate(chinese = "", english = "")
                    }
                },
                enabled = greetings.size < MAX_HOME_GREETINGS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(tr("增加问候语", "Add greeting"))
            }
        }
    }
}

@Composable
private fun PoetrySettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onImportFont: (Uri, (Boolean) -> Unit) -> Unit,
    onSave: (
        fontUri: String?,
        fontSizeSp: Float,
        lineSpacing: Float,
        textAlignment: PoetryTextAlignment,
        showSource: Boolean,
        showQuoteMark: Boolean,
        sevenCharacterWrapEnabled: Boolean,
    ) -> Unit,
) {
    var fontUri by rememberSaveable(settings.poetryFontUri) {
        mutableStateOf(settings.poetryFontUri)
    }
    var fontSizeSp by rememberSaveable(settings.poetryFontSizeSp) {
        mutableStateOf(settings.poetryFontSizeSp)
    }
    var lineSpacing by rememberSaveable(settings.poetryLineSpacing) {
        mutableStateOf(settings.poetryLineSpacing)
    }
    var textAlignment by rememberSaveable(settings.poetryTextAlignment) {
        mutableStateOf(settings.poetryTextAlignment)
    }
    var showSource by rememberSaveable(settings.poetryShowSource) {
        mutableStateOf(settings.poetryShowSource)
    }
    var showQuoteMark by rememberSaveable(settings.poetryShowQuoteMark) {
        mutableStateOf(settings.poetryShowQuoteMark)
    }
    var sevenCharacterWrapEnabled by rememberSaveable(
        settings.poetrySevenCharacterWrapEnabled,
    ) {
        mutableStateOf(settings.poetrySevenCharacterWrapEnabled)
    }
    val importedFontFamily = rememberPoetryFontFamily(fontUri)
    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            onImportFont(uri) { imported ->
                if (imported) fontUri = uri.toString()
            }
        }
    }
    val dirty = fontUri != settings.poetryFontUri ||
        fontSizeSp != settings.poetryFontSizeSp ||
        lineSpacing != settings.poetryLineSpacing ||
        textAlignment != settings.poetryTextAlignment ||
        showSource != settings.poetryShowSource ||
        showQuoteMark != settings.poetryShowQuoteMark ||
        sevenCharacterWrapEnabled != settings.poetrySevenCharacterWrapEnabled

    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = dirty,
        onReset = {
            val defaults = AppSettings()
            fontUri = defaults.poetryFontUri
            fontSizeSp = defaults.poetryFontSizeSp
            lineSpacing = defaults.poetryLineSpacing
            textAlignment = defaults.poetryTextAlignment
            showSource = defaults.poetryShowSource
            showQuoteMark = defaults.poetryShowQuoteMark
            sevenCharacterWrapEnabled = defaults.poetrySevenCharacterWrapEnabled
        },
    ) {
        onSave(
            fontUri,
            fontSizeSp,
            lineSpacing,
            textAlignment,
            showSource,
            showQuoteMark,
            sevenCharacterWrapEnabled,
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("字体", "Font")) {
                Text(
                    if (fontUri == null) {
                        tr("使用应用默认字体", "Using the app default font")
                    } else {
                        tr("已导入自定义字体", "Custom font imported")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            fontPicker.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "application/x-font-ttf",
                                    "application/x-font-opentype",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("导入字体", "Import font"))
                    }
                    OutlinedButton(
                        onClick = { fontUri = null },
                        enabled = fontUri != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(tr("恢复默认", "Use default"))
                    }
                }
                Text(
                    tr(
                        "字号 ${fontSizeSp.roundToInt()} sp",
                        "Font size ${fontSizeSp.roundToInt()} sp",
                    ),
                )
                Slider(
                    value = fontSizeSp,
                    onValueChange = { fontSizeSp = it },
                    valueRange = MIN_POETRY_FONT_SIZE_SP..MAX_POETRY_FONT_SIZE_SP,
                    steps = (MAX_POETRY_FONT_SIZE_SP - MIN_POETRY_FONT_SIZE_SP).toInt() - 1,
                )
            }
        }
        item {
            SettingsSection(tr("排版", "Layout")) {
                Text(
                    tr(
                        "行距 ${String.format(Locale.ROOT, "%.2f", lineSpacing)} 倍",
                        "Line spacing ${String.format(Locale.ROOT, "%.2f", lineSpacing)}×",
                    ),
                )
                Slider(
                    value = lineSpacing,
                    onValueChange = { lineSpacing = it },
                    valueRange = MIN_POETRY_LINE_SPACING..MAX_POETRY_LINE_SPACING,
                    steps = 9,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PoetryTextAlignment.entries.forEachIndexed { index, alignment ->
                        SegmentedButton(
                            selected = textAlignment == alignment,
                            onClick = { textAlignment = alignment },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = PoetryTextAlignment.entries.size,
                            ),
                            label = {
                                Text(
                                    when (alignment) {
                                        PoetryTextAlignment.START -> tr("左对齐", "Start")
                                        PoetryTextAlignment.CENTER -> tr("居中", "Center")
                                    },
                                )
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("显示出处", "Show source"), modifier = Modifier.weight(1f))
                    Switch(checked = showSource, onCheckedChange = { showSource = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("显示引号装饰", "Show quote decoration"), modifier = Modifier.weight(1f))
                    Switch(checked = showQuoteMark, onCheckedChange = { showQuoteMark = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("七言诗自动换行", "Wrap seven-character verse"))
                        Text(
                            tr(
                                "每七个正文字符连同紧随标点显示为一行",
                                "Show every seven content characters with trailing punctuation on one line",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = sevenCharacterWrapEnabled,
                        onCheckedChange = { sevenCharacterWrapEnabled = it },
                    )
                }
            }
        }
        item {
            SettingsSection(tr("预览", "Preview")) {
                Text(
                    text = if (sevenCharacterWrapEnabled) {
                        val preview = tr(
                            "两个黄鹂鸣翠柳，一行白鹭上青天。",
                            "Two orioles sing among green willows; a white egret climbs the blue sky.",
                        )
                        if (isSevenCharacterPoem(preview)) wrapSevenCharacterVerse(preview) else preview
                    } else {
                        tr(
                            "山中何事？松花酿酒，春水煎茶。",
                            "What happens in the hills? Pine blossoms brew wine; spring water makes tea.",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    fontFamily = importedFontFamily,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * lineSpacing).sp,
                    textAlign = when (textAlignment) {
                        PoetryTextAlignment.START -> TextAlign.Start
                        PoetryTextAlignment.CENTER -> TextAlign.Center
                    },
                )
                if (showSource) {
                    Text(
                        text = tr("—— 张可久《人月圆·山中书事》", "— Zhang Kejiu"),
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = importedFontFamily,
                        textAlign = when (textAlignment) {
                            PoetryTextAlignment.START -> TextAlign.Start
                            PoetryTextAlignment.CENTER -> TextAlign.Center
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    var markdownHeadingSizes by remember(settings.markdownHeadingSizesSp) {
        mutableStateOf(normalizeMarkdownHeadingSizes(settings.markdownHeadingSizesSp))
    }
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
    var saveOriginalToGallery by rememberSaveable(settings.saveOriginalToGallery) {
        mutableStateOf(settings.saveOriginalToGallery)
    }
    var photoLocationEnabled by rememberSaveable(settings.photoLocationEnabled) {
        mutableStateOf(settings.photoLocationEnabled)
    }
    val mediaLocationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Best-effort: the feature still works for camera photos when denied. */ }
    var mealCalendarWrapEnabled by rememberSaveable(settings.mealCalendarWrapEnabled) {
        mutableStateOf(settings.mealCalendarWrapEnabled)
    }
    var mealCalendarPhotosPerRow by rememberSaveable(settings.mealCalendarPhotosPerRow) {
        mutableStateOf(settings.mealCalendarPhotosPerRow)
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
        markdownHeadingSizesSp = normalizeMarkdownHeadingSizes(markdownHeadingSizes),
        mealImageCompressionEnabled = mealImageCompressionEnabled,
        mealImageCompressionQuality = mealImageCompressionQuality,
        saveOriginalToGallery = saveOriginalToGallery,
        photoLocationEnabled = photoLocationEnabled,
        mealCalendarImageMaxHeightDp = mealCalendarImageMaxHeight,
        mealCalendarShowCaptions = mealCalendarShowCaptions,
        mealCalendarWrapEnabled = mealCalendarWrapEnabled,
        mealCalendarPhotosPerRow = mealCalendarPhotosPerRow,
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
        diaryDraft.markdownHeadingSizesSp != normalizeMarkdownHeadingSizes(
            settings.markdownHeadingSizesSp,
        ) ||
        mealImageCompressionEnabled != settings.mealImageCompressionEnabled ||
        mealImageCompressionQuality != settings.mealImageCompressionQuality ||
        saveOriginalToGallery != settings.saveOriginalToGallery ||
        photoLocationEnabled != settings.photoLocationEnabled ||
        mealCalendarImageMaxHeight != settings.mealCalendarImageMaxHeightDp ||
        mealCalendarShowCaptions != settings.mealCalendarShowCaptions ||
        mealCalendarWrapEnabled != settings.mealCalendarWrapEnabled ||
        mealCalendarPhotosPerRow != settings.mealCalendarPhotosPerRow ||
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
        onReset = {
            val defaults = AppSettings()
            diaryTreeUri = defaults.diaryTreeUri
            mediaTreeUri = defaults.mediaTreeUri
            filePattern = defaults.fileNamePattern
            template = defaults.markdownTemplate
            imagePattern = defaults.imageNamePattern
            imageWidth = defaults.imageMaxWidthDp.toString()
            imageHeight = defaults.imageMaxHeightDp.toString()
            markdownHeadingSizes = defaults.markdownHeadingSizesSp
            mealImageCompressionEnabled = defaults.mealImageCompressionEnabled
            mealImageCompressionQuality = defaults.mealImageCompressionQuality
            saveOriginalToGallery = defaults.saveOriginalToGallery
            photoLocationEnabled = defaults.photoLocationEnabled
            mealCalendarImageMaxHeight = defaults.mealCalendarImageMaxHeightDp
            mealCalendarShowCaptions = defaults.mealCalendarShowCaptions
            mealCalendarWrapEnabled = defaults.mealCalendarWrapEnabled
            mealCalendarPhotosPerRow = defaults.mealCalendarPhotosPerRow
            calorieEnabled = defaults.calorieEstimationEnabled
            calorieTextConfigId = defaults.calorieTextConfigId
            calorieImageConfigId = defaults.calorieImageConfigId
            calorieVisionPrompt = defaults.calorieVisionPrompt
            calorieTextPrompt = defaults.calorieTextPrompt
        },
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
            SettingsSection(tr("Markdown 阅读预览", "Markdown reading preview")) {
                Text(
                    tr(
                        "标题、粗体、斜体、列表、引用、代码和链接会保留排版。下面可分别调整 H1–H6 标题字号；笔记预览也共用这些设置。",
                        "Headings, emphasis, lists, quotes, code, and links retain their formatting. Adjust H1–H6 sizes individually below; Notes uses the same preview settings.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                markdownHeadingSizes.forEachIndexed { index, size ->
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                tr("${index + 1} 级标题", "Heading ${index + 1}"),
                                fontSize = size.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("${size.roundToInt()} sp", color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = size,
                            onValueChange = { value ->
                                markdownHeadingSizes = markdownHeadingSizes.mapIndexed {
                                        itemIndex, current ->
                                    if (itemIndex == index) value.roundToInt().toFloat() else current
                                }
                            },
                            valueRange = MIN_MARKDOWN_HEADING_SIZE_SP..
                                MAX_MARKDOWN_HEADING_SIZE_SP,
                            steps = (MAX_MARKDOWN_HEADING_SIZE_SP -
                                MIN_MARKDOWN_HEADING_SIZE_SP).toInt() - 1,
                        )
                    }
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
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("保存原图到系统相册", "Save original to system gallery"))
                        Text(
                            tr(
                                "导入图片时把未压缩的原图另存到系统相册的 DeskCubby 相簿。",
                                "Also saves the uncompressed original into the DeskCubby album in the system gallery.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = saveOriginalToGallery,
                        onCheckedChange = { saveOriginalToGallery = it },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("记录照片拍摄地点", "Record photo location"))
                        Text(
                            tr(
                                "从照片 EXIF 读取拍摄地点，与热量一起记录到媒体目录的 dc-media.json；相册照片可能需要授予媒体位置权限。",
                                "Reads the photo's EXIF location into dc-media.json in the media folder alongside calories; gallery photos may need the media-location permission.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = photoLocationEnabled,
                        onCheckedChange = { enabled ->
                            photoLocationEnabled = enabled
                            if (enabled && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                mediaLocationPermission.launch(
                                    android.Manifest.permission.ACCESS_MEDIA_LOCATION,
                                )
                            }
                        },
                    )
                }
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
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("图片自动换行", "Wrap photos into rows"))
                        Text(
                            tr(
                                "关闭时单行横向滑动，开启后按每行数量换行显示。",
                                "Off keeps one scrollable row; on wraps photos into fixed rows.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = mealCalendarWrapEnabled,
                        onCheckedChange = { mealCalendarWrapEnabled = it },
                    )
                }
                if (mealCalendarWrapEnabled) {
                    Text(tr("每行图片数量", "Photos per row"), style = MaterialTheme.typography.labelLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        MealPhotosPerRow.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = mealCalendarPhotosPerRow == mode,
                                onClick = { mealCalendarPhotosPerRow = mode },
                                shape = SegmentedButtonDefaults.itemShape(index, MealPhotosPerRow.entries.size),
                            ) {
                                Text(
                                    when (mode) {
                                        MealPhotosPerRow.TWO -> "2"
                                        MealPhotosPerRow.THREE -> "3"
                                        MealPhotosPerRow.SMART -> tr("2+3 自动", "2+3 auto")
                                    },
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Text(
                        tr(
                            "2+3 自动：混合每行 2 或 3 张，让最后一行不留空位（如 4=2+2、5=3+2、7=3+2+2）。",
                            "2+3 auto mixes rows of 2 and 3 so the last row is never left short (4=2+2, 5=3+2, 7=3+2+2).",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        onReset = {
            val defaults = AppSettings()
            browserHome = defaults.browserHomeUrl
            browserTheme = defaults.browserTheme
            browserDesktopMode = defaults.browserDesktopMode
        },
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
private fun VaultSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (Int) -> Unit,
) {
    var rowHeight by remember(settings.vaultRowHeightDp) {
        mutableIntStateOf(
            settings.vaultRowHeightDp.coerceIn(MIN_VAULT_ROW_HEIGHT_DP, MAX_VAULT_ROW_HEIGHT_DP),
        )
    }
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = rowHeight != settings.vaultRowHeightDp,
        onReset = { rowHeight = AppSettings().vaultRowHeightDp },
    ) { onSave(rowHeight) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("每行高度", "Row height")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("收藏夹列表", "Vault list"))
                    Text("$rowHeight dp", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = rowHeight.toFloat(),
                    onValueChange = {
                        rowHeight = it.roundToInt().coerceIn(
                            MIN_VAULT_ROW_HEIGHT_DP,
                            MAX_VAULT_ROW_HEIGHT_DP,
                        )
                    },
                    valueRange = MIN_VAULT_ROW_HEIGHT_DP.toFloat()..MAX_VAULT_ROW_HEIGHT_DP.toFloat(),
                    steps = MAX_VAULT_ROW_HEIGHT_DP - MIN_VAULT_ROW_HEIGHT_DP - 1,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        tr("紧凑 $MIN_VAULT_ROW_HEIGHT_DP dp", "Compact $MIN_VAULT_ROW_HEIGHT_DP dp"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        tr("宽松 $MAX_VAULT_ROW_HEIGHT_DP dp", "Spacious $MAX_VAULT_ROW_HEIGHT_DP dp"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    tr(
                        "控制收藏夹条目的最小高度；内容较多的条目仍会按内容自然增高。",
                        "Controls the minimum height of vault entries; longer entries can still grow naturally.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThoughtSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (Int, ThoughtReopenMode, ThoughtDisplayMode, Int, Int) -> Unit,
) {
    var rowHeight by remember(settings.thoughtRowHeightDp) {
        mutableIntStateOf(settings.thoughtRowHeightDp.coerceIn(48, 120))
    }
    var reopenMode by remember(settings.thoughtReopenMode) { mutableStateOf(settings.thoughtReopenMode) }
    var displayMode by remember(settings.thoughtDisplayMode) { mutableStateOf(settings.thoughtDisplayMode) }
    var highlightColor by remember(settings.thoughtHighlightColorArgb) {
        mutableIntStateOf(settings.thoughtHighlightColorArgb)
    }
    var editorMaxHeight by remember(settings.thoughtEditorMaxHeightDp) {
        mutableIntStateOf(
            settings.thoughtEditorMaxHeightDp.coerceIn(
                MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP,
                MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP,
            ),
        )
    }
    var showHighlightPicker by remember { mutableStateOf(false) }
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = rowHeight != settings.thoughtRowHeightDp || reopenMode != settings.thoughtReopenMode ||
            displayMode != settings.thoughtDisplayMode ||
            highlightColor != settings.thoughtHighlightColorArgb ||
            editorMaxHeight != settings.thoughtEditorMaxHeightDp,
        onReset = {
            val defaults = AppSettings()
            rowHeight = defaults.thoughtRowHeightDp
            reopenMode = defaults.thoughtReopenMode
            displayMode = defaults.thoughtDisplayMode
            highlightColor = defaults.thoughtHighlightColorArgb
            editorMaxHeight = defaults.thoughtEditorMaxHeightDp
        },
    ) { onSave(rowHeight, reopenMode, displayMode, highlightColor, editorMaxHeight) }

    if (showHighlightPicker) {
        ColorPickerDialog(
            initialColorArgb = highlightColor,
            onDismiss = { showHighlightPicker = false },
            onConfirm = { picked ->
                highlightColor = picked
                showHighlightPicker = false
            },
        )
    }

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
        item {
            SettingsSection(tr("重点标记", "Highlight")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("重点背景颜色", "Highlight background color"))
                        Text(
                            tr("长按小巧思可标记为重点", "Long-press a thought to mark it as a highlight"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(highlightColor))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { showHighlightPicker = true },
                    )
                }
            }
        }
        item {
            SettingsSection(tr("输入框高度", "Editor height")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("输入框最大高度", "Editor max height"))
                    Text("$editorMaxHeight dp", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = editorMaxHeight.toFloat(),
                    onValueChange = {
                        editorMaxHeight = it.roundToInt().coerceIn(
                            MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP,
                            MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP,
                        )
                    },
                    valueRange = MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP.toFloat()..MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tr("超过上限后输入框内部滚动。", "The editor scrolls internally beyond this height."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        onReset = {
            val defaults = AppSettings()
            maxItems = defaults.rssMaxItemsPerFeed
            showSummaries = defaults.rssShowSummaries
        },
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

@Composable
private fun AiSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onOpenConfigs: () -> Unit,
    onSave: (Float, Float, String, (Boolean) -> Unit) -> Unit,
) {
    var fontSizeSp by rememberSaveable(settings.aiPageFontSizeSp) {
        mutableFloatStateOf(settings.aiPageFontSizeSp)
    }
    var replyWidthDp by rememberSaveable(settings.aiReplyBoxWidthDp) {
        mutableFloatStateOf(settings.aiReplyBoxWidthDp)
    }
    var agentPrompt by remember(settings.agentPrompt) { mutableStateOf(settings.agentPrompt) }
    var saving by remember { mutableStateOf(false) }
    val dirty = fontSizeSp != settings.aiPageFontSizeSp ||
        replyWidthDp != settings.aiReplyBoxWidthDp ||
        agentPrompt != settings.agentPrompt
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = dirty,
        enabled = !saving && agentPrompt.length <= 20_000,
        onReset = {
            val defaults = AppSettings()
            fontSizeSp = defaults.aiPageFontSizeSp
            replyWidthDp = defaults.aiReplyBoxWidthDp
            agentPrompt = defaults.agentPrompt
        },
    ) {
        saving = true
        onSave(fontSizeSp, replyWidthDp, agentPrompt) { saving = false }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("AI 配置", "AI configurations")) {
                Text(
                    tr(
                        "文字/图片模型配置已移到独立子页；在 AI 聊天页选择模型，点右上角齿轮也会回到本页。",
                        "Text/image model configurations now live in their own subpage; pick a model on the AI chat page, and its gear button returns here.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenConfigs),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr("AI 配置", "AI configurations"), fontWeight = FontWeight.Medium)
                            Text(
                                tr(
                                    "接口、模型、API Key 与工具能力",
                                    "Endpoints, models, API keys and tool capability",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
            }
        }
        item {
            SettingsSection(tr("AI 页面显示", "AI page display")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("字体大小", "Font size"))
                    Text(fontSizeSp.roundToInt().toString() + " sp", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = fontSizeSp,
                    onValueChange = { fontSizeSp = it.roundToInt().toFloat() },
                    valueRange = MIN_AI_PAGE_FONT_SIZE_SP..MAX_AI_PAGE_FONT_SIZE_SP,
                    steps = (MAX_AI_PAGE_FONT_SIZE_SP - MIN_AI_PAGE_FONT_SIZE_SP).toInt() - 1,
                )
                Text(
                    tr(
                        "作用于 AI 聊天页的消息气泡与输入框。",
                        "Applies to message bubbles and the input box on the AI chat page.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("回复框宽度", "Reply box width"))
                    Text(replyWidthDp.roundToInt().toString() + " dp", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = replyWidthDp,
                    onValueChange = { replyWidthDp = (it / 10f).roundToInt() * 10f },
                    valueRange = MIN_AI_REPLY_BOX_WIDTH_DP..MAX_AI_REPLY_BOX_WIDTH_DP,
                    steps = ((MAX_AI_REPLY_BOX_WIDTH_DP - MIN_AI_REPLY_BOX_WIDTH_DP) / 10f).toInt() - 1,
                )
                Text(
                    tr(
                        "限制消息气泡的最大宽度；手机窄屏会自动收窄。",
                        "Limits the maximum bubble width; narrow screens still shrink automatically.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection(tr("Agent 提示词", "Agent prompt")) {
                Text(
                    tr(
                        "作为风格与任务偏好附加在严格的内置规则之后，不能扩大权限；也可在“AI 配置 → 附加模型指令”按模型补充。",
                        "Appended after the strict built-in rules as style and task preferences; it cannot expand permissions. You can still add per-model instructions under AI configurations.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = agentPrompt,
                    onValueChange = { agentPrompt = it.take(20_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Agent 提示词", "Agent prompt")) },
                    minLines = 4,
                    supportingText = {
                        Text(
                            tr(
                                "最多 20000 个字符",
                                "Up to 20000 characters",
                            ),
                        )
                    },
                )
                TextButton(onClick = { agentPrompt = DEFAULT_AGENT_PROMPT }) {
                    Icon(Icons.Outlined.Restore, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("恢复 Agent 提示词", "Restore Agent prompt"))
                }
            }
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
                    Text(
                        when {
                            config.type == AiModelType.IMAGE -> tr("图片", "Image")
                            config.supportsToolCalling -> tr("Agent", "Agent")
                            else -> tr("文字", "Text")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
    var supportsTools by rememberSaveable(initial.id) { mutableStateOf(initial.supportsToolCalling) }
    var requestPreview by remember(initial.id) { mutableStateOf<String?>(null) }
    val changed = initial.copy(name = name, type = type, endpointUrl = endpoint, model = model,
        allowInsecureHttp = allowHttp, temperature = temperature, systemPrompt = systemPrompt,
        apiKey = apiKey, supportsToolCalling = supportsTools, enabled = true)
    val endpointUri = remember(endpoint) { runCatching { Uri.parse(endpoint.trim()) }.getOrNull() }
    val endpointValid = endpointUri?.host?.isNotBlank() == true && when (endpointUri.scheme?.lowercase()) {
        "https" -> true; "http" -> allowHttp; else -> false
    }
    val dirty = changed != initial.copy(enabled = true)
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = dirty,
        enabled = name.isNotBlank() && model.isNotBlank() && endpointValid,
        onReset = {
            val defaults = AiModelConfig(
                id = initial.id,
                name = "",
                type = initial.type,
                endpointUrl = "https://api.openai.com/v1/chat/completions",
                model = "",
                systemPrompt = "你是一个有帮助的助手。",
            )
            name = defaults.name
            type = defaults.type
            endpoint = defaults.endpointUrl
            model = defaults.model
            allowHttp = defaults.allowInsecureHttp
            temperature = defaults.temperature
            systemPrompt = defaults.systemPrompt
            apiKey = defaults.apiKey
            supportsTools = defaults.supportsToolCalling
            requestPreview = null
        },
    ) {
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
                label = { Text(tr("附加模型指令", "Additional model instructions")) }, minLines = 4,
                supportingText = {
                    Text(
                        tr(
                            "DeskCubby 的严格 Agent system prompt 始终优先；这里仅补充风格和任务偏好。",
                            "DeskCubby's strict Agent system prompt always takes precedence; use this only for style and task preferences.",
                        ),
                    )
                },
            )
            if (type == AiModelType.TEXT) Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("原生工具调用", "Native tool calling"))
                    Text(
                        tr(
                            "仅当 Provider 支持 OpenAI-compatible tools/tool_calls 时开启；关闭后该配置不能运行 Agent。",
                            "Enable only if the provider supports OpenAI-compatible tools/tool_calls. Disabled configurations cannot run Agent.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(supportsTools, { supportsTools = it })
            }
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
    onSave: (
        NavItemId,
        List<NavItemConfig>,
        Boolean,
        Boolean,
        MusicVisualizerStyle,
        MusicVisualizerFrequencyMode,
        Int,
        Int,
        (Boolean) -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var defaultPage by remember(settings.defaultPage) { mutableStateOf(settings.defaultPage) }
    var navItems by remember(settings.navItems) { mutableStateOf(settings.navItems.map { it.copy() }) }
    var showLabels by remember(settings.bottomNavShowLabels) { mutableStateOf(settings.bottomNavShowLabels) }
    var visualizerEnabled by remember(settings.musicVisualizerEnabled) {
        mutableStateOf(settings.musicVisualizerEnabled)
    }
    var visualizerStyle by remember(settings.musicVisualizerStyle) {
        mutableStateOf(settings.musicVisualizerStyle)
    }
    var visualizerFrequencyMode by remember(settings.musicVisualizerFrequencyMode) {
        mutableStateOf(settings.musicVisualizerFrequencyMode)
    }
    var visualizerMinFrequencyHz by remember(settings.musicVisualizerMinFrequencyHz) {
        mutableIntStateOf(settings.musicVisualizerMinFrequencyHz)
    }
    var visualizerMaxFrequencyHz by remember(settings.musicVisualizerMaxFrequencyHz) {
        mutableIntStateOf(settings.musicVisualizerMaxFrequencyHz)
    }
    var audioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var visualizerPermissionDenied by remember { mutableStateOf(false) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        audioPermissionGranted = granted
        visualizerEnabled = granted
        visualizerPermissionDenied = !granted
    }
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                audioPermissionGranted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val navCenters = remember { mutableStateMapOf<NavItemId, Float>() }
    var draggingNavId by remember { mutableStateOf<NavItemId?>(null) }
    var navDragDistancePx by remember { mutableStateOf(0f) }
    var navDragOriginY by remember { mutableStateOf<Float?>(null) }
    var navDragTargetIndex by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
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

    fun frequencySliderValue(frequencyHz: Int): Float {
        val minimum = 20.0
        val maximum = 20_000.0
        return ((ln(frequencyHz.coerceIn(20, 20_000).toDouble()) - ln(minimum)) /
            (ln(maximum) - ln(minimum))).toFloat().coerceIn(0f, 1f)
    }

    fun frequencyFromSlider(value: Float): Int {
        val minimum = 20.0
        val maximum = 20_000.0
        return exp(ln(minimum) + (ln(maximum) - ln(minimum)) * value.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(20, 20_000)
    }

    fun frequencyLabel(frequencyHz: Int): String = if (frequencyHz >= 1_000) {
        String.format(Locale.ROOT, "%.1f kHz", frequencyHz / 1_000f)
    } else {
        "$frequencyHz Hz"
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
            showLabels != settings.bottomNavShowLabels ||
            visualizerEnabled != settings.musicVisualizerEnabled ||
            visualizerStyle != settings.musicVisualizerStyle ||
            visualizerFrequencyMode != settings.musicVisualizerFrequencyMode ||
            visualizerMinFrequencyHz != settings.musicVisualizerMinFrequencyHz ||
            visualizerMaxFrequencyHz != settings.musicVisualizerMaxFrequencyHz,
        enabled = !saving,
        onReset = {
            val defaults = AppSettings()
            defaultPage = defaults.defaultPage
            navItems = defaults.navItems
            showLabels = defaults.bottomNavShowLabels
            visualizerEnabled = defaults.musicVisualizerEnabled
            visualizerStyle = defaults.musicVisualizerStyle
            visualizerFrequencyMode = defaults.musicVisualizerFrequencyMode
            visualizerMinFrequencyHz = defaults.musicVisualizerMinFrequencyHz
            visualizerMaxFrequencyHz = defaults.musicVisualizerMaxFrequencyHz
            visualizerPermissionDenied = false
            clearNavDrag()
        },
    ) {
        saving = true
        onSave(
            defaultPage,
            navItems,
            showLabels,
            visualizerEnabled,
            visualizerStyle,
            visualizerFrequencyMode,
            visualizerMinFrequencyHz,
            visualizerMaxFrequencyHz,
        ) { saving = false }
    }

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
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("音乐可视化", "Music visualization"))
                        Text(
                            tr(
                                "播放音乐时用实时频谱或波形让底栏跟随节奏；音频只在内存中绘制，不会保存。",
                                "Animate the bottom bar from live spectrum or waveform data while music plays. Audio is drawn in memory and never stored.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = visualizerEnabled && audioPermissionGranted,
                        onCheckedChange = { enabled ->
                            visualizerPermissionDenied = false
                            if (!enabled) {
                                visualizerEnabled = false
                            } else if (
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                audioPermissionGranted = true
                                visualizerEnabled = true
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    )
                }
                if (visualizerPermissionDenied || visualizerEnabled && !audioPermissionGranted) {
                    Text(
                        tr(
                            "需要音频权限才能读取系统播放的实时波形；拒绝后底栏不会捕获声音。",
                            "Audio permission is required for live system-playback data; no sound is captured after denial.",
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (visualizerEnabled && audioPermissionGranted) {
                    Text(tr("显示方式", "Visualization style"), fontWeight = FontWeight.SemiBold)
                    MusicVisualizerStyle.entries.forEach { style ->
                        Row(
                            Modifier.fillMaxWidth().clickable { visualizerStyle = style },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = visualizerStyle == style,
                                onClick = { visualizerStyle = style },
                            )
                            Text(
                                when (style) {
                                    MusicVisualizerStyle.BARS -> tr("频谱直方图", "Spectrum bars")
                                    MusicVisualizerStyle.WAVEFORM -> tr("实时波形", "Waveform")
                                    MusicVisualizerStyle.CURVE -> tr("频谱曲线", "Spectrum curve")
                                },
                            )
                        }
                    }
                    if (visualizerStyle != MusicVisualizerStyle.WAVEFORM) {
                        HorizontalDivider()
                        Text(tr("频率范围", "Frequency range"), fontWeight = FontWeight.SemiBold)
                        MusicVisualizerFrequencyMode.entries.forEach { mode ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    visualizerFrequencyMode = mode
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = visualizerFrequencyMode == mode,
                                    onClick = { visualizerFrequencyMode = mode },
                                )
                                Column {
                                    Text(
                                        when (mode) {
                                            MusicVisualizerFrequencyMode.ADAPTIVE ->
                                                tr("自适应频率", "Adaptive frequencies")
                                            MusicVisualizerFrequencyMode.MANUAL ->
                                                tr("手动范围", "Manual range")
                                        },
                                    )
                                    if (mode == MusicVisualizerFrequencyMode.ADAPTIVE) {
                                        Text(
                                            tr(
                                                "跟随当前音乐的有效频段并平滑调整，让能量铺满底栏。",
                                                "Smoothly follows the useful band in the current audio so energy fills the bar.",
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        if (visualizerFrequencyMode == MusicVisualizerFrequencyMode.MANUAL) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(frequencyLabel(visualizerMinFrequencyHz))
                                Text(
                                    frequencyLabel(visualizerMaxFrequencyHz),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            val frequencyRangeDescription = tr(
                                "音乐可视化频率范围",
                                "Music visualization frequency range",
                            )
                            val frequencyRangeState = tr(
                                "${frequencyLabel(visualizerMinFrequencyHz)} 到 ${frequencyLabel(visualizerMaxFrequencyHz)}",
                                "${frequencyLabel(visualizerMinFrequencyHz)} to ${frequencyLabel(visualizerMaxFrequencyHz)}",
                            )
                            RangeSlider(
                                value = frequencySliderValue(visualizerMinFrequencyHz)..
                                    frequencySliderValue(visualizerMaxFrequencyHz),
                                onValueChange = { range ->
                                    val minimum = frequencyFromSlider(range.start)
                                        .coerceAtMost(19_999)
                                    val maximum = frequencyFromSlider(range.endInclusive)
                                        .coerceAtLeast(minimum + 1)
                                        .coerceAtMost(20_000)
                                    visualizerMinFrequencyHz = minimum
                                    visualizerMaxFrequencyHz = maximum
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier.semantics {
                                    contentDescription = frequencyRangeDescription
                                    stateDescription = frequencyRangeState
                                },
                            )
                        }
                    }
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
                    tr(
                        "在这里选择底栏项目，并调整底栏顺序、名称和图标。导航页的收纳与排序请到“导航页设置”调整。",
                        "Choose bottom-bar items here and adjust their order, labels and icons. Manage collection and order separately in Navigation page settings.",
                    ),
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
                                position = index + 1,
                                total = navItems.size,
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
                                onMoveUp = if (index > 0) {
                                    { moveNavItem(index, index - 1) }
                                } else {
                                    null
                                },
                                onMoveDown = if (index < navItems.lastIndex) {
                                    { moveNavItem(index, index + 1) }
                                } else {
                                    null
                                },
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
private fun MorePageSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (Boolean, Int, List<NavItemConfig>, (Boolean) -> Unit) -> Unit,
) {
    var showDescriptions by remember(settings.morePageShowDescriptions) {
        mutableStateOf(settings.morePageShowDescriptions)
    }
    var columns by rememberSaveable(settings.morePageColumns) {
        mutableIntStateOf(settings.morePageColumns)
    }
    var navItems by remember(settings.navItems) {
        mutableStateOf(settings.navItems.map { it.copy() })
    }
    var saving by remember { mutableStateOf(false) }
    var colorTarget by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val language = LocalAppLanguage.current
    val editableItems = navItems.withIndex().filter { (_, item) ->
        item.id != NavItemId.HOME &&
            item.id != NavItemId.MORE &&
            item.id != NavItemId.SETTINGS
    }

    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = showDescriptions != settings.morePageShowDescriptions ||
            columns != settings.morePageColumns ||
            navItems != settings.navItems,
        enabled = !saving,
        onReset = {
            val defaults = AppSettings()
            showDescriptions = defaults.morePageShowDescriptions
            columns = defaults.morePageColumns
            navItems = defaults.navItems
        },
    ) {
        saving = true
        onSave(showDescriptions, columns, navItems) { saving = false }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("页面布局", "Layout")) {
                Text(
                    tr(
                        "选择导航页模块按一列、两列或三列显示。",
                        "Choose whether navigation modules display in one, two, or three columns.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    (MIN_MORE_PAGE_COLUMNS..MAX_MORE_PAGE_COLUMNS).forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = columns == option,
                            onClick = { columns = option },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                MAX_MORE_PAGE_COLUMNS - MIN_MORE_PAGE_COLUMNS + 1,
                            ),
                        ) {
                            Text(
                                when (option) {
                                    1 -> tr("一列", "1 column")
                                    2 -> tr("两列", "2 columns")
                                    else -> tr("三列", "3 columns")
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("描述显示", "Descriptions")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示页面描述", "Show page descriptions"))
                        Text(
                            tr(
                                "关闭后导航卡片只显示图标和名称。",
                                "When off, navigation cards show only their icon and name.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showDescriptions,
                        onCheckedChange = { showDescriptions = it },
                    )
                }
            }
        }
        item {
            SettingsSection(tr("导航页内容", "Navigation page content")) {
                Text(
                    tr(
                        "可按列数连续排列；单独设置每个模块的名称、按钮底色与整体底色，并修改描述。",
                        "Modules flow by the selected column count; set each module's name, button background, card background, and description individually.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                editableItems.forEachIndexed { position, indexed ->
                    val index = indexed.index
                    val item = indexed.value
                    if (position > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(iconFor(item.iconKey), contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(localizedNavLabel(item), modifier = Modifier.weight(1f))
                        Switch(
                            checked = item.showInMore,
                            onCheckedChange = { checked ->
                                navItems = navItems.toMutableList().apply {
                                    set(index, item.copy(showInMore = checked))
                                }
                            },
                        )
                    }
                    OutlinedTextField(
                        value = if (
                            language == AppLanguage.ENGLISH &&
                            item.label == item.id.defaultLabel
                        ) {
                            item.id.englishLabel
                        } else {
                            item.label
                        },
                        onValueChange = { value ->
                            navItems = navItems.toMutableList().apply {
                                set(index, item.copy(label = value.take(32)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("模块名称", "Module name")) },
                        singleLine = true,
                        supportingText = {
                            Text(tr("最多 32 个字符", "Up to 32 characters"))
                        },
                    )
                    MoreModuleColorRow(
                        label = tr("按钮底色", "Button background"),
                        colorArgb = item.moreButtonColorArgb,
                        onPick = { colorTarget = index to "button" },
                        onClear = {
                            navItems = navItems.toMutableList().apply {
                                set(index, item.copy(moreButtonColorArgb = null))
                            }
                        },
                    )
                    MoreModuleColorRow(
                        label = tr("模块整体底色", "Card background"),
                        colorArgb = item.moreCardColorArgb,
                        onPick = { colorTarget = index to "card" },
                        onClear = {
                            navItems = navItems.toMutableList().apply {
                                set(index, item.copy(moreCardColorArgb = null))
                            }
                        },
                    )
                    OutlinedTextField(
                        value = if (
                            language == AppLanguage.ENGLISH &&
                            item.moreDescription == item.id.defaultDescription
                        ) {
                            item.id.englishDescription
                        } else {
                            item.moreDescription
                        },
                        onValueChange = { value ->
                            navItems = navItems.toMutableList().apply {
                                set(index, item.copy(moreDescription = value))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("描述", "Description")) },
                        minLines = 1,
                        maxLines = 3,
                        supportingText = {
                            Text(
                                tr(
                                    "最多 160 个字符",
                                    "Up to 160 characters",
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
    colorTarget?.let { (index, kind) ->
        val item = navItems.getOrNull(index)
        if (item == null) {
            colorTarget = null
        } else {
            val current = if (kind == "button") {
                item.moreButtonColorArgb
            } else {
                item.moreCardColorArgb
            }
            ColorPickerDialog(
                initialColorArgb = current ?: MaterialTheme.colorScheme.primaryContainer.toArgb(),
                title = if (kind == "button") {
                    tr("按钮底色", "Button background")
                } else {
                    tr("模块整体底色", "Card background")
                },
                onDismiss = { colorTarget = null },
                onConfirm = { picked ->
                    navItems = navItems.toMutableList().apply {
                        set(
                            index,
                            if (kind == "button") {
                                item.copy(moreButtonColorArgb = picked)
                            } else {
                                item.copy(moreCardColorArgb = picked)
                            },
                        )
                    }
                    colorTarget = null
                },
            )
        }
    }
}

@Composable
private fun MoreModuleColorRow(
    label: String,
    colorArgb: Int?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (colorArgb != null) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(colorArgb))
                    .clickable(onClick = onPick),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClear) {
                Text(tr("默认", "Default"))
            }
        } else {
            TextButton(onClick = onPick) {
                Text(tr("自定义颜色", "Custom color"))
            }
        }
    }
}

@Composable
private fun DeviceTrackingSettingsPage(
    title: String,
    explanation: String,
    enabled: Boolean,
    contentPadding: PaddingValues,
    saveCoordinator: SettingsSaveCoordinator,
    onSave: (Boolean) -> Unit,
) {
    var draftEnabled by remember(enabled) { mutableStateOf(enabled) }
    RegisterSettingsSave(
        coordinator = saveCoordinator,
        dirty = draftEnabled != enabled,
        onReset = { draftEnabled = false },
    ) { onSave(draftEnabled) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(title) {
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (draftEnabled) tr("统计已开启", "Tracking enabled")
                        else tr("统计已关闭", "Tracking disabled"),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = draftEnabled,
                        onCheckedChange = { draftEnabled = it },
                    )
                }
                Text(
                    tr(
                        "开启后仍需在系统授权页面授予相应访问权限；拒绝或撤销权限不会写入零值，也不会把未完成日期标记为已统计。",
                        "You must still grant the corresponding system access. Refusing or revoking access never writes a fake zero or finalizes an incomplete day.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AboutSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    var showThirdPartyLicenses by remember { mutableStateOf(false) }
    var thirdPartyLicenses by remember { mutableStateOf<String?>(null) }
    val checking by viewModel.updateCheckInProgress.collectAsStateWithLifecycle()
    val result by viewModel.updateCheckResult.collectAsStateWithLifecycle()
    val downloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* Permission is re-checked after ON_RESUME below. */ }
    LaunchedEffect(lifecycleOwner, viewModel, installLauncher) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.resumeUpdateInstallAfterPermission()
            viewModel.updateInstallActions.collect { intent ->
                try {
                    installLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.reportUpdateActionUnavailable(intent)
                } catch (_: SecurityException) {
                    viewModel.reportUpdateActionUnavailable(intent)
                }
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsSection(tr("应用信息", "App info")) {
                Text(
                    if (settings.useChineseLauncherName) "桌洞" else "Desk Cubby",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    tr("版本 ", "Version ") + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { openUrl(context, GITHUB_URL) }) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("GitHub 仓库", "GitHub repository"))
                }
                TextButton(onClick = { openUrl(context, TUTORIAL_URL) }) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("应用教学", "App tutorial"))
                }
                TextButton(
                    onClick = {
                        thirdPartyLicenses = readPdfiumNotices(context)
                        showThirdPartyLicenses = true
                    },
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("第三方许可", "Third-party licenses"))
                }
            }
        }
        item {
            SettingsSection(tr("页面教学", "Page tutorials")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("软件教学模式", "Tutorial mode"))
                        Text(
                            tr(
                                "默认开启。第一次进入每个页面时显示一次蒙版说明；确认记录只保存在当前设备。",
                                "On by default. A walkthrough overlay appears once the first time you open each page; confirmations stay on this device.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.tutorialModeEnabled,
                        onCheckedChange = viewModel::setTutorialModeEnabled,
                    )
                }
                OutlinedButton(
                    onClick = viewModel::resetTutorialPages,
                    enabled = settings.tutorialAcknowledgedPages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Restore, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("重新显示全部页面教学", "Show all page tutorials again"))
                }
            }
        }
        item {
            SettingsSection(tr("检查更新", "Check for updates")) {
                Text(
                    tr(
                        "从 GitHub Release 获取最新版本信息。",
                        "Fetches the latest release information from GitHub.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = viewModel::checkForUpdate,
                    enabled = !checking && !downloadState.isUpdateOperationInProgress(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (checking) tr("正在检查…", "Checking…") else tr("检查更新", "Check for updates"))
                }
                when (val current = result) {
                    null -> Unit
                    is UpdateCheckResult.UpToDate -> Text(
                        tr(
                            "已是最新版本（${current.currentVersion}）。",
                            "You are on the latest version (${current.currentVersion}).",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is UpdateCheckResult.Failed -> Text(
                        tr(current.message, current.messageEnglish),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is UpdateCheckResult.UpdateAvailable -> Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            tr(
                                "发现新版本 ${current.latestVersion}（当前 ${current.currentVersion}）",
                                "New version ${current.latestVersion} available (current ${current.currentVersion})",
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (current.releaseName.isNotBlank()) {
                            Text(current.releaseName, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (current.notes.isNotBlank()) {
                            Text(
                                current.notes.take(2000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val updatePackage = current.updatePackage
                        if (updatePackage == null) {
                            Text(
                                tr(
                                    "此版本没有可验证的 DeskCubby APK，请前往发布页面查看。",
                                    "This release has no verifiable DeskCubby APK. Open the release page for details.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(
                                onClick = { openUrl(context, current.htmlUrl) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(tr("打开发布页面", "Open release page")) }
                        } else {
                            when (val state = downloadState) {
                                is UpdateDownloadState.Downloading -> {
                                    val progress = if (state.totalBytes > 0L) {
                                        (state.downloadedBytes.toFloat() / state.totalBytes)
                                            .coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        tr(
                                            "正在下载 ${formatUpdateSize(state.downloadedBytes)} / ${formatUpdateSize(state.totalBytes)}",
                                            "Downloading ${formatUpdateSize(state.downloadedBytes)} / ${formatUpdateSize(state.totalBytes)}",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                is UpdateDownloadState.Preparing -> Text(
                                    tr(
                                        "正在验证安装包并准备系统安装界面…",
                                        "Verifying the APK and preparing the system installer…",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                is UpdateDownloadState.AwaitingInstallPermission -> Text(
                                    tr(
                                        "安装包已验证。请在系统页面允许此来源安装应用，返回后会继续安装。",
                                        "The APK is verified. Allow installs from this source in system settings, then return to continue.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                is UpdateDownloadState.ReadyToInstall -> Text(
                                    tr(
                                        "安装包已下载并验证；若安装界面已关闭，可再次打开。",
                                        "The APK is downloaded and verified. You can reopen the installer if it was closed.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                is UpdateDownloadState.Failed -> Text(
                                    updateDownloadFailureMessage(state.reason),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                UpdateDownloadState.Idle -> Unit
                            }
                            Button(
                                onClick = { viewModel.downloadAndInstallUpdate(current) },
                                enabled = !downloadState.isUpdateOperationInProgress(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    when (downloadState) {
                                        is UpdateDownloadState.Preparing ->
                                            tr("正在准备安装…", "Preparing installation…")
                                        is UpdateDownloadState.AwaitingInstallPermission ->
                                            tr("继续安装", "Continue installation")
                                        is UpdateDownloadState.ReadyToInstall ->
                                            tr("重新打开安装界面", "Reopen installer")
                                        is UpdateDownloadState.Failed ->
                                            tr("重试下载并安装", "Retry download and install")
                                        else -> tr("下载并安装", "Download and install")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("应用显示名称", "App display name")) {
                Text(
                    tr(
                        "更改桌面图标下显示的应用名称。部分启动器需要片刻才会刷新。",
                        "Changes the launcher label. Some launchers take a moment to refresh.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(false to "Desk Cubby", true to "桌洞").forEach { (useChinese, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setUseChineseLauncherName(useChinese) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.useChineseLauncherName == useChinese,
                            onClick = { viewModel.setUseChineseLauncherName(useChinese) },
                        )
                        Text(label)
                    }
                }
            }
        }
        item {
            SettingsSection(tr("软件图标", "App icon")) {
                Text(
                    tr(
                        "选择桌面启动图标。部分启动器可能需要片刻刷新缓存。",
                        "Choose the launcher icon. Some launchers may take a moment to refresh their cache.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LauncherIcon.entries.forEach { icon ->
                    val selected = settings.launcherIcon == icon
                    val label = when (icon) {
                        LauncherIcon.CURRENT -> tr("经典图标", "Classic icon")
                        LauncherIcon.MAGIC_BOOK -> tr("魔法书图标", "Magic book icon")
                        LauncherIcon.DESK_CUBBY -> tr("桌洞图标", "Desk cubby icon")
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setLauncherIcon(icon) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = { viewModel.setLauncherIcon(icon) },
                        )
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(
                                    when (icon) {
                                        LauncherIcon.CURRENT -> Color.Black
                                        LauncherIcon.MAGIC_BOOK -> Color(0xFFFFFDF8)
                                        LauncherIcon.DESK_CUBBY -> Color.White
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(
                                    when (icon) {
                                        LauncherIcon.CURRENT -> R.drawable.ic_launcher_art
                                        LauncherIcon.MAGIC_BOOK ->
                                            R.drawable.ic_launcher_book_foreground
                                        LauncherIcon.DESK_CUBBY ->
                                            R.drawable.ic_launcher_cubby_foreground
                                    },
                                ),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(
                                        when (icon) {
                                            LauncherIcon.CURRENT -> 8.dp
                                            LauncherIcon.MAGIC_BOOK -> 2.dp
                                            LauncherIcon.DESK_CUBBY -> 5.dp
                                        },
                                    ),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
            }
        }
    }
    if (showThirdPartyLicenses) {
        AlertDialog(
            onDismissRequest = { showThirdPartyLicenses = false },
            title = { Text(tr("第三方许可", "Third-party licenses")) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            thirdPartyLicenses?.takeIf(String::isNotBlank) ?: tr(
                                "无法读取第三方许可文件。",
                                "The third-party license file could not be read.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThirdPartyLicenses = false }) {
                    Text(tr("关闭", "Close"))
                }
            },
        )
    }
}

private fun readPdfiumNotices(context: Context): String? = runCatching {
    context.assets.open(PDFIUM_NOTICES_ASSET).use { input ->
        val buffer = ByteArray(PDFIUM_NOTICES_READ_BUFFER_BYTES)
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            require(output.size() + count <= MAX_PDFIUM_NOTICES_BYTES) {
                "PDFium notices exceed the supported size"
            }
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}.getOrNull()

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
    position: Int,
    total: Int,
    onChange: (NavItemConfig) -> Unit,
    onCenterChanged: (Float) -> Unit,
    onDragStarted: () -> Unit,
    onDragChanged: (Float) -> Unit,
    onDragCancelled: () -> Unit,
    onMoveUp: (() -> Boolean)?,
    onMoveDown: (() -> Boolean)?,
    onMove: (Float) -> Unit,
) {
    var iconMenu by remember { mutableStateOf(false) }
    val icons = listOf(
        "home", "desk", "book", "poetry", "language", "bolt", "settings", "calendar",
        "event", "rss", "ai", "apps", "star", "write", "sparkle", "day",
        "lock", "reader", "game", "usage", "steps", "statistics", "widgets",
    )
    val visibilityDescription = tr(
        "${item.id.defaultLabel}是否显示在底栏",
        "Show ${item.id.englishLabel} in bottom bar",
    )
    val orderDescription = tr("第 $position 项，共 $total 项", "$position of $total")

    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { onCenterChanged(it.boundsInRoot().center.y) }
            .padding(vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            FourDotDragHandle(
                modifier = Modifier.semantics {
                    stateDescription = orderDescription
                },
                translateSelf = false,
                onDragStarted = onDragStarted,
                onDragChanged = onDragChanged,
                onDragCancelled = onDragCancelled,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onDragFinished = onMove,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                tr("显示在底栏", "Show in bottom bar"),
                style = MaterialTheme.typography.labelMedium,
            )
            Switch(
                checked = item.visible || item.id == NavItemId.SETTINGS,
                enabled = item.id != NavItemId.SETTINGS,
                onCheckedChange = { onChange(item.copy(visible = it)) },
                modifier = Modifier.semantics {
                    contentDescription = visibilityDescription
                },
            )
        }
    }
}

@Composable
private fun settingsPageTutorialTarget(page: SettingsPage): PageTutorialTarget {
    val description = when (page) {
        SettingsPage.MAIN -> tr(
            "从搜索或分组入口进入各项设置。可编辑子页统一使用右上角保存。",
            "Use search or grouped entries to open settings. Editable subpages use Save in the top-right.",
        )
        SettingsPage.APPEARANCE -> tr(
            "切换语言、主题、字号与全局背景图片，并调整背景可见度和模糊。",
            "Change language, theme, type size, and the global background image, including visibility and blur.",
        )
        SettingsPage.SUBPAGES -> tr(
            "这里集中管理主页、日记、阅读相关入口和各功能页面的行为。",
            "Manage Home, diary, reading-related entries, and the behavior of feature pages here.",
        )
        SettingsPage.HOME -> tr(
            "选择主页模块、标题、顺序、边框与饮食按钮显示方式。",
            "Choose Home modules, titles, order, borders, and meal-button appearance.",
        )
        SettingsPage.HOME_GREETING -> tr(
            "编辑用户名和中英文问候模板；{name} 会替换为用户名。",
            "Edit the user name and bilingual greetings; {name} is replaced with the user name.",
        )
        SettingsPage.BACKUP -> tr(
            "手动导出或恢复 DeskCubby 结构化数据，并查看应用数据占用。",
            "Manually export or restore structured DeskCubby data and inspect app storage use.",
        )
        SettingsPage.SYNC -> tr(
            "管理 WebDAV/S3 服务、同步内容与方向；结构化数据走记录同步，真实文件走文件同步。",
            "Manage WebDAV/S3 services, content, and direction. Structured data uses record sync and real files use file sync.",
        )
        SettingsPage.SYNC_DETAIL -> tr(
            "填写单个云服务的非秘密元数据与凭据，保存前会执行边界校验。",
            "Enter one cloud service's metadata and credentials; validation runs before saving.",
        )
        SettingsPage.DIARY -> tr(
            "配置 SAF 日记/媒体目录、命名、图片、吃历和热量估算。",
            "Configure SAF diary/media folders, naming, images, the meal calendar, and energy estimation.",
        )
        SettingsPage.BLOG -> tr(
            "设置应用内浏览器主页、明暗主题和电脑网页模式。",
            "Set the in-app browser home page, theme, and desktop-site mode.",
        )
        SettingsPage.THOUGHT -> tr(
            "设置小巧思重开位置、显示密度、重点颜色与输入框高度。",
            "Set Thoughts reopen behavior, density, highlight color, and editor height.",
        )
        SettingsPage.VAULT -> tr(
            "调整收藏夹条目的最小高度；加密密码仍在收藏夹页面管理。",
            "Adjust Vault entry height; encryption passwords remain managed on the Vault page.",
        )
        SettingsPage.POETRY -> tr(
            "导入字体并调整诗词字号、行距、对齐、出处与七言换行。",
            "Import a font and adjust poetry size, spacing, alignment, source, and seven-character wrapping.",
        )
        SettingsPage.RSS -> tr(
            "调整每个订阅的文章上限和摘要显示。订阅地址在 RSS 页面维护。",
            "Adjust the item limit and summaries per feed. Feed URLs are managed on the RSS page.",
        )
        SettingsPage.AI -> tr(
            "调整 AI 页面字体、回复框宽度与 Agent 提示词；模型配置在独立的“AI 配置”子页管理。",
            "Adjust AI page font size, reply box width, and the Agent prompt; model configurations live in the separate AI configurations subpage.",
        )
        SettingsPage.AI_CONFIGS -> tr(
            "管理文字/图片模型配置；点按编辑，长按可复制或删除。",
            "Manage text/image model configurations; tap to edit, or long-press to copy or delete.",
        )
        SettingsPage.AI_DETAIL -> tr(
            "编辑兼容接口、模型、提示词和完整 API Key；请求预览不会包含密钥。",
            "Edit the compatible endpoint, model, prompt, and full API key; request previews omit the key.",
        )
        SettingsPage.NAVIGATION -> tr(
            "选择底栏页面、默认页、名称图标和音乐可视化；拖动四点手柄排序。",
            "Choose bottom-bar pages, default page, names, icons, and music visualization; drag four-dot handles to reorder.",
        )
        SettingsPage.MORE_PAGE -> tr(
            "选择收纳到“导航”页的入口、描述和卡片顺序。",
            "Choose entries, descriptions, and card order for the Navigation page.",
        )
        SettingsPage.USAGE -> tr(
            "启用后仍需系统授予使用情况访问权限；关闭不会删除已有历史。",
            "System usage access is still required after enabling; disabling does not delete history.",
        )
        SettingsPage.STEPS -> tr(
            "启用后从 Health Connect 只读步数、距离与活动热量。",
            "After enabling, read steps, distance, and active calories from Health Connect only.",
        )
        SettingsPage.ABOUT -> tr(
            "查看版本、教学模式、更新、桌面名称与图标。页面教学可在这里关闭或重置。",
            "View version, tutorials, updates, launcher name, and icon. Page tutorials can be disabled or reset here.",
        )
    }
    return PageTutorialTarget(
        pageId = "settings/${page.name.lowercase(Locale.ROOT)}",
        title = pageTitle(page),
        description = description,
        hints = if (page != SettingsPage.MAIN && page !in setOf(
                SettingsPage.SUBPAGES,
                SettingsPage.BACKUP,
                SettingsPage.SYNC,
                SettingsPage.AI,
                SettingsPage.ABOUT,
            )
        ) {
            listOf(
                tr(
                    "“恢复本页默认值”只修改草稿，仍需点右上角保存。",
                    "Reset page defaults changes only the draft; use Save in the top-right to commit it.",
                ),
            )
        } else {
            emptyList()
        },
    )
}

@Composable
private fun pageTitle(page: SettingsPage): String = when (page) {
    SettingsPage.MAIN -> tr("设置", "Settings")
    SettingsPage.APPEARANCE -> tr("外观与语言", "Appearance & language")
    SettingsPage.SUBPAGES -> tr("子页面设置", "Subpage settings")
    SettingsPage.HOME -> tr("主页", "Home")
    SettingsPage.HOME_GREETING -> tr("主页问候", "Home greeting")
    SettingsPage.BACKUP -> tr("应用数据", "App data")
    SettingsPage.SYNC -> tr("云端同步", "Cloud sync")
    SettingsPage.SYNC_DETAIL -> tr("同步配置", "Sync configuration")
    SettingsPage.DIARY -> tr("日记与媒体", "Diary & media")
    SettingsPage.BLOG -> tr("浏览器", "Browser")
    SettingsPage.THOUGHT -> tr("小巧思", "Thoughts")
    SettingsPage.VAULT -> tr("收藏夹", "Vault")
    SettingsPage.POETRY -> tr("诗词本", "Poetry book")
    SettingsPage.RSS -> tr("RSS 订阅", "RSS")
    SettingsPage.AI -> tr("AI 设置", "AI settings")
    SettingsPage.AI_CONFIGS -> tr("AI 配置", "AI configurations")
    SettingsPage.AI_DETAIL -> tr("AI 配置详情", "AI configuration")
    SettingsPage.NAVIGATION -> tr("底部导航", "Bottom navigation")
    SettingsPage.MORE_PAGE -> tr("导航页", "Navigation page")
    SettingsPage.USAGE -> tr("手机使用时间", "Screen time")
    SettingsPage.STEPS -> tr("健康", "Health")
    SettingsPage.ABOUT -> tr("关于", "About")
}

private fun parentSettingsPage(page: SettingsPage): SettingsPage = when (page) {
    SettingsPage.HOME_GREETING -> SettingsPage.HOME

    SettingsPage.HOME,
    SettingsPage.DIARY,
    SettingsPage.BLOG,
    SettingsPage.THOUGHT,
    SettingsPage.VAULT,
    SettingsPage.POETRY,
    SettingsPage.RSS,
    SettingsPage.AI,
    SettingsPage.MORE_PAGE,
    SettingsPage.USAGE,
    SettingsPage.STEPS,
    -> SettingsPage.SUBPAGES

    SettingsPage.AI_CONFIGS -> SettingsPage.AI
    SettingsPage.AI_DETAIL -> SettingsPage.AI_CONFIGS
    SettingsPage.SYNC_DETAIL -> SettingsPage.SYNC
    SettingsPage.SYNC -> SettingsPage.BACKUP

    SettingsPage.MAIN,
    SettingsPage.APPEARANCE,
    SettingsPage.SUBPAGES,
    SettingsPage.BACKUP,
    SettingsPage.NAVIGATION,
    SettingsPage.ABOUT,
    -> SettingsPage.MAIN
}

internal fun defaultBackupFileName(clock: Clock): String =
    "DC-${LocalDate.now(clock)}.json"

private fun formatBackupTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

@Composable
private fun dataUsageLabel(key: String): String = when (key) {
    "code" -> tr("应用安装包", "Installed app")
    "database" -> tr("结构化数据库", "Structured database")
    "settings" -> tr("设置与偏好", "Settings and preferences")
    "reader" -> tr("书架、阅读设置与进度", "Library, reading settings, and progress")
    "engagement" -> tr("阅读/小游戏时间 JSON", "Reading/game time JSON")
    "statistics" -> tr("旧统计迁移文件", "Legacy statistics migration files")
    "other_files" -> tr("备份、云状态与其他文件", "Backups, cloud state, and other files")
    "cache" -> tr("缓存", "Cache")
    "external_app" -> tr("应用专属外部文件", "App-specific external files")
    "diary_tree" -> tr("日记目录", "Diary folder")
    "media_tree" -> tr("媒体目录", "Media folder")
    else -> key
}

private fun formatStorageBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L).toDouble()
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = safe
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, units[index])
    }
}

@Composable
private fun updateDownloadFailureMessage(reason: UpdateDownloadFailure): String = when (reason) {
    UpdateDownloadFailure.NO_TRUSTED_APK -> tr(
        "此版本没有可验证的 APK。",
        "This release has no verifiable APK.",
    )
    UpdateDownloadFailure.INVALID_DOWNLOAD_URL -> tr(
        "下载地址或跳转目标不可信，已停止下载。",
        "The download URL or redirect was not trusted, so the download was stopped.",
    )
    UpdateDownloadFailure.HTTP_ERROR -> tr(
        "更新服务器未能提供安装包，请稍后重试。",
        "The update server did not provide the APK. Please try again later.",
    )
    UpdateDownloadFailure.DOWNLOAD_TOO_LARGE -> tr(
        "安装包超过 256 MiB 安全上限。",
        "The APK exceeds the 256 MiB safety limit.",
    )
    UpdateDownloadFailure.SIZE_MISMATCH -> tr(
        "安装包大小与发布信息不一致，文件已删除。",
        "The APK size did not match the release metadata, so it was deleted.",
    )
    UpdateDownloadFailure.INVALID_APK -> tr(
        "下载的文件不是有效的 Android 安装包。",
        "The downloaded file is not a valid Android package.",
    )
    UpdateDownloadFailure.WRONG_APPLICATION -> tr(
        "安装包不属于 DeskCubby，文件已删除。",
        "The package is not DeskCubby, so it was deleted.",
    )
    UpdateDownloadFailure.VERSION_MISMATCH -> tr(
        "安装包版本与发布版本不一致，文件已删除。",
        "The package version does not match the release version, so it was deleted.",
    )
    UpdateDownloadFailure.NOT_NEWER -> tr(
        "安装包的内部版本并不高于当前版本。",
        "The package's internal version is not newer than the installed version.",
    )
    UpdateDownloadFailure.SIGNATURE_MISMATCH -> tr(
        "安装包签名与当前应用不一致，文件已删除。",
        "The package signature does not match this app, so it was deleted.",
    )
    UpdateDownloadFailure.TIMEOUT -> tr(
        "下载安装包超时，请稍后重试。",
        "The APK download timed out. Please try again later.",
    )
    UpdateDownloadFailure.TLS_ERROR -> tr(
        "下载时 HTTPS 证书验证失败。",
        "HTTPS certificate verification failed while downloading.",
    )
    UpdateDownloadFailure.NETWORK_ERROR -> tr(
        "下载安装包失败，请检查网络连接。",
        "The APK download failed. Check your network connection.",
    )
    UpdateDownloadFailure.STORAGE_ERROR -> tr(
        "无法把安装包安全写入应用缓存。",
        "The APK could not be safely written to the app cache.",
    )
    UpdateDownloadFailure.INSTALL_PERMISSION_SETTINGS_UNAVAILABLE -> tr(
        "无法打开“允许此来源安装应用”系统设置。",
        "The system settings for allowing installs from this source could not be opened.",
    )
    UpdateDownloadFailure.INSTALLER_UNAVAILABLE -> tr(
        "无法打开系统安装程序。",
        "The Android package installer could not be opened.",
    )
}

private fun formatUpdateSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L ->
        String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L ->
        String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

private const val JSON_PREVIEW_CHUNK_CHARS = 2_048

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
private const val TUTORIAL_URL = "https://github.com/vexpaer/DeskCubby/blob/main/TUTORIAL.md"
private const val PDFIUM_NOTICES_ASSET = "pdfium_NOTICES.txt"
private const val MAX_PDFIUM_NOTICES_BYTES = 256 * 1024
private const val PDFIUM_NOTICES_READ_BUFFER_BYTES = 8 * 1024

@Composable
private fun localizedNavLabel(item: NavItemConfig): String =
    if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == AppLanguage.ENGLISH && item.label.isDefaultLabelFor(item.id)) {
        item.id.englishLabel
    } else {
        item.label
    }

private fun String.isDefaultLabelFor(id: NavItemId): Boolean =
    this == id.defaultLabel || (id == NavItemId.BLOG && this == "博客") || (id == NavItemId.THOUGHT && this == "闪思")


private fun appLanguageLabel(item: AppLanguage): String = when (item) {
    AppLanguage.CHINESE -> "简体中文"
    AppLanguage.TRADITIONAL_CHINESE -> "繁體中文"
    AppLanguage.ENGLISH -> "English"
    AppLanguage.KOREAN -> "한국어"
    AppLanguage.JAPANESE -> "日本語"
}
