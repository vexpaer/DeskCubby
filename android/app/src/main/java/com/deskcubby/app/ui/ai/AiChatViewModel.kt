package com.deskcubby.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.agent.AgentApprovalRequest
import com.deskcubby.app.agent.AgentConversationMessage
import com.deskcubby.app.agent.AgentConversationRole
import com.deskcubby.app.agent.AgentExecutionStatus
import com.deskcubby.app.agent.AgentExecutionUpdate
import com.deskcubby.app.agent.AgentPermissionManager
import com.deskcubby.app.agent.AgentRunRequest
import com.deskcubby.app.agent.AgentRunUsage
import com.deskcubby.app.agent.AgentRuntime
import com.deskcubby.app.agent.AgentRuntimeException
import com.deskcubby.app.data.local.AiTaskQueueEntity
import com.deskcubby.app.data.local.AiTaskStateEntity
import com.deskcubby.app.data.local.AiTaskTypeEntity
import com.deskcubby.app.data.model.AgentDataSource
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.AiAttachmentKind
import com.deskcubby.app.data.repository.AiChatAttachment
import com.deskcubby.app.data.repository.AiChatException
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRepository
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.app.data.repository.AiConversation
import com.deskcubby.app.data.repository.generateConversationTitle
import com.deskcubby.app.data.taskqueue.AiTaskQueue
import com.deskcubby.app.data.taskqueue.AgentRunTaskPayload
import com.deskcubby.plugin.api.core.api.AIImage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AiChatUiState(
    val activeConversationId: Long? = null,
    val activeConversationTitle: String = "",
    val messages: List<AiChatMessage> = emptyList(),
    val draft: String = "",
    val pendingAttachments: List<AiChatAttachment> = emptyList(),
    val isPreparingAttachments: Boolean = false,
    val isSending: Boolean = false,
    val executionUpdates: List<AgentExecutionUpdate> = emptyList(),
    val lastRunUsage: AgentRunUsage? = null,
    val isContextManagerVisible: Boolean = false,
    val isPermissionModeVisible: Boolean = false,
    val errorMessage: String? = null,
    val transientMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: AiChatRepository,
    private val agentRuntime: AgentRuntime,
    private val permissionManager: AgentPermissionManager,
    private val aiTaskQueue: AiTaskQueue,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    val conversations: StateFlow<List<AiConversation>> = chatRepository.observeConversations()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val pendingApproval: StateFlow<AgentApprovalRequest?> = permissionManager.pending

    private val mutableUiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = mutableUiState.asStateFlow()

    private val activeConversationId = MutableStateFlow<Long?>(null)
    private var requestSerial = 0L
    private var sendJob: Job? = null
    private var activeTaskId: Long? = null
    private var initialConversationResolved = false
    private var initialPromptSubmitted = false

    init {
        viewModelScope.launch {
            // Re-surface an approval persisted before a process death so it can be decided.
            permissionManager.refreshPendingFromDb()
        }
        viewModelScope.launch {
            activeConversationId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
                }
                .collect { messages ->
                    mutableUiState.update { state -> state.copy(messages = messages) }
                }
        }
        viewModelScope.launch {
            val latest = chatRepository.observeConversations().first().firstOrNull()
            if (!initialConversationResolved && activeConversationId.value == null && latest != null) {
                openConversationInternal(latest)
            }
            initialConversationResolved = true
        }
    }

    fun updateDraft(value: String) {
        mutableUiState.update { it.copy(draft = value.take(MAX_DRAFT_CHARS)) }
    }

    fun attachFiles(uris: List<String>) {
        val current = mutableUiState.value
        if (current.isSending || current.isPreparingAttachments || uris.isEmpty()) return
        val remaining = MAX_ATTACHMENTS - current.pendingAttachments.size
        if (remaining <= 0) {
            mutableUiState.update { it.copy(errorMessage = localized("一次最多添加 5 个附件。", "You can add at most five attachments.")) }
            return
        }
        mutableUiState.update { it.copy(isPreparingAttachments = true, errorMessage = null) }
        viewModelScope.launch {
            val prepared = mutableListOf<AiChatAttachment>()
            try {
                uris.distinct().take(remaining).forEach { uri ->
                    prepared += chatRepository.prepareAttachment(uri)
                }
                mutableUiState.update { state ->
                    state.copy(
                        pendingAttachments = (state.pendingAttachments + prepared)
                            .distinctBy(AiChatAttachment::uri)
                            .take(MAX_ATTACHMENTS),
                    )
                }
                if (uris.distinct().size > remaining) {
                    mutableUiState.update {
                        it.copy(transientMessage = localized("已添加前 $remaining 个附件。", "Added the first $remaining attachments."))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AiChatException) {
                mutableUiState.update { it.copy(errorMessage = error.message ?: localized("无法添加附件。", "Could not add the attachment.")) }
            } catch (_: Exception) {
                mutableUiState.update { it.copy(errorMessage = localized("无法读取所选附件。", "Could not read the selected attachment.")) }
            } finally {
                mutableUiState.update { it.copy(isPreparingAttachments = false) }
            }
        }
    }

    fun removePendingAttachment(uri: String) {
        if (mutableUiState.value.isSending) return
        mutableUiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.uri == uri })
        }
    }

    fun showContextManager() {
        if (!mutableUiState.value.isSending) {
            mutableUiState.update { it.copy(isContextManagerVisible = true) }
        }
    }

    fun hideContextManager() {
        mutableUiState.update { it.copy(isContextManagerVisible = false) }
    }

    fun setSourceEnabled(source: AgentDataSource, enabled: Boolean) {
        if (mutableUiState.value.isSending) return
        viewModelScope.launch {
            val current = settingsRepository.settings.first().agentEnabledSources
            settingsRepository.setAgentEnabledSources(if (enabled) current + source else current - source)
        }
    }

    fun showPermissionMode() {
        if (!mutableUiState.value.isSending) {
            mutableUiState.update { it.copy(isPermissionModeVisible = true) }
        }
    }

    fun hidePermissionMode() {
        mutableUiState.update { it.copy(isPermissionModeVisible = false) }
    }

    fun setPermissionMode(mode: AgentPermissionMode) {
        if (mutableUiState.value.isSending) return
        viewModelScope.launch {
            settingsRepository.setAgentPermissionMode(mode)
            mutableUiState.update { it.copy(isPermissionModeVisible = false) }
        }
    }

    fun approveMutation(requestId: String) = permissionManager.approve(requestId)

    fun rejectMutation(requestId: String) = permissionManager.reject(requestId)

    fun sendMessage() {
        val current = mutableUiState.value
        if (current.isSending || current.isPreparingAttachments) return
        val content = current.draft.trim()
        val attachments = current.pendingAttachments
        if (content.isEmpty() && attachments.isEmpty()) return

        val draftSnapshot = current.draft
        val attachmentSnapshot = attachments
        val requestId = ++requestSerial
        mutableUiState.update {
            it.copy(
                isSending = true,
                executionUpdates = emptyList(),
                lastRunUsage = null,
                errorMessage = null,
            )
        }
        sendJob = viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.settings.first()
                val textConfig = currentSettings.aiConfigs.firstOrNull { config ->
                    config.id == currentSettings.aiChatConfigId &&
                        config.type == AiModelType.TEXT && config.enabled
                } ?: throw AgentRuntimeException(
                    "AI_MODEL_UNAVAILABLE",
                    localized("请先选择可用的文字模型配置。", "Select an available text-model configuration first."),
                )
                if (!textConfig.supportsToolCalling) {
                    throw AgentRuntimeException(
                        "AI_TOOLS_UNSUPPORTED",
                        localized(
                            "当前 Provider 未启用原生工具调用，无法运行 Agent。请在 AI 设置中确认能力。",
                            "Native tool calling is disabled for this provider. Enable the capability in AI settings.",
                        ),
                    )
                }
                val effectiveRequest = content.ifBlank {
                    localized("请分析我附加的内容。", "Please analyze the attached content.")
                }
                val conversationId = current.activeConversationId ?: chatRepository.createConversation(
                    firstMessage = effectiveRequest,
                    hasImage = attachments.any { it.kind == AiAttachmentKind.IMAGE },
                    modelConfigId = textConfig.id,
                ).also { newId ->
                    activeConversationId.value = newId
                    mutableUiState.update {
                        it.copy(
                            activeConversationId = newId,
                            activeConversationTitle = generateConversationTitle(
                                effectiveRequest,
                                attachments.any { attachment -> attachment.kind == AiAttachmentKind.IMAGE },
                            ),
                        )
                    }
                }
                chatRepository.appendAgentUserMessage(
                    conversationId = conversationId,
                    content = effectiveRequest,
                    attachments = attachments,
                )
                mutableUiState.update { state ->
                    state.copy(
                        draft = if (state.draft == draftSnapshot) "" else state.draft,
                        pendingAttachments = if (state.pendingAttachments == attachmentSnapshot) {
                            emptyList()
                        } else {
                            state.pendingAttachments
                        },
                    )
                }
                val runId = UUID.randomUUID().toString()
                activeTaskId = aiTaskQueue.enqueueTask(
                    type = AiTaskTypeEntity.AGENT_RUN,
                    payload = AgentRunTaskPayload(
                        conversationId = conversationId,
                        runId = runId,
                        conversationTitle = mutableUiState.value.activeConversationTitle,
                        userRequest = effectiveRequest,
                        modelConfigId = textConfig.id,
                        customModelInstructions = buildAgentCustomInstructions(
                            currentSettings.agentPrompt,
                            textConfig.systemPrompt,
                        ),
                        allowedSources = currentSettings.agentEnabledSources.mapTo(linkedSetOf()) {
                            it.wireValue
                        },
                        permissionMode = currentSettings.agentPermissionMode,
                        english = currentSettings.appLanguage == AppLanguage.ENGLISH,
                    ),
                )
                // The worker runs the Agent and appends the assistant message itself. We only need
                // to wait for a durable terminal state; navigation/backgrounding/force-stop cannot
                // cancel a queued task. A killed run surfaces as FAILED/CANCELED below.
                val terminalTask = waitForAgentTerminal()
                activeTaskId = null
                if (requestId != requestSerial) return@launch
                if (terminalTask == null || terminalTask.state == AiTaskStateEntity.FAILED) {
                    mutableUiState.update {
                        it.copy(
                            errorMessage = terminalTask?.errorSummary
                                ?.takeIf(String::isNotBlank)
                                ?: localized(
                                    "本次 Agent 运行被中断，请重新发送。",
                                    "This Agent run was interrupted; please send it again.",
                                ),
                        )
                    }
                } else if (terminalTask.state == AiTaskStateEntity.CANCELED) {
                    mutableUiState.update {
                        it.copy(transientMessage = localized("Agent 已中止。", "Agent stopped."))
                    }
                }
            } catch (error: CancellationException) {
                if (requestId == requestSerial) {
                    mutableUiState.update {
                        it.copy(transientMessage = localized("Agent 已中止。", "Agent stopped."))
                    }
                }
                throw error
            } catch (error: AgentRuntimeException) {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(errorMessage = error.message ?: localized("Agent 运行失败。", "Agent run failed.")) }
                }
            } catch (error: AiChatException) {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(errorMessage = error.message ?: localized("AI 请求失败。", "AI request failed.")) }
                }
            } catch (_: Exception) {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(errorMessage = localized("Agent 运行失败，请稍后重试。", "Agent run failed. Try again later.")) }
                }
            } finally {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(isSending = false) }
                    sendJob = null
                }
            }
        }
    }

    fun stopAgent() {
        if (!mutableUiState.value.isSending) return
        requestSerial += 1
        pendingApproval.value?.let { permissionManager.reject(it.requestId) }
        sendJob?.cancel()
        sendJob = null
        val taskId = activeTaskId
        activeTaskId = null
        if (taskId != null) {
            viewModelScope.launch { runCatching { aiTaskQueue.cancelTask(taskId) } }
        }
        mutableUiState.update {
            it.copy(
                isSending = false,
                transientMessage = localized("Agent 已中止。", "Agent stopped."),
            )
        }
    }

    /**
     * Sends a one-shot initial prompt (e.g. Desk's "总结今天" action) as the user's first message.
     * The consumed flag keeps rotation/recomposition from re-sending the same prompt.
     */
    fun submitInitialPrompt(prompt: String?) {
        if (prompt.isNullOrBlank() || initialPromptSubmitted || mutableUiState.value.isSending) return
        initialPromptSubmitted = true
        mutableUiState.update { it.copy(draft = prompt) }
        sendMessage()
    }

    fun startNewConversation() {
        if (mutableUiState.value.isSending || mutableUiState.value.isPreparingAttachments) return
        initialConversationResolved = true
        activeConversationId.value = null
        mutableUiState.value = AiChatUiState()
    }

    fun clearConversation() = startNewConversation()

    fun openConversation(id: Long) {
        if (mutableUiState.value.isSending ||
            mutableUiState.value.isPreparingAttachments ||
            id == activeConversationId.value
        ) return
        initialConversationResolved = true
        viewModelScope.launch {
            chatRepository.getConversation(id)?.let { openConversationInternal(it) }
        }
    }

    fun renameConversation(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            if (!chatRepository.renameConversation(id, title)) {
                mutableUiState.update { it.copy(errorMessage = localized("无法重命名这段对话。", "Could not rename this conversation.")) }
            } else if (activeConversationId.value == id) {
                mutableUiState.update {
                    it.copy(activeConversationTitle = title.replace(Regex("\\s+"), " ").trim().take(80))
                }
            }
        }
    }

    fun deleteConversation(id: Long) {
        if (mutableUiState.value.isSending && activeConversationId.value == id) return
        viewModelScope.launch {
            if (chatRepository.deleteConversation(id) && activeConversationId.value == id) {
                startNewConversation()
            }
        }
    }

    fun selectConfiguration(id: String) {
        if (mutableUiState.value.isSending) return
        viewModelScope.launch {
            settingsRepository.setAiChatConfigId(id)
            activeConversationId.value?.let { chatRepository.setConversationModel(it, id) }
        }
    }

    fun consumeError() {
        mutableUiState.update { it.copy(errorMessage = null) }
    }

    fun consumeTransientMessage() {
        mutableUiState.update { it.copy(transientMessage = null) }
    }

    private fun recordExecutionUpdate(update: AgentExecutionUpdate) {
        mutableUiState.update { state ->
            val index = state.executionUpdates.indexOfFirst { it.toolCallId == update.toolCallId }
            state.copy(
                executionUpdates = if (index < 0) {
                    (state.executionUpdates + update).takeLast(MAX_EXECUTION_UPDATES)
                } else {
                    state.executionUpdates.toMutableList().apply { this[index] = update }
                },
            )
        }
    }

    /**
     * Waits for the active agent task to reach a terminal state, returning the durable row. [first]
     * completes as soon as the task row transitions to SUCCEEDED/FAILED/CANCELED, or when the row
     * disappears (defensive). A guard timeout keeps a stale observation from blocking forever.
     */
    private suspend fun waitForAgentTerminal(): AiTaskQueueEntity? {
        val taskId = activeTaskId ?: return null
        return withTimeoutOrNull(AGENT_WAIT_TIMEOUT_MS) {
            aiTaskQueue.observeTask(taskId).first { row ->
                val state = row?.state
                state == AiTaskStateEntity.SUCCEEDED ||
                    state == AiTaskStateEntity.FAILED ||
                    state == AiTaskStateEntity.CANCELED
            }
        }
    }

    private suspend fun openConversationInternal(conversation: AiConversation) {
        val persisted = settingsRepository.settings.first()
        val originalAvailable = persisted.aiConfigs.any {
            it.id == conversation.modelConfigId && it.type == AiModelType.TEXT && it.enabled
        }
        if (originalAvailable) settingsRepository.setAiChatConfigId(conversation.modelConfigId)
        activeConversationId.value = conversation.id
        mutableUiState.update {
            AiChatUiState(
                activeConversationId = conversation.id,
                activeConversationTitle = conversation.title,
                errorMessage = if (conversation.modelConfigId.isNotBlank() && !originalAvailable) {
                    localized(
                        "原对话使用的模型配置已不存在；历史仍可查看，继续时将使用当前配置。",
                        "The original model configuration no longer exists. History remains available; continuing uses the current configuration.",
                    )
                } else null,
            )
        }
    }

    private fun buildAgentConversation(messages: List<AiChatMessage>): List<AgentConversationMessage> {
        var remaining = MAX_HISTORY_CONTENT_CHARS
        val reversed = mutableListOf<AgentConversationMessage>()
        messages.asReversed().take(MAX_HISTORY_MESSAGES).forEach { message ->
            if (remaining <= 0) return@forEach
            val documentContext = message.attachments
                .asSequence()
                .filter { it.kind == AiAttachmentKind.DOCUMENT && !it.extractedText.isNullOrBlank() }
                .joinToString("\n\n") { attachment ->
                    "<untrusted_attachment name=\"${attachment.displayName.xmlEscape()}\" " +
                        "mime=\"${attachment.mimeType.xmlEscape()}\">\n" +
                        attachment.extractedText.orEmpty() + "\n</untrusted_attachment>"
                }
            val syncedImageNotice = message.attachments.any {
                it.kind == AiAttachmentKind.IMAGE && it.uri.isBlank()
            }
            val combined = buildString {
                append(message.content)
                if (documentContext.isNotBlank()) append("\n\n").append(documentContext)
                if (syncedImageNotice) {
                    append("\n\n[An image attachment exists in synced history but its device-local URI is unavailable.]")
                }
            }.takeLast(remaining)
            remaining -= combined.length
            val images = buildList {
                message.image?.takeIf { it.uri.isNotBlank() }?.let { add(AIImage(it.uri, it.mimeType)) }
                message.attachments.asSequence()
                    .filter { it.kind == AiAttachmentKind.IMAGE && it.uri.isNotBlank() }
                    .map { AIImage(it.uri, it.mimeType) }
                    .filterNot { candidate -> any { it.contentUri == candidate.contentUri } }
                    .forEach(::add)
            }
            reversed += AgentConversationMessage(
                role = when (message.role) {
                    AiChatRole.USER -> AgentConversationRole.USER
                    AiChatRole.ASSISTANT -> AgentConversationRole.ASSISTANT
                    AiChatRole.CONTEXT -> AgentConversationRole.UNTRUSTED_CONTEXT
                },
                content = combined,
                images = images,
            )
        }
        return reversed.asReversed()
    }

    private fun localized(chinese: String, english: String): String =
        if (settings.value.appLanguage == AppLanguage.ENGLISH) english else chinese

    private fun buildAgentCustomInstructions(
        globalPrompt: String,
        configPrompt: String,
    ): String = buildString {
        globalPrompt.trim().takeIf(String::isNotBlank)?.let { append(it).append("\n") }
        configPrompt.trim().takeIf(String::isNotBlank)?.let { append(it) }
    }.trim().take(MAX_AGENT_INSTRUCTIONS_CHARS)

    private fun String.xmlEscape(): String = replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .take(500)

    private companion object {
        const val MAX_AGENT_INSTRUCTIONS_CHARS = 20_000
        const val MAX_DRAFT_CHARS = 100_000
        const val MAX_ATTACHMENTS = 5
        const val MAX_HISTORY_MESSAGES = 80
        const val MAX_HISTORY_CONTENT_CHARS = 1024 * 1024
        const val MAX_EXECUTION_UPDATES = 200
        const val AGENT_WAIT_TIMEOUT_MS = 30 * 60_000L
    }
}

internal fun executionStatusIsTerminal(status: AgentExecutionStatus): Boolean = status in setOf(
    AgentExecutionStatus.REJECTED,
    AgentExecutionStatus.SUCCEEDED,
    AgentExecutionStatus.FAILED,
    AgentExecutionStatus.CANCELED,
)
