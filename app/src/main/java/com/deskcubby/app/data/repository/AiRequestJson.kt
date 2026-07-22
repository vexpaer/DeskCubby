package com.deskcubby.app.data.repository

import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import org.json.JSONArray
import org.json.JSONObject

internal fun buildTextChatRequestJson(
    model: String,
    temperature: Float,
    systemPrompt: String?,
    messages: List<AiChatMessage>,
): JSONObject {
    val requestMessages = JSONArray()
    systemPrompt?.takeIf(String::isNotBlank)?.let { prompt ->
        requestMessages.put(
            JSONObject()
                .put("role", "system")
                .put("content", prompt),
        )
    }
    messages.forEach { message ->
        requestMessages.put(
            JSONObject()
                .put("role", message.role.apiValue)
                .put("content", message.content),
        )
    }
    return JSONObject()
        .put("model", model)
        .put("messages", requestMessages)
        .put("temperature", normalizeAiTemperature(temperature).toDouble())
        .put("stream", false)
}

internal fun buildImageChatRequestJson(
    model: String,
    temperature: Float,
    prompt: String,
    imageDataUrl: String,
): JSONObject {
    val content = JSONArray()
        .put(JSONObject().put("type", "text").put("text", prompt))
        .put(
            JSONObject()
                .put("type", "image_url")
                .put("image_url", JSONObject().put("url", imageDataUrl)),
        )
    return JSONObject()
        .put("model", model)
        .put("stream", false)
        .put("temperature", normalizeAiTemperature(temperature).toDouble())
        .put(
            "messages",
            JSONArray().put(JSONObject().put("role", "user").put("content", content)),
        )
}

/** Builds the same JSON shape used by real requests, replacing runtime content with labels. */
internal fun buildAiRequestPreviewJson(config: AiModelConfig): String {
    val json = when (config.type) {
        AiModelType.TEXT -> buildTextChatRequestJson(
            model = config.model,
            temperature = config.temperature,
            systemPrompt = config.systemPrompt,
            messages = listOf(AiChatMessage(0L, AiChatRole.USER, "<USER_MESSAGE>")),
        )

        AiModelType.IMAGE -> buildImageChatRequestJson(
            model = config.model,
            temperature = config.temperature,
            prompt = "<IMAGE_PROMPT>",
            imageDataUrl = "data:image/jpeg;base64,<IMAGE_BASE64>",
        )
    }
    return json.toString(2)
}

private fun normalizeAiTemperature(value: Float): Float = value
    .takeIf(Float::isFinite)
    ?.coerceIn(MIN_AI_TEMPERATURE, MAX_AI_TEMPERATURE)
    ?: DEFAULT_AI_TEMPERATURE

private const val MIN_AI_TEMPERATURE = 0f
private const val MAX_AI_TEMPERATURE = 2f
private const val DEFAULT_AI_TEMPERATURE = 0.7f
