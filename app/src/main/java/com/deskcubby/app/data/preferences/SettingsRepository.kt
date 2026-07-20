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
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.DEFAULT_MEAL_BUTTON_ICONS
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
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
        val themeColorArgb = intPreferencesKey("theme_color_argb")
        val backupTreeUri = stringPreferencesKey("backup_tree_uri")
        val diaryTreeUri = stringPreferencesKey("diary_tree_uri")
        val mediaTreeUri = stringPreferencesKey("media_tree_uri")
        val fileNamePattern = stringPreferencesKey("file_name_pattern")
        val markdownTemplate = stringPreferencesKey("markdown_template")
        val imageNamePattern = stringPreferencesKey("image_name_pattern")
        val imageMaxWidthDp = intPreferencesKey("image_max_width_dp")
        val imageMaxHeightDp = intPreferencesKey("image_max_height_dp")
        val mealImageCompressionEnabled = booleanPreferencesKey("meal_image_compression_enabled")
        val mealImageCompressionQuality = intPreferencesKey("meal_image_compression_quality")
        val browserHomeUrl = stringPreferencesKey("browser_home_url")
        val lastBrowserUrl = stringPreferencesKey("last_browser_url")
        val browserTheme = stringPreferencesKey("browser_theme")
        val browserDesktopMode = booleanPreferencesKey("browser_desktop_mode")
        val thoughtSplitRatio = floatPreferencesKey("thought_split_ratio")
        val thoughtRowHeightDp = intPreferencesKey("thought_row_height_dp")
        val mealButtonsUseIcons = booleanPreferencesKey("meal_buttons_use_icons")
        val mealButtonIcons = stringPreferencesKey("meal_button_icons")
        val navItems = stringPreferencesKey("nav_items")
        val defaultPage = stringPreferencesKey("default_page")
        val bottomNavShowLabels = booleanPreferencesKey("bottom_nav_show_labels")
        val homeWidgetBordersEnabled = booleanPreferencesKey("home_widget_borders_enabled")
        val homeWidgets = stringPreferencesKey("home_widgets")
        val mealPhotosWidgetMigrated = booleanPreferencesKey("meal_photos_widget_migrated")
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
        val visibleIds = nav.filter { it.visible || it.id == NavItemId.SETTINGS }.map { it.id }.toSet()
        val requestedDefault = prefs[Keys.defaultPage].enumValueOr(defaults.defaultPage)
        return AppSettings(
            visualStyle = prefs[Keys.visualStyle].enumValueOr(defaults.visualStyle),
            darkMode = prefs[Keys.darkMode].enumValueOr(defaults.darkMode),
            appLanguage = prefs[Keys.appLanguage].enumValueOr(defaults.appLanguage),
            userName = normalizeUserName(prefs[Keys.userName] ?: defaults.userName),
            themeColorArgb = prefs[Keys.themeColorArgb] ?: defaults.themeColorArgb,
            backupTreeUri = prefs[Keys.backupTreeUri]?.takeIf(::hasPersistedTreeAccess),
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
            browserHomeUrl = prefs[Keys.browserHomeUrl]?.takeIf { it.isNotBlank() } ?: defaults.browserHomeUrl,
            lastBrowserUrl = prefs[Keys.lastBrowserUrl],
            browserTheme = prefs[Keys.browserTheme].enumValueOr(defaults.browserTheme),
            browserDesktopMode = prefs[Keys.browserDesktopMode] ?: defaults.browserDesktopMode,
            thoughtSplitRatio = (prefs[Keys.thoughtSplitRatio] ?: defaults.thoughtSplitRatio).coerceIn(0.25f, 0.8f),
            thoughtRowHeightDp = (prefs[Keys.thoughtRowHeightDp] ?: defaults.thoughtRowHeightDp).coerceIn(48, 120),
            mealButtonsUseIcons = prefs[Keys.mealButtonsUseIcons] ?: defaults.mealButtonsUseIcons,
            mealButtonIcons = decodeMealButtonIcons(prefs[Keys.mealButtonIcons], defaults.mealButtonIcons),
            navItems = nav,
            defaultPage = requestedDefault.takeIf { it in visibleIds } ?: visibleIds.firstOrNull() ?: NavItemId.SETTINGS,
            bottomNavShowLabels = prefs[Keys.bottomNavShowLabels] ?: defaults.bottomNavShowLabels,
            homeWidgetBordersEnabled = prefs[Keys.homeWidgetBordersEnabled]
                ?: defaults.homeWidgetBordersEnabled,
            homeWidgets = migrateMealPhotosWidget(
                items = decodeWidgets(prefs[Keys.homeWidgets], defaults.homeWidgets),
                migrated = prefs[Keys.mealPhotosWidgetMigrated] == true,
            ),
            homeWidgetTitles = decodeStringList(prefs[Keys.homeWidgetTitles], defaults.homeWidgetTitles),
        )
    }

    suspend fun setVisualStyle(value: VisualStyle) = set(Keys.visualStyle, value.name)
    suspend fun setDarkMode(value: DarkMode) = set(Keys.darkMode, value.name)
    suspend fun setAppLanguage(value: AppLanguage) = set(Keys.appLanguage, value.name)
    suspend fun setUserName(value: String) = set(Keys.userName, normalizeUserName(value))
    suspend fun setThemeColor(value: Int) = set(Keys.themeColorArgb, value or 0xFF000000.toInt())
    suspend fun setBackupTreeUri(value: String?) {
        context.settingsDataStore.edit {
            it.setOrRemove(Keys.backupTreeUri, value?.takeIf(String::isNotBlank))
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
    suspend fun setBrowserHomeUrl(value: String) = set(Keys.browserHomeUrl, normalizeUrl(value))
    suspend fun setLastBrowserUrl(value: String) = set(Keys.lastBrowserUrl, value)
    suspend fun setBrowserTheme(value: BrowserTheme) = set(Keys.browserTheme, value.name)
    suspend fun setBrowserDesktopMode(value: Boolean) = set(Keys.browserDesktopMode, value)
    suspend fun setThoughtSplitRatio(value: Float) = set(Keys.thoughtSplitRatio, value.coerceIn(0.25f, 0.8f))
    suspend fun setThoughtRowHeight(value: Int) = set(Keys.thoughtRowHeightDp, value.coerceIn(48, 120))
    suspend fun setMealButtonsUseIcons(value: Boolean) = set(Keys.mealButtonsUseIcons, value)
    suspend fun setMealButtonIcons(value: List<String>) =
        set(Keys.mealButtonIcons, encodeStringList(normalizeMealButtonIcons(value)))
    suspend fun setDefaultPage(value: NavItemId) = set(Keys.defaultPage, value.name)
    suspend fun setBottomNavShowLabels(value: Boolean) = set(Keys.bottomNavShowLabels, value)
    suspend fun setHomeWidgetBordersEnabled(value: Boolean) = set(Keys.homeWidgetBordersEnabled, value)
    suspend fun setHomeWidgets(value: List<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.homeWidgets] = encodeStringList(value.distinct())
            prefs[Keys.mealPhotosWidgetMigrated] = true
        }
    }
    suspend fun setHomeWidgetTitles(value: List<String>) =
        set(Keys.homeWidgetTitles, encodeStringList(value.distinct()))

    suspend fun setNavItems(value: List<NavItemConfig>) {
        set(Keys.navItems, encodeNav(normalizeNavItems(value)))
    }

    suspend fun restoreFromBackup(value: AppSettings) {
        val normalizedNav = normalizeNavItems(value.navItems)
        val visibleIds = normalizedNav.filter(NavItemConfig::visible).map(NavItemConfig::id).toSet()
        val normalizedDefaultPage = value.defaultPage.takeIf(visibleIds::contains)
            ?: visibleIds.firstOrNull()
            ?: NavItemId.SETTINGS

        context.settingsDataStore.edit { prefs ->
            prefs[Keys.visualStyle] = value.visualStyle.name
            prefs[Keys.darkMode] = value.darkMode.name
            prefs[Keys.appLanguage] = value.appLanguage.name
            prefs[Keys.userName] = normalizeUserName(value.userName)
            prefs[Keys.themeColorArgb] = value.themeColorArgb or 0xFF000000.toInt()
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
            prefs[Keys.browserHomeUrl] = normalizeUrl(value.browserHomeUrl)
            prefs.setOrRemove(Keys.lastBrowserUrl, value.lastBrowserUrl)
            prefs[Keys.browserTheme] = value.browserTheme.name
            prefs[Keys.browserDesktopMode] = value.browserDesktopMode
            prefs[Keys.thoughtSplitRatio] = value.thoughtSplitRatio.coerceIn(0.25f, 0.8f)
            prefs[Keys.thoughtRowHeightDp] = value.thoughtRowHeightDp.coerceIn(48, 120)
            prefs[Keys.mealButtonsUseIcons] = value.mealButtonsUseIcons
            prefs[Keys.mealButtonIcons] = encodeStringList(normalizeMealButtonIcons(value.mealButtonIcons))
            prefs[Keys.navItems] = encodeNav(normalizedNav)
            prefs[Keys.defaultPage] = normalizedDefaultPage.name
            prefs[Keys.bottomNavShowLabels] = value.bottomNavShowLabels
            prefs[Keys.homeWidgetBordersEnabled] = value.homeWidgetBordersEnabled
            prefs[Keys.homeWidgets] = encodeStringList(value.homeWidgets.distinct())
            prefs[Keys.mealPhotosWidgetMigrated] = true
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
        val array = JSONArray(raw ?: return@runCatching NavItemId.entries.map(::NavItemConfig))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = NavItemId.valueOf(item.getString("id"))
                add(
                    NavItemConfig(
                        id = id,
                        label = migrateLegacyDefaultLabel(
                            id,
                            item.optString("label", id.defaultLabel).ifBlank { id.defaultLabel },
                        ),
                        iconKey = item.optString("icon", id.defaultIcon),
                        visible = item.optBoolean("visible", true) || id == NavItemId.SETTINGS,
                    ),
                )
            }
        }.let(::normalizeNavItems)
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

    private fun encodeStringList(items: List<String>): String = JSONArray().apply {
        items.forEach { put(it) }
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

private const val MAX_USER_NAME_CHARS = 32
private const val MAX_MEAL_BUTTON_ICON_CHARS = 16

internal fun normalizeNavItems(items: List<NavItemConfig>): List<NavItemConfig> {
    val distinctItems = items.distinctBy(NavItemConfig::id).map { item ->
        item.copy(visible = item.visible || item.id == NavItemId.SETTINGS)
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
