package com.deskcubby.plugin.api.core.api

interface AIAPI {
    suspend fun models(): List<AIModel>

    suspend fun complete(
        request: AICompletionRequest,
        onUpdate: ((AICompletion) -> Unit)? = null,
    ): AICompletion

    /** Native provider tool calling. Hosts must never emulate this by parsing assistant prose. */
    suspend fun completeWithTools(request: AIToolCompletionRequest): AIToolCompletion
}

enum class AIModelType {
    TEXT,
    IMAGE,
}

data class AIModel(
    val id: String,
    val name: String,
    val type: AIModelType,
    val model: String,
    val enabled: Boolean,
    val supportsToolCalling: Boolean = false,
)

enum class AIMessageRole {
    USER,
    ASSISTANT,
    CONTEXT,
}

data class AIImage(
    val contentUri: String,
    val mimeType: String,
)

data class AIMessage(
    val role: AIMessageRole,
    val content: String,
    val image: AIImage? = null,
)

data class AICompletionRequest(
    val messages: List<AIMessage>,
    val modelConfigurationId: String? = null,
)

data class AICompletion(
    val content: String,
    val reasoning: String = "",
)

enum class AIAgentMessageRole {
    USER,
    ASSISTANT,
    TOOL,
}

data class AIAgentMessage(
    val role: AIAgentMessageRole,
    val content: String = "",
    val images: List<AIImage> = emptyList(),
    val toolCalls: List<AIToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
)

data class AIToolDefinition(
    val name: String,
    val description: String,
    /** A JSON Schema object encoded as JSON. */
    val parametersJson: String,
)

data class AIToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>,
)

data class AIToolCompletionRequest(
    val systemPrompt: String,
    val messages: List<AIAgentMessage>,
    val tools: List<AIToolDefinition>,
    val modelConfigurationId: String? = null,
)

data class AIToolCompletion(
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<AIToolCall> = emptyList(),
    /** Provider-reported usage only. Null values mean that metric was not reported. */
    val usage: AITokenUsage = AITokenUsage(),
)

data class AITokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val reasoningTokens: Long? = null,
) {
    val reported: Boolean
        get() = inputTokens != null || outputTokens != null || totalTokens != null ||
            cachedInputTokens != null || reasoningTokens != null
}
