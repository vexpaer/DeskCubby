package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.AgentDataSource
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.CustomThemeBaseStyle
import com.deskcubby.app.data.model.CustomThemePalette
import com.deskcubby.app.data.model.CustomThemeSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.Game2048AnimationSpeed
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.model.PoetryTextAlignment
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.VisualStyle
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal object GlobalSettingsSyncCodec {
    private const val FORMAT = "deskcubby-global-settings"
    private const val VERSION = 1
    const val MAX_BYTES = 1024 * 1024

    private val KNOWN_KEYS = setOf(
        "visualStyle", "customTheme", "darkMode", "appLanguage", "userName", "homeGreetings",
        "themeColorArgb", "themeSecondaryColorsArgb", "tutorialModeEnabled",
        "fileNamePattern", "markdownTemplate", "imageNamePattern", "imageMaxWidthDp",
        "imageMaxHeightDp", "markdownHeadingSizesSp", "mealImageCompressionEnabled",
        "mealImageCompressionQuality", "saveOriginalToGallery", "photoLocationEnabled",
        "browserHomeUrl", "browserTheme", "browserDesktopMode", "thoughtDisplayMode",
        "thoughtHighlightColorArgb", "thoughtEditorMaxHeightDp", "poetryShowSource",
        "poetryShowQuoteMark", "poetrySevenCharacterWrapEnabled",
        "mealCalendarImageMaxHeightDp", "mealCalendarShowCaptions", "mealCalendarWrapEnabled",
        "mealCalendarPhotosPerRow", "mealPhotoFilter", "mealButtonsUseIcons", "mealButtonIcons",
        "dailyEventTemplates", "rssMaxItemsPerFeed", "rssShowSummaries", "aiEndpointUrl",
        "aiModel", "aiSystemPrompt", "aiTemperature", "aiAllowInsecureHttp", "aiConfigs",
        "aiChatConfigId", "agentEnabledSources", "agentPermissionMode", "agentPrompt",
        "calorieEstimationEnabled", "calorieTextConfigId", "calorieImageConfigId",
        "calorieVisionPrompt", "calorieTextPrompt", "game2048AnimationSpeed",
    )

    fun encode(settings: AppSettings): ByteArray {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("visualStyle", settings.visualStyle.name)
            .put("customTheme", encodeCustomTheme(settings.customTheme))
            .put("darkMode", settings.darkMode.name)
            .put("appLanguage", settings.appLanguage.name)
            .put("userName", settings.userName)
            .put("homeGreetings", JSONArray().apply {
                settings.homeGreetings.forEach {
                    put(JSONObject().put("chinese", it.chinese).put("english", it.english))
                }
            })
            .put("themeColorArgb", settings.themeColorArgb)
            .put("themeSecondaryColorsArgb", JSONArray().apply { settings.themeSecondaryColorsArgb.forEach { put(it) } })
            .put("tutorialModeEnabled", settings.tutorialModeEnabled)
            .put("fileNamePattern", settings.fileNamePattern)
            .put("markdownTemplate", settings.markdownTemplate)
            .put("imageNamePattern", settings.imageNamePattern)
            .put("imageMaxWidthDp", settings.imageMaxWidthDp)
            .put("imageMaxHeightDp", settings.imageMaxHeightDp)
            .put("markdownHeadingSizesSp", JSONArray().apply { settings.markdownHeadingSizesSp.forEach { put(it.toDouble()) } })
            .put("mealImageCompressionEnabled", settings.mealImageCompressionEnabled)
            .put("mealImageCompressionQuality", settings.mealImageCompressionQuality)
            .put("saveOriginalToGallery", settings.saveOriginalToGallery)
            .put("photoLocationEnabled", settings.photoLocationEnabled)
            .put("browserHomeUrl", settings.browserHomeUrl)
            .put("browserTheme", settings.browserTheme.name)
            .put("browserDesktopMode", settings.browserDesktopMode)
            .put("thoughtDisplayMode", settings.thoughtDisplayMode.name)
            .put("thoughtHighlightColorArgb", settings.thoughtHighlightColorArgb)
            .put("thoughtEditorMaxHeightDp", settings.thoughtEditorMaxHeightDp)
            .put("poetryShowSource", settings.poetryShowSource)
            .put("poetryShowQuoteMark", settings.poetryShowQuoteMark)
            .put("poetrySevenCharacterWrapEnabled", settings.poetrySevenCharacterWrapEnabled)
            .put("mealCalendarImageMaxHeightDp", settings.mealCalendarImageMaxHeightDp)
            .put("mealCalendarShowCaptions", settings.mealCalendarShowCaptions)
            .put("mealCalendarWrapEnabled", settings.mealCalendarWrapEnabled)
            .put("mealCalendarPhotosPerRow", settings.mealCalendarPhotosPerRow.name)
            .put("mealPhotoFilter", encodeMealFilter(settings.mealPhotoFilter))
            .put("mealButtonsUseIcons", settings.mealButtonsUseIcons)
            .put("mealButtonIcons", JSONArray().apply { settings.mealButtonIcons.forEach { put(it) } })
            .put("dailyEventTemplates", JSONArray().apply {
                settings.dailyEventTemplates.forEach {
                    put(JSONObject().put("id", it.id).put("text", it.text).put("firstUnit", it.firstUnit).put("secondUnit", it.secondUnit))
                }
            })
            .put("rssMaxItemsPerFeed", settings.rssMaxItemsPerFeed)
            .put("rssShowSummaries", settings.rssShowSummaries)
            .put("aiEndpointUrl", settings.aiEndpointUrl)
            .put("aiModel", settings.aiModel)
            .put("aiSystemPrompt", settings.aiSystemPrompt)
            .put("aiTemperature", settings.aiTemperature.toDouble())
            .put("aiAllowInsecureHttp", settings.aiAllowInsecureHttp)
            .put("aiConfigs", JSONArray().apply {
                settings.aiConfigs.forEach { item ->
                    // API keys are SECRET and never enter record sync.
                    put(encodeAiConfig(item.copy(apiKey = "")))
                }
            })
            .put("aiChatConfigId", settings.aiChatConfigId ?: JSONObject.NULL)
            .put("agentEnabledSources", JSONArray().apply {
                settings.agentEnabledSources.sortedBy(AgentDataSource::ordinal).forEach { put(it.wireValue) }
            })
            .put("agentPermissionMode", settings.agentPermissionMode.name)
            .put("agentPrompt", settings.agentPrompt)
            .put("calorieEstimationEnabled", settings.calorieEstimationEnabled)
            .put("calorieTextConfigId", settings.calorieTextConfigId ?: JSONObject.NULL)
            .put("calorieImageConfigId", settings.calorieImageConfigId ?: JSONObject.NULL)
            .put("calorieVisionPrompt", settings.calorieVisionPrompt)
            .put("calorieTextPrompt", settings.calorieTextPrompt)
            .put("game2048AnimationSpeed", settings.game2048AnimationSpeed.name)
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "全局设置同步数据过大。" }
        return bytes
    }

    fun apply(current: AppSettings, bytes: ByteArray): AppSettings {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        require(root.optString("format") == FORMAT && root.optInt("version") == VERSION)
        require(root.keys().asSequence().toSet() == KNOWN_KEYS + setOf("format", "version")) {
            "全局设置同步字段无效。"
        }
        val remoteConfigs = decodeAiConfigs(root.getJSONArray("aiConfigs"))
        return current.copy(
            visualStyle = VisualStyle.valueOf(root.getString("visualStyle")),
            customTheme = decodeCustomTheme(root.getJSONObject("customTheme")),
            darkMode = DarkMode.valueOf(root.getString("darkMode")),
            appLanguage = AppLanguage.valueOf(root.getString("appLanguage")),
            userName = root.getString("userName"),
            homeGreetings = decodeGreetings(root.getJSONArray("homeGreetings")),
            themeColorArgb = root.getInt("themeColorArgb"),
            themeSecondaryColorsArgb = decodeInts(root.getJSONArray("themeSecondaryColorsArgb")),
            tutorialModeEnabled = root.getBoolean("tutorialModeEnabled"),
            fileNamePattern = root.getString("fileNamePattern"),
            markdownTemplate = root.getString("markdownTemplate"),
            imageNamePattern = root.getString("imageNamePattern"),
            imageMaxWidthDp = root.getInt("imageMaxWidthDp"),
            imageMaxHeightDp = root.getInt("imageMaxHeightDp"),
            markdownHeadingSizesSp = decodeFloats(root.getJSONArray("markdownHeadingSizesSp")),
            mealImageCompressionEnabled = root.getBoolean("mealImageCompressionEnabled"),
            mealImageCompressionQuality = root.getInt("mealImageCompressionQuality"),
            saveOriginalToGallery = root.getBoolean("saveOriginalToGallery"),
            photoLocationEnabled = root.getBoolean("photoLocationEnabled"),
            browserHomeUrl = root.getString("browserHomeUrl"),
            browserTheme = BrowserTheme.valueOf(root.getString("browserTheme")),
            browserDesktopMode = root.getBoolean("browserDesktopMode"),
            thoughtDisplayMode = ThoughtDisplayMode.valueOf(root.getString("thoughtDisplayMode")),
            thoughtHighlightColorArgb = root.getInt("thoughtHighlightColorArgb"),
            thoughtEditorMaxHeightDp = root.getInt("thoughtEditorMaxHeightDp"),
            poetryShowSource = root.getBoolean("poetryShowSource"),
            poetryShowQuoteMark = root.getBoolean("poetryShowQuoteMark"),
            poetrySevenCharacterWrapEnabled = root.getBoolean("poetrySevenCharacterWrapEnabled"),
            mealCalendarImageMaxHeightDp = root.getInt("mealCalendarImageMaxHeightDp"),
            mealCalendarShowCaptions = root.getBoolean("mealCalendarShowCaptions"),
            mealCalendarWrapEnabled = root.getBoolean("mealCalendarWrapEnabled"),
            mealCalendarPhotosPerRow = MealPhotosPerRow.valueOf(root.getString("mealCalendarPhotosPerRow")),
            mealPhotoFilter = decodeMealFilter(root.getJSONObject("mealPhotoFilter")),
            mealButtonsUseIcons = root.getBoolean("mealButtonsUseIcons"),
            mealButtonIcons = root.getJSONArray("mealButtonIcons").let { array ->
                (0 until array.length()).map { array.getString(it) }
            },
            dailyEventTemplates = decodeTemplates(root.getJSONArray("dailyEventTemplates")),
            rssMaxItemsPerFeed = root.getInt("rssMaxItemsPerFeed"),
            rssShowSummaries = root.getBoolean("rssShowSummaries"),
            aiEndpointUrl = root.getString("aiEndpointUrl"),
            aiModel = root.getString("aiModel"),
            aiSystemPrompt = root.getString("aiSystemPrompt"),
            aiTemperature = root.getDouble("aiTemperature").toFloat(),
            aiAllowInsecureHttp = root.getBoolean("aiAllowInsecureHttp"),
            aiConfigs = remoteConfigs.map { remote ->
                remote.copy(apiKey = current.aiConfigs.firstOrNull { it.id == remote.id }?.apiKey.orEmpty())
            },
            aiChatConfigId = root.optionalString("aiChatConfigId"),
            agentEnabledSources = root.getJSONArray("agentEnabledSources").let { array ->
                (0 until array.length()).mapNotNull { raw ->
                    AgentDataSource.entries.firstOrNull { it.wireValue == array.getString(raw) }
                }.toSet()
            },
            agentPermissionMode = AgentPermissionMode.valueOf(root.getString("agentPermissionMode")),
            agentPrompt = root.getString("agentPrompt"),
            calorieEstimationEnabled = root.getBoolean("calorieEstimationEnabled"),
            calorieTextConfigId = root.optionalString("calorieTextConfigId"),
            calorieImageConfigId = root.optionalString("calorieImageConfigId"),
            calorieVisionPrompt = root.getString("calorieVisionPrompt"),
            calorieTextPrompt = root.getString("calorieTextPrompt"),
            game2048AnimationSpeed = Game2048AnimationSpeed.valueOf(root.getString("game2048AnimationSpeed")),
        )
    }

    private fun encodeAiConfig(item: AiModelConfig): JSONObject = JSONObject()
        .put("id", item.id).put("name", item.name).put("type", item.type.name)
        .put("endpointUrl", item.endpointUrl).put("model", item.model).put("enabled", item.enabled)
        .put("allowInsecureHttp", item.allowInsecureHttp).put("temperature", item.temperature.toDouble())
        .put("systemPrompt", item.systemPrompt).put("supportsToolCalling", item.supportsToolCalling)

    private fun decodeAiConfigs(array: JSONArray): List<AiModelConfig> =
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            AiModelConfig(
                id = item.getString("id"),
                name = item.getString("name"),
                type = AiModelType.valueOf(item.getString("type")),
                endpointUrl = item.getString("endpointUrl"),
                model = item.getString("model"),
                enabled = item.getBoolean("enabled"),
                allowInsecureHttp = item.getBoolean("allowInsecureHttp"),
                temperature = item.getDouble("temperature").toFloat(),
                systemPrompt = item.getString("systemPrompt"),
                apiKey = "",
                supportsToolCalling = item.getBoolean("supportsToolCalling"),
            )
        }

    private fun encodeCustomTheme(value: CustomThemeSettings): JSONObject = JSONObject()
        .put("baseStyle", value.baseStyle.name)
        .put("lightPalette", encodePalette(value.lightPalette))
        .put("darkPalette", encodePalette(value.darkPalette))
        .put("cornerRadiusDp", value.cornerRadiusDp.toDouble())
        .put("borderWidthDp", value.borderWidthDp.toDouble())
        .put("elevationDp", value.elevationDp.toDouble())
        .put("panelOpacity", value.panelOpacity.toDouble())
        .put("spacingScale", value.spacingScale.toDouble())
        .put("animationScale", value.animationScale.toDouble())

    private fun encodePalette(value: CustomThemePalette): JSONObject = JSONObject()
        .put("backgroundArgb", value.backgroundArgb)
        .put("onBackgroundArgb", value.onBackgroundArgb)
        .put("surfaceArgb", value.surfaceArgb)
        .put("onSurfaceArgb", value.onSurfaceArgb)
        .put("surfaceContainerArgb", value.surfaceContainerArgb)
        .put("surfaceVariantArgb", value.surfaceVariantArgb)
        .put("onSurfaceVariantArgb", value.onSurfaceVariantArgb)
        .put("outlineArgb", value.outlineArgb)

    private fun decodeCustomTheme(value: JSONObject): CustomThemeSettings = CustomThemeSettings(
        baseStyle = CustomThemeBaseStyle.valueOf(value.getString("baseStyle")),
        lightPalette = decodePalette(value.getJSONObject("lightPalette")),
        darkPalette = decodePalette(value.getJSONObject("darkPalette")),
        cornerRadiusDp = value.getDouble("cornerRadiusDp").toFloat(),
        borderWidthDp = value.getDouble("borderWidthDp").toFloat(),
        elevationDp = value.getDouble("elevationDp").toFloat(),
        panelOpacity = value.getDouble("panelOpacity").toFloat(),
        spacingScale = value.getDouble("spacingScale").toFloat(),
        animationScale = value.getDouble("animationScale").toFloat(),
    )

    private fun decodePalette(value: JSONObject): CustomThemePalette = CustomThemePalette(
        backgroundArgb = value.getInt("backgroundArgb"),
        onBackgroundArgb = value.getInt("onBackgroundArgb"),
        surfaceArgb = value.getInt("surfaceArgb"),
        onSurfaceArgb = value.getInt("onSurfaceArgb"),
        surfaceContainerArgb = value.getInt("surfaceContainerArgb"),
        surfaceVariantArgb = value.getInt("surfaceVariantArgb"),
        onSurfaceVariantArgb = value.getInt("onSurfaceVariantArgb"),
        outlineArgb = value.getInt("outlineArgb"),
    )

    private fun encodeMealFilter(value: MealPhotoFilterSettings): JSONObject = JSONObject()
        .put("enabled", value.enabled).put("brightness", value.brightness.toDouble())
        .put("contrast", value.contrast.toDouble()).put("saturation", value.saturation.toDouble())
        .put("warmth", value.warmth.toDouble()).put("tint", value.tint.toDouble())

    private fun decodeMealFilter(value: JSONObject): MealPhotoFilterSettings = MealPhotoFilterSettings(
        enabled = value.getBoolean("enabled"),
        brightness = value.getDouble("brightness").toFloat(),
        contrast = value.getDouble("contrast").toFloat(),
        saturation = value.getDouble("saturation").toFloat(),
        warmth = value.getDouble("warmth").toFloat(),
        tint = value.getDouble("tint").toFloat(),
    )

    private fun decodeGreetings(array: JSONArray): List<HomeGreetingTemplate> =
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            HomeGreetingTemplate(item.getString("chinese"), item.getString("english"))
        }

    private fun decodeTemplates(array: JSONArray): List<DailyEventTemplate> =
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            DailyEventTemplate(item.getString("id"), item.getString("text"), item.getString("firstUnit"), item.getString("secondUnit"))
        }

    private fun decodeInts(array: JSONArray): List<Int> =
        (0 until array.length()).map { array.getInt(it) }

    private fun decodeFloats(array: JSONArray): List<Float> =
        (0 until array.length()).map { array.getDouble(it).toFloat() }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)
}
