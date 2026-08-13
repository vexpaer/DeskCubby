package com.deskcubby.app.data.repository

import com.deskcubby.plugin.api.core.api.AIAgentMessageRole
import com.deskcubby.plugin.api.core.api.AIToolCompletionRequest
import org.json.JSONArray
import org.json.JSONObject

internal fun buildAgentRequestJson(
    model: String,
    temperature: Float,
    request: AIToolCompletionRequest,
    imageDataUrls: Map<Pair<Int, Int>, String>,
): JSONObject {
    val messages = JSONArray().put(
        JSONObject()
            .put("role", "system")
            .put("content", request.systemPrompt),
    )
    request.messages.forEachIndexed { messageIndex, message ->
        val json = JSONObject()
        when (message.role) {
            AIAgentMessageRole.USER -> {
                json.put("role", "user")
                val images = message.images.mapIndexedNotNull { imageIndex, _ ->
                    imageDataUrls[messageIndex to imageIndex]
                }
                if (images.isEmpty()) {
                    json.put("content", message.content)
                } else {
                    json.put(
                        "content",
                        JSONArray().apply {
                            if (message.content.isNotBlank()) {
                                put(JSONObject().put("type", "text").put("text", message.content))
                            }
                            images.forEach { dataUrl ->
                                put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put("image_url", JSONObject().put("url", dataUrl)),
                                )
                            }
                        },
                    )
                }
            }

            AIAgentMessageRole.ASSISTANT -> {
                json.put("role", "assistant")
                json.put("content", message.content.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                if (message.toolCalls.isNotEmpty()) {
                    json.put(
                        "tool_calls",
                        JSONArray().apply {
                            message.toolCalls.forEach { call ->
                                put(
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.name)
                                                .put("arguments", JSONObject(call.arguments).toString()),
                                        ),
                                )
                            }
                        },
                    )
                }
            }

            AIAgentMessageRole.TOOL -> {
                json.put("role", "tool")
                json.put("tool_call_id", requireNotNull(message.toolCallId))
                message.toolName?.let { json.put("name", it) }
                json.put("content", message.content)
            }
        }
        messages.put(json)
    }
    val tools = JSONArray().apply {
        request.tools.forEach { tool ->
            put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", JSONObject(tool.parametersJson)),
                    ),
            )
        }
    }
    return JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("tools", tools)
        .put("tool_choice", "auto")
        .put("temperature", temperature.takeIf(Float::isFinite)?.coerceIn(0f, 2f) ?: 0.7f)
        .put("stream", false)
}

internal fun JSONObject.toStrictMap(): Map<String, Any?> = keys().asSequence().associateWith { key ->
    when (val value = get(key)) {
        JSONObject.NULL -> null
        is JSONObject -> value.toStrictMap()
        is JSONArray -> value.toStrictList()
        is String, is Boolean, is Number -> value
        else -> value.toString()
    }
}

private fun JSONArray.toStrictList(): List<Any?> = buildList(length()) {
    for (index in 0 until length()) {
        add(
            when (val value = get(index)) {
                JSONObject.NULL -> null
                is JSONObject -> value.toStrictMap()
                is JSONArray -> value.toStrictList()
                is String, is Boolean, is Number -> value
                else -> value.toString()
            },
        )
    }
}
