package com.deskcubby.app.plugin.adapter

import com.deskcubby.app.BuildConfig
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.AppAPI
import com.deskcubby.plugin.api.core.api.AppSettingMutationPlan
import com.deskcubby.plugin.api.core.api.AppSettingMutationResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * The Agent-facing application boundary. Only explicitly allowlisted, non-sensitive settings are
 * exposed here. Paths, model configuration, API keys, cloud credentials, and permission controls
 * intentionally have no representation in this API.
 */
@Singleton
class AppApiAdapter @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : AppAPI {
    override suspend fun settings(): Map<String, String> = settingsRepository.settings.first().let { value ->
        linkedMapOf(
            APP_LANGUAGE to value.appLanguage.name,
            DARK_MODE to value.darkMode.name,
            VISUAL_STYLE to value.visualStyle.name,
            FONT_SCALE to value.fontScale.toString(),
            COMPACT_MODE to value.compactMode.toString(),
            TUTORIAL_MODE to value.tutorialModeEnabled.toString(),
            THOUGHT_DISPLAY_MODE to value.thoughtDisplayMode.name,
            MEAL_CAPTIONS to value.mealCalendarShowCaptions.toString(),
            MEAL_BUTTON_ICONS to value.mealButtonsUseIcons.toString(),
            NAV_LABELS to value.bottomNavShowLabels.toString(),
            HOME_WIDGET_BORDERS to value.homeWidgetBordersEnabled.toString(),
            BROWSER_THEME to value.browserTheme.name,
            BROWSER_DESKTOP_MODE to value.browserDesktopMode.toString(),
        )
    }

    override suspend fun state(): Map<String, String> = settingsRepository.settings.first().let { value ->
        linkedMapOf(
            "applicationId" to BuildConfig.APPLICATION_ID,
            "versionName" to BuildConfig.VERSION_NAME,
            "diaryConfigured" to (!value.diaryTreeUri.isNullOrBlank()).toString(),
            "mediaConfigured" to (!value.mediaTreeUri.isNullOrBlank()).toString(),
            "notesConfigured" to (!value.notesTreeUri.isNullOrBlank()).toString(),
            "usageTrackingEnabled" to value.usageTrackingEnabled.toString(),
            "stepTrackingEnabled" to value.stepTrackingEnabled.toString(),
            "enabledTextModels" to value.aiConfigs.count { it.enabled && it.type.name == "TEXT" }.toString(),
        )
    }

    override suspend fun prepareSettingMutation(key: String, value: String): AppSettingMutationPlan {
        val normalizedKey = key.trim()
        val normalizedAfter = normalize(normalizedKey, value)
        val before = current(normalizedKey)
        val token = JSONObject()
            .put("schema", PLAN_SCHEMA)
            .put("key", normalizedKey)
            .put("before", before)
            .put("after", normalizedAfter)
            .toString()
        return AppSettingMutationPlan(
            planToken = token,
            key = normalizedKey,
            before = before,
            after = normalizedAfter,
            summary = "Set $normalizedKey from $before to $normalizedAfter",
        )
    }

    override suspend fun commitSettingMutation(planToken: String): AppSettingMutationResult {
        val token = decode(planToken, PLAN_SCHEMA)
        val key = token.getString("key")
        val before = normalize(key, token.getString("before"))
        val after = normalize(key, token.getString("after"))
        if (current(key) != before) {
            throw PluginApiException(
                "SETTING_CONFLICT",
                "The setting changed after the Agent prepared its modification.",
            )
        }
        apply(key, after)
        val undoToken = JSONObject()
            .put("schema", UNDO_SCHEMA)
            .put("key", key)
            .put("before", before)
            .put("after", after)
            .toString()
        return AppSettingMutationResult(
            key = key,
            before = before,
            after = after,
            summary = "Updated $key",
            undoToken = undoToken,
        )
    }

    override suspend fun undoSettingMutation(undoToken: String): AppSettingMutationResult {
        val token = decode(undoToken, UNDO_SCHEMA)
        val key = token.getString("key")
        val before = normalize(key, token.getString("before"))
        val after = normalize(key, token.getString("after"))
        if (current(key) != after) {
            throw PluginApiException(
                "SETTING_UNDO_CONFLICT",
                "The setting changed after the Agent modification and cannot be safely restored.",
            )
        }
        apply(key, before)
        return AppSettingMutationResult(
            key = key,
            before = after,
            after = before,
            summary = "Restored $key",
        )
    }

    private suspend fun current(key: String): String = settings()[key]
        ?: throw PluginApiException("SETTING_NOT_ALLOWED", "This setting is not available to the Agent.")

    private fun normalize(key: String, raw: String): String = try {
        when (key) {
            APP_LANGUAGE -> AppLanguage.valueOf(raw.trim().uppercase()).name
            DARK_MODE -> DarkMode.valueOf(raw.trim().uppercase()).name
            VISUAL_STYLE -> VisualStyle.valueOf(raw.trim().uppercase()).name
            THOUGHT_DISPLAY_MODE -> ThoughtDisplayMode.valueOf(raw.trim().uppercase()).name
            BROWSER_THEME -> BrowserTheme.valueOf(raw.trim().uppercase()).name
            FONT_SCALE -> raw.trim().toFloat().also {
                require(it.isFinite() && it in 0.8f..1.3f)
            }.toString()
            COMPACT_MODE,
            TUTORIAL_MODE,
            MEAL_CAPTIONS,
            MEAL_BUTTON_ICONS,
            NAV_LABELS,
            HOME_WIDGET_BORDERS,
            BROWSER_DESKTOP_MODE,
            -> raw.strictBoolean().toString()
            else -> throw PluginApiException(
                "SETTING_NOT_ALLOWED",
                "This setting is not available to the Agent.",
            )
        }
    } catch (error: PluginApiException) {
        throw error
    } catch (_: Exception) {
        throw PluginApiException("INVALID_SETTING_VALUE", "The requested setting value is invalid.")
    }

    private suspend fun apply(key: String, value: String) {
        when (key) {
            APP_LANGUAGE -> settingsRepository.setAppLanguage(AppLanguage.valueOf(value))
            DARK_MODE -> settingsRepository.setDarkMode(DarkMode.valueOf(value))
            VISUAL_STYLE -> settingsRepository.setVisualStyle(VisualStyle.valueOf(value))
            FONT_SCALE -> settingsRepository.setFontScale(value.toFloat())
            COMPACT_MODE -> settingsRepository.setCompactMode(value.toBooleanStrict())
            TUTORIAL_MODE -> settingsRepository.setTutorialModeEnabled(value.toBooleanStrict())
            THOUGHT_DISPLAY_MODE -> settingsRepository.setThoughtDisplayMode(ThoughtDisplayMode.valueOf(value))
            MEAL_CAPTIONS -> settingsRepository.setMealCalendarShowCaptions(value.toBooleanStrict())
            MEAL_BUTTON_ICONS -> settingsRepository.setMealButtonsUseIcons(value.toBooleanStrict())
            NAV_LABELS -> settingsRepository.setBottomNavShowLabels(value.toBooleanStrict())
            HOME_WIDGET_BORDERS -> settingsRepository.setHomeWidgetBordersEnabled(value.toBooleanStrict())
            BROWSER_THEME -> settingsRepository.setBrowserTheme(BrowserTheme.valueOf(value))
            BROWSER_DESKTOP_MODE -> settingsRepository.setBrowserDesktopMode(value.toBooleanStrict())
            else -> throw PluginApiException("SETTING_NOT_ALLOWED", "This setting is not available to the Agent.")
        }
    }

    private fun decode(raw: String, schema: String): JSONObject {
        if (raw.length > MAX_TOKEN_CHARS) {
            throw PluginApiException("INVALID_MUTATION_TOKEN", "The Agent mutation token is invalid.")
        }
        val value = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw PluginApiException("INVALID_MUTATION_TOKEN", "The Agent mutation token is invalid.")
        }
        if (value.optString("schema") != schema || value.length() != 4) {
            throw PluginApiException("INVALID_MUTATION_TOKEN", "The Agent mutation token is invalid.")
        }
        return value
    }

    private fun String.strictBoolean(): Boolean = when (trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Boolean expected")
    }

    private companion object {
        const val PLAN_SCHEMA = "deskcubby/app-setting-plan/v1"
        const val UNDO_SCHEMA = "deskcubby/app-setting-undo/v1"
        const val MAX_TOKEN_CHARS = 8 * 1024

        const val APP_LANGUAGE = "app_language"
        const val DARK_MODE = "dark_mode"
        const val VISUAL_STYLE = "visual_style"
        const val FONT_SCALE = "font_scale"
        const val COMPACT_MODE = "compact_mode"
        const val TUTORIAL_MODE = "tutorial_mode_enabled"
        const val THOUGHT_DISPLAY_MODE = "thought_display_mode"
        const val MEAL_CAPTIONS = "meal_calendar_show_captions"
        const val MEAL_BUTTON_ICONS = "meal_buttons_use_icons"
        const val NAV_LABELS = "bottom_navigation_show_labels"
        const val HOME_WIDGET_BORDERS = "home_widget_borders_enabled"
        const val BROWSER_THEME = "browser_theme"
        const val BROWSER_DESKTOP_MODE = "browser_desktop_mode"
    }
}
