package com.deskcubby.app.agent

import com.deskcubby.app.data.local.AgentApprovalDao
import com.deskcubby.app.data.local.AgentApprovalRequestEntity
import com.deskcubby.app.data.local.AiTaskDao
import com.deskcubby.app.data.local.AiTaskQueueEntity
import com.deskcubby.app.data.local.AiTaskStateEntity
import com.deskcubby.app.data.local.AiTaskTypeEntity
import com.deskcubby.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class AgentPermissionManagerRecoveryTest {
    @Test
    fun waitingApprovalIsResurfacedFromDatabaseAfterProcessRestart() = runBlocking {
        val database = mock(AppDatabase::class.java)
        val approvalDao = mock(AgentApprovalDao::class.java)
        val taskDao = mock(AiTaskDao::class.java)
        Mockito.`when`(database.agentApprovalDao()).thenReturn(approvalDao)
        Mockito.`when`(database.aiTaskDao()).thenReturn(taskDao)

        val pendingEntity = AgentApprovalRequestEntity(
            id = 5L,
            requestId = "agent-approval:run-approval:2",
            runId = "run-approval",
            taskId = 42L,
            toolCallId = "call-2",
            toolName = "edit_content",
            target = "diary/2026-08-20",
            summary = "Update diary",
            argumentsSummary = "content=after",
            beforeContent = "before",
            afterContent = "after",
            executionToken = "agent-approval:run-approval:2",
            status = AgentPermissionManager.STATUS_PENDING,
            createdAt = 123L,
        )
        val waitingTask = AiTaskQueueEntity(
            id = 42L,
            type = AiTaskTypeEntity.AGENT_RUN,
            state = AiTaskStateEntity.WAITING_APPROVAL,
            payloadJson = "{}",
            createdAt = 100L,
        )
        Mockito.`when`(approvalDao.getPending(AgentPermissionManager.STATUS_PENDING))
            .thenReturn(listOf(pendingEntity))
        Mockito.`when`(taskDao.getById(42L)).thenReturn(waitingTask)

        val manager = AgentPermissionManager(database)
        manager.refreshPendingFromDb()

        val pending = manager.pending.value
        assertNotNull(pending)
        assertEquals("agent-approval:run-approval:2", pending?.requestId)
        assertEquals("run-approval", pending?.runId)
        assertEquals("edit_content", pending?.toolName)
        assertEquals("diary/2026-08-20", pending?.target)
    }
}