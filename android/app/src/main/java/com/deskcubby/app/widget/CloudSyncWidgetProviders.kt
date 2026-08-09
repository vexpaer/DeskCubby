package com.deskcubby.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.graphics.ColorUtils
import com.deskcubby.app.R
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.sync.CloudSyncManualScheduler
import com.deskcubby.app.data.sync.CloudSyncRunMode
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class CloudSyncWidgetState {
    IDLE,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CONFIGURATION_REQUIRED,
}

/** Renders only bounded, non-sensitive state; service errors and endpoint details never reach it. */
@Singleton
class CloudSyncWidgetRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun update(mode: CloudSyncRunMode, state: CloudSyncWidgetState) {
        val manager = AppWidgetManager.getInstance(context)
        val settings = loadSettings()
        when (mode) {
            CloudSyncRunMode.NORMAL -> updateNow(
                manager,
                manager.getAppWidgetIds(
                    ComponentName(context, CloudSyncNowWidgetProvider::class.java),
                ),
                settings,
                state,
            )
            CloudSyncRunMode.FORCE_UPLOAD,
            CloudSyncRunMode.FORCE_DOWNLOAD,
            -> updateForce(
                manager,
                manager.getAppWidgetIds(
                    ComponentName(context, CloudSyncForceWidgetProvider::class.java),
                ),
                settings,
                activeMode = mode,
                state = state,
            )
        }
    }

    suspend fun updateNow(
        manager: AppWidgetManager,
        ids: IntArray,
        state: CloudSyncWidgetState = CloudSyncWidgetState.IDLE,
    ) = updateNow(manager, ids, loadSettings(), state)

    suspend fun updateForce(
        manager: AppWidgetManager,
        ids: IntArray,
        state: CloudSyncWidgetState = CloudSyncWidgetState.IDLE,
    ) = updateForce(manager, ids, loadSettings(), activeMode = null, state = state)

    private fun updateNow(
        manager: AppWidgetManager,
        ids: IntArray,
        settings: AppSettings,
        state: CloudSyncWidgetState,
    ) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.cloud_sync_now_widget)
            val background = opaque(settings.themeColorArgb)
            views.setInt(R.id.cloud_sync_now_root, "setBackgroundColor", background)
            views.setTextColor(R.id.cloud_sync_now_text, contentColor(background))
            views.setTextViewText(
                R.id.cloud_sync_now_text,
                stateLabel(settings, CloudSyncRunMode.NORMAL, state),
            )
            views.setContentDescription(
                R.id.cloud_sync_now_root,
                stateLabel(settings, CloudSyncRunMode.NORMAL, state),
            )
            views.setOnClickPendingIntent(
                R.id.cloud_sync_now_root,
                actionPendingIntent(id, CloudSyncRunMode.NORMAL),
            )
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    private fun updateForce(
        manager: AppWidgetManager,
        ids: IntArray,
        settings: AppSettings,
        activeMode: CloudSyncRunMode?,
        state: CloudSyncWidgetState,
    ) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.cloud_sync_force_widget)
            val uploadBackground = opaque(settings.themeColorArgb)
            val downloadBackground = opaque(
                settings.themeSecondaryColorsArgb.firstOrNull() ?: settings.themeColorArgb,
            )
            views.setInt(R.id.cloud_sync_force_upload, "setBackgroundColor", uploadBackground)
            views.setInt(R.id.cloud_sync_force_download, "setBackgroundColor", downloadBackground)
            views.setTextColor(
                R.id.cloud_sync_force_upload_text,
                contentColor(uploadBackground),
            )
            views.setTextColor(
                R.id.cloud_sync_force_download_text,
                contentColor(downloadBackground),
            )
            val uploadState = state.takeIf { activeMode == CloudSyncRunMode.FORCE_UPLOAD }
                ?: CloudSyncWidgetState.IDLE
            val downloadState = state.takeIf { activeMode == CloudSyncRunMode.FORCE_DOWNLOAD }
                ?: CloudSyncWidgetState.IDLE
            val uploadLabel = stateLabel(
                settings,
                CloudSyncRunMode.FORCE_UPLOAD,
                uploadState,
            )
            val downloadLabel = stateLabel(
                settings,
                CloudSyncRunMode.FORCE_DOWNLOAD,
                downloadState,
            )
            views.setTextViewText(R.id.cloud_sync_force_upload_text, uploadLabel)
            views.setTextViewText(R.id.cloud_sync_force_download_text, downloadLabel)
            views.setContentDescription(R.id.cloud_sync_force_upload, uploadLabel)
            views.setContentDescription(R.id.cloud_sync_force_download, downloadLabel)
            views.setOnClickPendingIntent(
                R.id.cloud_sync_force_upload,
                actionPendingIntent(id * 2, CloudSyncRunMode.FORCE_UPLOAD),
            )
            views.setOnClickPendingIntent(
                R.id.cloud_sync_force_download,
                actionPendingIntent(id * 2 + 1, CloudSyncRunMode.FORCE_DOWNLOAD),
            )
            runCatching { manager.updateAppWidget(id, views) }
        }
    }

    private suspend fun loadSettings(): AppSettings = try {
        settingsRepository.settings.first()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AppSettings()
    }

    private fun actionPendingIntent(requestCode: Int, mode: CloudSyncRunMode): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, CloudSyncWidgetActionReceiver::class.java)
                .setAction(actionFor(mode)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun stateLabel(
        settings: AppSettings,
        mode: CloudSyncRunMode,
        state: CloudSyncWidgetState,
    ): String {
        val english = settings.appLanguage == AppLanguage.ENGLISH
        return when (state) {
            CloudSyncWidgetState.IDLE -> when (mode) {
                CloudSyncRunMode.NORMAL -> localized(english, "立即同步", "Sync now")
                CloudSyncRunMode.FORCE_UPLOAD -> localized(english, "强制上传", "Force upload")
                CloudSyncRunMode.FORCE_DOWNLOAD -> localized(english, "强制下载", "Force download")
            }
            CloudSyncWidgetState.QUEUED -> localized(english, "已排队", "Queued")
            CloudSyncWidgetState.RUNNING -> when (mode) {
                CloudSyncRunMode.NORMAL -> localized(english, "正在同步", "Syncing")
                CloudSyncRunMode.FORCE_UPLOAD -> localized(english, "正在上传", "Uploading")
                CloudSyncRunMode.FORCE_DOWNLOAD -> localized(english, "正在下载", "Downloading")
            }
            CloudSyncWidgetState.SUCCEEDED -> localized(english, "已完成", "Completed")
            CloudSyncWidgetState.FAILED -> localized(english, "同步失败", "Sync failed")
            CloudSyncWidgetState.CONFIGURATION_REQUIRED ->
                localized(english, "请检查同步设置", "Check sync settings")
        }
    }

    private fun opaque(color: Int): Int = color or 0xFF000000.toInt()

    private fun contentColor(background: Int): Int =
        if (ColorUtils.calculateLuminance(background) > 0.48) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

    private fun localized(english: Boolean, chinese: String, englishText: String): String =
        if (english) englishText else chinese

    private fun actionFor(mode: CloudSyncRunMode): String = when (mode) {
        CloudSyncRunMode.NORMAL -> CloudSyncWidgetActionReceiver.ACTION_SYNC_NOW
        CloudSyncRunMode.FORCE_UPLOAD -> CloudSyncWidgetActionReceiver.ACTION_FORCE_UPLOAD
        CloudSyncRunMode.FORCE_DOWNLOAD -> CloudSyncWidgetActionReceiver.ACTION_FORCE_DOWNLOAD
    }
}

@AndroidEntryPoint
class CloudSyncNowWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var renderer: CloudSyncWidgetRenderer

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                renderer.updateNow(appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

@AndroidEntryPoint
class CloudSyncForceWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var renderer: CloudSyncWidgetRenderer

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                renderer.updateForce(appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/** Private PendingIntent target, so other applications cannot trigger a destructive sync action. */
@AndroidEntryPoint
class CloudSyncWidgetActionReceiver : BroadcastReceiver() {
    @Inject lateinit var renderer: CloudSyncWidgetRenderer

    override fun onReceive(context: Context, intent: Intent) {
        val mode = when (intent.action) {
            ACTION_SYNC_NOW -> CloudSyncRunMode.NORMAL
            ACTION_FORCE_UPLOAD -> CloudSyncRunMode.FORCE_UPLOAD
            ACTION_FORCE_DOWNLOAD -> CloudSyncRunMode.FORCE_DOWNLOAD
            else -> return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val state = if (CloudSyncManualScheduler.enqueue(context, mode)) {
                    CloudSyncWidgetState.QUEUED
                } else {
                    CloudSyncWidgetState.FAILED
                }
                renderer.update(mode, state)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SYNC_NOW = "com.deskcubby.app.action.CLOUD_SYNC_NOW"
        const val ACTION_FORCE_UPLOAD = "com.deskcubby.app.action.CLOUD_FORCE_UPLOAD"
        const val ACTION_FORCE_DOWNLOAD = "com.deskcubby.app.action.CLOUD_FORCE_DOWNLOAD"
    }
}
