package com.deskcubby.app.data.preferences

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deskcubby.app.takeCodePoints
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AgentDataSource
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.CustomThemeBaseStyle
import com.deskcubby.app.data.model.CustomThemePalette
import com.deskcubby.app.data.model.CustomThemeSettings
import com.deskcubby.app.data.model.DEFAULT_MEAL_BUTTON_ICONS
import com.deskcubby.app.data.model.DEFAULT_CLOUD_SYNC_USER_AGENT
import com.deskcubby.app.data.model.DEFAULT_CALORIE_TEXT_PROMPT
import com.deskcubby.app.data.model.DEFAULT_CALORIE_VISION_PROMPT
import com.deskcubby.app.data.model.DEFAULT_THEME_SECONDARY_COLORS_ARGB
import com.deskcubby.app.data.model.normalizeHomeGameShortcutIds
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.DEFAULT_DESKTOP_WIDGET_CONFIGS
import com.deskcubby.app.data.model.DESKTOP_WIDGET_HOME_MODULE_IDS
import com.deskcubby.app.data.model.normalizeDesktopWidgetHomeModuleId
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.DesktopWidgetTextAlignment
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.Game2048AnimationSpeed
import com.deskcubby.app.data.model.LauncherIcon
import com.deskcubby.app.data.model.MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_TEXT_SCALE_PERCENT
import com.deskcubby.app.data.model.MAX_VAULT_ROW_HEIGHT_DP
import com.deskcubby.app.data.model.MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_TEXT_SCALE_PERCENT
import com.deskcubby.app.data.model.MIN_VAULT_ROW_HEIGHT_DP
import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.MAX_APP_FONT_SCALE
import com.deskcubby.app.data.model.MAX_APP_BACKGROUND_BLUR_DP
import com.deskcubby.app.data.model.MAX_APP_BACKGROUND_OPACITY
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.data.model.MAX_POETRY_FONT_SIZE_SP
import com.deskcubby.app.data.model.MAX_POETRY_LINE_SPACING
import com.deskcubby.app.data.model.MAX_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.data.model.MIN_APP_FONT_SCALE
import com.deskcubby.app.data.model.MIN_APP_BACKGROUND_BLUR_DP
import com.deskcubby.app.data.model.MIN_APP_BACKGROUND_OPACITY
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.data.model.MIN_POETRY_FONT_SIZE_SP
import com.deskcubby.app.data.model.MIN_POETRY_LINE_SPACING
import com.deskcubby.app.data.model.MIN_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.MusicVisualizerStyle
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.PoetryTextAlignment
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.ThoughtReopenMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.normalizeMarkdownHeadingSizes
import com.deskcubby.app.data.model.normalizeMorePageOrder
import com.deskcubby.app.data.model.normalized
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(
    name = "deskcubby_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val visualStyle = stringPreferencesKey("visual_style")
        val customTheme = stringPreferencesKey("custom_theme_v1")
        val darkMode = stringPreferencesKey("dark_mode")
        val appLanguage = stringPreferencesKey("app_language")
        val userName = stringPreferencesKey("user_name")
        val homeGreetings = stringPreferencesKey("home_greetings_v1")
        val themeColorArgb = intPreferencesKey("theme_color_argb")
        val themeSecondaryColorsArgb = stringPreferencesKey("theme_secondary_colors_argb")
        val fontScale = floatPreferencesKey("font_scale")
        val compactMode = booleanPreferencesKey("compact_mode")
        val backgroundImageUri = stringPreferencesKey("background_image_uri")
        val backgroundImageOpacity = floatPreferencesKey("background_image_opacity")
        val backgroundImageBlurDp = floatPreferencesKey("background_image_blur_dp")
        val tutorialModeEnabled = booleanPreferencesKey("tutorial_mode_enabled")
        val tutorialAcknowledgedPages = stringSetPreferencesKey("tutorial_acknowledged_pages")
        val useChineseLauncherName = booleanPreferencesKey("use_chinese_launcher_name")
        val launcherIcon = stringPreferencesKey("launcher_icon")
        val backupTreeUri = stringPreferencesKey("backup_tree_uri")
        val cloudSyncEnabled = booleanPreferencesKey("cloud_sync_enabled")
        val cloudSyncConfigs = stringPreferencesKey("cloud_sync_configs_v1")
        val diaryTreeUri = stringPreferencesKey("diary_tree_uri")
        val mediaTreeUri = stringPreferencesKey("media_tree_uri")
        // Device-local parent grant used only to validate the scoped child tree URIs created by
        // the diary page's default-folder initializer. It is not user data and is not backed up.
        val defaultDiaryFoldersGrantUri = stringPreferencesKey("default_diary_folders_grant_uri")
        val notesTreeUri = stringPreferencesKey("notes_tree_uri")
        val fileNamePattern = stringPreferencesKey("file_name_pattern")
        val markdownTemplate = stringPreferencesKey("markdown_template")
        val imageNamePattern = stringPreferencesKey("image_name_pattern")
        val imageMaxWidthDp = intPreferencesKey("image_max_width_dp")
        val imageMaxHeightDp = intPreferencesKey("image_max_height_dp")
        val markdownHeadingSizesSp = stringPreferencesKey("markdown_heading_sizes_sp_v1")
        val mealImageCompressionEnabled = booleanPreferencesKey("meal_image_compression_enabled")
        val mealImageCompressionQuality = intPreferencesKey("meal_image_compression_quality")
        val saveOriginalToGallery = booleanPreferencesKey("save_original_to_gallery")
        val photoLocationEnabled = booleanPreferencesKey("photo_location_enabled")
        val browserHomeUrl = stringPreferencesKey("browser_home_url")
        val lastBrowserUrl = stringPreferencesKey("last_browser_url")
        val browserTheme = stringPreferencesKey("browser_theme")
        val browserDesktopMode = booleanPreferencesKey("browser_desktop_mode")
        val thoughtSplitRatio = floatPreferencesKey("thought_split_ratio")
        val thoughtRowHeightDp = intPreferencesKey("thought_row_height_dp")
        val thoughtReopenMode = stringPreferencesKey("thought_reopen_mode")
        val lastThoughtPageKey = stringPreferencesKey("last_thought_page_key")
        val thoughtDisplayMode = stringPreferencesKey("thought_display_mode")
        val thoughtHighlightColorArgb = intPreferencesKey("thought_highlight_color_argb")
        val thoughtEditorMaxHeightDp = intPreferencesKey("thought_editor_max_height_dp")
        val vaultRowHeightDp = intPreferencesKey("vault_row_height_dp")
        val poetryFontUri = stringPreferencesKey("poetry_font_uri")
        val poetryFontSizeSp = floatPreferencesKey("poetry_font_size_sp")
        val poetryLineSpacing = floatPreferencesKey("poetry_line_spacing")
        val poetryTextAlignment = stringPreferencesKey("poetry_text_alignment")
        val poetryShowSource = booleanPreferencesKey("poetry_show_source")
        val poetryShowQuoteMark = booleanPreferencesKey("poetry_show_quote_mark")
        val poetrySevenCharacterWrapEnabled =
            booleanPreferencesKey("poetry_seven_character_wrap_enabled")
        val mealCalendarImageMaxHeightDp = intPreferencesKey("meal_calendar_image_max_height_dp")
        val mealCalendarShowCaptions = booleanPreferencesKey("meal_calendar_show_captions")
        val mealCalendarWrapEnabled = booleanPreferencesKey("meal_calendar_wrap_enabled")
        val mealCalendarPhotosPerRow = stringPreferencesKey("meal_calendar_photos_per_row")
        val mealPhotoFilterEnabled = booleanPreferencesKey("meal_photo_filter_enabled")
        val mealPhotoFilterBrightness = floatPreferencesKey("meal_photo_filter_brightness")
        val mealPhotoFilterContrast = floatPreferencesKey("meal_photo_filter_contrast")
        val mealPhotoFilterSaturation = floatPreferencesKey("meal_photo_filter_saturation")
        val mealPhotoFilterWarmth = floatPreferencesKey("meal_photo_filter_warmth")
        val mealPhotoFilterTint = floatPreferencesKey("meal_photo_filter_tint")
        val mealButtonsUseIcons = booleanPreferencesKey("meal_buttons_use_icons")
        val mealButtonIcons = stringPreferencesKey("meal_button_icons")
        val dailyEventTemplates = stringPreferencesKey("daily_event_templates")
        val rssSubscriptions = stringPreferencesKey("rss_subscriptions")
        val rssMaxItemsPerFeed = intPreferencesKey("rss_max_items_per_feed")
        val rssShowSummaries = booleanPreferencesKey("rss_show_summaries")
        val aiEndpointUrl = stringPreferencesKey("ai_endpoint_url")
        val aiModel = stringPreferencesKey("ai_model")
        val aiSystemPrompt = stringPreferencesKey("ai_system_prompt")
        val aiTemperature = floatPreferencesKey("ai_temperature")
        val aiAllowInsecureHttp = booleanPreferencesKey("ai_allow_insecure_http")
        val aiConfigs = stringPreferencesKey("ai_configs_v2")
        val aiChatConfigId = stringPreferencesKey("ai_chat_config_id")
        val agentEnabledSources = stringSetPreferencesKey("agent_enabled_sources_v1")
        val agentPermissionMode = stringPreferencesKey("agent_permission_mode")
        val calorieEstimationEnabled = booleanPreferencesKey("calorie_estimation_enabled")
        val calorieTextConfigId = stringPreferencesKey("calorie_text_config_id")
        val calorieImageConfigId = stringPreferencesKey("calorie_image_config_id")
        val calorieVisionPrompt = stringPreferencesKey("calorie_vision_prompt")
        val calorieTextPrompt = stringPreferencesKey("calorie_text_prompt")
        val usageTrackingEnabled = booleanPreferencesKey("usage_tracking_enabled")
        val stepTrackingEnabled = booleanPreferencesKey("step_tracking_enabled")
        val navigationIntroAcknowledged = booleanPreferencesKey("navigation_intro_acknowledged")
        val navItems = stringPreferencesKey("nav_items")
        val morePageOrder = stringPreferencesKey("more_page_order")
        val defaultPage = stringPreferencesKey("default_page")
        val bottomNavShowLabels = booleanPreferencesKey("bottom_nav_show_labels")
        val musicVisualizerEnabled = booleanPreferencesKey("music_visualizer_enabled")
        val musicVisualizerStyle = stringPreferencesKey("music_visualizer_style")
        val musicVisualizerFrequencyMode = stringPreferencesKey("music_visualizer_frequency_mode")
        val musicVisualizerMinFrequencyHz = intPreferencesKey("music_visualizer_min_frequency_hz")
        val musicVisualizerMaxFrequencyHz = intPreferencesKey("music_visualizer_max_frequency_hz")
        val game2048AnimationSpeed = stringPreferencesKey("game_2048_animation_speed")
        val morePageShowDescriptions = booleanPreferencesKey("more_page_show_descriptions")
        val homeWidgetBordersEnabled = booleanPreferencesKey("home_widget_borders_enabled")
        val homeWidgets = stringPreferencesKey("home_widgets")
        val homeGameShortcuts = stringPreferencesKey("home_game_shortcuts")
        val mealPhotosWidgetMigrated = booleanPreferencesKey("meal_photos_widget_migrated")
        val dailyRecordsWidgetMigrated = booleanPreferencesKey("daily_records_widget_migrated")
        val homeModulesV26Migrated = booleanPreferencesKey("home_modules_v26_migrated")
        val homeCloudSyncWidgetMigrated = booleanPreferencesKey("home_cloud_sync_widget_migrated")
        val homeWidgetTitles = stringPreferencesKey("home_widget_titles")
        val desktopWidgetConfigs = stringPreferencesKey("desktop_widget_configs_v1")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::decode)

    private fun decode(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        val defaultDiaryFoldersGrantUri = prefs[Keys.defaultDiaryFoldersGrantUri]
        val nav = decodeNav(prefs[Keys.navItems])
        val cloudSyncConfigs = decodeCloudSyncConfigs(prefs[Keys.cloudSyncConfigs])
        val visibleIds = nav.filter { it.visible || it.id == NavItemId.SETTINGS }.map { it.id }.toSet()
        val requestedDefault = prefs[Keys.defaultPage].enumValueOr(defaults.defaultPage)
        val decodedConfigs = decodeAiConfigs(prefs[Keys.aiConfigs]).ifEmpty {
            prefs[Keys.aiModel]?.trim()?.takeIf(String::isNotEmpty)?.let { legacyModel ->
                listOf(AiModelConfig("legacy-text", "文字模型", AiModelType.TEXT,
                    prefs[Keys.aiEndpointUrl] ?: defaults.aiEndpointUrl, legacyModel, true,
                    prefs[Keys.aiAllowInsecureHttp] ?: false, prefs[Keys.aiTemperature] ?: 0.7f,
                    prefs[Keys.aiSystemPrompt] ?: defaults.aiSystemPrompt))
            }.orEmpty()
        }
        val requestedChatId = prefs[Keys.aiChatConfigId]
        val chatId = resolveAiConfigId(decodedConfigs, requestedChatId, AiModelType.TEXT, fallbackToAny = true)
        val requestedCalorieTextId = prefs[Keys.calorieTextConfigId]
        val calorieTextId = resolveAiConfigId(decodedConfigs, requestedCalorieTextId, AiModelType.TEXT)
        val requestedCalorieImageId = prefs[Keys.calorieImageConfigId]
        val calorieImageId = resolveAiConfigId(decodedConfigs, requestedCalorieImageId, AiModelType.IMAGE)
        val normalizedConfigs = decodedConfigs.map { it.copy(enabled = true) }
        val musicVisualizerFrequencyBounds = normalizeMusicVisualizerFrequencyBounds(
            prefs[Keys.musicVisualizerMinFrequencyHz]
                ?: defaults.musicVisualizerMinFrequencyHz,
            prefs[Keys.musicVisualizerMaxFrequencyHz]
                ?: defaults.musicVisualizerMaxFrequencyHz,
        )
        return AppSettings(
            visualStyle = prefs[Keys.visualStyle].enumValueOr(defaults.visualStyle),
            customTheme = decodeCustomTheme(prefs[Keys.customTheme], defaults.customTheme),
            darkMode = prefs[Keys.darkMode].enumValueOr(defaults.darkMode),
            appLanguage = prefs[Keys.appLanguage].enumValueOr(defaults.appLanguage),
            userName = normalizeUserName(prefs[Keys.userName] ?: defaults.userName),
            homeGreetings = decodeHomeGreetings(
                prefs[Keys.homeGreetings],
                defaults.homeGreetings,
            ),
            themeColorArgb = prefs[Keys.themeColorArgb] ?: defaults.themeColorArgb,
            themeSecondaryColorsArgb = decodeThemeSecondaryColors(
                prefs[Keys.themeSecondaryColorsArgb],
                defaults.themeSecondaryColorsArgb,
            ),
            fontScale = normalizeFontScale(prefs[Keys.fontScale], defaults.fontScale),
            compactMode = prefs[Keys.compactMode] ?: defaults.compactMode,
            backgroundImageUri = prefs[Keys.backgroundImageUri]
                ?.takeIf(::hasPersistedReadAccess),
            backgroundImageOpacity = normalizeAppBackgroundOpacity(
                prefs[Keys.backgroundImageOpacity],
                defaults.backgroundImageOpacity,
            ),
            backgroundImageBlurDp = normalizeAppBackgroundBlur(
                prefs[Keys.backgroundImageBlurDp],
                defaults.backgroundImageBlurDp,
            ),
            tutorialModeEnabled = prefs[Keys.tutorialModeEnabled]
                ?: defaults.tutorialModeEnabled,
            tutorialAcknowledgedPages = normalizeTutorialPageIds(
                prefs[Keys.tutorialAcknowledgedPages].orEmpty(),
            ),
            useChineseLauncherName = prefs[Keys.useChineseLauncherName]
                ?: defaults.useChineseLauncherName,
            launcherIcon = prefs[Keys.launcherIcon].enumValueOr(defaults.launcherIcon),
            backupTreeUri = prefs[Keys.backupTreeUri]?.takeIf(::hasPersistedTreeAccess),
            cloudSyncEnabled = (prefs[Keys.cloudSyncEnabled] ?: false) &&
                cloudSyncConfigs.any { it.enabled && it.selectedContents.isNotEmpty() },
            cloudSyncConfigs = cloudSyncConfigs,
            diaryTreeUri = prefs[Keys.diaryTreeUri]?.takeIf { raw ->
                hasPersistedTreeAccess(raw, defaultDiaryFoldersGrantUri)
            },
            mediaTreeUri = prefs[Keys.mediaTreeUri]?.takeIf { raw ->
                hasPersistedTreeAccess(raw, defaultDiaryFoldersGrantUri)
            },
            notesTreeUri = prefs[Keys.notesTreeUri]?.takeIf(::hasPersistedTreeAccess),
            fileNamePattern = (prefs[Keys.fileNamePattern] ?: defaults.fileNamePattern)
                .let { if (it == "yyyy-MM-dd '日记'") defaults.fileNamePattern else it },
            markdownTemplate = prefs[Keys.markdownTemplate] ?: defaults.markdownTemplate,
            imageNamePattern = prefs[Keys.imageNamePattern] ?: defaults.imageNamePattern,
            imageMaxWidthDp = (prefs[Keys.imageMaxWidthDp] ?: defaults.imageMaxWidthDp).coerceIn(120, 2400),
            imageMaxHeightDp = (prefs[Keys.imageMaxHeightDp] ?: defaults.imageMaxHeightDp).coerceIn(120, 2400),
            markdownHeadingSizesSp = decodeMarkdownHeadingSizes(
                prefs[Keys.markdownHeadingSizesSp],
                defaults.markdownHeadingSizesSp,
            ),
            mealImageCompressionEnabled = prefs[Keys.mealImageCompressionEnabled]
                ?: defaults.mealImageCompressionEnabled,
            mealImageCompressionQuality = (prefs[Keys.mealImageCompressionQuality]
                ?: defaults.mealImageCompressionQuality).coerceIn(30, 95),
            saveOriginalToGallery = prefs[Keys.saveOriginalToGallery]
                ?: defaults.saveOriginalToGallery,
            photoLocationEnabled = prefs[Keys.photoLocationEnabled]
                ?: defaults.photoLocationEnabled,
            browserHomeUrl = prefs[Keys.browserHomeUrl]?.takeIf { it.isNotBlank() } ?: defaults.browserHomeUrl,
            lastBrowserUrl = prefs[Keys.lastBrowserUrl],
            browserTheme = prefs[Keys.browserTheme].enumValueOr(defaults.browserTheme),
            browserDesktopMode = prefs[Keys.browserDesktopMode] ?: defaults.browserDesktopMode,
            thoughtSplitRatio = (prefs[Keys.thoughtSplitRatio] ?: defaults.thoughtSplitRatio).coerceIn(0.25f, 0.8f),
            thoughtRowHeightDp = (prefs[Keys.thoughtRowHeightDp] ?: defaults.thoughtRowHeightDp).coerceIn(48, 120),
            thoughtReopenMode = prefs[Keys.thoughtReopenMode].enumValueOr(defaults.thoughtReopenMode),
            lastThoughtPageKey = normalizeThoughtPageKey(
                prefs[Keys.lastThoughtPageKey] ?: defaults.lastThoughtPageKey,
            ),
            thoughtDisplayMode = prefs[Keys.thoughtDisplayMode].enumValueOr(defaults.thoughtDisplayMode),
            thoughtHighlightColorArgb = (prefs[Keys.thoughtHighlightColorArgb]
                ?: defaults.thoughtHighlightColorArgb) or 0xFF000000.toInt(),
            thoughtEditorMaxHeightDp = (prefs[Keys.thoughtEditorMaxHeightDp]
                ?: defaults.thoughtEditorMaxHeightDp)
                .coerceIn(MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP, MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP),
            vaultRowHeightDp = (prefs[Keys.vaultRowHeightDp] ?: defaults.vaultRowHeightDp)
                .coerceIn(MIN_VAULT_ROW_HEIGHT_DP, MAX_VAULT_ROW_HEIGHT_DP),
            poetryFontUri = prefs[Keys.poetryFontUri]?.takeIf(::hasPersistedReadAccess),
            poetryFontSizeSp = normalizePoetryFontSize(
                prefs[Keys.poetryFontSizeSp],
                defaults.poetryFontSizeSp,
            ),
            poetryLineSpacing = normalizePoetryLineSpacing(
                prefs[Keys.poetryLineSpacing],
                defaults.poetryLineSpacing,
            ),
            poetryTextAlignment = prefs[Keys.poetryTextAlignment]
                .enumValueOr(defaults.poetryTextAlignment),
            poetryShowSource = prefs[Keys.poetryShowSource] ?: defaults.poetryShowSource,
            poetryShowQuoteMark = prefs[Keys.poetryShowQuoteMark] ?: defaults.poetryShowQuoteMark,
            poetrySevenCharacterWrapEnabled = prefs[Keys.poetrySevenCharacterWrapEnabled]
                ?: defaults.poetrySevenCharacterWrapEnabled,
            mealCalendarImageMaxHeightDp = (prefs[Keys.mealCalendarImageMaxHeightDp]
                ?: defaults.mealCalendarImageMaxHeightDp).coerceIn(80, 320),
            mealCalendarShowCaptions = prefs[Keys.mealCalendarShowCaptions]
                ?: defaults.mealCalendarShowCaptions,
            mealCalendarWrapEnabled = prefs[Keys.mealCalendarWrapEnabled]
                ?: defaults.mealCalendarWrapEnabled,
            mealCalendarPhotosPerRow = prefs[Keys.mealCalendarPhotosPerRow]
                .enumValueOr(defaults.mealCalendarPhotosPerRow),
            mealPhotoFilter = MealPhotoFilterSettings(
                enabled = prefs[Keys.mealPhotoFilterEnabled] ?: defaults.mealPhotoFilter.enabled,
                brightness = prefs[Keys.mealPhotoFilterBrightness]
                    ?: defaults.mealPhotoFilter.brightness,
                contrast = prefs[Keys.mealPhotoFilterContrast] ?: defaults.mealPhotoFilter.contrast,
                saturation = prefs[Keys.mealPhotoFilterSaturation]
                    ?: defaults.mealPhotoFilter.saturation,
                warmth = prefs[Keys.mealPhotoFilterWarmth] ?: defaults.mealPhotoFilter.warmth,
                tint = prefs[Keys.mealPhotoFilterTint] ?: defaults.mealPhotoFilter.tint,
            ).normalized(),
            mealButtonsUseIcons = prefs[Keys.mealButtonsUseIcons] ?: defaults.mealButtonsUseIcons,
            mealButtonIcons = decodeMealButtonIcons(prefs[Keys.mealButtonIcons], defaults.mealButtonIcons),
            dailyEventTemplates = decodeDailyEventTemplates(prefs[Keys.dailyEventTemplates]),
            rssSubscriptions = decodeRssSubscriptions(prefs[Keys.rssSubscriptions]),
            rssMaxItemsPerFeed = (prefs[Keys.rssMaxItemsPerFeed]
                ?: defaults.rssMaxItemsPerFeed).coerceIn(10, 200),
            rssShowSummaries = prefs[Keys.rssShowSummaries] ?: defaults.rssShowSummaries,
            aiEndpointUrl = prefs[Keys.aiEndpointUrl]?.trim()?.take(MAX_URL_CHARS)?.takeIf(String::isNotEmpty)
                ?: defaults.aiEndpointUrl,
            aiModel = prefs[Keys.aiModel]?.trim()?.take(MAX_AI_MODEL_CHARS).orEmpty(),
            aiSystemPrompt = (prefs[Keys.aiSystemPrompt] ?: defaults.aiSystemPrompt)
                .take(MAX_AI_SYSTEM_PROMPT_CHARS),
            aiTemperature = (prefs[Keys.aiTemperature] ?: defaults.aiTemperature)
                .takeIf(Float::isFinite)?.coerceIn(0f, 2f) ?: defaults.aiTemperature,
            aiAllowInsecureHttp = prefs[Keys.aiAllowInsecureHttp] ?: defaults.aiAllowInsecureHttp,
            aiConfigs = normalizedConfigs,
            aiChatConfigId = chatId,
            agentEnabledSources = prefs[Keys.agentEnabledSources]
                .orEmpty()
                .mapNotNullTo(linkedSetOf()) { value ->
                    AgentDataSource.entries.firstOrNull { it.wireValue == value }
                },
            agentPermissionMode = prefs[Keys.agentPermissionMode]
                .enumValueOr(defaults.agentPermissionMode),
            calorieEstimationEnabled = (prefs[Keys.calorieEstimationEnabled] ?: false) &&
                calorieTextId != null && calorieImageId != null,
            calorieTextConfigId = calorieTextId,
            calorieImageConfigId = calorieImageId,
            calorieVisionPrompt = normalizeCalorieVisionPrompt(
                prefs[Keys.calorieVisionPrompt] ?: defaults.calorieVisionPrompt,
            ),
            calorieTextPrompt = normalizeCalorieTextPrompt(
                prefs[Keys.calorieTextPrompt] ?: defaults.calorieTextPrompt,
            ),
            usageTrackingEnabled = prefs[Keys.usageTrackingEnabled]
                ?: defaults.usageTrackingEnabled,
            stepTrackingEnabled = prefs[Keys.stepTrackingEnabled]
                ?: defaults.stepTrackingEnabled,
            navigationIntroAcknowledged = prefs[Keys.navigationIntroAcknowledged]
                ?: defaults.navigationIntroAcknowledged,
            navItems = nav,
            morePageOrder = decodeMorePageOrder(prefs[Keys.morePageOrder], nav),
            defaultPage = requestedDefault.takeIf { it in visibleIds } ?: visibleIds.firstOrNull() ?: NavItemId.SETTINGS,
            bottomNavShowLabels = prefs[Keys.bottomNavShowLabels] ?: defaults.bottomNavShowLabels,
            musicVisualizerEnabled = prefs[Keys.musicVisualizerEnabled]
                ?: defaults.musicVisualizerEnabled,
            musicVisualizerStyle = prefs[Keys.musicVisualizerStyle]
                .enumValueOr(defaults.musicVisualizerStyle),
            musicVisualizerFrequencyMode = prefs[Keys.musicVisualizerFrequencyMode]
                .enumValueOr(defaults.musicVisualizerFrequencyMode),
            musicVisualizerMinFrequencyHz = musicVisualizerFrequencyBounds.first,
            musicVisualizerMaxFrequencyHz = musicVisualizerFrequencyBounds.second,
            game2048AnimationSpeed = prefs[Keys.game2048AnimationSpeed]
                .enumValueOr(defaults.game2048AnimationSpeed),
            morePageShowDescriptions = prefs[Keys.morePageShowDescriptions]
                ?: defaults.morePageShowDescriptions,
            homeWidgetBordersEnabled = prefs[Keys.homeWidgetBordersEnabled]
                ?: defaults.homeWidgetBordersEnabled,
            // The widget migrations only apply to users with a stored list from an older
            // release; a fresh install must keep the small first-launch preset untouched.
            homeWidgets = if (prefs[Keys.homeWidgets] == null) {
                defaults.homeWidgets
            } else {
                migrateHomeCloudSyncWidget(
                    items = migrateHomeModulesV26(
                        items = migrateDailyRecordsWidget(
                            items = migrateMealPhotosWidget(
                                items = decodeWidgets(prefs[Keys.homeWidgets], defaults.homeWidgets),
                                migrated = prefs[Keys.mealPhotosWidgetMigrated] == true,
                            ),
                            migrated = prefs[Keys.dailyRecordsWidgetMigrated] == true,
                        ),
                        migrated = prefs[Keys.homeModulesV26Migrated] == true,
                    ),
                    migrated = prefs[Keys.homeCloudSyncWidgetMigrated] == true,
                )
            },
            homeGameShortcuts = if (prefs[Keys.homeGameShortcuts] == null) {
                defaults.homeGameShortcuts
            } else {
                normalizeHomeGameShortcutIds(
                    decodeStringList(prefs[Keys.homeGameShortcuts], defaults.homeGameShortcuts),
                )
            },
            homeWidgetTitles = if (prefs[Keys.homeWidgetTitles] == null) {
                defaults.homeWidgetTitles
            } else {
                migrateHomeCloudSyncWidget(
                    items = migrateHomeModulesV26(
                        items = migrateDailyRecordsWidget(
                            items = decodeStringList(
                                prefs[Keys.homeWidgetTitles],
                                defaults.homeWidgetTitles,
                            ),
                            migrated = prefs[Keys.dailyRecordsWidgetMigrated] == true,
                        ),
                        migrated = prefs[Keys.homeModulesV26Migrated] == true,
                    ),
                    migrated = prefs[Keys.homeCloudSyncWidgetMigrated] == true,
                )
            },
            desktopWidgetConfigs = decodeDesktopWidgetConfigs(
                prefs[Keys.desktopWidgetConfigs],
            ),
        )
    }

    suspend fun setVisualStyle(value: VisualStyle) = set(Keys.visualStyle, value.name)
    suspend fun setDarkMode(value: DarkMode) = set(Keys.darkMode, value.name)
    suspend fun setAppLanguage(value: AppLanguage) = set(Keys.appLanguage, value.name)
    suspend fun setUserName(value: String) = set(Keys.userName, normalizeUserName(value))
    suspend fun setHomeGreetingSettings(
        userName: String,
        greetings: List<HomeGreetingTemplate>,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.userName] = normalizeUserName(userName)
            prefs[Keys.homeGreetings] = encodeHomeGreetings(normalizeHomeGreetings(greetings))
        }
    }
    suspend fun setThemeColor(value: Int) = set(Keys.themeColorArgb, value or 0xFF000000.toInt())
    suspend fun setThemeSecondaryColors(value: List<Int>) = set(
        Keys.themeSecondaryColorsArgb,
        encodeThemeSecondaryColors(normalizeThemeSecondaryColors(value)),
    )
    suspend fun setFontScale(value: Float) = set(Keys.fontScale, normalizeFontScale(value))
    suspend fun setCompactMode(value: Boolean) = set(Keys.compactMode, value)
    suspend fun setAppearanceSettings(
        visualStyle: VisualStyle,
        customTheme: CustomThemeSettings,
        darkMode: DarkMode,
        appLanguage: AppLanguage,
        themeColorArgb: Int,
        themeSecondaryColorsArgb: List<Int>,
        fontScale: Float,
        compactMode: Boolean,
        backgroundImageUri: String?,
        backgroundImageOpacity: Float,
        backgroundImageBlurDp: Float,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.visualStyle] = visualStyle.name
            prefs[Keys.customTheme] = encodeCustomTheme(customTheme)
            prefs[Keys.darkMode] = darkMode.name
            prefs[Keys.appLanguage] = appLanguage.name
            prefs[Keys.themeColorArgb] = opaqueArgb(themeColorArgb)
            prefs[Keys.themeSecondaryColorsArgb] = encodeThemeSecondaryColors(
                normalizeThemeSecondaryColors(themeSecondaryColorsArgb),
            )
            prefs[Keys.fontScale] = normalizeFontScale(fontScale)
            prefs[Keys.compactMode] = compactMode
            prefs.setOrRemove(
                Keys.backgroundImageUri,
                backgroundImageUri?.takeIf(::hasPersistedReadAccess),
            )
            prefs[Keys.backgroundImageOpacity] = normalizeAppBackgroundOpacity(
                backgroundImageOpacity,
            )
            prefs[Keys.backgroundImageBlurDp] = normalizeAppBackgroundBlur(
                backgroundImageBlurDp,
            )
        }
    }
    suspend fun setUseChineseLauncherName(value: Boolean) = set(Keys.useChineseLauncherName, value)
    suspend fun setLauncherIcon(value: LauncherIcon) = set(Keys.launcherIcon, value.name)
    suspend fun setBackupTreeUri(value: String?) {
        context.settingsDataStore.edit {
            it.setOrRemove(Keys.backupTreeUri, value?.takeIf(String::isNotBlank))
        }
    }
    suspend fun setCloudSyncSettings(enabled: Boolean, configs: List<CloudSyncConfig>) {
        val normalized = normalizeCloudSyncConfigs(configs)
        require(normalized.size == configs.distinctBy { it.id.trim() }.size) {
            "同步配置无效，请检查名称、地址、远端目录和内容选择。"
        }
        val safeEnabled = enabled && normalized.any {
            it.enabled && it.selectedContents.isNotEmpty()
        }
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.cloudSyncEnabled] = safeEnabled
            prefs[Keys.cloudSyncConfigs] = encodeCloudSyncConfigs(normalized)
        }
    }
    suspend fun setDiaryTreeUri(value: String) = set(Keys.diaryTreeUri, value)
    suspend fun setMediaTreeUri(value: String) = set(Keys.mediaTreeUri, value)
    suspend fun setDefaultDiaryFolders(
        grantTreeUri: String,
        diaryTreeUri: String,
        mediaTreeUri: String,
    ) {
        require(hasPersistedTreeAccess(grantTreeUri)) { "Default folder grant is unavailable" }
        require(hasPersistedTreeAccess(diaryTreeUri, grantTreeUri)) {
            "Default diary folder grant is unavailable"
        }
        require(hasPersistedTreeAccess(mediaTreeUri, grantTreeUri)) {
            "Default media folder grant is unavailable"
        }
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.defaultDiaryFoldersGrantUri] = grantTreeUri
            prefs[Keys.diaryTreeUri] = diaryTreeUri
            prefs[Keys.mediaTreeUri] = mediaTreeUri
        }
    }
    suspend fun setNotesTreeUri(value: String) = set(Keys.notesTreeUri, value)
    suspend fun setFileNamePattern(value: String) = set(Keys.fileNamePattern, value)
    suspend fun setMarkdownTemplate(value: String) = set(Keys.markdownTemplate, value)
    suspend fun setImageNamePattern(value: String) = set(Keys.imageNamePattern, value)
    suspend fun setImageMaxWidth(value: Int) = set(Keys.imageMaxWidthDp, value.coerceIn(120, 2400))
    suspend fun setImageMaxHeight(value: Int) = set(Keys.imageMaxHeightDp, value.coerceIn(120, 2400))
    suspend fun setMarkdownHeadingSizes(value: List<Float>) =
        set(Keys.markdownHeadingSizesSp, encodeMarkdownHeadingSizes(value))
    suspend fun setMealImageCompressionEnabled(value: Boolean) =
        set(Keys.mealImageCompressionEnabled, value)
    suspend fun setMealImageCompressionQuality(value: Int) =
        set(Keys.mealImageCompressionQuality, value.coerceIn(30, 95))
    suspend fun setSaveOriginalToGallery(value: Boolean) = set(Keys.saveOriginalToGallery, value)
    suspend fun setPhotoLocationEnabled(value: Boolean) = set(Keys.photoLocationEnabled, value)
    suspend fun setBrowserHomeUrl(value: String) = set(Keys.browserHomeUrl, normalizeUrl(value))
    suspend fun setLastBrowserUrl(value: String) = set(Keys.lastBrowserUrl, value)
    suspend fun setBrowserTheme(value: BrowserTheme) = set(Keys.browserTheme, value.name)
    suspend fun setBrowserDesktopMode(value: Boolean) = set(Keys.browserDesktopMode, value)
    suspend fun setThoughtSplitRatio(value: Float) = set(Keys.thoughtSplitRatio, value.coerceIn(0.25f, 0.8f))
    suspend fun setThoughtRowHeight(value: Int) = set(Keys.thoughtRowHeightDp, value.coerceIn(48, 120))
    suspend fun setVaultRowHeight(value: Int) = set(
        Keys.vaultRowHeightDp,
        value.coerceIn(MIN_VAULT_ROW_HEIGHT_DP, MAX_VAULT_ROW_HEIGHT_DP),
    )
    suspend fun setThoughtReopenMode(value: ThoughtReopenMode) = set(Keys.thoughtReopenMode, value.name)
    suspend fun setLastThoughtPageKey(value: String) =
        set(Keys.lastThoughtPageKey, normalizeThoughtPageKey(value))
    suspend fun setThoughtDisplayMode(value: ThoughtDisplayMode) = set(Keys.thoughtDisplayMode, value.name)
    suspend fun setThoughtSettings(
        rowHeightDp: Int,
        reopenMode: ThoughtReopenMode,
        displayMode: ThoughtDisplayMode,
        highlightColorArgb: Int,
        editorMaxHeightDp: Int,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.thoughtRowHeightDp] = rowHeightDp.coerceIn(48, 120)
            prefs[Keys.thoughtReopenMode] = reopenMode.name
            prefs[Keys.thoughtDisplayMode] = displayMode.name
            prefs[Keys.thoughtHighlightColorArgb] = highlightColorArgb or 0xFF000000.toInt()
            prefs[Keys.thoughtEditorMaxHeightDp] = editorMaxHeightDp
                .coerceIn(MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP, MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP)
        }
    }
    suspend fun setPoetryDisplaySettings(
        fontUri: String?,
        fontSizeSp: Float,
        lineSpacing: Float,
        textAlignment: PoetryTextAlignment,
        showSource: Boolean,
        showQuoteMark: Boolean,
        sevenCharacterWrapEnabled: Boolean,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs.setOrRemove(
                Keys.poetryFontUri,
                fontUri?.takeIf(::hasPersistedReadAccess),
            )
            prefs[Keys.poetryFontSizeSp] = normalizePoetryFontSize(fontSizeSp)
            prefs[Keys.poetryLineSpacing] = normalizePoetryLineSpacing(lineSpacing)
            prefs[Keys.poetryTextAlignment] = textAlignment.name
            prefs[Keys.poetryShowSource] = showSource
            prefs[Keys.poetryShowQuoteMark] = showQuoteMark
            prefs[Keys.poetrySevenCharacterWrapEnabled] = sevenCharacterWrapEnabled
        }
    }
    suspend fun setMealCalendarImageMaxHeight(value: Int) =
        set(Keys.mealCalendarImageMaxHeightDp, value.coerceIn(80, 320))
    suspend fun setMealCalendarShowCaptions(value: Boolean) = set(Keys.mealCalendarShowCaptions, value)
    suspend fun setMealCalendarWrap(enabled: Boolean, photosPerRow: MealPhotosPerRow) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.mealCalendarWrapEnabled] = enabled
            prefs[Keys.mealCalendarPhotosPerRow] = photosPerRow.name
        }
    }
    suspend fun setMealPhotoFilter(value: MealPhotoFilterSettings) {
        val normalized = value.normalized()
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.mealPhotoFilterEnabled] = normalized.enabled
            prefs[Keys.mealPhotoFilterBrightness] = normalized.brightness
            prefs[Keys.mealPhotoFilterContrast] = normalized.contrast
            prefs[Keys.mealPhotoFilterSaturation] = normalized.saturation
            prefs[Keys.mealPhotoFilterWarmth] = normalized.warmth
            prefs[Keys.mealPhotoFilterTint] = normalized.tint
        }
    }
    suspend fun setMealButtonsUseIcons(value: Boolean) = set(Keys.mealButtonsUseIcons, value)
    suspend fun setMealButtonIcons(value: List<String>) =
        set(Keys.mealButtonIcons, encodeStringList(normalizeMealButtonIcons(value)))
    suspend fun setDailyEventTemplates(value: List<DailyEventTemplate>) =
        set(Keys.dailyEventTemplates, encodeDailyEventTemplates(normalizeDailyEventTemplates(value)))
    suspend fun addDailyEventTemplate(value: DailyEventTemplate) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeDailyEventTemplates(prefs[Keys.dailyEventTemplates])
            prefs[Keys.dailyEventTemplates] = encodeDailyEventTemplates(
                normalizeDailyEventTemplates(current + value),
            )
        }
    }
    suspend fun updateDailyEventTemplate(value: DailyEventTemplate) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeDailyEventTemplates(prefs[Keys.dailyEventTemplates])
            prefs[Keys.dailyEventTemplates] = encodeDailyEventTemplates(
                normalizeDailyEventTemplates(current.map { if (it.id == value.id) value else it }),
            )
        }
    }
    suspend fun removeDailyEventTemplate(id: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeDailyEventTemplates(prefs[Keys.dailyEventTemplates])
            prefs[Keys.dailyEventTemplates] = encodeDailyEventTemplates(current.filterNot { it.id == id })
        }
    }
    suspend fun setRssSubscriptions(value: List<RssSubscription>) =
        set(Keys.rssSubscriptions, encodeRssSubscriptions(normalizeRssSubscriptions(value)))
    suspend fun setRssSettings(maxItemsPerFeed: Int, showSummaries: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.rssMaxItemsPerFeed] = maxItemsPerFeed.coerceIn(10, 200)
            prefs[Keys.rssShowSummaries] = showSummaries
        }
    }
    suspend fun setAiConfigs(configs: List<AiModelConfig>) =
        set(Keys.aiConfigs, encodeAiConfigs(configs.map { it.copy(enabled = true) }))

    suspend fun setAiChatConfigId(id: String?) {
        context.settingsDataStore.edit { it.setOrRemove(Keys.aiChatConfigId, id?.takeIf(String::isNotBlank)) }
    }

    suspend fun setAgentEnabledSources(sources: Set<AgentDataSource>) {
        set(Keys.agentEnabledSources, sources.mapTo(linkedSetOf(), AgentDataSource::wireValue))
    }

    suspend fun setAgentPermissionMode(mode: AgentPermissionMode) =
        set(Keys.agentPermissionMode, mode.name)

    suspend fun setCalorieEstimationSettings(
        enabled: Boolean, textConfigId: String?, imageConfigId: String?,
        visionPrompt: String, textPrompt: String,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.calorieEstimationEnabled] = enabled
            prefs.setOrRemove(Keys.calorieTextConfigId, textConfigId?.takeIf(String::isNotBlank))
            prefs.setOrRemove(Keys.calorieImageConfigId, imageConfigId?.takeIf(String::isNotBlank))
            prefs[Keys.calorieVisionPrompt] = normalizeCalorieVisionPrompt(visionPrompt)
                .take(MAX_AI_SYSTEM_PROMPT_CHARS)
            prefs[Keys.calorieTextPrompt] = normalizeCalorieTextPrompt(textPrompt)
                .take(MAX_AI_SYSTEM_PROMPT_CHARS)
        }
    }
    suspend fun setUsageTrackingEnabled(value: Boolean) = set(Keys.usageTrackingEnabled, value)
    suspend fun setStepTrackingEnabled(value: Boolean) = set(Keys.stepTrackingEnabled, value)
    suspend fun acknowledgeNavigationIntro() = set(Keys.navigationIntroAcknowledged, true)
    suspend fun setTutorialModeEnabled(value: Boolean) = set(Keys.tutorialModeEnabled, value)
    suspend fun acknowledgeTutorialPage(pageId: String) {
        val normalized = normalizeTutorialPageIds(listOf(pageId)).firstOrNull() ?: return
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.tutorialAcknowledgedPages] = normalizeTutorialPageIds(
                prefs[Keys.tutorialAcknowledgedPages].orEmpty() + normalized,
            )
        }
    }
    suspend fun resetTutorialPages() {
        context.settingsDataStore.edit { prefs -> prefs.remove(Keys.tutorialAcknowledgedPages) }
    }
    suspend fun setDefaultPage(value: NavItemId) = set(Keys.defaultPage, value.name)
    suspend fun setBottomNavShowLabels(value: Boolean) = set(Keys.bottomNavShowLabels, value)
    suspend fun setGame2048AnimationSpeed(value: Game2048AnimationSpeed) =
        set(Keys.game2048AnimationSpeed, value.name)
    suspend fun setHomeWidgetBordersEnabled(value: Boolean) = set(Keys.homeWidgetBordersEnabled, value)
    suspend fun setHomePageSettings(
        userName: String,
        widgetBordersEnabled: Boolean,
        widgets: List<String>,
        gameShortcuts: List<String>,
        visibleWidgetTitles: List<String>,
        mealButtonsUseIcons: Boolean,
        mealButtonIcons: List<String>,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.userName] = normalizeUserName(userName)
            prefs[Keys.homeWidgetBordersEnabled] = widgetBordersEnabled
            prefs[Keys.homeWidgets] = encodeStringList(widgets.distinct())
            prefs[Keys.homeGameShortcuts] = encodeStringList(
                normalizeHomeGameShortcutIds(gameShortcuts),
            )
            prefs[Keys.mealPhotosWidgetMigrated] = true
            prefs[Keys.dailyRecordsWidgetMigrated] = true
            prefs[Keys.homeModulesV26Migrated] = true
            prefs[Keys.homeCloudSyncWidgetMigrated] = true
            prefs[Keys.homeWidgetTitles] = encodeStringList(visibleWidgetTitles.distinct())
            prefs[Keys.mealButtonsUseIcons] = mealButtonsUseIcons
            prefs[Keys.mealButtonIcons] = encodeStringList(normalizeMealButtonIcons(mealButtonIcons))
        }
    }
    suspend fun setHomeWidgets(value: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.homeWidgets] = encodeStringList(value.distinct())
            prefs[Keys.mealPhotosWidgetMigrated] = true
            prefs[Keys.dailyRecordsWidgetMigrated] = true
            prefs[Keys.homeModulesV26Migrated] = true
            prefs[Keys.homeCloudSyncWidgetMigrated] = true
        }
    }
    suspend fun setHomeWidgetTitles(value: List<String>) =
        set(Keys.homeWidgetTitles, encodeStringList(value.distinct()))

    suspend fun setDesktopWidgetConfigs(value: List<DesktopWidgetConfig>) =
        set(
            Keys.desktopWidgetConfigs,
            encodeDesktopWidgetConfigs(normalizeDesktopWidgetConfigs(value)),
        )

    suspend fun setNavItems(value: List<NavItemConfig>) {
        context.settingsDataStore.edit { prefs ->
            migrateMorePageOrderIfNeeded(prefs)
            prefs[Keys.navItems] = encodeNav(normalizeNavItems(value))
        }
    }

    suspend fun setNavigationSettings(
        defaultPage: NavItemId,
        items: List<NavItemConfig>,
        showLabels: Boolean,
        musicVisualizerEnabled: Boolean,
        musicVisualizerStyle: MusicVisualizerStyle,
        musicVisualizerFrequencyMode: MusicVisualizerFrequencyMode,
        musicVisualizerMinFrequencyHz: Int,
        musicVisualizerMaxFrequencyHz: Int,
    ) {
        val normalized = normalizeNavItems(items)
        val visibleIds = normalized.filter { it.visible || it.id == NavItemId.SETTINGS }.map { it.id }.toSet()
        val safeDefault = defaultPage.takeIf { it in visibleIds } ?: visibleIds.firstOrNull() ?: NavItemId.SETTINGS
        val frequencyBounds = normalizeMusicVisualizerFrequencyBounds(
            musicVisualizerMinFrequencyHz,
            musicVisualizerMaxFrequencyHz,
        )
        context.settingsDataStore.edit { prefs ->
            migrateMorePageOrderIfNeeded(prefs)
            prefs[Keys.navItems] = encodeNav(normalized)
            prefs[Keys.defaultPage] = safeDefault.name
            prefs[Keys.bottomNavShowLabels] = showLabels
            prefs[Keys.musicVisualizerEnabled] = musicVisualizerEnabled
            prefs[Keys.musicVisualizerStyle] = musicVisualizerStyle.name
            prefs[Keys.musicVisualizerFrequencyMode] = musicVisualizerFrequencyMode.name
            prefs[Keys.musicVisualizerMinFrequencyHz] = frequencyBounds.first
            prefs[Keys.musicVisualizerMaxFrequencyHz] = frequencyBounds.second
        }
    }

    suspend fun setMorePageSettings(
        showDescriptions: Boolean,
        items: List<NavItemConfig>,
    ) {
        val normalized = normalizeNavItems(items)
        context.settingsDataStore.edit { prefs ->
            migrateMorePageOrderIfNeeded(prefs)
            prefs[Keys.navItems] = encodeNav(normalized)
            prefs[Keys.morePageShowDescriptions] = showDescriptions
        }
    }

    suspend fun setMorePageOrder(value: List<NavItemId>) {
        context.settingsDataStore.edit { prefs ->
            val nav = decodeNav(prefs[Keys.navItems])
            prefs[Keys.morePageOrder] = encodeMorePageOrder(
                normalizeMorePageOrder(value, nav),
            )
        }
    }

    suspend fun restoreFromBackup(value: AppSettings) {
        val normalizedNav = normalizeNavItems(value.navItems)
        val normalizedMorePageOrder = normalizeMorePageOrder(value.morePageOrder, normalizedNav)
        val normalizedMealPhotoFilter = value.mealPhotoFilter.normalized()
        val normalizedCloudSyncConfigs = normalizeCloudSyncConfigs(value.cloudSyncConfigs)
        val normalizedDesktopWidgetConfigs = normalizeDesktopWidgetConfigs(
            value.desktopWidgetConfigs,
        )
        val visibleIds = normalizedNav.filter(NavItemConfig::visible).map(NavItemConfig::id).toSet()
        val normalizedDefaultPage = value.defaultPage.takeIf(visibleIds::contains)
            ?: visibleIds.firstOrNull()
            ?: NavItemId.SETTINGS

        context.settingsDataStore.edit { prefs ->
            val currentCloudSyncConfigs = decodeCloudSyncConfigs(prefs[Keys.cloudSyncConfigs])
            val currentDesktopWidgetConfigs = decodeDesktopWidgetConfigs(
                prefs[Keys.desktopWidgetConfigs],
            )
            val restoredDesktopWidgetConfigs = normalizedDesktopWidgetConfigs.map { restored ->
                val restoredImage = restored.backgroundImageUri
                restored.copy(
                    backgroundImageUri = when {
                        restoredImage == null -> null
                        hasPersistedReadAccess(restoredImage) -> restoredImage
                        else -> currentDesktopWidgetConfigs
                            .firstOrNull { it.id == restored.id }
                            ?.backgroundImageUri
                            ?.takeIf(::hasPersistedReadAccess)
                    },
                )
            }
            val restoredCloudSyncConfigs = normalizedCloudSyncConfigs.map { restored ->
                val local = currentCloudSyncConfigs.firstOrNull { it.id == restored.id }
                if (
                    restored.serviceType == CloudSyncServiceType.S3_COMPATIBLE &&
                    local?.serviceType == CloudSyncServiceType.S3_COMPATIBLE &&
                    hasSameS3CredentialScope(local, restored)
                ) {
                    restored.copy(
                        s3AccessKey = local.s3AccessKey,
                        s3SecretKey = local.s3SecretKey,
                        s3SessionToken = local.s3SessionToken,
                    )
                } else {
                    restored
                }
            }
            prefs[Keys.visualStyle] = value.visualStyle.name
            prefs[Keys.customTheme] = encodeCustomTheme(value.customTheme)
            prefs[Keys.darkMode] = value.darkMode.name
            prefs[Keys.appLanguage] = value.appLanguage.name
            prefs[Keys.userName] = normalizeUserName(value.userName)
            prefs[Keys.homeGreetings] = encodeHomeGreetings(
                normalizeHomeGreetings(value.homeGreetings),
            )
            prefs[Keys.themeColorArgb] = value.themeColorArgb or 0xFF000000.toInt()
            prefs[Keys.themeSecondaryColorsArgb] = encodeThemeSecondaryColors(
                normalizeThemeSecondaryColors(value.themeSecondaryColorsArgb),
            )
            prefs[Keys.fontScale] = normalizeFontScale(value.fontScale)
            prefs[Keys.compactMode] = value.compactMode
            prefs.setOrRemove(
                Keys.backgroundImageUri,
                restorableReadUriOrCurrent(
                    value.backgroundImageUri,
                    prefs[Keys.backgroundImageUri],
                ),
            )
            prefs[Keys.backgroundImageOpacity] = normalizeAppBackgroundOpacity(
                value.backgroundImageOpacity,
            )
            prefs[Keys.backgroundImageBlurDp] = normalizeAppBackgroundBlur(
                value.backgroundImageBlurDp,
            )
            prefs[Keys.tutorialModeEnabled] = value.tutorialModeEnabled
            prefs[Keys.useChineseLauncherName] = value.useChineseLauncherName
            prefs[Keys.launcherIcon] = value.launcherIcon.name
            prefs[Keys.cloudSyncConfigs] = encodeCloudSyncConfigs(restoredCloudSyncConfigs)
            prefs[Keys.cloudSyncEnabled] = value.cloudSyncEnabled &&
                restoredCloudSyncConfigs.any {
                    it.enabled && it.selectedContents.isNotEmpty()
                }
            prefs.setOrRemove(
                Keys.diaryTreeUri,
                restorableTreeUriOrCurrent(
                    value.diaryTreeUri,
                    prefs[Keys.diaryTreeUri],
                    prefs[Keys.defaultDiaryFoldersGrantUri],
                ),
            )
            prefs.setOrRemove(
                Keys.mediaTreeUri,
                restorableTreeUriOrCurrent(
                    value.mediaTreeUri,
                    prefs[Keys.mediaTreeUri],
                    prefs[Keys.defaultDiaryFoldersGrantUri],
                ),
            )
            prefs.setOrRemove(
                Keys.notesTreeUri,
                restorableTreeUriOrCurrent(value.notesTreeUri, prefs[Keys.notesTreeUri]),
            )
            prefs[Keys.fileNamePattern] = value.fileNamePattern
            prefs[Keys.markdownTemplate] = value.markdownTemplate
            prefs[Keys.imageNamePattern] = value.imageNamePattern
            prefs[Keys.imageMaxWidthDp] = value.imageMaxWidthDp.coerceIn(120, 2400)
            prefs[Keys.imageMaxHeightDp] = value.imageMaxHeightDp.coerceIn(120, 2400)
            prefs[Keys.markdownHeadingSizesSp] = encodeMarkdownHeadingSizes(
                value.markdownHeadingSizesSp,
            )
            prefs[Keys.mealImageCompressionEnabled] = value.mealImageCompressionEnabled
            prefs[Keys.mealImageCompressionQuality] = value.mealImageCompressionQuality.coerceIn(30, 95)
            prefs[Keys.saveOriginalToGallery] = value.saveOriginalToGallery
            prefs[Keys.photoLocationEnabled] = value.photoLocationEnabled
            prefs[Keys.browserHomeUrl] = normalizeUrl(value.browserHomeUrl)
            prefs.setOrRemove(Keys.lastBrowserUrl, value.lastBrowserUrl)
            prefs[Keys.browserTheme] = value.browserTheme.name
            prefs[Keys.browserDesktopMode] = value.browserDesktopMode
            prefs[Keys.thoughtSplitRatio] = value.thoughtSplitRatio.coerceIn(0.25f, 0.8f)
            prefs[Keys.thoughtRowHeightDp] = value.thoughtRowHeightDp.coerceIn(48, 120)
            prefs[Keys.thoughtReopenMode] = value.thoughtReopenMode.name
            prefs[Keys.thoughtDisplayMode] = value.thoughtDisplayMode.name
            prefs[Keys.thoughtHighlightColorArgb] =
                value.thoughtHighlightColorArgb or 0xFF000000.toInt()
            prefs[Keys.thoughtEditorMaxHeightDp] = value.thoughtEditorMaxHeightDp
                .coerceIn(MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP, MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP)
            prefs[Keys.vaultRowHeightDp] = value.vaultRowHeightDp
                .coerceIn(MIN_VAULT_ROW_HEIGHT_DP, MAX_VAULT_ROW_HEIGHT_DP)
            prefs.setOrRemove(
                Keys.poetryFontUri,
                restorableReadUriOrCurrent(value.poetryFontUri, prefs[Keys.poetryFontUri]),
            )
            prefs[Keys.poetryFontSizeSp] = normalizePoetryFontSize(value.poetryFontSizeSp)
            prefs[Keys.poetryLineSpacing] = normalizePoetryLineSpacing(value.poetryLineSpacing)
            prefs[Keys.poetryTextAlignment] = value.poetryTextAlignment.name
            prefs[Keys.poetryShowSource] = value.poetryShowSource
            prefs[Keys.poetryShowQuoteMark] = value.poetryShowQuoteMark
            prefs[Keys.poetrySevenCharacterWrapEnabled] =
                value.poetrySevenCharacterWrapEnabled
            prefs[Keys.mealCalendarImageMaxHeightDp] = value.mealCalendarImageMaxHeightDp.coerceIn(80, 320)
            prefs[Keys.mealCalendarShowCaptions] = value.mealCalendarShowCaptions
            prefs[Keys.mealCalendarWrapEnabled] = value.mealCalendarWrapEnabled
            prefs[Keys.mealCalendarPhotosPerRow] = value.mealCalendarPhotosPerRow.name
            prefs[Keys.mealPhotoFilterEnabled] = normalizedMealPhotoFilter.enabled
            prefs[Keys.mealPhotoFilterBrightness] = normalizedMealPhotoFilter.brightness
            prefs[Keys.mealPhotoFilterContrast] = normalizedMealPhotoFilter.contrast
            prefs[Keys.mealPhotoFilterSaturation] = normalizedMealPhotoFilter.saturation
            prefs[Keys.mealPhotoFilterWarmth] = normalizedMealPhotoFilter.warmth
            prefs[Keys.mealPhotoFilterTint] = normalizedMealPhotoFilter.tint
            prefs[Keys.mealButtonsUseIcons] = value.mealButtonsUseIcons
            prefs[Keys.mealButtonIcons] = encodeStringList(normalizeMealButtonIcons(value.mealButtonIcons))
            prefs[Keys.dailyEventTemplates] = encodeDailyEventTemplates(
                normalizeDailyEventTemplates(value.dailyEventTemplates),
            )
            prefs[Keys.rssSubscriptions] = encodeRssSubscriptions(
                normalizeRssSubscriptions(value.rssSubscriptions),
            )
            prefs[Keys.rssMaxItemsPerFeed] = value.rssMaxItemsPerFeed.coerceIn(10, 200)
            prefs[Keys.rssShowSummaries] = value.rssShowSummaries
            prefs[Keys.aiEndpointUrl] = value.aiEndpointUrl.trim().take(MAX_URL_CHARS)
            prefs[Keys.aiModel] = value.aiModel.trim().take(MAX_AI_MODEL_CHARS)
            prefs[Keys.aiSystemPrompt] = value.aiSystemPrompt.take(MAX_AI_SYSTEM_PROMPT_CHARS)
            prefs[Keys.aiTemperature] = value.aiTemperature.takeIf(Float::isFinite)
                ?.coerceIn(0f, 2f) ?: 0.7f
            prefs[Keys.aiAllowInsecureHttp] = value.aiAllowInsecureHttp
            prefs[Keys.aiConfigs] = encodeAiConfigs(value.aiConfigs)
            prefs.setOrRemove(Keys.aiChatConfigId, value.aiChatConfigId)
            prefs[Keys.agentEnabledSources] = value.agentEnabledSources
                .mapTo(linkedSetOf(), AgentDataSource::wireValue)
            prefs[Keys.agentPermissionMode] = value.agentPermissionMode.name
            prefs[Keys.calorieEstimationEnabled] = value.calorieEstimationEnabled
            prefs.setOrRemove(Keys.calorieTextConfigId, value.calorieTextConfigId)
            prefs.setOrRemove(Keys.calorieImageConfigId, value.calorieImageConfigId)
            prefs[Keys.calorieVisionPrompt] = normalizeCalorieVisionPrompt(value.calorieVisionPrompt)
                .take(MAX_AI_SYSTEM_PROMPT_CHARS)
            prefs[Keys.calorieTextPrompt] = normalizeCalorieTextPrompt(value.calorieTextPrompt)
                .take(MAX_AI_SYSTEM_PROMPT_CHARS)
            prefs[Keys.usageTrackingEnabled] = value.usageTrackingEnabled
            prefs[Keys.stepTrackingEnabled] = value.stepTrackingEnabled
            prefs[Keys.navItems] = encodeNav(normalizedNav)
            prefs[Keys.morePageOrder] = encodeMorePageOrder(normalizedMorePageOrder)
            prefs[Keys.defaultPage] = normalizedDefaultPage.name
            prefs[Keys.bottomNavShowLabels] = value.bottomNavShowLabels
            prefs[Keys.musicVisualizerEnabled] = value.musicVisualizerEnabled
            prefs[Keys.musicVisualizerStyle] = value.musicVisualizerStyle.name
            prefs[Keys.musicVisualizerFrequencyMode] = value.musicVisualizerFrequencyMode.name
            val musicVisualizerFrequencyBounds = normalizeMusicVisualizerFrequencyBounds(
                value.musicVisualizerMinFrequencyHz,
                value.musicVisualizerMaxFrequencyHz,
            )
            prefs[Keys.musicVisualizerMinFrequencyHz] = musicVisualizerFrequencyBounds.first
            prefs[Keys.musicVisualizerMaxFrequencyHz] = musicVisualizerFrequencyBounds.second
            prefs[Keys.game2048AnimationSpeed] = value.game2048AnimationSpeed.name
            prefs[Keys.morePageShowDescriptions] = value.morePageShowDescriptions
            prefs[Keys.homeWidgetBordersEnabled] = value.homeWidgetBordersEnabled
            prefs[Keys.homeWidgets] = encodeStringList(value.homeWidgets.distinct())
            prefs[Keys.homeGameShortcuts] = encodeStringList(
                normalizeHomeGameShortcutIds(value.homeGameShortcuts),
            )
            prefs[Keys.mealPhotosWidgetMigrated] = true
            prefs[Keys.dailyRecordsWidgetMigrated] = true
            prefs[Keys.homeModulesV26Migrated] = true
            prefs[Keys.homeCloudSyncWidgetMigrated] = true
            prefs[Keys.homeWidgetTitles] = encodeStringList(value.homeWidgetTitles.distinct())
            prefs[Keys.desktopWidgetConfigs] = encodeDesktopWidgetConfigs(
                restoredDesktopWidgetConfigs,
            )
        }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun restorableTreeUriOrCurrent(
        imported: String?,
        current: String?,
        defaultDiaryFoldersGrantUri: String? = null,
    ): String? {
        if (imported == null) return null
        return imported.takeIf { raw ->
            hasPersistedTreeAccess(raw, defaultDiaryFoldersGrantUri)
        } ?: current
    }

    private fun restorableReadUriOrCurrent(imported: String?, current: String?): String? {
        if (imported == null) return null
        return imported.takeIf(::hasPersistedReadAccess) ?: current
    }

    private fun hasPersistedTreeAccess(
        raw: String,
        inheritedGrantRaw: String? = null,
    ): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val isTreeUri = runCatching {
            uri.scheme == "content" && DocumentsContract.isTreeUri(uri)
        }.getOrDefault(false)
        if (!isTreeUri) return false
        return runCatching {
            val permissions = context.contentResolver.persistedUriPermissions
            if (permissions.any { permission ->
                permission.uri == uri && permission.isReadPermission && permission.isWritePermission
            }) return@runCatching true

            val inheritedGrant = inheritedGrantRaw
                ?.let { Uri.parse(it) }
                ?.takeIf { grant -> grant.authority == uri.authority }
                ?: return@runCatching false
            val grantIsPersisted = permissions.any { permission ->
                permission.uri == inheritedGrant &&
                    permission.isReadPermission && permission.isWritePermission
            }
            if (!grantIsPersisted) return@runCatching false

            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun hasPersistedReadAccess(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        if (uri.scheme != "content") return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission
            }
        }.getOrDefault(false)
    }

    private fun decodeNav(raw: String?): List<NavItemConfig> = runCatching {
        val array = JSONArray(raw ?: return@runCatching AppSettings().navItems)
        val decoded = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = NavItemId.valueOf(item.getString("id"))
                val visible = item.optBoolean("visible", id.defaultVisible) ||
                    id == NavItemId.SETTINGS
                add(
                    NavItemConfig(
                        id = id,
                        label = migrateLegacyDefaultLabel(
                            id,
                            item.optString("label", id.defaultLabel).ifBlank { id.defaultLabel },
                        ),
                        iconKey = item.optString("icon", id.defaultIcon),
                        visible = visible,
                        showInMore = when {
                            id == NavItemId.HOME ||
                                id == NavItemId.MORE ||
                                id == NavItemId.SETTINGS -> false
                            item.has("showInMore") -> item.optBoolean("showInMore")
                            else -> id.defaultShowInMore && !visible
                        },
                        moreDescription = migrateLegacyDefaultDescription(
                            id,
                            normalizeMoreDescription(
                                item.optString("moreDescription", id.defaultDescription),
                            ),
                        ),
                    ),
                )
            }
        }
        val statisticsWasMissing = decoded.none { it.id == NavItemId.STATISTICS }
        normalizeNavItems(decoded).map { item ->
            if (statisticsWasMissing &&
                (item.id == NavItemId.USAGE || item.id == NavItemId.STEPS)
            ) {
                item.copy(showInMore = false)
            } else {
                item
            }
        }
    }.getOrElse { AppSettings().navItems }

    private fun decodeMorePageOrder(
        raw: String?,
        navItems: List<NavItemConfig>,
    ): List<NavItemId> = runCatching {
        val array = JSONArray(raw ?: return@runCatching normalizeMorePageOrder(emptyList(), navItems))
        require(array.length() <= NavItemId.entries.size)
        val seen = HashSet<NavItemId>(array.length())
        val decoded = buildList {
            for (index in 0 until array.length()) {
                val id = NavItemId.valueOf(array.getString(index))
                require(
                    id != NavItemId.HOME &&
                        id != NavItemId.MORE &&
                        id != NavItemId.SETTINGS,
                )
                require(seen.add(id))
                add(id)
            }
        }
        normalizeMorePageOrder(decoded, navItems)
    }.getOrElse { normalizeMorePageOrder(emptyList(), navItems) }

    private fun encodeMorePageOrder(value: List<NavItemId>): String =
        JSONArray().apply { value.forEach { put(it.name) } }.toString()

    private fun migrateMorePageOrderIfNeeded(prefs: MutablePreferences) {
        val legacyNav = decodeNav(prefs[Keys.navItems])
        prefs[Keys.morePageOrder] = encodeMorePageOrder(
            decodeMorePageOrder(prefs[Keys.morePageOrder], legacyNav),
        )
    }

    private fun encodeNav(items: List<NavItemConfig>): String = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject()
                    .put("id", item.id.name)
                    .put("label", item.label)
                    .put("icon", item.iconKey)
                    .put("visible", item.visible || item.id == NavItemId.SETTINGS)
                    .put(
                        "showInMore",
                        item.showInMore && item.id != NavItemId.HOME &&
                            item.id != NavItemId.MORE && item.id != NavItemId.SETTINGS,
                    )
                    .put("moreDescription", normalizeMoreDescription(item.moreDescription)),
            )
        }
    }.toString()

    private fun decodeCloudSyncConfigs(raw: String?): List<CloudSyncConfig> = runCatching {
        val array = JSONArray(raw ?: return@runCatching emptyList())
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val contents = item.optJSONArray("selectedContents")?.let { values ->
                    buildSet {
                        for (contentIndex in 0 until values.length()) {
                            runCatching {
                                CloudSyncContent.valueOf(values.getString(contentIndex))
                            }.getOrNull()?.let(::add)
                        }
                    }
                }.orEmpty()
                add(
                    CloudSyncConfig(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        enabled = item.optBoolean("enabled", true),
                        serviceType = runCatching {
                            CloudSyncServiceType.valueOf(item.optString("serviceType"))
                        }.getOrDefault(CloudSyncServiceType.WEBDAV),
                        endpointUrl = item.optString("endpointUrl"),
                        remotePath = item.optString("remotePath", "DeskCubby"),
                        userAgent = item.optString("userAgent", DEFAULT_CLOUD_SYNC_USER_AGENT),
                        webDavUsername = item.optString("webDavUsername"),
                        s3Bucket = item.optString("s3Bucket"),
                        s3Region = item.optString("s3Region", "us-east-1"),
                        s3AccessKey = item.optString("s3AccessKey"),
                        s3SecretKey = item.optString("s3SecretKey"),
                        s3SessionToken = item.optString("s3SessionToken"),
                        s3PathStyle = item.optBoolean("s3PathStyle", true),
                        allowInsecureHttp = item.optBoolean("allowInsecureHttp", false),
                        selectedContents = contents,
                        direction = runCatching {
                            CloudSyncDirection.valueOf(item.optString("direction"))
                        }.getOrDefault(CloudSyncDirection.TWO_WAY),
                    ),
                )
            }
        }.let(::normalizeCloudSyncConfigs)
    }.getOrDefault(emptyList())

    private fun encodeCloudSyncConfigs(items: List<CloudSyncConfig>): String = JSONArray().apply {
        normalizeCloudSyncConfigs(items).forEach { item ->
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
                    .put("s3AccessKey", item.s3AccessKey)
                    .put("s3SecretKey", item.s3SecretKey)
                    .put("s3SessionToken", item.s3SessionToken)
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
    }.toString()

    private fun decodeDesktopWidgetConfigs(raw: String?): List<DesktopWidgetConfig> {
        if (raw == null) return DEFAULT_DESKTOP_WIDGET_CONFIGS
        return runCatching {
            val array = JSONArray(raw)
            require(array.length() <= MAX_DESKTOP_WIDGET_CONFIGS)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        DesktopWidgetConfig(
                            id = item.optString("id"),
                            name = item.optString("name"),
                            widthCells = item.optInt("widthCells", 2),
                            heightCells = item.optInt("heightCells", 2),
                            backgroundColorArgb = item.optInt(
                                "backgroundColorArgb",
                                0xFF263238.toInt(),
                            ),
                            textColorArgb = item.optInt(
                                "textColorArgb",
                                0xFFFFFFFF.toInt(),
                            ),
                            backgroundImageUri = if (item.isNull("backgroundImageUri")) {
                                null
                            } else {
                                item.optString("backgroundImageUri").takeIf(String::isNotBlank)
                            },
                            showName = item.optBoolean("showName", true),
                            backgroundOpacityPercent = item.optInt(
                                "backgroundOpacityPercent",
                                100,
                            ),
                            showIcon = item.optBoolean("showIcon", true),
                            textAlignment = item.optString("textAlignment")
                                .enumValueOr(DesktopWidgetTextAlignment.START),
                            textScalePercent = item.optInt("textScalePercent", 100),
                            contentType = item.optString("contentType")
                                .enumValueOr(DesktopWidgetContentType.HOME_MODULE),
                            homeModuleId = item.optString("homeModuleId", "today"),
                            appPackageName = if (item.isNull("appPackageName")) {
                                null
                            } else {
                                item.optString("appPackageName").takeIf(String::isNotBlank)
                            },
                            appLabel = if (item.isNull("appLabel")) {
                                null
                            } else {
                                item.optString("appLabel").takeIf(String::isNotBlank)
                            },
                        ),
                    )
                }
            }
        }.map(::normalizeDesktopWidgetConfigs)
            .getOrElse { DEFAULT_DESKTOP_WIDGET_CONFIGS }
            .map { item ->
                item.copy(
                    backgroundImageUri = item.backgroundImageUri
                        ?.takeIf(::hasPersistedReadAccess),
                )
            }
    }

    private fun encodeDesktopWidgetConfigs(items: List<DesktopWidgetConfig>): String =
        JSONArray().apply {
            normalizeDesktopWidgetConfigs(items).forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id)
                        .put("name", item.name)
                        .put("widthCells", item.widthCells)
                        .put("heightCells", item.heightCells)
                        .put("backgroundColorArgb", item.backgroundColorArgb)
                        .put("textColorArgb", item.textColorArgb)
                        .put("backgroundImageUri", item.backgroundImageUri ?: JSONObject.NULL)
                        .put("showName", item.showName)
                        .put("backgroundOpacityPercent", item.backgroundOpacityPercent)
                        .put("showIcon", item.showIcon)
                        .put("textAlignment", item.textAlignment.name)
                        .put("textScalePercent", item.textScalePercent)
                        .put("contentType", item.contentType.name)
                        .put("homeModuleId", item.homeModuleId)
                        .put("appPackageName", item.appPackageName ?: JSONObject.NULL)
                        .put("appLabel", item.appLabel ?: JSONObject.NULL),
                )
            }
        }.toString()

    private fun decodeWidgets(raw: String?, fallback: List<String>): List<String> {
        if (raw == null) return fallback
        if (raw.trimStart().startsWith("[")) return decodeStringList(raw, fallback)
        return raw.split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .takeIf(List<String>::isNotEmpty)
            ?: fallback
    }

    private fun decodeStringList(raw: String?, fallback: List<String>): List<String> {
        if (raw == null) return fallback
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }.distinct()
        }.getOrElse { fallback }
    }

    private fun decodeMarkdownHeadingSizes(
        raw: String?,
        fallback: List<Float>,
    ): List<Float> {
        if (raw == null) return normalizeMarkdownHeadingSizes(fallback)
        return runCatching {
            val array = JSONArray(raw)
            require(array.length() == 6)
            normalizeMarkdownHeadingSizes(
                List(array.length()) { index -> array.getDouble(index).toFloat() },
            )
        }.getOrElse { normalizeMarkdownHeadingSizes(fallback) }
    }

    private fun decodeMealButtonIcons(raw: String?, fallback: List<String>): List<String> {
        if (raw == null) return fallback
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optString(index) }
        }.map { normalizeMealButtonIcons(it, fallback) }.getOrElse { fallback }
    }

    private fun decodeThemeSecondaryColors(raw: String?, fallback: List<Int>): List<Int> {
        if (raw == null) return normalizeThemeSecondaryColors(fallback)
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (index in 0 until array.length()) add(array.getInt(index))
            }
        }.map { normalizeThemeSecondaryColors(it, fallback) }
            .getOrElse { normalizeThemeSecondaryColors(fallback) }
    }

    private fun decodeCustomTheme(
        raw: String?,
        fallback: CustomThemeSettings,
    ): CustomThemeSettings {
        if (raw == null) return fallback.normalized()
        return runCatching {
            val json = JSONObject(raw)
            CustomThemeSettings(
                baseStyle = CustomThemeBaseStyle.valueOf(json.getString("baseStyle")),
                lightPalette = decodeCustomThemePalette(json.getJSONObject("lightPalette")),
                darkPalette = decodeCustomThemePalette(json.getJSONObject("darkPalette")),
                cornerRadiusDp = json.getDouble("cornerRadiusDp").toFloat(),
                borderWidthDp = json.getDouble("borderWidthDp").toFloat(),
                elevationDp = json.getDouble("elevationDp").toFloat(),
                panelOpacity = json.getDouble("panelOpacity").toFloat(),
                spacingScale = json.getDouble("spacingScale").toFloat(),
                animationScale = json.getDouble("animationScale").toFloat(),
            ).normalized()
        }.getOrElse { fallback.normalized() }
    }

    private fun decodeCustomThemePalette(json: JSONObject): CustomThemePalette =
        CustomThemePalette(
            backgroundArgb = json.getInt("backgroundArgb"),
            onBackgroundArgb = json.getInt("onBackgroundArgb"),
            surfaceArgb = json.getInt("surfaceArgb"),
            onSurfaceArgb = json.getInt("onSurfaceArgb"),
            surfaceContainerArgb = json.getInt("surfaceContainerArgb"),
            surfaceVariantArgb = json.getInt("surfaceVariantArgb"),
            onSurfaceVariantArgb = json.getInt("onSurfaceVariantArgb"),
            outlineArgb = json.getInt("outlineArgb"),
        )

    private fun encodeCustomTheme(value: CustomThemeSettings): String {
        val normalized = value.normalized()
        return JSONObject()
            .put("baseStyle", normalized.baseStyle.name)
            .put("lightPalette", encodeCustomThemePalette(normalized.lightPalette))
            .put("darkPalette", encodeCustomThemePalette(normalized.darkPalette))
            .put("cornerRadiusDp", normalized.cornerRadiusDp.toDouble())
            .put("borderWidthDp", normalized.borderWidthDp.toDouble())
            .put("elevationDp", normalized.elevationDp.toDouble())
            .put("panelOpacity", normalized.panelOpacity.toDouble())
            .put("spacingScale", normalized.spacingScale.toDouble())
            .put("animationScale", normalized.animationScale.toDouble())
            .toString()
    }

    private fun encodeCustomThemePalette(value: CustomThemePalette): JSONObject = JSONObject()
        .put("backgroundArgb", value.backgroundArgb)
        .put("onBackgroundArgb", value.onBackgroundArgb)
        .put("surfaceArgb", value.surfaceArgb)
        .put("onSurfaceArgb", value.onSurfaceArgb)
        .put("surfaceContainerArgb", value.surfaceContainerArgb)
        .put("surfaceVariantArgb", value.surfaceVariantArgb)
        .put("onSurfaceVariantArgb", value.onSurfaceVariantArgb)
        .put("outlineArgb", value.outlineArgb)

    private fun decodeDailyEventTemplates(raw: String?): List<DailyEventTemplate> = runCatching {
        val array = JSONArray(raw ?: return@runCatching emptyList())
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    DailyEventTemplate(
                        id = item.optString("id"),
                        text = item.optString("text"),
                        firstUnit = item.optString("firstUnit"),
                        secondUnit = item.optString("secondUnit"),
                    ),
                )
            }
        }.let(::normalizeDailyEventTemplates)
    }.getOrDefault(emptyList())

    private fun encodeDailyEventTemplates(items: List<DailyEventTemplate>): String = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("firstUnit", item.firstUnit)
                    .put("secondUnit", item.secondUnit),
            )
        }
    }.toString()

    private fun decodeAiConfigs(raw: String?): List<AiModelConfig> = runCatching {
        val array = JSONArray(raw ?: return@runCatching emptyList())
        buildList(array.length()) {
            for (index in 0 until array.length()) array.getJSONObject(index).let { item ->
                add(AiModelConfig(
                    id = item.getString("id"), name = item.optString("name"),
                    type = runCatching { AiModelType.valueOf(item.getString("type")) }.getOrDefault(AiModelType.TEXT),
                    endpointUrl = item.getString("endpointUrl"), model = item.getString("model"),
                    enabled = item.optBoolean("enabled", true),
                    allowInsecureHttp = item.optBoolean("allowInsecureHttp", false),
                    temperature = item.optDouble("temperature", 0.7).toFloat().coerceIn(0f, 2f),
                    systemPrompt = item.optString("systemPrompt").take(MAX_AI_SYSTEM_PROMPT_CHARS),
                    apiKey = item.optString("apiKey").take(MAX_AI_API_KEY_CHARS),
                    supportsToolCalling = item.optBoolean("supportsToolCalling", false),
                ))
            }
        }.filter { it.id.isNotBlank() && it.endpointUrl.isNotBlank() && it.model.isNotBlank() }.take(20)
    }.getOrDefault(emptyList())

    private fun encodeAiConfigs(items: List<AiModelConfig>): String = JSONArray().apply {
        items.distinctBy { it.id }.take(20).forEach { item ->
            val json = JSONObject()
                .put("id", item.id).put("name", item.name).put("type", item.type.name)
                .put("endpointUrl", item.endpointUrl).put("model", item.model).put("enabled", item.enabled)
                .put("allowInsecureHttp", item.allowInsecureHttp).put("temperature", item.temperature.toDouble())
                .put("apiKey", item.apiKey.take(MAX_AI_API_KEY_CHARS))
                .put("supportsToolCalling", item.supportsToolCalling)
            if (item.systemPrompt.isNotEmpty()) json.put("systemPrompt", item.systemPrompt)
            put(json)
        }
    }.toString()

    private fun decodeRssSubscriptions(raw: String?): List<RssSubscription> = runCatching {
        val array = JSONArray(raw ?: return@runCatching emptyList())
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    RssSubscription(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        url = item.optString("url"),
                        enabled = item.optBoolean("enabled", true),
                    ),
                )
            }
        }.let(::normalizeRssSubscriptions)
    }.getOrDefault(emptyList())

    private fun encodeRssSubscriptions(items: List<RssSubscription>): String = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("enabled", item.enabled),
            )
        }
    }.toString()

    private fun encodeThemeSecondaryColors(items: List<Int>): String = JSONArray().apply {
        items.forEach(::put)
    }.toString()

    private fun encodeStringList(items: List<String>): String = JSONArray().apply {
        items.forEach { put(it) }
    }.toString()

    private fun encodeMarkdownHeadingSizes(items: List<Float>): String = JSONArray().apply {
        normalizeMarkdownHeadingSizes(items).forEach { put(it.toDouble()) }
    }.toString()

    private fun decodeHomeGreetings(
        raw: String?,
        fallback: List<HomeGreetingTemplate>,
    ): List<HomeGreetingTemplate> {
        if (raw == null) return normalizeHomeGreetings(fallback)
        return runCatching {
            val array = JSONArray(raw)
            require(array.length() <= MAX_HOME_GREETINGS)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        HomeGreetingTemplate(
                            chinese = item.opt("chinese") as? String
                                ?: throw IllegalArgumentException("Invalid Chinese greeting"),
                            english = item.opt("english") as? String
                                ?: throw IllegalArgumentException("Invalid English greeting"),
                        ),
                    )
                }
            }.let(::normalizeHomeGreetings)
        }.getOrElse { normalizeHomeGreetings(fallback) }
    }

    private fun encodeHomeGreetings(items: List<HomeGreetingTemplate>): String =
        JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("chinese", item.chinese)
                        .put("english", item.english),
                )
            }
        }.toString()

    private fun migrateLegacyDefaultLabel(id: NavItemId, label: String): String = when {
        id == NavItemId.BLOG && label == "博客" -> id.defaultLabel
        id == NavItemId.THOUGHT && label == "闪思" -> id.defaultLabel
        id == NavItemId.STEPS && label == "步数记录" -> id.defaultLabel
        else -> label
    }

    private fun migrateLegacyDefaultDescription(id: NavItemId, description: String): String =
        when {
            id == NavItemId.STEPS && description == "自动读取并可视化每日步数" ->
                id.defaultDescription
            else -> description
        }

    private inline fun <reified T : Enum<T>> String?.enumValueOr(fallback: T): T =
        this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback

    companion object {
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return "about:blank"
            return if (trimmed.contains("://") || trimmed.startsWith("about:")) trimmed else "https://$trimmed"
        }
    }
}

internal const val LEGACY_DEFAULT_CALORIE_VISION_PROMPT: String =
    "识别图片中的所有食物和饮料。只返回 JSON，不要 Markdown：" +
        "{\"foods\":[{\"name\":\"食物名称\",\"amount\":\"估计份量\",\"unit\":\"单位\"," +
        "\"confidence\":0.0}],\"notes\":\"必要说明\"}。" +
        "无法确定时给出合理估计并降低 confidence。"

internal const val LEGACY_DEFAULT_CALORIE_TEXT_PROMPT: String =
    "根据随后提供的食物识别 JSON，估算整张图片中食物的总能量。只返回 JSON，不要 Markdown：" +
        "{\"energyKj\":整数}。energyKj 使用千焦(kJ)，综合份量并避免重复计算。"

internal const val V091_DEFAULT_CALORIE_TEXT_PROMPT: String =
    "你是谨慎的营养能量估算助手。根据随后 JSON 中的 recognizedFoods、visionNotes 和可选 " +
        "userNote，结合可食用分量、常见烹饪方式、可见油脂/酱汁与饮料估算能量；用户备注" +
        "可用于判断多人分享、同一餐多角度拍摄、剩余比例或实际分量。避免重复计算，并在证据" +
        "不足时采用中性的合理估值。只返回 JSON，不要 Markdown：{\"energyKj\":整数," +
        "\"foods\":[{\"name\":\"食物名称\",\"amount\":\"分量\",\"unit\":\"单位\"," +
        "\"energyKj\":整数}]}。所有能量使用千焦(kJ)，保留每种食物，各项之和应与总能量" +
        "在合理舍入范围内一致。"

internal fun normalizeCalorieVisionPrompt(value: String): String =
    if (value == LEGACY_DEFAULT_CALORIE_VISION_PROMPT) DEFAULT_CALORIE_VISION_PROMPT else value

internal fun normalizeCalorieTextPrompt(value: String): String =
    if (value == LEGACY_DEFAULT_CALORIE_TEXT_PROMPT ||
        value == V091_DEFAULT_CALORIE_TEXT_PROMPT
    ) {
        DEFAULT_CALORIE_TEXT_PROMPT
    } else {
        value
    }

internal fun normalizeUserName(value: String): String = value.trim().takeCodePoints(MAX_USER_NAME_CHARS)

internal fun normalizeHomeGreetings(
    items: List<HomeGreetingTemplate>,
): List<HomeGreetingTemplate> = items.asSequence()
    .take(MAX_HOME_GREETINGS)
    .map { item ->
        val chinese = item.chinese.trim().replaceLineBreaks()
            .takeCodePoints(MAX_HOME_GREETING_CODE_POINTS)
        val english = item.english.trim().replaceLineBreaks()
            .takeCodePoints(MAX_HOME_GREETING_CODE_POINTS)
        HomeGreetingTemplate(
            chinese = chinese,
            english = english,
        )
    }
    .filter { it.chinese.isNotBlank() || it.english.isNotBlank() }
    .toList()

internal fun resolveAiConfigId(
    configs: List<AiModelConfig>,
    requestedId: String?,
    type: AiModelType,
    fallbackToAny: Boolean = false,
): String? = configs.firstOrNull { it.id == requestedId && it.type == type }?.id
    ?: configs.firstOrNull { it.enabled && it.type == type }?.id
    ?: configs.firstOrNull { fallbackToAny && it.type == type }?.id

internal fun normalizeMealButtonIcons(
    items: List<String>,
    fallback: List<String> = DEFAULT_MEAL_BUTTON_ICONS,
): List<String> = fallback.mapIndexed { index, defaultIcon ->
    items.getOrNull(index)
        ?.trim()
        ?.takeCodePoints(MAX_MEAL_BUTTON_ICON_CHARS)
        ?.takeIf(String::isNotBlank)
        ?: defaultIcon
}

internal fun normalizeCloudSyncConfigs(items: List<CloudSyncConfig>): List<CloudSyncConfig> =
    items.asSequence()
        .map { item ->
            val normalizedEndpoint = if (
                item.serviceType == CloudSyncServiceType.S3_COMPATIBLE
            ) {
                normalizeS3EndpointScheme(item.endpointUrl, item.allowInsecureHttp)
            } else {
                item.endpointUrl.trim()
            }
            val normalizedPath = item.remotePath
                .trim()
                .trim('/')
                .split('/')
                .filter(String::isNotEmpty)
                .takeIf { segments -> segments.none { it == "." || it == ".." } }
                ?.joinToString("/")
                .orEmpty()
                .take(MAX_CLOUD_SYNC_PATH_CHARS)
            item.copy(
                id = item.id.trim().take(MAX_CLOUD_SYNC_ID_CHARS),
                name = item.name.trim().replaceLineBreaks().take(MAX_CLOUD_SYNC_NAME_CHARS),
                endpointUrl = normalizedEndpoint.take(MAX_URL_CHARS),
                remotePath = normalizedPath,
                userAgent = item.userAgent
                    .trim()
                    .replaceLineBreaks()
                    .take(MAX_CLOUD_SYNC_USER_AGENT_CHARS)
                    .ifBlank { DEFAULT_CLOUD_SYNC_USER_AGENT },
                webDavUsername = item.webDavUsername.trim().take(MAX_CLOUD_SYNC_USERNAME_CHARS),
                webDavPassword = "",
                s3Bucket = item.s3Bucket.trim().take(MAX_CLOUD_SYNC_BUCKET_CHARS),
                s3Region = item.s3Region.trim().take(MAX_CLOUD_SYNC_REGION_CHARS),
                s3AccessKey = item.s3AccessKey.trim().take(MAX_CLOUD_SYNC_CREDENTIAL_CHARS),
                s3SecretKey = item.s3SecretKey.take(MAX_CLOUD_SYNC_CREDENTIAL_CHARS),
                s3SessionToken = item.s3SessionToken.take(MAX_CLOUD_SYNC_CREDENTIAL_CHARS),
                selectedContents = item.selectedContents.intersect(CloudSyncContent.entries.toSet()),
            )
        }
        .filter { item ->
            item.id.isNotEmpty() &&
                item.name.isNotEmpty() &&
                item.selectedContents.isNotEmpty() &&
                item.userAgent.isNotBlank() &&
                item.userAgent.length <= MAX_CLOUD_SYNC_USER_AGENT_CHARS &&
                item.userAgent.none(Char::isISOControl) &&
                item.remotePath.length <= MAX_CLOUD_SYNC_PATH_CHARS &&
                isValidCloudSyncEndpoint(item.endpointUrl, item.allowInsecureHttp) &&
                (item.serviceType != CloudSyncServiceType.S3_COMPATIBLE ||
                    isValidS3Metadata(item.s3Bucket, item.s3Region))
        }
        .distinctBy(CloudSyncConfig::id)
        .take(MAX_CLOUD_SYNC_CONFIGS)
        .toList()

internal fun normalizeS3EndpointScheme(raw: String, allowInsecureHttp: Boolean): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || "://" in trimmed) return trimmed
    return "${if (allowInsecureHttp) "http" else "https"}://$trimmed"
}

internal fun hasSameS3CredentialScope(
    local: CloudSyncConfig,
    restored: CloudSyncConfig,
): Boolean =
    local.endpointUrl.trim().trimEnd('/') == restored.endpointUrl.trim().trimEnd('/') &&
        local.s3Bucket.trim() == restored.s3Bucket.trim() &&
        local.s3Region.trim() == restored.s3Region.trim()

private fun isValidCloudSyncEndpoint(raw: String, allowInsecureHttp: Boolean): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    return uri.isAbsolute &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null &&
        (scheme == "https" || scheme == "http" && allowInsecureHttp)
}

private fun isValidS3Metadata(bucket: String, region: String): Boolean =
    bucket.isNotEmpty() &&
        bucket.none { it == '/' || it == '\\' || it.isISOControl() } &&
        Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(region)

internal fun normalizeThemeSecondaryColors(
    items: List<Int>,
    fallback: List<Int> = DEFAULT_THEME_SECONDARY_COLORS_ARGB,
): List<Int> {
    val normalized = items
        .map(::opaqueArgb)
        .distinct()
        .take(MAX_THEME_SECONDARY_COLOR_COUNT)
    val normalizedFallback = fallback
        .map(::opaqueArgb)
        .distinct()
        .take(MAX_THEME_SECONDARY_COLOR_COUNT)
    val fallbackColors = buildList(MAX_THEME_SECONDARY_COLOR_COUNT) {
        addAll(normalizedFallback)
        DEFAULT_THEME_SECONDARY_COLORS_ARGB.forEach { color ->
            if (size < MIN_THEME_SECONDARY_COLOR_COUNT && opaqueArgb(color) !in this) {
                add(opaqueArgb(color))
            }
        }
    }
    if (normalized.isEmpty()) return fallbackColors
    if (normalized.size >= MIN_THEME_SECONDARY_COLOR_COUNT) return normalized

    return buildList(MAX_THEME_SECONDARY_COLOR_COUNT) {
        addAll(normalized)
        fallbackColors.forEach { color ->
            if (size < MIN_THEME_SECONDARY_COLOR_COUNT && color !in this) add(color)
        }
    }
}

internal fun normalizeFontScale(value: Float?, fallback: Float = 1f): Float {
    val normalizedFallback = fallback.takeIf(Float::isFinite)
        ?.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE)
        ?: 1f
    return value?.takeIf(Float::isFinite)
        ?.coerceIn(MIN_APP_FONT_SCALE, MAX_APP_FONT_SCALE)
        ?: normalizedFallback
}

internal fun normalizeAppBackgroundOpacity(value: Float?, fallback: Float = 0.45f): Float {
    val normalizedFallback = fallback.takeIf(Float::isFinite)
        ?.coerceIn(MIN_APP_BACKGROUND_OPACITY, MAX_APP_BACKGROUND_OPACITY)
        ?: 0.45f
    return value?.takeIf(Float::isFinite)
        ?.coerceIn(MIN_APP_BACKGROUND_OPACITY, MAX_APP_BACKGROUND_OPACITY)
        ?: normalizedFallback
}

internal fun normalizeAppBackgroundBlur(value: Float?, fallback: Float = 0f): Float {
    val normalizedFallback = fallback.takeIf(Float::isFinite)
        ?.coerceIn(MIN_APP_BACKGROUND_BLUR_DP, MAX_APP_BACKGROUND_BLUR_DP)
        ?: 0f
    return value?.takeIf(Float::isFinite)
        ?.coerceIn(MIN_APP_BACKGROUND_BLUR_DP, MAX_APP_BACKGROUND_BLUR_DP)
        ?: normalizedFallback
}

internal fun normalizeTutorialPageIds(values: Iterable<String>): Set<String> = values.asSequence()
    .map(String::trim)
    .filter(TUTORIAL_PAGE_ID_REGEX::matches)
    .distinct()
    .take(MAX_TUTORIAL_ACKNOWLEDGED_PAGES)
    .toSet()

internal fun normalizePoetryFontSize(value: Float?, fallback: Float = 18f): Float {
    val normalizedFallback = fallback.takeIf(Float::isFinite)
        ?.coerceIn(MIN_POETRY_FONT_SIZE_SP, MAX_POETRY_FONT_SIZE_SP)
        ?: 18f
    return value?.takeIf(Float::isFinite)
        ?.coerceIn(MIN_POETRY_FONT_SIZE_SP, MAX_POETRY_FONT_SIZE_SP)
        ?: normalizedFallback
}

internal fun normalizePoetryLineSpacing(value: Float?, fallback: Float = 1.45f): Float {
    val normalizedFallback = fallback.takeIf(Float::isFinite)
        ?.coerceIn(MIN_POETRY_LINE_SPACING, MAX_POETRY_LINE_SPACING)
        ?: 1.45f
    return value?.takeIf(Float::isFinite)
        ?.coerceIn(MIN_POETRY_LINE_SPACING, MAX_POETRY_LINE_SPACING)
        ?: normalizedFallback
}

internal fun normalizeThoughtPageKey(value: String): String {
    val normalized = value.trim()
    return when {
        normalized == "all" || normalized == "uncategorized" -> normalized
        normalized.startsWith("category:") && normalized.substringAfter(':').toLongOrNull() != null -> normalized
        else -> "all"
    }
}

internal fun normalizeDailyEventTemplates(items: List<DailyEventTemplate>): List<DailyEventTemplate> =
    items.asSequence()
        .map { item ->
            val migratedText = buildString {
                append(item.text.trim().normalizeLineBreaks())
                item.firstUnit.trim().replaceLineBreaks().takeIf(String::isNotEmpty)?.let {
                    append(" xx ").append(it)
                }
                item.secondUnit.trim().replaceLineBreaks().takeIf(String::isNotEmpty)?.let {
                    append(" xx ").append(it)
                }
            }
            item.copy(
                id = item.id.trim().take(80),
                text = migratedText.take(MAX_DAILY_EVENT_TEXT_CHARS),
                firstUnit = "",
                secondUnit = "",
            )
        }
        .filter { it.id.isNotBlank() && it.text.isNotBlank() }
        .distinctBy(DailyEventTemplate::id)
        .take(MAX_DAILY_EVENT_TEMPLATES)
        .toList()

internal fun normalizeRssSubscriptions(items: List<RssSubscription>): List<RssSubscription> =
    items.asSequence()
        .map { item ->
            item.copy(
                id = item.id.trim().take(80),
                title = item.title.trim().replace('\n', ' ').take(120),
                url = item.url.trim().take(4_096),
            )
        }
        .filter { it.id.isNotBlank() && it.url.isNotBlank() }
        .distinctBy(RssSubscription::id)
        .take(MAX_RSS_SUBSCRIPTIONS)
        .toList()

internal fun normalizeDesktopWidgetConfigs(
    items: List<DesktopWidgetConfig>,
): List<DesktopWidgetConfig> = items.asSequence()
    .map { item ->
        val packageName = item.appPackageName
            ?.trim()
            ?.take(MAX_DESKTOP_WIDGET_PACKAGE_CHARS)
            ?.takeIf(DESKTOP_WIDGET_PACKAGE_REGEX::matches)
        item.copy(
            id = item.id.trim().take(MAX_DESKTOP_WIDGET_ID_CHARS)
                .takeIf(DESKTOP_WIDGET_ID_REGEX::matches)
                .orEmpty(),
            name = item.name.trim().replaceLineBreaks()
                .takeCodePoints(MAX_DESKTOP_WIDGET_NAME_CODE_POINTS),
            widthCells = item.widthCells.coerceIn(
                MIN_DESKTOP_WIDGET_CELLS,
                MAX_DESKTOP_WIDGET_CELLS,
            ),
            heightCells = item.heightCells.coerceIn(
                MIN_DESKTOP_WIDGET_CELLS,
                MAX_DESKTOP_WIDGET_CELLS,
            ),
            backgroundColorArgb = opaqueArgb(item.backgroundColorArgb),
            textColorArgb = opaqueArgb(item.textColorArgb),
            backgroundOpacityPercent = item.backgroundOpacityPercent.coerceIn(
                MIN_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT,
                MAX_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT,
            ),
            textScalePercent = item.textScalePercent.coerceIn(
                MIN_DESKTOP_WIDGET_TEXT_SCALE_PERCENT,
                MAX_DESKTOP_WIDGET_TEXT_SCALE_PERCENT,
            ),
            backgroundImageUri = item.backgroundImageUri
                ?.trim()
                ?.take(MAX_URL_CHARS)
                ?.takeIf { it.startsWith("content://") },
            homeModuleId = normalizeDesktopWidgetHomeModuleId(item.homeModuleId),
            appPackageName = packageName,
            appLabel = item.appLabel
                ?.trim()
                ?.replaceLineBreaks()
                ?.takeCodePoints(MAX_DESKTOP_WIDGET_APP_LABEL_CODE_POINTS)
                ?.takeIf(String::isNotBlank),
        )
    }
    .filter { item ->
        item.id.isNotBlank() && item.name.isNotBlank() &&
            (item.contentType != DesktopWidgetContentType.APP_SHORTCUT ||
                item.appPackageName != null)
    }
    .distinctBy(DesktopWidgetConfig::id)
    .take(MAX_DESKTOP_WIDGET_CONFIGS)
    .toList()

private fun opaqueArgb(value: Int): Int = value or 0xFF000000.toInt()

private const val MAX_USER_NAME_CHARS = 32
private const val MAX_TUTORIAL_ACKNOWLEDGED_PAGES = 128
private val TUTORIAL_PAGE_ID_REGEX = Regex("[A-Za-z0-9._:/-]{1,120}")
internal const val MAX_HOME_GREETINGS = 100
internal const val MAX_HOME_GREETING_CODE_POINTS = 40
private const val MAX_MEAL_BUTTON_ICON_CHARS = 16
private fun String.replaceLineBreaks(): String = replace('\r', ' ').replace('\n', ' ')
private fun String.normalizeLineBreaks(): String = replace("\r\n", "\n").replace('\r', '\n')

private const val MAX_DAILY_EVENT_TEMPLATES = 100
private const val MAX_DAILY_EVENT_TEXT_CHARS = 100
private const val MAX_DAILY_EVENT_UNIT_CHARS = 12
private const val MAX_RSS_SUBSCRIPTIONS = 100
private const val MAX_AI_SYSTEM_PROMPT_CHARS = 20_000
private const val MAX_AI_MODEL_CHARS = 512
private const val MAX_AI_API_KEY_CHARS = 8_192
private const val MAX_URL_CHARS = 4_096
private const val MAX_CLOUD_SYNC_CONFIGS = 20
private const val MAX_CLOUD_SYNC_ID_CHARS = 128
private const val MAX_CLOUD_SYNC_NAME_CHARS = 200
private const val MAX_CLOUD_SYNC_PATH_CHARS = 1_024
private const val MAX_CLOUD_SYNC_USER_AGENT_CHARS = 512
private const val MAX_CLOUD_SYNC_USERNAME_CHARS = 512
private const val MAX_CLOUD_SYNC_BUCKET_CHARS = 255
private const val MAX_CLOUD_SYNC_REGION_CHARS = 128
private const val MAX_CLOUD_SYNC_CREDENTIAL_CHARS = 8_192
internal const val MAX_DESKTOP_WIDGET_CONFIGS = 50
private const val MAX_DESKTOP_WIDGET_ID_CHARS = 80
private const val MAX_DESKTOP_WIDGET_NAME_CODE_POINTS = 80
private const val MAX_DESKTOP_WIDGET_PACKAGE_CHARS = 255
private const val MAX_DESKTOP_WIDGET_APP_LABEL_CODE_POINTS = 100
private val DESKTOP_WIDGET_PACKAGE_REGEX =
    Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
private val DESKTOP_WIDGET_ID_REGEX = Regex("[A-Za-z0-9._-]{1,80}")
internal const val MAX_MORE_DESCRIPTION_CODE_POINTS = 160

internal fun normalizeMusicVisualizerFrequencyBounds(
    minimumHz: Int,
    maximumHz: Int,
): Pair<Int, Int> {
    val minimum = minimumHz.coerceIn(20, 19_999)
    val maximum = maximumHz.coerceIn(minimum + 1, 20_000)
    return minimum to maximum
}

internal fun normalizeMoreDescription(value: String): String =
    value.trim()
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .takeCodePoints(MAX_MORE_DESCRIPTION_CODE_POINTS)

internal fun normalizeNavItems(items: List<NavItemConfig>): List<NavItemConfig> {
    val distinctItems = items.distinctBy(NavItemConfig::id).map { item ->
        item.copy(
            visible = item.visible || item.id == NavItemId.SETTINGS,
            showInMore = item.showInMore &&
                item.id != NavItemId.HOME &&
                item.id != NavItemId.MORE &&
                item.id != NavItemId.SETTINGS,
            moreDescription = normalizeMoreDescription(item.moreDescription),
        )
    }
    val presentIds = distinctItems.map(NavItemConfig::id).toSet()
    val missingNonSettings = NavItemId.entries
        .filter { id -> id != NavItemId.SETTINGS && id !in presentIds }
        .map(::NavItemConfig)
    val settingsIndex = distinctItems.indexOfFirst { it.id == NavItemId.SETTINGS }

    return when {
        settingsIndex == -1 ->
            distinctItems + missingNonSettings + NavItemConfig(NavItemId.SETTINGS)
        settingsIndex == distinctItems.lastIndex ->
            distinctItems.dropLast(1) + missingNonSettings + distinctItems.last()
        else ->
            distinctItems + missingNonSettings
    }
}

internal fun migrateMealPhotosWidget(items: List<String>, migrated: Boolean): List<String> {
    if (migrated || "meal_photos" in items) return items

    val quickInputIndex = items.indexOf("quick_input")
    if (quickInputIndex == -1) return items + "meal_photos"

    return items.toMutableList().apply {
        add(quickInputIndex + 1, "meal_photos")
    }
}

internal fun migrateDailyRecordsWidget(items: List<String>, migrated: Boolean): List<String> {
    if (migrated || "daily_records" in items) return items

    val quickInputIndex = items.indexOf("quick_input")
    if (quickInputIndex == -1) return items + "daily_records"

    return items.toMutableList().apply {
        add(quickInputIndex + 1, "daily_records")
    }
}

internal fun migrateHomeModulesV26(items: List<String>, migrated: Boolean): List<String> {
    if (migrated) return items
    return (items + listOf("notes", "game_shortcuts", "record_overview")).distinct()
}

internal fun migrateHomeCloudSyncWidget(items: List<String>, migrated: Boolean): List<String> {
    val withLegacyWidget = if (
        migrated ||
        "cloud_sync" in items ||
        "cloud_sync_now" in items ||
        "cloud_sync_force" in items
    ) {
        items
    } else {
        items + "cloud_sync"
    }
    val result = mutableListOf<String>()
    withLegacyWidget.forEach { item ->
        if (item == "cloud_sync") {
            if ("cloud_sync_now" !in result) result += "cloud_sync_now"
            if ("cloud_sync_force" !in result) result += "cloud_sync_force"
        } else if (item !in result) {
            result += item
        }
    }
    return result
}
