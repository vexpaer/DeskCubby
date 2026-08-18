package com.deskcubby.app.data.taskqueue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.deskcubby.app.data.local.AiTaskDao
import com.deskcubby.app.data.local.AiTaskQueueEntity
import com.deskcubby.app.data.local.AiTaskStateEntity
import com.deskcubby.app.data.local.AiTaskTypeEntity
import com.deskcubby.app.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Central entry point for all background AI work. Tasks are durable rows in Room; WorkManager only
 * *wakes* the executor while QUEUED work exists and exits when the queue drains, so nothing stays
 * resident between runs.
 *
 * Lifecycle decoupling: once [enqueueTask] returns, execution is owned by [AiTaskWorker] on a
 * WorkManager thread. Navigation, backgrounding, locking, and force-stopping the app cannot cancel
 * a queued task; only an explicit [cancelTask] of a not-yet-run task can.
 */
@Singleton
class AiTaskQueue @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: AppDatabase,
) {
    private val dao: AiTaskDao get() = database.aiTaskDao()
    companion object {
        internal const val WORK_NAME = "deskcubby-ai-task-queue"
        internal const val WORK_TAG = "ai-task"
        private const val MIN_BACKOFF_SECONDS = 20L
        private const val MAX_BACKOFF_SECONDS = 300L
        val TERMINAL_STATES = setOf(
            AiTaskStateEntity.SUCCEEDED,
            AiTaskStateEntity.FAILED,
            AiTaskStateEntity.CANCELED,
        )
    }

    /** Task rows in insertion order. UI observes this to rebuild progress after process death. */
    fun observeTasks(): Flow<List<AiTaskQueueEntity>> = dao.observeAll()

    /**
     * Enqueue one AI task and ensure a worker is running to drain it.
     *
     * @return the new task's row ID.
     */
    suspend fun enqueueTask(
        type: AiTaskTypeEntity,
        payload: AiTaskPayload,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val id = dao.insert(
            AiTaskQueueEntity(
                type = type,
                state = AiTaskStateEntity.QUEUED,
                payloadJson = payload.encode(),
                createdAt = now,
            ),
        )
        ensureScheduled()
        return id
    }

    /**
     * Enqueue a day-scoped calorie task with the same dedup/upgrade semantics the old in-memory
     * queue had: a second request for the same date that is not a force recalculation is dropped;
     * a force recalculation replaces an only-queued (not yet claimed) non-force task in place.
     */
    suspend fun enqueueCalorieDay(payload: CalorieDayTaskPayload): CalorieEnqueueOutcome {
        val nonTerminal = dao.getAll().filter { task ->
            task.type == AiTaskTypeEntity.CALORIE_DAY &&
                task.state !in TERMINAL_STATES
        }
        val existing = nonTerminal.firstOrNull { task ->
            runCatching { CalorieDayTaskPayload.decode(task.payloadJson).dateIso }
                .getOrNull() == payload.dateIso
        }
        if (existing != null) {
            if (!payload.force) return CalorieEnqueueOutcome.DUPLICATE
            val wasForce = runCatching { CalorieDayTaskPayload.decode(existing.payloadJson).force }
                .getOrDefault(false)
            if (wasForce) return CalorieEnqueueOutcome.DUPLICATE
            val replaced = dao.updatePayloadIfQueued(
                id = existing.id,
                payloadJson = payload.encode(),
                queued = AiTaskStateEntity.QUEUED,
            )
            return if (replaced > 0) {
                ensureScheduled()
                CalorieEnqueueOutcome.UPGRADED
            } else {
                CalorieEnqueueOutcome.DUPLICATE
            }
        }
        dao.insert(
            AiTaskQueueEntity(
                type = AiTaskTypeEntity.CALORIE_DAY,
                state = AiTaskStateEntity.QUEUED,
                payloadJson = payload.encode(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        ensureScheduled()
        return CalorieEnqueueOutcome.ADDED
    }

    /** Remove finished calorie task rows from the progress list. */
    suspend fun clearFinishedCalorieTasks() {
        dao.getAll().filter { task ->
            task.type == AiTaskTypeEntity.CALORIE_DAY &&
                task.state in TERMINAL_STATES
        }.forEach { dao.deleteById(it.id) }
    }

    /**
     * Cancel a task that has not started yet. A RUNNING task's network call is cancelled by the
     * worker's own cooperative cancellation when the process dies; there is no cross-process way to
     * cancel a live coroutine, so cancellation only applies to QUEUED work.
     */
    suspend fun cancelTask(id: Long) {
        val task = dao.getById(id) ?: return
        if (task.state != AiTaskStateEntity.QUEUED) return
        dao.markCanceled(id, AiTaskStateEntity.CANCELED, System.currentTimeMillis())
    }

    suspend fun taskById(id: Long): AiTaskQueueEntity? = dao.getById(id)

    fun observeTask(id: Long): Flow<AiTaskQueueEntity?> = dao.observeById(id)

    /** Called from the Application on startup. Re-enqueues an interrupted worker for any leftover
     * non-terminal work (including rows the OS killed mid-`running`).
     */
    suspend fun start(): Unit = runCatching {
        val incomplete = dao.nextIncomplete(
            queued = AiTaskStateEntity.QUEUED,
            running = AiTaskStateEntity.RUNNING,
        )
        if (incomplete != null) ensureScheduled()
    }.getOrDefault(Unit)

    private fun ensureScheduled() {
        val request = OneTimeWorkRequestBuilder<AiTaskWorker>()
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
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

}