package com.deskcubby.app.data.backup

import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.codePointLength
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.local.GameStatisticEntity
import com.deskcubby.app.data.local.PoetryCategoryEntity
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.local.VaultItemEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.DEFAULT_CLOUD_SYNC_USER_AGENT
import com.deskcubby.app.data.model.DEFAULT_DESKTOP_WIDGET_CONFIGS
import com.deskcubby.app.data.model.DESKTOP_WIDGET_HOME_MODULE_IDS
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.HOME_GAME_SHORTCUT_IDS
import com.deskcubby.app.data.model.Game2048AnimationSpeed
import com.deskcubby.app.data.model.LauncherIcon
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.PoetryTextAlignment
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.normalizeMarkdownHeadingSizes
import com.deskcubby.app.data.model.normalizeHomeGameShortcutIds
import com.deskcubby.app.data.model.normalizeMorePageOrder
import com.deskcubby.app.data.model.MAX_APP_FONT_SCALE
import com.deskcubby.app.data.model.MAX_APP_BACKGROUND_BLUR_DP
import com.deskcubby.app.data.model.MAX_APP_BACKGROUND_OPACITY
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.data.model.MAX_MARKDOWN_HEADING_SIZE_SP
import com.deskcubby.app.data.model.MAX_POETRY_FONT_SIZE_SP
import com.deskcubby.app.data.model.MAX_POETRY_LINE_SPACING
import com.deskcubby.app.data.model.MAX_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.model.MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MAX_VAULT_ROW_HEIGHT_DP
import com.deskcubby.app.data.model.MIN_APP_FONT_SCALE
import com.deskcubby.app.data.model.MIN_APP_BACKGROUND_BLUR_DP
import com.deskcubby.app.data.model.MIN_APP_BACKGROUND_OPACITY
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.data.model.MIN_MARKDOWN_HEADING_SIZE_SP
import com.deskcubby.app.data.model.MIN_POETRY_FONT_SIZE_SP
import com.deskcubby.app.data.model.MIN_POETRY_LINE_SPACING
import com.deskcubby.app.data.model.MIN_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.model.MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MIN_VAULT_ROW_HEIGHT_DP
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.data.preferences.migrateMealPhotosWidget
import com.deskcubby.app.data.preferences.migrateDailyRecordsWidget
import com.deskcubby.app.data.preferences.migrateHomeModulesV26
import com.deskcubby.app.data.preferences.normalizeThemeSecondaryColors
import com.deskcubby.app.data.preferences.normalizeNavItems
import com.deskcubby.app.data.repository.VAULT_KEY_MARKER_ENTITY_ID
import com.deskcubby.app.data.repository.VaultEncryptedBackup
import com.deskcubby.app.data.repository.VaultEncryptedKeyBackup
import com.deskcubby.app.data.statistics.MAX_USAGE_DEVICES
import com.deskcubby.app.data.statistics.GameStatisticCatalog
import com.deskcubby.app.data.statistics.UsageDeviceJsonCodec
import com.deskcubby.app.data.statistics.UsageDeviceRecord
import java.math.BigDecimal
import java.time.LocalDate
import java.net.URI
import java.util.Base64
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class AppBackup(
    val formatVersion: Int = 27,
    val exportedAt: Long,
    val settings: AppSettings,
    val thoughts: List<FlashThoughtEntity>,
    val favorites: List<BrowserRecordEntity>,
    val dateRecords: List<DateRecordEntity> = emptyList(),
    val categories: List<ThoughtCategoryEntity> = emptyList(),
    val poetryCategories: List<PoetryCategoryEntity> = emptyList(),
    val poems: List<SavedPoemEntity> = emptyList(),
    val vault: VaultEncryptedBackup = VaultEncryptedBackup(
        active = null,
        pending = null,
        items = emptyList(),
    ),
    val gameStates: List<GameStateEntity> = emptyList(),
    val gameStatistics: List<GameStatisticEntity> = emptyList(),
    val usageDevices: List<UsageDeviceRecord> = emptyList(),
)

data class BackupSummary(
    val thoughtCount: Int,
    val favoriteCount: Int,
    val exportedAt: Long,
    val dateRecordCount: Int = 0,
    val categoryCount: Int = 0,
    val poetryCategoryCount: Int = 0,
    val poemCount: Int = 0,
    val vaultItemCount: Int = 0,
    val gameStateCount: Int = 0,
    val gameStatisticCount: Int = 0,
    val usageDeviceCount: Int = 0,
    val usageDayCount: Int = 0,
)

object BackupJsonCodec {
    const val FORMAT_VERSION: Int = 27

    private const val FORMAT_NAME = "DeskCubby"
    const val MAX_JSON_BYTES = 64 * 1024 * 1024
    private const val MAX_THOUGHTS = 50_000
    private const val MAX_FAVORITES = 20_000
    private const val MAX_DATE_RECORDS = 50_000
    private const val MAX_CATEGORIES = 10_000
    private const val MAX_POETRY_CATEGORIES = 10_000
    private const val MAX_POEMS = 50_000
    private const val MAX_THOUGHT_CHARS = 1_000_000
    private const val MAX_URL_CHARS = 8_192
    private const val MAX_TITLE_CHARS = 4_096
    private const val MAX_SETTING_STRING_CHARS = 1_000_000
    private const val MAX_DATE_NAME_CHARS = 256
    private const val MAX_DATE_ICON_CHARS = 64
    private const val MAX_CATEGORY_NAME_CHARS = 40
    private const val MAX_POETRY_CATEGORY_NAME_CHARS = 100
    private const val MAX_POEM_CONTENT_CHARS = 100_000
    private const val MAX_POEM_SOURCE_CHARS = 4_096
    private const val MAX_USERNAME_CHARS = 32
    private const val MAX_HOME_GREETINGS = 100
    private const val MAX_HOME_GREETING_CHARS = 40
    private const val MAX_MEAL_BUTTON_ICON_CHARS = 16
    private const val MAX_DAILY_EVENT_TEMPLATES = 100
    private const val MAX_RSS_SUBSCRIPTIONS = 100
    private const val MAX_AI_API_KEY_CHARS = 8_192
    private const val MAX_CLOUD_SYNC_CONFIGS = 20
    private const val MAX_DESKTOP_WIDGET_CONFIGS = 50
    private const val MAX_DESKTOP_WIDGET_ID_CHARS = 80
    private const val MAX_DESKTOP_WIDGET_NAME_CHARS = 80
    private const val MAX_DESKTOP_WIDGET_APP_LABEL_CHARS = 100
    private const val MAX_DESKTOP_WIDGET_PACKAGE_CHARS = 255
    private const val MAX_MORE_DESCRIPTION_CODE_POINTS = 160
    private const val MAX_VAULT_ITEMS = 50_000
    private const val MAX_VAULT_CIPHER_CHARS = 2 * 1024 * 1024
    private const val MAX_VAULT_IV_CHARS = 128
    private const val MAX_VAULT_SALT_CHARS = 2_048
    private const val MAX_VAULT_GENERATION_CHARS = 64
    private const val MAX_GAME_STATES = 16
    private const val MAX_GAME_STATISTICS = 64
    private const val MAX_GAME_ID_CHARS = 64
    private const val MAX_GAME_SAVE_CHARS = 16 * 1024 * 1024
    private val SUPPORTED_GAME_IDS = setOf(
        "2048",
        "2048_5",
        "2048_6",
        "snake",
        "tetris",
        "minesweeper",
        "spider",
    )
    private val DESKTOP_WIDGET_PACKAGE_REGEX =
        Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val DESKTOP_WIDGET_ID_REGEX = Regex("[A-Za-z0-9._-]{1,80}")

    fun encode(backup: AppBackup): String {
        require(backup.formatVersion == FORMAT_VERSION) {
            "Unsupported backup version: ${backup.formatVersion}"
        }
        requireValidBrowserUrl(backup.settings.browserHomeUrl, "browserHomeUrl")
        backup.settings.lastBrowserUrl?.let { requireValidBrowserUrl(it, "lastBrowserUrl") }
        validateEntityKeys(
            thoughts = backup.thoughts,
            categories = backup.categories,
            poetryCategories = backup.poetryCategories,
            favorites = backup.favorites,
            dateRecords = backup.dateRecords,
            poems = backup.poems,
        )
        validateVaultBackup(backup.vault)
        validateGameStates(backup.gameStates)
        validateGameStatistics(backup.gameStatistics)
        validateUsageDevices(backup.usageDevices)

        val root = JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", backup.formatVersion)
            .put("exportedAt", backup.exportedAt)
            .put("settings", encodeSettings(backup.settings))
            .put("thoughts", encodeThoughts(backup.thoughts))
            .put("categories", encodeCategories(backup.categories))
            .put("favorites", encodeFavorites(backup.favorites))
            .put("dateRecords", encodeDateRecords(backup.dateRecords))
            .put("poetryCategories", encodePoetryCategories(backup.poetryCategories))
            .put("poems", encodePoems(backup.poems))
            .put("vault", encodeVault(backup.vault))
            .put("gameStates", encodeGameStates(backup.gameStates))
            .put("gameStatistics", encodeGameStatistics(backup.gameStatistics))
            .put("usageDevices", encodeUsageDevices(backup.usageDevices))
        return root.toString(2).also { encoded ->
            requireWithinSizeLimit(encoded)
            // Keep files produced from locally corrupted state just as strict as imported files.
            decode(encoded)
        }
    }

    fun decode(json: String): AppBackup {
        requireWithinSizeLimit(json)
        return try {
            decodeRoot(parseRoot(json))
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid backup JSON: ${error.message}", error)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Invalid backup JSON: ${error.message}", error)
        }
    }

    private fun parseRoot(json: String): JSONObject {
        val tokener = JSONTokener(json)
        val value = tokener.nextValue()
        require(value is JSONObject) { "Backup root must be a JSON object" }
        require(tokener.nextClean() == '\u0000') { "Unexpected content after backup root" }
        return value
    }

    private fun decodeRoot(root: JSONObject): AppBackup {
        val format = root.requiredString("format")
        require(format == FORMAT_NAME) { "Unsupported backup format: $format" }

        val version = root.requiredInt("version")
        require(version in 1..FORMAT_VERSION) { "Unsupported backup version: $version" }

        val exportedAt = root.requiredLong("exportedAt").also {
            require(it >= 0) { "exportedAt must not be negative" }
        }
        val settings = decodeSettings(root.requiredObject("settings"), version)
        val categories = if (version >= 3) {
            decodeCategories(root.requiredArray("categories"))
        } else {
            emptyList()
        }
        val thoughts = decodeThoughts(
            json = root.requiredArray("thoughts"),
            includeCategoryId = version >= 3,
            includeHighlighted = version >= 14,
        )
        validateCategoryReferences(thoughts, categories)
        val favorites = decodeFavorites(root.requiredArray("favorites"))
        val dateRecords = if (version >= 2) {
            decodeDateRecords(root.requiredArray("dateRecords"))
        } else {
            emptyList()
        }
        val poetryCategories = if (version >= 19) {
            decodePoetryCategories(root.requiredArray("poetryCategories"))
        } else {
            emptyList()
        }
        val poems = if (version >= 4) {
            decodePoems(
                root.requiredArray("poems"),
                includeCategoryId = version >= 19,
                includeSortOrder = version >= 21,
            )
        } else {
            emptyList()
        }
        val vault = if (version >= 20) {
            decodeVault(root.requiredObject("vault"))
        } else {
            VaultEncryptedBackup(active = null, pending = null, items = emptyList())
        }
        val gameStates = if (version >= 20) {
            decodeGameStates(root.requiredArray("gameStates"))
        } else {
            emptyList()
        }
        val gameStatistics = if (version >= 24) {
            decodeGameStatistics(root.requiredArray("gameStatistics"))
        } else {
            emptyList()
        }
        val usageDevices = if (version >= 20) {
            decodeUsageDevices(root.requiredArray("usageDevices"))
        } else {
            emptyList()
        }
        return AppBackup(
            formatVersion = version,
            exportedAt = exportedAt,
            settings = settings,
            thoughts = thoughts,
            favorites = favorites,
            dateRecords = dateRecords,
            categories = categories,
            poetryCategories = poetryCategories,
            poems = poems,
            vault = vault,
            gameStates = gameStates,
            gameStatistics = gameStatistics,
            usageDevices = usageDevices,
        ).also {
            validatePoetryCategoryReferences(it.poems, it.poetryCategories)
        }
    }

    private fun encodeSettings(settings: AppSettings): JSONObject = JSONObject()
        .put("visualStyle", settings.visualStyle.name)
        .put("darkMode", settings.darkMode.name)
        .put("appLanguage", settings.appLanguage.name)
        .put("themeColorArgb", settings.themeColorArgb)
        .put("themeSecondaryColorsArgb", settings.themeSecondaryColorsArgb.toJsonIntArray())
        .put("fontScale", settings.fontScale)
        .put("compactMode", settings.compactMode)
        .putNullable("backgroundImageUri", settings.backgroundImageUri)
        .put("backgroundImageOpacity", settings.backgroundImageOpacity)
        .put("backgroundImageBlurDp", settings.backgroundImageBlurDp)
        .put("tutorialModeEnabled", settings.tutorialModeEnabled)
        .put("useChineseLauncherName", settings.useChineseLauncherName)
        .put("launcherIcon", settings.launcherIcon.name)
        // Credentials are device-local. Imports must be explicitly re-enabled after review.
        .put("cloudSyncEnabled", false)
        .put("cloudSyncConfigs", encodeCloudSyncConfigs(settings.cloudSyncConfigs))
        .putNullable("diaryTreeUri", settings.diaryTreeUri)
        .putNullable("mediaTreeUri", settings.mediaTreeUri)
        .putNullable("notesTreeUri", settings.notesTreeUri)
        .put("fileNamePattern", settings.fileNamePattern)
        .put("markdownTemplate", settings.markdownTemplate)
        .put("imageNamePattern", settings.imageNamePattern)
        .put("imageMaxWidthDp", settings.imageMaxWidthDp)
        .put("imageMaxHeightDp", settings.imageMaxHeightDp)
        .put(
            "markdownHeadingSizesSp",
            JSONArray().apply {
                normalizeMarkdownHeadingSizes(settings.markdownHeadingSizesSp)
                    .forEach { put(it.toDouble()) }
            },
        )
        .put("mealImageCompressionEnabled", settings.mealImageCompressionEnabled)
        .put("mealImageCompressionQuality", settings.mealImageCompressionQuality)
        .put("saveOriginalToGallery", settings.saveOriginalToGallery)
        .put("photoLocationEnabled", settings.photoLocationEnabled)
        .put("browserHomeUrl", settings.browserHomeUrl)
        .putNullable("lastBrowserUrl", settings.lastBrowserUrl)
        .put("browserTheme", settings.browserTheme.name)
        .put("browserDesktopMode", settings.browserDesktopMode)
        .put("thoughtSplitRatio", settings.thoughtSplitRatio)
        .put("thoughtRowHeightDp", settings.thoughtRowHeightDp)
        .put("thoughtReopenMode", settings.thoughtReopenMode.name)
        .put("thoughtDisplayMode", settings.thoughtDisplayMode.name)
        .put("thoughtHighlightColorArgb", settings.thoughtHighlightColorArgb)
        .put("thoughtEditorMaxHeightDp", settings.thoughtEditorMaxHeightDp)
        .put("vaultRowHeightDp", settings.vaultRowHeightDp)
        .putNullable("poetryFontUri", settings.poetryFontUri)
        .put("poetryFontSizeSp", settings.poetryFontSizeSp)
        .put("poetryLineSpacing", settings.poetryLineSpacing)
        .put("poetryTextAlignment", settings.poetryTextAlignment.name)
        .put("poetryShowSource", settings.poetryShowSource)
        .put("poetryShowQuoteMark", settings.poetryShowQuoteMark)
        .put(
            "poetrySevenCharacterWrapEnabled",
            settings.poetrySevenCharacterWrapEnabled,
        )
        .put("mealCalendarImageMaxHeightDp", settings.mealCalendarImageMaxHeightDp)
        .put("mealCalendarShowCaptions", settings.mealCalendarShowCaptions)
        .put("mealCalendarWrapEnabled", settings.mealCalendarWrapEnabled)
        .put("mealCalendarPhotosPerRow", settings.mealCalendarPhotosPerRow.name)
        .put("mealPhotoFilter", JSONObject()
            .put("enabled", settings.mealPhotoFilter.enabled)
            .put("brightness", settings.mealPhotoFilter.brightness)
            .put("contrast", settings.mealPhotoFilter.contrast)
            .put("saturation", settings.mealPhotoFilter.saturation)
            .put("warmth", settings.mealPhotoFilter.warmth)
            .put("tint", settings.mealPhotoFilter.tint))
        .put("mealButtonsUseIcons", settings.mealButtonsUseIcons)
        .put("userName", settings.userName)
        .put("homeGreetings", encodeHomeGreetings(settings.homeGreetings))
        .put("homeWidgetBordersEnabled", settings.homeWidgetBordersEnabled)
        .put("mealButtonIcons", settings.mealButtonIcons.toJsonArray())
        .put("dailyEventTemplates", JSONArray().apply {
            settings.dailyEventTemplates.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("text", item.text)
                        .put("firstUnit", item.firstUnit)
                        .put("secondUnit", item.secondUnit),
                )
            }
        })
        .put("rssSubscriptions", JSONArray().apply {
            settings.rssSubscriptions.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("title", item.title)
                        .put("url", item.url)
                        .put("enabled", item.enabled),
                )
            }
        })
        .put("rssMaxItemsPerFeed", settings.rssMaxItemsPerFeed)
        .put("rssShowSummaries", settings.rssShowSummaries)
        .put("aiEndpointUrl", settings.aiEndpointUrl)
        .put("aiModel", settings.aiModel)
        .put("aiSystemPrompt", settings.aiSystemPrompt)
        .put("aiTemperature", settings.aiTemperature)
        .put("aiAllowInsecureHttp", settings.aiAllowInsecureHttp)
        .put("aiConfigs", JSONArray().apply { settings.aiConfigs.forEach { item -> put(JSONObject()
            .put("id", item.id).put("name", item.name).put("type", item.type.name)
            .put("endpointUrl", item.endpointUrl).put("model", item.model).put("enabled", item.enabled)
            .put("allowInsecureHttp", item.allowInsecureHttp).put("temperature", item.temperature)
            .put("systemPrompt", item.systemPrompt).put("apiKey", item.apiKey)) } })
        .putNullable("aiChatConfigId", settings.aiChatConfigId)
        .put("calorieEstimationEnabled", settings.calorieEstimationEnabled)
        .putNullable("calorieTextConfigId", settings.calorieTextConfigId)
        .putNullable("calorieImageConfigId", settings.calorieImageConfigId)
        .put("calorieVisionPrompt", settings.calorieVisionPrompt)
        .put("calorieTextPrompt", settings.calorieTextPrompt)
        // These are preferences only. Permission grants and the sensitive statistics files
        // remain device-local and are intentionally excluded from application JSON backups.
        .put("usageTrackingEnabled", settings.usageTrackingEnabled)
        .put("stepTrackingEnabled", settings.stepTrackingEnabled)
        .put("navItems", JSONArray().apply {
            settings.navItems.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id.name)
                        .put("label", item.label)
                        .put("iconKey", item.iconKey)
                        .put("visible", item.visible)
                        .put("showInMore", item.showInMore)
                        .put("moreDescription", item.moreDescription),
                )
            }
        })
        .put(
            "morePageOrder",
            JSONArray().apply {
                normalizeMorePageOrder(settings.morePageOrder, settings.navItems)
                    .forEach { put(it.name) }
            },
        )
        .put("defaultPage", settings.defaultPage.name)
        .put("bottomNavShowLabels", settings.bottomNavShowLabels)
        .put("musicVisualizerEnabled", settings.musicVisualizerEnabled)
        .put("musicVisualizerStyle", settings.musicVisualizerStyle.name)
        .put("musicVisualizerFrequencyMode", settings.musicVisualizerFrequencyMode.name)
        .put("musicVisualizerMinFrequencyHz", settings.musicVisualizerMinFrequencyHz)
        .put("musicVisualizerMaxFrequencyHz", settings.musicVisualizerMaxFrequencyHz)
        .put("game2048AnimationSpeed", settings.game2048AnimationSpeed.name)
        .put("morePageShowDescriptions", settings.morePageShowDescriptions)
        .put("homeWidgets", settings.homeWidgets.toJsonArray())
        .put(
            "homeGameShortcuts",
            normalizeHomeGameShortcutIds(settings.homeGameShortcuts).toJsonArray(),
        )
        .put("homeWidgetTitles", settings.homeWidgetTitles.toJsonArray())
        .put("desktopWidgetConfigs", encodeDesktopWidgetConfigs(settings.desktopWidgetConfigs))

    private fun encodeDesktopWidgetConfigs(
        configs: List<DesktopWidgetConfig>,
    ): JSONArray = JSONArray().apply {
        require(configs.size <= MAX_DESKTOP_WIDGET_CONFIGS) {
            "Too many desktop widget configurations"
        }
        val ids = HashSet<String>(configs.size)
        configs.forEachIndexed { index, item ->
            validateDesktopWidgetConfig(item, "desktopWidgetConfigs[$index]")
            require(ids.add(item.id)) { "Duplicate desktop widget configuration: ${item.id}" }
            put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("widthCells", item.widthCells)
                    .put("heightCells", item.heightCells)
                    .put("backgroundColorArgb", item.backgroundColorArgb)
                    .put("textColorArgb", item.textColorArgb)
                    .putNullable("backgroundImageUri", item.backgroundImageUri)
                    .put("contentType", item.contentType.name)
                    .put("homeModuleId", item.homeModuleId)
                    .putNullable("appPackageName", item.appPackageName)
                    .putNullable("appLabel", item.appLabel),
            )
        }
    }

    private fun decodeDesktopWidgetConfigs(json: JSONArray): List<DesktopWidgetConfig> {
        require(json.length() <= MAX_DESKTOP_WIDGET_CONFIGS) {
            "Too many desktop widget configurations"
        }
        val ids = HashSet<String>(json.length())
        return buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "desktopWidgetConfigs")
                val decoded = DesktopWidgetConfig(
                    id = item.requiredString("id"),
                    name = item.requiredString("name"),
                    widthCells = item.requiredInt("widthCells"),
                    heightCells = item.requiredInt("heightCells"),
                    backgroundColorArgb = item.requiredInt("backgroundColorArgb"),
                    textColorArgb = item.requiredInt("textColorArgb"),
                    backgroundImageUri = item.requiredNullableString("backgroundImageUri"),
                    contentType = item.requiredEnum("contentType"),
                    homeModuleId = item.requiredString("homeModuleId"),
                    appPackageName = item.requiredNullableString("appPackageName"),
                    appLabel = item.requiredNullableString("appLabel"),
                )
                validateDesktopWidgetConfig(decoded, "desktopWidgetConfigs[$index]")
                require(ids.add(decoded.id)) {
                    "Duplicate desktop widget configuration: ${decoded.id}"
                }
                add(decoded)
            }
        }
    }

    private fun validateDesktopWidgetConfig(config: DesktopWidgetConfig, field: String) {
        require(
            config.id.length <= MAX_DESKTOP_WIDGET_ID_CHARS &&
                DESKTOP_WIDGET_ID_REGEX.matches(config.id),
        ) {
            "$field.id is invalid"
        }
        require(
            config.name.isNotBlank() &&
                config.name.codePointLength() <= MAX_DESKTOP_WIDGET_NAME_CHARS,
        ) { "$field.name is invalid" }
        require(config.widthCells in MIN_DESKTOP_WIDGET_CELLS..MAX_DESKTOP_WIDGET_CELLS) {
            "$field.widthCells is out of range"
        }
        require(config.heightCells in MIN_DESKTOP_WIDGET_CELLS..MAX_DESKTOP_WIDGET_CELLS) {
            "$field.heightCells is out of range"
        }
        config.backgroundImageUri?.let { uri ->
            require(uri.length <= MAX_URL_CHARS && uri.startsWith("content://")) {
                "$field.backgroundImageUri is invalid"
            }
        }
        require(config.homeModuleId in DESKTOP_WIDGET_HOME_MODULE_IDS) {
            "$field.homeModuleId is invalid"
        }
        config.appLabel?.let {
            require(it.codePointLength() <= MAX_DESKTOP_WIDGET_APP_LABEL_CHARS) {
                "$field.appLabel is too long"
            }
        }
        if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
            val packageName = config.appPackageName.orEmpty()
            require(
                packageName.length <= MAX_DESKTOP_WIDGET_PACKAGE_CHARS &&
                    DESKTOP_WIDGET_PACKAGE_REGEX.matches(packageName),
            ) { "$field.appPackageName is invalid" }
        } else {
            config.appPackageName?.let { packageName ->
                require(
                    packageName.length <= MAX_DESKTOP_WIDGET_PACKAGE_CHARS &&
                        DESKTOP_WIDGET_PACKAGE_REGEX.matches(packageName),
                ) { "$field.appPackageName is invalid" }
            }
        }
    }

    private fun encodeCloudSyncConfigs(configs: List<CloudSyncConfig>): JSONArray = JSONArray().apply {
        require(configs.size <= MAX_CLOUD_SYNC_CONFIGS) { "Too many cloud sync configurations" }
        val ids = HashSet<String>(configs.size)
        configs.forEachIndexed { index, item ->
            require(ids.add(item.id)) { "Duplicate cloud sync configuration: ${item.id}" }
            validateCloudSyncConfigMetadata(item, "cloudSyncConfigs[$index]")
            put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("enabled", item.enabled)
                    .put("serviceType", item.serviceType.name)
                    .put("endpointUrl", item.endpointUrl)
                    .put("remotePath", item.remotePath)
                    .put("userAgent", item.userAgent)
                    .put("webDavUsername", item.webDavUsername)
                    .put("s3Bucket", item.s3Bucket)
                    .put("s3Region", item.s3Region)
                    .put("s3PathStyle", item.s3PathStyle)
                    .put("allowInsecureHttp", item.allowInsecureHttp)
                    .put("selectedContents", JSONArray().apply {
                        CloudSyncContent.entries
                            .filter(item.selectedContents::contains)
                            .forEach { put(it.name) }
                    })
                    .put("direction", item.direction.name),
            )
        }
    }

    private fun decodeCloudSyncConfigs(
        json: JSONArray,
        version: Int,
    ): List<CloudSyncConfig> = buildList {
        require(json.length() <= MAX_CLOUD_SYNC_CONFIGS) {
            "Too many cloud sync configurations"
        }
        val ids = HashSet<String>(json.length())
        for (index in 0 until json.length()) {
            val item = json.requiredObject(index, "cloudSyncConfigs")
            val contentsJson = item.requiredArray("selectedContents")
            val contents = buildSet {
                for (contentIndex in 0 until contentsJson.length()) {
                    val raw = contentsJson.get(contentIndex)
                    require(raw is String) {
                        "cloudSyncConfigs[$index].selectedContents[$contentIndex] must be a string"
                    }
                    val content = enumValues<CloudSyncContent>().firstOrNull { it.name == raw }
                        ?: throw IllegalArgumentException(
                            "Invalid CloudSyncContent value: $raw",
                        )
                    require(add(content)) {
                        "cloudSyncConfigs[$index].selectedContents contains a duplicate: $raw"
                    }
                }
            }
            val decoded = CloudSyncConfig(
                id = item.requiredString("id"),
                name = item.requiredString("name"),
                enabled = item.requiredBoolean("enabled"),
                serviceType = item.requiredEnum("serviceType"),
                endpointUrl = item.requiredString("endpointUrl"),
                remotePath = item.requiredString("remotePath"),
                userAgent = if (version >= 21) {
                    item.requiredString("userAgent")
                } else {
                    DEFAULT_CLOUD_SYNC_USER_AGENT
                },
                webDavUsername = item.requiredString("webDavUsername"),
                s3Bucket = item.requiredString("s3Bucket"),
                s3Region = item.requiredString("s3Region"),
                s3PathStyle = if (version >= 19) item.requiredBoolean("s3PathStyle") else true,
                allowInsecureHttp = item.requiredBoolean("allowInsecureHttp"),
                selectedContents = contents,
                direction = item.requiredEnum("direction"),
            )
            require(ids.add(decoded.id)) {
                "Duplicate cloud sync configuration: ${decoded.id}"
            }
            validateCloudSyncConfigMetadata(decoded, "cloudSyncConfigs[$index]")
            add(decoded)
        }
    }

    private fun validateCloudSyncConfigMetadata(config: CloudSyncConfig, field: String) {
        require(config.id.isNotBlank() && config.id.length <= 128) { "$field.id is invalid" }
        require(config.name.isNotBlank() && config.name.length <= 200) { "$field.name is invalid" }
        require(config.selectedContents.isNotEmpty()) { "$field.selectedContents must not be empty" }
        require(config.endpointUrl.length <= MAX_URL_CHARS) { "$field.endpointUrl is too long" }
        val endpoint = runCatching { URI(config.endpointUrl) }.getOrElse {
            throw IllegalArgumentException("$field.endpointUrl is invalid", it)
        }
        val scheme = endpoint.scheme?.lowercase(Locale.ROOT)
        require(
            endpoint.isAbsolute && !endpoint.host.isNullOrBlank() &&
                endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null &&
                (scheme == "https" || scheme == "http" && config.allowInsecureHttp),
        ) { "$field.endpointUrl must use HTTPS, or explicitly allowed HTTP" }
        require(config.remotePath.length <= 1_024 && !config.remotePath.contains('\\')) {
            "$field.remotePath is invalid"
        }
        require(config.remotePath.split('/').none { it == "." || it == ".." }) {
            "$field.remotePath cannot contain . or .."
        }
        require(config.userAgent.isNotBlank() && config.userAgent.length <= 512 &&
            config.userAgent.none(Char::isISOControl)) {
            "$field.userAgent is invalid"
        }
        require(config.webDavUsername.length <= 512) { "$field.webDavUsername is too long" }
        require(config.s3Bucket.length <= 255) { "$field.s3Bucket is too long" }
        require(config.s3Region.length <= 128) { "$field.s3Region is too long" }
        if (config.serviceType == CloudSyncServiceType.S3_COMPATIBLE) {
            require(config.s3Bucket.isNotBlank() &&
                config.s3Bucket.none { it == '/' || it == '\\' || it.isISOControl() }) {
                "$field.s3Bucket is invalid"
            }
            require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(config.s3Region)) {
                "$field.s3Region is invalid"
            }
        }
    }

    private fun encodeHomeGreetings(items: List<HomeGreetingTemplate>): JSONArray {
        require(items.size <= MAX_HOME_GREETINGS) { "Too many home greetings" }
        return JSONArray().apply {
            items.forEachIndexed { index, item ->
                require(item.chinese.isNotBlank() || item.english.isNotBlank()) {
                    "homeGreetings[$index] is blank"
                }
                item.chinese.requireMaxCodePoints(
                    "homeGreetings[$index].chinese",
                    MAX_HOME_GREETING_CHARS,
                )
                item.english.requireMaxCodePoints(
                    "homeGreetings[$index].english",
                    MAX_HOME_GREETING_CHARS,
                )
                put(
                    JSONObject()
                        .put("chinese", item.chinese)
                        .put("english", item.english),
                )
            }
        }
    }

    private fun decodeHomeGreetings(json: JSONArray): List<HomeGreetingTemplate> {
        require(json.length() <= MAX_HOME_GREETINGS) { "Too many home greetings" }
        return buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val chinese = item.requiredString("chinese").requireMaxCodePoints(
                    "homeGreetings[$index].chinese",
                    MAX_HOME_GREETING_CHARS,
                )
                val english = item.requiredString("english").requireMaxCodePoints(
                    "homeGreetings[$index].english",
                    MAX_HOME_GREETING_CHARS,
                )
                require(chinese.isNotBlank() || english.isNotBlank()) {
                    "homeGreetings[$index] is blank"
                }
                add(HomeGreetingTemplate(chinese = chinese, english = english))
            }
        }
    }

    private fun decodeSettings(json: JSONObject, version: Int): AppSettings {
        val defaults = AppSettings()
        val homeWidgets = migrateHomeModulesV26(
            items = migrateDailyRecordsWidget(
                items = migrateMealPhotosWidget(
                    items = json.requiredArray("homeWidgets").requiredStringList("homeWidgets"),
                    migrated = version >= 4,
                ),
                migrated = version >= 9,
            ),
            migrated = version >= 26,
        )
        val decodedTitles = json.requiredArray("homeWidgetTitles").requiredStringList("homeWidgetTitles")
        val mealMigratedTitles = if (version < 4 && "meal_photos" !in decodedTitles) {
            decodedTitles + "meal_photos"
        } else {
            decodedTitles
        }
        val homeWidgetTitles = migrateHomeModulesV26(
            items = migrateDailyRecordsWidget(
                items = mealMigratedTitles,
                migrated = version >= 9,
            ),
            migrated = version >= 26,
        )
        val homeGameShortcuts = if (version >= 27) {
            json.requiredArray("homeGameShortcuts")
                .requiredStringList("homeGameShortcuts")
                .also { items ->
                    require(items.all(HOME_GAME_SHORTCUT_IDS::contains)) {
                        "homeGameShortcuts contains an unsupported game"
                    }
                }
                .let(::normalizeHomeGameShortcutIds)
        } else {
            defaults.homeGameShortcuts
        }
        val navItems = decodeNavItems(json.requiredArray("navItems"), version)
        val musicVisualizerMinFrequencyHz = if (version >= 24) {
            json.requiredInt("musicVisualizerMinFrequencyHz").also { value ->
                require(value in 20..19_999) {
                    "musicVisualizerMinFrequencyHz is out of range"
                }
            }
        } else {
            defaults.musicVisualizerMinFrequencyHz
        }
        val musicVisualizerMaxFrequencyHz = if (version >= 24) {
            json.requiredInt("musicVisualizerMaxFrequencyHz").also { value ->
                require(value in (musicVisualizerMinFrequencyHz + 1)..20_000) {
                    "musicVisualizerMaxFrequencyHz is out of range"
                }
            }
        } else {
            defaults.musicVisualizerMaxFrequencyHz
        }
        return AppSettings(
            visualStyle = decodeVisualStyle(json, version),
            darkMode = json.requiredEnum("darkMode"),
            appLanguage = json.requiredEnum("appLanguage"),
            themeColorArgb = json.requiredInt("themeColorArgb"),
            themeSecondaryColorsArgb = if (version >= 8) {
                decodeThemeSecondaryColors(json.requiredArray("themeSecondaryColorsArgb"))
            } else {
                defaults.themeSecondaryColorsArgb
            },
            fontScale = if (version >= 8) {
                json.requiredFiniteNumber("fontScale").also { value ->
                    require(value in MIN_APP_FONT_SCALE.toDouble()..MAX_APP_FONT_SCALE.toDouble()) {
                        "fontScale must be between $MIN_APP_FONT_SCALE and $MAX_APP_FONT_SCALE"
                    }
                }.toFloat()
            } else {
                defaults.fontScale
            },
            compactMode = if (version >= 14) {
                json.requiredBoolean("compactMode")
            } else {
                defaults.compactMode
            },
            backgroundImageUri = if (version >= 25) {
                json.requiredNullableString("backgroundImageUri")
                    ?.requireMaxLength("backgroundImageUri", MAX_URL_CHARS)
                    ?.also { uri -> require(uri.startsWith("content://")) {
                        "backgroundImageUri must be a content URI"
                    } }
            } else {
                defaults.backgroundImageUri
            },
            backgroundImageOpacity = if (version >= 25) {
                json.requiredFiniteNumber("backgroundImageOpacity").also { value ->
                    require(
                        value in MIN_APP_BACKGROUND_OPACITY.toDouble()..
                            MAX_APP_BACKGROUND_OPACITY.toDouble(),
                    ) { "backgroundImageOpacity is out of range" }
                }.toFloat()
            } else {
                defaults.backgroundImageOpacity
            },
            backgroundImageBlurDp = if (version >= 25) {
                json.requiredFiniteNumber("backgroundImageBlurDp").also { value ->
                    require(
                        value in MIN_APP_BACKGROUND_BLUR_DP.toDouble()..
                            MAX_APP_BACKGROUND_BLUR_DP.toDouble(),
                    ) { "backgroundImageBlurDp is out of range" }
                }.toFloat()
            } else {
                defaults.backgroundImageBlurDp
            },
            tutorialModeEnabled = if (version >= 25) {
                json.requiredBoolean("tutorialModeEnabled")
            } else {
                defaults.tutorialModeEnabled
            },
            useChineseLauncherName = if (version >= 14) {
                json.requiredBoolean("useChineseLauncherName")
            } else {
                defaults.useChineseLauncherName
            },
            launcherIcon = if (version >= 15) {
                json.requiredEnum<LauncherIcon>("launcherIcon")
            } else {
                defaults.launcherIcon
            },
            cloudSyncEnabled = if (version >= 13) {
                json.requiredBoolean("cloudSyncEnabled")
                false
            } else {
                false
            },
            cloudSyncConfigs = if (version >= 13) {
                decodeCloudSyncConfigs(json.requiredArray("cloudSyncConfigs"), version)
            } else {
                defaults.cloudSyncConfigs
            },
            diaryTreeUri = json.requiredNullableString("diaryTreeUri"),
            mediaTreeUri = json.requiredNullableString("mediaTreeUri"),
            notesTreeUri = if (version >= 26) {
                json.requiredNullableString("notesTreeUri")
            } else {
                defaults.notesTreeUri
            },
            fileNamePattern = json.requiredString("fileNamePattern").requireMaxLength("fileNamePattern", 1_024),
            markdownTemplate = json.requiredString("markdownTemplate")
                .requireMaxLength("markdownTemplate", MAX_SETTING_STRING_CHARS),
            imageNamePattern = json.requiredString("imageNamePattern").requireMaxLength("imageNamePattern", 1_024),
            imageMaxWidthDp = json.requiredCoercedInt("imageMaxWidthDp", 120, 2400),
            imageMaxHeightDp = json.requiredCoercedInt("imageMaxHeightDp", 120, 2400),
            markdownHeadingSizesSp = if (version >= 26) {
                decodeMarkdownHeadingSizes(json.requiredArray("markdownHeadingSizesSp"))
            } else {
                defaults.markdownHeadingSizesSp
            },
            mealImageCompressionEnabled = if (version >= 6) {
                json.requiredBoolean("mealImageCompressionEnabled")
            } else {
                defaults.mealImageCompressionEnabled
            },
            mealImageCompressionQuality = if (version >= 6) {
                json.requiredCoercedInt("mealImageCompressionQuality", 30, 95)
            } else {
                defaults.mealImageCompressionQuality
            },
            saveOriginalToGallery = if (version >= 14) {
                json.requiredBoolean("saveOriginalToGallery")
            } else {
                defaults.saveOriginalToGallery
            },
            photoLocationEnabled = if (version >= 14) {
                json.requiredBoolean("photoLocationEnabled")
            } else {
                defaults.photoLocationEnabled
            },
            browserHomeUrl = json.requiredString("browserHomeUrl")
                .requireMaxLength("browserHomeUrl", MAX_URL_CHARS)
                .also { requireValidBrowserUrl(it, "browserHomeUrl") },
            lastBrowserUrl = json.requiredNullableString("lastBrowserUrl")
                ?.requireMaxLength("lastBrowserUrl", MAX_URL_CHARS)
                ?.also { requireValidBrowserUrl(it, "lastBrowserUrl") },
            browserTheme = json.requiredEnum("browserTheme"),
            browserDesktopMode = json.requiredBoolean("browserDesktopMode"),
            thoughtSplitRatio = json.requiredFiniteNumber("thoughtSplitRatio")
                .toFloat()
                .coerceIn(0.25f, 0.8f),
            thoughtRowHeightDp = json.requiredCoercedInt("thoughtRowHeightDp", 48, 120),
            thoughtReopenMode = if (version >= 9) json.requiredEnum("thoughtReopenMode")
            else defaults.thoughtReopenMode,
            thoughtDisplayMode = if (version >= 9) json.requiredEnum("thoughtDisplayMode")
            else defaults.thoughtDisplayMode,
            thoughtHighlightColorArgb = if (version >= 14) {
                json.requiredInt("thoughtHighlightColorArgb") or 0xFF000000.toInt()
            } else {
                defaults.thoughtHighlightColorArgb
            },
            thoughtEditorMaxHeightDp = if (version >= 14) {
                json.requiredCoercedInt(
                    "thoughtEditorMaxHeightDp",
                    MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP,
                    MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP,
                )
            } else {
                defaults.thoughtEditorMaxHeightDp
            },
            vaultRowHeightDp = if (version >= 21) {
                json.requiredCoercedInt(
                    "vaultRowHeightDp",
                    MIN_VAULT_ROW_HEIGHT_DP,
                    MAX_VAULT_ROW_HEIGHT_DP,
                )
            } else {
                defaults.vaultRowHeightDp
            },
            poetryFontUri = if (version >= 17) {
                json.requiredNullableString("poetryFontUri")
                    ?.requireMaxLength("poetryFontUri", MAX_URL_CHARS)
            } else {
                defaults.poetryFontUri
            },
            poetryFontSizeSp = if (version >= 17) {
                json.requiredFiniteNumber("poetryFontSizeSp").also { value ->
                    require(
                        value in MIN_POETRY_FONT_SIZE_SP.toDouble()..
                            MAX_POETRY_FONT_SIZE_SP.toDouble(),
                    ) {
                        "poetryFontSizeSp must be between $MIN_POETRY_FONT_SIZE_SP and " +
                            "$MAX_POETRY_FONT_SIZE_SP"
                    }
                }.toFloat()
            } else {
                defaults.poetryFontSizeSp
            },
            poetryLineSpacing = if (version >= 17) {
                json.requiredFiniteNumber("poetryLineSpacing").also { value ->
                    require(
                        value in MIN_POETRY_LINE_SPACING.toDouble()..
                            MAX_POETRY_LINE_SPACING.toDouble(),
                    ) {
                        "poetryLineSpacing must be between $MIN_POETRY_LINE_SPACING and " +
                            "$MAX_POETRY_LINE_SPACING"
                    }
                }.toFloat()
            } else {
                defaults.poetryLineSpacing
            },
            poetryTextAlignment = if (version >= 17) {
                json.requiredEnum<PoetryTextAlignment>("poetryTextAlignment")
            } else {
                defaults.poetryTextAlignment
            },
            poetryShowSource = if (version >= 17) {
                json.requiredBoolean("poetryShowSource")
            } else {
                defaults.poetryShowSource
            },
            poetryShowQuoteMark = if (version >= 17) {
                json.requiredBoolean("poetryShowQuoteMark")
            } else {
                defaults.poetryShowQuoteMark
            },
            poetrySevenCharacterWrapEnabled = if (version >= 18) {
                json.requiredBoolean("poetrySevenCharacterWrapEnabled")
            } else {
                defaults.poetrySevenCharacterWrapEnabled
            },
            mealCalendarImageMaxHeightDp = if (version >= 9) {
                json.requiredCoercedInt("mealCalendarImageMaxHeightDp", 80, 320)
            } else {
                defaults.mealCalendarImageMaxHeightDp
            },
            mealCalendarShowCaptions = if (version >= 9) {
                json.requiredBoolean("mealCalendarShowCaptions")
            } else {
                defaults.mealCalendarShowCaptions
            },
            mealCalendarWrapEnabled = if (version >= 14) {
                json.requiredBoolean("mealCalendarWrapEnabled")
            } else {
                defaults.mealCalendarWrapEnabled
            },
            mealCalendarPhotosPerRow = if (version >= 14) {
                json.requiredEnum("mealCalendarPhotosPerRow")
            } else {
                defaults.mealCalendarPhotosPerRow
            },
            mealPhotoFilter = if (version >= 13) {
                decodeMealPhotoFilter(json.requiredObject("mealPhotoFilter"))
            } else {
                defaults.mealPhotoFilter
            },
            mealButtonsUseIcons = if (version >= 4) {
                json.requiredBoolean("mealButtonsUseIcons")
            } else {
                false
            },
            userName = if (version >= 5) {
                json.requiredString("userName").requireMaxCodePoints("userName", MAX_USERNAME_CHARS)
            } else {
                defaults.userName
            },
            homeGreetings = if (version >= 16) {
                decodeHomeGreetings(json.requiredArray("homeGreetings"))
            } else {
                defaults.homeGreetings
            },
            homeWidgetBordersEnabled = if (version >= 5) {
                json.requiredBoolean("homeWidgetBordersEnabled")
            } else {
                defaults.homeWidgetBordersEnabled
            },
            mealButtonIcons = if (version >= 5) {
                decodeMealButtonIcons(
                    json = json.requiredArray("mealButtonIcons"),
                    expectedCount = defaults.mealButtonIcons.size,
                )
            } else {
                defaults.mealButtonIcons
            },
            dailyEventTemplates = if (version >= 9) {
                decodeDailyEventTemplates(json.requiredArray("dailyEventTemplates"))
            } else {
                defaults.dailyEventTemplates
            },
            rssSubscriptions = if (version >= 9) {
                decodeRssSubscriptions(json.requiredArray("rssSubscriptions"))
            } else {
                defaults.rssSubscriptions
            },
            rssMaxItemsPerFeed = if (version >= 9) {
                json.requiredCoercedInt("rssMaxItemsPerFeed", 10, 200)
            } else {
                defaults.rssMaxItemsPerFeed
            },
            rssShowSummaries = if (version >= 9) json.requiredBoolean("rssShowSummaries")
            else defaults.rssShowSummaries,
            aiEndpointUrl = if (version >= 9) {
                json.requiredString("aiEndpointUrl").requireMaxLength("aiEndpointUrl", MAX_URL_CHARS)
            } else {
                defaults.aiEndpointUrl
            },
            aiModel = if (version >= 9) {
                json.requiredString("aiModel").requireMaxLength("aiModel", 512)
            } else {
                defaults.aiModel
            },
            aiSystemPrompt = if (version >= 9) {
                json.requiredString("aiSystemPrompt").requireMaxLength("aiSystemPrompt", 20_000)
            } else {
                defaults.aiSystemPrompt
            },
            aiTemperature = if (version >= 9) {
                json.requiredFiniteNumber("aiTemperature").also { value ->
                    require(value in 0.0..2.0) { "aiTemperature must be between 0 and 2" }
                }.toFloat()
            } else {
                defaults.aiTemperature
            },
            aiAllowInsecureHttp = if (version >= 9) json.requiredBoolean("aiAllowInsecureHttp")
            else defaults.aiAllowInsecureHttp,
            aiConfigs = if (version >= 10) {
                decodeAiConfigs(json.requiredArray("aiConfigs"), includeApiKeys = version >= 12)
            } else {
                emptyList()
            },
            aiChatConfigId = if (version >= 11) json.requiredNullableString("aiChatConfigId")?.requireMaxLength("aiChatConfigId", 80) else null,
            calorieEstimationEnabled = if (version >= 10) json.requiredBoolean("calorieEstimationEnabled") else false,
            calorieTextConfigId = if (version >= 11) json.requiredNullableString("calorieTextConfigId")?.requireMaxLength("calorieTextConfigId", 80) else null,
            calorieImageConfigId = if (version >= 11) json.requiredNullableString("calorieImageConfigId")?.requireMaxLength("calorieImageConfigId", 80) else null,
            calorieVisionPrompt = if (version >= 10) json.requiredString("calorieVisionPrompt").requireMaxLength("calorieVisionPrompt", 20_000) else defaults.calorieVisionPrompt,
            calorieTextPrompt = if (version >= 10) json.requiredString("calorieTextPrompt").requireMaxLength("calorieTextPrompt", 20_000) else defaults.calorieTextPrompt,
            usageTrackingEnabled = if (version >= 15) {
                json.requiredBoolean("usageTrackingEnabled")
            } else {
                defaults.usageTrackingEnabled
            },
            stepTrackingEnabled = if (version >= 15) {
                json.requiredBoolean("stepTrackingEnabled")
            } else {
                defaults.stepTrackingEnabled
            },
            navItems = navItems,
            morePageOrder = if (version >= 16) {
                decodeMorePageOrder(json.requiredArray("morePageOrder"), navItems)
            } else {
                normalizeMorePageOrder(emptyList(), navItems)
            },
            defaultPage = json.requiredEnum("defaultPage"),
            bottomNavShowLabels = json.requiredBoolean("bottomNavShowLabels"),
            musicVisualizerEnabled = if (version >= 23) {
                json.requiredBoolean("musicVisualizerEnabled")
            } else {
                defaults.musicVisualizerEnabled
            },
            musicVisualizerStyle = if (version >= 23) {
                json.requiredEnum<MusicVisualizerStyle>("musicVisualizerStyle")
            } else {
                defaults.musicVisualizerStyle
            },
            musicVisualizerFrequencyMode = if (version >= 24) {
                json.requiredEnum<MusicVisualizerFrequencyMode>("musicVisualizerFrequencyMode")
            } else {
                defaults.musicVisualizerFrequencyMode
            },
            musicVisualizerMinFrequencyHz = musicVisualizerMinFrequencyHz,
            musicVisualizerMaxFrequencyHz = musicVisualizerMaxFrequencyHz,
            game2048AnimationSpeed = if (version >= 23) {
                json.requiredEnum<Game2048AnimationSpeed>("game2048AnimationSpeed")
            } else {
                defaults.game2048AnimationSpeed
            },
            morePageShowDescriptions = if (version >= 15) {
                json.requiredBoolean("morePageShowDescriptions")
            } else {
                defaults.morePageShowDescriptions
            },
            homeWidgets = homeWidgets,
            homeGameShortcuts = homeGameShortcuts,
            homeWidgetTitles = homeWidgetTitles,
            desktopWidgetConfigs = if (version >= 22) {
                decodeDesktopWidgetConfigs(json.requiredArray("desktopWidgetConfigs"))
            } else {
                DEFAULT_DESKTOP_WIDGET_CONFIGS
            },
        )
    }

    private fun decodeVisualStyle(json: JSONObject, version: Int): VisualStyle {
        val visualStyle = json.requiredEnum<VisualStyle>("visualStyle")
        require(version >= 7 || visualStyle != VisualStyle.ORGANIC_FUTURE) {
            "visualStyle ${visualStyle.name} requires backup version 7 or newer"
        }
        return visualStyle
    }

    private fun decodeMealButtonIcons(json: JSONArray, expectedCount: Int): List<String> {
        require(json.length() == expectedCount || json.length() == expectedCount - 1) {
            "mealButtonIcons must contain exactly $expectedCount items"
        }
        val decoded = buildList(json.length()) {
            for (index in 0 until json.length()) {
                val value = json.get(index)
                require(value is String) { "mealButtonIcons[$index] must be a string" }
                require(value.isNotBlank()) { "mealButtonIcons[$index] must not be blank" }
                require(value.codePointLength() <= MAX_MEAL_BUTTON_ICON_CHARS) {
                    "mealButtonIcons[$index] is too long"
                }
                add(value)
            }
        }
        return if (decoded.size == expectedCount - 1) decoded.toMutableList().apply { add(2, defaultsMealTeaIcon()) } else decoded
    }

    private fun decodeMealPhotoFilter(json: JSONObject): MealPhotoFilterSettings {
        val brightness = json.requiredFiniteNumber("brightness").toFloat()
        val contrast = json.requiredFiniteNumber("contrast").toFloat()
        val saturation = json.requiredFiniteNumber("saturation").toFloat()
        val warmth = json.requiredFiniteNumber("warmth").toFloat()
        val tint = json.requiredFiniteNumber("tint").toFloat()
        require(brightness in MealPhotoFilterSettings.MIN_BRIGHTNESS..
            MealPhotoFilterSettings.MAX_BRIGHTNESS) { "mealPhotoFilter.brightness is out of range" }
        require(contrast in MealPhotoFilterSettings.MIN_CONTRAST..
            MealPhotoFilterSettings.MAX_CONTRAST) { "mealPhotoFilter.contrast is out of range" }
        require(saturation in MealPhotoFilterSettings.MIN_SATURATION..
            MealPhotoFilterSettings.MAX_SATURATION) { "mealPhotoFilter.saturation is out of range" }
        require(warmth in MealPhotoFilterSettings.MIN_WARMTH..
            MealPhotoFilterSettings.MAX_WARMTH) { "mealPhotoFilter.warmth is out of range" }
        require(tint in MealPhotoFilterSettings.MIN_TINT..
            MealPhotoFilterSettings.MAX_TINT) { "mealPhotoFilter.tint is out of range" }
        return MealPhotoFilterSettings(
            enabled = json.requiredBoolean("enabled"),
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            warmth = warmth,
            tint = tint,
        )
    }

    private fun defaultsMealTeaIcon() = AppSettings().mealButtonIcons[2]

    private fun decodeAiConfigs(json: JSONArray, includeApiKeys: Boolean): List<AiModelConfig> = buildList {
        require(json.length() <= 20) { "Too many AI configurations" }
        for (index in 0 until json.length()) json.getJSONObject(index).let { item ->
            add(AiModelConfig(
                id = item.requiredString("id").requireMaxLength("id", 80),
                name = item.requiredString("name").requireMaxLength("name", 80),
                type = item.requiredEnum<AiModelType>("type"),
                endpointUrl = item.requiredString("endpointUrl").requireMaxLength("endpointUrl", MAX_URL_CHARS),
                model = item.requiredString("model").requireMaxLength("model", 512),
                enabled = item.requiredBoolean("enabled"),
                allowInsecureHttp = item.requiredBoolean("allowInsecureHttp"),
                temperature = item.requiredFiniteNumber("temperature").toFloat().coerceIn(0f, 2f),
                systemPrompt = item.optString("systemPrompt").requireMaxLength("systemPrompt", 20_000),
                apiKey = if (includeApiKeys) {
                    item.requiredString("apiKey").requireMaxLength("apiKey", MAX_AI_API_KEY_CHARS)
                } else {
                    ""
                },
            ))
        }
    }

    private fun decodeDailyEventTemplates(json: JSONArray): List<DailyEventTemplate> {
        require(json.length() <= MAX_DAILY_EVENT_TEMPLATES) { "Too many daily event templates" }
        val ids = HashSet<String>(json.length())
        return buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "dailyEventTemplates")
                val id = item.requiredString("id")
                    .requireMaxLength("dailyEventTemplates[$index].id", 80)
                require(id.isNotBlank() && ids.add(id)) { "Invalid or duplicate daily event id: $id" }
                val text = item.requiredString("text")
                    .requireMaxLength("dailyEventTemplates[$index].text", 100)
                require(text.isNotBlank()) { "dailyEventTemplates[$index].text must not be blank" }
                add(
                    DailyEventTemplate(
                        id = id,
                        text = text,
                        firstUnit = item.requiredString("firstUnit")
                            .requireMaxLength("dailyEventTemplates[$index].firstUnit", 12),
                        secondUnit = item.requiredString("secondUnit")
                            .requireMaxLength("dailyEventTemplates[$index].secondUnit", 12),
                    ),
                )
            }
        }
    }

    private fun decodeRssSubscriptions(json: JSONArray): List<RssSubscription> {
        require(json.length() <= MAX_RSS_SUBSCRIPTIONS) { "Too many RSS subscriptions" }
        val ids = HashSet<String>(json.length())
        return buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "rssSubscriptions")
                val id = item.requiredString("id").requireMaxLength("rssSubscriptions[$index].id", 80)
                require(id.isNotBlank() && ids.add(id)) { "Invalid or duplicate RSS subscription id: $id" }
                val url = item.requiredString("url")
                    .requireMaxLength("rssSubscriptions[$index].url", MAX_URL_CHARS)
                requireValidRssUrl(url, "rssSubscriptions[$index].url")
                add(
                    RssSubscription(
                        id = id,
                        title = item.requiredString("title")
                            .requireMaxLength("rssSubscriptions[$index].title", 120),
                        url = url,
                        enabled = item.requiredBoolean("enabled"),
                    ),
                )
            }
        }
    }

    private fun decodeThemeSecondaryColors(json: JSONArray): List<Int> {
        require(json.length() in MIN_THEME_SECONDARY_COLOR_COUNT..MAX_THEME_SECONDARY_COLOR_COUNT) {
            "themeSecondaryColorsArgb must contain between " +
                "$MIN_THEME_SECONDARY_COLOR_COUNT and $MAX_THEME_SECONDARY_COLOR_COUNT items"
        }
        val decoded = buildList(json.length()) {
            for (index in 0 until json.length()) {
                add(json.requiredInt(index, "themeSecondaryColorsArgb"))
            }
        }
        return normalizeThemeSecondaryColors(decoded)
    }

    private fun decodeMarkdownHeadingSizes(json: JSONArray): List<Float> {
        require(json.length() == 6) { "markdownHeadingSizesSp must contain six items" }
        val decoded = List(json.length()) { index ->
            val value = (json.get(index) as? Number)?.toDouble()
                ?.takeIf(Double::isFinite)
                ?: throw IllegalArgumentException(
                    "markdownHeadingSizesSp[$index] must be a finite number",
                )
            value.also {
                require(
                    it in MIN_MARKDOWN_HEADING_SIZE_SP.toDouble()..
                        MAX_MARKDOWN_HEADING_SIZE_SP.toDouble(),
                ) { "markdownHeadingSizesSp[$index] is out of range" }
            }.toFloat()
        }
        return normalizeMarkdownHeadingSizes(decoded)
    }

    private fun decodeNavItems(json: JSONArray, version: Int): List<NavItemConfig> {
        require(json.length() <= NavItemId.entries.size) { "navItems contains too many items" }
        val ids = HashSet<NavItemId>(json.length())
        val decoded = buildList {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "navItems")
                val id = item.requiredEnum<NavItemId>("id")
                require(ids.add(id)) { "Duplicate navigation item: $id" }
                val visible = item.requiredBoolean("visible")
                add(
                    NavItemConfig(
                        id = id,
                        label = item.requiredString("label")
                            .requireMaxLength("navItems[$index].label", 128),
                        iconKey = item.requiredString("iconKey")
                            .requireMaxLength("navItems[$index].iconKey", 128),
                        visible = visible,
                        showInMore = when {
                            id == NavItemId.HOME ||
                                id == NavItemId.MORE ||
                                id == NavItemId.SETTINGS -> false
                            version >= 13 -> item.requiredBoolean("showInMore")
                            else -> id.defaultShowInMore && !visible
                        },
                        moreDescription = if (version >= 15) {
                            item.requiredString("moreDescription")
                                .requireMaxCodePoints(
                                    "navItems[$index].moreDescription",
                                    MAX_MORE_DESCRIPTION_CODE_POINTS,
                                )
                        } else {
                            id.defaultDescription
                        },
                    ),
                )
            }
        }
        return normalizeNavItems(decoded).map { item ->
            if (version < 24 &&
                (item.id == NavItemId.USAGE || item.id == NavItemId.STEPS)
            ) {
                item.copy(showInMore = false)
            } else {
                item
            }
        }
    }

    private fun decodeMorePageOrder(
        json: JSONArray,
        navItems: List<NavItemConfig>,
    ): List<NavItemId> {
        require(json.length() <= NavItemId.entries.size) {
            "morePageOrder contains too many items"
        }
        val decoded = json.requiredStringList("morePageOrder")
            .mapIndexed { index, value ->
                val id = runCatching { NavItemId.valueOf(value) }
                    .getOrElse { throw IllegalArgumentException("Invalid morePageOrder[$index]", it) }
                require(
                    id != NavItemId.HOME &&
                        id != NavItemId.MORE &&
                        id != NavItemId.SETTINGS,
                ) {
                    "morePageOrder[$index] is not orderable"
                }
                id
            }
        require(decoded.toSet().size == decoded.size) {
            "morePageOrder contains duplicate IDs"
        }
        return normalizeMorePageOrder(decoded, navItems)
    }

    private fun encodeThoughts(thoughts: List<FlashThoughtEntity>): JSONArray = JSONArray().apply {
        thoughts.forEach { thought ->
            put(
                JSONObject()
                    .put("id", thought.id)
                    .put("content", thought.content)
                    .put("createdAt", thought.createdAt)
                    .put("updatedAt", thought.updatedAt)
                    .put("pinned", thought.pinned)
                    .putNullable("deletedAt", thought.deletedAt)
                    .put("sortOrder", thought.sortOrder)
                    .putNullable("categoryId", thought.categoryId)
                    .put("highlighted", thought.highlighted),
            )
        }
    }

    private fun decodeThoughts(
        json: JSONArray,
        includeCategoryId: Boolean,
        includeHighlighted: Boolean,
    ): List<FlashThoughtEntity> {
        require(json.length() <= MAX_THOUGHTS) { "Backup contains too many thoughts" }
        val ids = HashSet<Long>(json.length())
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "thoughts")
                val id = item.requiredLong("id")
                require(id > 0) { "thoughts[$index].id must be positive" }
                require(ids.add(id)) { "Duplicate thought id: $id" }
                val content = item.requiredString("content")
                    .requireMaxLength("thoughts[$index].content", MAX_THOUGHT_CHARS)
                val createdAt = item.requiredLong("createdAt")
                val updatedAt = item.requiredLong("updatedAt")
                val deletedAt = item.requiredNullableLong("deletedAt")
                require(createdAt >= 0) { "thoughts[$index].createdAt must not be negative" }
                require(updatedAt >= createdAt) { "thoughts[$index].updatedAt must not precede createdAt" }
                require(deletedAt == null || deletedAt >= createdAt) {
                    "thoughts[$index].deletedAt must not precede createdAt"
                }
                add(
                    FlashThoughtEntity(
                        id = id,
                        content = content,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        pinned = item.requiredBoolean("pinned"),
                        deletedAt = deletedAt,
                        sortOrder = item.requiredLong("sortOrder"),
                        categoryId = if (includeCategoryId) {
                            item.requiredNullableLong("categoryId")
                        } else {
                            null
                        },
                        highlighted = if (includeHighlighted) {
                            item.requiredBoolean("highlighted")
                        } else {
                            false
                        },
                    ),
                )
            }
        }
    }

    private fun encodeCategories(categories: List<ThoughtCategoryEntity>): JSONArray = JSONArray().apply {
        categories.forEach { category ->
            put(
                JSONObject()
                    .put("id", category.id)
                    .put("name", category.name)
                    .put("colorArgb", category.colorArgb)
                    .put("sortOrder", category.sortOrder)
                    .put("createdAt", category.createdAt)
                    .put("updatedAt", category.updatedAt),
            )
        }
    }

    private fun decodeCategories(json: JSONArray): List<ThoughtCategoryEntity> {
        require(json.length() <= MAX_CATEGORIES) { "Backup contains too many categories" }
        val ids = HashSet<Long>(json.length())
        val names = HashSet<String>(json.length())
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "categories")
                val id = item.requiredLong("id")
                require(id > 0) { "categories[$index].id must be positive" }
                require(ids.add(id)) { "Duplicate category id: $id" }
                val name = item.requiredString("name")
                    .requireMaxLength("categories[$index].name", MAX_CATEGORY_NAME_CHARS)
                require(name.isNotBlank()) { "categories[$index].name must not be blank" }
                require(names.add(name.lowercase(Locale.ROOT))) {
                    "Duplicate category name (case-insensitive): $name"
                }
                val createdAt = item.requiredLong("createdAt")
                val updatedAt = item.requiredLong("updatedAt")
                require(createdAt >= 0) { "categories[$index].createdAt must not be negative" }
                require(updatedAt >= createdAt) {
                    "categories[$index].updatedAt must not precede createdAt"
                }
                add(
                    ThoughtCategoryEntity(
                        id = id,
                        name = name,
                        colorArgb = item.requiredInt("colorArgb"),
                        sortOrder = item.requiredLong("sortOrder"),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    ),
                )
            }
        }
    }

    private fun encodeFavorites(favorites: List<BrowserRecordEntity>): JSONArray = JSONArray().apply {
        favorites.forEach { favorite ->
            put(
                JSONObject()
                    .put("url", favorite.url)
                    .put("title", favorite.title)
                    .put("lastVisitedAt", favorite.lastVisitedAt)
                    .put("visitCount", favorite.visitCount)
                    .put("favorite", true),
            )
        }
    }

    private fun decodeFavorites(json: JSONArray): List<BrowserRecordEntity> {
        require(json.length() <= MAX_FAVORITES) { "Backup contains too many favorites" }
        val urls = HashSet<String>(json.length())
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "favorites")
                val url = item.requiredString("url")
                requireValidFavoriteUrl(url, "favorites[$index].url")
                require(urls.add(url)) { "Duplicate favorite url: $url" }
                require(item.requiredBoolean("favorite")) { "favorites[$index].favorite must be true" }
                val lastVisitedAt = item.requiredLong("lastVisitedAt")
                require(lastVisitedAt >= 0) { "favorites[$index].lastVisitedAt must not be negative" }
                add(
                    BrowserRecordEntity(
                        url = url,
                        title = item.requiredString("title")
                            .requireMaxLength("favorites[$index].title", MAX_TITLE_CHARS),
                        lastVisitedAt = lastVisitedAt,
                        visitCount = item.requiredCoercedInt("visitCount", 1, Int.MAX_VALUE),
                        favorite = true,
                    ),
                )
            }
        }
    }

    private fun encodeDateRecords(dateRecords: List<DateRecordEntity>): JSONArray = JSONArray().apply {
        dateRecords.forEach { record ->
            put(
                JSONObject()
                    .put("id", record.id)
                    .put("name", record.name)
                    .put("icon", record.icon)
                    .put("dateIso", record.dateIso)
                    .put("createdAt", record.createdAt)
                    .put("updatedAt", record.updatedAt),
            )
        }
    }

    private fun decodeDateRecords(json: JSONArray): List<DateRecordEntity> {
        require(json.length() <= MAX_DATE_RECORDS) { "Backup contains too many date records" }
        val ids = HashSet<Long>(json.length())
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "dateRecords")
                val id = item.requiredLong("id")
                require(id > 0) { "dateRecords[$index].id must be positive" }
                require(ids.add(id)) { "Duplicate date record id: $id" }
                val name = item.requiredString("name")
                    .requireMaxLength("dateRecords[$index].name", MAX_DATE_NAME_CHARS)
                require(name.isNotBlank()) { "dateRecords[$index].name must not be blank" }
                val icon = item.requiredString("icon")
                    .requireMaxLength("dateRecords[$index].icon", MAX_DATE_ICON_CHARS)
                require(icon.isNotBlank()) { "dateRecords[$index].icon must not be blank" }
                val dateIso = item.requiredString("dateIso")
                requireValidDateIso(dateIso, "dateRecords[$index].dateIso")
                val createdAt = item.requiredLong("createdAt")
                val updatedAt = item.requiredLong("updatedAt")
                require(createdAt >= 0) { "dateRecords[$index].createdAt must not be negative" }
                require(updatedAt >= createdAt) {
                    "dateRecords[$index].updatedAt must not precede createdAt"
                }
                add(
                    DateRecordEntity(
                        id = id,
                        name = name,
                        icon = icon,
                        dateIso = dateIso,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    ),
                )
            }
        }
    }

    private fun encodePoetryCategories(
        categories: List<PoetryCategoryEntity>,
    ): JSONArray = JSONArray().apply {
        categories.forEach { category ->
            put(
                JSONObject()
                    .put("id", category.id)
                    .put("name", category.name)
                    .put("colorArgb", category.colorArgb)
                    .put("sortOrder", category.sortOrder)
                    .put("createdAt", category.createdAt)
                    .put("updatedAt", category.updatedAt),
            )
        }
    }

    private fun decodePoetryCategories(json: JSONArray): List<PoetryCategoryEntity> {
        require(json.length() <= MAX_POETRY_CATEGORIES) {
            "Backup contains too many poetry categories"
        }
        val ids = HashSet<Long>(json.length())
        val names = HashSet<String>(json.length())
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "poetryCategories")
                val id = item.requiredLong("id")
                require(id > 0) { "poetryCategories[$index].id must be positive" }
                require(ids.add(id)) { "Duplicate poetry category id: $id" }
                val name = item.requiredString("name").requireMaxLength(
                    "poetryCategories[$index].name",
                    MAX_POETRY_CATEGORY_NAME_CHARS,
                )
                require(name.isNotBlank()) {
                    "poetryCategories[$index].name must not be blank"
                }
                require(names.add(name.lowercase(Locale.ROOT))) {
                    "Duplicate poetry category name (case-insensitive): $name"
                }
                val createdAt = item.requiredLong("createdAt")
                val updatedAt = item.requiredLong("updatedAt")
                require(createdAt >= 0) {
                    "poetryCategories[$index].createdAt must not be negative"
                }
                require(updatedAt >= createdAt) {
                    "poetryCategories[$index].updatedAt must not precede createdAt"
                }
                add(
                    PoetryCategoryEntity(
                        id = id,
                        name = name,
                        colorArgb = item.requiredInt("colorArgb"),
                        sortOrder = item.requiredLong("sortOrder"),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                    ),
                )
            }
        }
    }

    private fun encodePoems(poems: List<SavedPoemEntity>): JSONArray = JSONArray().apply {
        poems.forEach { poem ->
            put(
                JSONObject()
                    .put("id", poem.id)
                    .put("content", poem.content)
                    .put("source", poem.source)
                    .put("createdAt", poem.createdAt)
                    .put("updatedAt", poem.updatedAt)
                    .put("sortOrder", poem.sortOrder)
                    .putNullable("categoryId", poem.categoryId),
            )
        }
    }

    private fun decodePoems(
        json: JSONArray,
        includeCategoryId: Boolean,
        includeSortOrder: Boolean,
    ): List<SavedPoemEntity> {
        require(json.length() <= MAX_POEMS) { "Backup contains too many poems" }
        val ids = HashSet<Long>(json.length())
        return buildList {
            for (index in 0 until json.length()) {
                val item = json.requiredObject(index, "poems")
                val id = item.requiredLong("id")
                require(id > 0) { "poems[$index].id must be positive" }
                require(ids.add(id)) { "Duplicate poem id: $id" }
                val content = item.requiredString("content")
                    .requireMaxLength("poems[$index].content", MAX_POEM_CONTENT_CHARS)
                require(content.isNotBlank()) { "poems[$index].content must not be blank" }
                val source = item.requiredString("source")
                    .requireMaxLength("poems[$index].source", MAX_POEM_SOURCE_CHARS)
                val createdAt = item.requiredLong("createdAt")
                val updatedAt = item.requiredLong("updatedAt")
                require(createdAt >= 0) { "poems[$index].createdAt must not be negative" }
                require(updatedAt >= createdAt) {
                    "poems[$index].updatedAt must not precede createdAt"
                }
                add(
                    SavedPoemEntity(
                        id = id,
                        content = content,
                        source = source,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        sortOrder = if (includeSortOrder) item.requiredLong("sortOrder") else 0L,
                        categoryId = if (includeCategoryId) {
                            item.requiredNullableLong("categoryId")
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }

    private fun encodeVault(vault: VaultEncryptedBackup): JSONObject = JSONObject()
        .put("active", vault.active?.let(::encodeVaultKey) ?: JSONObject.NULL)
        .put("pending", vault.pending?.let(::encodeVaultKey) ?: JSONObject.NULL)
        .put(
            "items",
            JSONArray().apply {
                vault.items.sortedBy(VaultItemEntity::id).forEach { item ->
                    put(
                        JSONObject()
                            .put("id", item.id)
                            .put("cipherText", item.cipherText)
                            .put("iv", item.iv)
                            .put("createdAt", item.createdAt)
                            .put("updatedAt", item.updatedAt)
                            .put("sortOrder", item.sortOrder),
                    )
                }
            },
        )

    private fun encodeVaultKey(key: VaultEncryptedKeyBackup): JSONObject = JSONObject()
        .put("saltBase64", key.saltBase64)
        .put("verifierCipher", key.verifierCipher)
        .put("verifierIv", key.verifierIv)
        .put("iterations", key.iterations)
        .putNullable("generationId", key.generationId)

    private fun decodeVault(json: JSONObject): VaultEncryptedBackup {
        val active = if (json.isNull("active")) {
            null
        } else {
            decodeVaultKey(json.requiredObject("active"), pending = false)
        }
        val pending = if (json.isNull("pending")) {
            null
        } else {
            decodeVaultKey(json.requiredObject("pending"), pending = true)
        }
        val itemsJson = json.requiredArray("items")
        require(itemsJson.length() <= MAX_VAULT_ITEMS) {
            "Backup contains too many Vault rows"
        }
        val ids = HashSet<Long>(itemsJson.length())
        val items = buildList {
            repeat(itemsJson.length()) { index ->
                val item = itemsJson.requiredObject(index, "vault.items")
                val id = item.requiredLong("id")
                require(id > 0L || id == VAULT_KEY_MARKER_ENTITY_ID) {
                    "vault.items[$index].id is invalid"
                }
                require(ids.add(id)) { "Duplicate Vault row id: $id" }
                val createdAt = item.requiredLong("createdAt")
                val updatedAt = item.requiredLong("updatedAt")
                val sortOrder = item.requiredLong("sortOrder")
                require(createdAt >= 0L && updatedAt >= 0L && sortOrder >= 0L) {
                    "vault.items[$index] contains a negative value"
                }
                add(
                    VaultItemEntity(
                        id = id,
                        cipherText = item.requiredString("cipherText")
                            .requireMaxLength(
                                "vault.items[$index].cipherText",
                                MAX_VAULT_CIPHER_CHARS,
                            ),
                        iv = item.requiredString("iv")
                            .requireMaxLength("vault.items[$index].iv", MAX_VAULT_IV_CHARS),
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        sortOrder = sortOrder,
                    ),
                )
            }
        }
        return VaultEncryptedBackup(active = active, pending = pending, items = items)
            .also(::validateVaultBackup)
    }

    private fun decodeVaultKey(
        json: JSONObject,
        pending: Boolean,
    ): VaultEncryptedKeyBackup = VaultEncryptedKeyBackup(
        saltBase64 = json.requiredString("saltBase64")
            .requireMaxLength("vault.saltBase64", MAX_VAULT_SALT_CHARS),
        verifierCipher = json.requiredString("verifierCipher")
            .requireMaxLength("vault.verifierCipher", MAX_VAULT_CIPHER_CHARS),
        verifierIv = json.requiredString("verifierIv")
            .requireMaxLength("vault.verifierIv", MAX_VAULT_IV_CHARS),
        iterations = json.requiredInt("iterations"),
        generationId = json.requiredNullableString("generationId")
            ?.requireMaxLength("vault.generationId", MAX_VAULT_GENERATION_CHARS),
    ).also { key ->
        validateVaultKey(key, generationRequired = pending)
    }

    private fun validateVaultBackup(vault: VaultEncryptedBackup) {
        require(vault.items.size <= MAX_VAULT_ITEMS) { "Backup contains too many Vault rows" }
        require(vault.items.map(VaultItemEntity::id).distinct().size == vault.items.size) {
            "Backup contains duplicate Vault row ids"
        }
        if (vault.active == null) {
            require(vault.pending == null && vault.items.isEmpty()) {
                "Vault rows or pending metadata exist without active metadata"
            }
            return
        }
        validateVaultKey(vault.active, generationRequired = false)
        vault.pending?.let { validateVaultKey(it, generationRequired = true) }
        vault.items.forEachIndexed { index, item ->
            require(item.id > 0L || item.id == VAULT_KEY_MARKER_ENTITY_ID) {
                "vault.items[$index].id is invalid"
            }
            require(item.createdAt >= 0L && item.updatedAt >= 0L && item.sortOrder >= 0L) {
                "vault.items[$index] contains a negative value"
            }
            validateAesGcmBase64(
                cipher = item.cipherText,
                iv = item.iv,
                field = "vault.items[$index]",
            )
        }
    }

    private fun validateVaultKey(
        key: VaultEncryptedKeyBackup,
        generationRequired: Boolean,
    ) {
        require(key.saltBase64.length <= MAX_VAULT_SALT_CHARS) { "Vault salt is too long" }
        val salt = decodeBase64(key.saltBase64, "Vault salt")
        require(salt.size in 1..1_024) { "Vault salt size is invalid" }
        require(key.iterations in 1..10_000_000) { "Vault KDF iterations are invalid" }
        val generation = key.generationId
        require(!generationRequired || generation != null) {
            "Pending Vault generation is missing"
        }
        require(
            generation == null || Regex("[A-Za-z0-9-]{1,64}").matches(generation),
        ) { "Vault generation id is invalid" }
        validateAesGcmBase64(
            cipher = key.verifierCipher,
            iv = key.verifierIv,
            field = "Vault verifier",
        )
    }

    private fun validateAesGcmBase64(
        cipher: String,
        iv: String,
        field: String,
    ) {
        require(cipher.isNotBlank() && cipher.length <= MAX_VAULT_CIPHER_CHARS) {
            "$field ciphertext is invalid"
        }
        require(iv.length <= MAX_VAULT_IV_CHARS) { "$field IV is too long" }
        require(decodeBase64(cipher, "$field ciphertext").size >= 16) {
            "$field ciphertext is too short"
        }
        require(decodeBase64(iv, "$field IV").size == 12) { "$field IV size is invalid" }
    }

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("$field is not valid Base64", error)
    }

    private fun encodeGameStates(states: List<GameStateEntity>): JSONArray = JSONArray().apply {
        states.sortedBy(GameStateEntity::gameId).forEach { state ->
            put(
                JSONObject()
                    .put("gameId", state.gameId)
                    .put("highScore", state.highScore)
                    .putNullable("saveJson", state.saveJson)
                    .put("updatedAt", state.updatedAt),
            )
        }
    }

    private fun decodeGameStates(json: JSONArray): List<GameStateEntity> {
        require(json.length() <= MAX_GAME_STATES) { "Backup contains too many game states" }
        val ids = HashSet<String>(json.length())
        return buildList {
            repeat(json.length()) { index ->
                val item = json.requiredObject(index, "gameStates")
                val state = GameStateEntity(
                    gameId = item.requiredString("gameId")
                        .requireMaxLength("gameStates[$index].gameId", MAX_GAME_ID_CHARS),
                    highScore = item.requiredInt("highScore"),
                    saveJson = item.requiredNullableString("saveJson")
                        ?.requireMaxLength(
                            "gameStates[$index].saveJson",
                            MAX_GAME_SAVE_CHARS,
                        ),
                    updatedAt = item.requiredLong("updatedAt"),
                )
                require(ids.add(state.gameId)) { "Duplicate game state id: ${state.gameId}" }
                validateGameState(state, index)
                add(state)
            }
        }
    }

    private fun validateGameStates(states: List<GameStateEntity>) {
        require(states.size <= MAX_GAME_STATES) { "Backup contains too many game states" }
        require(states.map(GameStateEntity::gameId).distinct().size == states.size) {
            "Backup contains duplicate game state ids"
        }
        states.forEachIndexed(::validateGameState)
    }

    private fun validateGameState(index: Int, state: GameStateEntity) =
        validateGameState(state, index)

    private fun validateGameState(state: GameStateEntity, index: Int) {
        require(state.gameId in SUPPORTED_GAME_IDS) {
            "gameStates[$index].gameId is unsupported"
        }
        require(state.highScore >= 0 && state.updatedAt >= 0L) {
            "gameStates[$index] contains a negative value"
        }
        state.saveJson?.let { save ->
            save.requireMaxLength("gameStates[$index].saveJson", MAX_GAME_SAVE_CHARS)
            require(save.isNotBlank()) { "gameStates[$index].saveJson must not be blank" }
            val tokener = JSONTokener(save)
            require(tokener.nextValue() is JSONObject && tokener.nextClean() == '\u0000') {
                "gameStates[$index].saveJson must contain one JSON object"
            }
        }
    }

    private fun encodeGameStatistics(items: List<GameStatisticEntity>): JSONArray =
        JSONArray().apply {
            items.sortedWith(
                compareBy(GameStatisticEntity::gameId).thenBy(GameStatisticEntity::metricKey),
            ).forEach { item ->
                put(
                    JSONObject()
                        .put("gameId", item.gameId)
                        .put("metricKey", item.metricKey)
                        .put("value", item.value)
                        .put("updatedAt", item.updatedAt),
                )
            }
        }

    private fun decodeGameStatistics(json: JSONArray): List<GameStatisticEntity> {
        require(json.length() <= MAX_GAME_STATISTICS) {
            "Backup contains too many game statistics"
        }
        val keys = HashSet<String>(json.length())
        return buildList {
            repeat(json.length()) { index ->
                val item = json.requiredObject(index, "gameStatistics")
                val statistic = GameStatisticEntity(
                    gameId = item.requiredString("gameId")
                        .requireMaxLength("gameStatistics[$index].gameId", MAX_GAME_ID_CHARS),
                    metricKey = item.requiredString("metricKey")
                        .requireMaxLength("gameStatistics[$index].metricKey", MAX_GAME_ID_CHARS),
                    value = item.requiredLong("value"),
                    updatedAt = item.requiredLong("updatedAt"),
                )
                require(keys.add("${statistic.gameId}\u0000${statistic.metricKey}")) {
                    "Duplicate game statistic key"
                }
                validateGameStatistic(statistic, index)
                add(statistic)
            }
        }
    }

    private fun validateGameStatistics(items: List<GameStatisticEntity>) {
        require(items.size <= MAX_GAME_STATISTICS) {
            "Backup contains too many game statistics"
        }
        require(items.map { "${it.gameId}\u0000${it.metricKey}" }.distinct().size == items.size) {
            "Backup contains duplicate game statistic keys"
        }
        items.forEachIndexed { index, item -> validateGameStatistic(item, index) }
    }

    private fun validateGameStatistic(item: GameStatisticEntity, index: Int) {
        require(GameStatisticCatalog.supports(item.gameId, item.metricKey)) {
            "gameStatistics[$index] contains an unsupported key"
        }
        require(item.value >= 0L && item.updatedAt >= 0L) {
            "gameStatistics[$index] contains a negative value"
        }
    }

    private fun encodeUsageDevices(records: List<UsageDeviceRecord>): JSONArray =
        JSONArray().apply {
            records.sortedBy(UsageDeviceRecord::deviceId).forEach { record ->
                put(JSONObject(UsageDeviceJsonCodec.encode(record)))
            }
        }

    private fun decodeUsageDevices(json: JSONArray): List<UsageDeviceRecord> {
        require(json.length() <= MAX_USAGE_DEVICES) { "Backup contains too many usage devices" }
        val ids = HashSet<String>(json.length())
        return buildList {
            repeat(json.length()) { index ->
                val record = UsageDeviceJsonCodec.decode(
                    json.requiredObject(index, "usageDevices").toString(),
                )
                require(ids.add(record.deviceId)) {
                    "Duplicate usage device id: ${record.deviceId}"
                }
                add(record)
            }
        }
    }

    private fun validateUsageDevices(records: List<UsageDeviceRecord>) {
        require(records.size <= MAX_USAGE_DEVICES) { "Backup contains too many usage devices" }
        require(records.map(UsageDeviceRecord::deviceId).distinct().size == records.size) {
            "Backup contains duplicate usage device ids"
        }
        records.forEach { UsageDeviceJsonCodec.encode(it) }
    }

    private fun validateEntityKeys(
        thoughts: List<FlashThoughtEntity>,
        categories: List<ThoughtCategoryEntity>,
        poetryCategories: List<PoetryCategoryEntity>,
        favorites: List<BrowserRecordEntity>,
        dateRecords: List<DateRecordEntity>,
        poems: List<SavedPoemEntity>,
    ) {
        val categoryIds = HashSet<Long>(categories.size)
        val categoryNames = HashSet<String>(categories.size)
        require(categories.size <= MAX_CATEGORIES) { "Backup contains too many categories" }
        categories.forEach { category ->
            require(category.id > 0) { "Category id must be positive: ${category.id}" }
            require(categoryIds.add(category.id)) { "Duplicate category id: ${category.id}" }
            category.name.requireMaxLength("Category name", MAX_CATEGORY_NAME_CHARS)
            require(category.name.isNotBlank()) { "Category name must not be blank" }
            require(categoryNames.add(category.name.lowercase(Locale.ROOT))) {
                "Duplicate category name (case-insensitive): ${category.name}"
            }
            require(category.createdAt >= 0 && category.updatedAt >= category.createdAt) {
                "Category timestamps are invalid: ${category.id}"
            }
        }
        val poetryCategoryIds = HashSet<Long>(poetryCategories.size)
        val poetryCategoryNames = HashSet<String>(poetryCategories.size)
        require(poetryCategories.size <= MAX_POETRY_CATEGORIES) {
            "Backup contains too many poetry categories"
        }
        poetryCategories.forEach { category ->
            require(category.id > 0) {
                "Poetry category id must be positive: ${category.id}"
            }
            require(poetryCategoryIds.add(category.id)) {
                "Duplicate poetry category id: ${category.id}"
            }
            category.name.requireMaxLength(
                "Poetry category name",
                MAX_POETRY_CATEGORY_NAME_CHARS,
            )
            require(category.name.isNotBlank()) { "Poetry category name must not be blank" }
            require(poetryCategoryNames.add(category.name.lowercase(Locale.ROOT))) {
                "Duplicate poetry category name (case-insensitive): ${category.name}"
            }
            require(category.createdAt >= 0 && category.updatedAt >= category.createdAt) {
                "Poetry category timestamps are invalid: ${category.id}"
            }
        }
        val thoughtIds = HashSet<Long>(thoughts.size)
        require(thoughts.size <= MAX_THOUGHTS) { "Backup contains too many thoughts" }
        thoughts.forEach { thought ->
            require(thought.id > 0) { "Thought id must be positive: ${thought.id}" }
            require(thoughtIds.add(thought.id)) { "Duplicate thought id: ${thought.id}" }
            thought.content.requireMaxLength("Thought content", MAX_THOUGHT_CHARS)
            require(thought.createdAt >= 0 && thought.updatedAt >= thought.createdAt) {
                "Thought timestamps are invalid: ${thought.id}"
            }
            require(thought.deletedAt == null || thought.deletedAt >= thought.createdAt) {
                "Thought deletion timestamp is invalid: ${thought.id}"
            }
            require(thought.categoryId == null || thought.categoryId in categoryIds) {
                "Thought ${thought.id} references missing category: ${thought.categoryId}"
            }
        }
        val favoriteUrls = HashSet<String>(favorites.size)
        require(favorites.size <= MAX_FAVORITES) { "Backup contains too many favorites" }
        favorites.forEach { favorite ->
            requireValidFavoriteUrl(favorite.url, "Favorite url")
            require(favoriteUrls.add(favorite.url)) { "Duplicate favorite url: ${favorite.url}" }
            favorite.title.requireMaxLength("Favorite title", MAX_TITLE_CHARS)
            require(favorite.lastVisitedAt >= 0) { "Favorite timestamp must not be negative" }
        }
        val dateRecordIds = HashSet<Long>(dateRecords.size)
        require(dateRecords.size <= MAX_DATE_RECORDS) { "Backup contains too many date records" }
        dateRecords.forEach { record ->
            require(record.id > 0) { "Date record id must be positive: ${record.id}" }
            require(dateRecordIds.add(record.id)) { "Duplicate date record id: ${record.id}" }
            record.name.requireMaxLength("Date record name", MAX_DATE_NAME_CHARS)
            require(record.name.isNotBlank()) { "Date record name must not be blank" }
            record.icon.requireMaxLength("Date record icon", MAX_DATE_ICON_CHARS)
            require(record.icon.isNotBlank()) { "Date record icon must not be blank" }
            requireValidDateIso(record.dateIso, "Date record dateIso")
            require(record.createdAt >= 0 && record.updatedAt >= record.createdAt) {
                "Date record timestamps are invalid: ${record.id}"
            }
        }
        val poemIds = HashSet<Long>(poems.size)
        require(poems.size <= MAX_POEMS) { "Backup contains too many poems" }
        poems.forEach { poem ->
            require(poem.id > 0) { "Poem id must be positive: ${poem.id}" }
            require(poemIds.add(poem.id)) { "Duplicate poem id: ${poem.id}" }
            poem.content.requireMaxLength("Poem content", MAX_POEM_CONTENT_CHARS)
            require(poem.content.isNotBlank()) { "Poem content must not be blank" }
            poem.source.requireMaxLength("Poem source", MAX_POEM_SOURCE_CHARS)
            require(poem.createdAt >= 0 && poem.updatedAt >= poem.createdAt) {
                "Poem timestamps are invalid: ${poem.id}"
            }
            require(poem.categoryId == null || poem.categoryId in poetryCategoryIds) {
                "Poem ${poem.id} references missing poetry category: ${poem.categoryId}"
            }
        }
    }

    private fun validateCategoryReferences(
        thoughts: List<FlashThoughtEntity>,
        categories: List<ThoughtCategoryEntity>,
    ) {
        val categoryIds = categories.mapTo(HashSet(categories.size)) { it.id }
        thoughts.forEachIndexed { index, thought ->
            require(thought.categoryId == null || thought.categoryId in categoryIds) {
                "thoughts[$index].categoryId references a missing category: ${thought.categoryId}"
            }
        }
    }

    private fun validatePoetryCategoryReferences(
        poems: List<SavedPoemEntity>,
        categories: List<PoetryCategoryEntity>,
    ) {
        val categoryIds = categories.mapTo(HashSet(categories.size)) { it.id }
        poems.forEachIndexed { index, poem ->
            require(poem.categoryId == null || poem.categoryId in categoryIds) {
                "poems[$index].categoryId references a missing poetry category: ${poem.categoryId}"
            }
        }
    }

    private fun requireWithinSizeLimit(json: String) {
        require(json.length <= MAX_JSON_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Backup JSON exceeds the 64 MiB limit"
        }
    }

    private fun requireValidFavoriteUrl(url: String, field: String) {
        require(url.length <= MAX_URL_CHARS) { "$field is too long" }
        require(url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) {
            "$field must use http or https"
        }
    }

    private fun requireValidBrowserUrl(url: String, field: String) {
        require(
            url.equals("about:blank", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true),
        ) { "$field must use http, https, or about:blank" }
    }

    private fun requireValidRssUrl(url: String, field: String) {
        val uri = runCatching { URI(url) }.getOrElse {
            throw IllegalArgumentException("$field must be a valid HTTPS URL", it)
        }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "$field must use HTTPS and include a host"
        }
    }

    private fun requireValidDateIso(value: String, field: String) {
        require(value.length == 10) { "$field must use yyyy-MM-dd" }
        try {
            LocalDate.parse(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("$field must be a valid yyyy-MM-dd date", error)
        }
    }
}

private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
    put(name, value ?: JSONObject.NULL)

private fun List<String>.toJsonArray(): JSONArray = JSONArray().apply {
    this@toJsonArray.forEach(::put)
}

private fun List<Int>.toJsonIntArray(): JSONArray = JSONArray().apply {
    this@toJsonIntArray.forEach(::put)
}

private fun JSONObject.requiredValue(name: String): Any {
    require(has(name)) { "Missing required field: $name" }
    return get(name)
}

private fun JSONObject.requiredString(name: String): String {
    val value = requiredValue(name)
    require(value is String) { "$name must be a string" }
    return value
}

private fun JSONObject.requiredNullableString(name: String): String? {
    val value = requiredValue(name)
    if (value === JSONObject.NULL) return null
    require(value is String) { "$name must be a string or null" }
    return value
}

private fun JSONObject.requiredBoolean(name: String): Boolean {
    val value = requiredValue(name)
    require(value is Boolean) { "$name must be a boolean" }
    return value
}

private fun JSONObject.requiredObject(name: String): JSONObject {
    val value = requiredValue(name)
    require(value is JSONObject) { "$name must be an object" }
    return value
}

private fun JSONObject.requiredArray(name: String): JSONArray {
    val value = requiredValue(name)
    require(value is JSONArray) { "$name must be an array" }
    return value
}

private fun JSONObject.requiredFiniteNumber(name: String): Double {
    val value = requiredValue(name)
    require(value is Number) { "$name must be a number" }
    return value.toDouble().also { number ->
        require(number.isFinite()) { "$name must be finite" }
    }
}

private fun JSONObject.requiredLong(name: String): Long {
    val value = requiredValue(name)
    require(value is Number) { "$name must be an integer" }
    return try {
        BigDecimal(value.toString()).longValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$name must be a 64-bit integer")
    } catch (_: NumberFormatException) {
        throw IllegalArgumentException("$name must be a 64-bit integer")
    }
}

private fun JSONObject.requiredNullableLong(name: String): Long? {
    val value = requiredValue(name)
    if (value === JSONObject.NULL) return null
    return requiredLong(name)
}

private fun JSONObject.requiredInt(name: String): Int {
    val value = requiredLong(name)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$name must be a 32-bit integer" }
    return value.toInt()
}

private fun JSONObject.requiredCoercedInt(name: String, minimum: Int, maximum: Int): Int {
    val number = requiredFiniteNumber(name)
    require(number % 1.0 == 0.0) { "$name must be an integer" }
    return number.coerceIn(minimum.toDouble(), maximum.toDouble()).toInt()
}

private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(name: String): T {
    val value = requiredString(name)
    return enumValues<T>().firstOrNull { it.name == value }
        ?: throw IllegalArgumentException("Invalid ${T::class.java.simpleName} value for $name: $value")
}

private fun JSONArray.requiredObject(index: Int, arrayName: String): JSONObject {
    val value = get(index)
    require(value is JSONObject) { "$arrayName[$index] must be an object" }
    return value
}

private fun JSONArray.requiredInt(index: Int, arrayName: String): Int {
    val value = get(index)
    require(value is Number) { "$arrayName[$index] must be an integer" }
    val decoded = try {
        BigDecimal(value.toString()).longValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("$arrayName[$index] must be a 32-bit integer")
    } catch (_: NumberFormatException) {
        throw IllegalArgumentException("$arrayName[$index] must be a 32-bit integer")
    }
    require(decoded in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "$arrayName[$index] must be a 32-bit integer"
    }
    return decoded.toInt()
}

private fun JSONArray.requiredStringList(arrayName: String): List<String> = buildList {
    require(length() <= 1_000) { "$arrayName contains too many items" }
    val values = HashSet<String>(length())
    for (index in 0 until length()) {
        val value = this@requiredStringList.get(index)
        require(value is String) { "$arrayName[$index] must be a string" }
        require(value.length <= 256) { "$arrayName[$index] is too long" }
        require(values.add(value)) { "$arrayName contains a duplicate value: $value" }
        add(value)
    }
}

private fun String.requireMaxLength(field: String, maximum: Int): String = also {
    require(length <= maximum) { "$field is too long" }
}

private fun String.requireMaxCodePoints(field: String, maximum: Int): String = also {
    require(codePointLength() <= maximum) { "$field is too long" }
}
