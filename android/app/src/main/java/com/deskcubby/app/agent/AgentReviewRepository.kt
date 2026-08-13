package com.deskcubby.app.agent

import com.deskcubby.app.data.local.AgentDao
import com.deskcubby.app.data.local.AgentMutationEntity
import com.deskcubby.app.data.local.AgentRunEntity
import com.deskcubby.app.data.local.AgentToolEventEntity
import com.deskcubby.plugin.api.core.api.AIToolCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentReviewRun(
    val runId: String,
    val conversationId: Long?,
    val conversationTitle: String,
    val userRequestSummary: String,
    val status: String,
    val usage: AgentRunUsage,
    val startedAt: Long,
    val completedAt: Long?,
)

data class AgentReviewMutation(
    val id: Long,
    val runId: String,
    val toolName: String,
    val target: String,
    val operation: String,
    val summary: String,
    val before: String,
    val after: String,
    val status: String,
    val createdAt: Long,
    val undoneAt: Long?,
) {
    val canUndo: Boolean get() = status == STATUS_APPLIED

    companion object {
        const val STATUS_APPLIED = "APPLIED"
    }
}

data class AgentReviewToolEvent(
    val id: Long,
    val toolName: String,
    val classification: String,
    val status: String,
    val target: String,
    val summary: String,
    val argumentsSummary: String,
    val resultSummary: String,
    val startedAt: Long,
    val completedAt: Long?,
)

@Singleton
class AgentReviewRepository @Inject constructor(
    private val dao: AgentDao,
    private val registry: AgentToolRegistry,
) : AgentReviewStore {
    private val undoMutex = Mutex()
    fun observeRuns(): Flow<List<AgentReviewRun>> = dao.observeRuns().map { runs ->
        runs.map(AgentRunEntity::toReviewRun)
    }

    fun observeMutations(runId: String): Flow<List<AgentReviewMutation>> =
        dao.observeMutations(runId).map { items -> items.map(AgentMutationEntity::toReviewMutation) }

    fun observeToolEvents(runId: String): Flow<List<AgentReviewToolEvent>> =
        dao.observeToolEvents(runId).map { items -> items.map(AgentToolEventEntity::toReviewEvent) }

    override suspend fun startRun(request: AgentRunRequest) = withContext(Dispatchers.IO) {
        dao.insertRun(
            AgentRunEntity(
                runId = request.runId,
                conversationId = request.conversationId,
                conversationTitle = request.conversationTitle.take(MAX_TITLE_CHARS),
                userRequestSummary = request.userRequest.replace(Regex("\\s+"), " ")
                    .trim()
                    .take(MAX_REQUEST_SUMMARY_CHARS),
                modelConfigId = request.modelConfigId.take(MAX_MODEL_CONFIG_ID_CHARS),
                permissionMode = request.permissionMode.name,
                enabledSourcesJson = request.allowedSources.sorted().joinToString(
                    prefix = "[",
                    separator = ",",
                    postfix = "]",
                    transform = { "\"${jsonEscape(it)}\"" },
                ).take(MAX_SOURCES_JSON_CHARS),
                status = "RUNNING",
                startedAt = System.currentTimeMillis(),
                completedAt = null,
            ),
        )
    }

    override suspend fun finishRun(
        runId: String,
        status: String,
        usage: AgentRunUsage,
    ) = withContext(Dispatchers.IO) {
        dao.finishRun(
            runId = runId,
            status = status.take(32),
            modelCallCount = usage.modelCallCount,
            usageReportedCallCount = usage.reportedCallCount,
            inputTokens = usage.inputTokens.takeIf { usage.inputTokensReported },
            outputTokens = usage.outputTokens.takeIf { usage.outputTokensReported },
            totalTokens = usage.totalTokens.takeIf { usage.totalTokensReported },
            cachedInputTokens = usage.cachedInputTokens.takeIf { usage.cachedInputTokensReported },
            cacheRateInputTokens = usage.cacheRateInputTokens.takeIf { usage.cacheRateInputTokensReported },
            reasoningTokens = usage.reasoningTokens.takeIf { usage.reasoningTokensReported },
            completedAt = System.currentTimeMillis(),
        )
        Unit
    }

    override suspend fun startToolEvent(
        runId: String,
        sequence: Int,
        call: AIToolCall,
        definition: AgentToolDefinition?,
    ): Long = withContext(Dispatchers.IO) {
        dao.insertToolEvent(
            AgentToolEventEntity(
                runId = runId,
                sequence = sequence,
                toolCallId = call.id.take(200),
                toolName = call.name.take(64),
                classification = definition?.classification?.name ?: "UNKNOWN",
                status = AgentExecutionStatus.PREPARING.name,
                target = "",
                summary = "",
                argumentsSummary = summarizeArguments(call.arguments),
                resultSummary = "",
                errorCode = null,
                startedAt = System.currentTimeMillis(),
                completedAt = null,
            ),
        )
    }

    override suspend fun finishToolEvent(
        eventId: Long,
        status: AgentExecutionStatus,
        target: String,
        summary: String,
        resultSummary: String,
        errorCode: String?,
    ) = withContext(Dispatchers.IO) {
        dao.finishToolEvent(
            id = eventId,
            status = status.name,
            target = target.take(MAX_TARGET_CHARS),
            summary = summary.take(MAX_SUMMARY_CHARS),
            resultSummary = resultSummary.take(MAX_RESULT_SUMMARY_CHARS),
            errorCode = errorCode?.take(80),
            completedAt = System.currentTimeMillis(),
        )
        Unit
    }

    override suspend fun beginMutation(
        runId: String,
        eventId: Long,
        tool: AgentTool,
        preparation: AgentToolPreparation,
    ): Long = withContext(Dispatchers.IO) {
        require(preparation.before.length <= MAX_REVIEW_CONTENT_CHARS)
        require(preparation.after.length <= MAX_REVIEW_CONTENT_CHARS)
        dao.insertMutation(
            AgentMutationEntity(
                runId = runId,
                toolEventId = eventId,
                toolName = tool.definition.name,
                target = preparation.target.take(MAX_TARGET_CHARS),
                operation = mutationOperation(tool.definition.name),
                summary = preparation.summary.take(MAX_SUMMARY_CHARS),
                beforeContent = preparation.before,
                afterContent = preparation.after,
                undoPayload = preparation.executionToken.take(MAX_UNDO_PAYLOAD_CHARS),
                status = "PENDING",
                createdAt = System.currentTimeMillis(),
                undoneAt = null,
            ),
        )
    }

    override suspend fun completeMutation(
        mutationId: Long,
        outcome: AgentToolOutcome,
    ) = withContext(Dispatchers.IO) {
        require(outcome.before.length <= MAX_REVIEW_CONTENT_CHARS)
        require(outcome.after.length <= MAX_REVIEW_CONTENT_CHARS)
        val undo = outcome.undoToken.orEmpty()
        require(undo.length <= MAX_UNDO_PAYLOAD_CHARS)
        check(
            dao.completeMutation(
                id = mutationId,
                beforeContent = outcome.before,
                afterContent = outcome.after,
                undoPayload = undo,
                status = AgentReviewMutation.STATUS_APPLIED,
            ) > 0,
        ) { "Agent mutation review record disappeared" }
    }

    override suspend fun failMutation(mutationId: Long) = withContext(Dispatchers.IO) {
        dao.failPendingMutation(mutationId, "FAILED")
        Unit
    }

    suspend fun undo(mutationId: Long): AgentToolOutcome = withContext(Dispatchers.IO) {
        undoMutex.withLock {
            val mutation = dao.getMutation(mutationId)
                ?: throw AgentToolException("REVIEW_NOT_FOUND", "The Review item no longer exists.")
            if (mutation.status != AgentReviewMutation.STATUS_APPLIED || mutation.undoPayload.isBlank()) {
                throw AgentToolException("UNDO_UNAVAILABLE", "This Review item can no longer be undone.")
            }
            val tool = registry.find(mutation.toolName)
                ?: throw AgentToolException("UNDO_TOOL_UNAVAILABLE", "The tool needed for Undo is unavailable.")
            val outcome = tool.undo(mutation.undoPayload)
            check(dao.markMutationUndone(mutation.id, "UNDONE", System.currentTimeMillis()) > 0) {
                "Review item changed while Undo was running"
            }
            outcome
        }
    }

    private fun summarizeArguments(arguments: Map<String, Any?>): String = arguments.entries
        .sortedBy(Map.Entry<String, Any?>::key)
        .joinToString(", ") { (key, value) ->
            val safeValue = if (SECRET_ARGUMENT_KEY.containsMatchIn(key)) {
                "<redacted>"
            } else when (value) {
                null -> "null"
                is Collection<*> -> "[${value.size} items]"
                is Map<*, *> -> "{${value.size} fields}"
                else -> value.toString().replace(Regex("\\s+"), " ").take(160)
            }
            "$key=$safeValue"
        }
        .take(MAX_ARGUMENT_SUMMARY_CHARS)

    private fun mutationOperation(toolName: String): String = when {
        toolName.startsWith("create_") -> "CREATE"
        toolName.startsWith("delete_") -> "DELETE"
        else -> "UPDATE"
    }

    private companion object {
        const val MAX_TITLE_CHARS = 160
        const val MAX_REQUEST_SUMMARY_CHARS = 500
        const val MAX_MODEL_CONFIG_ID_CHARS = 80
        const val MAX_SOURCES_JSON_CHARS = 2_048
        const val MAX_TARGET_CHARS = 1_024
        const val MAX_SUMMARY_CHARS = 4_096
        const val MAX_RESULT_SUMMARY_CHARS = 8_192
        const val MAX_ARGUMENT_SUMMARY_CHARS = 8_192
        const val MAX_REVIEW_CONTENT_CHARS = 256 * 1024
        const val MAX_UNDO_PAYLOAD_CHARS = 512 * 1024
        val SECRET_ARGUMENT_KEY = Regex(
            "(?i)(api.?key|password|passwd|authorization|credential|secret|session.?token|access.?token|keystore)",
        )
    }
}

private fun AgentRunEntity.toReviewRun() = AgentReviewRun(
    runId,
    conversationId,
    conversationTitle,
    userRequestSummary,
    status,
    AgentRunUsage(
        modelCallCount,
        usageReportedCallCount,
        inputTokens ?: 0,
        outputTokens ?: 0,
        totalTokens ?: 0,
        cachedInputTokens ?: 0,
        cacheRateInputTokens ?: 0,
        reasoningTokens ?: 0,
        inputTokens != null,
        outputTokens != null,
        totalTokens != null,
        cachedInputTokens != null,
        cacheRateInputTokens != null,
        reasoningTokens != null,
    ),
    startedAt,
    completedAt,
)

private fun AgentMutationEntity.toReviewMutation() = AgentReviewMutation(
    id,
    runId,
    toolName,
    target,
    operation,
    summary,
    beforeContent,
    afterContent,
    status,
    createdAt,
    undoneAt,
)

private fun AgentToolEventEntity.toReviewEvent() = AgentReviewToolEvent(
    id,
    toolName,
    classification,
    status,
    target,
    summary,
    argumentsSummary,
    resultSummary,
    startedAt,
    completedAt,
)
