package com.deskcubby.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Private target for the mutable launcher pin callback. */
@AndroidEntryPoint
class DesktopWidgetPinResultReceiver : BroadcastReceiver() {
    @Inject lateinit var instanceStore: DesktopWidgetInstanceStore
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PIN_SUCCEEDED) return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val configId = DesktopWidgetNavigationTokenStore.consumeConfigId(
            intent.getStringExtra(DeskCubbyWidgetConfigureActivity.EXTRA_CONFIG_TOKEN),
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || configId.isNullOrBlank()) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runBoundedWidgetBroadcast(WidgetBroadcastTarget.PIN_BINDING) {
                    if (instanceStore.snapshot(appWidgetId) == null) {
                        settingsRepository.settings.first().desktopWidgetConfigs
                            .firstOrNull { it.id == configId }
                            ?.let { instanceStore.bind(appWidgetId, it) }
                    }
                    DeskCubbyWidgetProvider.requestUpdate(context, intArrayOf(appWidgetId))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PIN_SUCCEEDED = "com.deskcubby.app.action.WIDGET_PIN_SUCCEEDED"
    }
}
