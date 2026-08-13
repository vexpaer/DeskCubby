package com.deskcubby.app.agent

import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.plugin.api.core.api.AIImage
import com.deskcubby.plugin.api.core.api.AIToolCall
import com.deskcubby.plugin.api.core.api.AITokenUsage

enum class AgentToolClassification {
    READ_ONLY,
    MUTATION,
}

enum class AgentExecutionStatus {
    PREPARING,
    RUNNING,
    WAITING_APPROVAL,
    APPROVED,
    REJECTED,
    SUCCEEDED,
    FAILED,
    CANCELED,
}

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
    val classification: AgentToolClassification,
)

data class AgentRunScope(
    val runId: String,
    val allowedSources: Set<String>,
    val permissionMode: AgentPermissionMode,
    val english: Boolean,
) {
    fun requireSource(sourceId: String) {
        if (sourceId !in allowedSources) {
            throw AgentToolException(
                code = "SOURCE_NOT_AUTHORIZED",
                message = "The data source '$sourceId' is not authorized for this Agent run.",
            )
        }
    }
}

data class AgentToolPreparation(
    val call: AIToolCall,
    val target: String,
    val summary: String,
    val argumentsSummary: String,
    val before: String = "",
    val after: String = "",
    val executionToken: String = "",
)

data class AgentToolOutcome(
    val content: String,
    val summary: String,
    val target: String = "",
    val before: String = "",
    val after: String = "",
    val undoToken: String? = null,
)

data class AgentToolResult(
    val callId: String,
    val toolName: String,
    val success: Boolean,
    val rejected: Boolean = false,
    val content: String,
    val summary: String,
    val target: String = "",
    val errorCode: String? = null,
)

data class AgentApprovalRequest(
    val requestId: String,
    val runId: String,
    val toolCallId: String,
    val toolName: String,
    val target: String,
    val summary: String,
    val before: String,
    val after: String,
)

enum class AgentApprovalDecision {
    APPROVE,
    REJECT,
}

data class AgentExecutionUpdate(
    val toolCallId: String,
    val toolName: String,
    val status: AgentExecutionStatus,
    val title: String,
    val target: String = "",
    val argumentsSummary: String = "",
    val resultSummary: String = "",
)

enum class AgentConversationRole {
    USER,
    ASSISTANT,
    UNTRUSTED_CONTEXT,
}

data class AgentConversationMessage(
    val role: AgentConversationRole,
    val content: String,
    val images: List<AIImage> = emptyList(),
)

data class AgentRunRequest(
    val runId: String,
    val conversationId: Long,
    val conversationTitle: String,
    val userRequest: String,
    val modelConfigId: String,
    val customModelInstructions: String,
    val allowedSources: Set<String>,
    val permissionMode: AgentPermissionMode,
    val english: Boolean,
    val messages: List<AgentConversationMessage>,
)

data class AgentRunResult(
    val content: String,
    val reasoning: String = "",
    val usage: AgentRunUsage = AgentRunUsage(),
)

data class AgentRunUsage(
    val modelCallCount: Int = 0,
    val reportedCallCount: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheRateInputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val inputTokensReported: Boolean = inputTokens > 0,
    val outputTokensReported: Boolean = outputTokens > 0,
    val totalTokensReported: Boolean = totalTokens > 0,
    val cachedInputTokensReported: Boolean = cachedInputTokens > 0,
    val cacheRateInputTokensReported: Boolean = cacheRateInputTokens > 0,
    val reasoningTokensReported: Boolean = reasoningTokens > 0,
) {
    val cacheRate: Double?
        get() = if (cacheRateInputTokensReported && cachedInputTokensReported && cacheRateInputTokens > 0) {
            cachedInputTokens.toDouble() / cacheRateInputTokens
        } else {
            null
        }

    fun plus(value: AITokenUsage): AgentRunUsage = copy(
        modelCallCount = modelCallCount + 1,
        reportedCallCount = reportedCallCount + if (value.reported) 1 else 0,
        inputTokens = inputTokens.saturatedPlus(value.inputTokens ?: 0),
        outputTokens = outputTokens.saturatedPlus(value.outputTokens ?: 0),
        totalTokens = totalTokens.saturatedPlus(
            value.totalTokens ?: (value.inputTokens ?: 0).saturatedPlus(value.outputTokens ?: 0),
        ),
        // Cached tokens are only statistically meaningful when the provider also reports the
        // corresponding input total for the same model call.
        cachedInputTokens = cachedInputTokens.saturatedPlus(
            if (value.inputTokens != null) value.cachedInputTokens ?: 0 else 0,
        ),
        cacheRateInputTokens = cacheRateInputTokens.saturatedPlus(
            if (value.cachedInputTokens != null) value.inputTokens ?: 0 else 0,
        ),
        reasoningTokens = reasoningTokens.saturatedPlus(value.reasoningTokens ?: 0),
        inputTokensReported = inputTokensReported || value.inputTokens != null,
        outputTokensReported = outputTokensReported || value.outputTokens != null,
        totalTokensReported = totalTokensReported || value.totalTokens != null ||
            (value.inputTokens != null && value.outputTokens != null),
        cachedInputTokensReported = cachedInputTokensReported ||
            (value.cachedInputTokens != null && value.inputTokens != null),
        cacheRateInputTokensReported = cacheRateInputTokensReported ||
            (value.cachedInputTokens != null && value.inputTokens != null),
        reasoningTokensReported = reasoningTokensReported || value.reasoningTokens != null,
    )
}

private fun Long.saturatedPlus(other: Long): Long =
    if (other > 0 && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

open class AgentRuntimeException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class AgentToolException(
    code: String,
    message: String,
    cause: Throwable? = null,
) : AgentRuntimeException(code, message, cause)

class AgentLoopLimitException : AgentRuntimeException(
    code = "AGENT_LOOP_LIMIT",
    message = "Agent stopped after reaching the maximum number of tool-call rounds.",
)

class AgentInvalidToolCallException(message: String) : AgentRuntimeException(
    code = "INVALID_TOOL_CALL",
    message = message,
)
