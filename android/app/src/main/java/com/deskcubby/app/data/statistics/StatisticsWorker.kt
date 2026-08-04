package com.deskcubby.app.data.statistics

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface StatisticsWorkerEntryPoint {
    fun settingsRepository(): SettingsRepository
    fun usageStatisticsRepository(): UsageStatisticsRepository
    fun stepStatisticsRepository(): StepStatisticsRepository
}

class StatisticsWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            StatisticsWorkerEntryPoint::class.java,
        )
        return try {
            val settings = entryPoint.settingsRepository().settings.first()
            val outcomes = buildList {
                if (settings.usageTrackingEnabled) {
                    add(entryPoint.usageStatisticsRepository().refresh())
                }
                if (settings.stepTrackingEnabled) {
                    val steps = entryPoint.stepStatisticsRepository()
                    if (steps.canReadInBackground()) {
                        add(steps.refresh(fromBackground = true))
                    }
                }
            }
            if (StatisticsRefreshOutcome.ERROR in outcomes) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/**
 * Keeps one six-hour compensation worker aligned with the two local tracking
 * switches. Cancelling work never removes either app-private Room history.
 */
@Singleton
class StatisticsScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settingsRepository.settings
                .map { it.usageTrackingEnabled || it.stepTrackingEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) enqueuePeriodic() else workManager.cancelUniqueWork(WORK_NAME)
                }
        }
    }

    private fun enqueuePeriodic() {
        val request = PeriodicWorkRequestBuilder<StatisticsWorker>(
            REPEAT_HOURS,
            TimeUnit.HOURS,
        )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private companion object {
        const val WORK_NAME = "deskcubby-local-statistics"
        const val WORK_TAG = "local-statistics"
        const val REPEAT_HOURS = 6L
    }
}
