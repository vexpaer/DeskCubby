package com.deskcubby.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentChatSyncCodecTest {
    @Test
    fun roundTripIncludesChatsDocumentsAndProviderReportedUsageWithoutUrisOrSecrets() {
        val payload = AgentChatSyncPayload(
            conversations = listOf(SyncConversation("conversation-1", "Title", "model", 1, 2, null)),
            messages = listOf(
                SyncMessage("message-1", "conversation-1", "user", "hello", "", "image/png", 2),
            ),
            attachments = listOf(
                SyncAttachment(
                    "attachment-1",
                    "message-1",
                    "text/plain",
                    "notes.txt",
                    5,
                    "DOCUMENT",
                    "frozen untrusted text",
                ),
            ),
            runs = listOf(
                SyncRun(
                    "run-1",
                    "conversation-1",
                    "Title",
                    "summarize",
                    "model",
                    "REQUIRE_APPROVAL",
                    "[\"diary\"]",
                    "SUCCEEDED",
                    2,
                    2,
                    100,
                    20,
                    120,
                    40,
                    100,
                    5,
                    2,
                    3,
                ),
            ),
        )

        val encoded = AgentChatSyncCodec.encode(payload)
        val raw = encoded.decodeToString()

        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("apiKey", true))
        assertFalse(raw.contains("undoPayload"))
        val decoded = AgentChatSyncCodec.decode(encoded)
        assertEquals(payload, decoded)
        assertEquals(40L, decoded.runs.single().cachedInputTokens)
        assertEquals(100L, decoded.runs.single().cacheRateInputTokens)
    }

    @Test
    fun rejectsDuplicateIdsDanglingRelationsAndImpossibleCacheUsage() {
        val base = JSONObject(
            AgentChatSyncCodec.encode(
                AgentChatSyncPayload(
                    listOf(SyncConversation("conversation-1", "Title", "", 1, 1, null)),
                    listOf(SyncMessage("message-1", "conversation-1", "user", "", "", null, 1)),
                    emptyList(),
                    emptyList(),
                ),
            ).decodeToString(),
        )
        val duplicate = JSONObject(base.toString()).apply {
            getJSONArray("conversations").put(getJSONArray("conversations").getJSONObject(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentChatSyncCodec.decode(duplicate.toString().encodeToByteArray())
        }

        val dangling = JSONObject(base.toString()).apply {
            getJSONArray("messages").getJSONObject(0).put("conversationSyncId", "missing")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentChatSyncCodec.decode(dangling.toString().encodeToByteArray())
        }

        val invalidUsage = JSONObject(base.toString()).apply {
            getJSONArray("runs").put(
                JSONObject()
                    .put("runId", "run-1")
                    .put("conversationSyncId", "conversation-1")
                    .put("conversationTitle", "Title")
                    .put("userRequestSummary", "summary")
                    .put("modelConfigId", "")
                    .put("permissionMode", "FULL_AUTO")
                    .put("enabledSourcesJson", "[]")
                    .put("status", "SUCCEEDED")
                    .put("modelCallCount", 1)
                    .put("usageReportedCallCount", 1)
                    .put("inputTokens", 10)
                    .put("outputTokens", 1)
                    .put("totalTokens", 11)
                    .put("cachedInputTokens", 20)
                    .put("reasoningTokens", 0)
                    .put("startedAt", 1)
                    .put("completedAt", 2),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentChatSyncCodec.decode(invalidUsage.toString().encodeToByteArray())
        }
    }

    @Test
    fun rejectsUnsupportedOrOversizedPayload() {
        val unsupported = """{"format":"deskcubby-agent-chats","version":2,"conversations":[],"messages":[],"attachments":[],"runs":[]}"""
        assertTrue(
            assertThrows(CloudSyncConflictException::class.java) {
                AgentChatSyncCodec.decode(unsupported.encodeToByteArray())
            }.message.orEmpty().isNotBlank(),
        )
        assertThrows(CloudSyncLimitException::class.java) {
            AgentChatSyncCodec.decode(ByteArray(AgentChatSyncRepository.MAX_JSON_BYTES + 1))
        }
    }
}
