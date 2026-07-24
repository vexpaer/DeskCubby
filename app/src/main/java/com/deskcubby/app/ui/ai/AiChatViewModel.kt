package com.deskcubby.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.AiChatException
import com.deskcubby.app.data.repository.AiChatImage
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRepository
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.app.data.repository.AiConversation
import com.deskcubby.app.data.repository.generateConversationTitle
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class AiChatUiState(
    val activeConversationId: Long? = null,
    val activeConversationTitle: String = "",
    val messages: List<AiChatMessage> = emptyList(),
    val draft: String = "",
    val pendingImage: AiChatImage? = null,
    val isPreparingImage: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: AiChatRepository,
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

    private val mutableUiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = mutableUiState.asStateFlow()

    private val activeConversationId = MutableStateFlow<Long?>(null)
    private var requestSerial = 0L
    private var sendJob: Job? = null
    private var initialConversationResolved = false

    init {
        viewModelScope.launch {
            activeConversationId
                .flatMapLatest { id ->
                    if (id == null) flowOf(emptyList())
                    else chatRepository.observeMessages(id)
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

    fun attachImage(uri: String) {
        val current = mutableUiState.value
        if (current.isSending || current.isPreparingImage) return
        mutableUiState.update { it.copy(isPreparingImage = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val image = chatRepository.prepareImage(uri)
                mutableUiState.update { it.copy(pendingImage = image) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AiChatException) {
                mutableUiState.update {
                    it.copy(errorMessage = error.message ?: "无法添加图片。")
                }
            } catch (_: Exception) {
                mutableUiState.update { it.copy(errorMessage = "无法添加图片，请重新选择。") }
            } finally {
                mutableUiState.update { it.copy(isPreparingImage = false) }
            }
        }
    }

    fun removePendingImage() {
        if (mutableUiState.value.isSending) return
        mutableUiState.update { it.copy(pendingImage = null) }
    }

    fun sendMessage() {
        val current = mutableUiState.value
        if (current.isSending || current.isPreparingImage) return
        val content = current.draft.trim()
        val image = current.pendingImage
        if (content.isEmpty() && image == null) return

        val draftSnapshot = current.draft
        val requestId = ++requestSerial
        mutableUiState.update {
            it.copy(
                isSending = true,
                errorMessage = null,
            )
        }

        sendJob = viewModelScope.launch {
            try {
                val currentSettings = settingsRepository.settings.first()
                val textConfig = currentSettings.aiConfigs.firstOrNull {
                    it.id == currentSettings.aiChatConfigId && it.type == AiModelType.TEXT
                }
                if (textConfig == null && currentSettings.aiModel.isBlank()) {
                    mutableUiState.update {
                        it.copy(errorMessage = "请先在 AI 设置中填写模型名称。")
                    }
                    return@launch
                }
                if ((textConfig?.endpointUrl ?: currentSettings.aiEndpointUrl).isBlank()) {
                    mutableUiState.update {
                        it.copy(errorMessage = "请先在 AI 设置中填写接口地址。")
                    }
                    return@launch
                }
                if (requestId != requestSerial) return@launch
                val conversationId = current.activeConversationId ?: chatRepository.createConversation(
                    firstMessage = content,
                    hasImage = image != null,
                    modelConfigId = textConfig?.id.orEmpty(),
                ).also { newId ->
                    activeConversationId.value = newId
                    mutableUiState.update {
                        it.copy(
                            activeConversationId = newId,
                            activeConversationTitle = generateConversationTitle(content, image != null),
                        )
                    }
                }
                chatRepository.appendMessage(
                    conversationId = conversationId,
                    role = AiChatRole.USER,
                    content = content,
                    image = image,
                )
                mutableUiState.update { state ->
                    state.copy(
                        draft = if (state.draft == draftSnapshot) "" else state.draft,
                        pendingImage = if (state.pendingImage == image) null else state.pendingImage,
                    )
                }
                val requestMessages = chatRepository.getMessages(conversationId)
                val answer = chatRepository.completeWithReasoning(currentSettings, requestMessages)
                if (requestId != requestSerial) return@launch
                chatRepository.appendMessage(
                    conversationId = conversationId,
                    role = AiChatRole.ASSISTANT,
                    content = answer.content,
                    reasoning = answer.reasoning,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: AiChatException) {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(errorMessage = error.message ?: "AI 请求失败。") }
                }
            } catch (_: Exception) {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(errorMessage = "AI 请求失败，请稍后重试。") }
                }
            } finally {
                if (requestId == requestSerial) {
                    mutableUiState.update { it.copy(isSending = false) }
                    sendJob = null
                }
            }
        }
    }

    fun startNewConversation() {
        if (mutableUiState.value.isSending || mutableUiState.value.isPreparingImage) return
        initialConversationResolved = true
        activeConversationId.value = null
        mutableUiState.update {
            it.copy(
                activeConversationId = null,
                activeConversationTitle = "",
                messages = emptyList(),
                draft = "",
                pendingImage = null,
                errorMessage = null,
            )
        }
    }

    /** Kept for callers compiled against the earlier in-memory chat API. */
    fun clearConversation() = startNewConversation()

    fun openConversation(id: Long) {
        if (mutableUiState.value.isSending ||
            mutableUiState.value.isPreparingImage ||
            id == activeConversationId.value
        ) {
            return
        }
        initialConversationResolved = true
        viewModelScope.launch {
            val conversation = chatRepository.getConversation(id) ?: return@launch
            openConversationInternal(conversation)
        }
    }

    fun renameConversation(id: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            if (!chatRepository.renameConversation(id, title)) {
                mutableUiState.update { it.copy(errorMessage = "无法重命名这段对话。") }
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
            if (!chatRepository.deleteConversation(id)) return@launch
            if (activeConversationId.value == id) startNewConversation()
        }
    }

    fun selectConfiguration(id: String) {
        if (mutableUiState.value.isSending) return
        viewModelScope.launch {
            settingsRepository.setAiChatConfigId(id)
            activeConversationId.value?.let { conversationId ->
                chatRepository.setConversationModel(conversationId, id)
            }
        }
    }

    fun consumeError() {
        mutableUiState.update { it.copy(errorMessage = null) }
    }

    private suspend fun openConversationInternal(conversation: AiConversation) {
        val persistedSettings = settingsRepository.settings.first()
        val originalConfigAvailable = persistedSettings.aiConfigs.any {
            it.id == conversation.modelConfigId && it.type == AiModelType.TEXT
        }
        if (originalConfigAvailable) {
            settingsRepository.setAiChatConfigId(conversation.modelConfigId)
        }
        activeConversationId.value = conversation.id
        mutableUiState.update {
            it.copy(
                activeConversationId = conversation.id,
                activeConversationTitle = conversation.title,
                messages = emptyList(),
                draft = "",
                pendingImage = null,
                errorMessage = if (conversation.modelConfigId.isNotBlank() && !originalConfigAvailable) {
                    "原对话使用的模型配置已不存在；历史仍可查看，继续聊天时将使用当前配置。"
                } else {
                    null
                },
            )
        }
    }

    private companion object {
        const val MAX_DRAFT_CHARS = 100_000
    }
}
