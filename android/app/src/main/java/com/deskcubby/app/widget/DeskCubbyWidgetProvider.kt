package com.deskcubby.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class DeskCubbyWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var renderer: DesktopWidgetRenderer
    @Inject lateinit var instanceStore: DesktopWidgetInstanceStore
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_PIN_SUCCEEDED) return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val configId = intent.getStringExtra(DeskCubbyWidgetConfigureActivity.EXTRA_CONFIG_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || configId.isNullOrBlank()) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A launcher configuration activity may already have stored an independent
                // snapshot. The pin callback only fills the gap for launchers that skip it.
                if (instanceStore.snapshot(appWidgetId) == null) {
                    settingsRepository.settings.first().desktopWidgetConfigs
                        .firstOrNull { it.id == configId }
                        ?.let { instanceStore.bind(appWidgetId, it) }
                }
                requestUpdate(context, intArrayOf(appWidgetId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onEnabled(context: Context) {
        DesktopWidgetUpdateScheduler.ensurePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        DesktopWidgetUpdateScheduler.cancel(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                renderer.update(appWidgetManager, appWidgetIds)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        instanceStore.remove(appWidgetIds)
    }

    companion object {
        const val ACTION_PIN_SUCCEEDED = "com.deskcubby.app.action.WIDGET_PIN_SUCCEEDED"

        fun requestUpdate(context: Context, appWidgetIds: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DeskCubbyWidgetProvider::class.java)
            val ids = appWidgetIds ?: manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
            // Some OEM launchers defer or suppress provider broadcasts while the app is
            // background-restricted. WorkManager gives the same update a durable fallback;
            // platform/OEM battery restrictions still apply and cannot be bypassed.
            DesktopWidgetUpdateScheduler.enqueueImmediate(context)
            DesktopWidgetUpdateScheduler.ensurePeriodic(context)
        }
    }
}
