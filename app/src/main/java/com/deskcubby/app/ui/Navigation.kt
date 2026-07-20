@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.deskcubby.app.ui

import android.animation.ValueAnimator
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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.blog.BlogScreen
import com.deskcubby.app.ui.blog.BlogViewModel
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.diary.DiaryEditorScreen
import com.deskcubby.app.ui.diary.DiaryListScreen
import com.deskcubby.app.ui.diary.DiaryViewModel
import com.deskcubby.app.ui.date.DateRecordScreen
import com.deskcubby.app.ui.date.DateRecordViewModel
import com.deskcubby.app.ui.home.HomeScreen
import com.deskcubby.app.ui.home.HomeViewModel
import com.deskcubby.app.ui.poetry.PoetryBookScreen
import com.deskcubby.app.ui.poetry.PoetryBookViewModel
import com.deskcubby.app.ui.settings.SettingsScreen
import com.deskcubby.app.ui.settings.SettingsViewModel
import com.deskcubby.app.ui.theme.DeskCubbyTheme
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.LocalVisualStyle
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.deskCubbyVisuals
import com.deskcubby.app.ui.thought.ThoughtScreen
import com.deskcubby.app.ui.thought.ThoughtTrashScreen
import com.deskcubby.app.ui.thought.ThoughtViewModel

object Routes {
    const val EDITOR = "diary_editor"
    const val THOUGHT_TRASH = "thought_trash"
}

@Composable
fun DeskCubbyRoot(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
    thoughtViewModel: ThoughtViewModel = hiltViewModel(),
    blogViewModel: BlogViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
    dateRecordViewModel: DateRecordViewModel = hiltViewModel(),
    poetryBookViewModel: PoetryBookViewModel = hiltViewModel(),
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
        val initialStartDestination = remember { settings.defaultPage.route }
        val systemAnimationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
        val organicMotionEnabled = settings.visualStyle == VisualStyle.ORGANIC_FUTURE &&
            systemAnimationsEnabled
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val visibleTabs = settings.navItems.filter { it.visible || it.id == NavItemId.SETTINGS }
        val showBottomBar = route in NavItemId.entries.map { it.route } && !WindowInsets.isImeVisible
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
                        selectedRoute = route,
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
                            onWidgetsChanged = settingsViewModel::setHomeWidgets,
                            onWidgetTitlesChanged = settingsViewModel::setHomeWidgetTitles,
                            onMealButtonsUseIconsChanged = settingsViewModel::setMealButtonsUseIcons,
                            onMealButtonIconsChanged = settingsViewModel::setMealButtonIcons,
                        )
                    }
                    composable(NavItemId.DIARY.route) {
                        DiaryListScreen(
                            padding = padding,
                            viewModel = diaryViewModel,
                            onOpen = { uri -> diaryViewModel.open(uri); navController.navigate(Routes.EDITOR) },
                            onOpenToday = { diaryViewModel.enterToday { navController.navigate(Routes.EDITOR) } },
                            onOpenSettings = { navigateMain(NavItemId.SETTINGS.route) },
                        )
                    }
                    composable(NavItemId.BLOG.route) {
                        BlogScreen(padding = padding, viewModel = blogViewModel)
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
                        PoetryBookScreen(padding = padding, viewModel = poetryBookViewModel)
                    }
                    composable(NavItemId.SETTINGS.route) {
                        SettingsScreen(padding = padding, viewModel = settingsViewModel)
                    }
                    composable(Routes.EDITOR) {
                        DiaryEditorScreen(viewModel = diaryViewModel, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.THOUGHT_TRASH) {
                        ThoughtTrashScreen(viewModel = thoughtViewModel, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
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
                        selectedIconColor = if (organic) MaterialTheme.colorScheme.primary
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
    else -> Icons.Outlined.MenuBook
}
