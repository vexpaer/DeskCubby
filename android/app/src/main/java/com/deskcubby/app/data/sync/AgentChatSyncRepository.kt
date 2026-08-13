package com.deskcubby.app.data.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import com.deskcubby.app.data.local.AgentRunEntity
import com.deskcubby.app.data.local.AiAttachmentEntity
import com.deskcubby.app.data.local.AiConversationEntity
import com.deskcubby.app.data.local.AiMessageEntity
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.model.AgentPermissionMode
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class AgentChatSyncSnapshot(
    val bytes: ByteArray,
    val lastModifiedMillis: Long,
    val localId: String = "agent-chats",
) {
    override fun toString(): String =
        "AgentChatSyncSnapshot(bytes=<redacted:${bytes.size}>, lastModifiedMillis=$lastModifiedMillis)"
}

/**
 * URI-free, record-level merge for Agent conversations. Images remain device-local; document
 * attachments sync only their already-frozen extracted text. Review undo payloads and secrets are
 * deliberately excluded.
 */
@Singleton
class AgentChatSyncRepository @Inject constructor(
    private val database: AppDatabase,
    @param:ApplicationContext private val context: Context,
) {
    suspend fun snapshot(maxBytes: Long): AgentChatSyncSnapshot = withContext(Dispatchers.IO) {
        database.withTransaction {
            backfillSyncIds()
            encodeSnapshot(maxBytes)
        }
    }

    suspend fun mergeIncoming(
        bytes: ByteArray,
        expectedSha256: String,
        maxBytes: Long,
    ): AgentChatSyncSnapshot = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size.toLong() > maxBytes || bytes.size > MAX_JSON_BYTES ||
            sha256(bytes) != expectedSha256
        ) {
            throw CloudSyncConflictException("Agent 会话同步文件校验失败。")
        }
        val decoded = AgentChatSyncCodec.decode(bytes)
        val permissionsToRelease = database.withTransaction {
            backfillSyncIds()
            mergeDecoded(decoded)
        }
        releaseUnusedUriPermissions(permissionsToRelease)
        snapshot(maxBytes)
    }

    private suspend fun backfillSyncIds() {
        val chatDao = database.aiChatDao()
        chatDao.getAllConversationsForSync().filter { it.syncId == null }.forEach { item ->
            chatDao.assignConversationSyncId(item.id, UUID.randomUUID().toString())
        }
        chatDao.getAllMessagesForSync().filter { it.syncId == null }.forEach { item ->
            chatDao.assignMessageSyncId(item.id, UUID.randomUUID().toString())
        }
        chatDao.getAllAttachmentsForSync().filter { it.syncId == null }.forEach { item ->
            chatDao.assignAttachmentSyncId(item.id, UUID.randomUUID().toString())
        }
    }

    private suspend fun encodeSnapshot(maxBytes: Long): AgentChatSyncSnapshot {
        val chatDao = database.aiChatDao()
        val agentDao = database.agentDao()
        val conversations = chatDao.getAllConversationsForSync()
        val messages = chatDao.getAllMessagesForSync()
        val attachments = chatDao.getAllAttachmentsForSync()
        val conversationSyncIds = conversations.associate { it.id to requireNotNull(it.syncId) }
        val messageSyncIds = messages.associate { it.id to requireNotNull(it.syncId) }
        val payload = AgentChatSyncPayload(
            conversations = conversations.map { item ->
                SyncConversation(
                    requireNotNull(item.syncId),
                    item.title,
                    item.modelConfigId,
                    item.createdAt,
                    item.updatedAt,
                    item.deletedAt,
                )
            },
            messages = messages.mapNotNull { item ->
                val conversationSyncId = conversationSyncIds[item.conversationId]
                    ?: return@mapNotNull null
                SyncMessage(
                    requireNotNull(item.syncId),
                    conversationSyncId,
                    item.role,
                    item.content,
                    item.reasoning,
                    imageMimeType = item.imageMimeType,
                    createdAt = item.createdAt,
                )
            },
            attachments = attachments.mapNotNull { item ->
                val messageSyncId = messageSyncIds[item.messageId] ?: return@mapNotNull null
                SyncAttachment(
                    requireNotNull(item.syncId),
                    messageSyncId,
                    item.mimeType,
                    item.displayName,
                    item.sizeBytes,
                    item.kind,
                    item.extractedText,
                )
            },
            runs = agentDao.getRunsForSync().mapNotNull { item ->
                item.completedAt ?: return@mapNotNull null
                SyncRun(
                    item.runId,
                    item.conversationId?.let(conversationSyncIds::get),
                    item.conversationTitle,
                    item.userRequestSummary,
                    item.modelConfigId,
                    item.permissionMode,
                    item.enabledSourcesJson,
                    item.status,
                    item.modelCallCount,
                    item.usageReportedCallCount,
                    item.inputTokens,
                    item.outputTokens,
                    item.totalTokens,
                    item.cachedInputTokens,
                    item.cacheRateInputTokens,
                    item.reasoningTokens,
                    item.startedAt,
                    item.completedAt,
                )
            },
        )
        val bytes = AgentChatSyncCodec.encode(payload)
        if (bytes.size.toLong() > maxBytes || bytes.size > MAX_JSON_BYTES) {
            throw CloudSyncLimitException("Agent 会话超过单文件同步上限。")
        }
        val latest = maxOf(
            conversations.maxOfOrNull(AiConversationEntity::updatedAt) ?: 0L,
            agentDao.getRunsForSync().mapNotNull(AgentRunEntity::completedAt).maxOrNull() ?: 0L,
        )
        return AgentChatSyncSnapshot(bytes, latest)
    }

    private suspend fun mergeDecoded(payload: AgentChatSyncPayload): Set<String> {
        val chatDao = database.aiChatDao()
        val agentDao = database.agentDao()
        val permissionsToRelease = mutableSetOf<String>()
        val conversationIds = chatDao.getAllConversationsForSync()
            .mapNotNull { item -> item.syncId?.let { it to item.id } }
            .toMap()
            .toMutableMap()
        payload.conversations.sortedBy(SyncConversation::createdAt).forEach { remote ->
            val local = chatDao.getConversationBySyncId(remote.syncId)
            val localId = if (local == null) {
                chatDao.insertConversation(
                    AiConversationEntity(
                        title = remote.title,
                        modelConfigId = remote.modelConfigId,
                        createdAt = remote.createdAt,
                        updatedAt = remote.updatedAt,
                        syncId = remote.syncId,
                        deletedAt = remote.deletedAt,
                    ),
                )
            } else {
                if (remote.shouldReplace(local)) {
                    if (remote.deletedAt != null) {
                        permissionsToRelease += chatDao.getOwnedImageUris(local.id)
                        permissionsToRelease += chatDao.getOwnedAttachmentUris(local.id)
                    }
                    chatDao.updateConversationFromSync(
                        local.id,
                        remote.title,
                        remote.modelConfigId,
                        remote.createdAt,
                        remote.updatedAt,
                        remote.deletedAt,
                    )
                    if (remote.deletedAt != null) {
                        chatDao.clearDeletedConversationImageUris(local.id)
                        chatDao.clearDeletedConversationAttachmentUris(local.id)
                    }
                }
                local.id
            }
            conversationIds[remote.syncId] = localId
        }

        val messageIds = chatDao.getAllMessagesForSync()
            .mapNotNull { item -> item.syncId?.let { it to item.id } }
            .toMap()
            .toMutableMap()
        payload.messages.sortedBy(SyncMessage::createdAt).forEach { remote ->
            if (chatDao.getMessageBySyncId(remote.syncId) != null) return@forEach
            val conversationId = conversationIds[remote.conversationSyncId] ?: return@forEach
            val id = chatDao.insertMessage(
                AiMessageEntity(
                    conversationId = conversationId,
                    role = remote.role,
                    content = remote.content,
                    reasoning = remote.reasoning,
                    imageUri = null,
                    imageMimeType = remote.imageMimeType,
                    imagePermissionOwned = false,
                    createdAt = remote.createdAt,
                    syncId = remote.syncId,
                ),
            )
            messageIds[remote.syncId] = id
        }
        payload.attachments.forEach { remote ->
            if (chatDao.getAttachmentBySyncId(remote.syncId) != null) return@forEach
            val messageId = messageIds[remote.messageSyncId] ?: return@forEach
            chatDao.insertAttachments(
                listOf(
                    AiAttachmentEntity(
                        messageId = messageId,
                        uri = "",
                        mimeType = remote.mimeType,
                        displayName = remote.displayName,
                        sizeBytes = remote.sizeBytes,
                        kind = remote.kind,
                        extractedText = remote.extractedText,
                        permissionOwned = false,
                        syncId = remote.syncId,
                    ),
                ),
            )
        }
        payload.runs.forEach { remote ->
            val conversationId = remote.conversationSyncId?.let(conversationIds::get)
            val run = remote.toEntity(conversationId)
            val local = agentDao.getRun(run.runId)
            if (local == null) {
                agentDao.insertRunFromSync(run)
            } else if (run.shouldReplace(local)) {
                agentDao.updateRunFromSync(
                    runId = run.runId,
                    conversationId = conversationId,
                    conversationTitle = run.conversationTitle,
                    userRequestSummary = run.userRequestSummary,
                    status = run.status,
                    modelCallCount = run.modelCallCount,
                    usageReportedCallCount = run.usageReportedCallCount,
                    inputTokens = run.inputTokens,
                    outputTokens = run.outputTokens,
                    totalTokens = run.totalTokens,
                    cachedInputTokens = run.cachedInputTokens,
                    cacheRateInputTokens = run.cacheRateInputTokens,
                    reasoningTokens = run.reasoningTokens,
                    completedAt = run.completedAt,
                )
            }
        }
        return permissionsToRelease
    }

    private suspend fun releaseUnusedUriPermissions(uris: Set<String>) {
        val chatDao = database.aiChatDao()
        uris.forEach { value ->
            if (chatDao.countImageReferences(value) == 0 &&
                chatDao.countAttachmentReferences(value) == 0
            ) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(value),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
    }

    companion object {
        const val MAX_JSON_BYTES = 64 * 1024 * 1024
    }
}

private fun SyncConversation.shouldReplace(local: AiConversationEntity): Boolean {
    if (updatedAt != local.updatedAt) return updatedAt > local.updatedAt
    if ((deletedAt != null) != (local.deletedAt != null)) return deletedAt != null
    return listOf(title, modelConfigId, createdAt.toString(), deletedAt?.toString().orEmpty())
        .joinToString("\u0001") >
        listOf(local.title, local.modelConfigId, local.createdAt.toString(), local.deletedAt?.toString().orEmpty())
            .joinToString("\u0001")
}

private fun AgentRunEntity.shouldReplace(local: AgentRunEntity): Boolean {
    val remoteCompleted = completedAt ?: return false
    val localCompleted = local.completedAt ?: return true
    if (remoteCompleted != localCompleted) return remoteCompleted > localCompleted
    return listOf(status, conversationTitle, userRequestSummary, totalTokens?.toString().orEmpty())
        .joinToString("\u0001") >
        listOf(local.status, local.conversationTitle, local.userRequestSummary, local.totalTokens?.toString().orEmpty())
            .joinToString("\u0001")
}

internal data class AgentChatSyncPayload(
    val conversations: List<SyncConversation>,
    val messages: List<SyncMessage>,
    val attachments: List<SyncAttachment>,
    val runs: List<SyncRun>,
)

internal data class SyncConversation(
    val syncId: String,
    val title: String,
    val modelConfigId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

internal data class SyncMessage(
    val syncId: String,
    val conversationSyncId: String,
    val role: String,
    val content: String,
    val reasoning: String,
    val imageMimeType: String?,
    val createdAt: Long,
)

internal data class SyncAttachment(
    val syncId: String,
    val messageSyncId: String,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val kind: String,
    val extractedText: String?,
)

internal data class SyncRun(
    val runId: String,
    val conversationSyncId: String?,
    val conversationTitle: String,
    val userRequestSummary: String,
    val modelConfigId: String,
    val permissionMode: String,
    val enabledSourcesJson: String,
    val status: String,
    val modelCallCount: Int,
    val usageReportedCallCount: Int,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
    val cachedInputTokens: Long?,
    val cacheRateInputTokens: Long?,
    val reasoningTokens: Long?,
    val startedAt: Long,
    val completedAt: Long,
) {
    fun toEntity(conversationId: Long?) = AgentRunEntity(
        runId,
        conversationId,
        conversationTitle,
        userRequestSummary,
        modelConfigId,
        permissionMode,
        enabledSourcesJson,
        status,
        modelCallCount,
        usageReportedCallCount,
        inputTokens,
        outputTokens,
        totalTokens,
        cachedInputTokens,
        cacheRateInputTokens,
        reasoningTokens,
        startedAt,
        completedAt,
    )
}

internal object AgentChatSyncCodec {
    fun encode(payload: AgentChatSyncPayload): ByteArray {
        val root = JSONObject()
            .put("format", "deskcubby-agent-chats")
            .put("version", 1)
            .put("conversations", JSONArray(payload.conversations.sortedBy(SyncConversation::syncId).map { it.toJson() }))
            .put("messages", JSONArray(payload.messages.sortedBy(SyncMessage::syncId).map { it.toJson() }))
            .put("attachments", JSONArray(payload.attachments.sortedBy(SyncAttachment::syncId).map { it.toJson() }))
            .put("runs", JSONArray(payload.runs.sortedBy(SyncRun::runId).map { it.toJson() }))
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): AgentChatSyncPayload {
        if (bytes.isEmpty() || bytes.size > AgentChatSyncRepository.MAX_JSON_BYTES) {
            throw CloudSyncLimitException("Agent 会话同步文件大小无效。")
        }
        val root = try {
            JSONObject(bytes.toString(StandardCharsets.UTF_8))
        } catch (error: JSONException) {
            throw CloudSyncConflictException("Agent 会话同步文件不是有效 JSON。")
        }
        if (root.optString("format") != "deskcubby-agent-chats" || root.optInt("version") != 1) {
            throw CloudSyncConflictException("Agent 会话同步格式不受支持。")
        }
        val conversations = root.requireArray("conversations", MAX_CONVERSATIONS).mapObjects { value ->
            SyncConversation(
                value.safeId("syncId"),
                value.safeString("title", MAX_TITLE_CHARS),
                value.safeString("modelConfigId", MAX_MODEL_ID_CHARS, allowEmpty = true),
                value.safeTime("createdAt"),
                value.safeTime("updatedAt"),
                value.optionalTime("deletedAt"),
            ).also { require(it.updatedAt >= it.createdAt && (it.deletedAt == null || it.deletedAt >= it.createdAt)) }
        }
        val conversationIds = conversations.map(SyncConversation::syncId).requireUnique("conversation")
        val messages = root.requireArray("messages", MAX_MESSAGES).mapObjects { value ->
            val role = value.safeString("role", 20)
            require(role in setOf("user", "assistant", "system"))
            SyncMessage(
                value.safeId("syncId"),
                value.safeId("conversationSyncId").also { require(it in conversationIds) },
                role,
                value.safeString("content", MAX_MESSAGE_CHARS, allowEmpty = true),
                value.safeString("reasoning", MAX_MESSAGE_CHARS, allowEmpty = true),
                value.optionalString("imageMimeType", MAX_MIME_CHARS),
                value.safeTime("createdAt"),
            )
        }
        val messageIds = messages.map(SyncMessage::syncId).requireUnique("message")
        val attachments = root.requireArray("attachments", MAX_ATTACHMENTS).mapObjects { value ->
            SyncAttachment(
                value.safeId("syncId"),
                value.safeId("messageSyncId").also { require(it in messageIds) },
                value.safeString("mimeType", MAX_MIME_CHARS),
                value.safeString("displayName", MAX_ATTACHMENT_NAME_CHARS),
                value.safeBoundedLong("sizeBytes", 0, MAX_ATTACHMENT_SIZE),
                value.safeString("kind", 20).also { require(it in setOf("IMAGE", "DOCUMENT")) },
                value.optionalString("extractedText", MAX_EXTRACTED_TEXT_CHARS),
            )
        }.also { values -> values.map(SyncAttachment::syncId).requireUnique("attachment") }
        val runs = root.requireArray("runs", MAX_RUNS).mapObjects { value ->
            val input = value.optionalBoundedLong("inputTokens", MAX_TOKENS)
            val cached = value.optionalBoundedLong("cachedInputTokens", MAX_TOKENS)
            // v1 payloads written before the denominator field was introduced used the
            // complete run input total for cache-rate calculations.
            val cacheRateInput = value.optionalBoundedLong("cacheRateInputTokens", MAX_TOKENS)
                ?: input.takeIf { cached != null }
            require(cacheRateInput == null || cached == null || cached <= cacheRateInput)
            SyncRun(
                value.safeId("runId"),
                value.optionalString("conversationSyncId", MAX_ID_CHARS)
                    ?.also { require(it in conversationIds) },
                value.safeString("conversationTitle", MAX_TITLE_CHARS),
                value.safeString("userRequestSummary", MAX_REQUEST_CHARS, allowEmpty = true),
                value.safeString("modelConfigId", MAX_MODEL_ID_CHARS, allowEmpty = true),
                value.safeString("permissionMode", 32).also { AgentPermissionMode.valueOf(it) },
                value.safeString("enabledSourcesJson", MAX_SOURCES_CHARS).also { JSONArray(it) },
                value.safeString("status", 32).also { require(it in setOf("SUCCEEDED", "FAILED", "CANCELED")) },
                value.safeBoundedLong("modelCallCount", 0, MAX_CALLS.toLong()).toInt(),
                value.safeBoundedLong("usageReportedCallCount", 0, MAX_CALLS.toLong()).toInt(),
                input,
                value.optionalBoundedLong("outputTokens", MAX_TOKENS),
                value.optionalBoundedLong("totalTokens", MAX_TOKENS),
                cached,
                cacheRateInput,
                value.optionalBoundedLong("reasoningTokens", MAX_TOKENS),
                value.safeTime("startedAt"),
                value.safeTime("completedAt"),
            )
        }.also { values -> values.map(SyncRun::runId).requireUnique("run") }
        return AgentChatSyncPayload(conversations, messages, attachments, runs)
    }

    private fun SyncConversation.toJson() = JSONObject()
        .put("syncId", syncId).put("title", title).put("modelConfigId", modelConfigId)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)
        .putNullable("deletedAt", deletedAt)

    private fun SyncMessage.toJson() = JSONObject()
        .put("syncId", syncId).put("conversationSyncId", conversationSyncId).put("role", role)
        .put("content", content).put("reasoning", reasoning)
        .putNullable("imageMimeType", imageMimeType).put("createdAt", createdAt)

    private fun SyncAttachment.toJson() = JSONObject()
        .put("syncId", syncId).put("messageSyncId", messageSyncId).put("mimeType", mimeType)
        .put("displayName", displayName).put("sizeBytes", sizeBytes).put("kind", kind)
        .putNullable("extractedText", extractedText)

    private fun SyncRun.toJson() = JSONObject()
        .put("runId", runId).putNullable("conversationSyncId", conversationSyncId)
        .put("conversationTitle", conversationTitle).put("userRequestSummary", userRequestSummary)
        .put("modelConfigId", modelConfigId).put("permissionMode", permissionMode)
        .put("enabledSourcesJson", enabledSourcesJson).put("status", status)
        .put("modelCallCount", modelCallCount).put("usageReportedCallCount", usageReportedCallCount)
        .putNullable("inputTokens", inputTokens).putNullable("outputTokens", outputTokens)
        .putNullable("totalTokens", totalTokens).putNullable("cachedInputTokens", cachedInputTokens)
        .putNullable("cacheRateInputTokens", cacheRateInputTokens)
        .putNullable("reasoningTokens", reasoningTokens).put("startedAt", startedAt)
        .put("completedAt", completedAt)

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun JSONObject.safeId(key: String): String = safeString(key, MAX_ID_CHARS).also {
        require(SAFE_ID.matches(it)) { "$key is invalid" }
    }

    private fun JSONObject.safeString(key: String, max: Int, allowEmpty: Boolean = false): String {
        val value = getString(key)
        require(value.length <= max && (allowEmpty || value.isNotBlank())) { "$key is invalid" }
        return value
    }

    private fun JSONObject.optionalString(key: String, max: Int): String? =
        if (!has(key) || isNull(key)) null else safeString(key, max)

    private fun JSONObject.safeTime(key: String): Long = safeBoundedLong(key, 0, MAX_TIMESTAMP)

    private fun JSONObject.optionalTime(key: String): Long? = optionalBoundedLong(key, MAX_TIMESTAMP)

    private fun JSONObject.safeBoundedLong(key: String, min: Long, max: Long): Long =
        getLong(key).also { require(it in min..max) { "$key is outside the allowed range" } }

    private fun JSONObject.optionalBoundedLong(key: String, max: Long): Long? =
        if (!has(key) || isNull(key)) null else safeBoundedLong(key, 0, max)

    private fun JSONObject.requireArray(key: String, max: Int): JSONArray = getJSONArray(key).also {
        require(it.length() <= max) { "$key contains too many records" }
    }

    private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
        (0 until length()).map { index -> block(getJSONObject(index)) }

    private fun List<String>.requireUnique(label: String): Set<String> = toSet().also {
        require(it.size == size) { "$label ids must be unique" }
    }

    private const val MAX_CONVERSATIONS = 10_000
    private const val MAX_MESSAGES = 100_000
    private const val MAX_ATTACHMENTS = 200_000
    private const val MAX_RUNS = 100_000
    private const val MAX_TITLE_CHARS = 500
    private const val MAX_MODEL_ID_CHARS = 200
    private const val MAX_REQUEST_CHARS = 2_000
    private const val MAX_MESSAGE_CHARS = 1_000_000
    private const val MAX_ATTACHMENT_NAME_CHARS = 500
    private const val MAX_EXTRACTED_TEXT_CHARS = 256 * 1024
    private const val MAX_MIME_CHARS = 200
    private const val MAX_SOURCES_CHARS = 2_048
    private const val MAX_ID_CHARS = 200
    private const val MAX_ATTACHMENT_SIZE = 64L * 1024 * 1024
    private const val MAX_TOKENS = 1_000_000_000_000L
    private const val MAX_CALLS = 1_000_000
    private const val MAX_TIMESTAMP = 253_402_300_799_999L
    private val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,$MAX_ID_CHARS}")
}
