package com.deskcubby.app.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.deskcubby.app.R
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.ui.components.MusicSpectrumProcessor
import com.deskcubby.app.ui.components.normalizeWaveform
import com.deskcubby.app.ui.components.preferredVisualizerCaptureSize
import com.deskcubby.app.ui.components.zeroedWhenSilent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Publishes a text-free, full-bleed visualizer bitmap to every matching launcher widget. The
 * service reads the authoritative per-instance snapshot, so switching a placed card away from
 * the visualizer cannot be overwritten later by an old reusable template.
 */
@AndroidEntryPoint
class DesktopWidgetMusicVisualizerService : Service() {
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var instanceStore: DesktopWidgetInstanceStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var settingsJob: Job? = null
    private var visualizer: Visualizer? = null
    private var captureConfig: CaptureConfig? = null
    @Volatile private var cachedSettings: AppSettings? = null
    @Volatile private var lastWidgetUpdateMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (!hasRecordAudioPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (settingsJob == null) {
            settingsJob = serviceScope.launch {
                settingsRepository.settings.collectLatest(::applySettings)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        settingsJob = null
        serviceScope.cancel()
        releaseVisualizer()
        super.onDestroy()
    }

    private fun applySettings(settings: AppSettings) {
        cachedSettings = settings
        if (musicVisualizerWidgets(settings).isEmpty()) {
            stopSelf()
            return
        }
        val nextConfig = CaptureConfig(
            style = settings.musicVisualizerStyle,
            frequencyMode = settings.musicVisualizerFrequencyMode,
            minFrequencyHz = settings.musicVisualizerMinFrequencyHz,
            maxFrequencyHz = settings.musicVisualizerMaxFrequencyHz,
        )
        if (nextConfig != captureConfig || visualizer == null) {
            releaseVisualizer()
            captureConfig = nextConfig
            if (!startCapturing(nextConfig)) stopSelf()
        }
    }

    private fun startCapturing(config: CaptureConfig): Boolean {
        val captureSize = preferredVisualizerCaptureSize(Visualizer.getCaptureSizeRange())
            ?: return false
        val captureRate = Visualizer.getMaxCaptureRate().coerceAtMost(MAX_CAPTURE_RATE)
        if (captureRate <= 0) return false
        val processor = MusicSpectrumProcessor(
            frequencyMode = config.frequencyMode,
            minFrequencyHz = config.minFrequencyHz,
            maxFrequencyHz = config.maxFrequencyHz,
        )
        return try {
            val created = Visualizer(0)
            if (
                created.setCaptureSize(captureSize) != Visualizer.SUCCESS ||
                created.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) != Visualizer.SUCCESS
            ) {
                created.releaseSafely()
                return false
            }
            val waveform = config.style == MusicVisualizerStyle.WAVEFORM
            val listenerResult = created.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveformBytes: ByteArray?,
                        samplingRate: Int,
                    ) {
                        waveformBytes?.let { renderAndPublish(normalizeWaveform(it)) }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int,
                    ) {
                        fft?.let { renderAndPublish(processor.process(it, samplingRate)) }
                    }
                },
                captureRate,
                waveform,
                !waveform,
            )
            if (listenerResult != Visualizer.SUCCESS || created.setEnabled(true) != Visualizer.SUCCESS) {
                created.releaseSafely()
                false
            } else {
                visualizer = created
                true
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun renderAndPublish(samples: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - lastWidgetUpdateMillis < WIDGET_UPDATE_INTERVAL_MILLIS) return
        lastWidgetUpdateMillis = now
        val settings = cachedSettings ?: return
        val widgetIds = musicVisualizerWidgets(settings)
        if (widgetIds.isEmpty()) {
            stopSelf()
            return
        }
        val manager = AppWidgetManager.getInstance(this)
        widgetIds.forEach { appWidgetId ->
            val config = resolveConfig(appWidgetId, settings) ?: return@forEach
            runCatching {
                val size = desktopWidgetBitmapSize(this, appWidgetId, config)
                val board = drawSpectrum(
                    samples = samples,
                    style = settings.musicVisualizerStyle,
                    color = config.textColorArgb,
                    size = size,
                )
                // Only replace the bitmap. The initial full update owns the card background,
                // optional image, scrim and click action and must not be reset every frame.
                val partial = RemoteViews(packageName, R.layout.desktop_widget_visual)
                partial.setImageViewBitmap(R.id.widget_apps_board, board)
                manager.partiallyUpdateAppWidget(appWidgetId, partial)
            }
        }
    }

    private fun drawSpectrum(
        samples: FloatArray,
        style: MusicVisualizerStyle,
        color: Int,
        size: DesktopWidgetBitmapSize,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val values = resample(samples.zeroedWhenSilent(), MAX_DRAW_SAMPLES)
        if (values.isEmpty()) return bitmap
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.withAlpha(0xE8)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (style) {
            MusicVisualizerStyle.BARS -> {
                val gap = (size.width * 0.006f).coerceAtLeast(1f)
                val barWidth = (size.width - gap * (values.size + 1)) / values.size
                values.forEachIndexed { index, value ->
                    val height = abs(value).coerceIn(0f, 1f) * size.height
                    val left = gap + index * (barWidth + gap)
                    canvas.drawRoundRect(
                        RectF(left, size.height - height, left + barWidth, size.height.toFloat()),
                        barWidth / 2f,
                        barWidth / 2f,
                        paint,
                    )
                }
            }
            MusicVisualizerStyle.WAVEFORM -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (minOf(size.width, size.height) * 0.018f).coerceAtLeast(2f)
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = index * size.width / values.lastIndex.coerceAtLeast(1).toFloat()
                    val y = size.height / 2f + value.coerceIn(-1f, 1f) * size.height * 0.48f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
            MusicVisualizerStyle.CURVE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (minOf(size.width, size.height) * 0.024f).coerceAtLeast(3f)
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = index * size.width / values.lastIndex.coerceAtLeast(1).toFloat()
                    val y = size.height - abs(value).coerceIn(0f, 1f) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

    private fun musicVisualizerWidgets(settings: AppSettings): IntArray {
        val manager = AppWidgetManager.getInstance(this)
        return manager.getAppWidgetIds(ComponentName(this, DeskCubbyWidgetProvider::class.java))
            .filter { id -> resolveConfig(id, settings)?.homeModuleId == MUSIC_MODULE_ID }
            .toIntArray()
    }

    private fun resolveConfig(appWidgetId: Int, settings: AppSettings): DesktopWidgetConfig? {
        val snapshot = runCatching { instanceStore.snapshot(appWidgetId) }.getOrNull()
        val config = snapshot ?: runCatching { instanceStore.configId(appWidgetId) }
            .getOrNull()
            ?.let { configId -> settings.desktopWidgetConfigs.firstOrNull { it.id == configId } }
        return config?.takeIf {
            it.contentType == DesktopWidgetContentType.APP_MODULE &&
                it.homeModuleId == MUSIC_MODULE_ID
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    private fun releaseVisualizer() {
        val current = visualizer ?: return
        visualizer = null
        current.releaseSafely()
    }

    private fun Visualizer.releaseSafely() {
        runCatching { setDataCaptureListener(null, 0, false, false) }
        runCatching { enabled = false }
        runCatching { release() }
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

    private data class CaptureConfig(
        val style: MusicVisualizerStyle,
        val frequencyMode: MusicVisualizerFrequencyMode,
        val minFrequencyHz: Int,
        val maxFrequencyHz: Int,
    )

    companion object {
        private const val MUSIC_MODULE_ID = "music_visualizer"
        private const val NOTIFICATION_ID = 0x4D55_4C
        private const val WIDGET_UPDATE_INTERVAL_MILLIS = 180L
        private const val MAX_CAPTURE_RATE = 20_000
        private const val MAX_DRAW_SAMPLES = 64

        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DesktopWidgetMusicVisualizerService::class.java),
                )
            } catch (_: Exception) {
                // Foreground-service restrictions leave the text-free placeholder visible.
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, DesktopWidgetMusicVisualizerService::class.java))
            }
        }
    }
}

private fun resample(values: FloatArray, maximum: Int): FloatArray {
    if (values.size <= maximum) return values
    if (maximum <= 1) return floatArrayOf(values.firstOrNull() ?: 0f)
    return FloatArray(maximum) { index ->
        values[index * values.lastIndex / (maximum - 1)]
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
