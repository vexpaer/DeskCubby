package com.deskcubby.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deskcubby.app.data.preferences.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.deskcubby.app.widget.CloudSyncWidgetRenderer
import com.deskcubby.app.widget.CloudSyncWidgetState
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
    fun cloudSyncWidgetRenderer(): CloudSyncWidgetRenderer
}

class CloudSyncWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            CloudSyncWorkerEntryPoint::class.java,
        )
        val service = entryPoint.cloudSyncService()
        val rawMode = inputData.getString(KEY_RUN_MODE)
        val manual = rawMode != null
        val mode = parseCloudSyncRunMode(rawMode)
        val widgetRenderer = entryPoint.cloudSyncWidgetRenderer().takeIf { manual }
        widgetRenderer?.update(mode, CloudSyncWidgetState.RUNNING)
        return try {
            val runs = service.syncEnabled(mode)
            if (runs.any { it.errorMessage != null }) {
                widgetRenderer?.update(mode, CloudSyncWidgetState.FAILED)
                // A manual failure has already been surfaced by the widget. Mark this WorkManager
                // node complete so a different action appended behind it is still allowed to run;
                // otherwise dependency failure propagation would silently discard that action.
                cloudSyncFailureResolution(
                    manual = manual,
                    configurationRequired = false,
                ).toWorkResult()
            } else {
                widgetRenderer?.update(mode, CloudSyncWidgetState.SUCCEEDED)
                Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CloudSyncConfigurationException) {
            widgetRenderer?.update(mode, CloudSyncWidgetState.CONFIGURATION_REQUIRED)
            cloudSyncFailureResolution(
                manual = manual,
                configurationRequired = true,
            ).toWorkResult()
        } catch (_: Exception) {
            widgetRenderer?.update(mode, CloudSyncWidgetState.FAILED)
            cloudSyncFailureResolution(
                manual = manual,
                configurationRequired = false,
            ).toWorkResult()
        }
    }

    companion object {
        internal const val KEY_RUN_MODE = "cloud_sync_run_mode"
    }
}

internal fun parseCloudSyncRunMode(raw: String?): CloudSyncRunMode =
    CloudSyncRunMode.entries.firstOrNull { it.name == raw } ?: CloudSyncRunMode.NORMAL

internal enum class CloudSyncWorkResolution {
    COMPLETE,
    FAIL,
    RETRY,
}

/** Manual failures are UI-level terminal results, not failed prerequisites for later actions. */
internal fun cloudSyncFailureResolution(
    manual: Boolean,
    configurationRequired: Boolean,
): CloudSyncWorkResolution = when {
    manual -> CloudSyncWorkResolution.COMPLETE
    configurationRequired -> CloudSyncWorkResolution.FAIL
    else -> CloudSyncWorkResolution.RETRY
}

private fun CloudSyncWorkResolution.toWorkResult(): ListenableWorker.Result = when (this) {
    CloudSyncWorkResolution.COMPLETE -> ListenableWorker.Result.success()
    CloudSyncWorkResolution.FAIL -> ListenableWorker.Result.failure()
    CloudSyncWorkResolution.RETRY -> ListenableWorker.Result.retry()
}

/**
 * Enqueues launcher-triggered actions through one network-constrained serial chain.
 *
 * APPEND_OR_REPLACE matters here: KEEP would silently discard a second, different action while a
 * sync was already queued, even though its widget had just reported that action as queued.
 */
object CloudSyncManualScheduler {
    private const val WORK_NAME = "deskcubby-cloud-sync-manual"
    private const val WORK_TAG = "cloud-sync-manual"
    private const val MIN_BACKOFF_SECONDS = 30L

    fun enqueue(context: Context, mode: CloudSyncRunMode): Boolean = runCatching {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setInputData(
                Data.Builder()
                    .putString(CloudSyncWorker.KEY_RUN_MODE, mode.name)
                    .build(),
            )
            .setConstraints(cloudSyncNetworkConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }.isSuccess
}

private fun cloudSyncNetworkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

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
            .setConstraints(cloudSyncNetworkConstraints())
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
