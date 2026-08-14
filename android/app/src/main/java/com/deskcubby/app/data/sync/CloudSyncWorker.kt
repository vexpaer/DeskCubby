package com.deskcubby.app.data.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Observer
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
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.model.AppSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.deskcubby.app.widget.CloudSyncWidgetRenderer
import com.deskcubby.app.widget.CloudSyncWidgetState
import com.deskcubby.app.widget.DeskCubbyWidgetProvider
import com.deskcubby.app.widget.requestIndependentCloudWidgetUpdates
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
        var terminalWidgetState: CloudSyncWidgetState? = null
        return try {
            widgetRenderer?.update(mode, CloudSyncWidgetState.RUNNING)
            try {
                val runs = service.syncEnabled(mode)
                if (runs.any { it.errorMessage != null }) {
                    terminalWidgetState = CloudSyncWidgetState.FAILED
                    // A manual failure is surfaced by the widget and is terminal for this node.
                    cloudSyncFailureResolution(
                        manual = manual,
                        configurationRequired = false,
                    ).toWorkResult()
                } else {
                    terminalWidgetState = CloudSyncWidgetState.SUCCEEDED
                    Result.success()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: CloudSyncConfigurationException) {
                terminalWidgetState = CloudSyncWidgetState.CONFIGURATION_REQUIRED
                cloudSyncFailureResolution(
                    manual = manual,
                    configurationRequired = true,
                ).toWorkResult()
            } catch (_: Exception) {
                terminalWidgetState = CloudSyncWidgetState.FAILED
                cloudSyncFailureResolution(
                    manual = manual,
                    configurationRequired = false,
                ).toWorkResult()
            }
        } finally {
            if (manual) {
                withContext(NonCancellable) {
                    try {
                        CloudSyncManualQueueState.clear(applicationContext)
                    } finally {
                        runCatching {
                            widgetRenderer?.update(
                                mode,
                                finalCloudWidgetState(terminalWidgetState),
                            )
                        }
                        // Refresh every desktop card (the combined cloud-sync app panel shows
                        // the upload/download/conflict counts of the last completed run).
                        runCatching { DeskCubbyWidgetProvider.requestUpdate(applicationContext) }
                    }
                }
            }
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
    internal const val WORK_NAME = "deskcubby-cloud-sync-manual"
    private const val WORK_TAG = "cloud-sync-manual"
    private const val MIN_BACKOFF_SECONDS = 30L

    fun enqueue(context: Context, mode: CloudSyncRunMode): Boolean {
        if (!CloudSyncManualQueueState.tryMark(context, mode)) return false
        return runCatching {
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
        }.onFailure {
            CloudSyncManualQueueState.clear(context)
        }.isSuccess
    }
}

internal object CloudSyncManualQueueState {
    private const val PREFS = "cloud_sync_manual_queue"
    private const val KEY_MODE = "queued_mode"
    private const val KEY_MARKED_AT = "queued_marked_at"
    internal const val STALE_AFTER_MS = 24L * 60L * 60L * 1_000L
    internal const val ENQUEUE_GRACE_MS = 10_000L
    private val mutableQueuedMode = MutableStateFlow<CloudSyncRunMode?>(null)
    val queuedModeFlow: StateFlow<CloudSyncRunMode?> = mutableQueuedMode.asStateFlow()

    @Synchronized
    fun tryMark(context: Context, mode: CloudSyncRunMode): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (queuedMode(context) != null) return false
        if (!prefs.edit()
                .putString(KEY_MODE, mode.name)
                .putLong(KEY_MARKED_AT, System.currentTimeMillis())
                .commit()
        ) return false
        mutableQueuedMode.value = mode
        DeskCubbyWidgetProvider.requestUpdate(context.applicationContext)
        return true
    }

    fun queuedMode(context: Context): CloudSyncRunMode? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, null)
            ?.let { raw -> CloudSyncRunMode.entries.firstOrNull { it.name == raw } }
        if (mode == null) {
            prefs.edit().remove(KEY_MODE).remove(KEY_MARKED_AT).commit()
            mutableQueuedMode.value = null
            return null
        }
        val markedAt = prefs.getLong(KEY_MARKED_AT, 0L)
        if (!isCloudQueueMarkerFresh(markedAt, System.currentTimeMillis())) {
            // Preserve stale markers until the suspend WorkManager reconciliation can prove that
            // no ENQUEUED/RUNNING/BLOCKED work exists. Query failure must fail closed.
            mutableQueuedMode.value = mode
            return mode
        }
        mutableQueuedMode.value = mode
        return mode
    }

    suspend fun reconcile(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val markedAt = prefs.getLong(KEY_MARKED_AT, 0L)
        val mode = queuedMode(appContext) ?: return
        val now = System.currentTimeMillis()
        val infos = awaitManualCloudWorkInfos(appContext) ?: return
        val live = infos.any { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        }
        val recheckDelay = manualQueueMarkerRecheckDelayMs(live, markedAt, now)
        if (recheckDelay != null) {
            delay(recheckDelay)
            if (mutableQueuedMode.value == mode) reconcile(appContext)
            return
        }
        if (
            shouldRefreshIndependentCloudWidgetsAfterReconcile(live, markedAt, now) &&
            mutableQueuedMode.value == mode
        ) {
            clear(appContext)
            requestIndependentCloudWidgetUpdates(appContext)
        }
    }

    @Synchronized
    fun clear(context: Context, refreshWidgets: Boolean = true) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_MODE)
            .remove(KEY_MARKED_AT)
            .commit()
        mutableQueuedMode.value = null
        if (refreshWidgets) DeskCubbyWidgetProvider.requestUpdate(context.applicationContext)
    }
}

private suspend fun awaitManualCloudWorkInfos(context: Context): List<WorkInfo>? =
    withTimeoutOrNull(1_000L) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val liveData = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkLiveData(CloudSyncManualScheduler.WORK_NAME)
                lateinit var observer: Observer<List<WorkInfo>>
                observer = Observer { infos ->
                    liveData.removeObserver(observer)
                    if (continuation.isActive) continuation.resume(infos.orEmpty())
                }
                continuation.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { liveData.removeObserver(observer) }
                }
                liveData.observeForever(observer)
            }
        }
    }

internal fun isCloudQueueMarkerFresh(markedAt: Long, now: Long): Boolean =
    markedAt > 0L && now >= markedAt && now - markedAt <= CloudSyncManualQueueState.STALE_AFTER_MS

internal fun finalCloudWidgetState(
    terminalState: CloudSyncWidgetState?,
): CloudSyncWidgetState = terminalState ?: CloudSyncWidgetState.IDLE

internal fun shouldClearManualQueueMarker(
    hasLiveWork: Boolean,
    markedAt: Long,
    now: Long,
): Boolean = !hasLiveWork &&
    markedAt > 0L &&
    now >= markedAt &&
    now - markedAt > CloudSyncManualQueueState.ENQUEUE_GRACE_MS

internal fun manualQueueMarkerRecheckDelayMs(
    hasLiveWork: Boolean,
    markedAt: Long,
    now: Long,
): Long? {
    if (hasLiveWork || markedAt <= 0L || now < markedAt) return null
    val age = now - markedAt
    return if (age <= CloudSyncManualQueueState.ENQUEUE_GRACE_MS) {
        CloudSyncManualQueueState.ENQUEUE_GRACE_MS - age + 1L
    } else {
        null
    }
}

internal fun shouldRefreshIndependentCloudWidgetsAfterReconcile(
    hasLiveWork: Boolean,
    markedAt: Long,
    now: Long,
): Boolean = shouldClearManualQueueMarker(hasLiveWork, markedAt, now)

private fun cloudSyncNetworkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

/** Keeps one network-constrained periodic sync request aligned with the global setting. */
@Singleton
class CloudSyncScheduler @Inject constructor(
    @ApplicationContext context: Context,
    settingsRepository: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val settings = settingsRepository.settings

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            CloudSyncManualQueueState.reconcile(appContext)
            settings
                .map(::cloudSyncSchedulerSignature)
                .distinctUntilChanged()
                .collect { signature ->
                    DeskCubbyWidgetProvider.requestUpdate(appContext)
                    if (signature.enabled) enqueuePeriodic()
                    else workManager.cancelUniqueWork(WORK_NAME)
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

internal data class CloudSyncSchedulerSignature(
    val globallyEnabled: Boolean,
    val enabledConfigIds: List<String>,
) {
    val enabled: Boolean get() = globallyEnabled && enabledConfigIds.isNotEmpty()
}

internal fun cloudSyncSchedulerSignature(settings: AppSettings): CloudSyncSchedulerSignature =
    CloudSyncSchedulerSignature(
        globallyEnabled = settings.cloudSyncEnabled,
        enabledConfigIds = settings.cloudSyncConfigs.filter { it.enabled }.map { it.id }.sorted(),
    )
