package com.deskcubby.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import com.deskcubby.app.MainActivity
import com.deskcubby.app.R
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DateRecordRepository
import com.deskcubby.app.data.repository.PoetryRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
            val recentThought = async {
                loadOrNull { thoughtRepository.recent.first().firstOrNull()?.content }
            }
            val dateRecordCount = async {
                loadOrNull { dateRecordRepository.records.first().size } ?: 0
            }
            val poem = async { loadOrNull { poetryRepository.poem.first() } }
            val resolvedPoem = poem.await()
            DesktopWidgetContentSnapshot(
                diaries = diaries.await(),
                recentThought = recentThought.await(),
                dateRecordCount = dateRecordCount.await(),
                poemContent = resolvedPoem?.content
                    ?: localized(settings, "打开应用查看", "Open the app to view"),
                poemSource = resolvedPoem?.source.orEmpty(),
            )
        }
        return appWidgetIds.count { appWidgetId ->
            val config = settings.desktopWidgetConfigs.firstOrNull {
                it.id == instanceStore.configId(appWidgetId)
            } ?: settings.desktopWidgetConfigs.firstOrNull()
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
    }

    private fun render(
        manager: AppWidgetManager,
        appWidgetId: Int,
        config: DesktopWidgetConfig?,
        settings: AppSettings,
        snapshot: DesktopWidgetContentSnapshot,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.desktop_widget)
        if (config == null) {
            views.setInt(R.id.widget_root, "setBackgroundColor", 0xFF263238.toInt())
            views.setTextColor(R.id.widget_title, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_value, 0xFFFFFFFF.toInt())
            views.setTextColor(R.id.widget_detail, 0xFFFFFFFF.toInt())
            views.setTextViewText(
                R.id.widget_title,
                localized(settings, "小卡片", "Widget"),
            )
            views.setTextViewText(
                R.id.widget_value,
                localized(settings, "请先在 DeskCubby 中创建卡片", "Create a card in DeskCubby"),
            )
            views.setViewVisibility(R.id.widget_detail, View.GONE)
            views.setViewVisibility(R.id.widget_background_image, View.GONE)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                deskCubbyPendingIntent(appWidgetId, NavItemId.WIDGETS.route),
            )
            return views
        }

        val text = if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            appShortcutText(config, settings)
        } else {
            homeModuleText(config.homeModuleId, settings, snapshot)
        }
        views.setInt(R.id.widget_root, "setBackgroundColor", config.backgroundColorArgb)
        views.setTextColor(R.id.widget_title, config.textColorArgb)
        views.setTextColor(R.id.widget_value, config.textColorArgb)
        views.setTextColor(R.id.widget_detail, config.textColorArgb)
        views.setTextViewText(R.id.widget_title, config.name.ifBlank { text.title })
        views.setTextViewText(R.id.widget_value, text.value)
        views.setTextViewText(R.id.widget_detail, text.detail)

        val options = runCatching { manager.getAppWidgetOptions(appWidgetId) }
            .getOrDefault(android.os.Bundle.EMPTY)
        val actualWidth = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            config.widthCells * APPROX_CELL_DP,
        )
        val actualHeight = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            config.heightCells * APPROX_CELL_DP,
        )
        val compact = actualWidth < COMPACT_WIDTH_DP || actualHeight < COMPACT_HEIGHT_DP
        views.setViewVisibility(
            R.id.widget_detail,
            if (!compact && text.detail.isNotBlank()) View.VISIBLE else View.GONE,
        )
        views.setViewVisibility(
            R.id.widget_icon,
            if (actualWidth >= ICON_MIN_WIDTH_DP) View.VISIBLE else View.GONE,
        )

        val background = config.backgroundImageUri?.let { raw ->
            loadBackgroundBitmap(raw, actualWidth, actualHeight)
        }
        if (background != null) {
            views.setImageViewBitmap(R.id.widget_background_image, background)
            views.setViewVisibility(R.id.widget_background_image, View.VISIBLE)
            views.setViewVisibility(R.id.widget_scrim, View.VISIBLE)
            views.setInt(R.id.widget_scrim, "setBackgroundColor", 0x52000000)
        } else {
            views.setViewVisibility(R.id.widget_background_image, View.GONE)
            views.setViewVisibility(R.id.widget_scrim, View.GONE)
        }

        val icon = if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            config.appPackageName?.let { packageName ->
                runCatching {
                    context.packageManager.getApplicationIcon(packageName)
                        .toBitmap(96, 96, Bitmap.Config.ARGB_8888)
                }.getOrNull()
            }
        } else {
            runCatching {
                context.packageManager.getApplicationIcon(context.packageName)
                    .toBitmap(96, 96, Bitmap.Config.ARGB_8888)
            }.getOrNull()
        }
        icon?.let { views.setImageViewBitmap(R.id.widget_icon, it) }

        val clickIntent = if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            config.appPackageName?.let(context.packageManager::getLaunchIntentForPackage)
                ?.let { intent ->
                    PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
        } else {
            deskCubbyPendingIntent(appWidgetId, text.route)
        }
        views.setOnClickPendingIntent(
            R.id.widget_root,
            clickIntent ?: deskCubbyPendingIntent(appWidgetId, NavItemId.WIDGETS.route),
        )
        return views
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
                today.format(DateTimeFormatter.ofPattern(if (english) "MMMM yyyy" else "yyyy年M月")),
            )
            "weather" -> DesktopWidgetText(
                localized(settings, "天气缓存", "Weather cache"),
                localized(settings, "打开应用查看", "Open the app to view"),
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
                if (english) "${snapshot.dateRecordCount} records" else "${snapshot.dateRecordCount} 条记录",
                route = NavItemId.DATE.route,
            )
            "streak" -> DesktopWidgetText(
                localized(settings, "连续记录", "Writing streak"),
                if (english) "${streakDays(snapshot.diaries, today)} days" else "${streakDays(snapshot.diaries, today)} 天",
                route = NavItemId.DIARY.route,
            )
            "month_diaries" -> {
                val count = snapshot.diaries.count { it.dateIso.startsWith(today.toString().take(7)) }
                DesktopWidgetText(
                    localized(settings, "本月日记", "Diaries this month"),
                    if (english) "$count entries" else "$count 篇",
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
            )
            "recent_thought" -> DesktopWidgetText(
                localized(settings, "最近小巧思", "Recent thought"),
                snapshot.recentThought
                    ?: localized(settings, "还没有小巧思", "No thoughts yet"),
                route = NavItemId.THOUGHT.route,
            )
            "quick_input" -> DesktopWidgetText(
                localized(settings, "快速输入", "Quick input"),
                localized(settings, "点按打开记录", "Tap to capture a thought"),
                route = NavItemId.THOUGHT.route,
            )
            "daily_records" -> DesktopWidgetText(
                localized(settings, "日常记录", "Daily records"),
                if (english) "${settings.dailyEventTemplates.size} templates" else "${settings.dailyEventTemplates.size} 个模板",
                route = NavItemId.DIARY.route,
            )
            "meal_photos" -> DesktopWidgetText(
                localized(settings, "饮食图片", "Meal photos"),
                localized(settings, "点按打开日记", "Tap to open Diary"),
                route = NavItemId.DIARY.route,
            )
            "random_diary" -> {
                val index = snapshot.diaries.takeIf { it.isNotEmpty() }?.let {
                    Math.floorMod(today.toEpochDay(), snapshot.diaries.size.toLong()).toInt()
                }
                DesktopWidgetText(
                    localized(settings, "随机旧日记", "Random old diary"),
                    index?.let { snapshot.diaries[it].title }
                        ?: localized(settings, "还没有日记", "No diaries yet"),
                    route = NavItemId.DIARY.route,
                )
            }
            "year_progress" -> {
                val total = if (today.isLeapYear) 366 else 365
                val percent = today.dayOfYear * 100 / total
                DesktopWidgetText(
                    localized(settings, "年度进度", "Year progress"),
                    "$percent%",
                    if (english) "Day ${today.dayOfYear} / $total" else "第 ${today.dayOfYear} / $total 天",
                )
            }
            "website" -> DesktopWidgetText(
                localized(settings, "网站快捷入口", "Website shortcut"),
                runCatching { Uri.parse(settings.browserHomeUrl).host }.getOrNull()
                    ?: settings.browserHomeUrl,
                route = NavItemId.BLOG.route,
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

    private fun deskCubbyPendingIntent(appWidgetId: Int, route: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_START_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            appWidgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

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
        val recentThought: String?,
        val dateRecordCount: Int,
        val poemContent: String,
        val poemSource: String,
    )

    companion object {
        const val EXTRA_START_ROUTE = "com.deskcubby.app.extra.START_ROUTE"
        private const val APPROX_CELL_DP = 70
        private const val COMPACT_WIDTH_DP = 120
        private const val COMPACT_HEIGHT_DP = 90
        private const val ICON_MIN_WIDTH_DP = 100
        private const val MAX_BACKGROUND_EDGE_PX = 384
        private const val MAX_BACKGROUND_SOURCE_BYTES = 32L * 1024L * 1024L
        private const val DATA_SOURCE_TIMEOUT_MS = 2_500L
    }
}
