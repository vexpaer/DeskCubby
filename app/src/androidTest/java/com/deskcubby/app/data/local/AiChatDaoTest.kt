package com.deskcubby.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiChatDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: AiChatDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
}
