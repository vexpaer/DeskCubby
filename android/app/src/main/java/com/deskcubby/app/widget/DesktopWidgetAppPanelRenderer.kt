package com.deskcubby.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.deskcubby.app.MainActivity
import com.deskcubby.app.R
import com.deskcubby.app.data.local.UsageStatisticsDao
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.repository.ReaderRepository
import com.deskcubby.app.data.sync.AppCloudSyncService
import com.deskcubby.app.data.sync.CloudSyncUndoStore
import com.deskcubby.app.data.sync.CloudSyncManualQueueState
import com.deskcubby.app.data.sync.CloudSyncRunMode
import com.deskcubby.app.data.statistics.UsageDeviceRepository
import com.deskcubby.app.ui.theme.translate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * Renders the non-game app modules (reader, screen-time visualizations, music visualizer and the
 * combined cloud-sync panel) into the shared app-panel RemoteViews layout.
 */
@Singleton
class DesktopWidgetAppPanelRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val readerRepository: ReaderRepository,
    private val usageDeviceRepository: UsageDeviceRepository,
    private val usageStatisticsDao: UsageStatisticsDao,
    private val cloudSyncService: AppCloudSyncService,
    private val cloudSyncUndoStore: CloudSyncUndoStore,
) {
    private val boardWidthPx = 480
    private val boardHeightPx = 320

    suspend fun render(
        appWidgetId: Int,
        moduleId: String,
        settings: AppSettings,
        usageRangeDays: Int,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.desktop_widget_apps)
        val title = when (moduleId) {
            "reader" -> translate("阅读", "Reader", settings.appLanguage)
            "usage_overview" -> translate("使用时间总览", "Screen time overview", settings.appLanguage)
            "usage_chart" -> translate("使用时间图表", "Screen time chart", settings.appLanguage)
            "usage_apps" -> translate("使用时间应用排行", "Top apps by usage", settings.appLanguage)
            "music_visualizer" -> translate("音乐可视化", "Music visualizer", settings.appLanguage)
            "cloud_sync" -> translate("云端同步", "Cloud sync", settings.appLanguage)
            else -> translate("应用", "Apps", settings.appLanguage)
        }
        applyPanelBase(views, settings, title)
        val board = Bitmap.createBitmap(boardWidthPx, boardHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        try {
            when (moduleId) {
                "reader" -> drawReader(canvas, settings)
                "usage_overview" -> drawUsageOverview(canvas, settings, usageRangeDays)
                "usage_chart" -> drawUsageChart(canvas, settings, usageRangeDays)
                "usage_apps" -> drawUsageApps(canvas, settings, usageRangeDays)
                "music_visualizer" -> {
                    drawMusicPlaceholder(canvas, settings)
                    DesktopWidgetMusicVisualizerService.ensureRunning(context)
                }
                "cloud_sync" -> {
                    drawCloudStatus(canvas, settings)
                    configureCloudButtons(views, appWidgetId, settings)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Degrade gracefully: the title and background still render.
        }
        views.setImageViewBitmap(R.id.widget_apps_board, board)
        views.setViewVisibility(R.id.widget_apps_cloud_status, View.GONE)
        views.setViewVisibility(R.id.widget_apps_cloud_actions, View.GONE)
        views.setViewVisibility(R.id.widget_apps_dpad, View.GONE)
        views.setViewVisibility(R.id.widget_apps_actions, View.GONE)
        views.setViewVisibility(R.id.widget_apps_grid, View.GONE)
        views.setViewVisibility(R.id.widget_apps_columns, View.GONE)
        // Root click opens the matching app screen.
        val route = when (moduleId) {
            "reader" -> NavItemId.READER.route
            "usage_overview", "usage_chart", "usage_apps" -> NavItemId.USAGE.route
            "music_visualizer" -> NavItemId.SETTINGS.route
            "cloud_sync" -> NavItemId.SETTINGS.route
            else -> NavItemId.HOME.route
        }
        views.setOnClickPendingIntent(
            R.id.widget_apps_root,
            PendingIntent.getActivity(
                context,
                appWidgetId * 7 + route.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(DesktopWidgetRenderer.EXTRA_START_ROUTE, route)
                    .setData(Uri.parse("deskcubby://widget-app/" + appWidgetId + "/" + moduleId))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return views
    }

    private suspend fun drawReader(canvas: Canvas, settings: AppSettings) {
        val state = try {
            readerRepository.state.value
        } catch (_: Exception) {
            null
        }
        val books = state?.books.orEmpty()
        val english = settings.appLanguage == AppLanguage.ENGLISH
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = panelTextColor(settings)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelTextColor(settings).withAlpha(0xDD) }
        canvas.drawColor(panelBackgroundColor(settings))
        if (books.isEmpty()) {
            canvas.drawText(
                translate("书架还没有书，点按导入 TXT 或 PDF", "No books yet. Tap to import TXT or PDF", settings.appLanguage),
                24f,
                60f,
                bodyPaint,
            )
            return
        }
        books.take(4).forEachIndexed { index, book ->
            val y = 48f + index * 66f
            canvas.drawText(book.title, 24f, y, textPaint)
            val progress = bookProgress(book)
            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = panelTextColor(settings).withAlpha(0x99)
                textSize = 15f
            }
            canvas.drawText(progress, 24f, y + 26f, progressPaint)
        }
    }

    private fun bookProgress(book: com.deskcubby.app.data.repository.ReaderBook): String {
        val total = book.totalPages.coerceAtLeast(1)
        val page = if (book.type == ReaderBookType.PDF) book.pdfPageIndex else book.textPageIndex
        return (page.coerceIn(0, total - 1) + 1).toString() + " / " + total
    }

    private suspend fun drawUsageOverview(
        canvas: Canvas,
        settings: AppSettings,
        rangeDays: Int,
    ) {
        val rows = usageRows(settings, rangeDays)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = panelTextColor(settings)
            textSize = 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelTextColor(settings).withAlpha(0xDD) }
        canvas.drawColor(panelBackgroundColor(settings))
        val totalMillis = rows.sumOf { it.foregroundMillis ?: 0L }
        val activeDays = rows.map { it.dayDateIso }.distinct().count { it != null }
        val total = formatMinutes(totalMillis / 60000)
        canvas.drawText(translate("近 " + rangeDays + " 天总时长", "Total (" + rangeDays + "d)", settings.appLanguage), 24f, 52f, textPaint)
        canvas.drawText(total, 24f, 92f, textPaint)
        val avg = if (activeDays > 0) totalMillis / activeDays else 0L
        canvas.drawText(
            translate("日均 " + formatMinutes(avg / 60000), "Daily avg " + formatMinutes(avg / 60000), settings.appLanguage),
            24f,
            132f,
            bodyPaint,
        )
        canvas.drawText(
            translate("活跃 " + activeDays + " 天", activeDays.toString() + " active days", settings.appLanguage),
            24f,
            162f,
            bodyPaint,
        )
    }

    private suspend fun drawUsageChart(
        canvas: Canvas,
        settings: AppSettings,
        rangeDays: Int,
    ) {
        val rows = usageRows(settings, rangeDays)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = panelTextColor(settings)
            textSize = 16f
        }
        canvas.drawColor(panelBackgroundColor(settings))
        val byDate = rows.groupBy { it.dayDateIso ?: "" }
        val days = (0 until rangeDays).map { LocalDate.now().minusDays((rangeDays - 1 - it).toLong()).toString() }
        val values = days.map { date -> byDate[date]?.sumOf { it.foregroundMillis ?: 0L } ?: 0L }
        val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val chartLeft = 36f
        val chartRight = boardWidthPx - 12f
        val chartTop = 28f
        val chartBottom = boardHeightPx - 44f
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelAccentColor(settings) }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = panelTextColor(settings).withAlpha(0x33)
            strokeWidth = 1f
        }
        val step = (chartRight - chartLeft) / rangeDays
        values.forEachIndexed { index, value ->
            val left = chartLeft + index * step + step * 0.18f
            val right = chartLeft + (index + 1) * step - step * 0.18f
            val height = (value.toFloat() / max * (chartBottom - chartTop)).coerceAtLeast(2f)
            canvas.drawRoundRect(
                RectF(left, chartBottom - height, right, chartBottom),
                step * 0.1f,
                step * 0.1f,
                barPaint,
            )
        }
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, gridPaint)
        val maxText = formatMinutes(max / 60000)
        canvas.drawText(maxText, 4f, chartTop + 8f, textPaint)
        val todayLabel = if (settings.appLanguage == AppLanguage.ENGLISH) "today" else "今天"
        canvas.drawText(todayLabel, chartRight - 40f, boardHeightPx - 14f, textPaint)
    }

    private suspend fun drawUsageApps(
        canvas: Canvas,
        settings: AppSettings,
        rangeDays: Int,
    ) {
        val rows = usageRows(settings, rangeDays)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = panelTextColor(settings)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelTextColor(settings).withAlpha(0xDD) }
        canvas.drawColor(panelBackgroundColor(settings))
        val totals = rows.groupBy { it.packageName ?: "" }
            .mapValues { (_, dayRows) -> dayRows.sumOf { it.foregroundMillis ?: 0L } }
            .filterKeys { it.isNotBlank() }
            .toList()
            .sortedByDescending { it.second }
            .take(5)
        if (totals.isEmpty()) {
            canvas.drawText(
                translate("暂无使用时间数据", "No screen time data yet", settings.appLanguage),
                24f,
                60f,
                bodyPaint,
            )
            return
        }
        totals.forEachIndexed { index, (packageName, millis) ->
            val y = 48f + index * 52f
            val label = packageName.substringAfterLast('.').take(16)
            canvas.drawText((index + 1).toString() + ". " + label, 24f, y, textPaint)
            canvas.drawText(formatMinutes(millis / 60000), boardWidthPx - 90f, y, bodyPaint)
        }
    }

    private suspend fun usageRows(
        settings: AppSettings,
        rangeDays: Int,
    ): List<com.deskcubby.app.data.local.UsageHistoryRoomRow> {
        val identity = try {
            usageDeviceRepository.identity.first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val rows = try {
            usageStatisticsDao.getHistoryRows(identity.deviceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val from = LocalDate.now().minusDays((rangeDays - 1).toLong()).toString()
        return rows.filter { row -> row.dayDateIso != null && row.dayDateIso >= from }
    }

    private fun drawMusicPlaceholder(canvas: Canvas, settings: AppSettings) {
        canvas.drawColor(panelBackgroundColor(settings))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = panelTextColor(settings).withAlpha(0xCC)
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            translate("播放音乐时在桌面显示频谱", "Spectrum appears here while music plays", settings.appLanguage),
            boardWidthPx / 2f,
            boardHeightPx / 2f,
            paint,
        )
    }

    private fun drawCloudStatus(canvas: Canvas, settings: AppSettings) {
        canvas.drawColor(panelBackgroundColor(settings))
        val status = cloudSyncService.status.value
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = panelTextColor(settings)
            textSize = 18f
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panelTextColor(settings).withAlpha(0xCC) }
        val queued = CloudSyncManualQueueState.queuedMode(context)
        val totals = cloudSyncTotals()
        val stateLine = when {
            status.running -> translate("正在同步…", "Syncing...", settings.appLanguage)
            queued != null -> translate("同步已排队", "Sync queued", settings.appLanguage)
            totals != null -> translate(
                "已完成 ↑" + totals.first + " ↓" + totals.second + " 冲突" + totals.third,
                "Done ↑" + totals.first + " ↓" + totals.second + " ⚠" + totals.third,
                settings.appLanguage,
            )
            !settings.cloudSyncEnabled -> translate("云同步尚未开启", "Cloud sync is off", settings.appLanguage)
            settings.cloudSyncConfigs.none { it.enabled } -> translate("没有已启用来源", "No enabled source", settings.appLanguage)
            else -> translate("已就绪", "Ready", settings.appLanguage)
        }
        canvas.drawText(stateLine, 24f, 56f, paint)
        if (totals != null) {
            val line = translate(
                "上次：上传 " + totals.first + " · 下载 " + totals.second + " · 冲突 " + totals.third,
                "Last: " + totals.first + " up / " + totals.second + " down / " + totals.third + " conflicts",
                settings.appLanguage,
            )
            canvas.drawText(line, 24f, 96f, bodyPaint)
        } else {
            canvas.drawText(translate("尚无同步记录", "No sync runs yet", settings.appLanguage), 24f, 96f, bodyPaint)
        }
        val undoAvailable = cloudSyncUndoStore.hasUndo()
        canvas.drawText(
            translate(
                if (undoAvailable) "可撤回一次上次同步" else "无可撤回的同步",
                if (undoAvailable) "One sync undo available" else "Nothing to undo",
                settings.appLanguage,
            ),
            24f,
            136f,
            bodyPaint,
        )
    }

    /** (uploaded, downloaded, conflicts) from the last completed run, or null. */
    private fun cloudSyncTotals(): Triple<Int, Int, Int>? {
        val runs = cloudSyncService.status.value.lastRuns
        if (runs.isEmpty()) return null
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        var any = false
        runs.forEach { run ->
            run.result?.let { result ->
                uploaded += result.uploadedCount
                downloaded += result.downloadedCount
                conflicts += result.conflictCount
                any = true
            }
        }
        return if (any) Triple(uploaded, downloaded, conflicts) else null
    }

    private fun configureCloudButtons(
        views: RemoteViews,
        appWidgetId: Int,
        settings: AppSettings,
    ) {
        val textColor = panelTextColor(settings)
        val buttonIds = listOf(
            R.id.widget_apps_cloud_now,
            R.id.widget_apps_cloud_undo,
            R.id.widget_apps_cloud_upload,
            R.id.widget_apps_cloud_download,
        )
        buttonIds.forEach { id ->
            views.setTextColor(id, textColor)
            views.setInt(id, "setBackgroundColor", textColor.withAlpha(0x33))
        }
        views.setViewVisibility(R.id.widget_apps_cloud_actions, View.VISIBLE)
        val canRun = settings.cloudSyncEnabled &&
            settings.cloudSyncConfigs.any { it.enabled } &&
            !cloudSyncService.status.value.running &&
            CloudSyncManualQueueState.queuedMode(context) == null
        val undoAvailable = cloudSyncUndoStore.hasUndo() && !cloudSyncService.status.value.running
        bindCloudAction(
            views,
            R.id.widget_apps_cloud_now,
            translate("立即同步", "Sync now", settings.appLanguage),
            cloudPendingIntent(appWidgetId, CloudSyncWidgetActionReceiver.ACTION_SYNC_NOW).takeIf { canRun },
        )
        bindCloudAction(
            views,
            R.id.widget_apps_cloud_undo,
            translate("撤回一次", "Undo last", settings.appLanguage),
            cloudPendingIntent(appWidgetId, CloudSyncWidgetActionReceiver.ACTION_SYNC_UNDO).takeIf { undoAvailable },
        )
        val enabledSources = settings.cloudSyncConfigs.count { it.enabled }
        bindCloudAction(
            views,
            R.id.widget_apps_cloud_upload,
            translate("强制上传", "Force upload", settings.appLanguage),
            cloudPendingIntent(appWidgetId, CloudSyncWidgetActionReceiver.ACTION_FORCE_UPLOAD).takeIf { canRun },
        )
        bindCloudAction(
            views,
            R.id.widget_apps_cloud_download,
            translate("强制下载", "Force download", settings.appLanguage),
            cloudPendingIntent(appWidgetId, CloudSyncWidgetActionReceiver.ACTION_FORCE_DOWNLOAD)
                .takeIf { canRun && enabledSources == 1 },
        )
    }

    private fun bindCloudAction(
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

    private fun cloudPendingIntent(appWidgetId: Int, action: String): PendingIntent {
        val identity = appWidgetId.toString() + "/" + action
        return PendingIntent.getBroadcast(
            context,
            identity.hashCode(),
            Intent(context, CloudSyncWidgetActionReceiver::class.java)
                .setAction(action)
                .setData(Uri.parse("deskcubby://widget-cloud/" + identity)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun applyPanelBase(views: RemoteViews, settings: AppSettings, title: String) {
        views.setInt(R.id.widget_apps_root, "setBackgroundColor", panelBackgroundColor(settings))
        views.setTextViewText(R.id.widget_apps_title, title)
        views.setTextColor(R.id.widget_apps_title, panelTextColor(settings))
    }

    private fun panelBackgroundColor(settings: AppSettings): Int =
        settings.themeColorArgb or 0xFF000000.toInt()

    private fun panelTextColor(settings: AppSettings): Int =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(panelBackgroundColor(settings)) > 0.48) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

    private fun panelAccentColor(settings: AppSettings): Int =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(panelBackgroundColor(settings)) > 0.48) {
            Color.rgb(0x2E, 0x6E, 0xE6)
        } else {
            Color.rgb(0x8A, 0xB4, 0xF8)
        }

    private fun formatMinutes(minutes: Long): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) hours.toString() + "h " + mins + "m" else mins.toString() + "m"
    }

    companion object {
        const val EXTRA_WIDGET_ID = "com.deskcubby.app.extra.WIDGET_ID"
        const val EXTRA_MODULE_ID = "com.deskcubby.app.extra.WIDGET_MODULE_ID"
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
