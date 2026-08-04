@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.deskcubby.app.ui

import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.deskcubby.app.syncLauncherAlias
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.normalizeMorePageOrder
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.ui.blog.BlogScreen
import com.deskcubby.app.ui.blog.BlogViewModel
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.components.AppBackground
import com.deskcubby.app.ui.components.PageTutorialOverlay
import com.deskcubby.app.ui.components.PageTutorialTarget
import com.deskcubby.app.ui.components.MusicVisualizerLayer
import com.deskcubby.app.ui.diary.DiaryEditorScreen
import com.deskcubby.app.ui.diary.DiaryListScreen
import com.deskcubby.app.ui.diary.DiaryViewModel
import com.deskcubby.app.ui.diary.MealCalendarScreen
import com.deskcubby.app.ui.diary.CalorieEstimationProgressScreen
import com.deskcubby.app.ui.diary.filter.MealPhotoFilterSettingsScreen
import com.deskcubby.app.ui.daily.DailyRecordScreen
import com.deskcubby.app.ui.daily.DailyRecordViewModel
import com.deskcubby.app.ui.date.DateRecordScreen
import com.deskcubby.app.ui.date.DateRecordViewModel
import com.deskcubby.app.ui.home.HomeScreen
import com.deskcubby.app.ui.home.HomeViewModel
import com.deskcubby.app.ui.more.MoreHubScreen
import com.deskcubby.app.ui.poetry.PoetryBookScreen
import com.deskcubby.app.ui.poetry.PoetryBookViewModel
import com.deskcubby.app.ui.reader.ReaderScreen
import com.deskcubby.app.ui.reader.ReaderViewModel
import com.deskcubby.app.ui.settings.SettingsScreen
import com.deskcubby.app.ui.settings.SettingsStartPage
import com.deskcubby.app.ui.settings.SettingsViewModel
import com.deskcubby.app.ui.rss.RssScreen
import com.deskcubby.app.ui.rss.RssViewModel
import com.deskcubby.app.ui.steps.StepStatisticsScreen
import com.deskcubby.app.ui.steps.StepStatisticsViewModel
import com.deskcubby.app.ui.statshub.StatisticsHubScreen
import com.deskcubby.app.ui.statshub.StatisticsHubViewModel
import com.deskcubby.app.ui.usage.UsageStatisticsScreen
import com.deskcubby.app.ui.usage.UsageStatisticsViewModel
import com.deskcubby.app.ui.ai.AiChatScreen
import com.deskcubby.app.ui.ai.AiChatViewModel
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.theme.tr
import com.deskcubby.app.ui.games.GamesScreen
import com.deskcubby.app.ui.games.GamesViewModel
import com.deskcubby.app.ui.thought.ThoughtScreen
import com.deskcubby.app.ui.thought.ThoughtTrashScreen
import com.deskcubby.app.ui.thought.ThoughtViewModel
import com.deskcubby.app.ui.vault.VaultScreen
import com.deskcubby.app.ui.vault.VaultViewModel
import com.deskcubby.app.ui.widgets.DesktopWidgetsScreen
import com.deskcubby.app.ui.widgets.DesktopWidgetsViewModel
import com.deskcubby.app.data.statistics.StepHealthConnectAccess

object Routes {
    const val EDITOR = "diary_editor"
    const val MEAL_CALENDAR = "meal_calendar"
    const val CALORIE_ESTIMATION_PROGRESS = "meal_calendar/calorie_progress"
    const val MEAL_FILTER_SETTINGS = "meal_filter_settings"
    const val THOUGHT_TRASH = "thought_trash"
    const val DAILY_RECORDS = "daily_records"
    const val DAILY_RECORDS_TODAY = "daily_records/today"
    const val NAVIGATION_SETTINGS = "settings/navigation"
    const val MORE_PAGE_SETTINGS = "settings/more-page"
    const val USAGE_SETTINGS = "settings/usage-statistics"
    const val STEPS_SETTINGS = "settings/step-statistics"
    const val STATISTICS_USAGE = "statistics/screen-time"
    const val STATISTICS_HEALTH = "statistics/health"
    const val AI_SETTINGS = "settings/ai"
    const val POETRY_SETTINGS = "settings/poetry"
}

@Composable
fun DeskCubbyRoot(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
    thoughtViewModel: ThoughtViewModel = hiltViewModel(),
    blogViewModel: BlogViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    dateRecordViewModel: DateRecordViewModel = hiltViewModel(),
    dailyRecordViewModel: DailyRecordViewModel = hiltViewModel(),
    externalNavigationRoute: String? = null,
    onExternalNavigationHandled: () -> Unit = {},
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val ready by settingsViewModel.ready.collectAsStateWithLifecycle()
    DeskCubbyTheme(settings) {
        AppBackground(settings) {
        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                AppLoadingIndicator()
            }
            return@AppBackground
        }
        val navController = rememberNavController()
        val aliasContext = LocalContext.current
        LaunchedEffect(settings.useChineseLauncherName, settings.launcherIcon) {
            syncLauncherAlias(
                aliasContext,
                settings.useChineseLauncherName,
                settings.launcherIcon,
            )
        }
        var settingsSubpageOpen by remember { mutableStateOf(false) }
        var readerOpen by remember { mutableStateOf(false) }
        var gameOpen by remember { mutableStateOf(false) }
        var childTutorialTarget by remember { mutableStateOf<PageTutorialTarget?>(null) }
        var tutorialConfirmedThisSession by remember { mutableStateOf(emptySet<String>()) }
        val initialStartDestination = remember { settings.defaultPage.route }
        val systemAnimationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
        val organicMotionEnabled = settings.visualStyle == VisualStyle.ORGANIC_FUTURE &&
            systemAnimationsEnabled
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val visibleTabs = settings.navItems.filter { it.visible || it.id == NavItemId.SETTINGS }
        val bottomSelectedRoute = route.takeIf { currentRoute ->
            visibleTabs.any { it.id.route == currentRoute }
        } ?: NavItemId.MORE.route.takeIf {
            route != null && settings.navItems.any { item ->
                item.id.route == route && item.showInMore
            }
        }
        val showBottomBar = route in NavItemId.entries.map { it.route } &&
            !(route == NavItemId.SETTINGS.route && settingsSubpageOpen) &&
            !(route == NavItemId.READER.route && readerOpen) &&
            !(route == NavItemId.GAMES.route && gameOpen) &&
            !WindowInsets.isImeVisible
        val navigateMain: (String) -> Unit = { destination ->
            navController.navigate(destination) {
                // Keep only the graph itself, so no tab can restore another tab's nested page.
                popUpTo(navController.graph.id) { saveState = false }
                launchSingleTop = true
                restoreState = false
            }
        }
        LaunchedEffect(externalNavigationRoute) {
            externalNavigationRoute
                ?.takeIf { requested -> NavItemId.entries.any { it.route == requested } }
                ?.let(navigateMain)
            if (externalNavigationRoute != null) onExternalNavigationHandled()
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBottomBar) {
                    DeskBottomBar(
                        items = visibleTabs,
                        selectedRoute = bottomSelectedRoute,
                        showLabels = settings.bottomNavShowLabels,
                        musicVisualizerEnabled = settings.musicVisualizerEnabled,
                        musicVisualizerStyle = settings.musicVisualizerStyle,
                        musicVisualizerFrequencyMode = settings.musicVisualizerFrequencyMode,
                        musicVisualizerMinFrequencyHz = settings.musicVisualizerMinFrequencyHz,
                        musicVisualizerMaxFrequencyHz = settings.musicVisualizerMaxFrequencyHz,
                        onSelected = { item -> navigateMain(item.id.route) },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = initialStartDestination,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        when {
                            organicMotionEnabled -> fadeIn(tween(340)) +
                                slideInHorizontally(tween(340)) { it / 20 } +
                                scaleIn(tween(340), initialScale = 0.992f)
                            settings.visualStyle == VisualStyle.ORGANIC_FUTURE -> EnterTransition.None
                            else -> fadeIn(tween(700))
                        }
                    },
                    exitTransition = {
                        when {
                            organicMotionEnabled -> fadeOut(tween(300)) +
                                slideOutHorizontally(tween(340)) { -it / 28 } +
                                scaleOut(tween(340), targetScale = 1.008f)
                            settings.visualStyle == VisualStyle.ORGANIC_FUTURE -> ExitTransition.None
                            else -> fadeOut(tween(700))
                        }
                    },
                    popEnterTransition = {
                        when {
                            organicMotionEnabled -> fadeIn(tween(340)) +
                                slideInHorizontally(tween(340)) { -it / 20 } +
                                scaleIn(tween(340), initialScale = 0.992f)
                            settings.visualStyle == VisualStyle.ORGANIC_FUTURE -> EnterTransition.None
                            else -> fadeIn(tween(700))
                        }
                    },
                    popExitTransition = {
                        when {
                            organicMotionEnabled -> fadeOut(tween(300)) +
                                slideOutHorizontally(tween(340)) { it / 28 } +
                                scaleOut(tween(340), targetScale = 1.008f)
                            settings.visualStyle == VisualStyle.ORGANIC_FUTURE -> ExitTransition.None
                            else -> fadeOut(tween(700))
                        }
                    },
                ) {
                    composable(NavItemId.HOME.route) {
                        HomeScreen(
                            padding = padding,
                            settings = settings,
                            viewModel = homeViewModel,
                            onOpenDiary = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
                            onOpenThoughts = { navController.navigate(NavItemId.THOUGHT.route) },
                            onOpenWebsite = { navController.navigate(NavItemId.BLOG.route) },
                            onOpenDateRecords = { navController.navigate(NavItemId.DATE.route) },
                            onOpenDailyRecords = { navController.navigate(Routes.DAILY_RECORDS_TODAY) },
                        )
                    }
                    composable(NavItemId.DIARY.route) {
                        DiaryListScreen(
                            padding = padding,
                            viewModel = diaryViewModel,
                            onOpen = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
                            onOpenToday = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
                            onOpenMealCalendar = { navController.navigate(Routes.MEAL_CALENDAR) },
                            onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
                        )
                    }
                    composable(NavItemId.BLOG.route) {
                        BlogScreen(
                            padding = padding,
                            viewModel = blogViewModel,
                            onCloseTrustedArticle = { navController.popBackStack() },
                        )
                    }
                    composable(NavItemId.THOUGHT.route) {
                        ThoughtScreen(
                            padding = padding,
                            viewModel = thoughtViewModel,
                            onTrash = { navController.navigate(Routes.THOUGHT_TRASH) },
                        )
                    }
                    composable(NavItemId.DATE.route) {
                        DateRecordScreen(padding = padding, viewModel = dateRecordViewModel)
                    }
                    composable(NavItemId.POETRY.route) {
                        val poetryBookViewModel: PoetryBookViewModel = hiltViewModel()
                        PoetryBookScreen(
                            padding = padding,
                            viewModel = poetryBookViewModel,
                            settings = settings,
                            onOpenSettings = {
                                navController.navigate(Routes.POETRY_SETTINGS)
                            },
                        )
                    }
                    composable(NavItemId.RSS.route) {
                        val rssViewModel: RssViewModel = hiltViewModel()
                        RssScreen(
                            padding = padding,
                            viewModel = rssViewModel,
                            onOpenArticle = { articleUrl ->
                                if (blogViewModel.openTrustedArticleUrl(articleUrl)) {
                                    navController.navigate(NavItemId.BLOG.route) {
                                        launchSingleTop = true
                                    }
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                    }
                    composable(NavItemId.AI_CHAT.route) {
                        val aiChatViewModel: AiChatViewModel = hiltViewModel()
                        AiChatScreen(
                            padding = padding,
                            viewModel = aiChatViewModel,
                            onOpenSettings = { navController.navigate(Routes.AI_SETTINGS) },
                        )
                    }
                    composable(NavItemId.VAULT.route) {
                        val vaultViewModel: VaultViewModel = hiltViewModel()
                        VaultScreen(padding = padding, viewModel = vaultViewModel, settings = settings)
                    }
                    composable(NavItemId.READER.route) {
                        val readerViewModel: ReaderViewModel = hiltViewModel()
                        ReaderScreen(
                            padding = padding,
                            viewModel = readerViewModel,
                            onReadingChanged = { readerOpen = it },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(NavItemId.GAMES.route) {
                        val gamesViewModel: GamesViewModel = hiltViewModel()
                        GamesScreen(
                            padding = padding,
                            viewModel = gamesViewModel,
                            onGameOpenChanged = { gameOpen = it },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(NavItemId.STATISTICS.route) {
                        val statisticsHubViewModel: StatisticsHubViewModel = hiltViewModel()
                        StatisticsHubScreen(
                            padding = padding,
                            viewModel = statisticsHubViewModel,
                            onOpenUsage = { navController.navigate(Routes.STATISTICS_USAGE) },
                            onOpenHealth = { navController.navigate(Routes.STATISTICS_HEALTH) },
                        )
                    }
                    composable(NavItemId.USAGE.route) {
                        val usageStatisticsViewModel: UsageStatisticsViewModel = hiltViewModel()
                        UsageStatisticsScreen(
                            padding = padding,
                            viewModel = usageStatisticsViewModel,
                            onRequestUsageAccess = { openUsageAccessSettings(aliasContext) },
                            onOpenTrackingSettings = {
                                navController.navigate(Routes.USAGE_SETTINGS)
                            },
                        )
                    }
                    composable(NavItemId.STEPS.route) {
                        val stepStatisticsViewModel: StepStatisticsViewModel = hiltViewModel()
                        StepStatisticsScreen(
                            padding = padding,
                            viewModel = stepStatisticsViewModel,
                            onOpenTrackingSettings = {
                                navController.navigate(Routes.STEPS_SETTINGS)
                            },
                            onOpenHealthConnect = {
                                if (StepHealthConnectAccess.open(aliasContext).isFailure) {
                                    stepStatisticsViewModel.onHealthConnectOpenFailed()
                                }
                            },
                        )
                    }
                    composable(NavItemId.WIDGETS.route) {
                        val desktopWidgetsViewModel: DesktopWidgetsViewModel = hiltViewModel()
                        DesktopWidgetsScreen(
                            padding = padding,
                            viewModel = desktopWidgetsViewModel,
                        )
                    }
                    composable(NavItemId.MORE.route) {
                        MoreHubScreen(
                            padding = padding,
                            items = orderedMorePageItems(
                                allItems = settings.navItems,
                                order = settings.morePageOrder,
                            ),
                            showDescriptions = settings.morePageShowDescriptions,
                            onOpenPage = { itemId ->
                                navController.navigate(itemId.route) {
                                    launchSingleTop = true
                                }
                            },
                            onItemsReordered = { reorderedIds, onDone ->
                                settingsViewModel.setMorePageOrder(
                                    mergeVisibleMorePageOrder(
                                        allItems = settings.navItems,
                                        currentOrder = settings.morePageOrder,
                                        visibleOrder = reorderedIds,
                                    ),
                                    onDone,
                                )
                            },
                            onOpenNavigationSettings = {
                                navController.navigate(Routes.MORE_PAGE_SETTINGS)
                            },
                        )
                    }
                    composable(NavItemId.SETTINGS.route) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            onSubpageOpenChanged = { settingsSubpageOpen = it },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(Routes.EDITOR) {
                        DiaryEditorScreen(
                            viewModel = diaryViewModel,
                            onBack = { navController.popBackStack() },
                            onOpenDailyRecords = { navController.navigate(Routes.DAILY_RECORDS) },
                        )
                    }
                    composable(Routes.DAILY_RECORDS) {
                        DailyRecordScreen(
                            padding = padding,
                            viewModel = dailyRecordViewModel,
                            onBack = { navController.popBackStack() },
                            onRecordToCurrentDiary = diaryViewModel::appendDailyRecordToCurrent,
                        )
                    }
                    composable(Routes.DAILY_RECORDS_TODAY) {
                        DailyRecordScreen(
                            padding = padding,
                            viewModel = dailyRecordViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.MEAL_CALENDAR) {
                        MealCalendarScreen(
                            viewModel = diaryViewModel,
                            onBack = { navController.popBackStack() },
                            filterSettings = settings.mealPhotoFilter,
                            onFilterEnabledChange = { enabled ->
                                settingsViewModel.setMealPhotoFilter(
                                    settings.mealPhotoFilter.copy(enabled = enabled),
                                )
                            },
                            onOpenFilterSettings = {
                                navController.navigate(Routes.MEAL_FILTER_SETTINGS)
                            },
                            onOpenCalorieProgress = {
                                navController.navigate(Routes.CALORIE_ESTIMATION_PROGRESS)
                            },
                        )
                    }
                    composable(Routes.STATISTICS_USAGE) {
                        val usageStatisticsViewModel: UsageStatisticsViewModel = hiltViewModel()
                        UsageStatisticsScreen(
                            padding = PaddingValues(0.dp),
                            viewModel = usageStatisticsViewModel,
                            onRequestUsageAccess = { openUsageAccessSettings(aliasContext) },
                            onOpenTrackingSettings = {
                                navController.navigate(Routes.USAGE_SETTINGS)
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.STATISTICS_HEALTH) {
                        val stepStatisticsViewModel: StepStatisticsViewModel = hiltViewModel()
                        StepStatisticsScreen(
                            padding = PaddingValues(0.dp),
                            viewModel = stepStatisticsViewModel,
                            onOpenTrackingSettings = {
                                navController.navigate(Routes.STEPS_SETTINGS)
                            },
                            onOpenHealthConnect = {
                                if (StepHealthConnectAccess.open(aliasContext).isFailure) {
                                    stepStatisticsViewModel.onHealthConnectOpenFailed()
                                }
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.CALORIE_ESTIMATION_PROGRESS) {
                        CalorieEstimationProgressScreen(
                            viewModel = diaryViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.MEAL_FILTER_SETTINGS) {
                        MealPhotoFilterSettingsScreen(
                            settings = settings.mealPhotoFilter,
                            onBack = { navController.popBackStack() },
                            onSave = { filter ->
                                settingsViewModel.setMealPhotoFilter(filter) {
                                    navController.popBackStack()
                                }
                            },
                        )
                    }
                    composable(Routes.THOUGHT_TRASH) {
                        ThoughtTrashScreen(viewModel = thoughtViewModel, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.NAVIGATION_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.NAVIGATION,
                            onExit = { navController.popBackStack() },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(Routes.MORE_PAGE_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.MORE_PAGE,
                            onExit = { navController.popBackStack() },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(Routes.USAGE_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.USAGE,
                            onExit = { navController.popBackStack() },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(Routes.STEPS_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.STEPS,
                            onExit = { navController.popBackStack() },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(Routes.AI_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.AI,
                            onExit = { navController.popBackStack() },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                    composable(Routes.POETRY_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.POETRY,
                            onExit = { navController.popBackStack() },
                            onTutorialTargetChanged = { childTutorialTarget = it },
                        )
                    }
                }
            }
        }

        val tutorialTarget = childTutorialTarget ?: routeTutorialTarget(route, settings)
        if (
            settings.tutorialModeEnabled && tutorialTarget != null &&
            tutorialTarget.pageId !in settings.tutorialAcknowledgedPages &&
            tutorialTarget.pageId !in tutorialConfirmedThisSession
        ) {
            PageTutorialOverlay(
                target = tutorialTarget,
                onConfirm = {
                    tutorialConfirmedThisSession += tutorialTarget.pageId
                    settingsViewModel.acknowledgeTutorialPage(tutorialTarget.pageId)
                },
            )
        }
        }
    }
}

@Composable
private fun routeTutorialTarget(route: String?, settings: AppSettings): PageTutorialTarget? {
    @Composable
    fun target(
        id: String,
        titleChinese: String,
        titleEnglish: String,
        descriptionChinese: String,
        descriptionEnglish: String,
        vararg hints: Pair<String, String>,
    ) = PageTutorialTarget(
        pageId = id,
        title = tr(titleChinese, titleEnglish),
        description = tr(descriptionChinese, descriptionEnglish),
        hints = hints.map { (chinese, english) -> tr(chinese, english) },
    )

    return when (route) {
        // These destinations report their finer-grained internal page directly.
        NavItemId.READER.route,
        NavItemId.GAMES.route,
        NavItemId.SETTINGS.route,
        Routes.NAVIGATION_SETTINGS,
        Routes.MORE_PAGE_SETTINGS,
        Routes.USAGE_SETTINGS,
        Routes.STEPS_SETTINGS,
        Routes.AI_SETTINGS,
        Routes.POETRY_SETTINGS,
        -> null

        Routes.EDITOR -> target(
            "page/diary-editor",
            "日记编辑器",
            "Diary editor",
            "在这里编辑 Markdown 正文、切换阅读预览，并安全保存真实日记文件。",
            "Edit Markdown, switch to reading preview, and safely save the real diary file here.",
            "标题栏会显示已保存或未保存；外部修改冲突时应用不会静默覆盖。" to
                "The title bar shows save state; external-edit conflicts are never overwritten silently.",
            "“日常记录”可把常用多行内容追加到当前日记。" to
                "Daily records can append reusable multi-line text to this diary.",
        )
        Routes.MEAL_CALENDAR -> target(
            "page/meal-calendar",
            "吃历",
            "Meal calendar",
            "按日期与餐别浏览饮食照片、热量明细、地点和非破坏滤镜。",
            "Browse meal photos, energy details, locations, and non-destructive filters by day and meal.",
            "点照片可放大；计算器用于排队估算，长按可进入进度页。" to
                "Tap a photo to zoom; use the calculator to queue estimates and long-press it for progress.",
        )
        Routes.CALORIE_ESTIMATION_PROGRESS -> target(
            "page/calorie-progress",
            "热量估算进度",
            "Calorie estimation progress",
            "查看按日期串行处理的图片识别、文字估算与保存状态。",
            "Follow image recognition, text estimation, and save status in the per-date queue.",
            "离开本页不会取消队列；失败项会保留具体阶段。" to
                "Leaving does not cancel the queue; failed items retain their exact stage.",
        )
        Routes.MEAL_FILTER_SETTINGS -> target(
            "page/meal-filter-settings",
            "吃历滤镜设置",
            "Meal filter settings",
            "调整亮度、对比度、饱和度、色温与色调，预览不会改写原图。",
            "Adjust brightness, contrast, saturation, warmth, and tint without rewriting originals.",
            "右上角保存前，返回会提示是否放弃草稿。" to
                "Going back before using Save asks whether to discard the draft.",
        )
        Routes.THOUGHT_TRASH -> target(
            "page/thought-trash",
            "小巧思回收站",
            "Thought trash",
            "恢复误删的小巧思，或确认永久删除。",
            "Restore accidentally deleted thoughts or confirm permanent deletion.",
        )
        Routes.DAILY_RECORDS,
        Routes.DAILY_RECORDS_TODAY,
        -> target(
            "page/daily-records",
            "日常记录",
            "Daily records",
            "建立可复用的多行事件模板，填写后写入当前或今日日记。",
            "Create reusable multi-line event templates, fill them in, then write to the current or today's diary.",
            "模板中的 xx 会加下划线，点按即可直接选中替换。" to
                "Any xx placeholder is underlined and can be tapped to select it for replacement.",
            "只有真实文件写入成功后，输入才会重置并显示成功。" to
                "Input resets and success appears only after the real file write succeeds.",
        )
        Routes.STATISTICS_USAGE -> target(
            "page/statistics-screen-time",
            "使用时间统计",
            "Screen-time statistics",
            "查看按设备、日期范围和应用汇总的手机使用时间。",
            "Review phone usage grouped by device, date range, and app.",
        )
        Routes.STATISTICS_HEALTH -> target(
            "page/statistics-health",
            "健康统计",
            "Health statistics",
            "查看 Health Connect 提供的步数、距离与活动热量。",
            "View steps, distance, and active calories supplied by Health Connect.",
        )
        else -> {
            val id = NavItemId.entries.firstOrNull { it.route == route } ?: return null
            val config = settings.navItems.firstOrNull { it.id == id }
            val title = if (
                settings.appLanguage == AppLanguage.ENGLISH &&
                (config == null || config.label == id.defaultLabel)
            ) id.englishLabel else config?.label ?: id.defaultLabel
            val description = if (settings.appLanguage == AppLanguage.ENGLISH) {
                id.englishDescription
            } else {
                id.defaultDescription
            }
            val pageHints = when (id) {
                NavItemId.HOME -> listOf(
                    tr("卡片内容与顺序可在“设置 → 子页面设置 → 主页”调整。", "Configure cards and order in Settings → Subpage settings → Home."),
                    tr("快速输入、饮食图片和日常记录会在真正保存后再提示成功。", "Quick input, meal photos, and daily records report success only after saving."),
                )
                NavItemId.DIARY -> listOf(
                    tr("点日记进入编辑；顶部按钮可新建、打开今日日记或进入吃历。", "Tap a diary to edit it; top actions create, open today, or enter the meal calendar."),
                )
                NavItemId.MORE -> listOf(
                    tr("拖动四点手柄调整卡片顺序；收纳内容在导航页设置中管理。", "Drag four-dot handles to reorder cards; manage included pages in Navigation page settings."),
                )
                NavItemId.THOUGHT -> listOf(
                    tr("长按条目可编辑、分类、标重点或删除；四点手柄可排序。", "Long-press to edit, categorize, highlight, or delete; use the four-dot handle to reorder."),
                )
                NavItemId.AI_CHAT -> listOf(
                    tr("发送前可从输入框左侧选择图片、日记或小巧思作为上下文。", "Before sending, use the left input menu to attach an image, diary, or thought context."),
                )
                NavItemId.VAULT -> listOf(
                    tr("密码与明文只在本机解锁会话中使用；忘记密码无法恢复。", "The password and plaintext are used only in the local unlock session; a forgotten password cannot be recovered."),
                )
                NavItemId.STATISTICS -> listOf(
                    tr("点任一概览卡可进入趋势与明细；未知数据保持“—”。", "Tap an overview card for trends and details; unavailable data remains “—”."),
                )
                else -> emptyList()
            }
            PageTutorialTarget(
                pageId = "page/${id.route}",
                title = title,
                description = description,
                hints = pageHints,
            )
        }
    }
}

private fun openUsageAccessSettings(context: Context) {
    val packageIntent = Intent(
        Settings.ACTION_USAGE_ACCESS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(packageIntent) }
        .recoverCatching {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
}

internal fun orderedMorePageItems(
    allItems: List<NavItemConfig>,
    order: List<NavItemId>,
): List<NavItemConfig> {
    val byId = allItems.associateBy(NavItemConfig::id)
    return normalizeMorePageOrder(order, allItems)
        .mapNotNull(byId::get)
        .filter(NavItemConfig::showInMore)
}

/**
 * Reorders only the pages currently shown on the navigation page. Hidden pages keep their slots
 * in the complete order so enabling one later does not make bottom-navigation edits affect it.
 */
internal fun mergeVisibleMorePageOrder(
    allItems: List<NavItemConfig>,
    currentOrder: List<NavItemId>,
    visibleOrder: List<NavItemId>,
): List<NavItemId> {
    val normalized = normalizeMorePageOrder(currentOrder, allItems)
    val visibleIds = allItems.asSequence()
        .filter(NavItemConfig::showInMore)
        .map(NavItemConfig::id)
        .filter(normalized::contains)
        .toSet()
    if (visibleIds.isEmpty()) return normalized

    val replacements = buildList {
        visibleOrder.forEach { id ->
            if (id in visibleIds && id !in this) add(id)
        }
        normalized.forEach { id ->
            if (id in visibleIds && id !in this) add(id)
        }
    }.iterator()
    return normalized.map { id ->
        if (id in visibleIds) replacements.next() else id
    }
}

@Composable
internal fun DeskBottomBar(
    items: List<NavItemConfig>,
    selectedRoute: String?,
    showLabels: Boolean,
    musicVisualizerEnabled: Boolean,
    musicVisualizerStyle: MusicVisualizerStyle,
    musicVisualizerFrequencyMode: MusicVisualizerFrequencyMode,
    musicVisualizerMinFrequencyHz: Int,
    musicVisualizerMaxFrequencyHz: Int,
    onSelected: (NavItemConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalVisualStyle.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var audioPermissionGranted by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
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
    val visualizerActive = musicVisualizerEnabled &&
        audioPermissionGranted
    val glass = style == VisualStyle.LIQUID_GLASS
    val organic = style == VisualStyle.ORGANIC_FUTURE
    val floatingPanel = glass || organic
    val language = LocalAppLanguage.current
    val visuals = deskCubbyVisuals
    val content: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            MusicVisualizerLayer(
                enabled = visualizerActive,
                style = musicVisualizerStyle,
                frequencyMode = musicVisualizerFrequencyMode,
                minFrequencyHz = musicVisualizerMinFrequencyHz,
                maxFrequencyHz = musicVisualizerMaxFrequencyHz,
                // A regular fillMaxSize child takes Scaffold's full bottom-bar constraint and
                // makes this Box consume the whole screen. Match the NavigationBar only after
                // the Box has derived its height from that non-match-parent child.
                modifier = Modifier.matchParentSize(),
            )
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (showLabels) Modifier else Modifier.height(56.dp)),
                containerColor = if (floatingPanel || visualizerActive) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                tonalElevation = if (floatingPanel || visualizerActive) 0.dp else 3.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                items.forEach { item ->
                    val label = if (language == AppLanguage.ENGLISH && item.label.isDefaultLabelFor(item.id)) {
                        item.id.englishLabel
                    } else {
                        item.label
                    }
                    NavigationBarItem(
                        selected = selectedRoute == item.id.route,
                        onClick = { onSelected(item) },
                        icon = { Icon(iconFor(item.iconKey), label) },
                        label = if (showLabels) {
                            { Text(label, maxLines = 1) }
                        } else {
                            null
                        },
                        alwaysShowLabel = showLabels,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }

    if (floatingPanel) {
        Box(
            modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(
                    horizontal = if (organic) 10.dp else 12.dp,
                    vertical = if (showLabels) {
                        if (organic) 6.dp else 8.dp
                    } else {
                        4.dp
                    },
                ),
        ) {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 28.dp,
                role = if (organic) PanelRole.FEATURE else PanelRole.STANDARD,
                padding = PaddingValues(0.dp),
            ) { content() }
        }
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ),
        ) {
            content()
        }
    }
}

private fun String.isDefaultLabelFor(id: NavItemId): Boolean =
    this == id.defaultLabel ||
        (id == NavItemId.BLOG && this == "博客") ||
        (id == NavItemId.THOUGHT && this == "闪思") ||
        (id == NavItemId.STEPS && this == "步数记录")

fun iconFor(key: String): ImageVector = when (key) {
    "home" -> Icons.Outlined.Home
    "book" -> Icons.Outlined.Book
    "language" -> Icons.Outlined.Language
    "bolt" -> Icons.Outlined.Bolt
    "poetry" -> Icons.AutoMirrored.Outlined.MenuBook
    "settings" -> Icons.Outlined.Settings
    "calendar" -> Icons.Outlined.CalendarMonth
    "event" -> Icons.Outlined.Event
    "star" -> Icons.Outlined.Star
    "write" -> Icons.Outlined.Create
    "sparkle" -> Icons.Outlined.AutoAwesome
    "day" -> Icons.Outlined.ViewDay
    "rss" -> Icons.Outlined.RssFeed
    "ai" -> Icons.Outlined.Psychology
    "apps" -> Icons.Outlined.Apps
    "lock" -> Icons.Outlined.Lock
    "game" -> Icons.Outlined.SportsEsports
    "reader" -> Icons.Outlined.AutoStories
    "usage" -> Icons.Outlined.AccessTime
    "steps" -> Icons.Outlined.MonitorHeart
    "statistics" -> Icons.Outlined.BarChart
    "widgets" -> Icons.Outlined.Widgets
    else -> Icons.AutoMirrored.Outlined.MenuBook
}
