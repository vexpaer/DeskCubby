package com.deskcubby.app.agent

import com.deskcubby.plugin.api.core.api.AIAgentMessage
import com.deskcubby.plugin.api.core.api.AIToolCall
import com.deskcubby.plugin.api.core.api.AIToolCompletion

interface AgentTool {
    val definition: AgentToolDefinition

    suspend fun prepare(call: AIToolCall, scope: AgentRunScope): AgentToolPreparation

    suspend fun execute(preparation: AgentToolPreparation, scope: AgentRunScope): AgentToolOutcome

    suspend fun undo(undoToken: String): AgentToolOutcome =
        throw AgentToolException("UNDO_UNAVAILABLE", "This tool result cannot be undone.")
}

fun interface AgentToolContributor {
    fun tools(): List<AgentTool>
}

data class AgentModelRequest(
    val systemPrompt: String,
    val messages: List<AIAgentMessage>,
    val tools: List<AgentToolDefinition>,
    val modelConfigId: String,
)

fun interface AgentModelClient {
    suspend fun complete(request: AgentModelRequest): AIToolCompletion
}

fun interface AgentContextProvider {
    suspend fun metadataPrompt(allowedSources: Set<String>, english: Boolean): String
}

interface AgentApprovalGateway {
    suspend fun authorize(
        mode: com.deskcubby.app.data.model.AgentPermissionMode,
        request: AgentApprovalRequest,
    ): AgentApprovalDecision
}

interface AgentReviewStore {
    suspend fun startRun(request: AgentRunRequest)

    suspend fun finishRun(runId: String, status: String, usage: AgentRunUsage)

    suspend fun startToolEvent(
        runId: String,
        sequence: Int,
        call: AIToolCall,
        definition: AgentToolDefinition?,
    ): Long

    suspend fun finishToolEvent(
        eventId: Long,
        status: AgentExecutionStatus,
        target: String,
        summary: String,
        resultSummary: String,
        errorCode: String? = null,
    )

    suspend fun beginMutation(
        runId: String,
        eventId: Long,
        tool: AgentTool,
        preparation: AgentToolPreparation,
    ): Long

    suspend fun completeMutation(
        mutationId: Long,
        outcome: AgentToolOutcome,
    )

    suspend fun failMutation(mutationId: Long)
}

fun interface AgentToolExecutionGateway {
    suspend fun execute(
        runId: String,
        sequence: Int,
        call: AIToolCall,
        scope: AgentRunScope,
        onUpdate: (AgentExecutionUpdate) -> Unit,
    ): AgentToolResult
}
