package com.deskcubby.app.agent

import com.deskcubby.app.data.local.AiMessageEntity
import com.deskcubby.app.data.local.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recovery view over the existing Room tables. It deliberately reuses agent_runs,
 * agent_tool_events and agent_mutations instead of introducing a second execution ledger.
 */
@Singleton
class AgentRecoveryStore @Inject constructor(
    private val database: AppDatabase,
) {
    private val aiChatDao = database.aiChatDao()

    data class ToolCheckpoint(
        val id: Long,
        val toolCallId: String,
        val toolName: String,
        val classification: String,
        val status: String,
        val target: String,
        val summary: String,
        val resultContent: String,
        val errorCode: String?,
    )

    data class MutationCheckpoint(
        val id: Long,
        val status: String,
        val target: String,
        val beforeContent: String,
        val afterContent: String,
    )

    suspend fun resumeRunIfExists(runId: String): Boolean = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        val exists = db.query(
            "SELECT 1 FROM agent_runs WHERE runId = ? LIMIT 1",
            arrayOf(runId),
        ).use { it.moveToFirst() }
        if (exists) {
            db.execSQL(
                "UPDATE agent_runs SET status = 'RUNNING', completedAt = NULL WHERE runId = ?",
                arrayOf(runId),
            )
        }
        exists
    }

    suspend fun toolCheckpoint(runId: String, sequence: Int): ToolCheckpoint? =
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.query(
                """
                SELECT id, toolCallId, toolName, classification, status, target,
                       summary, resultSummary, errorCode
                FROM agent_tool_events
                WHERE runId = ? AND sequence = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(runId, sequence),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                ToolCheckpoint(
                    id = cursor.getLong(0),
                    toolCallId = cursor.getString(1),
                    toolName = cursor.getString(2),
                    classification = cursor.getString(3),
                    status = cursor.getString(4),
                    target = cursor.getString(5),
                    summary = cursor.getString(6),
                    resultContent = cursor.getString(7),
                    errorCode = cursor.takeUnless { it.isNull(8) }?.getString(8),
                )
            }
        }

    suspend fun mutationCheckpoint(toolEventId: Long): MutationCheckpoint? =
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.query(
                """
                SELECT id, status, target, beforeContent, afterContent
                FROM agent_mutations
                WHERE toolEventId = ?
                LIMIT 1
                """.trimIndent(),
                arrayOf(toolEventId),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@withContext null
                MutationCheckpoint(
                    id = cursor.getLong(0),
                    status = cursor.getString(1),
                    target = cursor.getString(2),
                    beforeContent = cursor.getString(3),
                    afterContent = cursor.getString(4),
                )
            }
        }

    suspend fun markRecoveredMutationCommitted(
        mutationId: Long,
        eventId: Long,
        target: String,
        summary: String,
        resultContent: String,
    ) = withContext(Dispatchers.IO) {
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE agent_mutations SET status = 'APPLIED' WHERE id = ? AND status = 'PENDING'",
                arrayOf(mutationId),
            )
            db.execSQL(
                """
                UPDATE agent_tool_events
                SET status = 'SUCCEEDED', target = ?, summary = ?, resultSummary = ?,
                    errorCode = NULL, completedAt = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf(target, summary, resultContent, System.currentTimeMillis(), eventId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun markRecoveredSuccess(
        eventId: Long,
        target: String,
        summary: String,
        resultContent: String,
    ) = withContext(Dispatchers.IO) {
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE agent_tool_events
            SET status = 'SUCCEEDED', target = ?, summary = ?, resultSummary = ?,
                errorCode = NULL, completedAt = ?
            WHERE id = ?
            """.trimIndent(),
            arrayOf(target, summary, resultContent, System.currentTimeMillis(), eventId),
        )
    }

    suspend fun markRecoveryFailure(eventId: Long, code: String, message: String) =
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                """
                UPDATE agent_tool_events
                SET status = 'FAILED', resultSummary = ?, errorCode = ?, completedAt = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf(message, code, System.currentTimeMillis(), eventId),
            )
        }

    suspend fun insertFinalAssistantMessageIfAbsent(
        conversationId: Long,
        runId: String,
        content: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val syncId = finalMessageSyncId(runId)
        if (aiChatDao.getMessageBySyncId(syncId) != null) return@withContext false
        val now = System.currentTimeMillis()
        try {
            aiChatDao.insertMessageAndTouch(
                AiMessageEntity(
                    conversationId = conversationId,
                    role = "assistant",
                    content = content,
                    reasoning = "",
                    imageUri = null,
                    imageMimeType = null,
                    imagePermissionOwned = false,
                    createdAt = now,
                    syncId = syncId,
                ),
            )
            true
        } catch (error: Exception) {
            // The unique syncId is the final guard if a concurrent recovery raced the pre-check.
            if (aiChatDao.getMessageBySyncId(syncId) != null) false else throw error
        }
    }

    suspend fun hasFinalAssistantMessage(runId: String): Boolean =
        aiChatDao.getMessageBySyncId(finalMessageSyncId(runId)) != null

    companion object {
        fun approvalRequestId(runId: String, sequence: Int): String = "agent-approval:$runId:$sequence"
        fun finalMessageSyncId(runId: String): String = "agent-final:$runId"
    }
}
