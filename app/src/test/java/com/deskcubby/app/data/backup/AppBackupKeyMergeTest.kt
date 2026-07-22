package com.deskcubby.app.data.backup

import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBackupKeyMergeTest {
    private val endpoint = "https://example.com/v1/chat/completions"

    @Test
    fun versionElevenPreservesKeyOnlyForMatchingIdAndEndpoint() {
        val local = AppSettings(
            aiConfigs = listOf(config("same", endpoint, apiKey = "local-key")),
        )

        val matching = mergeLegacyBackupAiApiKeys(
            imported = AppSettings(aiConfigs = listOf(config("same", endpoint))),
            current = local,
            formatVersion = 11,
        )
        val changedEndpoint = mergeLegacyBackupAiApiKeys(
            imported = AppSettings(aiConfigs = listOf(config("same", "https://other.example/v1/chat/completions"))),
            current = local,
            formatVersion = 11,
        )

        assertEquals("local-key", matching.aiConfigs.single().apiKey)
        assertEquals("", changedEndpoint.aiConfigs.single().apiKey)
    }

    @Test
    fun versionTwelveUsesBackupKeyIncludingAnExplicitBlank() {
        val local = AppSettings(aiConfigs = listOf(config("same", endpoint, apiKey = "local-key")))
        val imported = AppSettings(aiConfigs = listOf(config("same", endpoint, apiKey = "backup-key")))
        val importedBlank = AppSettings(aiConfigs = listOf(config("same", endpoint, apiKey = "")))

        assertEquals(
            "backup-key",
            mergeLegacyBackupAiApiKeys(imported, local, 12).aiConfigs.single().apiKey,
        )
        assertEquals(
            "",
            mergeLegacyBackupAiApiKeys(importedBlank, local, 12).aiConfigs.single().apiKey,
        )
    }

    @Test
    fun versionNineSynthesizesLegacyConfigurationAndPreservesItsMatchingKey() {
        val local = AppSettings(
            aiConfigs = listOf(config("legacy-text", endpoint, apiKey = "legacy-key")),
        )
        val imported = AppSettings(
            aiEndpointUrl = endpoint,
            aiModel = "legacy-model",
            aiSystemPrompt = "legacy prompt",
        )

        val merged = mergeLegacyBackupAiApiKeys(imported, local, 9)

        assertEquals("legacy-text", merged.aiConfigs.single().id)
        assertEquals("legacy-key", merged.aiConfigs.single().apiKey)
        assertEquals("legacy-model", merged.aiConfigs.single().model)
    }

    private fun config(id: String, url: String, apiKey: String = "") = AiModelConfig(
        id = id,
        name = id,
        type = AiModelType.TEXT,
        endpointUrl = url,
        model = "model",
        apiKey = apiKey,
    )
}
