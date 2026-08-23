package com.deskcubby.app.data.taskqueue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.deskcubby.app.data.local.AiTaskDao
import com.deskcubby.app.data.local.AiTaskQueueEntity
import com.deskcubby.app.data.local.AiTaskStateEntity
import com.deskcubby.app.data.local.AiTaskTypeEntity
import com.deskcubby.app.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Central entry point for background AI work. Room owns durable task state; WorkManager only wakes
 * executors. Each enqueue creates an independent wake-up instead of appending every AI operation to
 * one unique WorkManager chain, so a slow Agent/approval can never block calorie/image work behind it.
 */
@Singleton
class AiTaskQueue @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: AppDatabase,
    private val agentPermissionManager: com.deskcubby.app.agent.AgentPermissionManager,
) {
    private val dao: AiTaskDao get() = database.aiTaskDao()

    init {
        // Let a durable approve-after-restart wake an executor to resume the re-queued task.
        agentPermissionManager.attachResolutionScheduler(::ensureScheduled)
    }

    companion object {
        internal const val WORK_TAG = "ai-task"
        internal const val TASK_ID_KEY = "task_id"
        private const val WORK_NAME_PREFIX = "deskcubby-ai-task-"
        private const val MIN_BACKOFF_SECONDS = 20L
        val TERMINAL_STATES = setOf(
            AiTaskStateEntity.SUCCEEDED,
            AiTaskStateEntity.FAILED,
            AiTaskStateEntity.CANCELED,
        )
    }

    /** Task rows in insertion order. UI observes this to rebuild progress after process death. */
    fun observeTasks(): Flow<List<AiTaskQueueEntity>> = dao.observeAll()

    /** Enqueue one durable AI task and give it its own WorkManager wake-up. */
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
        ensureScheduled(id)
        return id
    }

    /**
     * Enqueue a day-scoped calorie task. A normal duplicate for the same date is ignored; a force
     * recalculation may upgrade an only-queued non-force task in place.
     */
    suspend fun enqueueCalorieDay(payload: CalorieDayTaskPayload): CalorieEnqueueOutcome {
        val nonTerminal = dao.getAll().filter { task ->
            task.type == AiTaskTypeEntity.CALORIE_DAY && task.state !in TERMINAL_STATES
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
                ensureScheduled(existing.id)
                CalorieEnqueueOutcome.UPGRADED
            } else {
                CalorieEnqueueOutcome.DUPLICATE
            }
        }
        val id = dao.insert(
            AiTaskQueueEntity(
                type = AiTaskTypeEntity.CALORIE_DAY,
                state = AiTaskStateEntity.QUEUED,
                payloadJson = payload.encode(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        ensureScheduled(id)
        return CalorieEnqueueOutcome.ADDED
    }

    suspend fun clearFinishedCalorieTasks() {
        dao.getAll().filter { task ->
            task.type == AiTaskTypeEntity.CALORIE_DAY && task.state in TERMINAL_STATES
        }.forEach { dao.deleteById(it.id) }
    }

    suspend fun cancelTask(id: Long) {
        val task = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        when (task.state) {
            AiTaskStateEntity.QUEUED ->
                dao.markCanceled(id, AiTaskStateEntity.CANCELED, now)
            AiTaskStateEntity.RUNNING,
            AiTaskStateEntity.WAITING_APPROVAL,
            -> {
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

    /**
     * Startup recovery gives every persisted runnable row its own WorkRequest. Workers execute one
     * task each, so Agent and calorie/image work remain independent after process death as well as
     * during normal enqueue.
     */
    suspend fun start() {
        try {
            agentPermissionManager.refreshPendingFromDb()
            dao.coalesceCancelRequested(
                canceled = AiTaskStateEntity.CANCELED,
                cancelRequested = AiTaskStateEntity.CANCEL_REQUESTED,
                now = System.currentTimeMillis(),
            )
            dao.getAll()
                .filter { task ->
                    task.state == AiTaskStateEntity.QUEUED || task.state == AiTaskStateEntity.RUNNING
                }
                .forEach { task -> ensureScheduled(task.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Startup remains best effort. The next enqueue or application start schedules again.
        }
    }

    /**
     * Do not use one global unique-work chain here. Independent, per-row unique WorkRequests let
     * multiple workers claim distinct Room rows without scheduling duplicates for the same task.
     */
    private fun ensureScheduled(taskId: Long) {
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
            .setInputData(workDataOf(TASK_ID_KEY to taskId))
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "$WORK_NAME_PREFIX$taskId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
