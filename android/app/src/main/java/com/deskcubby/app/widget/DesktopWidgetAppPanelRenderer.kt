package com.deskcubby.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.deskcubby.app.MainActivity
import com.deskcubby.app.R
import com.deskcubby.app.data.local.UsageStatisticsDao
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.NavItemId
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
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Renders the non-game app modules (reader, screen-time visualizations, music visualizer and the
 * combined cloud-sync panel) into the shared app-panel RemoteViews layout.
 */
@Singleton
class DesktopWidgetAppPanelRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
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
        config: DesktopWidgetConfig,
    ): RemoteViews {
        if (moduleId in VISUAL_ONLY_MODULES) {
            return renderVisualModule(appWidgetId, moduleId, settings, config)
        }
        val views = RemoteViews(context.packageName, R.layout.desktop_widget_apps)
        val title = when (moduleId) {
            "reader" -> translate("阅读", "Reader", settings.appLanguage)
            "cloud_sync" -> translate("云端同步", "Cloud sync", settings.appLanguage)
            else -> translate("应用", "Apps", settings.appLanguage)
        }
        applyPanelBase(views, settings, title)
        val board = Bitmap.createBitmap(boardWidthPx, boardHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        try {
            when (moduleId) {
                "reader" -> drawReader(canvas, settings)
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

    private suspend fun renderVisualModule(
        appWidgetId: Int,
        moduleId: String,
        settings: AppSettings,
        config: DesktopWidgetConfig,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.desktop_widget_visual)
        val size = desktopWidgetBitmapSize(context, appWidgetId, config)
        val board = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        try {
            when (moduleId) {
                "usage_overview" -> drawUsageOverview(
                    canvas,
                    config.usageRangeDays,
                    config.textColorArgb,
                )
                "usage_chart" -> drawUsageChart(
                    canvas,
                    config.usageRangeDays,
                    config.textColorArgb,
                )
                "usage_apps" -> drawUsageApps(
                    canvas,
                    config.usageRangeDays,
                    config.textColorArgb,
                )
                "music_visualizer" -> {
                    drawMusicPlaceholder(canvas, config.textColorArgb)
                    DesktopWidgetMusicVisualizerService.ensureRunning(context)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transparent full-bleed panel is still a valid fallback.
        }
        views.setImageViewBitmap(R.id.widget_apps_board, board)
        val route = if (moduleId.startsWith("usage_")) {
            NavItemId.USAGE.route
        } else {
            NavItemId.SETTINGS.route
        }
        views.setOnClickPendingIntent(
            R.id.widget_apps_root,
            PendingIntent.getActivity(
                context,
                appWidgetId * 7 + route.hashCode(),
                Intent(context, MainActivity::class.java)
                    .putExtra(DesktopWidgetRenderer.EXTRA_START_ROUTE, route)
                    .setData(Uri.parse("deskcubby://widget-app/$appWidgetId/$moduleId"))
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
        rangeDays: Int,
        color: Int,
    ) {
        val rows = usageRows(rangeDays)
        val byDate = rows.groupBy { it.dayDateIso.orEmpty() }
        val values = usageDays(rangeDays).map { date ->
            byDate[date]?.sumOf { it.foregroundMillis ?: 0L } ?: 0L
        }
        val maximum = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val aspect = canvas.width.toFloat() / canvas.height.coerceAtLeast(1)
        val columns = ceil(sqrt(values.size * aspect)).toInt().coerceIn(1, values.size)
        val rowsCount = ceil(values.size / columns.toFloat()).toInt().coerceAtLeast(1)
        val gap = (minOf(canvas.width, canvas.height) * 0.018f).coerceAtLeast(2f)
        val cellWidth = (canvas.width - gap * (columns + 1)) / columns
        val cellHeight = (canvas.height - gap * (rowsCount + 1)) / rowsCount
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        values.forEachIndexed { index, value ->
            val column = index % columns
            val row = index / columns
            val intensity = value.toFloat() / maximum
            paint.color = color.withAlpha((36 + intensity * 219).toInt())
            val left = gap + column * (cellWidth + gap)
            val top = gap + row * (cellHeight + gap)
            canvas.drawRoundRect(
                RectF(left, top, left + cellWidth, top + cellHeight),
                minOf(cellWidth, cellHeight) * 0.18f,
                minOf(cellWidth, cellHeight) * 0.18f,
                paint,
            )
        }
    }

    private suspend fun drawUsageChart(
        canvas: Canvas,
        rangeDays: Int,
        color: Int,
    ) {
        val rows = usageRows(rangeDays)
        val byDate = rows.groupBy { it.dayDateIso ?: "" }
        val days = usageDays(rangeDays)
        val values = days.map { date -> byDate[date]?.sumOf { it.foregroundMillis ?: 0L } ?: 0L }
        val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val inset = minOf(canvas.width, canvas.height) * 0.06f
        val width = (canvas.width - inset * 2).coerceAtLeast(1f)
        val height = (canvas.height - inset * 2).coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = inset + index * width / values.lastIndex.coerceAtLeast(1)
            val y = canvas.height - inset - value.toFloat() / max * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (minOf(canvas.width, canvas.height) * 0.025f).coerceAtLeast(3f)
            },
        )
    }

    private suspend fun drawUsageApps(
        canvas: Canvas,
        rangeDays: Int,
        color: Int,
    ) {
        val rows = usageRows(rangeDays)
        val totals = rows.groupBy { it.packageName ?: "" }
            .mapValues { (_, dayRows) -> dayRows.sumOf { it.foregroundMillis ?: 0L } }
            .filterKeys { it.isNotBlank() }
            .toList()
            .sortedByDescending { it.second }
            .take(7)
        val maximum = totals.maxOfOrNull { it.second }?.coerceAtLeast(1L) ?: return
        val gap = (canvas.height * 0.035f).coerceAtLeast(3f)
        val barHeight = (canvas.height - gap * (totals.size + 1)) / totals.size
        val maximumWidth = canvas.width - gap * 2
        totals.forEachIndexed { index, (_, millis) ->
            val top = gap + index * (barHeight + gap)
            val right = gap + maximumWidth * millis.toFloat() / maximum
            canvas.drawRoundRect(
                RectF(gap, top, right, top + barHeight),
                barHeight / 2f,
                barHeight / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color.withAlpha((255 - index * 20).coerceAtLeast(96))
                },
            )
        }
    }

    private fun usageDays(rangeDays: Int): List<String> = (0 until rangeDays).map { index ->
        LocalDate.now().minusDays((rangeDays - 1 - index).toLong()).toString()
    }

    private suspend fun usageRows(rangeDays: Int): List<com.deskcubby.app.data.local.UsageHistoryRoomRow> {
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

    private fun drawMusicPlaceholder(canvas: Canvas, color: Int) {
        val count = 18
        val gap = (canvas.width * 0.008f).coerceAtLeast(2f)
        val barWidth = (canvas.width - gap * (count + 1)) / count
        val base = canvas.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.withAlpha(0x42) }
        repeat(count) { index ->
            val phase = (index % 6 + 1) / 7f
            val height = canvas.height * (0.12f + phase * 0.34f)
            val left = gap + index * (barWidth + gap)
            canvas.drawRoundRect(
                RectF(left, base - height, left + barWidth, base),
                barWidth / 2f,
                barWidth / 2f,
                paint,
            )
        }
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
        val status = cloudSyncService.status.value
        val uploaded = status.lastUploadedCount ?: return null
        val downloaded = status.lastDownloadedCount ?: return null
        val conflicts = status.lastConflictCount ?: return null
        return Triple(uploaded, downloaded, conflicts)
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

    companion object {
        const val EXTRA_WIDGET_ID = "com.deskcubby.app.extra.WIDGET_ID"
        const val EXTRA_MODULE_ID = "com.deskcubby.app.extra.WIDGET_MODULE_ID"
        private val VISUAL_ONLY_MODULES = setOf(
            "music_visualizer",
            "usage_overview",
            "usage_chart",
            "usage_apps",
        )
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
