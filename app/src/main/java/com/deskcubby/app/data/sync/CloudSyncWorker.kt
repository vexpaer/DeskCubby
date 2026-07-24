package com.deskcubby.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CloudSyncWorkerEntryPoint {
    fun cloudSyncService(): AppCloudSyncService
}

class CloudSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val service = EntryPointAccessors.fromApplication(
            applicationContext,
            CloudSyncWorkerEntryPoint::class.java,
        ).cloudSyncService()
        return try {
            val runs = service.syncEnabled()
            if (runs.any { it.errorMessage != null }) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CloudSyncConfigurationException) {
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

/** Keeps one network-constrained periodic sync request aligned with the global setting. */
@Singleton
class CloudSyncScheduler @Inject constructor(
    @ApplicationContext context: Context,
    settingsRepository: SettingsRepository,
) {
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val settings = settingsRepository.settings

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settings
                .map { current ->
                    current.cloudSyncEnabled &&
                        current.cloudSyncConfigs.any { it.enabled }
                }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) enqueuePeriodic() else workManager.cancelUniqueWork(WORK_NAME)
                }
        }
    }

    private fun enqueuePeriodic() {
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            REPEAT_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
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
        const val WORK_NAME = "deskcubby-cloud-sync"
        const val WORK_TAG = "cloud-sync"
        const val REPEAT_HOURS = 6L
        const val MIN_BACKOFF_SECONDS = 30L
    }
}
