package com.deskcubby.app.data.taskqueue

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.deskcubby.app.agent.AgentPermissionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlin.coroutines.cancellation.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface AiTaskWorkerEntryPoint {
    fun aiTaskRunner(): AiTaskRunner
    fun aiPermissionManager(): AgentPermissionManager
}

/**
 * Drains the durable AI task queue. One WorkManager run claims QUEUED rows, executes them, and
 * exits when the queue is empty — nothing stays resident between runs. Interrupted (RUNNING) rows
 * are re-claimed by the next drain, which the Application schedules on startup and each enqueue.
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
        val runner = entryPoint.aiTaskRunner()
        return try {
            runner.drain(shouldContinue = {
                !isStopped
            })
            Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager stopped us (process kill or stop). Leave RUNNING rows in place; the
            // next drain re-claims them from a durable checkpoint.
            Result.failure()
        }
    }
}