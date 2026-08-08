package com.deskcubby.plugin.api.core.api

interface AIAPI {
    suspend fun models(): List<AIModel>

    suspend fun complete(
        request: AICompletionRequest,
        onUpdate: ((AICompletion) -> Unit)? = null,
    ): AICompletion
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
