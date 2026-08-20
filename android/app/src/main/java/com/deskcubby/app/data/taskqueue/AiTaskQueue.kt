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
    private val agentPermissionManager: com.deskcubby.app.agent.AgentPermissionManager,
) {
    private val dao: AiTaskDao get() = database.aiTaskDao()

    init {
        // Let a durable approve-after-restart wake the worker to resume the re-queued task.
        agentPermissionManager.attachResolutionScheduler(::ensureScheduled)
    }

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
     * Cancel a task. QUEUED work is cancelled immediately. A RUNNING or WAITING_APPROVAL task is
     * marked CANCEL_REQUESTED (persisted) so the worker's execution loops stop cooperatively at the
     * next long-step boundary and coalesce the row to CANCELED; a pending approval, if any, is
     * rejected so the blocked executor unwinds.
     */
    suspend fun cancelTask(id: Long) {
        val task = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        when (task.state) {
            AiTaskStateEntity.QUEUED ->
                dao.markCanceled(id, AiTaskStateEntity.CANCELED, now)
            AiTaskStateEntity.RUNNING,
            AiTaskStateEntity.WAITING_APPROVAL,
            -> {
                // The state flip and the approval-rejection run under the permission manager's state
                // Mutex, so a concurrent authorize() can never overwrite CANCEL_REQUESTED back to
                // WAITING_APPROVAL after this cancel. rejectPendingForTask is called unconditionally
                // (it matches the live waiter by taskId, not by a pre-lock state snapshot): even if
                // authorize flipped RUNNING -> WAITING_APPROVAL in the same window, the live waiter
                // is still completed so the blocked executor unwinds instead of stranding forever.
                agentPermissionManager.withStateLock {
                    dao.markCancelRequested(
                        id = id,
                        cancelRequested = AiTaskStateEntity.CANCEL_REQUESTED,
                        running = AiTaskStateEntity.RUNNING,
                        waitingApproval = AiTaskStateEntity.WAITING_APPROVAL,
                    )
                    agentPermissionManager.rejectPendingForTask(id)
                }
            }
            else -> Unit
        }
    }

    suspend fun taskById(id: Long): AiTaskQueueEntity? = dao.getById(id)

    fun observeTask(id: Long): Flow<AiTaskQueueEntity?> = dao.observeById(id)

    /** Called from the Application on startup. Re-surfaces any persisted pending approval and
     * re-enqueues an interrupted worker for leftover non-terminal work. Interrupted RUNNING rows are
     * re-queued by the next drain (their lease owner is gone), so no task can stay RUNNING forever. */
    suspend fun start(): Unit = runCatching {
        agentPermissionManager.refreshPendingFromDb()
        // A task the user stopped but whose worker died before coalescing is finalized now.
        dao.coalesceCancelRequested(
            canceled = AiTaskStateEntity.CANCELED,
            cancelRequested = AiTaskStateEntity.CANCEL_REQUESTED,
            now = System.currentTimeMillis(),
        )
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