package com.deskcubby.app.ui.ai

import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentModelConfigTest {
    @Test
    fun `agent rejects selected ordinary chat configuration`() {
        val config = textConfig(supportsToolCalling = false)
        val settings = AppSettings(
            aiConfigs = listOf(config),
            aiChatConfigId = config.id,
        )

        assertNull(settings.agentModelConfig())
    }

    @Test
    fun `agent accepts selected native tool calling configuration`() {
        val config = textConfig(supportsToolCalling = true)
        val settings = AppSettings(
            aiConfigs = listOf(config),
            aiChatConfigId = config.id,
        )

        assertEquals(config, settings.agentModelConfig())
    }

    @Test
    fun `agent rejects incomplete endpoint even when tool calling is enabled`() {
        val config = textConfig(supportsToolCalling = true).copy(endpointUrl = "")
        val settings = AppSettings(
            aiConfigs = listOf(config),
            aiChatConfigId = config.id,
        )

        assertNull(settings.agentModelConfig())
    }

    private fun textConfig(supportsToolCalling: Boolean) = AiModelConfig(
        id = "agent-text",
        name = "Agent text",
        type = AiModelType.TEXT,
        endpointUrl = "https://example.com/v1/chat/completions",
        model = "tool-model",
        enabled = true,
        supportsToolCalling = supportsToolCalling,
    )
}
