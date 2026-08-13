package com.deskcubby.app.agent

import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.AIAPI
import com.deskcubby.plugin.api.core.api.AIToolCompletionRequest
import com.deskcubby.plugin.api.core.api.AIToolDefinition
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OpenAiCompatibleAgentModelClient @Inject constructor(
    private val aiApi: AIAPI,
) : AgentModelClient {
    override suspend fun complete(request: AgentModelRequest) = try {
        aiApi.completeWithTools(
            AIToolCompletionRequest(
                systemPrompt = request.systemPrompt,
                messages = request.messages,
                tools = request.tools.map { tool ->
                    AIToolDefinition(tool.name, tool.description, tool.parametersJson)
                },
                modelConfigurationId = request.modelConfigId,
            ),
        )
    } catch (error: PluginApiException) {
        throw AgentRuntimeException(error.code, error.message ?: "Agent model request failed.", error)
    }
}
