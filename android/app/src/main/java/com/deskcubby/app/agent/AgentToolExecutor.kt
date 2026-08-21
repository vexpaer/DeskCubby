package com.deskcubby.app.agent

import com.deskcubby.app.ui.theme.translate
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.plugin.api.core.api.AIToolCall
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class AgentToolExecutor @Inject constructor(
    private val registry: AgentToolRegistry,
    private val approvalGateway: AgentApprovalGateway,
    private val reviewStore: AgentReviewStore,
    private val recoveryStore: AgentRecoveryStore,
) : AgentToolExecutionGateway {
    override suspend fun execute(
        runId: String,
        sequence: Int,
        call: AIToolCall,
        scope: AgentRunScope,
        onUpdate: (AgentExecutionUpdate) -> Unit,
    ): AgentToolResult {
        val tool = registry.find(call.name)
        val checkpoint = recoveryStore.toolCheckpoint(runId, sequence)
        checkpoint?.let { existing ->
            recoverTerminalCheckpoint(call, existing)?.let { return it }
        }
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
            val recoveredMutation = checkpoint
                ?.takeIf { it.classification == AgentToolClassification.MUTATION.name }
                ?.let { recoveryStore.mutationCheckpoint(it.id) }

            if (recoveredMutation?.status == "PENDING") {
                when {
                    preparation.target != recoveredMutation.target -> {
                        val message = "The mutation target changed while recovering the interrupted tool."
                        recoveryStore.markRecoveryFailure(eventId, "RECOVERY_CONFLICT", message)
                        return failure(call, "RECOVERY_CONFLICT", message, recoveredMutation.target)
                    }
                    preparation.before == recoveredMutation.afterContent -> {
                        // The file/repository already matches the planned after-state: the side
                        // effect crossed storage before process death, so only the durable receipt
                        // was missing. Commit the receipt without executing the tool again.
                        val content = recoveredSuccessContent()
                        val summary = checkpoint.summary.ifBlank { "Recovered committed mutation." }
                        recoveryStore.markRecoveredMutationCommitted(
                            mutationId = recoveredMutation.id,
                            eventId = eventId,
                            target = recoveredMutation.target,
                            summary = summary,
                            resultContent = content,
                        )
                        return AgentToolResult(
                            callId = call.id,
                            toolName = call.name,
                            success = true,
                            content = content,
                            summary = summary,
                            target = recoveredMutation.target,
                        )
                    }
                    preparation.before == recoveredMutation.beforeContent -> {
                        // Storage still matches the exact pre-mutation state. It is safe to continue
                        // the same claimed mutation and later complete this existing receipt.
                        mutationId = recoveredMutation.id
                    }
                    else -> {
                        val message = "The mutation target changed after the interrupted tool; recovery will not overwrite concurrent data."
                        recoveryStore.markRecoveryFailure(eventId, "RECOVERY_CONFLICT", message)
                        return failure(call, "RECOVERY_CONFLICT", message, recoveredMutation.target)
                    }
                }
            } else if (recoveredMutation != null && recoveredMutation.status != AgentReviewMutation.STATUS_APPLIED) {
                val message = "Recovered mutation has an unsupported durable state: ${recoveredMutation.status}."
                recoveryStore.markRecoveryFailure(eventId, "RECOVERY_CONFLICT", message)
                return failure(call, "RECOVERY_CONFLICT", message, recoveredMutation.target)
            }

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
                        requestId = AgentRecoveryStore.approvalRequestId(runId, sequence),
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
            if (tool.definition.classification == AgentToolClassification.MUTATION && mutationId == null) {
                mutationId = reviewStore.beginMutation(runId, eventId, tool, preparation)
            }
            val outcome = tool.execute(preparation, scope)
            mutationId?.let { reviewStore.completeMutation(it, outcome) }
            reviewStore.finishToolEvent(
                eventId,
                AgentExecutionStatus.SUCCEEDED,
                outcome.target.ifBlank { preparation.target },
                outcome.summary,
                outcome.content,
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

    /** Returns a durable result only for terminal checkpoints. Interrupted PENDING mutations are
     * reconciled after tool.prepare() can inspect the current target state. */
    private suspend fun recoverTerminalCheckpoint(
        call: AIToolCall,
        checkpoint: AgentRecoveryStore.ToolCheckpoint,
    ): AgentToolResult? {
        if (checkpoint.toolName != call.name) {
            val message = "Recovered tool sequence conflicts with the persisted execution ledger."
            recoveryStore.markRecoveryFailure(checkpoint.id, "RECOVERY_CONFLICT", message)
            return failure(call, "RECOVERY_CONFLICT", message, checkpoint.target)
        }
        when (checkpoint.status) {
            AgentExecutionStatus.SUCCEEDED.name -> return AgentToolResult(
                callId = call.id,
                toolName = call.name,
                success = true,
                content = checkpoint.resultContent.ifBlank { recoveredSuccessContent() },
                summary = checkpoint.summary.ifBlank { "Recovered committed tool result." },
                target = checkpoint.target,
            )
            AgentExecutionStatus.REJECTED.name -> return AgentToolResult(
                callId = call.id,
                toolName = call.name,
                success = false,
                rejected = true,
                content = errorContent("USER_REJECTED", checkpoint.resultContent.ifBlank { checkpoint.summary }),
                summary = checkpoint.summary,
                target = checkpoint.target,
                errorCode = "USER_REJECTED",
            )
            AgentExecutionStatus.FAILED.name,
            AgentExecutionStatus.CANCELED.name,
            -> return failure(
                call,
                checkpoint.errorCode ?: "RECOVERED_TOOL_FAILURE",
                checkpoint.resultContent.ifBlank { checkpoint.summary.ifBlank { "Recovered tool failure." } },
                checkpoint.target,
            )
        }

        if (checkpoint.classification == AgentToolClassification.MUTATION.name) {
            recoveryStore.mutationCheckpoint(checkpoint.id)?.let { mutation ->
                if (mutation.status == AgentReviewMutation.STATUS_APPLIED) {
                    val content = checkpoint.resultContent.ifBlank { recoveredSuccessContent() }
                    val summary = checkpoint.summary.ifBlank { "Recovered committed mutation." }
                    recoveryStore.markRecoveredSuccess(
                        checkpoint.id,
                        mutation.target.ifBlank { checkpoint.target },
                        summary,
                        content,
                    )
                    return AgentToolResult(
                        callId = call.id,
                        toolName = call.name,
                        success = true,
                        content = content,
                        summary = summary,
                        target = mutation.target.ifBlank { checkpoint.target },
                    )
                }
            }
        }
        return null
    }

    private fun recoveredSuccessContent(): String =
        "{\"ok\":true,\"recovered\":true,\"message\":\"Committed side effect reused without re-execution.\"}"

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
