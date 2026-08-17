package com.deskcubby.app.data.backup

import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun preVersionThirteenRestoreKeepsLocalCloudConfigsButDisablesSync() {
        val localConfig = cloudConfig(
            id = "local",
            password = "device-password",
        )
        val importedConfig = cloudConfig(id = "imported")

        val merged = mergeBackupCloudSyncSettings(
            imported = AppSettings(
                cloudSyncEnabled = true,
                cloudSyncConfigs = listOf(importedConfig),
            ),
            current = AppSettings(
                cloudSyncEnabled = true,
                cloudSyncConfigs = listOf(localConfig),
            ),
            formatVersion = 12,
        )

        assertFalse(merged.cloudSyncEnabled)
        assertEquals(listOf(localConfig), merged.cloudSyncConfigs)
    }

    @Test
    fun versionThirteenRestoreUsesImportedMetadataWithoutLocalCredentials() {
        val localConfig = cloudConfig(
            id = "shared",
            password = "device-password",
        )
        val importedMetadata = cloudConfig(
            id = "shared",
            password = "",
        ).copy(
            name = "Imported metadata",
            selectedContents = setOf(CloudSyncContent.NOTES),
        )

        val merged = mergeBackupCloudSyncSettings(
            imported = AppSettings(
                cloudSyncEnabled = true,
                cloudSyncConfigs = listOf(importedMetadata),
            ),
            current = AppSettings(
                cloudSyncEnabled = true,
                cloudSyncConfigs = listOf(localConfig),
            ),
            formatVersion = 13,
        )

        assertFalse(merged.cloudSyncEnabled)
        assertEquals(listOf(importedMetadata), merged.cloudSyncConfigs)
        assertEquals("", merged.cloudSyncConfigs.single().webDavPassword)
    }

    private fun config(id: String, url: String, apiKey: String = "") = AiModelConfig(
        id = id,
        name = id,
        type = AiModelType.TEXT,
        endpointUrl = url,
        model = "model",
        apiKey = apiKey,
    )

    private fun cloudConfig(
        id: String,
        password: String = "",
    ) = CloudSyncConfig(
        id = id,
        name = id,
        endpointUrl = "https://cloud.example.com/dav",
        webDavUsername = "alice",
        webDavPassword = password,
    )
}
