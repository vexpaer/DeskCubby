package com.deskcubby.app.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.deskcubby.app.MainActivity
import com.deskcubby.app.R
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.ui.components.MusicSpectrumProcessor
import com.deskcubby.app.ui.components.SAMPLE_COUNT
import com.deskcubby.app.ui.components.normalizeWaveform
import com.deskcubby.app.ui.components.preferredVisualizerCaptureSize
import com.deskcubby.app.ui.components.zeroedWhenSilent
import com.deskcubby.app.ui.theme.translate
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that renders the live music spectrum into every "music visualizer" desktop
 * widget while music plays. It runs only while at least one such widget is placed and the user has
 * granted RECORD_AUDIO; no audio is ever stored.
 */
@AndroidEntryPoint
class DesktopWidgetMusicVisualizerService : Service() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var instanceStore: DesktopWidgetInstanceStore

    private var visualizer: Visualizer? = null
    private var lastWidgetUpdateMillis = 0L
    private var cachedSettings: com.deskcubby.app.data.model.AppSettings? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!hasRecordAudioPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        cachedSettings = try {
            kotlinx.coroutines.runBlocking { settingsRepository.settings.first() }
        } catch (_: Exception) {
            null
        }
        if (!hasMusicVisualizerWidgets()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startCapturing()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseVisualizer()
        super.onDestroy()
    }

    private fun startCapturing() {
        if (visualizer != null) return
        val captureSize = preferredVisualizerCaptureSize(Visualizer.getCaptureSizeRange())
            ?: return
        val captureRate = Visualizer.getMaxCaptureRate().coerceAtMost(20_000)
        if (captureRate <= 0) return
        val processor = MusicSpectrumProcessor(
            frequencyMode = MusicVisualizerFrequencyMode.ADAPTIVE,
            minFrequencyHz = 20,
            maxFrequencyHz = 20_000,
        )
        try {
            val created = Visualizer(0)
            if (
                created.setCaptureSize(captureSize) != Visualizer.SUCCESS ||
                created.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) != Visualizer.SUCCESS
            ) {
                releaseVisualizer()
                return
            }
            val listenerResult = created.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveformBytes: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (waveformBytes != null) {
                            renderAndPublish(normalizeWaveform(waveformBytes))
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {
                        if (fft != null) {
                            renderAndPublish(processor.process(fft, samplingRate))
                        }
                    }
                },
                captureRate,
                true,
                true,
            )
            if (listenerResult != Visualizer.SUCCESS || created.setEnabled(true) != Visualizer.SUCCESS) {
                releaseVisualizer()
                return
            }
            visualizer = created
        } catch (_: RuntimeException) {
            releaseVisualizer()
        }
    }

    private fun renderAndPublish(samples: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - lastWidgetUpdateMillis < WIDGET_UPDATE_INTERVAL_MILLIS) return
        lastWidgetUpdateMillis = now
        val widgets = musicVisualizerWidgets()
        if (widgets == null || widgets.isEmpty()) {
            stopSelf()
            return
        }
        val settings = cachedSettings ?: return
        val board = drawSpectrum(samples, settings)
        val manager = AppWidgetManager.getInstance(this)
        widgets.forEach { appWidgetId ->
            runCatching {
                val views = RemoteViews(packageName, R.layout.desktop_widget_apps)
                views.setInt(
                    R.id.widget_apps_root,
                    "setBackgroundColor",
                    settings.themeColorArgb or 0xFF000000.toInt(),
                )
                views.setTextViewText(
                    R.id.widget_apps_title,
                    translate("音乐可视化", "Music visualizer", settings.appLanguage),
                )
                views.setTextColor(R.id.widget_apps_title, panelTextColor(settings))
                views.setImageViewBitmap(R.id.widget_apps_board, board)
                views.setViewVisibility(R.id.widget_apps_dpad, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_apps_actions, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_apps_grid, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_apps_columns, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_apps_cloud_actions, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_apps_cloud_status, android.view.View.GONE)
                views.setOnClickPendingIntent(
                    R.id.widget_apps_root,
                    PendingIntent.getActivity(
                        this@DesktopWidgetMusicVisualizerService,
                        appWidgetId * 11,
                        Intent(this@DesktopWidgetMusicVisualizerService, MainActivity::class.java)
                            .putExtra(DesktopWidgetRenderer.EXTRA_START_ROUTE, "settings")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                manager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    private fun drawSpectrum(samples: FloatArray, settings: com.deskcubby.app.data.model.AppSettings): Bitmap {
        val width = 480
        val height = 320
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = settings.themeColorArgb or 0xFF000000.toInt()
        canvas.drawColor(background)
        val fg = panelTextColor(settings)
        val accent = if (androidx.core.graphics.ColorUtils.calculateLuminance(background) > 0.48) {
            Color.rgb(0x2E, 0x6E, 0xE6)
        } else {
            Color.rgb(0x8A, 0xB4, 0xF8)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        val values = samples.zeroedWhenSilent()
        val barCount = values.size.coerceAtMost(48)
        val gap = 3f
        val barWidth = (width - gap * (barCount + 1)) / barCount.coerceAtLeast(1)
        val base = height - 24f
        values.forEachIndexed { index, value ->
            val amplitude = abs(value).coerceIn(0f, 1f)
            val barHeight = (amplitude * (height - 40f)).coerceAtLeast(2f)
            val left = gap + index * (barWidth + gap)
            canvas.drawRoundRect(
                RectF(left, base - barHeight, left + barWidth, base),
                4f,
                4f,
                paint,
            )
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg.withAlpha(0x88)
            textSize = 12f
        }
        canvas.drawText(
            translate("音乐可视化", "Music visualizer", settings.appLanguage),
            8f,
            height - 8f,
            textPaint,
        )
        return bitmap
    }

    private fun panelTextColor(settings: com.deskcubby.app.data.model.AppSettings): Int =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(settings.themeColorArgb or 0xFF000000.toInt()) > 0.48) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

    private fun musicVisualizerWidgets(): IntArray? {
        val manager = AppWidgetManager.getInstance(this)
        val allIds = manager.getAppWidgetIds(
            android.content.ComponentName(this, DeskCubbyWidgetProvider::class.java),
        )
        if (allIds.isEmpty()) return allIds
        return allIds.filter { id ->
            runCatching {
                instanceStore.snapshot(id)?.homeModuleId == "music_visualizer" ||
                    instanceStore.configId(id)?.let { configId ->
                        cachedSettings?.desktopWidgetConfigs.orEmpty()
                            .firstOrNull { it.id == configId }
                            ?.homeModuleId == "music_visualizer"
                    } == true
            }.getOrDefault(false)
        }.toIntArray()
    }

    private fun hasMusicVisualizerWidgets(): Boolean =
        musicVisualizerWidgets()?.isNotEmpty() == true

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun releaseVisualizer() {
        val current = visualizer ?: return
        visualizer = null
        try {
            current.setDataCaptureListener(null, 0, false, false)
        } catch (_: RuntimeException) {
            // The platform effect can already be dead when audio output changes.
        }
        try {
            current.setEnabled(false)
        } catch (_: RuntimeException) {
            // Ignore.
        }
        try {
            current.release()
        } catch (_: RuntimeException) {
            // Ignore.
        }
    }

    private fun startForegroundCompat() {
        val channelId = "desktop_music_visualizer"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "DeskCubby 音乐可视化 / Music visualizer",
                    NotificationManager.IMPORTANCE_MIN,
                ),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_widget_music)
            .setContentTitle("DeskCubby")
            .setContentText("桌面音乐可视化运行中 / Music visualizer is running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4D55_4C
        private const val WIDGET_UPDATE_INTERVAL_MILLIS = 120L

        fun ensureRunning(context: Context) {
            val intent = Intent(context, DesktopWidgetMusicVisualizerService::class.java)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Foreground-service restrictions: the widget still shows the placeholder.
            }
        }
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
