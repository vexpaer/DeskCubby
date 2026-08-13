package com.deskcubby.app.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.AgentRunEntity
import com.deskcubby.app.data.local.AiAttachmentEntity
import com.deskcubby.app.data.local.AiConversationEntity
import com.deskcubby.app.data.local.AiMessageEntity
import com.deskcubby.app.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentChatSyncRepositoryTest {
    @Test
    fun legacyChatAgentUsageAndFrozenDocumentMergeWithoutDeviceUris() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val destination = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sourceChat = source.aiChatDao()
            val conversationId = sourceChat.insertConversation(
                AiConversationEntity(
                    title = "Legacy conversation",
                    modelConfigId = "model",
                    createdAt = 10,
                    updatedAt = 20,
                ),
            )
            val messageId = sourceChat.insertMessage(
                AiMessageEntity(
                    conversationId = conversationId,
                    role = "system",
                    content = "{\"schema\":\"deskcubby.ai-context\",\"version\":1,\"items\":[]}",
                    reasoning = "",
                    imageUri = "content://device/private-image",
                    imageMimeType = "image/png",
                    imagePermissionOwned = true,
                    createdAt = 20,
                ),
            )
            sourceChat.insertAttachments(
                listOf(
                    AiAttachmentEntity(
                        messageId = messageId,
                        uri = "content://device/private-document",
                        mimeType = "text/plain",
                        displayName = "document.txt",
                        sizeBytes = 8,
                        kind = "DOCUMENT",
                        extractedText = "untrusted frozen text",
                        permissionOwned = true,
                    ),
                ),
            )
            source.agentDao().insertRun(
                AgentRunEntity(
                    runId = "run-1",
                    conversationId = conversationId,
                    conversationTitle = "Legacy conversation",
                    userRequestSummary = "summarize",
                    modelConfigId = "model",
                    permissionMode = "REQUIRE_APPROVAL",
                    enabledSourcesJson = "[\"diary\"]",
                    status = "SUCCEEDED",
                    modelCallCount = 2,
                    usageReportedCallCount = 2,
                    inputTokens = 100,
                    outputTokens = 20,
                    totalTokens = 120,
                    cachedInputTokens = 25,
                    cacheRateInputTokens = 100,
                    reasoningTokens = 4,
                    startedAt = 20,
                    completedAt = 30,
                ),
            )

            val snapshot = AgentChatSyncRepository(source, context).snapshot(10L * 1024 * 1024)
            val raw = snapshot.bytes.decodeToString()
            assertFalse(raw.contains("content://"))
            assertTrue(raw.contains("untrusted frozen text"))

            AgentChatSyncRepository(destination, context).mergeIncoming(
                snapshot.bytes,
                sha256(snapshot.bytes),
                10L * 1024 * 1024,
            )

            val remoteConversation = destination.aiChatDao().getAllConversationsForSync().single()
            assertNotNull(remoteConversation.syncId)
            val remoteMessage = destination.aiChatDao().getMessages(remoteConversation.id).single()
            assertEquals("system", remoteMessage.role)
            assertNull(remoteMessage.imageUri)
            assertEquals("image/png", remoteMessage.imageMimeType)
            val remoteAttachment = destination.aiChatDao().getAllAttachmentsForSync().single()
            assertEquals("", remoteAttachment.uri)
            assertEquals("untrusted frozen text", remoteAttachment.extractedText)
            assertFalse(remoteAttachment.permissionOwned)
            val remoteRun = destination.agentDao().getRun("run-1")
            assertEquals(120L, remoteRun?.totalTokens)
            assertEquals(25L, remoteRun?.cachedInputTokens)
            assertEquals(100L, remoteRun?.cacheRateInputTokens)
        } finally {
            source.close()
            destination.close()
        }
    }

    @Test
    fun newerRemoteTombstoneClearsDeviceLocalAttachmentUris() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val destination = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val sourceConversationId = source.aiChatDao().insertConversation(
                AiConversationEntity(
                    title = "Deleted remotely",
                    modelConfigId = "model",
                    createdAt = 10,
                    updatedAt = 30,
                    syncId = "conversation-1",
                    deletedAt = 30,
                ),
            )
            source.aiChatDao().insertMessage(
                AiMessageEntity(
                    conversationId = sourceConversationId,
                    role = "user",
                    content = "message",
                    reasoning = "",
                    imageUri = null,
                    imageMimeType = "image/png",
                    imagePermissionOwned = false,
                    createdAt = 11,
                    syncId = "message-1",
                ),
            )

            val destinationConversationId = destination.aiChatDao().insertConversation(
                AiConversationEntity(
                    title = "Still local",
                    modelConfigId = "model",
                    createdAt = 10,
                    updatedAt = 20,
                    syncId = "conversation-1",
                ),
            )
            val destinationMessageId = destination.aiChatDao().insertMessage(
                AiMessageEntity(
                    conversationId = destinationConversationId,
                    role = "user",
                    content = "message",
                    reasoning = "",
                    imageUri = "content://device/local-image",
                    imageMimeType = "image/png",
                    imagePermissionOwned = true,
                    createdAt = 11,
                    syncId = "message-1",
                ),
            )
            destination.aiChatDao().insertAttachments(
                listOf(
                    AiAttachmentEntity(
                        messageId = destinationMessageId,
                        uri = "content://device/local-document",
                        mimeType = "text/plain",
                        displayName = "local.txt",
                        sizeBytes = 10,
                        kind = "DOCUMENT",
                        extractedText = "frozen",
                        permissionOwned = true,
                        syncId = "attachment-1",
                    ),
                ),
            )

            val snapshot = AgentChatSyncRepository(source, context).snapshot(10L * 1024 * 1024)
            AgentChatSyncRepository(destination, context).mergeIncoming(
                snapshot.bytes,
                sha256(snapshot.bytes),
                10L * 1024 * 1024,
            )

            val conversation = destination.aiChatDao().getConversationBySyncId("conversation-1")
            assertEquals(30L, conversation?.deletedAt)
            val message = destination.aiChatDao().getMessageBySyncId("message-1")
            assertNull(message?.imageUri)
            assertFalse(message?.imagePermissionOwned ?: true)
            val attachment = destination.aiChatDao().getAttachmentBySyncId("attachment-1")
            assertEquals("", attachment?.uri)
            assertFalse(attachment?.permissionOwned ?: true)
        } finally {
            source.close()
            destination.close()
        }
    }
}
