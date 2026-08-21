package com.deskcubby.app.agent

import com.deskcubby.app.data.local.AiChatDao
import com.deskcubby.app.data.local.AiMessageEntity
import com.deskcubby.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class AgentRecoveryStoreTest {
    @Test
    fun finalAssistantMessageIsInsertedAtMostOnceForSameRunAfterRecovery() {
        runBlocking {
            val database = mock(AppDatabase::class.java)
            val dao = mock(AiChatDao::class.java)
            Mockito.`when`(database.aiChatDao()).thenReturn(dao)

            val syncId = "agent-final:run-crash-after-message"
            val persisted = AiMessageEntity(
                id = 1L,
                conversationId = 7L,
                role = "assistant",
                content = "done",
                reasoning = "",
                imageUri = null,
                imageMimeType = null,
                imagePermissionOwned = false,
                createdAt = 123L,
                syncId = syncId,
            )
            // First runtime sees no final message. The reconstructed runtime sees the message that the
            // first runtime inserted immediately before it crashed. Exact arguments avoid Mockito's
            // nullable any() matcher crossing a Kotlin non-null boundary.
            Mockito.`when`(dao.getMessageBySyncId(syncId)).thenReturn(null, persisted)

            val recoveryStore = AgentRecoveryStore(database)
            val first = recoveryStore.insertFinalAssistantMessageIfAbsent(
                conversationId = 7L,
                runId = "run-crash-after-message",
                content = "done",
            )
            val second = recoveryStore.insertFinalAssistantMessageIfAbsent(
                conversationId = 7L,
                runId = "run-crash-after-message",
                content = "done",
            )

            assertTrue(first)
            assertFalse(second)
            assertEquals(syncId, persisted.syncId)
            assertEquals("done", persisted.content)
            Mockito.verify(dao, Mockito.times(2)).getMessageBySyncId(syncId)
        }
    }
}
