package com.deskcubby.app.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.AiChatException
import com.deskcubby.app.data.repository.AiChatImage
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRepository
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.app.data.repository.AiConversation
import com.deskcubby.app.data.repository.AiContextCandidate
import com.deskcubby.app.data.repository.AiContextCodec
import com.deskcubby.app.data.repository.AiContextException
import com.deskcubby.app.data.repository.AiContextFailure
import com.deskcubby.app.data.repository.AiContextItemPreview
import com.deskcubby.app.data.repository.AiContextRepository
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
    val pendingContextKeys: Set<String> = emptySet(),
    val contextCandidates: List<AiContextCandidate> = emptyList(),
    val contextCandidatesLoaded: Boolean = false,
    val isContextPickerVisible: Boolean = false,
    val isLoadingContextCandidates: Boolean = false,
    val contextPreview: AiContextItemPreview? = null,
    val isLoadingContextPreview: Boolean = false,
    val isPreparingImage: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AiChatViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: AiChatRepository,
    private val contextRepository: AiContextRepository,
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

    fun openContextPicker() {
        val current = mutableUiState.value
        if (current.isSending || current.isPreparingImage) return
        mutableUiState.update {
            it.copy(
                isContextPickerVisible = true,
                errorMessage = null,
            )
        }
        if (!current.contextCandidatesLoaded && !current.isLoadingContextCandidates) {
            loadContextCandidates()
        }
    }

    fun closeContextPicker() {
        if (mutableUiState.value.isLoadingContextPreview) return
        mutableUiState.update {
            it.copy(
                isContextPickerVisible = false,
                contextPreview = null,
            )
        }
    }

    fun refreshContextCandidates() {
        val current = mutableUiState.value
        if (current.isSending || current.isLoadingContextCandidates) return
        loadContextCandidates()
    }

    fun toggleContextCandidate(selectionKey: String) {
        val current = mutableUiState.value
        if (current.isSending || current.isLoadingContextCandidates) return
        if (current.contextCandidates.none { it.selectionKey == selectionKey }) return
        val updated = current.pendingContextKeys.toMutableSet()
        if (!updated.add(selectionKey)) {
            updated.remove(selectionKey)
        } else if (updated.size > AiContextCodec.MAX_ITEMS) {
            mutableUiState.update {
                it.copy(
                    errorMessage = aiContextFailureMessage(
                        AiContextException(
                            failure = AiContextFailure.TOO_MANY_ITEMS,
                            itemCount = updated.size,
                        ),
                        settings.value.appLanguage,
                    ),
                )
            }
            return
        }
        mutableUiState.update { it.copy(pendingContextKeys = updated) }
    }

    fun clearPendingContexts() {
        if (mutableUiState.value.isSending) return
        mutableUiState.update { it.copy(pendingContextKeys = emptySet()) }
    }

    fun previewContextCandidate(selectionKey: String) {
        val current = mutableUiState.value
        if (current.isSending || current.isLoadingContextPreview) return
        if (current.contextCandidates.none { it.selectionKey == selectionKey }) return
        mutableUiState.update {
            it.copy(
                isLoadingContextPreview = true,
                contextPreview = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val preview = contextRepository.preview(selectionKey)
                mutableUiState.update { it.copy(contextPreview = preview) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AiContextException) {
                mutableUiState.update {
                    it.copy(
                        errorMessage = aiContextFailureMessage(
                            error,
                            settings.value.appLanguage,
                        ),
                    )
                }
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        errorMessage = aiContextFailureMessage(
                            AiContextException(AiContextFailure.SOURCE_UNAVAILABLE),
                            settings.value.appLanguage,
                        ),
                    )
                }
            } finally {
                mutableUiState.update { it.copy(isLoadingContextPreview = false) }
            }
        }
    }

    fun dismissContextPreview() {
        mutableUiState.update { it.copy(contextPreview = null) }
    }

    fun sendMessage() {
        val current = mutableUiState.value
        if (current.isSending || current.isPreparingImage) return
        val content = current.draft.trim()
        val image = current.pendingImage
        val contextKeys = current.pendingContextKeys.toList()
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
                val frozenContext = contextKeys.takeIf { it.isNotEmpty() }
                    ?.let { selected ->
                        AiContextCodec.encode(contextRepository.freeze(selected))
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
                chatRepository.appendUserTurn(
                    conversationId = conversationId,
                    frozenContext = frozenContext,
                    content = content,
                    image = image,
                )
                mutableUiState.update { state ->
                    state.copy(
                        draft = if (state.draft == draftSnapshot) "" else state.draft,
                        pendingImage = if (state.pendingImage == image) null else state.pendingImage,
                        pendingContextKeys = if (state.pendingContextKeys == contextKeys.toSet()) {
                            emptySet()
                        } else {
                            state.pendingContextKeys
                        },
                        isContextPickerVisible = false,
                        contextPreview = null,
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
            } catch (error: AiContextException) {
                if (requestId == requestSerial) {
                    mutableUiState.update {
                        it.copy(
                            errorMessage = aiContextFailureMessage(
                                error,
                                settings.value.appLanguage,
                            ),
                        )
                    }
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
                pendingContextKeys = emptySet(),
                isContextPickerVisible = false,
                contextPreview = null,
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
                pendingContextKeys = emptySet(),
                isContextPickerVisible = false,
                contextPreview = null,
                errorMessage = if (conversation.modelConfigId.isNotBlank() && !originalConfigAvailable) {
                    "原对话使用的模型配置已不存在；历史仍可查看，继续聊天时将使用当前配置。"
                } else {
                    null
                },
            )
        }
    }

    private fun loadContextCandidates() {
        mutableUiState.update {
            it.copy(
                isLoadingContextCandidates = true,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val candidates = contextRepository.listCandidates()
                val availableKeys = candidates.mapTo(hashSetOf(), AiContextCandidate::selectionKey)
                mutableUiState.update { state ->
                    state.copy(
                        contextCandidates = candidates,
                        contextCandidatesLoaded = true,
                        pendingContextKeys = state.pendingContextKeys.filterTo(linkedSetOf()) {
                            it in availableKeys
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        errorMessage = contextCandidateLoadFailureMessage(
                            settings.value.appLanguage,
                        ),
                    )
                }
            } finally {
                mutableUiState.update { it.copy(isLoadingContextCandidates = false) }
            }
        }
    }

    private companion object {
        const val MAX_DRAFT_CHARS = 100_000
    }
}

internal fun aiContextFailureMessage(
    error: AiContextException,
    language: AppLanguage,
): String {
    val english = language == AppLanguage.ENGLISH
    val title = error.itemTitle?.trim()?.takeIf(String::isNotEmpty)
    val measuredSize = error.measuredBytes?.let(::formatContextKiB)
    return when (error.failure) {
        AiContextFailure.TOO_MANY_ITEMS -> if (english) {
            val selected = error.itemCount?.let { " ($it selected)" }.orEmpty()
            "You can select at most ${AiContextCodec.MAX_ITEMS} context items$selected."
        } else {
            val selected = error.itemCount?.let { "（当前选择 $it 项）" }.orEmpty()
            "一次最多选择 ${AiContextCodec.MAX_ITEMS} 条上下文$selected。"
        }

        AiContextFailure.ITEM_TOO_LARGE -> if (english) {
            val subject = title?.let { "“$it”" } ?: "One selected item"
            val size = measuredSize?.let { " is $it after encoding and" }.orEmpty()
            "$subject$size exceeds the 64 KiB per-item limit. It will not be truncated or sent."
        } else {
            val subject = title?.let { "“$it”" } ?: "有一条所选内容"
            val size = measuredSize?.let { "编码后为 $it，" }.orEmpty()
            "$subject${size}超过单条 64 KiB 上限；不会截断或发送。"
        }

        AiContextFailure.TOTAL_TOO_LARGE -> if (english) {
            val size = measuredSize?.let { " ($it after encoding)" }.orEmpty()
            "The selected context$size exceeds the 256 KiB total limit. Remove some items and try again."
        } else {
            val size = measuredSize?.let { "（编码后为 $it）" }.orEmpty()
            "所选上下文${size}超过 256 KiB 总上限；请减少条目后重试。"
        }

        AiContextFailure.SOURCE_UNAVAILABLE -> if (english) {
            "The selected context could not be read. It may have been deleted, its directory permission may have expired, or it may currently be unavailable."
        } else {
            "无法读取所选上下文；它可能已被删除、目录授权已失效，或当前不可访问。"
        }

        AiContextFailure.INVALID_TEXT_ENCODING -> if (english) {
            val subject = title?.let { "“$it”" } ?: "The selected diary"
            "$subject is not valid UTF-8 text and cannot be imported."
        } else {
            val subject = title?.let { "“$it”" } ?: "所选日记"
            "$subject 不是有效的 UTF-8 文本，无法导入。"
        }

        AiContextFailure.INVALID_SNAPSHOT -> if (english) {
            "The saved context snapshot is invalid or uses an unsupported version."
        } else {
            "已保存的上下文快照无效或版本不受支持。"
        }
    }
}

internal fun contextCandidateLoadFailureMessage(language: AppLanguage): String =
    if (language == AppLanguage.ENGLISH) {
        "Could not load importable context. Please try again."
    } else {
        "无法读取可导入的上下文，请稍后重试。"
    }

private fun formatContextKiB(bytes: Int): String {
    val whole = bytes / 1024
    val decimal = bytes % 1024 * 10 / 1024
    return "$whole.$decimal KiB"
}
