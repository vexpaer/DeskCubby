package com.deskcubby.app.data.backup

import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSecurityTest {
    @Test
    fun manualBackupExcludesApiKeysUrisAndCloudCredentials() {
        val settings = AppSettings(
            backgroundImageUri = "content://images/app-background",
            diaryTreeUri = "content://tree/diary",
            mediaTreeUri = "content://tree/media",
            notesTreeUri = "content://tree/notes",
            poetryFontUri = "content://font/poetry",
            aiConfigs = listOf(
                AiModelConfig(
                    id = "text",
                    name = "Text",
                    type = AiModelType.TEXT,
                    endpointUrl = "https://ai.example.test/v1",
                    model = "m",
                    apiKey = "sk-secret",
                ),
            ),
            cloudSyncConfigs = listOf(
                CloudSyncConfig(
                    id = "sync",
                    name = "Sync",
                    endpointUrl = "https://dav.example.test",
                    webDavPassword = "webdav-secret",
                    s3AccessKey = "access-secret",
                    s3SecretKey = "secret-secret",
                    s3SessionToken = "token-secret",
                    selectedContents = setOf(CloudSyncContent.THOUGHTS),
                ),
            ),
        )
        val backup = AppBackup(
            exportedAt = 1L,
            settings = settings,
            thoughts = emptyList(),
            favorites = emptyList(),
        )
        val json = BackupJsonCodec.encode(backup)
        listOf(
            "sk-secret",
            "webdav-secret",
            "access-secret",
            "secret-secret",
            "token-secret",
            "content://",
        ).forEach { secret -> assertFalse(json.contains(secret)) }

        val decoded = BackupJsonCodec.decode(json)
        assertEquals("", decoded.settings.aiConfigs.single().apiKey)
        assertNull(decoded.settings.diaryTreeUri)
        assertNull(decoded.settings.backgroundImageUri)
    }

    @Test
    fun sanitizedProjectionClearsLegacyAndDeviceLocalValues() {
        val settings = AppSettings(
            backupTreeUri = "content://tree/backup",
            poetryFontUri = "content://font",
            usageTrackingEnabled = true,
            stepTrackingEnabled = true,
            aiConfigs = listOf(
                AiModelConfig(
                    id = "a",
                    name = "A",
                    type = AiModelType.TEXT,
                    endpointUrl = "https://x",
                    model = "m",
                    apiKey = "key",
                ),
            ),
            cloudSyncConfigs = listOf(
                CloudSyncConfig(
                    id = "c",
                    name = "C",
                    enabled = true,
                    endpointUrl = "https://x",
                    webDavPassword = "p",
                    selectedContents = setOf(CloudSyncContent.NOTES),
                ),
            ),
        )

        val sanitized = settings.sanitizedForManualBackup()

        assertNull(sanitized.backupTreeUri)
        assertNull(sanitized.poetryFontUri)
        assertFalse(sanitized.usageTrackingEnabled)
        assertFalse(sanitized.stepTrackingEnabled)
        assertEquals("", sanitized.aiConfigs.single().apiKey)
        assertFalse(sanitized.cloudSyncConfigs.single().enabled)
        assertEquals("", sanitized.cloudSyncConfigs.single().webDavPassword)
        assertTrue(CloudSyncContent.NOTES in sanitized.cloudSyncConfigs.single().selectedContents)
    }
}
