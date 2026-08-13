package com.deskcubby.app.agent

import com.deskcubby.app.data.model.AgentPermissionMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AgentPermissionManager @Inject constructor() : AgentApprovalGateway {
    private data class WaitingApproval(
        val request: AgentApprovalRequest,
        val decision: CompletableDeferred<AgentApprovalDecision>,
    )

    private val lock = Any()
    private var waiting: WaitingApproval? = null
    private val mutablePending = MutableStateFlow<AgentApprovalRequest?>(null)
    val pending: StateFlow<AgentApprovalRequest?> = mutablePending.asStateFlow()

    override suspend fun authorize(
        mode: AgentPermissionMode,
        request: AgentApprovalRequest,
    ): AgentApprovalDecision {
        if (mode == AgentPermissionMode.FULL_AUTO) return AgentApprovalDecision.APPROVE
        val normalized = request.copy(
            requestId = request.requestId.ifBlank { UUID.randomUUID().toString() },
        )
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
        }
    }

    fun approve(requestId: String) = decide(requestId, AgentApprovalDecision.APPROVE)

    fun reject(requestId: String) = decide(requestId, AgentApprovalDecision.REJECT)

    private fun decide(requestId: String, decision: AgentApprovalDecision) {
        val deferred = synchronized(lock) {
            waiting?.takeIf { it.request.requestId == requestId }?.decision
        }
        deferred?.complete(decision)
    }
}
