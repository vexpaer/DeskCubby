package com.deskcubby.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.repository.AiChatRepository
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.app.data.repository.AiChatException
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiChatDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: AiChatDao
    private lateinit var context: Context

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.aiChatDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun messagesPersistInOrderAndDeletingConversationCascades() = runBlocking {
        val conversationId = dao.insertConversation(
            AiConversationEntity(
                title = "第一条消息",
                modelConfigId = "text",
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        dao.insertMessageAndTouch(
            AiMessageEntity(
                conversationId = conversationId,
                role = "user",
                content = "问题",
                reasoning = "",
                imageUri = "content://example/image",
                imageMimeType = "image/png",
                imagePermissionOwned = true,
                createdAt = 20,
            ),
        )
        dao.insertMessageAndTouch(
            AiMessageEntity(
                conversationId = conversationId,
                role = "assistant",
                content = "回答",
                reasoning = "服务端思考内容",
                imageUri = null,
                imageMimeType = null,
                imagePermissionOwned = false,
                createdAt = 30,
            ),
        )

        assertEquals(listOf("问题", "回答"), dao.observeMessages(conversationId).first().map { it.content })
        assertEquals(30L, dao.observeConversations().first().single().updatedAt)

        dao.deleteConversation(conversationId)

        assertTrue(dao.getMessages(conversationId).isEmpty())
    }

    @Test
    fun renameAndModelSelectionSurviveReload() = runBlocking {
        val conversationId = dao.insertConversation(
            AiConversationEntity(
                title = "旧标题",
                modelConfigId = "old-model",
                createdAt = 10,
                updatedAt = 10,
            ),
        )

        assertEquals(1, dao.renameConversation(conversationId, "新标题", 20))
        assertEquals(1, dao.setModelConfig(conversationId, "new-model", 30))

        val saved = dao.getConversation(conversationId)
        assertEquals("新标题", saved?.title)
        assertEquals("new-model", saved?.modelConfigId)
        assertEquals(30L, saved?.updatedAt)
    }

    @Test
    fun systemRoleReloadsAsFrozenContextWithoutSchemaChange() = runBlocking {
        val conversationId = dao.insertConversation(
            AiConversationEntity(
                title = "上下文会话",
                modelConfigId = "text",
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        dao.insertMessageAndTouch(
            AiMessageEntity(
                conversationId = conversationId,
                role = "system",
                content = """{"schema":"deskcubby.ai-context","version":1,"items":[]}""",
                reasoning = "",
                imageUri = null,
                imageMimeType = null,
                imagePermissionOwned = false,
                createdAt = 20,
            ),
        )

        val messages = AiChatRepository(context, dao).getMessages(conversationId)

        assertEquals(1, messages.size)
        assertEquals(AiChatRole.CONTEXT, messages.single().role)
    }

    @Test
    fun frozenContextAndUserMessagePersistAsOneOrderedTurn() = runBlocking {
        val conversationId = dao.insertConversation(
            AiConversationEntity(
                title = "原子上下文",
                modelConfigId = "text",
                createdAt = 10,
                updatedAt = 10,
            ),
        )

        dao.insertUserTurnAndTouch(
            listOf(
                testMessage(conversationId, role = "system", content = "frozen", createdAt = 20),
                testMessage(conversationId, role = "user", content = "analyze", createdAt = 20),
            ),
        )

        assertEquals(
            listOf("system", "user"),
            dao.getMessages(conversationId).map(AiMessageEntity::role),
        )
        assertEquals(20L, dao.getConversation(conversationId)?.updatedAt)
    }

    @Test
    fun failedUserTurnRollsBackFrozenContextAndConversationTouch() = runBlocking {
        val conversationId = dao.insertConversation(
            AiConversationEntity(
                title = "回滚上下文",
                modelConfigId = "text",
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        val duplicateId = 77L

        val error = runCatching {
            dao.insertUserTurnAndTouch(
                listOf(
                    testMessage(
                        conversationId,
                        role = "system",
                        content = "must roll back",
                        createdAt = 20,
                        id = duplicateId,
                    ),
                    testMessage(
                        conversationId,
                        role = "user",
                        content = "constraint failure",
                        createdAt = 20,
                        id = duplicateId,
                    ),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error != null)
        assertTrue(dao.getMessages(conversationId).isEmpty())
        assertEquals(10L, dao.getConversation(conversationId)?.updatedAt)
    }

    @Test
    fun successfulHttpBodyWithErrorObjectDoesNotExposeApiKey() {
        val apiKey = "PRIVATE-API-KEY-123"
        val repository = AiChatRepository(context, dao)

        val error = assertThrows(AiChatException::class.java) {
            repository.parseAssistantContent(
                """{"error":{"message":"Authorization: Bearer $apiKey"}}""",
                apiKey,
            )
        }

        assertFalse(error.message.orEmpty().contains(apiKey))
        assertFalse(error.message.orEmpty().contains("Bearer PRIVATE"))
    }

    private fun testMessage(
        conversationId: Long,
        role: String,
        content: String,
        createdAt: Long,
        id: Long = 0,
    ) = AiMessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        reasoning = "",
        imageUri = null,
        imageMimeType = null,
        imagePermissionOwned = false,
        createdAt = createdAt,
    )
}
