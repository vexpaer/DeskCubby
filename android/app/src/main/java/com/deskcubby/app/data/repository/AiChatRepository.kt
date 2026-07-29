package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.deskcubby.app.data.local.AiChatDao
import com.deskcubby.app.data.local.AiConversationEntity
import com.deskcubby.app.data.local.AiMessageEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
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
) {
    private val imagePermissionMutex = Mutex()

    fun observeConversations(): Flow<List<AiConversation>> =
        aiChatDao.observeConversations().map { items -> items.map(AiConversationEntity::toDomain) }

    fun observeMessages(conversationId: Long): Flow<List<AiChatMessage>> =
        aiChatDao.observeMessages(conversationId).map { items -> items.mapNotNull(AiMessageEntity::toDomain) }

    suspend fun getConversation(id: Long): AiConversation? = withContext(Dispatchers.IO) {
        aiChatDao.getConversation(id)?.toDomain()
    }

    suspend fun getMessages(conversationId: Long): List<AiChatMessage> = withContext(Dispatchers.IO) {
        aiChatDao.getMessages(conversationId).mapNotNull(AiMessageEntity::toDomain)
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
                ),
            )
        }
    }

    /**
     * Persists the optional frozen context and its user message as one logical turn.
     *
     * Persistable image permission is acquired before entering Room's transaction. If the
     * transaction fails, a permission newly acquired for this turn is released and neither
     * message remains in the conversation.
     */
    suspend fun appendUserTurn(
        conversationId: Long,
        frozenContext: String?,
        content: String,
        image: AiChatImage? = null,
        now: Long = System.currentTimeMillis(),
    ): Long = withContext(Dispatchers.IO) {
        withPersistedImagePermission(image) { imagePermission ->
            val messages = buildList {
                frozenContext?.takeIf(String::isNotBlank)?.let { encoded ->
                    add(
                        AiMessageEntity(
                            conversationId = conversationId,
                            role = AiChatRole.CONTEXT.apiValue,
                            content = encoded,
                            reasoning = "",
                            imageUri = null,
                            imageMimeType = null,
                            imagePermissionOwned = false,
                            createdAt = now,
                        ),
                    )
                }
                add(
                    AiMessageEntity(
                        conversationId = conversationId,
                        role = AiChatRole.USER.apiValue,
                        content = content,
                        reasoning = "",
                        imageUri = image?.uri,
                        imageMimeType = image?.mimeType,
                        imagePermissionOwned = imagePermission?.ownedByChat ?: false,
                        createdAt = now,
                    ),
                )
            }
            aiChatDao.insertUserTurnAndTouch(messages).last()
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
                if (aiChatDao.countImageReferences(uri) == 0) releaseImagePermission(uri)
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
        val requestBody = buildTextChatRequestJson(
            model = model,
            temperature = config?.temperature ?: settings.aiTemperature,
            systemPrompt = config?.systemPrompt?.takeIf(String::isNotBlank)
                ?: settings.aiSystemPrompt.takeIf(String::isNotBlank),
            messages = messages,
            imageDataUrls = imageDataUrls,
        )
            .toString()
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
            val response = executeRequest(
                initialUrl = endpoint,
                body = requestBody,
                apiKey = config?.apiKey?.trim().orEmpty(),
                allowInsecureHttp = config?.allowInsecureHttp ?: settings.aiAllowInsecureHttp,
            )
            parseAssistantContent(response, config?.apiKey?.trim().orEmpty())
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
        val rawContent = extractContent(message?.opt("content"))
            ?: firstChoice.optString("text").takeIf(String::isNotBlank)
            ?: ""
        val tagged = splitAiThinkingContent(rawContent)
        val explicitReasoning = listOf("reasoning_content", "reasoning", "analysis")
            .mapNotNull { key -> extractContent(message?.opt(key))?.takeIf(String::isNotBlank) }
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

    private fun extractContent(value: Any?): String? = when (value) {
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
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
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

private fun AiMessageEntity.toDomain(): AiChatMessage? {
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
        image = messageImage,
        createdAt = createdAt,
    )
}

private data class PersistedImagePermission(
    val ownedByChat: Boolean,
    val newlyAcquired: Boolean,
)
