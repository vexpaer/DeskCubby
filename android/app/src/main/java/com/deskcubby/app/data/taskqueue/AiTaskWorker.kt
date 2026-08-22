package com.deskcubby.app.data.taskqueue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deskcubby.app.data.repository.LegacyAiKeyMigrator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.cancellation.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AiTaskWorkerEntryPoint {
    fun aiTaskRunner(): AiTaskRunner
    fun legacyAiKeyMigrator(): LegacyAiKeyMigrator
}

/**
 * Executes at most one durable AI task. Each queued AI operation gets an independent WorkRequest,
 * so Agent work and calorie/image work can run in parallel. Ordering exists only inside a single
 * task, for example image recognition -> calorie estimation -> save.
 */
class AiTaskWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            AiTaskWorkerEntryPoint::class.java,
        )
        return try {
            // This is deliberately repeated at the execution boundary. The singleton migrator is
            // mutex-protected, so a task launched immediately after app/widget startup cannot race
            // the application-level legacy-key migration and fail with a transient empty API key.
            entryPoint.legacyAiKeyMigrator().migrateIfNeeded()

            val taskId = inputData.getLong(AiTaskQueue.TASK_ID_KEY, -1L)
            if (taskId >= 0L) {
                entryPoint.aiTaskRunner().runTask(taskId)
            } else {
                // WorkManager may restore a request created by the previous queue implementation.
                var mayClaim = true
                entryPoint.aiTaskRunner().drain(shouldContinue = {
                    if (isStopped || !mayClaim) false else {
                        mayClaim = false
                        true
                    }
                })
            }
            Result.success()
        } catch (_: CancellationException) {
            // A stopped CoroutineWorker is not an AI failure. The RUNNING row deliberately remains
            // durable; retrying creates a new lease that reclaims it after network/system
            // interruption instead of stranding the task until another enqueue or app restart.
            Result.retry()
        } catch (_: Exception) {
            // Failures before a task is claimed (for example a transient DataStore/KeyStore read)
            // must not strand a durable QUEUED row. AiTaskRunner converts claimed task failures to
            // terminal rows itself, so an exception reaching here is infrastructure-level.
            Result.retry()
        }
    }
}
