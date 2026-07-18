package com.deskcubby.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(name = "deskcubby_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val visualStyle = stringPreferencesKey("visual_style")
        val darkMode = stringPreferencesKey("dark_mode")
        val diaryTreeUri = stringPreferencesKey("diary_tree_uri")
        val mediaTreeUri = stringPreferencesKey("media_tree_uri")
        val mediaMarkdownPrefix = stringPreferencesKey("media_markdown_prefix")
        val fileNamePattern = stringPreferencesKey("file_name_pattern")
        val titlePattern = stringPreferencesKey("title_pattern")
        val datePattern = stringPreferencesKey("date_pattern")
        val markdownTemplate = stringPreferencesKey("markdown_template")
        val imageNamePattern = stringPreferencesKey("image_name_pattern")
        val imageMaxWidthDp = intPreferencesKey("image_max_width_dp")
        val imageMaxHeightDp = intPreferencesKey("image_max_height_dp")
        val browserHomeUrl = stringPreferencesKey("browser_home_url")
        val lastBrowserUrl = stringPreferencesKey("last_browser_url")
        val thoughtSplitRatio = floatPreferencesKey("thought_split_ratio")
        val navItems = stringPreferencesKey("nav_items")
        val defaultPage = stringPreferencesKey("default_page")
        val homeWidgets = stringPreferencesKey("home_widgets")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map(::decode)

    private fun decode(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        val nav = decodeNav(prefs[Keys.navItems])
        val visibleIds = nav.filter { it.visible || it.id == NavItemId.SETTINGS }.map { it.id }.toSet()
        val requestedDefault = prefs[Keys.defaultPage].enumValueOr(defaults.defaultPage)
        return AppSettings(
            visualStyle = prefs[Keys.visualStyle].enumValueOr(defaults.visualStyle),
            darkMode = prefs[Keys.darkMode].enumValueOr(defaults.darkMode),
            diaryTreeUri = prefs[Keys.diaryTreeUri],
            mediaTreeUri = prefs[Keys.mediaTreeUri],
            mediaMarkdownPrefix = prefs[Keys.mediaMarkdownPrefix] ?: defaults.mediaMarkdownPrefix,
            fileNamePattern = prefs[Keys.fileNamePattern] ?: defaults.fileNamePattern,
            titlePattern = prefs[Keys.titlePattern] ?: defaults.titlePattern,
            datePattern = prefs[Keys.datePattern] ?: defaults.datePattern,
            markdownTemplate = prefs[Keys.markdownTemplate] ?: defaults.markdownTemplate,
            imageNamePattern = prefs[Keys.imageNamePattern] ?: defaults.imageNamePattern,
            imageMaxWidthDp = (prefs[Keys.imageMaxWidthDp] ?: defaults.imageMaxWidthDp).coerceIn(120, 2400),
            imageMaxHeightDp = (prefs[Keys.imageMaxHeightDp] ?: defaults.imageMaxHeightDp).coerceIn(120, 2400),
            browserHomeUrl = prefs[Keys.browserHomeUrl]?.takeIf { it.isNotBlank() } ?: defaults.browserHomeUrl,
            lastBrowserUrl = prefs[Keys.lastBrowserUrl],
            thoughtSplitRatio = (prefs[Keys.thoughtSplitRatio] ?: defaults.thoughtSplitRatio).coerceIn(0.25f, 0.8f),
            navItems = nav,
            defaultPage = requestedDefault.takeIf { it in visibleIds } ?: visibleIds.firstOrNull() ?: NavItemId.SETTINGS,
            homeWidgets = decodeWidgets(prefs[Keys.homeWidgets], defaults.homeWidgets),
        )
    }

    suspend fun setVisualStyle(value: VisualStyle) = set(Keys.visualStyle, value.name)
    suspend fun setDarkMode(value: DarkMode) = set(Keys.darkMode, value.name)
    suspend fun setDiaryTreeUri(value: String) = set(Keys.diaryTreeUri, value)
    suspend fun setMediaTreeUri(value: String) = set(Keys.mediaTreeUri, value)
    suspend fun setMediaMarkdownPrefix(value: String) = set(Keys.mediaMarkdownPrefix, value.trim().trimEnd('/'))
    suspend fun setFileNamePattern(value: String) = set(Keys.fileNamePattern, value)
    suspend fun setTitlePattern(value: String) = set(Keys.titlePattern, value)
    suspend fun setDatePattern(value: String) = set(Keys.datePattern, value)
    suspend fun setMarkdownTemplate(value: String) = set(Keys.markdownTemplate, value)
    suspend fun setImageNamePattern(value: String) = set(Keys.imageNamePattern, value)
    suspend fun setImageMaxWidth(value: Int) = set(Keys.imageMaxWidthDp, value.coerceIn(120, 2400))
    suspend fun setImageMaxHeight(value: Int) = set(Keys.imageMaxHeightDp, value.coerceIn(120, 2400))
    suspend fun setBrowserHomeUrl(value: String) = set(Keys.browserHomeUrl, normalizeUrl(value))
    suspend fun setLastBrowserUrl(value: String) = set(Keys.lastBrowserUrl, value)
    suspend fun setThoughtSplitRatio(value: Float) = set(Keys.thoughtSplitRatio, value.coerceIn(0.25f, 0.8f))
    suspend fun setDefaultPage(value: NavItemId) = set(Keys.defaultPage, value.name)
    suspend fun setHomeWidgets(value: List<String>) = set(Keys.homeWidgets, value.distinct().joinToString(","))

    suspend fun setNavItems(value: List<NavItemConfig>) {
        val byId = value.associateBy { it.id }
        val normalized = buildList {
            value.distinctBy { it.id }.forEach { item ->
                add(item.copy(visible = item.visible || item.id == NavItemId.SETTINGS))
            }
            NavItemId.entries.filterNot(byId::containsKey).forEach { add(NavItemConfig(it)) }
        }
        set(Keys.navItems, encodeNav(normalized))
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private fun decodeNav(raw: String?): List<NavItemConfig> = runCatching {
        val array = JSONArray(raw ?: return@runCatching NavItemId.entries.map(::NavItemConfig))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = NavItemId.valueOf(item.getString("id"))
                add(
                    NavItemConfig(
                        id = id,
                        label = item.optString("label", id.defaultLabel).ifBlank { id.defaultLabel },
                        iconKey = item.optString("icon", id.defaultIcon),
                        visible = item.optBoolean("visible", true) || id == NavItemId.SETTINGS,
                    ),
                )
            }
        }.let { decoded ->
            decoded + NavItemId.entries.filterNot { id -> decoded.any { it.id == id } }.map(::NavItemConfig)
        }
    }.getOrElse { NavItemId.entries.map(::NavItemConfig) }

    private fun encodeNav(items: List<NavItemConfig>): String = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject()
                    .put("id", item.id.name)
                    .put("label", item.label)
                    .put("icon", item.iconKey)
                    .put("visible", item.visible || item.id == NavItemId.SETTINGS),
            )
        }
    }.toString()

    private fun decodeWidgets(raw: String?, fallback: List<String>): List<String> =
        raw?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.distinct()?.takeIf(List<String>::isNotEmpty)
            ?: fallback

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
