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
    @Inject lateinit var thoughtDraftStore: DesktopWidgetThoughtDraftStore

    override fun onEnabled(context: Context) {
        DesktopWidgetUpdateScheduler.ensurePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        DesktopWidgetUpdateScheduler.cancel(context)
        DesktopWidgetMusicVisualizerService.stop(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runBoundedWidgetBroadcast(WidgetBroadcastTarget.DESKTOP_CONTENT_UPDATE) {
                    renderer.update(appWidgetManager, appWidgetIds)
                }
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
        thoughtDraftStore.remove(appWidgetIds)
        val manager = AppWidgetManager.getInstance(context)
        val deleted = appWidgetIds.toSet()
        val remaining = manager.getAppWidgetIds(
            ComponentName(context, DeskCubbyWidgetProvider::class.java),
        ).filterNot(deleted::contains).toIntArray()
        if (remaining.isEmpty()) {
            DesktopWidgetMusicVisualizerService.stop(context)
        } else {
            requestUpdate(context, remaining)
        }
    }

    companion object {
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
