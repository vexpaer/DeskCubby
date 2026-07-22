package com.deskcubby.app.data.backup

import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.codePointLength
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.data.model.MAX_APP_FONT_SCALE
import com.deskcubby.app.data.model.MAX_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.model.MIN_APP_FONT_SCALE
import com.deskcubby.app.data.model.MIN_THEME_SECONDARY_COLOR_COUNT
import com.deskcubby.app.data.preferences.migrateMealPhotosWidget
import com.deskcubby.app.data.preferences.normalizeThemeSecondaryColors
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class AppBackup(
    val formatVersion: Int = 8,
    val exportedAt: Long,
    val settings: AppSettings,
    val thoughts: List<FlashThoughtEntity>,
    val favorites: List<BrowserRecordEntity>,
    val dateRecords: List<DateRecordEntity> = emptyList(),
    val categories: List<ThoughtCategoryEntity> = emptyList(),
    val poems: List<SavedPoemEntity> = emptyList(),
)

data class BackupSummary(
    val thoughtCount: Int,
    val favoriteCount: Int,
    val exportedAt: Long,
    val dateRecordCount: Int = 0,
    val categoryCount: Int = 0,
    val poemCount: Int = 0,
)

object BackupJsonCodec {
    const val FORMAT_VERSION: Int = 8

    private const val FORMAT_NAME = "DeskCubby"
    private const val MAX_JSON_BYTES = 10 * 1024 * 1024
    private const val MAX_THOUGHTS = 50_000
    private const val MAX_FAVORITES = 20_000
    private const val MAX_DATE_RECORDS = 50_000
    private const val MAX_CATEGORIES = 10_000
    private const val MAX_POEMS = 50_000
    private const val MAX_THOUGHT_CHARS = 1_000_000
    private const val MAX_URL_CHARS = 8_192
    private const val MAX_TITLE_CHARS = 4_096
    private const val MAX_SETTING_STRING_CHARS = 1_000_000
    private const val MAX_DATE_NAME_CHARS = 256
    private const val MAX_DATE_ICON_CHARS = 64
    private const val MAX_CATEGORY_NAME_CHARS = 40
    private const val MAX_POEM_CONTENT_CHARS = 100_000
    private const val MAX_POEM_SOURCE_CHARS = 4_096
    private const val MAX_USERNAME_CHARS = 32
    private const val MAX_MEAL_BUTTON_ICON_CHARS = 16

    fun encode(backup: AppBackup): String {
        require(backup.formatVersion == FORMAT_VERSION) {
            "Unsupported backup version: ${backup.formatVersion}"
        }
        requireValidBrowserUrl(backup.settings.browserHomeUrl, "browserHomeUrl")
        backup.settings.lastBrowserUrl?.let { requireValidBrowserUrl(it, "lastBrowserUrl") }
        validateEntityKeys(
            thoughts = backup.thoughts,
            categories = backup.categories,
            favorites = backup.favorites,
            dateRecords = backup.dateRecords,
            poems = backup.poems,
        )

        val root = JSONObject()
            .put("format", FORMAT_NAME)
            .put("version", backup.formatVersion)
            .put("exportedAt", backup.exportedAt)
            .put("settings", encodeSettings(backup.settings))
            .put("thoughts", encodeThoughts(backup.thoughts))
            .put("categories", encodeCategories(backup.categories))
            .put("favorites", encodeFavorites(backup.favorites))
            .put("dateRecords", encodeDateRecords(backup.dateRecords))
            .put("poems", encodePoems(backup.poems))
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
        )
        validateCategoryReferences(thoughts, categories)
        val favorites = decodeFavorites(root.requiredArray("favorites"))
        val dateRecords = if (version >= 2) {
            decodeDateRecords(root.requiredArray("dateRecords"))
        } else {
            emptyList()
        }
        val poems = if (version >= 4) {
            decodePoems(root.requiredArray("poems"))
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
            poems = poems,
        )
    }

    private fun encodeSettings(settings: AppSettings): JSONObject = JSONObject()
        .put("visualStyle", settings.visualStyle.name)
        .put("darkMode", settings.darkMode.name)
        .put("appLanguage", settings.appLanguage.name)
        .put("themeColorArgb", settings.themeColorArgb)
        .put("themeSecondaryColorsArgb", settings.themeSecondaryColorsArgb.toJsonIntArray())
        .put("fontScale", settings.fontScale)
        .putNullable("diaryTreeUri", settings.diaryTreeUri)
        .putNullable("mediaTreeUri", settings.mediaTreeUri)
        .put("fileNamePattern", settings.fileNamePattern)
        .put("markdownTemplate", settings.markdownTemplate)
        .put("imageNamePattern", settings.imageNamePattern)
        .put("imageMaxWidthDp", settings.imageMaxWidthDp)
        .put("imageMaxHeightDp", settings.imageMaxHeightDp)
        .put("mealImageCompressionEnabled", settings.mealImageCompressionEnabled)
        .put("mealImageCompressionQuality", settings.mealImageCompressionQuality)
        .put("browserHomeUrl", settings.browserHomeUrl)
        .putNullable("lastBrowserUrl", settings.lastBrowserUrl)
        .put("browserTheme", settings.browserTheme.name)
        .put("browserDesktopMode", settings.browserDesktopMode)
        .put("thoughtSplitRatio", settings.thoughtSplitRatio)
        .put("thoughtRowHeightDp", settings.thoughtRowHeightDp)
        .put("mealButtonsUseIcons", settings.mealButtonsUseIcons)
        .put("userName", settings.userName)
        .put("homeWidgetBordersEnabled", settings.homeWidgetBordersEnabled)
        .put("mealButtonIcons", settings.mealButtonIcons.toJsonArray())
        .put("navItems", JSONArray().apply {
            settings.navItems.forEach { item ->
                put(
                    JSONObject()
                        .put("id", item.id.name)
                        .put("label", item.label)
                        .put("iconKey", item.iconKey)
                        .put("visible", item.visible),
                )
            }
        })
        .put("defaultPage", settings.defaultPage.name)
        .put("bottomNavShowLabels", settings.bottomNavShowLabels)
        .put("homeWidgets", settings.homeWidgets.toJsonArray())
        .put("homeWidgetTitles", settings.homeWidgetTitles.toJsonArray())

    private fun decodeSettings(json: JSONObject, version: Int): AppSettings {
        val defaults = AppSettings()
        val homeWidgets = migrateMealPhotosWidget(
            items = json.requiredArray("homeWidgets").requiredStringList("homeWidgets"),
            migrated = version >= 4,
        )
        val decodedTitles = json.requiredArray("homeWidgetTitles").requiredStringList("homeWidgetTitles")
        val homeWidgetTitles = if (version < 4 && "meal_photos" !in decodedTitles) {
            decodedTitles + "meal_photos"
        } else {
            decodedTitles
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
            diaryTreeUri = json.requiredNullableString("diaryTreeUri"),
            mediaTreeUri = json.requiredNullableString("mediaTreeUri"),
            fileNamePattern = json.requiredString("fileNamePattern").requireMaxLength("fileNamePattern", 1_024),
            markdownTemplate = json.requiredString("markdownTemplate")
                .requireMaxLength("markdownTemplate", MAX_SETTING_STRING_CHARS),
            imageNamePattern = json.requiredString("imageNamePattern").requireMaxLength("imageNamePattern", 1_024),
            imageMaxWidthDp = json.requiredCoercedInt("imageMaxWidthDp", 120, 2400),
            imageMaxHeightDp = json.requiredCoercedInt("imageMaxHeightDp", 120, 2400),
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
            navItems = decodeNavItems(json.requiredArray("navItems")),
            defaultPage = json.requiredEnum("defaultPage"),
            bottomNavShowLabels = json.requiredBoolean("bottomNavShowLabels"),
            homeWidgets = homeWidgets,
            homeWidgetTitles = homeWidgetTitles,
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
        require(json.length() == expectedCount) {
            "mealButtonIcons must contain exactly $expectedCount items"
        }
        return buildList(expectedCount) {
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

    private fun decodeNavItems(json: JSONArray): List<NavItemConfig> = buildList {
        require(json.length() <= NavItemId.entries.size) { "navItems contains too many items" }
        val ids = HashSet<NavItemId>(json.length())
        for (index in 0 until json.length()) {
            val item = json.requiredObject(index, "navItems")
            val id = item.requiredEnum<NavItemId>("id")
            require(ids.add(id)) { "Duplicate navigation item: $id" }
            add(
                NavItemConfig(
                    id = id,
                    label = item.requiredString("label").requireMaxLength("navItems[$index].label", 128),
                    iconKey = item.requiredString("iconKey").requireMaxLength("navItems[$index].iconKey", 128),
                    visible = item.requiredBoolean("visible"),
                ),
            )
        }
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
                    .putNullable("categoryId", thought.categoryId),
            )
        }
    }

    private fun decodeThoughts(
        json: JSONArray,
        includeCategoryId: Boolean,
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

    private fun encodePoems(poems: List<SavedPoemEntity>): JSONArray = JSONArray().apply {
        poems.forEach { poem ->
            put(
                JSONObject()
                    .put("id", poem.id)
                    .put("content", poem.content)
                    .put("source", poem.source)
                    .put("createdAt", poem.createdAt)
                    .put("updatedAt", poem.updatedAt),
            )
        }
    }

    private fun decodePoems(json: JSONArray): List<SavedPoemEntity> {
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
                    ),
                )
            }
        }
    }

    private fun validateEntityKeys(
        thoughts: List<FlashThoughtEntity>,
        categories: List<ThoughtCategoryEntity>,
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

    private fun requireWithinSizeLimit(json: String) {
        require(json.length <= MAX_JSON_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "Backup JSON exceeds the 10 MiB limit"
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
