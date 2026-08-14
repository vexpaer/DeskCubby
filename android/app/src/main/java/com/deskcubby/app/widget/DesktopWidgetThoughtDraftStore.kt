package com.deskcubby.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.deskcubby.app.data.repository.ThoughtRepository
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device-local draft shown in the launcher widget's input-shaped field. */
@Singleton
class DesktopWidgetThoughtDraftStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val sendingWidgetIds = mutableSetOf<Int>()

    @Synchronized
    fun get(appWidgetId: Int): String = preferences.getString(key(appWidgetId), null).orEmpty()

    @Synchronized
    fun set(appWidgetId: Int, value: String) {
        val normalized = value.trim().take(MAX_DRAFT_CHARS)
        preferences.edit().apply {
            if (normalized.isEmpty()) remove(key(appWidgetId)) else putString(key(appWidgetId), normalized)
        }.apply()
    }

    @Synchronized
    fun clear(appWidgetId: Int) {
        preferences.edit().remove(key(appWidgetId)).apply()
    }

    /** Claims a draft for one send without deleting it before the repository commit succeeds. */
    @Synchronized
    fun claimForSend(appWidgetId: Int): String? {
        if (!sendingWidgetIds.add(appWidgetId)) return null
        val draft = preferences.getString(key(appWidgetId), null).orEmpty().trim()
        if (draft.isEmpty()) {
            sendingWidgetIds.remove(appWidgetId)
            return null
        }
        return draft
    }

    /** Releases a send claim and only clears the exact draft that was successfully persisted. */
    @Synchronized
    fun completeSend(appWidgetId: Int, claimedDraft: String, persisted: Boolean) {
        if (persisted && preferences.getString(key(appWidgetId), null).orEmpty() == claimedDraft) {
            preferences.edit().remove(key(appWidgetId)).apply()
        }
        sendingWidgetIds.remove(appWidgetId)
    }

    @Synchronized
    fun remove(appWidgetIds: IntArray) {
        preferences.edit().apply {
            appWidgetIds.forEach { remove(key(it)) }
        }.apply()
        sendingWidgetIds.removeAll(appWidgetIds.toSet())
    }

    private fun key(appWidgetId: Int): String = "draft_$appWidgetId"

    private companion object {
        const val FILE_NAME = "desktop_widget_thought_drafts"
        const val MAX_DRAFT_CHARS = 10_000
    }
}

/** A plane tap sends the saved draft straight to the uncategorized thought list. */
@AndroidEntryPoint
class DesktopWidgetQuickThoughtReceiver : BroadcastReceiver() {
    @Inject lateinit var thoughtRepository: ThoughtRepository
    @Inject lateinit var draftStore: DesktopWidgetThoughtDraftStore

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND) return
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val draft = draftStore.claimForSend(appWidgetId)
        if (draft == null) {
            val message = if (draftStore.get(appWidgetId).isBlank()) {
                "请先输入小巧思 / Type a thought first"
            } else {
                "正在发送 / Sending"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var persisted = false
            try {
                thoughtRepository.create(draft, categoryId = null)
                draftStore.completeSend(appWidgetId, draft, persisted = true)
                persisted = true
                DeskCubbyWidgetProvider.requestUpdate(context, intArrayOf(appWidgetId))
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已添加到未分类 / Added to uncategorized", Toast.LENGTH_SHORT).show()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "保存失败 / Could not save", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (!persisted) {
                    draftStore.completeSend(appWidgetId, draft, persisted = false)
                }
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SEND = "com.deskcubby.app.action.WIDGET_QUICK_THOUGHT_SEND"

        fun sendPendingIntent(context: Context, appWidgetId: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                appWidgetId * 37 + ACTION_SEND.hashCode(),
                Intent(context, DesktopWidgetQuickThoughtReceiver::class.java)
                    .setAction(ACTION_SEND)
                    .setData(Uri.parse("deskcubby://widget-quick-thought/$appWidgetId"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
