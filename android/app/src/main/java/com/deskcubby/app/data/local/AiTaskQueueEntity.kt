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
    WAITING_APPROVAL,
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
)