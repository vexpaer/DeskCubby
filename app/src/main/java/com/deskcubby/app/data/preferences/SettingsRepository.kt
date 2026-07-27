package com.deskcubby.app.data.preferences

import android.content.Context
import android.net.Uri
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
import androidx.datastore.preferences.preferencesDataStore
import com.deskcubby.app.takeCodePoints
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.DEFAULT_MEAL_BUTTON_ICONS
import com.deskcubby.app.data.model.DEFAULT_THEME_SECONDARY_COLORS_ARGB
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.LauncherIcon
import com.deskcubby.app.data.model.MAX_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MIN_THOUGHT_EDITOR_MAX_HEIGHT_DP
import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.MAX_APP_FONT_SCALE
import com.deskcubby.app.data.model.MAX_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.data.model.MIN_APP_FONT_SCALE
import com.deskcubby.app.data.model.MIN_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.ThoughtReopenMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.normalizeMorePageOrder
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
        val darkMode = stringPreferencesKey("dark_mode")
        val appLanguage = stringPreferencesKey("app_language")
        val userName = stringPreferencesKey("user_name")
        val homeGreetings = stringPreferencesKey("home_greetings_v1")
        val themeColorArgb = intPreferencesKey("theme_color_argb")
        val themeSecondaryColorsArgb = stringPreferencesKey("theme_secondary_colors_argb")
        val fontScale = floatPreferencesKey("font_scale")
        val compactMode = booleanPreferencesKey("compact_mode")
        val useChineseLauncherName = booleanPreferencesKey("use_chinese_launcher_name")
        val launcherIcon = stringPreferencesKey("launcher_icon")
        val backupTreeUri = stringPreferencesKey("backup_tree_uri")
        val cloudSyncEnabled = booleanPreferencesKey("cloud_sync_enabled")
        val cloudSyncConfigs = stringPreferencesKey("cloud_sync_configs_v1")
        val diaryTreeUri = stringPreferencesKey("diary_tree_uri")
        val mediaTreeUri = stringPreferencesKey("media_tree_uri")
        val fileNamePattern = stringPreferencesKey("file_name_pattern")
        val markdownTemplate = stringPreferencesKey("markdown_template")
        val imageNamePattern = stringPreferencesKey("image_name_pattern")
        val imageMaxWidthDp = intPreferencesKey("image_max_width_dp")
        val imageMaxHeightDp = intPreferencesKey("image_max_height_dp")
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
        val morePageShowDescriptions = booleanPreferencesKey("more_page_show_descriptions")
        val homeWidgetBordersEnabled = booleanPreferencesKey("home_widget_borders_enabled")
        val homeWidgets = stringPreferencesKey("home_widgets")
        val mealPhotosWidgetMigrated = booleanPreferencesKey("meal_photos_widget_migrated")
        val dailyRecordsWidgetMigrated = booleanPreferencesKey("daily_records_widget_migrated")
        val homeWidgetTitles = stringPreferencesKey("home_widget_titles")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::decode)

    private fun decode(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
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
        return AppSettings(
            visualStyle = prefs[Keys.visualStyle].enumValueOr(defaults.visualStyle),
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
            useChineseLauncherName = prefs[Keys.useChineseLauncherName]
                ?: defaults.useChineseLauncherName,
            launcherIcon = prefs[Keys.launcherIcon].enumValueOr(defaults.launcherIcon),
            backupTreeUri = prefs[Keys.backupTreeUri]?.takeIf(::hasPersistedTreeAccess),
            cloudSyncEnabled = (prefs[Keys.cloudSyncEnabled] ?: false) &&
                cloudSyncConfigs.any { it.enabled && it.selectedContents.isNotEmpty() },
            cloudSyncConfigs = cloudSyncConfigs,
            diaryTreeUri = prefs[Keys.diaryTreeUri]?.takeIf(::hasPersistedTreeAccess),
            mediaTreeUri = prefs[Keys.mediaTreeUri]?.takeIf(::hasPersistedTreeAccess),
            fileNamePattern = (prefs[Keys.fileNamePattern] ?: defaults.fileNamePattern)
                .let { if (it == "yyyy-MM-dd '日记'") defaults.fileNamePattern else it },
            markdownTemplate = prefs[Keys.markdownTemplate] ?: defaults.markdownTemplate,
            imageNamePattern = prefs[Keys.imageNamePattern] ?: defaults.imageNamePattern,
            imageMaxWidthDp = (prefs[Keys.imageMaxWidthDp] ?: defaults.imageMaxWidthDp).coerceIn(120, 2400),
            imageMaxHeightDp = (prefs[Keys.imageMaxHeightDp] ?: defaults.imageMaxHeightDp).coerceIn(120, 2400),
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
            calorieEstimationEnabled = (prefs[Keys.calorieEstimationEnabled] ?: false) &&
                calorieTextId != null && calorieImageId != null,
            calorieTextConfigId = calorieTextId,
            calorieImageConfigId = calorieImageId,
            calorieVisionPrompt = prefs[Keys.calorieVisionPrompt] ?: defaults.calorieVisionPrompt,
            calorieTextPrompt = prefs[Keys.calorieTextPrompt] ?: defaults.calorieTextPrompt,
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
            morePageShowDescriptions = prefs[Keys.morePageShowDescriptions]
                ?: defaults.morePageShowDescriptions,
            homeWidgetBordersEnabled = prefs[Keys.homeWidgetBordersEnabled]
                ?: defaults.homeWidgetBordersEnabled,
            // The widget migrations only apply to users with a stored list from an older
            // release; a fresh install must keep the small first-launch preset untouched.
            homeWidgets = if (prefs[Keys.homeWidgets] == null) {
                defaults.homeWidgets
            } else {
                migrateDailyRecordsWidget(
                    items = migrateMealPhotosWidget(
                        items = decodeWidgets(prefs[Keys.homeWidgets], defaults.homeWidgets),
                        migrated = prefs[Keys.mealPhotosWidgetMigrated] == true,
                    ),
                    migrated = prefs[Keys.dailyRecordsWidgetMigrated] == true,
                )
            },
            homeWidgetTitles = if (prefs[Keys.homeWidgetTitles] == null) {
                defaults.homeWidgetTitles
            } else {
                migrateDailyRecordsWidget(
                    items = decodeStringList(prefs[Keys.homeWidgetTitles], defaults.homeWidgetTitles),
                    migrated = prefs[Keys.dailyRecordsWidgetMigrated] == true,
                )
            },
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
    suspend fun setFileNamePattern(value: String) = set(Keys.fileNamePattern, value)
    suspend fun setMarkdownTemplate(value: String) = set(Keys.markdownTemplate, value)
    suspend fun setImageNamePattern(value: String) = set(Keys.imageNamePattern, value)
    suspend fun setImageMaxWidth(value: Int) = set(Keys.imageMaxWidthDp, value.coerceIn(120, 2400))
    suspend fun setImageMaxHeight(value: Int) = set(Keys.imageMaxHeightDp, value.coerceIn(120, 2400))
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

    suspend fun setCalorieEstimationSettings(
        enabled: Boolean, textConfigId: String?, imageConfigId: String?,
        visionPrompt: String, textPrompt: String,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.calorieEstimationEnabled] = enabled
            prefs.setOrRemove(Keys.calorieTextConfigId, textConfigId?.takeIf(String::isNotBlank))
            prefs.setOrRemove(Keys.calorieImageConfigId, imageConfigId?.takeIf(String::isNotBlank))
            prefs[Keys.calorieVisionPrompt] = visionPrompt.take(MAX_AI_SYSTEM_PROMPT_CHARS)
            prefs[Keys.calorieTextPrompt] = textPrompt.take(MAX_AI_SYSTEM_PROMPT_CHARS)
        }
    }
    suspend fun setUsageTrackingEnabled(value: Boolean) = set(Keys.usageTrackingEnabled, value)
    suspend fun setStepTrackingEnabled(value: Boolean) = set(Keys.stepTrackingEnabled, value)
    suspend fun acknowledgeNavigationIntro() = set(Keys.navigationIntroAcknowledged, true)
    suspend fun setDefaultPage(value: NavItemId) = set(Keys.defaultPage, value.name)
    suspend fun setBottomNavShowLabels(value: Boolean) = set(Keys.bottomNavShowLabels, value)
    suspend fun setHomeWidgetBordersEnabled(value: Boolean) = set(Keys.homeWidgetBordersEnabled, value)
    suspend fun setHomePageSettings(
        userName: String,
        widgetBordersEnabled: Boolean,
        widgets: List<String>,
        visibleWidgetTitles: List<String>,
        mealButtonsUseIcons: Boolean,
        mealButtonIcons: List<String>,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.userName] = normalizeUserName(userName)
            prefs[Keys.homeWidgetBordersEnabled] = widgetBordersEnabled
            prefs[Keys.homeWidgets] = encodeStringList(widgets.distinct())
            prefs[Keys.mealPhotosWidgetMigrated] = true
            prefs[Keys.dailyRecordsWidgetMigrated] = true
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
        }
    }
    suspend fun setHomeWidgetTitles(value: List<String>) =
        set(Keys.homeWidgetTitles, encodeStringList(value.distinct()))

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
    ) {
        val normalized = normalizeNavItems(items)
        val visibleIds = normalized.filter { it.visible || it.id == NavItemId.SETTINGS }.map { it.id }.toSet()
        val safeDefault = defaultPage.takeIf { it in visibleIds } ?: visibleIds.firstOrNull() ?: NavItemId.SETTINGS
        context.settingsDataStore.edit { prefs ->
            migrateMorePageOrderIfNeeded(prefs)
            prefs[Keys.navItems] = encodeNav(normalized)
            prefs[Keys.defaultPage] = safeDefault.name
            prefs[Keys.bottomNavShowLabels] = showLabels
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
        val visibleIds = normalizedNav.filter(NavItemConfig::visible).map(NavItemConfig::id).toSet()
        val normalizedDefaultPage = value.defaultPage.takeIf(visibleIds::contains)
            ?: visibleIds.firstOrNull()
            ?: NavItemId.SETTINGS

        context.settingsDataStore.edit { prefs ->
            prefs[Keys.visualStyle] = value.visualStyle.name
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
            prefs[Keys.useChineseLauncherName] = value.useChineseLauncherName
            prefs[Keys.launcherIcon] = value.launcherIcon.name
            prefs[Keys.cloudSyncConfigs] = encodeCloudSyncConfigs(normalizedCloudSyncConfigs)
            prefs[Keys.cloudSyncEnabled] = value.cloudSyncEnabled &&
                normalizedCloudSyncConfigs.any {
                    it.enabled && it.selectedContents.isNotEmpty()
                }
            prefs.setOrRemove(
                Keys.diaryTreeUri,
                restorableTreeUriOrCurrent(value.diaryTreeUri, prefs[Keys.diaryTreeUri]),
            )
            prefs.setOrRemove(
                Keys.mediaTreeUri,
                restorableTreeUriOrCurrent(value.mediaTreeUri, prefs[Keys.mediaTreeUri]),
            )
            prefs[Keys.fileNamePattern] = value.fileNamePattern
            prefs[Keys.markdownTemplate] = value.markdownTemplate
            prefs[Keys.imageNamePattern] = value.imageNamePattern
            prefs[Keys.imageMaxWidthDp] = value.imageMaxWidthDp.coerceIn(120, 2400)
            prefs[Keys.imageMaxHeightDp] = value.imageMaxHeightDp.coerceIn(120, 2400)
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
            prefs[Keys.calorieEstimationEnabled] = value.calorieEstimationEnabled
            prefs.setOrRemove(Keys.calorieTextConfigId, value.calorieTextConfigId)
            prefs.setOrRemove(Keys.calorieImageConfigId, value.calorieImageConfigId)
            prefs[Keys.calorieVisionPrompt] = value.calorieVisionPrompt.take(MAX_AI_SYSTEM_PROMPT_CHARS)
            prefs[Keys.calorieTextPrompt] = value.calorieTextPrompt.take(MAX_AI_SYSTEM_PROMPT_CHARS)
            prefs[Keys.usageTrackingEnabled] = value.usageTrackingEnabled
            prefs[Keys.stepTrackingEnabled] = value.stepTrackingEnabled
            prefs[Keys.navItems] = encodeNav(normalizedNav)
            prefs[Keys.morePageOrder] = encodeMorePageOrder(normalizedMorePageOrder)
            prefs[Keys.defaultPage] = normalizedDefaultPage.name
            prefs[Keys.bottomNavShowLabels] = value.bottomNavShowLabels
            prefs[Keys.morePageShowDescriptions] = value.morePageShowDescriptions
            prefs[Keys.homeWidgetBordersEnabled] = value.homeWidgetBordersEnabled
            prefs[Keys.homeWidgets] = encodeStringList(value.homeWidgets.distinct())
            prefs[Keys.mealPhotosWidgetMigrated] = true
            prefs[Keys.dailyRecordsWidgetMigrated] = true
            prefs[Keys.homeWidgetTitles] = encodeStringList(value.homeWidgetTitles.distinct())
        }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }

    private fun restorableTreeUriOrCurrent(imported: String?, current: String?): String? {
        if (imported == null) return null
        return imported.takeIf(::hasPersistedTreeAccess) ?: current
    }

    private fun hasPersistedTreeAccess(raw: String): Boolean {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
        val isTreeUri = runCatching {
            uri.scheme == "content" && DocumentsContract.isTreeUri(uri)
        }.getOrDefault(false)
        if (!isTreeUri) return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.uri == uri && permission.isReadPermission && permission.isWritePermission
            }
        }.getOrDefault(false)
    }

    private fun decodeNav(raw: String?): List<NavItemConfig> = runCatching {
        val array = JSONArray(raw ?: return@runCatching AppSettings().navItems)
        buildList {
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
                        moreDescription = normalizeMoreDescription(
                            item.optString("moreDescription", id.defaultDescription),
                        ),
                    ),
                )
            }
        }.let(::normalizeNavItems)
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
                        webDavUsername = item.optString("webDavUsername"),
                        s3Bucket = item.optString("s3Bucket"),
                        s3Region = item.optString("s3Region", "us-east-1"),
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
                    .put("webDavUsername", item.webDavUsername)
                    .put("s3Bucket", item.s3Bucket)
                    .put("s3Region", item.s3Region)
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
        else -> label
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
                endpointUrl = item.endpointUrl.trim().take(MAX_URL_CHARS),
                remotePath = normalizedPath,
                webDavUsername = item.webDavUsername.trim().take(MAX_CLOUD_SYNC_USERNAME_CHARS),
                webDavPassword = "",
                s3Bucket = item.s3Bucket.trim().take(MAX_CLOUD_SYNC_BUCKET_CHARS),
                s3Region = item.s3Region.trim().take(MAX_CLOUD_SYNC_REGION_CHARS),
                s3AccessKey = "",
                s3SecretKey = "",
                s3SessionToken = "",
                selectedContents = item.selectedContents.intersect(CloudSyncContent.entries.toSet()),
            )
        }
        .filter { item ->
            item.id.isNotEmpty() &&
                item.name.isNotEmpty() &&
                item.selectedContents.isNotEmpty() &&
                item.remotePath.length <= MAX_CLOUD_SYNC_PATH_CHARS &&
                isValidCloudSyncEndpoint(item.endpointUrl, item.allowInsecureHttp) &&
                (item.serviceType != CloudSyncServiceType.S3_COMPATIBLE ||
                    isValidS3Metadata(item.s3Bucket, item.s3Region))
        }
        .distinctBy(CloudSyncConfig::id)
        .take(MAX_CLOUD_SYNC_CONFIGS)
        .toList()

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
                append(item.text.trim().replaceLineBreaks())
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

private fun opaqueArgb(value: Int): Int = value or 0xFF000000.toInt()

private const val MAX_USER_NAME_CHARS = 32
internal const val MAX_HOME_GREETINGS = 100
internal const val MAX_HOME_GREETING_CODE_POINTS = 40
private const val MAX_MEAL_BUTTON_ICON_CHARS = 16
private fun String.replaceLineBreaks(): String = replace('\r', ' ').replace('\n', ' ')

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
private const val MAX_CLOUD_SYNC_USERNAME_CHARS = 512
private const val MAX_CLOUD_SYNC_BUCKET_CHARS = 255
private const val MAX_CLOUD_SYNC_REGION_CHARS = 128
internal const val MAX_MORE_DESCRIPTION_CODE_POINTS = 160

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
