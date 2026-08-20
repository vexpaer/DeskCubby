package com.deskcubby.app.agent

import com.deskcubby.app.data.local.AgentApprovalDao
import com.deskcubby.app.data.local.AgentApprovalRequestEntity
import com.deskcubby.app.data.local.AiTaskDao
import com.deskcubby.app.data.local.AiTaskStateEntity
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.model.AgentPermissionMode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Authorization gateway for Agent mutations. Approvals are durable: the request is persisted in
 * [AgentApprovalRequestEntity] and the owning ai_task_queue row is moved to WAITING_APPROVAL before
 * awaiting the user. This means a process kill during the wait neither loses the approval nor
 * leaves an ownerless RUNNING task — after restart the pending request is re-shown from the DB and
 * the decision is applied durably.
 *
 * While the process is alive the decision completes a live [CompletableDeferred] (the original fast
 * path); when the app was restarted mid-wait there is no waiter, so the durable resolution applies
 * directly to the task row (reject -> FAILED, approve -> re-queue to resume the run).
 */
// Bound in AppModule via a @Provides; the nullable database (with lazy DAOs) lets tests that only
// exercise the FULL_AUTO path (which never touches the durable store) construct this without Room.
class AgentPermissionManager(
    database: AppDatabase? = null,
) : AgentApprovalGateway {
    private data class WaitingApproval(
        val request: AgentApprovalRequest,
        val decision: CompletableDeferred<AgentApprovalDecision>,
    )

    // Resolved lazily so tests that only exercise the FULL_AUTO path (which never touches the
    // durable store) can construct this manager without a Room database.
    private val approvalDao: AgentApprovalDao by lazy { requireNotNull(database).agentApprovalDao() }
    private val taskDao: AiTaskDao by lazy { requireNotNull(database).aiTaskDao() }
    private val lock = Any()
    // Serializes the durable RUNNING -> WAITING_APPROVAL transition against a concurrent cancel's
    // RUNNING/WAITING -> CANCEL_REQUESTED transition, so a cancel can never be overwritten back to
    // WAITING_APPROVAL (which would strand the task awaiting a decision the cancel already rejected).
    private val stateMutex = kotlinx.coroutines.sync.Mutex()
    private var waiting: WaitingApproval? = null
    private val mutablePending = MutableStateFlow<AgentApprovalRequest?>(null)
    val pending: StateFlow<AgentApprovalRequest?> = mutablePending.asStateFlow()
    private val taskIdByRunId = ConcurrentHashMap<String, Long>()
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resolutionScheduler: (() -> Unit)? = null

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
    }

    /** Provided by the AI task queue so a durable approve-after-restart can wake the worker. */
    fun attachResolutionScheduler(scheduler: () -> Unit) {
        resolutionScheduler = scheduler
    }

    fun setActiveTask(runId: String, taskId: Long) {
        taskIdByRunId[runId] = taskId
    }

    fun clearActiveTask(runId: String) {
        taskIdByRunId.remove(runId)
    }

    /** Re-surfaces the oldest persisted pending approval after a process restart, ignoring requests
     * that belong to tasks which are no longer awaiting approval (e.g. a run that died FAILED or was
     * cancelled while a PENDING row was left dangling). Dead rows are marked REJECTED so they never
     * accumulate or re-surface. */
    suspend fun refreshPendingFromDb() {
        val pendings = approvalDao.getPending(STATUS_PENDING)
        var valid: AgentApprovalRequestEntity? = null
        pendings.forEach { entity ->
            val taskId = entity.taskId
            val stillWaiting = taskId?.let { taskDao.getById(it)?.state }
                ?: AiTaskStateEntity.WAITING_APPROVAL
            if (stillWaiting == AiTaskStateEntity.WAITING_APPROVAL) {
                if (valid == null) valid = entity
            } else {
                approvalDao.markDecided(entity.requestId, STATUS_REJECTED, System.currentTimeMillis())
            }
        }
        synchronized(lock) {
            if (waiting == null) {
                mutablePending.value = valid?.toAgentApprovalRequest()
            }
        }
    }

    override suspend fun authorize(
        mode: AgentPermissionMode,
        request: AgentApprovalRequest,
    ): AgentApprovalDecision {
        if (mode == AgentPermissionMode.FULL_AUTO) return AgentApprovalDecision.APPROVE
        val normalized = request.copy(
            requestId = request.requestId.ifBlank { UUID.randomUUID().toString() },
        )
        val taskId = taskIdByRunId[request.runId]
        // Persist the durable request first: a kill while awaiting must not lose the approval.
        if (approvalDao.getByRequestId(normalized.requestId)?.status != STATUS_PENDING) {
            approvalDao.insert(
                AgentApprovalRequestEntity(
                    requestId = normalized.requestId,
                    runId = normalized.runId,
                    taskId = taskId,
                    toolCallId = normalized.toolCallId,
                    toolName = normalized.toolName,
                    target = normalized.target,
                    summary = normalized.summary,
                    argumentsSummary = normalized.argumentsSummary,
                    beforeContent = normalized.before,
                    afterContent = normalized.after,
                    executionToken = "",
                    status = STATUS_PENDING,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        // Reflect the durable wait so the task is never "ownerless RUNNING". The RUNNING ->
        // WAITING_APPROVAL transition runs under the same Mutex a concurrent cancelTask uses, so
        // the cancel can never overwrite this back to WAITING_APPROVAL and strand the task awaiting
        // a decision the cancel already intended to reject.
        val canceled = stateMutex.withLock {
            val current = taskId?.let { taskDao.getById(it) }
            when (current?.state) {
                AiTaskStateEntity.RUNNING -> {
                    taskDao.markWaitingApproval(taskId!!, AiTaskStateEntity.WAITING_APPROVAL)
                    false
                }
                AiTaskStateEntity.CANCEL_REQUESTED,
                AiTaskStateEntity.CANCELED,
                -> true
                else -> true
            }
        }
        if (canceled) {
            // The user cancelled this task between enqueue and approval (or the task is already
            // terminal). Mark the pending row rejected and unwind the executor so it does not
            // continue a cancelled run.
            try {
                withStateLock { discardPendingNow(taskId) }
            } catch (_: Exception) {
                // Cleanup is best-effort; the APPROVAL_CANCELED unwind below is what matters.
            }
            throw AgentToolException(
                "APPROVAL_CANCELED",
                "The Agent run was stopped before it could ask for approval.",
            )
        }
        val deferred = CompletableDeferred<AgentApprovalDecision>()
        synchronized(lock) {
            if (waiting != null) {
                throw AgentToolException(
                    "APPROVAL_ALREADY_PENDING",
                    "Another Agent mutation is already waiting for approval.",
                )
            }
            waiting = WaitingApproval(normalized, deferred)
            mutablePending.value = normalized
        }
        return try {
            deferred.await()
        } finally {
            synchronized(lock) {
                if (waiting?.decision === deferred) {
                    waiting = null
                    mutablePending.value = null
                }
            }
            // A live approve/reject must return the task to RUNNING (from WAITING_APPROVAL) so the
            // agent run can continue and the NEXT mutation tool can authorize again. Without this,
            // a run needing more than one approval would find the row still in WAITING_APPROVAL and
            // throw APPROVAL_CANCELED on the second approval, killing the whole run. Restoring under
            // stateMutex keeps it mutually exclusive with a concurrent cancel.
            if (taskId != null) {
                try {
                    stateMutex.withLock { resumeAfterApprovalLocked(taskId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The resume is best-effort; the run has already obtained its decision.
                }
            }
        }
    }

    /** Caller holds [stateMutex]. Restores a waiting task to RUNNING unless it was cancelled/terminal. */
    private suspend fun resumeAfterApprovalLocked(taskId: Long) {
        val current = taskDao.getById(taskId)?.state ?: return
        if (current == AiTaskStateEntity.WAITING_APPROVAL) {
            taskDao.markRunning(
                id = taskId,
                running = AiTaskStateEntity.RUNNING,
                waitingApproval = AiTaskStateEntity.WAITING_APPROVAL,
            )
        }
    }

    fun approve(requestId: String) = decide(requestId, AgentApprovalDecision.APPROVE)

    fun reject(requestId: String) = decide(requestId, AgentApprovalDecision.REJECT)

    /** Rejects the pending approval (if any) for a task that was cancelled by the user. */
    fun rejectPendingForTask(taskId: Long) {
        val target = synchronized(lock) {
            waiting?.takeIf { request ->
                taskIdByRunId[request.request.runId] == taskId
            }?.request
        }
        if (target != null) decide(target.requestId, AgentApprovalDecision.REJECT)
    }

    /**
     * Runs a cancel-side state flip under the same Mutex [authorize] uses to transition RUNNING ->
     * WAITING_APPROVAL. This makes the cancel's durable state flip mutually exclusive with the
     * approval wait marker, closing the race where a cancel could be overwritten back to
     * WAITING_APPROVAL (and the task stranded awaiting a decision the cancel already rejected).
     */
    suspend fun <T> withStateLock(block: suspend () -> T): T = stateMutex.withLock { block() }

    /**
     * Cleans up any leftover PENDING approval for a task that reached a terminal state (FAILED /
     * CANCELED) without a decision. Leaves the durable row marked REJECTED so it is never surfaced
     * again, and completes a live waiter if one is blocked on this task.
     */
    suspend fun discardPendingForTask(taskId: Long) {
        // Live-waiter completion must not run under the state Mutex (decide() launches on its own
        // scope and touches the same resolve path); only the durable-row cleanup is serialized.
        val live = synchronized(lock) {
            waiting?.takeIf { request -> taskIdByRunId[request.request.runId] == taskId }
        }
        if (live != null) {
            decide(live.request.requestId, AgentApprovalDecision.REJECT)
            return
        }
        withStateLock { discardPendingNow(taskId) }
    }

    /** Cleans up a task's pending approval row; caller holds [stateMutex]. */
    private suspend fun discardPendingNow(taskId: Long?) {
        if (taskId == null) return
        val pending = approvalDao.getPendingByTaskId(taskId, STATUS_PENDING) ?: return
        approvalDao.markDecided(
            pending.requestId,
            STATUS_REJECTED,
            System.currentTimeMillis(),
        )
    }

    private fun decide(requestId: String, decision: AgentApprovalDecision) {
        val deferred = synchronized(lock) {
            waiting?.takeIf { it.request.requestId == requestId }?.decision
        }
        val hadLiveWaiter = deferred != null
        deferred?.complete(decision)
        resolutionScope.launch {
            persistAndResolve(requestId, decision, hadLiveWaiter)
        }
    }

    private suspend fun persistAndResolve(
        requestId: String,
        decision: AgentApprovalDecision,
        hadLiveWaiter: Boolean,
    ) {
        val request = approvalDao.getByRequestId(requestId) ?: return
        val now = System.currentTimeMillis()
        val status = if (decision == AgentApprovalDecision.APPROVE) STATUS_APPROVED else STATUS_REJECTED
        approvalDao.markDecided(requestId, status, now)
        if (hadLiveWaiter) return

        // No live waiter: the process was restarted while waiting. Apply the decision durably.
        val taskId = request.taskId
        if (taskId != null) {
            val task = taskDao.getById(taskId)
            if (task != null && task.state == AiTaskStateEntity.WAITING_APPROVAL) {
                if (decision == AgentApprovalDecision.REJECT) {
                    // The mutation never executed; failing with USER_REJECTED is data-safe.
                    taskDao.markFailed(
                        id = taskId,
                        failed = AiTaskStateEntity.FAILED,
                        errorSummary = "用户拒绝了这项修改。",
                        errorFailure = "USER_REJECTED",
                        attemptCount = task.attemptCount,
                        completedAt = now,
                    )
                } else {
                    // Resume: re-queue so the worker re-runs the agent from its durable
                    // conversation. Prior completed side effects may re-run (at-least-once).
                    taskDao.requeueWaitingApproval(
                        id = taskId,
                        queued = AiTaskStateEntity.QUEUED,
                        waitingApproval = AiTaskStateEntity.WAITING_APPROVAL,
                    )
                    resolutionScheduler?.invoke()
                }
            }
        }
        if (mutablePending.value?.requestId == requestId) {
            mutablePending.value = null
        }
    }
}

private fun AgentApprovalRequestEntity.toAgentApprovalRequest(): AgentApprovalRequest =
    AgentApprovalRequest(
        requestId = requestId,
        runId = runId,
        toolCallId = toolCallId,
        toolName = toolName,
        target = target,
        summary = summary,
        before = beforeContent,
        after = afterContent,
        argumentsSummary = argumentsSummary,
    )
