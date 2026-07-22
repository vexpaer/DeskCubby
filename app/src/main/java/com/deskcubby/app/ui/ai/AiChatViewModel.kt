package com.deskcubby.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.AiChatException
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRepository
import com.deskcubby.app.data.repository.AiChatRole
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AiChatUiState(
    val messages: List<AiChatMessage> = emptyList(),
    val draft: String = "",
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

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

    private val mutableUiState = MutableStateFlow(AiChatUiState())
    val uiState: StateFlow<AiChatUiState> = mutableUiState.asStateFlow()

    private var nextMessageId = 1L
    private var requestSerial = 0L
    private var sendJob: Job? = null

    fun updateDraft(value: String) {
        mutableUiState.update { it.copy(draft = value.take(MAX_DRAFT_CHARS)) }
    }

    fun sendMessage() {
        val current = mutableUiState.value
        if (current.isSending) return
        val content = current.draft.trim()
        if (content.isEmpty()) return

        val currentSettings = settings.value
        val textConfig = currentSettings.aiConfigs.firstOrNull {
            it.id == currentSettings.aiChatConfigId && it.type == AiModelType.TEXT
        }
        if (textConfig == null && currentSettings.aiModel.isBlank()) {
            mutableUiState.update {
                it.copy(errorMessage = "请先在 AI 设置中填写模型名称。")
            }
            return
        }
        if ((textConfig?.endpointUrl ?: currentSettings.aiEndpointUrl).isBlank()) {
            mutableUiState.update {
                it.copy(errorMessage = "请先在 AI 设置中填写接口地址。")
            }
            return
        }

        val userMessage = AiChatMessage(
            id = nextMessageId++,
            role = AiChatRole.USER,
            content = content,
        )
        val requestMessages = current.messages + userMessage
        val requestId = ++requestSerial
        mutableUiState.value = current.copy(
            messages = requestMessages,
            draft = "",
            isSending = true,
            errorMessage = null,
        )

        sendJob = viewModelScope.launch {
            try {
                val answer = chatRepository.complete(currentSettings, requestMessages)
                if (requestId != requestSerial) return@launch
                mutableUiState.update { state ->
                    state.copy(
                        messages = state.messages + AiChatMessage(
                            id = nextMessageId++,
                            role = AiChatRole.ASSISTANT,
                            content = answer,
                        ),
                    )
                }
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

    fun clearConversation() {
        requestSerial += 1
        sendJob?.cancel()
        sendJob = null
        mutableUiState.update {
            it.copy(messages = emptyList(), isSending = false, errorMessage = null)
        }
    }

    fun selectConfiguration(id: String) {
        if (mutableUiState.value.isSending) return
        viewModelScope.launch { settingsRepository.setAiChatConfigId(id) }
    }

    fun consumeError() {
        mutableUiState.update { it.copy(errorMessage = null) }
    }

    private companion object {
        const val MAX_DRAFT_CHARS = 100_000
    }
}
