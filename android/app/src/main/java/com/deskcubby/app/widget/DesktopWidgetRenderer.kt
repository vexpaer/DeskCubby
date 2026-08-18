package com.deskcubby.app.widget

import android.app.PendingIntent
import com.deskcubby.app.ui.theme.translate
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import com.deskcubby.app.MainActivity
import com.deskcubby.app.R
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.DesktopWidgetCornerStyle
import com.deskcubby.app.data.model.DesktopWidgetTextAlignment
import com.deskcubby.app.data.model.MealCategory
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DateRecordRepository
import com.deskcubby.app.data.repository.PoetryRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import com.deskcubby.app.data.sync.AppCloudSyncService
import com.deskcubby.app.data.sync.AppCloudSyncStatus
import com.deskcubby.app.data.sync.CloudSyncManualQueueState
import com.deskcubby.app.data.sync.CloudSyncRunMode
import com.deskcubby.app.ui.Routes
import java.text.DateFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

internal data class DesktopWidgetText(
    val title: String,
    val value: String,
    val detail: String = "",
    val route: String = NavItemId.HOME.route,
    val diaryUri: String? = null,
    val gameId: String? = null,
)

@Singleton
class DesktopWidgetRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val instanceStore: DesktopWidgetInstanceStore,
    private val diaryIndexDao: DiaryIndexDao,
    private val thoughtRepository: ThoughtRepository,
    private val dateRecordRepository: DateRecordRepository,
    private val poetryRepository: PoetryRepository,
    private val cloudSyncService: AppCloudSyncService,
    private val gameRenderer: DesktopWidgetGameRenderer,
    private val appPanelRenderer: DesktopWidgetAppPanelRenderer,
    private val cloudSyncUndoStore: com.deskcubby.app.data.sync.CloudSyncUndoStore,
    private val thoughtDraftStore: DesktopWidgetThoughtDraftStore,
) {
    suspend fun update(
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ): Int {
        if (appWidgetIds.isEmpty()) return 0
        // AppWidgetProvider broadcasts have a short execution window. Read independent sources
        // concurrently and degrade individual cards instead of letting one unavailable Room or
        // DataStore source leave every launcher instance blank.
        val settings = loadOrNull { settingsRepository.settings.first() } ?: AppSettings()
        val snapshot = supervisorScope {
            val diaries = async {
                loadOrNull { diaryIndexDao.observeAll().first() }.orEmpty()
            }
            val recentThoughts = async {
                loadOrNull { thoughtRepository.recent.first() }.orEmpty()
            }
            val thoughtCount = async {
                loadOrNull { thoughtRepository.active.first().size } ?: 0
            }
            val dateRecords = async {
                loadOrNull { dateRecordRepository.records.first() }.orEmpty()
            }
            val poem = async { loadOrNull { poetryRepository.poem.first() } }
            val resolvedPoem = poem.await()
            val resolvedDiaries = diaries.await()
            DesktopWidgetContentSnapshot(
                diaries = resolvedDiaries,
                recentThoughts = recentThoughts.await(),
                thoughtCount = thoughtCount.await(),
                dateRecords = dateRecords.await(),
                poemContent = resolvedPoem?.content
                    ?: localized(settings, "打开应用查看", "Open the app to view"),
                poemSource = resolvedPoem?.source.orEmpty(),
                randomDiary = resolvedDiaries.randomOrNull(),
                cloudStatus = cloudSyncService.status.value,
                queuedCloudMode = CloudSyncManualQueueState.queuedMode(context),
            )
        }
        val updatedCount = appWidgetIds.count { appWidgetId ->
            val storedSnapshot = instanceStore.snapshot(appWidgetId)
            val legacyConfigId = if (storedSnapshot == null) {
                instanceStore.configId(appWidgetId)
            } else {
                null
            }
            val config = resolveDesktopWidgetConfig(
                storedSnapshot = storedSnapshot,
                legacyConfigId = legacyConfigId,
                reusableConfigs = settings.desktopWidgetConfigs,
            )
            if (config != null && config != storedSnapshot) {
                // Persist the template version just rendered as this instance's deletion fallback.
                // A write failure must not prevent the launcher from receiving the current view.
                runCatching { instanceStore.bind(appWidgetId, config) }
            }
            try {
                manager.updateAppWidget(
                    appWidgetId,
                    render(manager, appWidgetId, config, settings, snapshot),
                )
                true
            } catch (_: RuntimeException) {
                // A launcher may retire an ID between getAppWidgetIds() and this update.
                false
            }
        }
        reconcileMusicVisualizer(manager, settings)
        return updatedCount
    }

    private fun reconcileMusicVisualizer(
        manager: AppWidgetManager,
        settings: AppSettings,
    ) {
        val placedIds = manager.getAppWidgetIds(ComponentName(context, DeskCubbyWidgetProvider::class.java))
        val hasVisualizer = placedIds.any { appWidgetId ->
            val snapshot = instanceStore.snapshot(appWidgetId)
            val legacyConfigId = if (snapshot == null) instanceStore.configId(appWidgetId) else null
            resolveDesktopWidgetConfig(snapshot, legacyConfigId, settings.desktopWidgetConfigs)
                ?.let { config ->
                    config.contentType == DesktopWidgetContentType.APP_MODULE &&
                        config.homeModuleId == "music_visualizer"
                } == true
        }
        if (hasVisualizer) {
            DesktopWidgetMusicVisualizerService.ensureRunning(context)
        } else {
            DesktopWidgetMusicVisualizerService.stop(context)
        }
    }

    private suspend fun render(
        manager: AppWidgetManager,
        appWidgetId: Int,
        config: DesktopWidgetConfig?,
        settings: AppSettings,
        snapshot: DesktopWidgetContentSnapshot,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.desktop_widget)
        if (config == null) {
            views.setInt(R.id.widget_surface, "setBackgroundColor", 0xFF263238.toInt())
            views.setTextColor(R.id.widget_title, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_value, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_detail, 0xFFFFFFFF.toInt())
            views.setTextViewText(
                R.id.widget_title,
                localized(settings, "配置小卡片", "Configure widget"),
            )
            views.setTextViewText(
                R.id.widget_value,
                localized(settings, "点按选择这个桌面实例的内容", "Tap to choose this instance"),
            )
            views.setViewVisibility(R.id.widget_detail, View.GONE)
            views.setViewVisibility(R.id.widget_background_image, View.GONE)
            views.setViewVisibility(R.id.widget_scrim, View.GONE)
            views.setViewVisibility(R.id.widget_icon, View.GONE)
            views.setViewVisibility(R.id.widget_app_shortcut_content, View.GONE)
            views.setViewVisibility(R.id.widget_foreground, View.VISIBLE)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                configurePendingIntent(appWidgetId),
            )
            return views
        }

        if (config.contentType == DesktopWidgetContentType.APP_MODULE) {
            return renderAppModule(appWidgetId, config, settings)
        }
        val text = if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            appShortcutText(config, settings)
        } else {
            homeModuleText(config.homeModuleId, settings, snapshot)
        }
        val backgroundAlpha = config.backgroundOpacityPercent * 255 / 100
        views.setInt(
            R.id.widget_surface,
            "setBackgroundColor",
            config.backgroundColorArgb.withAlpha(backgroundAlpha),
        )
        views.setTextColor(R.id.widget_title, config.textColorArgb)
        views.setTextColor(R.id.widget_value, config.textColorArgb)
        views.setTextColor(R.id.widget_detail, config.textColorArgb)
        views.setTextViewText(R.id.widget_title, config.name.ifBlank { text.title })
        views.setTextViewText(R.id.widget_value, text.value)
        views.setTextViewText(R.id.widget_detail, text.detail)
        views.setViewVisibility(
            R.id.widget_title,
            if (config.showName) View.VISIBLE else View.GONE,
        )
        val gravity = when (config.textAlignment) {
            DesktopWidgetTextAlignment.START -> Gravity.START
            DesktopWidgetTextAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
            DesktopWidgetTextAlignment.END -> Gravity.END
        }
        listOf(R.id.widget_title, R.id.widget_value, R.id.widget_detail).forEach { viewId ->
            views.setInt(viewId, "setGravity", gravity)
        }
        val textScale = config.textScalePercent / 100f
        views.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 12f * textScale)
        views.setTextViewTextSize(R.id.widget_value, TypedValue.COMPLEX_UNIT_SP, 16f * textScale)
        views.setTextViewTextSize(R.id.widget_detail, TypedValue.COMPLEX_UNIT_SP, 11f * textScale)

        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }
            .getOrDefault(android.os.Bundle.EMPTY)
        val bounds = desktopWidgetBoundsDp(
            minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            config = config,
        )
        val actualWidth = bounds.width
        val actualHeight = bounds.height
        applyWidgetSurfaceAppearance(views, R.id.widget_surface, config, actualWidth, actualHeight)
        val compact = actualWidth < COMPACT_WIDTH_DP || actualHeight < COMPACT_HEIGHT_DP
        views.setViewVisibility(
            R.id.widget_detail,
            if (!compact && text.detail.isNotBlank()) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.widget_icon,
            if (config.showIcon && actualWidth >= ICON_MIN_WIDTH_DP) View.VISIBLE else View.GONE,
        )
        val interactionMode = DesktopWidgetInteractionPolicy.mode(
            config.homeModuleId,
            actualWidth,
            actualHeight,
        ).takeIf { config.contentType == DesktopWidgetContentType.HOME_MODULE }
            ?: DesktopWidgetInteractionMode.NAVIGATION_ONLY
        configureInteractions(
            views = views,
            appWidgetId = appWidgetId,
            mode = interactionMode,
            config = config,
            settings = settings,
            snapshot = snapshot,
            actualWidth = actualWidth,
            actualHeight = actualHeight,
        )

        val background = config.backgroundImageUri?.let { raw ->
            loadBackgroundBitmap(raw, actualWidth, actualHeight)
        }
        if (background != null) {
            views.setImageViewBitmap(R.id.widget_background_image, background)
            views.setInt(R.id.widget_background_image, "setImageAlpha", backgroundAlpha)
            views.setViewVisibility(R.id.widget_background_image, View.VISIBLE)
            views.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
            views.setInt(
                R.id.widget_scrim,
                "setBackgroundColor",
                0x52000000.withAlpha(0x52 * config.backgroundOpacityPercent / 100),
            )
        } else {
            views.setViewVisibility(R.id.widget_background_image, View.GONE)
            views.setViewVisibility(R.id.widget_scrim, View.GONE)
        }

        val packageManager = context.packageManager
        val appLaunchIntent = if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            config.appPackageName?.let { packageName ->
                runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
            }
        } else {
            null
        }
        val icon = if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            config.appPackageName?.takeIf { config.showIcon }?.let { packageName ->
                runCatching {
                    val drawable = appLaunchIntent?.component?.let { component ->
                        runCatching { packageManager.getActivityIcon(component) }.getOrNull()
                    }
                        ?: packageManager.getApplicationIcon(packageName)
                    val edge = desktopAppIconBitmapEdgePx(
                        density = context.resources.displayMetrics.density,
                        scalePercent = config.appIconScalePercent,
                    )
                    drawable.toBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                }.getOrNull()
            }
        } else {
            runCatching {
                packageManager.getApplicationIcon(context.packageName)
                    .toBitmap(96, 96, Bitmap.Config.ARGB_8888)
            }.getOrNull()
        }
        if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            configureAppShortcutContent(
                views = views,
                config = config,
                text = text,
                icon = icon,
                actualWidthDp = actualWidth,
                actualHeightDp = actualHeight,
                gravity = gravity,
                textScale = textScale,
            )
        } else {
            views.setViewVisibility(R.id.widget_app_shortcut_content, View.GONE)
            views.setViewVisibility(R.id.widget_foreground, View.VISIBLE)
            icon?.let { views.setImageViewBitmap(R.id.widget_icon, it) }
        }

        val clickIntent = if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            appLaunchIntent?.let { intent ->
                    PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
        } else {
            deskCubbyPendingIntent(
                appWidgetId = appWidgetId,
                route = text.route,
                diaryUri = text.diaryUri,
                gameId = text.gameId,
            )
        }
        views.setOnClickPendingIntent(
            R.id.widget_root,
            clickIntent ?: deskCubbyPendingIntent(appWidgetId, NavItemId.WIDGETS.route),
        )
        return views
    }

    private suspend fun renderAppModule(
        appWidgetId: Int,
        config: DesktopWidgetConfig,
        settings: AppSettings,
    ): RemoteViews {
        val moduleId = config.homeModuleId
        val gameId = desktopGameIdForModule(moduleId)
        val views = if (gameId != null) {
            gameRenderer.render(appWidgetId, gameId, null, -1, settings)
        } else {
            appPanelRenderer.render(appWidgetId, moduleId, settings, config)
        } ?: RemoteViews(context.packageName, R.layout.desktop_widget_apps)
        val options = runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        }.getOrDefault(android.os.Bundle.EMPTY)
        val bounds = desktopWidgetBoundsDp(
            minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
            minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
            config = config,
        )
        applyWidgetSurfaceAppearance(views, R.id.widget_surface, config, bounds.width, bounds.height)
        val backgroundAlpha = config.backgroundOpacityPercent * 255 / 100
        views.setInt(
            R.id.widget_surface,
            "setBackgroundColor",
            config.backgroundColorArgb.withAlpha(backgroundAlpha),
        )
        views.setTextColor(R.id.widget_apps_title, config.textColorArgb)
        if (config.backgroundImageUri != null && gameId == null) {
            val widthDp = bounds.width
            val heightDp = bounds.height
            config.backgroundImageUri?.let { raw ->
                loadBackgroundBitmap(raw, widthDp, heightDp)?.let { bitmap ->
                    views.setImageViewBitmap(R.id.widget_apps_background_image, bitmap)
                    views.setInt(R.id.widget_apps_background_image, "setImageAlpha", backgroundAlpha)
                    views.setViewVisibility(R.id.widget_apps_background_image, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_apps_scrim, View.VISIBLE)
                    views.setInt(
                        R.id.widget_apps_scrim,
                        "setBackgroundColor",
                        0x52000000.withAlpha(0x52 * config.backgroundOpacityPercent / 100),
                    )
                }
            }
        }
        if (gameId != null) {
            views.setOnClickPendingIntent(
                R.id.widget_apps_root,
                deskCubbyPendingIntent(appWidgetId, NavItemId.GAMES.route),
            )
        }
        return views
    }

    private fun applyWidgetSurfaceAppearance(
        views: RemoteViews,
        rootId: Int,
        config: DesktopWidgetConfig,
        widthDp: Int,
        heightDp: Int,
    ) {
        val insets = desktopWidgetSurfaceInsetsDp(widthDp, heightDp, config.surfaceScalePercent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(RemoteViews.MARGIN_LEFT, RemoteViews.MARGIN_RIGHT).forEach { margin ->
                views.setViewLayoutMargin(
                    rootId,
                    margin,
                    insets.horizontal.toFloat(),
                    TypedValue.COMPLEX_UNIT_DIP,
                )
            }
            listOf(RemoteViews.MARGIN_TOP, RemoteViews.MARGIN_BOTTOM).forEach { margin ->
                views.setViewLayoutMargin(
                    rootId,
                    margin,
                    insets.vertical.toFloat(),
                    TypedValue.COMPLEX_UNIT_DIP,
                )
            }
            val radiusDp = if (config.cornerStyle == DesktopWidgetCornerStyle.ROUNDED) 22f else 0f
            views.setViewOutlinePreferredRadius(rootId, radiusDp, TypedValue.COMPLEX_UNIT_DIP)
        }
    }

    private fun configureAppShortcutContent(
        views: RemoteViews,
        config: DesktopWidgetConfig,
        text: DesktopWidgetText,
        icon: Bitmap?,
        actualWidthDp: Int,
        actualHeightDp: Int,
        gravity: Int,
        textScale: Float,
    ) {
        views.setViewVisibility(R.id.widget_foreground, View.GONE)
        views.setViewVisibility(R.id.widget_app_shortcut_content, View.VISIBLE)
        val surfaceInsets = desktopWidgetSurfaceInsetsDp(
            actualWidthDp,
            actualHeightDp,
            config.surfaceScalePercent,
        )
        val iconSizeDp = desktopAppIconSizeDp(config.appIconScalePercent)
        val showLauncherIcon = shouldShowDesktopAppIcon(
            requested = config.showIcon,
            availableWidthDp = (actualWidthDp - surfaceInsets.horizontal * 2).coerceAtLeast(0),
            availableHeightDp = (actualHeightDp - surfaceInsets.vertical * 2).coerceAtLeast(0),
            iconLoaded = icon != null,
            iconSizeDp = iconSizeDp,
        )
        views.setViewVisibility(
            R.id.widget_app_shortcut_icon,
            if (showLauncherIcon) View.VISIBLE else View.GONE,
        )
        if (showLauncherIcon && icon != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                views.setViewLayoutWidth(
                    R.id.widget_app_shortcut_icon,
                    iconSizeDp.toFloat(),
                    TypedValue.COMPLEX_UNIT_DIP,
                )
                views.setViewLayoutHeight(
                    R.id.widget_app_shortcut_icon,
                    iconSizeDp.toFloat(),
                    TypedValue.COMPLEX_UNIT_DIP,
                )
            }
            views.setImageViewBitmap(R.id.widget_app_shortcut_icon, icon)
            views.setContentDescription(R.id.widget_app_shortcut_icon, text.value)
        }
        views.setTextViewText(R.id.widget_app_shortcut_fallback, text.value)
        views.setTextColor(R.id.widget_app_shortcut_fallback, config.textColorArgb)
        views.setInt(R.id.widget_app_shortcut_fallback, "setGravity", gravity)
        views.setTextViewTextSize(
            R.id.widget_app_shortcut_fallback,
            TypedValue.COMPLEX_UNIT_SP,
            16f * textScale,
        )
        views.setViewVisibility(
            R.id.widget_app_shortcut_fallback,
            if (showLauncherIcon) View.GONE else View.VISIBLE,
        )
        views.setTextViewText(R.id.widget_app_shortcut_name, config.name.ifBlank { text.title })
        views.setTextColor(R.id.widget_app_shortcut_name, config.textColorArgb)
        views.setInt(R.id.widget_app_shortcut_name, "setGravity", gravity)
        views.setTextViewTextSize(
            R.id.widget_app_shortcut_name,
            TypedValue.COMPLEX_UNIT_SP,
            12f * textScale,
        )
        views.setViewVisibility(
            R.id.widget_app_shortcut_name,
            if (config.showName) View.VISIBLE else View.GONE,
        )
    }

    private fun homeModuleText(
        moduleId: String,
        settings: AppSettings,
        snapshot: DesktopWidgetContentSnapshot,
    ): DesktopWidgetText {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val today = LocalDate.now()
        return when (moduleId) {
            "calendar" -> DesktopWidgetText(
                localized(settings, "日历", "Calendar"),
                today.dayOfMonth.toString(),
                today.format(DateTimeFormatter.ofPattern(translate("yyyy年M月", "MMMM yyyy", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE))),
            )
            "weather" -> DesktopWidgetText(
                localized(settings, "天气缓存", "Weather cache"),
                localized(settings, "离线模式", "Offline"),
                localized(settings, "暂无上次天气缓存", "No cached weather"),
            )
            "poem" -> DesktopWidgetText(
                localized(settings, "每日诗词", "Daily poem"),
                snapshot.poemContent,
                snapshot.poemSource,
            )
            "today" -> DesktopWidgetText(
                localized(settings, "今天", "Today"),
                today.format(
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(
                        if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE,
                    ),
                ),
            )
            "date_records" -> DesktopWidgetText(
                localized(settings, "日期记录", "Date records"),
                translate("${snapshot.dateRecords.size} 条记录", "${snapshot.dateRecords.size} records", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE),
                route = NavItemId.DATE.route,
            )
            "streak" -> DesktopWidgetText(
                localized(settings, "连续记录", "Writing streak"),
                translate("${streakDays(snapshot.diaries, today)} 天", "${streakDays(snapshot.diaries, today)} days", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE),
                route = NavItemId.DIARY.route,
            )
            "month_diaries" -> {
                val count = snapshot.diaries.count { it.dateIso.startsWith(today.toString().take(7)) }
                DesktopWidgetText(
                    localized(settings, "本月日记", "Diaries this month"),
                    translate("$count 篇", "$count entries", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE),
                    route = NavItemId.DIARY.route,
                )
            }
            "total_words" -> DesktopWidgetText(
                localized(settings, "日记总字数", "Total diary words"),
                snapshot.diaries.sumOf(DiaryIndexEntity::wordCount).toString(),
                route = NavItemId.DIARY.route,
            )
            "recent_diary" -> DesktopWidgetText(
                localized(settings, "最近日记", "Recent diary"),
                snapshot.diaries.firstOrNull()?.title
                    ?: localized(settings, "还没有日记", "No diaries yet"),
                snapshot.diaries.firstOrNull()?.dateIso.orEmpty(),
                NavItemId.DIARY.route,
                diaryUri = snapshot.diaries.firstOrNull()?.uri,
            )
            "recent_thought" -> DesktopWidgetText(
                localized(settings, "最近小巧思", "Recent thought"),
                snapshot.recentThoughts.firstOrNull()?.content
                    ?: localized(settings, "还没有小巧思", "No thoughts yet"),
                route = NavItemId.THOUGHT.route,
            )
            "quick_input" -> DesktopWidgetText(
                localized(settings, "快速输入", "Quick input"),
                localized(settings, "点按打开记录", "Tap to capture a thought"),
                route = NavItemId.THOUGHT.route,
            )
            "daily_records" -> DesktopWidgetText(
                localized(settings, "结构化记录", "Structured records"),
                translate("${settings.dailyEventTemplates.size} 个模板", "${settings.dailyEventTemplates.size} templates", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE),
                route = Routes.DAILY_RECORDS_TODAY,
            )
            "meal_photos" -> DesktopWidgetText(
                localized(settings, "饮食图片", "Meal photos"),
                localized(settings, "点按打开日记", "Tap to open Diary"),
                route = NavItemId.DIARY.route,
            )
            "random_diary" -> {
                DesktopWidgetText(
                    localized(settings, "随机旧日记", "Random old diary"),
                    snapshot.randomDiary?.title
                        ?: localized(settings, "还没有日记", "No diaries yet"),
                    route = NavItemId.DIARY.route,
                    diaryUri = snapshot.randomDiary?.uri,
                )
            }
            "year_progress" -> {
                val total = if (today.isLeapYear) 366 else 365
                val percent = today.dayOfYear * 100 / total
                DesktopWidgetText(
                    localized(settings, "年度进度", "Year progress"),
                    "$percent%",
                    translate("第 ${today.dayOfYear} / $total 天", "Day ${today.dayOfYear} / $total", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE),
                )
            }
            "website" -> DesktopWidgetText(
                localized(settings, "网站快捷入口", "Website shortcut"),
                settings.browserHomeUrl.trim().ifBlank {
                    localized(settings, "尚未设置网址", "No website configured")
                },
                route = NavItemId.BLOG.route,
            )
            "notes" -> DesktopWidgetText(
                localized(settings, "笔记", "Notes"),
                if (settings.notesTreeUri == null) {
                    localized(settings, "请先选择笔记库", "Choose a notes vault first")
                } else {
                    localized(settings, "打开 Markdown 笔记库", "Open Markdown notes vault")
                },
                route = NavItemId.NOTES.route,
            )
            "game_shortcuts" -> {
                val labels = settings.homeGameShortcuts.mapNotNull { id ->
                    gameShortcutLabel(id, settings.appLanguage == AppLanguage.ENGLISH)
                }
                DesktopWidgetText(
                    localized(settings, "小游戏", "Mini games"),
                    labels.take(3).joinToString(" · ").ifBlank {
                        localized(settings, "还没有快捷游戏", "No game shortcuts")
                    },
                    if (labels.size > 3) "+${labels.size - 3}" else "",
                    route = NavItemId.GAMES.route,
                )
            }
            "record_overview" -> DesktopWidgetText(
                localized(settings, "记录概览", "Record overview"),
                translate("${snapshot.diaries.size} 篇日记 · ${snapshot.thoughtCount} 条小巧思", "${snapshot.diaries.size} diaries · ${snapshot.thoughtCount} thoughts", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE),
                translate("${snapshot.dateRecords.size} 条日期记录", "${snapshot.dateRecords.size} date records", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE),
                route = NavItemId.STATISTICS.route,
            )
            else -> DesktopWidgetText(
                localized(settings, "小卡片", "Widget"),
                localized(settings, "点按打开 DeskCubby", "Tap to open DeskCubby"),
            )
        }
    }

    private fun appShortcutText(
        config: DesktopWidgetConfig,
        settings: AppSettings,
    ): DesktopWidgetText = DesktopWidgetText(
        title = localized(settings, "应用启动", "App launcher"),
        value = config.appLabel
            ?: config.appPackageName
            ?: localized(settings, "未选择应用", "No app selected"),
        detail = localized(settings, "点按启动", "Tap to launch"),
        route = NavItemId.WIDGETS.route,
    )

    private fun configureInteractions(
        views: RemoteViews,
        appWidgetId: Int,
        mode: DesktopWidgetInteractionMode,
        config: DesktopWidgetConfig,
        settings: AppSettings,
        snapshot: DesktopWidgetContentSnapshot,
        actualWidth: Int,
        actualHeight: Int,
    ) {
        val containers = listOf(
            R.id.widget_poem_actions,
            R.id.widget_quick_input_actions,
            R.id.widget_meal_actions_wide,
            R.id.widget_meal_actions_3_by_2,
            R.id.widget_meal_actions_2_by_3,
            R.id.widget_calendar_grid,
            R.id.widget_module_list,
            R.id.widget_cloud_status,
            R.id.widget_year_progress,
        )
        containers.forEach { views.setViewVisibility(it, View.GONE) }

        val actionIds = listOf(
            R.id.widget_poem_refresh,
            R.id.widget_poem_save,
            R.id.widget_quick_input_field,
            R.id.widget_module_row_1,
            R.id.widget_module_row_2,
            R.id.widget_module_row_3,
            R.id.widget_module_row_4,
            R.id.widget_module_row_5,
            R.id.widget_module_row_6,
            R.id.widget_module_row_7,
            R.id.widget_module_row_8,
            R.id.widget_module_footer_primary,
            R.id.widget_module_footer_secondary,
        )
        actionIds.forEach { id ->
            views.setTextColor(id, config.textColorArgb)
            views.setInt(id, "setBackgroundColor", config.textColorArgb.withAlpha(0x33))
        }

        when (mode) {
            DesktopWidgetInteractionMode.NAVIGATION_ONLY -> Unit
            DesktopWidgetInteractionMode.POEM_ACTIONS -> {
                views.setViewVisibility(R.id.widget_poem_actions, View.VISIBLE)
                views.setViewVisibility(R.id.widget_detail, View.GONE)
                // Keep the poem text itself visible next to the actions; a 4x2 card must show
                // both the poem and its refresh/save buttons (regression fix).
                views.setViewVisibility(R.id.widget_value, View.VISIBLE)
                bindAction(
                    views,
                    R.id.widget_poem_refresh,
                    localized(settings, "刷新", "Refresh"),
                    workerActionPendingIntent(appWidgetId, DesktopWidgetWorkerAction.REFRESH_POEM),
                )
                bindAction(
                    views,
                    R.id.widget_poem_save,
                    localized(settings, "加入诗词本", "Save poem"),
                    workerActionPendingIntent(appWidgetId, DesktopWidgetWorkerAction.SAVE_POEM),
                )
            }
            DesktopWidgetInteractionMode.QUICK_INPUT -> {
                views.setViewVisibility(R.id.widget_quick_input_actions, View.VISIBLE)
                views.setViewVisibility(R.id.widget_content_row, View.GONE)
                views.setViewVisibility(R.id.widget_value, View.GONE)
                views.setViewVisibility(R.id.widget_detail, View.GONE)
                val pendingIntent = DesktopWidgetInteractionActivity.quickInputPendingIntent(
                    context,
                    appWidgetId,
                )
                bindAction(
                    views,
                    R.id.widget_quick_input_field,
                    thoughtDraftStore.get(appWidgetId)
                        .ifBlank { localized(settings, "点按输入小巧思", "Tap to type a thought") },
                    pendingIntent,
                )
                views.setInt(R.id.widget_quick_input_send, "setBackgroundColor", Color.TRANSPARENT)
                views.setInt(R.id.widget_quick_input_send, "setColorFilter", config.textColorArgb)
                views.setContentDescription(
                    R.id.widget_quick_input_send,
                    localized(settings, "发送到未分类", "Send to uncategorized"),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_quick_input_send,
                    DesktopWidgetQuickThoughtReceiver.sendPendingIntent(context, appWidgetId),
                )
            }
            DesktopWidgetInteractionMode.MEAL_ACTIONS_WIDE,
            DesktopWidgetInteractionMode.MEAL_ACTIONS_3_BY_2,
            DesktopWidgetInteractionMode.MEAL_ACTIONS_2_BY_3,
            -> {
                views.setViewVisibility(R.id.widget_content_row, View.GONE)
                views.setViewVisibility(R.id.widget_value, View.GONE)
                views.setViewVisibility(R.id.widget_detail, View.GONE)
                val groupIndex = when (mode) {
                    DesktopWidgetInteractionMode.MEAL_ACTIONS_WIDE -> 0
                    DesktopWidgetInteractionMode.MEAL_ACTIONS_3_BY_2 -> 1
                    else -> 2
                }
                val containerId = listOf(
                    R.id.widget_meal_actions_wide,
                    R.id.widget_meal_actions_3_by_2,
                    R.id.widget_meal_actions_2_by_3,
                )[groupIndex]
                views.setViewVisibility(containerId, View.VISIBLE)
                MealCategory.entries.zip(MEAL_ACTION_VIEW_IDS[groupIndex]).forEachIndexed {
                        index,
                        (category, viewId),
                    ->
                    val configuredIcon = settings.mealButtonIcons.getOrNull(index)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                    val visibleLabel = configuredIcon ?: category.defaultIcon
                    val description = if (settings.appLanguage == AppLanguage.ENGLISH) {
                        "Add ${category.englishLabel} photo"
                    } else {
                        "添加${category.chineseLabel}图片"
                    }
                    bindAction(
                        views,
                        viewId,
                        visibleLabel,
                        DesktopWidgetInteractionActivity.mealPhotoPendingIntent(
                            context,
                            appWidgetId,
                            category,
                        ),
                        description,
                    )
                    views.setInt(viewId, "setBackgroundColor", Color.TRANSPARENT)
                }
            }
        }
        configureExpandedModule(
            views = views,
            appWidgetId = appWidgetId,
            moduleId = config.homeModuleId,
            expandedMode = DesktopWidgetInteractionPolicy.expandedMode(
                config.homeModuleId,
                actualWidth,
                actualHeight,
            ),
            config = config,
            settings = settings,
            snapshot = snapshot,
        )
    }

    private fun bindAction(
        views: RemoteViews,
        viewId: Int,
        label: String,
        pendingIntent: PendingIntent,
        description: String = label,
    ) {
        views.setTextViewText(viewId, label)
        views.setContentDescription(viewId, description)
        views.setOnClickPendingIntent(viewId, pendingIntent)
    }

    private fun bindOptionalAction(
        views: RemoteViews,
        viewId: Int,
        label: String,
        pendingIntent: PendingIntent?,
    ) {
        views.setTextViewText(viewId, label)
        views.setContentDescription(viewId, label)
        views.setBoolean(viewId, "setEnabled", pendingIntent != null)
        pendingIntent?.let { views.setOnClickPendingIntent(viewId, it) }
    }

    private fun configureExpandedModule(
        views: RemoteViews,
        appWidgetId: Int,
        moduleId: String,
        expandedMode: ExpandedWidgetMode,
        config: DesktopWidgetConfig,
        settings: AppSettings,
        snapshot: DesktopWidgetContentSnapshot,
    ) {
        when (expandedMode) {
            ExpandedWidgetMode.NONE -> Unit
            ExpandedWidgetMode.CALENDAR -> bindCalendar(views, config, settings)
            ExpandedWidgetMode.DATE_RECORDS_ONE -> bindDateRecordsCompact(
                views,
                appWidgetId,
                settings,
                snapshot,
                maxRows = 1,
            )
            ExpandedWidgetMode.DATE_RECORDS_TWO -> bindDateRecordsCompact(
                views,
                appWidgetId,
                settings,
                snapshot,
                maxRows = 2,
            )
            ExpandedWidgetMode.FOUR_ROW_LIST -> bindExpandedList(
                views,
                appWidgetId,
                moduleId,
                settings,
                snapshot,
                maxRows = 4,
            )
            ExpandedWidgetMode.EIGHT_ROW_LIST -> bindExpandedList(
                views,
                appWidgetId,
                moduleId,
                settings,
                snapshot,
                maxRows = 8,
            )
            ExpandedWidgetMode.YEAR_PROGRESS -> {
                val today = LocalDate.now()
                val total = if (today.isLeapYear) 366 else 365
                views.setViewVisibility(R.id.widget_year_progress, View.VISIBLE)
                views.setProgressBar(
                    R.id.widget_year_progress,
                    total,
                    today.dayOfYear,
                    false,
                )
                views.setContentDescription(
                    R.id.widget_year_progress,
                    localized(
                        settings,
                        "年度进度 ${today.dayOfYear} / $total",
                        "Year progress ${today.dayOfYear} / $total",
                    ),
                )
            }
        }
    }

    private fun bindCalendar(
        views: RemoteViews,
        config: DesktopWidgetConfig,
        settings: AppSettings,
    ) {
        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val firstOffset = month.atDay(1).dayOfWeek.value - 1
        val monthLabel = today.format(
            DateTimeFormatter.ofPattern(if (settings.appLanguage == AppLanguage.ENGLISH) "MMMM yyyy" else "yyyy年M月")
                .withLocale(if (settings.appLanguage == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE),
        )
        val headers = if (settings.appLanguage == AppLanguage.ENGLISH) {
            listOf("M", "T", "W", "T", "F", "S", "S")
        } else {
            listOf("一", "二", "三", "四", "五", "六", "日")
        }
        views.setViewVisibility(R.id.widget_calendar_grid, View.VISIBLE)
        views.setViewVisibility(R.id.widget_value, View.GONE)
        views.setViewVisibility(R.id.widget_detail, View.GONE)
        views.setViewVisibility(R.id.widget_icon, View.GONE)
        views.setTextViewText(R.id.widget_calendar_month, monthLabel)
        views.setTextColor(R.id.widget_calendar_month, config.textColorArgb)
        views.setContentDescription(R.id.widget_calendar_month, monthLabel)
        CALENDAR_HEADER_VIEW_IDS.forEachIndexed { index, viewId ->
            views.setTextViewText(viewId, headers[index])
            views.setTextColor(viewId, config.textColorArgb)
            views.setInt(viewId, "setBackgroundColor", 0x00000000)
        }
        CALENDAR_DAY_VIEW_IDS.forEachIndexed { index, viewId ->
            val day = index - firstOffset + 1
            val inMonth = day in 1..month.lengthOfMonth()
            views.setTextViewText(viewId, if (inMonth) day.toString() else "")
            views.setTextColor(viewId, config.textColorArgb)
            views.setInt(
                viewId,
                "setBackgroundColor",
                if (inMonth && day == today.dayOfMonth) {
                    config.textColorArgb.withAlpha(0x33)
                } else {
                    0x00000000
                },
            )
            views.setContentDescription(
                viewId,
                if (inMonth) month.atDay(day).toString() else "",
            )
        }
    }

    /**
     * Compact date-records rows: 3x1/4x1 shows the single nearest record plus an add button;
     * 3x2/4x2 shows the two nearest records.
     */
    private fun bindDateRecordsCompact(
        views: RemoteViews,
        appWidgetId: Int,
        settings: AppSettings,
        snapshot: DesktopWidgetContentSnapshot,
        maxRows: Int,
    ) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val records = nearestDesktopDateRecords(snapshot.dateRecords, LocalDate.now()).take(maxRows)
        views.setViewVisibility(R.id.widget_module_list, View.VISIBLE)
        views.setViewVisibility(R.id.widget_value, View.GONE)
        views.setViewVisibility(R.id.widget_detail, View.GONE)
        views.setViewVisibility(R.id.widget_icon, View.GONE)
        MODULE_ROW_VIEW_IDS.forEachIndexed { index, viewId ->
            val record = records.getOrNull(index)
            if (record != null) {
                val date = runCatching { LocalDate.parse(record.dateIso) }.getOrNull()
                val days = date?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
                val distance = when {
                    days == null -> ""
                    days < 0 -> if (english) (-days).toString() + "d ago" else "已过去 " + (-days) + " 天"
                    days > 0 -> if (english) "in " + days + "d" else "还有 " + days + " 天"
                    else -> if (english) "today" else "就是今天"
                }
                bindAction(
                    views,
                    viewId,
                    (record.icon.ifBlank { "🎯" } + " " + record.name + " · " + distance).take(60),
                    deskCubbyPendingIntent(appWidgetId, NavItemId.DATE.route),
                )
                views.setViewVisibility(viewId, View.VISIBLE)
            } else {
                views.setViewVisibility(viewId, View.GONE)
            }
        }
        bindAction(
            views,
            R.id.widget_module_footer_primary,
            localized(settings, "添加", "Add"),
            DesktopWidgetInteractionActivity.dateRecordAddPendingIntent(context, appWidgetId),
        )
        bindAction(
            views,
            R.id.widget_module_footer_secondary,
            localized(settings, "查看全部", "View all"),
            deskCubbyPendingIntent(appWidgetId, NavItemId.DATE.route),
        )
    }

    private fun bindExpandedList(
        views: RemoteViews,
        appWidgetId: Int,
        moduleId: String,
        settings: AppSettings,
        snapshot: DesktopWidgetContentSnapshot,
        maxRows: Int,
    ) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val rows: List<ExpandedWidgetRow>
        val primary: ExpandedWidgetRow
        var secondary: ExpandedWidgetRow? = null
        when (moduleId) {
            "date_records" -> {
                rows = nearestDesktopDateRecords(snapshot.dateRecords, LocalDate.now()).map { record ->
                    val date = LocalDate.parse(record.dateIso)
                    val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
                    val distance = when {
                        days < 0 -> translate("已过去 ${-days} 天", "${-days}d since", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
                        days > 0 -> translate("还有 $days 天", "in ${days}d", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
                        else -> translate("就是今天", "today", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
                    }
                    ExpandedWidgetRow(
                        "${record.icon.ifBlank { "🎯" }} ${record.name} · $distance",
                        deskCubbyPendingIntent(appWidgetId, NavItemId.DATE.route),
                    )
                }
                primary = ExpandedWidgetRow(
                    localized(settings, "添加", "Add"),
                    DesktopWidgetInteractionActivity.dateRecordAddPendingIntent(context, appWidgetId),
                )
                secondary = ExpandedWidgetRow(
                    localized(settings, "查看全部", "View all"),
                    deskCubbyPendingIntent(appWidgetId, NavItemId.DATE.route),
                )
            }
            "recent_diary" -> {
                rows = snapshot.diaries.take(4).map { diary ->
                    ExpandedWidgetRow(
                        "${diary.title.ifBlank { diary.name.removeSuffix(".md") }} · ${diary.dateIso}",
                        deskCubbyPendingIntent(
                            appWidgetId,
                            NavItemId.DIARY.route,
                            diaryUri = diary.uri,
                            identitySuffix = "diary-${diary.uri.hashCode()}",
                        ),
                    )
                }
                primary = ExpandedWidgetRow(
                    localized(settings, "查看全部", "View all"),
                    deskCubbyPendingIntent(appWidgetId, NavItemId.DIARY.route),
                )
            }
            "recent_thought" -> {
                rows = snapshot.recentThoughts.take(4).map { thought ->
                    ExpandedWidgetRow(
                        thought.content.replace(Regex("\\s+"), " ").trim(),
                        DesktopWidgetInteractionActivity.thoughtPendingIntent(
                            context,
                            appWidgetId,
                            thought.id,
                        ),
                    )
                }
                primary = ExpandedWidgetRow(
                    localized(settings, "查看全部", "View all"),
                    deskCubbyPendingIntent(appWidgetId, NavItemId.THOUGHT.route),
                )
            }
            "daily_records" -> {
                rows = settings.dailyEventTemplates.take(4).map { template ->
                    ExpandedWidgetRow(
                        template.text.replace(Regex("\\s+"), " ").trim(),
                        DesktopWidgetInteractionActivity.dailyRecordPendingIntent(
                            context,
                            appWidgetId,
                            template.id,
                        ),
                    )
                }
                primary = ExpandedWidgetRow(
                    localized(settings, "管理结构化记录", "Manage structured records"),
                    deskCubbyPendingIntent(appWidgetId, Routes.DAILY_RECORDS_TODAY),
                )
            }
            "game_shortcuts" -> {
                rows = settings.homeGameShortcuts.take(maxRows).mapNotNull { gameId ->
                    gameShortcutLabel(gameId, english)?.let { label ->
                        ExpandedWidgetRow(
                            label,
                            deskCubbyPendingIntent(
                                appWidgetId,
                                NavItemId.GAMES.route,
                                gameId = gameId,
                                identitySuffix = "game-$gameId",
                            ),
                        )
                    }
                }
                primary = ExpandedWidgetRow(
                    localized(settings, "全部游戏", "All games"),
                    deskCubbyPendingIntent(appWidgetId, NavItemId.GAMES.route),
                )
            }
            else -> return
        }
        views.setViewVisibility(R.id.widget_module_list, View.VISIBLE)
        views.setViewVisibility(R.id.widget_value, View.GONE)
        views.setViewVisibility(R.id.widget_detail, View.GONE)
        views.setViewVisibility(R.id.widget_icon, View.GONE)
        MODULE_ROW_VIEW_IDS.forEachIndexed { index, viewId ->
            val row = rows.getOrNull(index)
            views.setViewVisibility(
                viewId,
                if (
                    DesktopWidgetInteractionPolicy.shouldShowExpandedRow(
                        index,
                        rows.size,
                        maxRows,
                    )
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                },
            )
            row?.let { bindAction(views, viewId, it.label, it.pendingIntent) }
        }
        bindAction(views, R.id.widget_module_footer_primary, primary.label, primary.pendingIntent)
        views.setViewVisibility(
            R.id.widget_module_footer_secondary,
            if (secondary == null) View.GONE else View.VISIBLE,
        )
        secondary?.let {
            bindAction(views, R.id.widget_module_footer_secondary, it.label, it.pendingIntent)
        }
    }

    private fun bindCloudStatus(
        views: RemoteViews,
        config: DesktopWidgetConfig,
        settings: AppSettings,
        status: AppCloudSyncStatus,
        queuedMode: CloudSyncRunMode?,
    ) {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val progress = status.progress
        val state = when {
            status.running && progress != null && progress.totalObjects > 0 -> translate("正在同步 ${progress.completedObjects}/${progress.totalObjects}", "Syncing ${progress.completedObjects}/${progress.totalObjects}", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
            status.running -> localized(settings, "正在同步", "Syncing")
            queuedMode != null -> when (queuedMode) {
                CloudSyncRunMode.NORMAL -> localized(settings, "立即同步已排队", "Sync now queued")
                CloudSyncRunMode.FORCE_UPLOAD -> localized(settings, "强制上传已排队", "Force upload queued")
                CloudSyncRunMode.FORCE_DOWNLOAD -> localized(settings, "强制下载已排队", "Force download queued")
            }
            !settings.cloudSyncEnabled -> localized(settings, "云同步尚未开启", "Cloud sync is off")
            settings.cloudSyncConfigs.none { it.enabled } ->
                localized(settings, "没有已启用来源", "No enabled source")
            else -> localized(settings, "已就绪", "Ready")
        }
        val lastFinished = status.lastFinishedAt?.let { timestamp ->
            val locale = if (english) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
            val formatted = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT,
                locale,
            ).format(Date(timestamp))
            translate("上次完成：$formatted", "Last completed: $formatted", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        } ?: localized(settings, "尚无完成记录", "No completed run yet")
        val result = status.error ?: status.message
            ?: localized(settings, "没有错误", "No error")
        val rows = listOf(state, lastFinished, result.safeWidgetLine(), "")
        views.setViewVisibility(R.id.widget_cloud_status, View.VISIBLE)
        views.setViewVisibility(R.id.widget_value, View.GONE)
        views.setViewVisibility(R.id.widget_detail, View.GONE)
        views.setViewVisibility(R.id.widget_icon, View.GONE)
        CLOUD_STATUS_VIEW_IDS.forEachIndexed { index, viewId ->
            views.setTextViewText(viewId, rows[index])
            views.setTextColor(viewId, config.textColorArgb)
            views.setContentDescription(viewId, rows[index])
        }
    }

    private fun cloudActionCanRun(
        settings: AppSettings,
        status: AppCloudSyncStatus,
        queuedMode: CloudSyncRunMode?,
    ): Boolean = DesktopWidgetInteractionPolicy.cloudActionCanRun(
        syncEnabled = settings.cloudSyncEnabled,
        enabledSourceCount = settings.cloudSyncConfigs.count { it.enabled },
        running = status.running,
        queued = queuedMode != null,
    )

    private fun workerActionPendingIntent(
        appWidgetId: Int,
        action: DesktopWidgetWorkerAction,
    ): PendingIntent {
        val identity = "$appWidgetId/${action.key}"
        return PendingIntent.getBroadcast(
            context,
            identity.hashCode(),
            Intent(context, DesktopWidgetActionReceiver::class.java)
                .setAction(action.intentAction)
                .setData(Uri.parse("deskcubby://widget-action/$identity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cloudSyncPendingIntent(appWidgetId: Int, action: String): PendingIntent {
        val identity = "$appWidgetId/$action"
        return PendingIntent.getBroadcast(
            context,
            identity.hashCode(),
            Intent(context, CloudSyncWidgetActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("deskcubby://widget-cloud/$identity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cloudSyncAvailability(settings: AppSettings): String = when {
        !settings.cloudSyncEnabled -> localized(settings, "云同步总开关未开启", "Cloud sync is disabled")
        settings.cloudSyncConfigs.none { it.enabled } ->
            localized(settings, "没有已启用来源", "No enabled source")
        else -> localized(settings, "已准备好", "Ready")
    }

    private fun gameShortcutLabel(id: String, english: Boolean): String? = when (id) {
        "2048" -> "2048 · 4×4"
        "2048_5" -> "2048 · 5×5"
        "2048_6" -> "2048 · 6×6"
        "snake" -> translate("贪吃蛇", "Snake", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "tetris" -> translate("俄罗斯方块", "Tetris", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "minesweeper" -> translate("扫雷", "Minesweeper", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "spider" -> translate("蜘蛛纸牌", "Spider", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "go" -> translate("围棋", "Go", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        else -> null
    }

    private fun deskCubbyPendingIntent(
        appWidgetId: Int,
        route: String,
        diaryUri: String? = null,
        gameId: String? = null,
        identitySuffix: String = route,
    ): PendingIntent {
        if (!diaryUri.isNullOrBlank()) {
            return DesktopWidgetInteractionActivity.diaryPendingIntent(
                context,
                appWidgetId,
                diaryUri,
            )
        }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_START_ROUTE, route)
            .putExtra(EXTRA_GAME_ID, gameId)
            .setData(Uri.parse("deskcubby://widget-navigation/$appWidgetId/${Uri.encode(identitySuffix)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            "$appWidgetId/$identitySuffix".hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun configurePendingIntent(appWidgetId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        appWidgetId,
        Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setClass(context, DeskCubbyWidgetConfigureActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun loadBackgroundBitmap(rawUri: String, widthDp: Int, heightDp: Int): Bitmap? =
        runCatching {
            val uri = Uri.parse(rawUri)
            val descriptorLength = context.contentResolver
                .openAssetFileDescriptor(uri, "r")
                ?.use { it.length }
                ?: -1L
            if (descriptorLength > MAX_BACKGROUND_SOURCE_BYTES) return@runCatching null

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val density = context.resources.displayMetrics.density
            val targetWidth = (widthDp * density).toInt().coerceIn(64, MAX_BACKGROUND_EDGE_PX)
            val targetHeight = (heightDp * density).toInt().coerceIn(64, MAX_BACKGROUND_EDGE_PX)
            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= targetWidth &&
                bounds.outHeight / (sampleSize * 2) >= targetHeight
            ) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }?.let { decoded ->
                val scale = minOf(
                    MAX_BACKGROUND_EDGE_PX.toFloat() / decoded.width,
                    MAX_BACKGROUND_EDGE_PX.toFloat() / decoded.height,
                    1f,
                )
                if (scale >= 1f) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { if (it !== decoded) decoded.recycle() }
                }
            }
        }.getOrNull()

    private fun localized(settings: AppSettings, chinese: String, english: String): String =
        if (settings.appLanguage == AppLanguage.ENGLISH) english else chinese

    private suspend fun <T> loadOrNull(block: suspend () -> T): T? = try {
        withTimeout(DATA_SOURCE_TIMEOUT_MS) { block() }
    } catch (_: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun streakDays(diaries: List<DiaryIndexEntity>, today: LocalDate): Int {
        val dates = diaries.mapNotNull { runCatching { LocalDate.parse(it.dateIso) }.getOrNull() }
            .toSet()
        var cursor = if (today in dates) today else today.minusDays(1)
        var count = 0
        while (cursor in dates) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    private data class DesktopWidgetContentSnapshot(
        val diaries: List<DiaryIndexEntity>,
        val recentThoughts: List<FlashThoughtEntity>,
        val thoughtCount: Int,
        val dateRecords: List<DateRecordEntity>,
        val poemContent: String,
        val poemSource: String,
        val randomDiary: DiaryIndexEntity?,
        val cloudStatus: AppCloudSyncStatus,
        val queuedCloudMode: CloudSyncRunMode?,
    )

    private data class ExpandedWidgetRow(
        val label: String,
        val pendingIntent: PendingIntent,
    )

    companion object {
        val APP_PANEL_MODULE_IDS = setOf(
            "reader",
            "music_visualizer",
            "usage_overview",
            "usage_chart",
            "usage_apps",
            "cloud_sync",
        )
        const val EXTRA_START_ROUTE = "com.deskcubby.app.extra.START_ROUTE"
        const val EXTRA_DIARY_TOKEN = "com.deskcubby.app.extra.DIARY_TOKEN"
        const val EXTRA_GAME_ID = "com.deskcubby.app.extra.GAME_ID"
        private const val APPROX_CELL_DP = 70
        private const val COMPACT_WIDTH_DP = 120
        private const val COMPACT_HEIGHT_DP = 90
        private const val ICON_MIN_WIDTH_DP = 100
        private const val MAX_BACKGROUND_EDGE_PX = 384
        private const val MAX_BACKGROUND_SOURCE_BYTES = 32L * 1024L * 1024L
        private const val DATA_SOURCE_TIMEOUT_MS = 2_500L
        private val CALENDAR_HEADER_VIEW_IDS = listOf(
            R.id.widget_calendar_mon,
            R.id.widget_calendar_tue,
            R.id.widget_calendar_wed,
            R.id.widget_calendar_thu,
            R.id.widget_calendar_fri,
            R.id.widget_calendar_sat,
            R.id.widget_calendar_sun,
        )
        private val CALENDAR_DAY_VIEW_IDS = listOf(
            R.id.widget_calendar_day_1, R.id.widget_calendar_day_2,
            R.id.widget_calendar_day_3, R.id.widget_calendar_day_4,
            R.id.widget_calendar_day_5, R.id.widget_calendar_day_6,
            R.id.widget_calendar_day_7, R.id.widget_calendar_day_8,
            R.id.widget_calendar_day_9, R.id.widget_calendar_day_10,
            R.id.widget_calendar_day_11, R.id.widget_calendar_day_12,
            R.id.widget_calendar_day_13, R.id.widget_calendar_day_14,
            R.id.widget_calendar_day_15, R.id.widget_calendar_day_16,
            R.id.widget_calendar_day_17, R.id.widget_calendar_day_18,
            R.id.widget_calendar_day_19, R.id.widget_calendar_day_20,
            R.id.widget_calendar_day_21, R.id.widget_calendar_day_22,
            R.id.widget_calendar_day_23, R.id.widget_calendar_day_24,
            R.id.widget_calendar_day_25, R.id.widget_calendar_day_26,
            R.id.widget_calendar_day_27, R.id.widget_calendar_day_28,
            R.id.widget_calendar_day_29, R.id.widget_calendar_day_30,
            R.id.widget_calendar_day_31, R.id.widget_calendar_day_32,
            R.id.widget_calendar_day_33, R.id.widget_calendar_day_34,
            R.id.widget_calendar_day_35, R.id.widget_calendar_day_36,
            R.id.widget_calendar_day_37, R.id.widget_calendar_day_38,
            R.id.widget_calendar_day_39, R.id.widget_calendar_day_40,
            R.id.widget_calendar_day_41, R.id.widget_calendar_day_42,
        )
        private val MODULE_ROW_VIEW_IDS = listOf(
            R.id.widget_module_row_1,
            R.id.widget_module_row_2,
            R.id.widget_module_row_3,
            R.id.widget_module_row_4,
            R.id.widget_module_row_5,
            R.id.widget_module_row_6,
            R.id.widget_module_row_7,
            R.id.widget_module_row_8,
        )
        private val CLOUD_STATUS_VIEW_IDS = listOf(
            R.id.widget_cloud_status_1,
            R.id.widget_cloud_status_2,
            R.id.widget_cloud_status_3,
            R.id.widget_cloud_status_4,
        )
        private val MEAL_ACTION_VIEW_IDS = listOf(
            listOf(
                R.id.widget_meal_wide_breakfast,
                R.id.widget_meal_wide_lunch,
                R.id.widget_meal_wide_afternoon_tea,
                R.id.widget_meal_wide_dinner,
                R.id.widget_meal_wide_fruit,
                R.id.widget_meal_wide_late_snack,
            ),
            listOf(
                R.id.widget_meal_3x2_breakfast,
                R.id.widget_meal_3x2_lunch,
                R.id.widget_meal_3x2_afternoon_tea,
                R.id.widget_meal_3x2_dinner,
                R.id.widget_meal_3x2_fruit,
                R.id.widget_meal_3x2_late_snack,
            ),
            listOf(
                R.id.widget_meal_2x3_breakfast,
                R.id.widget_meal_2x3_lunch,
                R.id.widget_meal_2x3_afternoon_tea,
                R.id.widget_meal_2x3_dinner,
                R.id.widget_meal_2x3_fruit,
                R.id.widget_meal_2x3_late_snack,
            ),
        )
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

private fun String.safeWidgetLine(): String =
    replace(Regex("\\s+"), " ").trim().take(160)

internal fun nearestDesktopDateRecords(
    records: List<DateRecordEntity>,
    today: LocalDate,
): List<DateRecordEntity> {
    val parsed = records.mapNotNull { record ->
        runCatching { record to LocalDate.parse(record.dateIso) }.getOrNull()
    }
    val upcoming = parsed.filter { !it.second.isBefore(today) }
        .sortedWith(compareBy<Pair<DateRecordEntity, LocalDate>> { it.second }.thenBy { it.first.id })
        .take(2)
    val past = parsed.filter { it.second.isBefore(today) }
        .sortedWith(
            compareByDescending<Pair<DateRecordEntity, LocalDate>> { it.second }
                .thenBy { it.first.id },
        )
        .take(2)
    return (upcoming + past).map { it.first }
}

internal fun resolveDesktopWidgetConfig(
    storedSnapshot: DesktopWidgetConfig?,
    legacyConfigId: String?,
    reusableConfigs: List<DesktopWidgetConfig>,
): DesktopWidgetConfig? {
    val bindingId = storedSnapshot?.id ?: legacyConfigId
    return bindingId?.let { id -> reusableConfigs.firstOrNull { it.id == id } }
        ?: storedSnapshot
}
