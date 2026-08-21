package com.deskcubby.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted categories of background AI work driven by [com.deskcubby.app.data.taskqueue.AiTaskQueue]. */
enum class AiTaskTypeEntity {
    CALORIE_DAY,
    CALORIE_SINGLE,
    AGENT_RUN,
}

/** Persistent lifecycle state of one queued AI task. */
enum class AiTaskStateEntity {
    QUEUED,
    RUNNING,
    /** The task is blocked on a persisted user approval; see [AgentApprovalRequestEntity]. */
    WAITING_APPROVAL,
    /** A live RUNNING/WAITING_APPROVAL task the user asked to stop; the worker coalesces it to CANCELED. */
    CANCEL_REQUESTED,
    SUCCEEDED,
    FAILED,
    CANCELED,
    ;
}

@Entity(
    tableName = "ai_task_queue",
    indices = [Index("state"), Index("createdAt")],
)
data class AiTaskQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: AiTaskTypeEntity,
    val state: AiTaskStateEntity,
    /** Durable input snapshot encoded by the task payload codec. Never URI permission-dependent. */
    val payloadJson: String,
    /** Live progress snapshot written by the worker while RUNNING (stages, photo counts, trace). */
    val progressJson: String = "",
    /** Final AI result, or diagnostic detail for the terminal state. */
    val resultJson: String = "",
    val errorSummary: String = "",
    /** `AiChatFailure` enum name, or other stable failure code for non-network failures. */
    val errorFailure: String = "",
    val attemptCount: Int = 0,
    val createdAt: Long,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    /**
     * Token of the live drain session that claimed this task. A RUNNING row whose owner is no longer
     * registered (process death / worker replacement) is re-queued on the next drain. Null for rows
     * written by releases before this field existed (treated as stale on first recovery).
     */
    val leaseOwner: String? = null,
    /** Last time the worker confirmed it owns the task. Used by recovery/lease accounting. */
    val leaseStartedAt: Long? = null,
)

/** A persisted, user-readable Agent approval that survives process death and is re-shown after restart. */
@Entity(
    tableName = "agent_approval_requests",
    indices = [
        Index("runId"),
        Index("status"),
        Index(value = ["requestId"], unique = true),
    ],
)
data class AgentApprovalRequestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val requestId: String,
    val runId: String,
    val conversationId: Long? = null,
    /** The ai_task_queue row this approval belongs to (null for legacy/historical rows). */
    val taskId: Long? = null,
    val toolCallId: String,
    val toolName: String,
    val target: String,
    val summary: String,
    val argumentsSummary: String = "",
    val beforeContent: String,
    val afterContent: String,
    /** Opaque token the tool needs to execute the approved mutation. */
    val executionToken: String,
    val status: String,
    val createdAt: Long,
    val decidedAt: Long? = null,
)