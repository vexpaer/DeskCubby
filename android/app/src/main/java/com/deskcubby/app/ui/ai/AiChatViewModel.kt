package com.deskcubby.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.agent.AgentApprovalRequest
import com.deskcubby.app.agent.AgentExecutionStatus
import com.deskcubby.app.agent.AgentExecutionUpdate
import com.deskcubby.app.agent.AgentPermissionManager
import com.deskcubby.app.agent.AgentReviewRepository
import com.deskcubby.app.agent.AgentReviewToolEvent
import com.deskcubby.app.agent.AgentRunUsage
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
import com.deskcubby.app.data.repository.AiConversation
import com.deskcubby.app.data.repository.generateConversationTitle
import com.deskcubby.app.data.taskqueue.AiTaskQueue
import com.deskcubby.app.data.taskqueue.AgentRunTaskPayload
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/**
 * AI UI state is deliberately thin: Room's ai_task_queue is the source of truth for long-running
 * work. This ViewModel only prepares/persists the user's request and observes the durable task row;
 * it never waits for a model call in a page-owned coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: AiChatRepository,
    private val permissionManager: AgentPermissionManager,
    private val agentReviewRepository: AgentReviewRepository,
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
    private val activeRunId = MutableStateFlow<String?>(null)
    private var requestSerial = 0L
    private var submitJob: Job? = null
    private var activeTaskId: Long? = null
    private var stopRequestedRunId: String? = null
    private var lastHandledTerminalTaskId: Long? = null
    private var localSubmitting = false
    private var initialConversationResolved = false
    private var initialPromptSubmitted = false

    init {
        viewModelScope.launch { permissionManager.refreshPendingFromDb() }

        viewModelScope.launch {
            activeConversationId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
                }
                .collect { messages ->
                    mutableUiState.update { state -> state.copy(messages = messages) }
                }
        }

        // Reconnect UI to durable work after navigation, rotation, or process recreation. No
        // page-owned timer/wait job is needed: a queued/running row alone means "sending".
        viewModelScope.launch {
            combine(activeConversationId, aiTaskQueue.observeTasks()) { conversationId, tasks ->
                if (conversationId == null) return@combine null
                tasks.asSequence()
                    .filter { it.type == AiTaskTypeEntity.AGENT_RUN }
                    .mapNotNull { task ->
                        val payload = runCatching { AgentRunTaskPayload.decode(task.payloadJson) }.getOrNull()
                        if (payload?.conversationId == conversationId) task else null
                    }
                    .maxByOrNull(AiTaskQueueEntity::id)
            }.collect { latest ->
                if (latest == null) {
                    if (!localSubmitting) {
                        activeTaskId = null
                        mutableUiState.update { it.copy(isSending = false) }
                    }
                } else {
                    applyObservedTask(latest)
                }
            }
        }

        // Tool progress and usage already have durable Room ledgers. Project those ledgers back to
        // the chat instead of relying on an in-memory Worker callback, which is lost on navigation
        // and process recreation.
        viewModelScope.launch {
            activeRunId.flatMapLatest { runId ->
                if (runId == null) flowOf(emptyList())
                else agentReviewRepository.observeToolEvents(runId)
            }.collect { events ->
                mutableUiState.update { state ->
                    state.copy(executionUpdates = events.map { it.toExecutionUpdate() })
                }
            }
        }
        viewModelScope.launch {
            combine(activeRunId, agentReviewRepository.observeRuns()) { runId, runs ->
                runs.firstOrNull { it.runId == runId && it.completedAt != null }?.usage
            }.collect { usage -> mutableUiState.update { it.copy(lastRunUsage = usage) } }
        }

        viewModelScope.launch {
            val latest = chatRepository.observeConversations().first().firstOrNull()
            if (!initialConversationResolved && activeConversationId.value == null && latest != null) {
                openConversationInternal(latest)
            }
            initialConversationResolved = true
        }
    }

    private fun applyObservedTask(task: AiTaskQueueEntity) {
        val runId = runCatching { AgentRunTaskPayload.decode(task.payloadJson).runId }.getOrNull()
        runId?.let { activeRunId.value = it }
        if (task.state !in AiTaskQueue.TERMINAL_STATES) {
            activeTaskId = task.id
            lastHandledTerminalTaskId = null
            if (runId != null && stopRequestedRunId == runId) {
                stopRequestedRunId = null
                viewModelScope.launch { aiTaskQueue.cancelTask(task.id) }
                mutableUiState.update {
                    it.copy(
                        isSending = true,
                        transientMessage = localized("正在中止 Agent…", "Stopping Agent…"),
                    )
                }
            } else {
                mutableUiState.update { it.copy(isSending = true) }
            }
            return
        }

        activeTaskId = null
        if (runId != null && stopRequestedRunId == runId) stopRequestedRunId = null
        if (lastHandledTerminalTaskId == task.id) {
            mutableUiState.update { it.copy(isSending = localSubmitting) }
            return
        }
        lastHandledTerminalTaskId = task.id
        mutableUiState.update { state ->
            when (task.state) {
                AiTaskStateEntity.FAILED -> state.copy(
                    isSending = localSubmitting,
                    errorMessage = task.errorSummary.takeIf(String::isNotBlank)
                        ?: localized("AI 请求失败。", "AI request failed."),
                )
                AiTaskStateEntity.CANCELED -> state.copy(
                    isSending = localSubmitting,
                    transientMessage = localized("Agent 已中止。", "Agent stopped."),
                )
                else -> state.copy(isSending = localSubmitting)
            }
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
        if (!mutableUiState.value.isSending) mutableUiState.update { it.copy(isContextManagerVisible = true) }
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
        if (!mutableUiState.value.isSending) mutableUiState.update { it.copy(isPermissionModeVisible = true) }
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
        localSubmitting = true
        stopRequestedRunId = null
        activeRunId.value = null
        mutableUiState.update {
            it.copy(
                isSending = true,
                executionUpdates = emptyList(),
                lastRunUsage = null,
                errorMessage = null,
            )
        }

        submitJob = viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.settings.first()
                val textConfig = currentSettings.aiConfigs.firstOrNull { config ->
                    config.id == currentSettings.aiChatConfigId &&
                        config.type == AiModelType.TEXT && config.enabled
                } ?: throw AgentRuntimeException(
                    "AI_MODEL_UNAVAILABLE",
                    localized("请先选择可用的文字模型配置。", "Select an available text-model configuration first."),
                )
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
                        pendingAttachments = if (state.pendingAttachments == attachmentSnapshot) emptyList()
                        else state.pendingAttachments,
                    )
                }
                val runId = UUID.randomUUID().toString()
                activeRunId.value = runId
                val taskId = aiTaskQueue.enqueueTask(
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
                activeTaskId = taskId
                lastHandledTerminalTaskId = null
                // Room invalidation can race ahead of this assignment for a very fast failure. Read
                // the just-enqueued row once so a terminal task can never leave the UI spinning.
                aiTaskQueue.taskById(taskId)?.let(::applyObservedTask)
            } catch (error: CancellationException) {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(transientMessage = localized("Agent 已中止。", "Agent stopped.")) }
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
            } catch (error: Exception) {
                if (requestId == requestSerial) {
                    mutableUiState.update {
                        it.copy(errorMessage = error.message?.takeIf(String::isNotBlank)
                            ?: localized("Agent 运行失败，请稍后重试。", "Agent run failed. Try again later."))
                    }
                }
            } finally {
                localSubmitting = false
                submitJob = null
                if (requestId == requestSerial && activeTaskId == null) {
                    mutableUiState.update { it.copy(isSending = false) }
                }
            }
        }
    }

    fun stopAgent() {
        if (!mutableUiState.value.isSending) return
        requestSerial += 1
        pendingApproval.value?.let { permissionManager.reject(it.requestId) }
        submitJob?.cancel()
        submitJob = null
        localSubmitting = false
        val taskId = activeTaskId
        if (taskId == null) stopRequestedRunId = activeRunId.value
        if (taskId != null) viewModelScope.launch { runCatching { aiTaskQueue.cancelTask(taskId) } }
        mutableUiState.update {
            if (taskId == null) {
                it.copy(
                    isSending = false,
                    transientMessage = localized("Agent 已中止。", "Agent stopped."),
                )
            } else {
                it.copy(transientMessage = localized("正在中止 Agent…", "Stopping Agent…"))
            }
        }
    }

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
        activeRunId.value = null
        activeTaskId = null
        stopRequestedRunId = null
        lastHandledTerminalTaskId = null
        mutableUiState.value = AiChatUiState()
    }

    fun clearConversation() = startNewConversation()

    fun openConversation(id: Long) {
        if (mutableUiState.value.isSending || mutableUiState.value.isPreparingAttachments || id == activeConversationId.value) return
        initialConversationResolved = true
        viewModelScope.launch { chatRepository.getConversation(id)?.let { openConversationInternal(it) }
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
            if (chatRepository.deleteConversation(id) && activeConversationId.value == id) startNewConversation()
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

    private suspend fun openConversationInternal(conversation: AiConversation) {
        val persisted = settingsRepository.settings.first()
        val originalAvailable = persisted.aiConfigs.any {
            it.id == conversation.modelConfigId && it.type == AiModelType.TEXT && it.enabled
        }
        if (originalAvailable) settingsRepository.setAiChatConfigId(conversation.modelConfigId)
        activeRunId.value = null
        activeTaskId = null
        stopRequestedRunId = null
        lastHandledTerminalTaskId = null
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

    private fun localized(chinese: String, english: String): String =
        if (settings.value.appLanguage == AppLanguage.ENGLISH) english else chinese

    private fun buildAgentCustomInstructions(globalPrompt: String, configPrompt: String): String = buildString {
        globalPrompt.trim().takeIf(String::isNotBlank)?.let { append(it).append("\n") }
        configPrompt.trim().takeIf(String::isNotBlank)?.let { append(it) }
    }.trim().take(MAX_AGENT_INSTRUCTIONS_CHARS)

    private companion object {
        const val MAX_AGENT_INSTRUCTIONS_CHARS = 20_000
        const val MAX_DRAFT_CHARS = 100_000
        const val MAX_ATTACHMENTS = 5
    }
}

internal fun AgentReviewToolEvent.toExecutionUpdate(): AgentExecutionUpdate {
    val executionStatus = runCatching { AgentExecutionStatus.valueOf(status) }
        .getOrDefault(AgentExecutionStatus.FAILED)
    return AgentExecutionUpdate(
        toolCallId = toolCallId.ifBlank { "event-$id" },
        toolName = toolName,
        status = executionStatus,
        title = summary.ifBlank { toolName },
        target = target,
        argumentsSummary = argumentsSummary,
        resultSummary = resultSummary,
    )
}

internal fun executionStatusIsTerminal(status: AgentExecutionStatus): Boolean = status in setOf(
    AgentExecutionStatus.REJECTED,
    AgentExecutionStatus.SUCCEEDED,
    AgentExecutionStatus.FAILED,
    AgentExecutionStatus.CANCELED,
)
