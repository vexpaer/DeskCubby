package com.deskcubby.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DesktopWidgetWorkerEntryPoint {
    fun renderer(): DesktopWidgetRenderer
}

/**
 * Durable compensation for launcher update broadcasts. Some launchers may defer a background
 * AppWidgetProvider even after accepting a widget. This worker still obeys WorkManager and system
 * battery limits; it does not try to bypass them.
 */
class DesktopWidgetUpdateWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val provider = ComponentName(applicationContext, DeskCubbyWidgetProvider::class.java)
        val ids = manager.getAppWidgetIds(provider)
        if (ids.isEmpty()) return Result.success()
        return try {
            val renderer = EntryPointAccessors.fromApplication(
                applicationContext,
                DesktopWidgetWorkerEntryPoint::class.java,
            ).renderer()
            if (renderer.update(manager, ids) == ids.size) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

internal object DesktopWidgetUpdateScheduler {
    private const val IMMEDIATE_WORK_NAME = "deskcubby-widget-update-now"
    private const val PERIODIC_WORK_NAME = "deskcubby-widget-update-periodic"
    private const val WORK_TAG = "desktop-widget-update"
    private const val REPEAT_HOURS = 6L

    fun enqueueImmediate(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DesktopWidgetUpdateWorker>()
                    .addTag(WORK_TAG)
                    .build(),
            )
        }
    }

    fun ensurePeriodic(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DesktopWidgetUpdateWorker>(
                    REPEAT_HOURS,
                    TimeUnit.HOURS,
                )
                    .addTag(WORK_TAG)
                    .build(),
            )
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(IMMEDIATE_WORK_NAME)
                cancelUniqueWork(PERIODIC_WORK_NAME)
            }
        }
    }
}
