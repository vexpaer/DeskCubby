package com.deskcubby.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DeskCubbyWidgetProvider : AppWidgetProvider() {
    @Inject lateinit var renderer: DesktopWidgetRenderer
    @Inject lateinit var instanceStore: DesktopWidgetInstanceStore

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_PIN_SUCCEEDED) return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val configId = intent.getStringExtra(DeskCubbyWidgetConfigureActivity.EXTRA_CONFIG_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || configId.isNullOrBlank()) return
        // A launcher configuration activity may already have made an explicit selection. Keep
        // that choice; otherwise bind the design from the in-app pin request.
        if (instanceStore.configId(appWidgetId) == null) {
            instanceStore.bind(appWidgetId, configId)
        }
        requestUpdate(context, intArrayOf(appWidgetId))
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
