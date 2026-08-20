package com.deskcubby.app.agent

import com.deskcubby.app.ui.theme.translate
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.plugin.api.core.api.AIToolCall
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class AgentToolExecutor @Inject constructor(
    private val registry: AgentToolRegistry,
    private val approvalGateway: AgentApprovalGateway,
    private val reviewStore: AgentReviewStore,
) : AgentToolExecutionGateway {
    override suspend fun execute(
        runId: String,
        sequence: Int,
        call: AIToolCall,
        scope: AgentRunScope,
        onUpdate: (AgentExecutionUpdate) -> Unit,
    ): AgentToolResult {
        val tool = registry.find(call.name)
        val eventId = reviewStore.startToolEvent(
            runId = runId,
            sequence = sequence,
            call = call,
            definition = tool?.definition,
        )
        if (tool == null) {
            val message = "Unknown Agent tool: ${call.name}"
            reviewStore.finishToolEvent(
                eventId,
                AgentExecutionStatus.FAILED,
                "",
                message,
                message,
                "UNKNOWN_TOOL",
            )
            return failure(call, "UNKNOWN_TOOL", message)
        }
        onUpdate(
            AgentExecutionUpdate(
                call.id,
                call.name,
                AgentExecutionStatus.PREPARING,
                titleFor(tool.definition.name, scope.english),
            ),
        )
        var preparation: AgentToolPreparation? = null
        var mutationId: Long? = null
        try {
            preparation = tool.prepare(call, scope)
            if (tool.definition.classification == AgentToolClassification.MUTATION) {
                if (scope.permissionMode == AgentPermissionMode.REQUIRE_APPROVAL) {
                    onUpdate(
                        AgentExecutionUpdate(
                            call.id,
                            call.name,
                            AgentExecutionStatus.WAITING_APPROVAL,
                            if (scope.english) "Waiting for approval" else "等待批准",
                            preparation.target,
                            preparation.argumentsSummary,
                        ),
                    )
                }
                val decision = approvalGateway.authorize(
                    scope.permissionMode,
                    AgentApprovalRequest(
                        requestId = UUID.randomUUID().toString(),
                        runId = runId,
                        toolCallId = call.id,
                        toolName = call.name,
                        target = preparation.target,
                        summary = preparation.summary,
                        before = preparation.before,
                        after = preparation.after,
                        argumentsSummary = preparation.argumentsSummary,
                    ),
                )
                if (decision == AgentApprovalDecision.REJECT) {
                    val summary = if (scope.english) {
                        "The user rejected this change."
                    } else {
                        "用户拒绝了这项修改。"
                    }
                    reviewStore.finishToolEvent(
                        eventId,
                        AgentExecutionStatus.REJECTED,
                        preparation.target,
                        preparation.summary,
                        summary,
                        "USER_REJECTED",
                    )
                    onUpdate(
                        AgentExecutionUpdate(
                            call.id,
                            call.name,
                            AgentExecutionStatus.REJECTED,
                            summary,
                            preparation.target,
                            preparation.argumentsSummary,
                            summary,
                        ),
                    )
                    return AgentToolResult(
                        callId = call.id,
                        toolName = call.name,
                        success = false,
                        rejected = true,
                        content = errorContent("USER_REJECTED", summary),
                        summary = summary,
                        target = preparation.target,
                        errorCode = "USER_REJECTED",
                    )
                }
            }
            onUpdate(
                AgentExecutionUpdate(
                    call.id,
                    call.name,
                    AgentExecutionStatus.RUNNING,
                    titleFor(tool.definition.name, scope.english),
                    preparation.target,
                    preparation.argumentsSummary,
                ),
            )
            mutationId = if (tool.definition.classification == AgentToolClassification.MUTATION) {
                reviewStore.beginMutation(runId, eventId, tool, preparation)
            } else {
                null
            }
            val outcome = tool.execute(preparation, scope)
            mutationId?.let { reviewStore.completeMutation(it, outcome) }
            reviewStore.finishToolEvent(
                eventId,
                AgentExecutionStatus.SUCCEEDED,
                outcome.target.ifBlank { preparation.target },
                outcome.summary,
                outcome.summary,
                null,
            )
            onUpdate(
                AgentExecutionUpdate(
                    call.id,
                    call.name,
                    AgentExecutionStatus.SUCCEEDED,
                    outcome.summary,
                    outcome.target.ifBlank { preparation.target },
                    preparation.argumentsSummary,
                    outcome.summary,
                ),
            )
            return AgentToolResult(
                callId = call.id,
                toolName = call.name,
                success = true,
                content = outcome.content,
                summary = outcome.summary,
                target = outcome.target.ifBlank { preparation.target },
            )
        } catch (error: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                mutationId?.let { runCatching { reviewStore.failMutation(it) } }
                reviewStore.finishToolEvent(
                    eventId,
                    AgentExecutionStatus.CANCELED,
                    preparation?.target.orEmpty(),
                    preparation?.summary.orEmpty(),
                    "Canceled",
                    "CANCELED",
                )
            }
            throw error
        } catch (error: AgentRuntimeException) {
            mutationId?.let { reviewStore.failMutation(it) }
            reviewStore.finishToolEvent(
                eventId,
                AgentExecutionStatus.FAILED,
                preparation?.target.orEmpty(),
                preparation?.summary.orEmpty(),
                error.message.orEmpty(),
                error.code,
            )
            onUpdate(
                AgentExecutionUpdate(
                    call.id,
                    call.name,
                    AgentExecutionStatus.FAILED,
                    error.message.orEmpty(),
                    preparation?.target.orEmpty(),
                    preparation?.argumentsSummary.orEmpty(),
                    error.message.orEmpty(),
                ),
            )
            return failure(call, error.code, error.message.orEmpty(), preparation?.target.orEmpty())
        } catch (error: Exception) {
            val message = "Tool execution failed safely."
            mutationId?.let { reviewStore.failMutation(it) }
            reviewStore.finishToolEvent(
                eventId,
                AgentExecutionStatus.FAILED,
                preparation?.target.orEmpty(),
                preparation?.summary.orEmpty(),
                message,
                "TOOL_FAILED",
            )
            return failure(call, "TOOL_FAILED", message, preparation?.target.orEmpty())
        }
    }

    private fun failure(
        call: AIToolCall,
        code: String,
        message: String,
        target: String = "",
    ) = AgentToolResult(
        callId = call.id,
        toolName = call.name,
        success = false,
        content = errorContent(code, message),
        summary = message,
        target = target,
        errorCode = code,
    )

    private fun titleFor(name: String, english: Boolean): String = when (name) {
        "web_search" -> translate("正在搜索网络", "Searching the web", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "read_web_page" -> translate("正在读取网页", "Reading a web page", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "search_entries" -> translate("正在搜索 DeskCubby", "Searching DeskCubby", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "read_entry", "read_entries" -> translate("正在读取 DeskCubby 数据", "Reading DeskCubby data", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        "list_entries", "list_sources" -> translate("正在列出 DeskCubby 数据", "Listing DeskCubby data", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
        else -> translate("正在执行 $name", "Running $name", if (english) AppLanguage.ENGLISH else AppLanguage.CHINESE)
    }
}

internal fun errorContent(code: String, message: String): String =
    "{\"ok\":false,\"errorCode\":\"${jsonEscape(code)}\",\"message\":\"${jsonEscape(message)}\"}"

internal fun jsonEscape(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) append(' ') else append(character)
        }
    }
}
