package com.deskcubby.app.data.taskqueue

import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable input snapshots for background AI tasks. Every payload is stored as JSON in the
 * `ai_task_queue` row so a worker restart can rebuild the work without relying on in-memory
 * ViewModel state or live SettingsRepository state (the user may change settings mid-flight).
 */

sealed interface AiTaskPayload {
    fun encode(): String
}

/** Result of submitting a day-scoped calorie task, mirroring the old in-memory queue semantics. */
enum class CalorieEnqueueOutcome {
    ADDED,
    UPGRADED,
    DUPLICATE,
}

data class CalorieDayTaskPayload(
    val dateIso: String,
    val photos: List<CaloriePhotoSnapshot>,
    val dayPhotoCount: Int,
    val force: Boolean,
    val noteOverride: String?,
    val fallbackNote: String,
    val clearManualTotalOnSave: Boolean,
    /** Existing manual override captured at enqueue time, preserved on a force recalculation. */
    val existingTotalEnergyKjOverride: Int?,
    val settings: AppSettings,
) : AiTaskPayload {
    override fun encode(): String = JSONObject()
        .put("dateIso", dateIso)
        .put(
            "photos",
            JSONArray().apply {
                photos.forEach { photo -> put(photo.toJson()) }
            },
        )
        .put("dayPhotoCount", dayPhotoCount)
        .put("force", force)
        .put("noteOverride", noteOverride ?: JSONObject.NULL)
        .put("fallbackNote", fallbackNote)
        .put("clearManualTotalOnSave", clearManualTotalOnSave)
        .put(
            "existingTotalEnergyKjOverride",
            existingTotalEnergyKjOverride ?: JSONObject.NULL,
        )
        .put("settings", AppSettingsCodec.encode(settings))
        .toString()

    companion object {
        fun decode(json: String): CalorieDayTaskPayload {
            val root = JSONObject(json)
            val photos = buildList {
                val array = root.getJSONArray("photos")
                for (index in 0 until array.length()) {
                    add(CaloriePhotoSnapshot.decode(array.getJSONObject(index)))
                }
            }
            return CalorieDayTaskPayload(
                dateIso = root.getString("dateIso"),
                photos = photos,
                dayPhotoCount = root.optInt("dayPhotoCount"),
                force = root.optBoolean("force"),
                noteOverride = root.opt("noteOverride").takeUnless { it == JSONObject.NULL }
                    ?.toString(),
                fallbackNote = root.optString("fallbackNote"),
                clearManualTotalOnSave = root.optBoolean("clearManualTotalOnSave"),
                existingTotalEnergyKjOverride = root.opt("existingTotalEnergyKjOverride")
                    .takeUnless { it == JSONObject.NULL }
                    ?.let { (it as? Number)?.toInt() },
                settings = AppSettingsCodec.decode(root.getJSONObject("settings")),
            )
        }
    }
}

data class CaloriePhotoSnapshot(
    val uri: String,
    val caption: String,
    val fileName: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri)
        .put("caption", caption)
        .put("fileName", fileName)

    fun encode(): String = toJson().toString()

    companion object {
        fun decode(json: JSONObject): CaloriePhotoSnapshot = CaloriePhotoSnapshot(
            uri = json.getString("uri"),
            caption = json.optString("caption"),
            fileName = json.optString("fileName"),
        )
    }
}

data class CalorieSingleTaskPayload(
    val uri: String,
    val fileName: String,
    val settings: AppSettings,
) : AiTaskPayload {
    override fun encode(): String = JSONObject()
        .put("uri", uri)
        .put("fileName", fileName)
        .put("settings", AppSettingsCodec.encode(settings))
        .toString()

    companion object {
        fun decode(json: String): CalorieSingleTaskPayload {
            val root = JSONObject(json)
            return CalorieSingleTaskPayload(
                uri = root.getString("uri"),
                fileName = root.optString("fileName"),
                settings = AppSettingsCodec.decode(root.getJSONObject("settings")),
            )
        }
    }
}

data class AgentRunTaskPayload(
    val conversationId: Long,
    val runId: String,
    val conversationTitle: String,
    val userRequest: String,
    val modelConfigId: String,
    val customModelInstructions: String,
    val allowedSources: Set<String>,
    val permissionMode: AgentPermissionMode,
    val english: Boolean,
) : AiTaskPayload {
    override fun encode(): String = JSONObject()
        .put("conversationId", conversationId)
        .put("runId", runId)
        .put("conversationTitle", conversationTitle)
        .put("userRequest", userRequest)
        .put("modelConfigId", modelConfigId)
        .put("customModelInstructions", customModelInstructions)
        .put(
            "allowedSources",
            JSONArray().apply {
                allowedSources.sorted().forEach { put(it) }
            },
        )
        .put("permissionMode", permissionMode.name)
        .put("english", english)
        .toString()

    companion object {
        fun decode(json: String): AgentRunTaskPayload {
            val root = JSONObject(json)
            return AgentRunTaskPayload(
                conversationId = root.getLong("conversationId"),
                runId = root.getString("runId"),
                conversationTitle = root.optString("conversationTitle"),
                userRequest = root.optString("userRequest"),
                modelConfigId = root.optString("modelConfigId"),
                customModelInstructions = root.optString("customModelInstructions"),
                allowedSources = buildSet {
                    val array = root.optJSONArray("allowedSources")
                    if (array != null) {
                        for (index in 0 until array.length()) add(array.getString(index))
                    }
                },
                permissionMode = runCatching {
                    AgentPermissionMode.valueOf(root.getString("permissionMode"))
                }.getOrDefault(AgentPermissionMode.REQUIRE_APPROVAL),
                english = root.optBoolean("english"),
            )
        }
    }
}

/**
 * User-visible progress written by the worker into a CALORIE_* task's `progressJson`. The
 * progress screen maps this (plus the task's terminal state) back to
 * [com.deskcubby.app.ui.diary.CalorieEstimationDayProgress].
 */
data class CalorieTaskProgress(
    val stage: String,
    val selectedPhotoCount: Int = 0,
    val dayPhotoCount: Int = 0,
    val force: Boolean = false,
    val completedPhotoCount: Int = 0,
    val activePhotoCount: Int = 0,
) {
    fun encode(): String = JSONObject()
        .put("stage", stage)
        .put("selectedPhotoCount", selectedPhotoCount)
        .put("dayPhotoCount", dayPhotoCount)
        .put("force", force)
        .put("completedPhotoCount", completedPhotoCount)
        .put("activePhotoCount", activePhotoCount)
        .toString()

    companion object {
        fun decode(json: String?): CalorieTaskProgress? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(json)
                CalorieTaskProgress(
                    stage = root.optString("stage"),
                    selectedPhotoCount = root.optInt("selectedPhotoCount"),
                    dayPhotoCount = root.optInt("dayPhotoCount"),
                    force = root.optBoolean("force"),
                    completedPhotoCount = root.optInt("completedPhotoCount"),
                    activePhotoCount = root.optInt("activePhotoCount"),
                )
            }.getOrNull()
        }
    }
}

/**
 * Encodes only the AI-relevant settings a background worker needs. This keeps the stored payload
 * small and free of unrelated fields and defaults, while preserving the exact model config
 * (including per-config API keys and HTTP-insecure allowance) so the request behaves identically
 * regardless of whatever the user changed after enqueue time.
 */
internal object AppSettingsCodec {
    fun encode(settings: AppSettings): JSONObject = JSONObject()
        .put("diaryTreeUri", settings.diaryTreeUri ?: JSONObject.NULL)
        .put("mediaTreeUri", settings.mediaTreeUri ?: JSONObject.NULL)
        .put("calorieImageConfigId", settings.calorieImageConfigId ?: JSONObject.NULL)
        .put("calorieTextConfigId", settings.calorieTextConfigId ?: JSONObject.NULL)
        .put("calorieVisionPrompt", settings.calorieVisionPrompt)
        .put("calorieTextPrompt", settings.calorieTextPrompt)
        .put("aiChatConfigId", settings.aiChatConfigId ?: JSONObject.NULL)
        .put("aiSystemPrompt", settings.aiSystemPrompt)
        .put("aiTemperature", settings.aiTemperature.toDouble())
        .put(
            "aiConfigs",
            JSONArray().apply {
                settings.aiConfigs.forEach { config ->
                    put(
                        JSONObject()
                            .put("id", config.id)
                            .put("name", config.name)
                            .put("type", config.type.name)
                            .put("endpointUrl", config.endpointUrl)
                            .put("model", config.model)
                            .put("enabled", config.enabled)
                            .put("allowInsecureHttp", config.allowInsecureHttp)
                            .put("temperature", config.temperature.toDouble())
                            .put("systemPrompt", config.systemPrompt)
                            .put("apiKey", config.apiKey)
                            .put("supportsToolCalling", config.supportsToolCalling),
                    )
                }
            },
        )

    fun decode(root: JSONObject): AppSettings {
        val base = AppSettings()
        val aiConfigs = buildList {
            val array = root.optJSONArray("aiConfigs")
            if (array != null) {
                for (index in 0 until array.length()) {
                    array.getJSONObject(index).let { item ->
                        add(
                            AiModelConfig(
                                id = item.getString("id"),
                                name = item.optString("name"),
                                type = runCatching {
                                    AiModelType.valueOf(item.getString("type"))
                                }.getOrDefault(AiModelType.TEXT),
                                endpointUrl = item.optString("endpointUrl"),
                                model = item.optString("model"),
                                enabled = item.optBoolean("enabled", true),
                                allowInsecureHttp = item.optBoolean("allowInsecureHttp"),
                                temperature = item.optDouble("temperature", 0.7).toFloat()
                                    .coerceIn(0f, 2f),
                                systemPrompt = item.optString("systemPrompt"),
                                apiKey = item.optString("apiKey"),
                                supportsToolCalling = item.optBoolean("supportsToolCalling"),
                            ),
                        )
                    }
                }
            }
        }
        return base.copy(
            diaryTreeUri = root.opt("diaryTreeUri").takeUnless { it == JSONObject.NULL }?.toString(),
            mediaTreeUri = root.opt("mediaTreeUri").takeUnless { it == JSONObject.NULL }?.toString(),
            calorieImageConfigId = root.opt("calorieImageConfigId")
                .takeUnless { it == JSONObject.NULL }?.toString(),
            calorieTextConfigId = root.opt("calorieTextConfigId")
                .takeUnless { it == JSONObject.NULL }?.toString(),
            calorieVisionPrompt = root.optString("calorieVisionPrompt"),
            calorieTextPrompt = root.optString("calorieTextPrompt"),
            aiChatConfigId = root.opt("aiChatConfigId").takeUnless { it == JSONObject.NULL }
                ?.toString(),
            aiSystemPrompt = root.optString("aiSystemPrompt"),
            aiTemperature = root.optDouble("aiTemperature", 0.7).toFloat().coerceIn(0f, 2f),
            aiConfigs = aiConfigs,
        )
    }
}
