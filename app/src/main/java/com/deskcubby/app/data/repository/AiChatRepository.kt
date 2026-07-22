package com.deskcubby.app.data.repository

import android.util.Base64
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class AiChatRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

data class AiChatMessage(
    val id: Long,
    val role: AiChatRole,
    val content: String,
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
class AiChatRepository @Inject constructor() {
    suspend fun complete(
        settings: AppSettings,
        messages: List<AiChatMessage>,
    ): String = withContext(Dispatchers.IO) {
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
        val requestBody = buildTextChatRequestJson(
            model = model,
            temperature = config?.temperature ?: settings.aiTemperature,
            systemPrompt = config?.systemPrompt?.takeIf(String::isNotBlank)
                ?: settings.aiSystemPrompt.takeIf(String::isNotBlank),
            messages = messages,
        )
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        if (requestBody.size > MAX_BODY_BYTES) {
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
            parseAssistantContent(response)
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
        parseAssistantContent(response)
    }

    private fun executeRequest(
        initialUrl: URL,
        body: ByteArray,
        apiKey: String,
        allowInsecureHttp: Boolean,
    ): String {
        val allowedHost = initialUrl.host
        var currentUrl = initialUrl
        var redirects = 0

        while (true) {
            val connection = (currentUrl.openConnection() as? HttpURLConnection)
                ?: throw AiChatException(AiChatFailure.CONFIGURATION, "AI 接口地址不是 HTTP 地址。")
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
                connection.outputStream.use { output -> output.write(body) }

                val status = connection.responseCode
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
                    val responseBody = readResponseBody(connection, status)
                    if (status !in 200..299) {
                        val remoteMessage = parseRemoteError(responseBody)
                        val suffix = remoteMessage?.let { "：$it" }.orEmpty()
                        throw AiChatException(
                            AiChatFailure.REMOTE,
                            "AI 服务返回 HTTP $status$suffix",
                        )
                    }
                    return responseBody
                }
            } finally {
                connection.disconnect()
            }

            currentUrl = checkNotNull(redirectUrl)
            redirects += 1
        }
    }

    private fun readResponseBody(connection: HttpURLConnection, status: Int): String {
        val declaredLength = connection.contentLengthLong
        if (declaredLength > MAX_BODY_BYTES) {
            throw AiChatException(
                AiChatFailure.RESPONSE_TOO_LARGE,
                "AI 服务响应超过 4 MiB，已停止读取。",
            )
        }
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        return readLimited(stream).toString(StandardCharsets.UTF_8)
    }

    private fun readLimited(stream: InputStream?): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
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

    private fun parseAssistantContent(responseBody: String): String {
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
            val message = errorObject.optString("message").trim()
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
        val content = extractContent(message?.opt("content"))
            ?: firstChoice.optString("text").takeIf(String::isNotBlank)
        return content?.trim()?.takeIf(String::isNotEmpty)
            ?: throw AiChatException(
                AiChatFailure.INVALID_RESPONSE,
                "AI 返回了空回答。",
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
        return message?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_ERROR_MESSAGE_CHARS)
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
        const val MAX_IMAGE_REQUEST_BODY_BYTES = 12 * 1024 * 1024
        const val READ_BUFFER_BYTES = 8 * 1024
        const val MAX_REDIRECTS = 3
        const val MAX_ERROR_MESSAGE_CHARS = 500
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
