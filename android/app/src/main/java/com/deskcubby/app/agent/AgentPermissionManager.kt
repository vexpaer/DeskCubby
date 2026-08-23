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
 */
class AgentPermissionManager(
    database: AppDatabase? = null,
) : AgentApprovalGateway {
    private data class WaitingApproval(
        val request: AgentApprovalRequest,
        val decision: CompletableDeferred<AgentApprovalDecision>,
    )

    private val approvalDao: AgentApprovalDao by lazy { requireNotNull(database).agentApprovalDao() }
    private val taskDao: AiTaskDao by lazy { requireNotNull(database).aiTaskDao() }
    private val lock = Any()
    private val stateMutex = kotlinx.coroutines.sync.Mutex()
    private var waiting: WaitingApproval? = null
    private val mutablePending = MutableStateFlow<AgentApprovalRequest?>(null)
    val pending: StateFlow<AgentApprovalRequest?> = mutablePending.asStateFlow()
    private val taskIdByRunId = ConcurrentHashMap<String, Long>()
    private val resolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resolutionScheduler: ((Long) -> Unit)? = null

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
    }

    fun attachResolutionScheduler(scheduler: (Long) -> Unit) {
        resolutionScheduler = scheduler
    }

    fun setActiveTask(runId: String, taskId: Long) {
        taskIdByRunId[runId] = taskId
    }

    fun clearActiveTask(runId: String) {
        taskIdByRunId.remove(runId)
    }

    /** Re-surfaces the oldest persisted pending approval after a process restart. */
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

        // A recovered tool sequence uses a deterministic request id. Reuse the already durable
        // decision instead of replacing APPROVED/REJECTED with a fresh PENDING request.
        val existing = approvalDao.getByRequestId(normalized.requestId)
        if (existing != null) {
            if (existing.runId != normalized.runId || existing.toolName != normalized.toolName) {
                throw AgentToolException(
                    "APPROVAL_RECOVERY_CONFLICT",
                    "Recovered approval does not match the persisted tool execution.",
                )
            }
            when (existing.status) {
                STATUS_APPROVED -> return AgentApprovalDecision.APPROVE
                STATUS_REJECTED -> return AgentApprovalDecision.REJECT
                STATUS_PENDING -> Unit
                else -> throw AgentToolException(
                    "APPROVAL_RECOVERY_CONFLICT",
                    "Recovered approval has an unknown durable status.",
                )
            }
        } else {
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
                    // The execution identity itself is durable even though the concrete mutation
                    // token is stored later in agent_mutations when the side effect is claimed.
                    executionToken = normalized.requestId,
                    status = STATUS_PENDING,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        val canceled = stateMutex.withLock {
            val current = taskId?.let { taskDao.getById(it) }
            when (current?.state) {
                AiTaskStateEntity.RUNNING -> {
                    taskDao.markWaitingApproval(taskId!!, AiTaskStateEntity.WAITING_APPROVAL)
                    false
                }
                AiTaskStateEntity.WAITING_APPROVAL -> false
                AiTaskStateEntity.CANCEL_REQUESTED,
                AiTaskStateEntity.CANCELED,
                -> true
                else -> true
            }
        }
        if (canceled) {
            try {
                withStateLock { discardPendingNow(taskId) }
            } catch (_: Exception) {
                // Cleanup is best-effort; the unwind below is what matters.
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
            if (taskId != null) {
                try {
                    stateMutex.withLock { resumeAfterApprovalLocked(taskId) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The durable decision remains authoritative.
                }
            }
        }
    }

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

    fun rejectPendingForTask(taskId: Long) {
        val target = synchronized(lock) {
            waiting?.takeIf { request ->
                taskIdByRunId[request.request.runId] == taskId
            }?.request
        }
        if (target != null) decide(target.requestId, AgentApprovalDecision.REJECT)
    }

    suspend fun <T> withStateLock(block: suspend () -> T): T = stateMutex.withLock { block() }

    suspend fun discardPendingForTask(taskId: Long) {
        val live = synchronized(lock) {
            waiting?.takeIf { request -> taskIdByRunId[request.request.runId] == taskId }
        }
        if (live != null) {
            decide(live.request.requestId, AgentApprovalDecision.REJECT)
            return
        }
        withStateLock { discardPendingNow(taskId) }
    }

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

        // No live waiter means the process was restarted while waiting. Approve re-queues the same
        // runId; AgentRecoveryStore + the tool ledger prevent committed sequences from executing
        // twice when the runtime reconstructs its transcript.
        val taskId = request.taskId
        if (taskId != null) {
            val task = taskDao.getById(taskId)
            if (task != null && task.state == AiTaskStateEntity.WAITING_APPROVAL) {
                if (decision == AgentApprovalDecision.REJECT) {
                    taskDao.markFailed(
                        id = taskId,
                        failed = AiTaskStateEntity.FAILED,
                        errorSummary = "用户拒绝了这项修改。",
                        errorFailure = "USER_REJECTED",
                        attemptCount = task.attemptCount,
                        completedAt = now,
                    )
                } else {
                    taskDao.requeueWaitingApproval(
                        id = taskId,
                        queued = AiTaskStateEntity.QUEUED,
                        waitingApproval = AiTaskStateEntity.WAITING_APPROVAL,
                    )
                    resolutionScheduler?.invoke(taskId)
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
