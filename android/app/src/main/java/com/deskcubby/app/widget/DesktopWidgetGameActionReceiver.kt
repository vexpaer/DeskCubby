package com.deskcubby.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles desktop-widget game taps and the alarm-driven ticks that advance automatic games
 * (snake movement, tetris gravity) directly on the home screen.
 */
@AndroidEntryPoint
class DesktopWidgetGameActionReceiver : BroadcastReceiver() {
    @Inject lateinit var renderer: DesktopWidgetGameRenderer
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_GAME_ACTION -> handleGameAction(context, intent)
            ACTION_GAME_TICK -> handleTick(context, intent)
        }
    }

    private fun handleGameAction(context: Context, intent: Intent) {
        val gameId = intent.getStringExtra(DesktopWidgetGameRenderer.EXTRA_GAME_ID) ?: return
        val actionName = intent.getStringExtra(DesktopWidgetGameRenderer.EXTRA_GAME_ACTION) ?: return
        val action = runCatching { WidgetGameAction.valueOf(actionName) }.getOrNull() ?: return
        val cell = intent.getIntExtra(DesktopWidgetGameRenderer.EXTRA_GAME_CELL, -1)
        val appWidgetId = widgetIdFrom(intent) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runBoundedWidgetBroadcast(WidgetBroadcastTarget.GAME_ACTION) {
                    val settings = try {
                        settingsRepository.settings.first()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        com.deskcubby.app.data.model.AppSettings()
                    }
                    val views = renderer.render(appWidgetId, gameId, action, cell, settings)
                    if (views != null) {
                        runCatching {
                            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
                        }
                    }
                    if (action == WidgetGameAction.NEW || action == WidgetGameAction.TICK) {
                        scheduleTick(context, appWidgetId, gameId)
                    } else if (needsTicker(gameId)) {
                        scheduleTick(context, appWidgetId, gameId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleTick(context: Context, intent: Intent) {
        val gameId = intent.getStringExtra(EXTRA_TICK_GAME_ID) ?: return
        val appWidgetId = widgetIdFrom(intent) ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runBoundedWidgetBroadcast(WidgetBroadcastTarget.GAME_TICK) {
                    val settings = try {
                        settingsRepository.settings.first()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        com.deskcubby.app.data.model.AppSettings()
                    }
                    val views = renderer.render(appWidgetId, gameId, WidgetGameAction.TICK, -1, settings)
                    if (views != null) {
                        runCatching {
                            AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, views)
                        }
                    }
                    // Keep the game running while the panel is still placed and the round is alive.
                    val manager = AppWidgetManager.getInstance(context)
                    val placed = manager.getAppWidgetIds(
                        ComponentName(context, DeskCubbyWidgetProvider::class.java),
                    ).contains(appWidgetId)
                    if (placed) scheduleTick(context, appWidgetId, gameId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun scheduleTick(context: Context, appWidgetId: Int, gameId: String) {
        if (!needsTicker(gameId)) return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val identity = "deskcubby://widget-game-tick/" + appWidgetId + "/" + gameId
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * 31 + gameId.hashCode(),
            Intent(context, DesktopWidgetGameActionReceiver::class.java)
                .setAction(ACTION_GAME_TICK)
                .setData(Uri.parse(identity))
                .putExtra(EXTRA_TICK_GAME_ID, gameId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + TICK_INTERVAL_MILLIS, pendingIntent)
        } catch (_: Exception) {
            // Alarm scheduling can be restricted; the game still responds to manual buttons.
        }
    }

    private fun widgetIdFrom(intent: Intent): Int? =
        intent.data?.pathSegments?.firstOrNull()?.toIntOrNull()

    private fun needsTicker(gameId: String): Boolean =
        gameId == "snake" || gameId == "tetris"

    companion object {
        const val ACTION_GAME_ACTION = "com.deskcubby.app.action.WIDGET_GAME_ACTION"
        const val ACTION_GAME_TICK = "com.deskcubby.app.action.WIDGET_GAME_TICK"
        const val EXTRA_TICK_GAME_ID = "com.deskcubby.app.extra.WIDGET_TICK_GAME_ID"
        private const val TICK_INTERVAL_MILLIS = 400L
    }
}
