package com.deskcubby.app.plugin.adapter

import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType as AppAIModelType
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.AiChatException
import com.deskcubby.app.data.repository.AiChatImage
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRepository
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.AIAgentMessageRole
import com.deskcubby.plugin.api.core.api.AIAPI
import com.deskcubby.plugin.api.core.api.AICompletion
import com.deskcubby.plugin.api.core.api.AICompletionRequest
import com.deskcubby.plugin.api.core.api.AIMessageRole
import com.deskcubby.plugin.api.core.api.AIModel
import com.deskcubby.plugin.api.core.api.AIModelType
import com.deskcubby.plugin.api.core.api.AIToolCompletion
import com.deskcubby.plugin.api.core.api.AIToolCompletionRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class AiApiAdapter @Inject constructor(
    private val repository: AiChatRepository,
    private val settingsRepository: SettingsRepository,
) : AIAPI {
    override suspend fun models(): List<AIModel> =
        settingsRepository.settings.first().aiConfigs.map(AiModelConfig::toPluginModel)

    override suspend fun complete(
        request: AICompletionRequest,
        onUpdate: ((AICompletion) -> Unit)?,
    ): AICompletion {
        val current = settingsRepository.settings.first()
        val selectedId = request.modelConfigurationId
        if (selectedId != null && current.aiConfigs.none {
                it.id == selectedId && it.type == AppAIModelType.TEXT && it.enabled
            }
        ) {
            throw PluginApiException(
                code = "AI_MODEL_UNAVAILABLE",
                message = "The requested AI text model is not available.",
            )
        }
        val settings = if (selectedId == null) current else current.copy(aiChatConfigId = selectedId)
        val messages = request.messages.mapIndexed { index, message ->
            AiChatMessage(
                id = index.toLong() + 1L,
                role = when (message.role) {
                    AIMessageRole.USER -> AiChatRole.USER
                    AIMessageRole.ASSISTANT -> AiChatRole.ASSISTANT
                    AIMessageRole.CONTEXT -> AiChatRole.CONTEXT
                },
                content = message.content,
                image = message.image?.let { image ->
                    AiChatImage(uri = image.contentUri, mimeType = image.mimeType)
                },
            )
        }
        return try {
            repository.completeWithReasoning(
                settings = settings,
                messages = messages,
                onUpdate = onUpdate?.let { callback ->
                    { update -> callback(AICompletion(update.content, update.reasoning)) }
                },
            ).let { AICompletion(it.content, it.reasoning) }
        } catch (error: AiChatException) {
            throw PluginApiException(
                code = "AI_${error.failure.name}",
                message = error.message ?: "The AI request failed.",
                cause = error,
            )
        }
    }

    override suspend fun completeWithTools(
        request: AIToolCompletionRequest,
    ): AIToolCompletion {
        val current = settingsRepository.settings.first()
        val selectedId = request.modelConfigurationId ?: current.aiChatConfigId
        val config = current.aiConfigs.firstOrNull {
            it.id == selectedId && it.type == AppAIModelType.TEXT && it.enabled
        } ?: throw PluginApiException(
            code = "AI_MODEL_UNAVAILABLE",
            message = "The requested AI text model is not available.",
        )

        return try {
            if (config.supportsToolCalling) {
                repository.completeWithTools(config, request)
            } else {
                // Configurations created before Agent support commonly have tool calling disabled.
                // Preserve ordinary chat instead of turning those providers into a hard upgrade
                // failure. AgentRuntime sees zero tool calls and completes in one model round.
                val fallbackMessages = buildList {
                    var nextId = 1L
                    request.messages.forEach { message ->
                        if (message.role == AIAgentMessageRole.TOOL) return@forEach
                        val role = when (message.role) {
                            AIAgentMessageRole.USER -> AiChatRole.USER
                            AIAgentMessageRole.ASSISTANT -> AiChatRole.ASSISTANT
                            AIAgentMessageRole.TOOL -> return@forEach
                        }
                        if (message.images.isEmpty()) {
                            add(AiChatMessage(id = nextId++, role = role, content = message.content))
                        } else {
                            message.images.forEachIndexed { imageIndex, image ->
                                add(
                                    AiChatMessage(
                                        id = nextId++,
                                        role = role,
                                        content = if (imageIndex == 0) message.content else "",
                                        image = AiChatImage(image.contentUri, image.mimeType),
                                    ),
                                )
                            }
                        }
                    }
                }
                val fallbackSettings = current.copy(
                    aiChatConfigId = config.id,
                    aiSystemPrompt = request.systemPrompt,
                    aiConfigs = current.aiConfigs.map { candidate ->
                        if (candidate.id == config.id) candidate.copy(systemPrompt = request.systemPrompt)
                        else candidate
                    },
                )
                repository.completeWithReasoning(
                    settings = fallbackSettings,
                    messages = fallbackMessages,
                ).let { completion ->
                    AIToolCompletion(
                        content = completion.content,
                        reasoning = completion.reasoning,
                        toolCalls = emptyList(),
                    )
                }
            }
        } catch (error: AiChatException) {
            throw PluginApiException(
                code = "AI_${error.failure.name}",
                message = error.message ?: "The AI request failed.",
                cause = error,
            )
        }
    }
}

private fun AiModelConfig.toPluginModel(): AIModel = AIModel(
    id = id,
    name = name,
    type = when (type) {
        AppAIModelType.TEXT -> AIModelType.TEXT
        AppAIModelType.IMAGE -> AIModelType.IMAGE
    },
    model = model,
    enabled = enabled,
    supportsToolCalling = supportsToolCalling,
)
