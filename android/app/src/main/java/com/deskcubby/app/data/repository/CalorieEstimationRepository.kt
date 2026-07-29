package com.deskcubby.app.data.repository

import android.content.Context
import android.net.Uri
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.ByteArrayOutputStream
import org.json.JSONObject

@Singleton
class CalorieEstimationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ai: AiChatRepository,
) {
    suspend fun estimate(imageUri: String, settings: AppSettings): Int {
        val vision = settings.aiConfigs.firstOrNull {
            it.id == settings.calorieImageConfigId && it.type == AiModelType.IMAGE
        }
            ?: error("请先在日记设置中选择图片识别模型")
        val text = settings.aiConfigs.firstOrNull {
            it.id == settings.calorieTextConfigId && it.type == AiModelType.TEXT
        }
            ?: error("请先在日记设置中选择文字模型")
        val uri = Uri.parse(imageUri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_IMAGE_BYTES) { "图片超过 8 MiB，无法估算热量；请开启饮食图片压缩" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取饮食图片")
        val mime = context.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val rawVision = ai.analyzeImage(vision, settings.calorieVisionPrompt, mime, bytes)
        val visionJson = extractObject(rawVision).also { JSONObject(it).getJSONArray("foods") }
        val answer = ai.complete(
            settings.copy(aiConfigs = listOf(text.copy(systemPrompt = "")), aiChatConfigId = text.id,
                aiSystemPrompt = settings.calorieTextPrompt),
            listOf(AiChatMessage(1, AiChatRole.USER, visionJson)),
        )
        val energy = JSONObject(extractObject(answer)).getDouble("energyKj")
        require(energy.isFinite() && energy in 1.0..1_000_000.0) { "AI 返回的热量无效" }
        return energy.toInt()
    }

    private fun extractObject(value: String): String {
        val start = value.indexOf('{')
        val end = value.lastIndexOf('}')
        require(start >= 0 && end > start) { "AI 未返回所需 JSON" }
        return value.substring(start, end + 1)
    }

    private companion object { const val MAX_IMAGE_BYTES = 8 * 1024 * 1024 }
}
