@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.deskcubby.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.normalizeMorePageOrder
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.blog.BlogScreen
import com.deskcubby.app.ui.blog.BlogViewModel
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.diary.DiaryEditorScreen
import com.deskcubby.app.ui.diary.DiaryListScreen
import com.deskcubby.app.ui.diary.DiaryViewModel
import com.deskcubby.app.ui.diary.MealCalendarScreen
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
import com.deskcubby.app.ui.settings.SettingsScreen
import com.deskcubby.app.ui.settings.SettingsStartPage
import com.deskcubby.app.ui.settings.SettingsViewModel
import com.deskcubby.app.ui.rss.RssScreen
import com.deskcubby.app.ui.rss.RssViewModel
import com.deskcubby.app.ui.steps.StepStatisticsScreen
import com.deskcubby.app.ui.steps.StepStatisticsViewModel
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
import com.deskcubby.app.ui.games.GamesScreen
import com.deskcubby.app.ui.games.GamesViewModel
import com.deskcubby.app.ui.thought.ThoughtScreen
import com.deskcubby.app.ui.thought.ThoughtTrashScreen
import com.deskcubby.app.ui.thought.ThoughtViewModel
import com.deskcubby.app.ui.vault.VaultScreen
import com.deskcubby.app.ui.vault.VaultViewModel
import com.deskcubby.app.data.statistics.StepHealthConnectAccess

object Routes {
    const val EDITOR = "diary_editor"
    const val MEAL_CALENDAR = "meal_calendar"
    const val MEAL_FILTER_SETTINGS = "meal_filter_settings"
    const val THOUGHT_TRASH = "thought_trash"
    const val DAILY_RECORDS = "daily_records"
    const val DAILY_RECORDS_TODAY = "daily_records/today"
    const val NAVIGATION_SETTINGS = "settings/navigation"
    const val MORE_PAGE_SETTINGS = "settings/more-page"
    const val USAGE_SETTINGS = "settings/usage-statistics"
    const val STEPS_SETTINGS = "settings/step-statistics"
    const val AI_SETTINGS = "settings/ai"
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
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val ready by settingsViewModel.ready.collectAsStateWithLifecycle()
    DeskCubbyTheme(settings) {
        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                AppLoadingIndicator()
            }
            return@DeskCubbyTheme
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
        var introDismissedForSession by remember { mutableStateOf(false) }
        var settingsSubpageOpen by remember { mutableStateOf(false) }
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
            !WindowInsets.isImeVisible
        val navigateMain: (String) -> Unit = { destination ->
            navController.navigate(destination) {
                // Keep only the graph itself, so no tab can restore another tab's nested page.
                popUpTo(navController.graph.id) { saveState = false }
                launchSingleTop = true
                restoreState = false
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBottomBar) {
                    DeskBottomBar(
                        items = visibleTabs,
                        selectedRoute = bottomSelectedRoute,
                        showLabels = settings.bottomNavShowLabels,
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
                        PoetryBookScreen(padding = padding, viewModel = poetryBookViewModel)
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
                        VaultScreen(padding = padding, viewModel = vaultViewModel)
                    }
                    composable(NavItemId.GAMES.route) {
                        val gamesViewModel: GamesViewModel = hiltViewModel()
                        GamesScreen(padding = padding, viewModel = gamesViewModel)
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
                        )
                    }
                    composable(Routes.MORE_PAGE_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.MORE_PAGE,
                            onExit = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.USAGE_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.USAGE,
                            onExit = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.STEPS_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.STEPS,
                            onExit = { navController.popBackStack() },
                        )
                    }
                    composable(Routes.AI_SETTINGS) {
                        SettingsScreen(
                            padding = padding,
                            viewModel = settingsViewModel,
                            startPage = SettingsStartPage.AI,
                            onExit = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        if (!settings.navigationIntroAcknowledged && !introDismissedForSession) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(if (settings.appLanguage == AppLanguage.ENGLISH) "Choose your pages" else "选择你需要的页面") },
                text = {
                    Text(
                        if (settings.appLanguage == AppLanguage.ENGLISH) {
                            "DeskCubby has several pages. Bottom navigation settings control the bottom bar; Navigation page settings under Subpage settings control the More page."
                        } else {
                            "DeskCubby 包含多个页面。“底部导航”设置管理底栏；“子页面设置 → 导航页”管理导航页中的入口。"
                        },
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            introDismissedForSession = true
                            settingsViewModel.acknowledgeNavigationIntro()
                            navController.navigate(Routes.NAVIGATION_SETTINGS)
                        },
                    ) {
                        Text(if (settings.appLanguage == AppLanguage.ENGLISH) "Navigation settings" else "跳转至底部导航设置")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            introDismissedForSession = true
                            settingsViewModel.acknowledgeNavigationIntro()
                        },
                    ) { Text(if (settings.appLanguage == AppLanguage.ENGLISH) "OK" else "确认") }
                },
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
private fun DeskBottomBar(
    items: List<NavItemConfig>,
    selectedRoute: String?,
    showLabels: Boolean,
    onSelected: (NavItemConfig) -> Unit,
) {
    val style = LocalVisualStyle.current
    val glass = style == VisualStyle.LIQUID_GLASS
    val organic = style == VisualStyle.ORGANIC_FUTURE
    val floatingPanel = glass || organic
    val language = LocalAppLanguage.current
    val visuals = deskCubbyVisuals
    val content: @Composable () -> Unit = {
        NavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showLabels) Modifier else Modifier.height(56.dp)),
            containerColor = if (floatingPanel) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = if (floatingPanel) 0.dp else 3.dp,
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
                        selectedIconColor = if (organic) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedTextColor = if (organic) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        indicatorColor = when {
                            glass -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            organic -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }

    if (floatingPanel) {
        Box(
            Modifier
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
            Modifier
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
    this == id.defaultLabel || (id == NavItemId.BLOG && this == "博客") || (id == NavItemId.THOUGHT && this == "闪思")

fun iconFor(key: String): ImageVector = when (key) {
    "home" -> Icons.Outlined.Home
    "book" -> Icons.Outlined.Book
    "language" -> Icons.Outlined.Language
    "bolt" -> Icons.Outlined.Bolt
    "poetry" -> Icons.Outlined.MenuBook
    "settings" -> Icons.Outlined.Settings
    "calendar" -> Icons.Outlined.CalendarMonth
    "event" -> Icons.Outlined.Event
    "star" -> Icons.Outlined.Star
    "write" -> Icons.Outlined.Create
    "sparkle" -> Icons.Outlined.AutoAwesome
    "day" -> Icons.Outlined.ViewDay
    "rss" -> Icons.Outlined.RssFeed
    "ai" -> Icons.Outlined.SmartToy
    "apps" -> Icons.Outlined.Apps
    "lock" -> Icons.Outlined.Lock
    "game" -> Icons.Outlined.SportsEsports
    "usage" -> Icons.Outlined.AccessTime
    "steps" -> Icons.Outlined.DirectionsWalk
    else -> Icons.Outlined.MenuBook
}
