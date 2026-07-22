package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiRequestJsonTest {
    @Test
    fun textBuilderKeepsMessageOrderAndOmitsBlankSystemPrompt() {
        val body = buildTextChatRequestJson(
            model = "text-model",
            temperature = Float.NaN,
            systemPrompt = "  ",
            messages = listOf(
                AiChatMessage(1, AiChatRole.USER, "你好\n世界"),
                AiChatMessage(2, AiChatRole.ASSISTANT, "回答"),
            ),
        )

        assertEquals("text-model", body.getString("model"))
        assertEquals(0.7, body.getDouble("temperature"), 0.0)
        assertFalse(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("你好\n世界", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun imageBuilderUsesTextThenImageUrlParts() {
        val body = buildImageChatRequestJson(
            model = "vision-model",
            temperature = 9f,
            prompt = "识别食物",
            imageDataUrl = "data:image/png;base64,AAAA",
        )

        assertEquals(2.0, body.getDouble("temperature"), 0.0)
        val message = body.getJSONArray("messages").getJSONObject(0)
        val content = message.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("识别食物", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,AAAA",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun previewUsesRealShapeWithoutApiKeyOrAuthorization() {
        val preview = buildAiRequestPreviewJson(
            AiModelConfig(
                id = "image",
                name = "图片",
                type = AiModelType.IMAGE,
                endpointUrl = "https://example.com/v1/chat/completions",
                model = "vision-model",
                apiKey = "SHOULD_NEVER_APPEAR",
            ),
        )

        val parsed = JSONObject(preview)
        assertEquals("vision-model", parsed.getString("model"))
        assertTrue(preview.contains("<IMAGE_PROMPT>"))
        assertTrue(preview.contains("<IMAGE_BASE64>"))
        assertFalse(preview.contains("SHOULD_NEVER_APPEAR"))
        assertFalse(preview.contains("Authorization", ignoreCase = true))
        assertFalse(preview.contains("Bearer", ignoreCase = true))
    }
}
