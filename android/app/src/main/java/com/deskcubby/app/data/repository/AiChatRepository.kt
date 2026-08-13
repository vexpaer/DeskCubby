package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.deskcubby.app.data.local.AiChatDao
import com.deskcubby.app.data.local.AiAttachmentEntity
import com.deskcubby.app.data.local.AiConversationEntity
import com.deskcubby.app.data.local.AiMessageEntity
import com.deskcubby.app.data.local.AiMessageWithAttachments
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.deskcubby.plugin.api.core.api.AIToolCall
import com.deskcubby.plugin.api.core.api.AIToolCompletion
import com.deskcubby.plugin.api.core.api.AIToolCompletionRequest
import com.deskcubby.plugin.api.core.api.AITokenUsage

enum class AiChatRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
    /**
     * Stored as the legacy `system` value for database compatibility. Request construction
     * deliberately sends its untrusted contents at user privilege.
     */
    CONTEXT("system"),
}

data class AiChatMessage(
    val id: Long,
    val role: AiChatRole,
    val content: String,
    val reasoning: String = "",
    val image: AiChatImage? = null,
    val attachments: List<AiChatAttachment> = emptyList(),
    val createdAt: Long = 0L,
)

data class AiChatImage(
    val uri: String,
    val mimeType: String,
    val permissionOwnedByChat: Boolean = false,
)

data class AiConversation(
    val id: Long,
    val title: String,
    val modelConfigId: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class AiChatCompletion(
    val content: String,
    val reasoning: String = "",
)

enum class AiChatFailure {
    CONFIGURATION,
    NETWORK,
    REMOTE,
    INVALID_RESPONSE,
    RESPONSE_TOO_LARGE,
}

class AiChatException(
    val failure: AiChatFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** A small, dependency-free client for OpenAI-compatible chat/completions APIs. */
@Singleton
class AiChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiChatDao: AiChatDao,
    private val attachmentService: AiAttachmentService,
) {
    private val imagePermissionMutex = Mutex()

    fun observeConversations(): Flow<List<AiConversation>> =
        aiChatDao.observeConversations().map { items -> items.map(AiConversationEntity::toDomain) }

    fun observeMessages(conversationId: Long): Flow<List<AiChatMessage>> =
        aiChatDao.observeMessagesWithAttachments(conversationId)
            .map { items -> items.mapNotNull(AiMessageWithAttachments::toDomain) }

    suspend fun getConversation(id: Long): AiConversation? = withContext(Dispatchers.IO) {
        aiChatDao.getConversation(id)?.toDomain()
    }

    suspend fun getMessages(conversationId: Long): List<AiChatMessage> = withContext(Dispatchers.IO) {
        aiChatDao.getMessagesWithAttachments(conversationId)
            .mapNotNull(AiMessageWithAttachments::toDomain)
    }

    suspend fun createConversation(
        firstMessage: String,
        hasImage: Boolean,
        modelConfigId: String,
        now: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        aiChatDao.insertConversation(
            AiConversationEntity(
                title = generateConversationTitle(firstMessage, hasImage),
                modelConfigId = modelConfigId,
                createdAt = now,
                updatedAt = now,
                syncId = UUID.randomUUID().toString(),
            ),
        )
    }

    suspend fun appendMessage(
        conversationId: Long,
        role: AiChatRole,
        content: String,
        reasoning: String = "",
        image: AiChatImage? = null,
        now: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        withPersistedImagePermission(image) { imagePermission ->
            aiChatDao.insertMessageAndTouch(
                AiMessageEntity(
                    conversationId = conversationId,
                    role = role.apiValue,
                    content = content,
                    reasoning = reasoning,
                    imageUri = image?.uri,
                    imageMimeType = image?.mimeType,
                    imagePermissionOwned = imagePermission?.ownedByChat ?: false,
                    createdAt = now,
                    syncId = UUID.randomUUID().toString(),
                ),
            )
        }
    }

    suspend fun renameConversation(id: Long, title: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = title.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_CHARS)
        normalized.isNotEmpty() &&
            aiChatDao.renameConversation(id, normalized, System.currentTimeMillis()) > 0
    }

    suspend fun setConversationModel(id: Long, modelConfigId: String): Boolean =
        withContext(Dispatchers.IO) {
            aiChatDao.setModelConfig(id, modelConfigId, System.currentTimeMillis()) > 0
        }

    suspend fun deleteConversation(id: Long): Boolean = withContext(Dispatchers.IO) {
        imagePermissionMutex.withLock {
            val ownedImageUris = aiChatDao.deleteConversationAndGetOwnedImageUris(id)
                ?: return@withLock false
            ownedImageUris.forEach { uri ->
                if (aiChatDao.countImageReferences(uri) == 0 &&
                    aiChatDao.countAttachmentReferences(uri) == 0
                ) {
                    releaseImagePermission(uri)
                }
            }
            true
        }
    }

    suspend fun prepareImage(uriValue: String): AiChatImage = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: throw AiChatException(AiChatFailure.CONFIGURATION, "只能选择系统文件选择器中的图片。")
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri)
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.startsWith("image/") }
            ?: throw AiChatException(AiChatFailure.CONFIGURATION, "所选文件不是受支持的图片。")
        readImageBytes(uri)
        AiChatImage(uri = uri.toString(), mimeType = mimeType)
    }

    suspend fun prepareAttachment(uriValue: String): AiChatAttachment =
        attachmentService.prepare(uriValue)

    suspend fun appendAgentUserMessage(
        conversationId: Long,
        content: String,
        attachments: List<AiChatAttachment>,
        now: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        require(attachments.size <= MAX_AGENT_ATTACHMENTS) { "Too many Agent attachments" }
        withPersistedAttachmentPermissions(attachments) { permissions ->
            aiChatDao.insertMessageWithAttachmentsAndTouch(
                message = AiMessageEntity(
                    conversationId = conversationId,
                    role = AiChatRole.USER.apiValue,
                    content = content,
                    reasoning = "",
                    imageUri = null,
                    imageMimeType = null,
                    imagePermissionOwned = false,
                    createdAt = now,
                    syncId = UUID.randomUUID().toString(),
                ),
                attachments = attachments.mapIndexed { index, attachment ->
                    AiAttachmentEntity(
                        messageId = 0,
                        uri = attachment.uri,
                        mimeType = attachment.mimeType,
                        displayName = attachment.displayName,
                        sizeBytes = attachment.sizeBytes,
                        kind = attachment.kind.name,
                        extractedText = attachment.extractedText,
                        permissionOwned = permissions[index].ownedByChat,
                        syncId = UUID.randomUUID().toString(),
                    )
                },
            )
        }
    }

    private suspend fun persistImagePermission(image: AiChatImage): PersistedImagePermission {
        val resolver = context.contentResolver
        val uri = Uri.parse(image.uri)
        val alreadyPersisted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        var newlyAcquired = false
        if (!alreadyPersisted) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                newlyAcquired = true
            } catch (error: SecurityException) {
                throw AiChatException(
                    AiChatFailure.CONFIGURATION,
                    "无法保留所选图片的读取权限，请重新选择。",
                    error,
                )
            }
        }
        return try {
            val alreadyOwnedByChat = alreadyPersisted && aiChatDao.isImagePermissionOwned(image.uri)
            PersistedImagePermission(
                ownedByChat = newlyAcquired || alreadyOwnedByChat,
                newlyAcquired = newlyAcquired,
            )
        } catch (error: Throwable) {
            if (newlyAcquired) {
                releaseImagePermission(image.uri)
            }
            throw error
        }
    }

    private suspend fun persistAttachmentPermission(
        attachment: AiChatAttachment,
    ): PersistedImagePermission {
        val resolver = context.contentResolver
        val uri = Uri.parse(attachment.uri)
        val alreadyPersisted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        var newlyAcquired = false
        if (!alreadyPersisted) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                newlyAcquired = true
            } catch (error: SecurityException) {
                throw AiChatException(
                    AiChatFailure.CONFIGURATION,
                    "无法保留所选附件的读取权限，请重新选择。",
                    error,
                )
            }
        }
        return try {
            val alreadyOwnedByChat = alreadyPersisted &&
                (aiChatDao.isAttachmentPermissionOwned(attachment.uri) ||
                    aiChatDao.isImagePermissionOwned(attachment.uri))
            PersistedImagePermission(
                ownedByChat = newlyAcquired || alreadyOwnedByChat,
                newlyAcquired = newlyAcquired,
            )
        } catch (error: Throwable) {
            if (newlyAcquired) releaseImagePermission(attachment.uri)
            throw error
        }
    }

    /**
     * URI grant ownership and Room references form one process-level critical section. Room is
     * still the durable reference count; on any insert failure or cancellation we check it in a
     * non-cancellable cleanup before releasing a grant acquired by this operation.
     */
    private suspend fun <T> withPersistedImagePermission(
        image: AiChatImage?,
        block: suspend (PersistedImagePermission?) -> T,
    ): T = imagePermissionMutex.withLock {
        var permission: PersistedImagePermission? = null
        try {
            permission = image?.let { persistImagePermission(it) }
            block(permission)
        } catch (error: Throwable) {
            if (image != null && permission?.newlyAcquired == true) {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (aiChatDao.countImageReferences(image.uri) == 0) {
                        releaseImagePermission(image.uri)
                    }
                }
            }
            throw error
        }
    }

    private suspend fun <T> withPersistedAttachmentPermissions(
        attachments: List<AiChatAttachment>,
        block: suspend (List<PersistedImagePermission>) -> T,
    ): T = imagePermissionMutex.withLock {
        val permissions = mutableListOf<PersistedImagePermission>()
        try {
            attachments.forEach { attachment ->
                permissions += persistAttachmentPermission(attachment)
            }
            block(permissions)
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                attachments.zip(permissions).forEach { (attachment, permission) ->
                    if (permission.newlyAcquired &&
                        aiChatDao.countImageReferences(attachment.uri) == 0 &&
                        aiChatDao.countAttachmentReferences(attachment.uri) == 0
                    ) {
                        releaseImagePermission(attachment.uri)
                    }
                }
            }
            throw error
        }
    }

    private fun releaseImagePermission(uriValue: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriValue),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    suspend fun complete(
        settings: AppSettings,
        messages: List<AiChatMessage>,
    ): String = completeWithReasoning(settings, messages).content

    suspend fun completeWithReasoning(
        settings: AppSettings,
        messages: List<AiChatMessage>,
        onUpdate: ((AiChatCompletion) -> Unit)? = null,
    ): AiChatCompletion = withContext(Dispatchers.IO) {
        val config = settings.aiConfigs.firstOrNull {
            it.id == settings.aiChatConfigId && it.type == AiModelType.TEXT
        }
        val model = config?.model?.trim() ?: settings.aiModel.trim()
        if (model.isEmpty()) {
            throw AiChatException(
                AiChatFailure.CONFIGURATION,
                "请先在 AI 设置中填写模型名称。",
            )
        }

        val endpoint = parseAndValidateEndpoint(
            rawValue = config?.endpointUrl ?: settings.aiEndpointUrl,
            allowInsecureHttp = config?.allowInsecureHttp ?: settings.aiAllowInsecureHttp,
        )
        val imageDataUrls = buildMap {
            var totalImageBytes = 0
            messages.asReversed().forEach { message ->
                val image = message.image ?: return@forEach
                val imageBytes = readImageBytes(Uri.parse(image.uri))
                if (totalImageBytes + imageBytes.size > MAX_IMAGE_BYTES) return@forEach
                put(
                    message.id,
                    "data:${image.mimeType};base64," +
                        Base64.encodeToString(imageBytes, Base64.NO_WRAP),
                )
                totalImageBytes += imageBytes.size
            }
        }
        val requestJson = buildTextChatRequestJson(
            model = model,
            temperature = config?.temperature ?: settings.aiTemperature,
            systemPrompt = config?.systemPrompt?.takeIf(String::isNotBlank)
                ?: settings.aiSystemPrompt.takeIf(String::isNotBlank),
            messages = messages,
            imageDataUrls = imageDataUrls,
        )
        if (onUpdate != null) requestJson.put("stream", true)
        val requestBody = requestJson.toString()
            .toByteArray(StandardCharsets.UTF_8)
        val requestBodyLimit = if (imageDataUrls.isEmpty()) {
            MAX_BODY_BYTES
        } else {
            MAX_IMAGE_REQUEST_BODY_BYTES
        }
        if (requestBody.size > requestBodyLimit) {
            throw AiChatException(
                AiChatFailure.CONFIGURATION,
                "当前对话内容过长，请清空对话后重试。",
            )
        }

        try {
            val apiKey = config?.apiKey?.trim().orEmpty()
            if (onUpdate == null) {
                parseAssistantContent(
                    executeRequest(
                        initialUrl = endpoint,
                        body = requestBody,
                        apiKey = apiKey,
                        allowInsecureHttp = config?.allowInsecureHttp
                            ?: settings.aiAllowInsecureHttp,
                    ),
                    apiKey,
                )
            } else {
                executeStreamingRequest(
                    initialUrl = endpoint,
                    body = requestBody,
                    apiKey = apiKey,
                    allowInsecureHttp = config?.allowInsecureHttp
                        ?: settings.aiAllowInsecureHttp,
                    onUpdate = onUpdate,
                )
            }
        } catch (error: AiChatException) {
            throw error
        } catch (error: IOException) {
            throw AiChatException(
                AiChatFailure.NETWORK,
                "无法连接 AI 服务，请检查网络和接口地址。",
                error,
            )
        } catch (error: JSONException) {
            throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 服务返回了无法识别的数据。",
                error,
            )
        }
    }

    suspend fun completeWithTools(
        config: AiModelConfig,
        request: AIToolCompletionRequest,
    ): AIToolCompletion = withContext(Dispatchers.IO) {
        require(config.type == AiModelType.TEXT) { "Agent requires a text model" }
        if (!config.supportsToolCalling) {
            throw AiChatException(
                AiChatFailure.CONFIGURATION,
                "当前模型配置未启用原生工具调用，无法运行 Agent。",
            )
        }
        if (request.tools.isEmpty() || request.tools.size > MAX_AGENT_TOOLS) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "Agent 工具列表无效。")
        }
        if (request.systemPrompt.isBlank() || request.systemPrompt.length > MAX_AGENT_SYSTEM_CHARS) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "Agent 系统提示词无效。")
        }
        request.tools.forEach { tool ->
            if (!AGENT_TOOL_NAME.matches(tool.name) ||
                tool.description.length > MAX_AGENT_TOOL_DESCRIPTION_CHARS ||
                tool.parametersJson.length > MAX_AGENT_TOOL_SCHEMA_CHARS
            ) {
                throw AiChatException(AiChatFailure.CONFIGURATION, "Agent 工具定义无效。")
            }
            try {
                JSONObject(tool.parametersJson)
            } catch (error: JSONException) {
                throw AiChatException(AiChatFailure.CONFIGURATION, "Agent 工具参数结构无效。", error)
            }
        }
        val endpoint = parseAndValidateEndpoint(config.endpointUrl, config.allowInsecureHttp)
        val imageDataUrls = buildMap {
            var totalBytes = 0
            request.messages.forEachIndexed { messageIndex, message ->
                message.images.forEachIndexed { imageIndex, image ->
                    val mimeType = image.mimeType.substringBefore(';').trim().lowercase()
                    if (!mimeType.startsWith("image/")) {
                        throw AiChatException(
                            AiChatFailure.CONFIGURATION,
                            "Agent 附件包含不受支持的图片类型。",
                        )
                    }
                    val bytes = readImageBytes(Uri.parse(image.contentUri))
                    totalBytes += bytes.size
                    if (totalBytes > MAX_IMAGE_BYTES) {
                        throw AiChatException(
                            AiChatFailure.CONFIGURATION,
                            "当前 Agent 会话中的图片合计超过 8 MiB。",
                        )
                    }
                    put(
                        messageIndex to imageIndex,
                        "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP),
                    )
                }
            }
        }
        val requestBody = buildAgentRequestJson(
            model = config.model.trim(),
            temperature = config.temperature,
            request = request,
            imageDataUrls = imageDataUrls,
        ).toString().toByteArray(StandardCharsets.UTF_8)
        val limit = if (imageDataUrls.isEmpty()) MAX_BODY_BYTES else MAX_IMAGE_REQUEST_BODY_BYTES
        if (requestBody.size > limit) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "Agent 请求内容过长。")
        }
        try {
            parseToolCompletion(
                responseBody = executeRequest(
                    initialUrl = endpoint,
                    body = requestBody,
                    apiKey = config.apiKey.trim(),
                    allowInsecureHttp = config.allowInsecureHttp,
                ),
                apiKey = config.apiKey.trim(),
            )
        } catch (error: AiChatException) {
            throw error
        } catch (error: IOException) {
            throw AiChatException(
                AiChatFailure.NETWORK,
                "无法连接 AI 服务，请检查网络和接口地址。",
                error,
            )
        } catch (error: JSONException) {
            throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "模型返回了非法工具调用。",
                error,
            )
        }
    }

    private fun parseToolCompletion(responseBody: String, apiKey: String): AIToolCompletion {
        val root = try {
            JSONObject(responseBody)
        } catch (error: JSONException) {
            throw AiChatException(AiChatFailure.INVALID_RESPONSE, "AI 服务返回了无法识别的数据。", error)
        }
        root.optJSONObject("error")?.let { errorObject ->
            val message = sanitizeAiRemoteError(errorObject.optString("message"), apiKey)
            throw AiChatException(
                AiChatFailure.REMOTE,
                message.takeIf(String::isNotEmpty) ?: "AI 服务返回了错误。",
            )
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiChatException(AiChatFailure.INVALID_RESPONSE, "AI 响应中没有可用的回答。")
        val message = choice.optJSONObject("message")
            ?: throw AiChatException(AiChatFailure.INVALID_RESPONSE, "AI 响应中没有消息对象。")
        val rawContent = extractAiContent(message.opt("content")).orEmpty()
        val tagged = splitAiThinkingContent(rawContent)
        val explicitReasoning = listOf("reasoning_content", "reasoning", "analysis")
            .mapNotNull { key -> extractAiContent(message.opt(key))?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.trim()
            .orEmpty()
        val reasoning = listOf(explicitReasoning, tagged.reasoning)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n\n")
        val callsArray = message.optJSONArray("tool_calls")
        if (callsArray != null && callsArray.length() > MAX_AGENT_TOOL_CALLS_PER_RESPONSE) {
            throw AiChatException(AiChatFailure.INVALID_RESPONSE, "模型一次返回了过多工具调用。")
        }
        val ids = hashSetOf<String>()
        val calls = buildList {
            if (callsArray != null) {
                for (index in 0 until callsArray.length()) {
                    val rawCall = callsArray.optJSONObject(index)
                        ?: throw AiChatException(AiChatFailure.INVALID_RESPONSE, "模型返回了非法工具调用。")
                    val id = rawCall.optString("id").trim()
                    val function = rawCall.optJSONObject("function")
                        ?: throw AiChatException(AiChatFailure.INVALID_RESPONSE, "模型返回了非法工具调用。")
                    val name = function.optString("name").trim()
                    val argumentsRaw = function.optString("arguments")
                    if (id.isBlank() || id.length > MAX_AGENT_TOOL_CALL_ID_CHARS ||
                        !ids.add(id) || !AGENT_TOOL_NAME.matches(name) ||
                        argumentsRaw.toByteArray(StandardCharsets.UTF_8).size > MAX_AGENT_ARGUMENT_BYTES
                    ) {
                        throw AiChatException(AiChatFailure.INVALID_RESPONSE, "模型返回了非法工具调用。")
                    }
                    val arguments = try {
                        JSONObject(argumentsRaw).toStrictMap()
                    } catch (error: JSONException) {
                        throw AiChatException(AiChatFailure.INVALID_RESPONSE, "模型返回了非法工具参数。", error)
                    }
                    add(AIToolCall(id = id, name = name, arguments = arguments))
                }
            }
        }
        if (calls.isEmpty() && tagged.content.isBlank() && reasoning.isBlank()) {
            throw AiChatException(AiChatFailure.INVALID_RESPONSE, "AI 返回了空回答。")
        }
        return AIToolCompletion(
            content = tagged.content.trim(),
            reasoning = reasoning,
            toolCalls = calls,
            usage = parseTokenUsage(root),
        )
    }

    private fun parseTokenUsage(root: JSONObject): AITokenUsage {
        val usage = root.optJSONObject("usage") ?: return AITokenUsage()
        fun boundedLong(container: JSONObject, key: String): Long? {
            if (!container.has(key) || container.isNull(key)) return null
            return container.optLong(key, -1L).takeIf { it in 0..MAX_REPORTED_TOKENS }
        }
        val input = boundedLong(usage, "prompt_tokens") ?: boundedLong(usage, "input_tokens")
        val output = boundedLong(usage, "completion_tokens") ?: boundedLong(usage, "output_tokens")
        val promptDetails = usage.optJSONObject("prompt_tokens_details")
            ?: usage.optJSONObject("input_tokens_details")
        val completionDetails = usage.optJSONObject("completion_tokens_details")
            ?: usage.optJSONObject("output_tokens_details")
        val cached = promptDetails?.let { boundedLong(it, "cached_tokens") }
            ?: boundedLong(usage, "cache_read_input_tokens")
        return AITokenUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = boundedLong(usage, "total_tokens"),
            cachedInputTokens = cached?.coerceAtMost(input ?: cached),
            reasoningTokens = completionDetails?.let { boundedLong(it, "reasoning_tokens") },
        )
    }

    suspend fun analyzeImage(
        config: AiModelConfig,
        prompt: String,
        mimeType: String,
        imageBytes: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        require(config.type == AiModelType.IMAGE)
        val endpoint = parseAndValidateEndpoint(config.endpointUrl, config.allowInsecureHttp)
        val imageDataUrl = "data:$mimeType;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}"
        val body = buildImageChatRequestJson(
            model = config.model,
            temperature = config.temperature,
            prompt = prompt,
            imageDataUrl = imageDataUrl,
        )
            .toString().toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_IMAGE_REQUEST_BODY_BYTES) throw AiChatException(AiChatFailure.CONFIGURATION, "图片过大，无法发送。")
        val response = executeRequest(endpoint, body, config.apiKey.trim(), config.allowInsecureHttp)
        parseAssistantContent(response, config.apiKey.trim()).content
    }

    suspend fun analyzeImageWithReasoningStreaming(
        config: AiModelConfig,
        prompt: String,
        mimeType: String,
        imageBytes: ByteArray,
        onUpdate: (AiChatCompletion) -> Unit,
    ): AiChatCompletion = withContext(Dispatchers.IO) {
        require(config.type == AiModelType.IMAGE)
        val endpoint = parseAndValidateEndpoint(config.endpointUrl, config.allowInsecureHttp)
        val imageDataUrl =
            "data:$mimeType;base64,${Base64.encodeToString(imageBytes, Base64.NO_WRAP)}"
        val requestJson = buildImageChatRequestJson(
            model = config.model,
            temperature = config.temperature,
            prompt = prompt,
            imageDataUrl = imageDataUrl,
        ).put("stream", true)
        val body = requestJson.toString().toByteArray(StandardCharsets.UTF_8)
        if (body.size > MAX_IMAGE_REQUEST_BODY_BYTES) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "图片过大，无法发送。")
        }
        try {
            executeStreamingRequest(
                initialUrl = endpoint,
                body = body,
                apiKey = config.apiKey.trim(),
                allowInsecureHttp = config.allowInsecureHttp,
                onUpdate = onUpdate,
            )
        } catch (error: AiChatException) {
            throw error
        } catch (error: IOException) {
            throw AiChatException(
                AiChatFailure.NETWORK,
                "无法连接 AI 服务，请检查网络和接口地址。",
                error,
            )
        } catch (error: JSONException) {
            throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 服务返回了无法识别的数据。",
                error,
            )
        }
    }

    private suspend fun executeRequest(
        initialUrl: URL,
        body: ByteArray,
        apiKey: String,
        allowInsecureHttp: Boolean,
    ): String = suspendCancellableCoroutine { continuation ->
        val allowedHost = initialUrl.host
        var currentUrl = initialUrl
        var redirects = 0
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
        }

        try {
            while (true) {
                ensureRequestActive(continuation.isActive)
                val connection = (currentUrl.openConnection() as? HttpURLConnection)
                    ?: throw AiChatException(AiChatFailure.CONFIGURATION, "AI 接口地址不是 HTTP 地址。")
                activeConnection.set(connection)
                ensureRequestActive(continuation.isActive)
                var redirectUrl: URL? = null
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (apiKey.isNotEmpty()) {
                        connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { output ->
                        var offset = 0
                        while (offset < body.size) {
                            ensureRequestActive(continuation.isActive)
                            val count = minOf(WRITE_BUFFER_BYTES, body.size - offset)
                            output.write(body, offset, count)
                            offset += count
                        }
                        output.flush()
                    }
                    ensureRequestActive(continuation.isActive)

                    val status = connection.responseCode
                    ensureRequestActive(continuation.isActive)
                    if (status in REDIRECT_STATUS_CODES) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw AiChatException(AiChatFailure.NETWORK, "AI 接口重定向次数过多。")
                        }
                        val location = connection.getHeaderField("Location")
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: throw AiChatException(
                                AiChatFailure.INVALID_RESPONSE,
                                "AI 接口返回了无效的重定向。",
                            )
                        val candidate = try {
                            URL(currentUrl, location)
                        } catch (error: MalformedURLException) {
                            throw AiChatException(
                                AiChatFailure.INVALID_RESPONSE,
                                "AI 接口返回了无效的重定向地址。",
                                error,
                            )
                        }
                        validateRedirect(
                            from = currentUrl,
                            candidate = candidate,
                            allowedHost = allowedHost,
                            allowInsecureHttp = allowInsecureHttp,
                        )
                        redirectUrl = candidate
                    } else {
                        val responseBody = readResponseBody(
                            connection = connection,
                            status = status,
                            isActive = { continuation.isActive },
                        )
                        if (status !in 200..299) {
                            val remoteMessage = parseRemoteError(responseBody)
                                ?.let { sanitizeAiRemoteError(it, apiKey) }
                            val suffix = remoteMessage?.let { "：$it" }.orEmpty()
                            throw AiChatException(
                                AiChatFailure.REMOTE,
                                "AI 服务返回 HTTP $status$suffix",
                            )
                        }
                        continuation.resume(responseBody)
                        return@suspendCancellableCoroutine
                    }
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }

                currentUrl = checkNotNull(redirectUrl)
                redirects += 1
            }
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    /**
     * Reads OpenAI-compatible server-sent events without exposing the connection to callers.
     * Providers that ignore `stream=true` and return a normal JSON body still use the regular
     * response parser, so calorie estimation remains compatible with non-streaming endpoints.
     */
    private suspend fun executeStreamingRequest(
        initialUrl: URL,
        body: ByteArray,
        apiKey: String,
        allowInsecureHttp: Boolean,
        onUpdate: (AiChatCompletion) -> Unit,
    ): AiChatCompletion = suspendCancellableCoroutine { continuation ->
        val allowedHost = initialUrl.host
        var currentUrl = initialUrl
        var redirects = 0
        val activeConnection = AtomicReference<HttpURLConnection?>(null)
        continuation.invokeOnCancellation {
            activeConnection.getAndSet(null)?.disconnect()
        }

        try {
            while (true) {
                ensureRequestActive(continuation.isActive)
                val connection = (currentUrl.openConnection() as? HttpURLConnection)
                    ?: throw AiChatException(
                        AiChatFailure.CONFIGURATION,
                        "AI 接口地址不是 HTTP 地址。",
                    )
                activeConnection.set(connection)
                ensureRequestActive(continuation.isActive)
                var redirectUrl: URL? = null
                try {
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "text/event-stream, application/json")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (apiKey.isNotEmpty()) {
                        connection.setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { output ->
                        var offset = 0
                        while (offset < body.size) {
                            ensureRequestActive(continuation.isActive)
                            val count = minOf(WRITE_BUFFER_BYTES, body.size - offset)
                            output.write(body, offset, count)
                            offset += count
                        }
                        output.flush()
                    }
                    ensureRequestActive(continuation.isActive)

                    val status = connection.responseCode
                    ensureRequestActive(continuation.isActive)
                    if (status in REDIRECT_STATUS_CODES) {
                        if (redirects >= MAX_REDIRECTS) {
                            throw AiChatException(
                                AiChatFailure.NETWORK,
                                "AI 接口重定向次数过多。",
                            )
                        }
                        val location = connection.getHeaderField("Location")
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: throw AiChatException(
                                AiChatFailure.INVALID_RESPONSE,
                                "AI 接口返回了无效的重定向。",
                            )
                        val candidate = try {
                            URL(currentUrl, location)
                        } catch (error: MalformedURLException) {
                            throw AiChatException(
                                AiChatFailure.INVALID_RESPONSE,
                                "AI 接口返回了无效的重定向地址。",
                                error,
                            )
                        }
                        validateRedirect(
                            from = currentUrl,
                            candidate = candidate,
                            allowedHost = allowedHost,
                            allowInsecureHttp = allowInsecureHttp,
                        )
                        redirectUrl = candidate
                    } else if (status !in 200..299) {
                        val responseBody = readResponseBody(
                            connection = connection,
                            status = status,
                            isActive = { continuation.isActive },
                        )
                        val remoteMessage = parseRemoteError(responseBody)
                            ?.let { sanitizeAiRemoteError(it, apiKey) }
                        val suffix = remoteMessage?.let { "：$it" }.orEmpty()
                        throw AiChatException(
                            AiChatFailure.REMOTE,
                            "AI 服务返回 HTTP $status$suffix",
                        )
                    } else {
                        val contentType = connection.contentType
                            ?.substringBefore(';')
                            ?.trim()
                            ?.lowercase()
                        val completion = if (contentType == "text/event-stream") {
                            readStreamingCompletion(
                                connection = connection,
                                apiKey = apiKey,
                                isActive = { continuation.isActive },
                                onUpdate = onUpdate,
                            )
                        } else {
                            val parsed = parseAssistantContent(
                                readResponseBody(
                                    connection = connection,
                                    status = status,
                                    isActive = { continuation.isActive },
                                ),
                                apiKey,
                            )
                            onUpdate(parsed)
                            parsed
                        }
                        continuation.resume(completion)
                        return@suspendCancellableCoroutine
                    }
                } finally {
                    activeConnection.compareAndSet(connection, null)
                    connection.disconnect()
                }

                currentUrl = checkNotNull(redirectUrl)
                redirects += 1
            }
        } catch (error: Throwable) {
            if (continuation.isActive) continuation.resumeWithException(error)
        } finally {
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    private fun readStreamingCompletion(
        connection: HttpURLConnection,
        apiKey: String,
        isActive: () -> Boolean,
        onUpdate: (AiChatCompletion) -> Unit,
    ): AiChatCompletion {
        ensureRequestActive(isActive())
        val declaredLength = connection.contentLengthLong
        if (declaredLength > MAX_BODY_BYTES) {
            throw AiChatException(
                AiChatFailure.RESPONSE_TOO_LARGE,
                "AI 服务响应超过 4 MiB，已停止读取。",
            )
        }
        val accumulator = AiStreamAccumulator(apiKey)

        fun consumeDataLine(line: String) {
            if (!line.startsWith("data:")) return
            val payload = line.substringAfter(':').trim()
            if (payload.isEmpty()) return
            accumulator.consumePayload(payload)?.let(onUpdate)
        }

        BufferedInputStream(connection.inputStream).use { input ->
            val line = ByteArrayOutputStream()
            var total = 0
            while (!accumulator.done) {
                ensureRequestActive(isActive())
                val value = input.read()
                ensureRequestActive(isActive())
                if (value < 0) break
                total += 1
                if (total > MAX_BODY_BYTES) {
                    throw AiChatException(
                        AiChatFailure.RESPONSE_TOO_LARGE,
                        "AI 服务响应超过 4 MiB，已停止读取。",
                    )
                }
                if (value == '\n'.code) {
                    consumeDataLine(
                        line.toByteArray().toString(StandardCharsets.UTF_8).trimEnd('\r'),
                    )
                    line.reset()
                } else {
                    line.write(value)
                }
            }
            if (!accumulator.done && line.size() > 0) {
                consumeDataLine(line.toByteArray().toString(StandardCharsets.UTF_8).trimEnd('\r'))
            }
        }
        return accumulator.requireResult()
    }

    private fun readResponseBody(
        connection: HttpURLConnection,
        status: Int,
        isActive: () -> Boolean,
    ): String {
        ensureRequestActive(isActive())
        val declaredLength = connection.contentLengthLong
        if (declaredLength > MAX_BODY_BYTES) {
            throw AiChatException(
                AiChatFailure.RESPONSE_TOO_LARGE,
                "AI 服务响应超过 4 MiB，已停止读取。",
            )
        }
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return readLimited(stream, isActive).toString(StandardCharsets.UTF_8)
    }

    private fun readLimited(stream: InputStream?, isActive: () -> Boolean): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                ensureRequestActive(isActive())
                val count = input.read(buffer)
                ensureRequestActive(isActive())
                if (count < 0) break
                total += count
                if (total > MAX_BODY_BYTES) {
                    throw AiChatException(
                        AiChatFailure.RESPONSE_TOO_LARGE,
                        "AI 服务响应超过 4 MiB，已停止读取。",
                    )
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun ensureRequestActive(isActive: Boolean) {
        if (!isActive) throw CancellationException("AI request cancelled")
    }

    private fun readImageBytes(uri: Uri): ByteArray {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (error: SecurityException) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "无法读取所选图片，请重新选择。", error)
        } catch (error: IOException) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "无法读取所选图片，请重新选择。", error)
        } ?: throw AiChatException(AiChatFailure.CONFIGURATION, "无法读取所选图片，请重新选择。")

        return try {
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) {
                        throw AiChatException(
                            AiChatFailure.CONFIGURATION,
                            "图片超过 8 MiB，请选择更小的图片。",
                        )
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (error: AiChatException) {
            throw error
        } catch (error: IOException) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "无法读取所选图片，请重新选择。", error)
        }
    }

    internal fun parseAssistantContent(responseBody: String, apiKey: String): AiChatCompletion {
        val root = try {
            JSONObject(responseBody)
        } catch (error: JSONException) {
            throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 服务返回了无法识别的数据。",
                error,
            )
        }
        root.optJSONObject("error")?.let { errorObject ->
            val message = sanitizeAiRemoteError(errorObject.optString("message"), apiKey)
            throw AiChatException(
                AiChatFailure.REMOTE,
                message.takeIf(String::isNotEmpty) ?: "AI 服务返回了错误。",
            )
        }

        val firstChoice = root.optJSONArray("choices")
            ?.optJSONObject(0)
            ?: throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 响应中没有可用的回答。",
            )
        val message = firstChoice.optJSONObject("message")
        val rawContent = extractAiContent(message?.opt("content"))
            ?: firstChoice.optString("text").takeIf(String::isNotBlank)
            ?: ""
        val tagged = splitAiThinkingContent(rawContent)
        val explicitReasoning = listOf("reasoning_content", "reasoning", "analysis")
            .mapNotNull { key -> extractAiContent(message?.opt(key))?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.trim()
            .orEmpty()
        val reasoning = listOf(explicitReasoning, tagged.reasoning)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n\n")
        if (tagged.content.isBlank() && reasoning.isBlank()) {
            throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 返回了空回答。",
            )
        }
        return AiChatCompletion(
            content = tagged.content.trim(),
            reasoning = reasoning,
        )
    }

    private fun parseRemoteError(responseBody: String): String? {
        if (responseBody.isBlank()) return null
        val message = runCatching {
            val root = JSONObject(responseBody)
            root.optJSONObject("error")?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: root.optString("message").takeIf(String::isNotBlank)
        }.getOrNull()
        return message
    }

    private fun parseAndValidateEndpoint(rawValue: String, allowInsecureHttp: Boolean): URL {
        val raw = rawValue.trim()
        if (raw.isEmpty()) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "请先配置 AI 接口地址。")
        }
        val url = try {
            URL(raw)
        } catch (error: MalformedURLException) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "AI 接口地址格式无效。", error)
        }
        validateHttpUrl(url, allowInsecureHttp)
        return url
    }

    private fun validateHttpUrl(url: URL, allowInsecureHttp: Boolean) {
        val scheme = url.protocol.lowercase()
        if (scheme != "https" && scheme != "http") {
            throw AiChatException(AiChatFailure.CONFIGURATION, "AI 接口地址必须使用 HTTPS 或 HTTP。")
        }
        if (scheme == "http" && !allowInsecureHttp) {
            throw AiChatException(
                AiChatFailure.CONFIGURATION,
                "当前 AI 接口使用不安全的 HTTP；请改用 HTTPS，或在设置中明确允许 HTTP。",
            )
        }
        if (url.host.isBlank() || !url.userInfo.isNullOrEmpty()) {
            throw AiChatException(AiChatFailure.CONFIGURATION, "AI 接口地址格式无效。")
        }
    }

    private fun validateRedirect(
        from: URL,
        candidate: URL,
        allowedHost: String,
        allowInsecureHttp: Boolean,
    ) {
        validateHttpUrl(candidate, allowInsecureHttp)
        if (!candidate.host.equals(allowedHost, ignoreCase = true)) {
            throw AiChatException(
                AiChatFailure.NETWORK,
                "为保护 API 密钥，已阻止 AI 请求重定向到其他主机。",
            )
        }
        if (from.protocol.equals("https", ignoreCase = true) &&
            candidate.protocol.equals("http", ignoreCase = true)
        ) {
            throw AiChatException(
                AiChatFailure.NETWORK,
                "为保护 API 密钥，已阻止 AI 请求降级到 HTTP。",
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_IMAGE_REQUEST_BODY_BYTES = 12 * 1024 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
        const val WRITE_BUFFER_BYTES = 8 * 1024
        const val MAX_REDIRECTS = 3
        const val MAX_TITLE_CHARS = 80
        const val MAX_AGENT_ATTACHMENTS = 5
        const val MAX_AGENT_TOOLS = 32
        const val MAX_AGENT_TOOL_CALLS_PER_RESPONSE = 16
        const val MAX_AGENT_TOOL_CALL_ID_CHARS = 200
        const val MAX_AGENT_ARGUMENT_BYTES = 64 * 1024
        const val MAX_REPORTED_TOKENS = 1_000_000_000_000L
        const val MAX_AGENT_SYSTEM_CHARS = 64 * 1024
        const val MAX_AGENT_TOOL_DESCRIPTION_CHARS = 4_096
        const val MAX_AGENT_TOOL_SCHEMA_CHARS = 32 * 1024
        val AGENT_TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

internal class AiStreamAccumulator(private val apiKey: String) {
    private val content = StringBuilder()
    private val explicitReasoning = StringBuilder()
    private var lastUpdate: AiChatCompletion? = null
    var done: Boolean = false
        private set

    fun consumePayload(payload: String): AiChatCompletion? {
        if (payload == "[DONE]") {
            done = true
            return null
        }
        val root = try {
            JSONObject(payload)
        } catch (error: JSONException) {
            throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 服务返回了无法识别的流式数据。",
                error,
            )
        }
        root.optJSONObject("error")?.let { errorObject ->
            val message = sanitizeAiRemoteError(errorObject.optString("message"), apiKey)
            throw AiChatException(
                AiChatFailure.REMOTE,
                message.takeIf(String::isNotEmpty) ?: "AI 服务返回了错误。",
            )
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        extractAiContent(delta?.opt("content"))?.let(content::append)
        listOf("reasoning_content", "reasoning", "analysis")
            .firstNotNullOfOrNull { key -> extractAiContent(delta?.opt(key)) }
            ?.let(explicitReasoning::append)
        val update = currentCompletion()
        return update.takeIf {
            it != lastUpdate && (it.content.isNotBlank() || it.reasoning.isNotBlank())
        }?.also { lastUpdate = it }
    }

    fun requireResult(): AiChatCompletion = currentCompletion().also { result ->
        if (result.content.isBlank() && result.reasoning.isBlank()) {
            throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 返回了空回答。",
            )
        }
    }

    private fun currentCompletion(): AiChatCompletion {
        val tagged = splitAiThinkingContent(content.toString())
        val reasoning = listOf(explicitReasoning.toString().trim(), tagged.reasoning)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n\n")
        return AiChatCompletion(tagged.content, reasoning)
    }
}

internal fun extractAiContent(value: Any?): String? = when (value) {
    is String -> value
    is JSONArray -> buildString {
        for (index in 0 until value.length()) {
            val part = value.optJSONObject(index) ?: continue
            val textValue = part.opt("text")
            val text = when (textValue) {
                is String -> textValue
                is JSONObject -> textValue.optString("value")
                else -> null
            }
            if (!text.isNullOrEmpty()) append(text)
        }
    }.takeIf(String::isNotEmpty)
    else -> null
}

internal fun sanitizeAiRemoteError(message: String, apiKey: String): String {
    var redacted = message.replace(
        Regex("(?i)Bearer\\s+[^\\s\"']+"),
        "Bearer [REDACTED]",
    )
    if (apiKey.isNotEmpty()) redacted = redacted.replace(apiKey, "[REDACTED]")
    return redacted
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(500)
}

internal fun generateConversationTitle(message: String, hasImage: Boolean): String {
    val normalized = message.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return if (hasImage) "🖼️" else "💬"
    val endIndex = normalized.offsetByCodePoints(
        0,
        normalized.codePointCount(0, normalized.length).coerceAtMost(40),
    )
    return normalized.substring(0, endIndex)
}

internal fun splitAiThinkingContent(raw: String): AiChatCompletion {
    val reasoningParts = mutableListOf<String>()
    val completeTag = Regex(
        pattern = "<think(?:\\s[^>]*)?>(.*?)</think\\s*>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    var answer = completeTag.replace(raw) { match ->
        match.groupValues[1].trim().takeIf(String::isNotEmpty)?.let(reasoningParts::add)
        ""
    }
    val openTag = Regex(
        pattern = "<think(?:\\s[^>]*)?>",
        options = setOf(RegexOption.IGNORE_CASE),
    )
    val unmatchedOpen = openTag.find(answer)
    if (unmatchedOpen != null) {
        answer.substring(unmatchedOpen.range.last + 1)
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(reasoningParts::add)
        answer = answer.substring(0, unmatchedOpen.range.first)
    }
    answer = answer.replace(Regex("</think\\s*>", RegexOption.IGNORE_CASE), "")
    return AiChatCompletion(
        content = answer.trim(),
        reasoning = reasoningParts.joinToString("\n\n"),
    )
}

private fun AiConversationEntity.toDomain() = AiConversation(
    id = id,
    title = title,
    modelConfigId = modelConfigId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AiMessageWithAttachments.toDomain(): AiChatMessage? {
    val entity = message
    val attachmentItems = attachments.mapNotNull { attachment ->
        val kind = AiAttachmentKind.entries.firstOrNull { it.name == attachment.kind }
            ?: return@mapNotNull null
        AiChatAttachment(
            uri = attachment.uri,
            mimeType = attachment.mimeType,
            displayName = attachment.displayName,
            sizeBytes = attachment.sizeBytes,
            kind = kind,
            extractedText = attachment.extractedText,
            permissionOwnedByChat = attachment.permissionOwned,
        )
    }.toMutableList().also { items ->
        // v5-v12 chat rows stored one image directly on the message. A remote sync cannot carry
        // its device-local URI, but retaining a placeholder keeps that historical attachment
        // visible and prevents a later Agent run from pretending it can inspect the image.
        if (entity.imageUri.isNullOrBlank() && !entity.imageMimeType.isNullOrBlank() &&
            items.none { it.kind == AiAttachmentKind.IMAGE }
        ) {
            items += AiChatAttachment(
                uri = "",
                mimeType = entity.imageMimeType,
                displayName = "image",
                sizeBytes = 0,
                kind = AiAttachmentKind.IMAGE,
            )
        }
    }
    return entity.toDomain(attachmentItems)
}

private fun AiMessageEntity.toDomain(
    attachmentItems: List<AiChatAttachment>,
): AiChatMessage? {
    val messageRole = AiChatRole.entries.firstOrNull { it.apiValue == role } ?: return null
    val messageImage = if (!imageUri.isNullOrBlank() && !imageMimeType.isNullOrBlank()) {
        AiChatImage(
            uri = imageUri,
            mimeType = imageMimeType,
            permissionOwnedByChat = imagePermissionOwned,
        )
    } else {
        null
    }
    return AiChatMessage(
        id = id,
        role = messageRole,
        content = content,
        reasoning = reasoning,
        image = messageImage ?: attachmentItems.firstOrNull { it.kind == AiAttachmentKind.IMAGE }
            ?.let { AiChatImage(it.uri, it.mimeType, it.permissionOwnedByChat) },
        attachments = attachmentItems,
        createdAt = createdAt,
    )
}

private data class PersistedImagePermission(
    val ownedByChat: Boolean,
    val newlyAcquired: Boolean,
)
