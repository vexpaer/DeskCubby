package com.deskcubby.app.data.taskqueue

import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTaskPayloadTest {

    private fun settings() = AppSettings().copy(
        diaryTreeUri = "content://diary",
        mediaTreeUri = "content://media",
        calorieImageConfigId = "vision-config",
        calorieTextConfigId = "text-config",
        calorieVisionPrompt = "识别图片",
        calorieTextPrompt = "估算热量",
        aiChatConfigId = "chat-config",
        aiSystemPrompt = "你是助手",
        aiTemperature = 0.9f,
        aiConfigs = listOf(
            AiModelConfig(
                id = "vision-config",
                name = "视觉",
                type = AiModelType.IMAGE,
                endpointUrl = "https://vision.example.com/v1",
                model = "vision-model",
                apiKey = "secret-vision",
            ),
            AiModelConfig(
                id = "text-config",
                name = "文字",
                type = AiModelType.TEXT,
                endpointUrl = "https://text.example.com/v1",
                model = "text-model",
                supportsToolCalling = true,
                apiKey = "secret-text",
            ),
        ),
    )

    @Test
    fun calorieDayRoundTrip() {
        val original = CalorieDayTaskPayload(
            dateIso = "2026-08-18",
            photos = listOf(
                CaloriePhotoSnapshot(
                    uri = "content://media/1.jpg",
                    caption = "早餐",
                    fileName = "2026-08-18_breakfast_1.jpg",
                ),
            ),
            dayPhotoCount = 1,
            force = true,
            noteOverride = "加班餐",
            fallbackNote = "早餐",
            clearManualTotalOnSave = false,
            existingTotalEnergyKjOverride = 1200,
            settings = settings(),
        )
        val decoded = CalorieDayTaskPayload.decode(original.encode())

        assertEquals(original.dateIso, decoded.dateIso)
        assertEquals(original.photos.size, decoded.photos.size)
        assertEquals(original.photos[0].uri, decoded.photos[0].uri)
        assertEquals(original.photos[0].caption, decoded.photos[0].caption)
        assertEquals(original.photos[0].fileName, decoded.photos[0].fileName)
        assertEquals(original.force, decoded.force)
        assertEquals(original.noteOverride, decoded.noteOverride)
        assertEquals(original.fallbackNote, decoded.fallbackNote)
        assertEquals(original.existingTotalEnergyKjOverride, decoded.existingTotalEnergyKjOverride)
        assertEquals(original.settings.mediaTreeUri, decoded.settings.mediaTreeUri)
        assertEquals(original.settings.calorieVisionPrompt, decoded.settings.calorieVisionPrompt)
        assertEquals(original.settings.aiTemperature, decoded.settings.aiTemperature)
        // Secrets must NOT survive the codec: the payload never snapshots API keys (they are
        // hydrated from the live settings at execution time), so a leaked task-history row can
        // never contain a key.
        assertEquals("", decoded.settings.aiConfigs[0].apiKey)
        assertEquals("", decoded.settings.aiConfigs[1].apiKey)
        assertEquals("vision-config", decoded.settings.aiConfigs[0].id)
        assertEquals("https://vision.example.com/v1", decoded.settings.aiConfigs[0].endpointUrl)
        assertEquals("vision-model", decoded.settings.aiConfigs[0].model)
    }

    @Test
    fun calorieSingleRoundTrip() {
        val original = CalorieSingleTaskPayload(
            uri = "content://media/2.jpg",
            fileName = "2026-08-18_lunch_2.jpg",
            settings = settings(),
        )
        val decoded = CalorieSingleTaskPayload.decode(original.encode())
        assertEquals(original.uri, decoded.uri)
        assertEquals(original.fileName, decoded.fileName)
        assertEquals(original.settings.calorieTextConfigId, decoded.settings.calorieTextConfigId)
    }

    @Test
    fun agentRunRoundTrip() {
        val original = AgentRunTaskPayload(
            conversationId = 42,
            runId = "run-abc",
            conversationTitle = "测试对话",
            userRequest = "帮我整理",
            modelConfigId = "text-config",
            customModelInstructions = "保持简洁",
            allowedSources = setOf("diary", "thoughts"),
            permissionMode = AgentPermissionMode.REQUIRE_APPROVAL,
            english = false,
        )
        val decoded = AgentRunTaskPayload.decode(original.encode())
        assertEquals(original.conversationId, decoded.conversationId)
        assertEquals(original.runId, decoded.runId)
        assertEquals(original.conversationTitle, decoded.conversationTitle)
        assertEquals(original.userRequest, decoded.userRequest)
        assertEquals(original.allowedSources, decoded.allowedSources)
        assertEquals(original.permissionMode, decoded.permissionMode)
        assertEquals(original.customModelInstructions, decoded.customModelInstructions)
        assertEquals(false, decoded.english)
    }

    @Test
    fun missingValuesUseDefaults() {
        // A legacy payload without the optional fields must decode safely.
        val json = """{"conversationId":7,"runId":"run-x"}"""
        val decoded = AgentRunTaskPayload.decode(json)
        assertEquals(7L, decoded.conversationId)
        assertEquals("run-x", decoded.runId)
        assertEquals(AgentPermissionMode.REQUIRE_APPROVAL, decoded.permissionMode)
        assertTrue(decoded.allowedSources.isEmpty())
    }
}
