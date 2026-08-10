package com.deskcubby.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deskcubby.app.data.repository.PoetryBookRepository
import com.deskcubby.app.data.repository.PoetryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Only quick, validated enqueueing happens in the receiver; network and Room work stays durable. */
class DesktopWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = DesktopWidgetWorkerAction.fromIntentAction(intent.action) ?: return
        DesktopWidgetActionScheduler.enqueue(context, action)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DesktopWidgetActionEntryPoint {
    fun poetryRepository(): PoetryRepository
    fun poetryBookRepository(): PoetryBookRepository
}

class DesktopWidgetActionWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val action = DesktopWidgetWorkerAction.fromKey(inputData.getString(KEY_ACTION))
            ?: return Result.failure()
        val repositories = EntryPointAccessors.fromApplication(
            applicationContext,
            DesktopWidgetActionEntryPoint::class.java,
        )
        return try {
            when (action) {
                DesktopWidgetWorkerAction.REFRESH_POEM ->
                    repositories.poetryRepository().refresh(force = true)
                DesktopWidgetWorkerAction.SAVE_POEM -> {
                    val poem = repositories.poetryRepository().poem.first()
                    repositories.poetryBookRepository().create(
                        poem.fullContent.ifBlank { poem.content },
                        poem.source,
                    )
                }
            }
            // Re-render only after the repository action has really completed.
            DeskCubbyWidgetProvider.requestUpdate(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < MAX_RETRY_COUNT) Result.retry() else Result.failure()
        }
    }

    internal companion object {
        const val KEY_ACTION = "widget_action"
        private const val MAX_RETRY_COUNT = 2
    }
}

internal object DesktopWidgetActionScheduler {
    fun enqueue(context: Context, action: DesktopWidgetWorkerAction) {
        val data = Data.Builder().putString(DesktopWidgetActionWorker.KEY_ACTION, action.key).build()
        val request = OneTimeWorkRequestBuilder<DesktopWidgetActionWorker>()
            .setInputData(data)
            .addTag("desktop-widget-action")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "desktop-widget-action-${action.key}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

internal enum class DesktopWidgetWorkerAction(
    val key: String,
    val intentAction: String,
) {
    REFRESH_POEM("refresh_poem", "com.deskcubby.app.action.WIDGET_REFRESH_POEM"),
    SAVE_POEM("save_poem", "com.deskcubby.app.action.WIDGET_SAVE_POEM"),
    ;

    companion object {
        fun fromKey(value: String?): DesktopWidgetWorkerAction? = entries.firstOrNull {
            it.key == value
        }

        fun fromIntentAction(value: String?): DesktopWidgetWorkerAction? = entries.firstOrNull {
            it.intentAction == value
        }
    }
}
