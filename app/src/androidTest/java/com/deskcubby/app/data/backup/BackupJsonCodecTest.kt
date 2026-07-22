package com.deskcubby.app.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.ThoughtReopenMode
import com.deskcubby.app.data.model.VisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class BackupJsonCodecTest {
    @Test
    fun roundTripPreservesContentButNotDeviceBackupFolder() {
        val settings = AppSettings(
            visualStyle = VisualStyle.ORGANIC_FUTURE,
            themeSecondaryColorsArgb = listOf(
                0xFF7B5C3E.toInt(),
                0xFF4F6D7A.toInt(),
                0xFFA26A4A.toInt(),
            ),
            fontScale = 1.15f,
            backupTreeUri = "content://device-only-backup-folder",
            diaryTreeUri = "content://diaries",
            mediaTreeUri = "content://media",
            markdownTemplate = "# {title}\n\n正文",
            mealButtonsUseIcons = true,
            userName = "书桌主人",
            homeWidgetBordersEnabled = false,
            mealButtonIcons = listOf("🥐", "🍜", "🍹", "🍲", "🍓", "🍢"),
            mealImageCompressionEnabled = false,
            mealImageCompressionQuality = 65,
            thoughtReopenMode = ThoughtReopenMode.LAST_VISITED,
            thoughtDisplayMode = ThoughtDisplayMode.FULL,
            mealCalendarImageMaxHeightDp = 188,
            mealCalendarShowCaptions = false,
            dailyEventTemplates = listOf(
                DailyEventTemplate("exercise", "俯卧撑", "个", "次"),
            ),
            rssSubscriptions = listOf(
                RssSubscription("feed", "示例", "https://example.com/feed.xml"),
            ),
            rssMaxItemsPerFeed = 80,
            rssShowSummaries = false,
            aiEndpointUrl = "https://example.com/v1/chat/completions",
            aiModel = "example-model",
            aiSystemPrompt = "测试系统提示词",
            aiTemperature = 1.2f,
            aiConfigs = listOf(
                AiModelConfig("text-1", "文字一", AiModelType.TEXT, "https://example.com/text", "text-model",
                    systemPrompt = "配置自己的系统提示词", apiKey = "sk-text-plain"),
                AiModelConfig("image-1", "图片一", AiModelType.IMAGE, "https://example.com/image", "image-model",
                    apiKey = "sk-image-plain"),
            ),
            aiChatConfigId = "text-1",
            calorieEstimationEnabled = true,
            calorieTextConfigId = "text-1",
            calorieImageConfigId = "image-1",
            homeWidgets = emptyList(),
            homeWidgetTitles = emptyList(),
        )
        val thought = FlashThoughtEntity(
            id = 7,
            content = "需要备份的小巧思\r\n\"emoji 😀\"",
            createdAt = 10,
            updatedAt = 20,
            pinned = true,
            deletedAt = null,
            sortOrder = 3,
            categoryId = 5,
        )
        val category = ThoughtCategoryEntity(
            id = 5,
            name = "灵感",
            colorArgb = 0xFF6750A4.toInt(),
            sortOrder = 0,
            createdAt = 8,
            updatedAt = 9,
        )
        val favorite = BrowserRecordEntity(
            url = "https://example.com",
            title = "Example",
            lastVisitedAt = 30,
            visitCount = 4,
            favorite = true,
        )
        val dateRecord = DateRecordEntity(
            id = 9,
            name = "第一次旅行 😀",
            icon = "flight",
            dateIso = "2024-02-29",
            createdAt = 31,
            updatedAt = 32,
        )
        val poem = SavedPoemEntity(
            id = 11,
            content = "海上生明月，天涯共此时。",
            source = "张九龄《望月怀远》",
            createdAt = 33,
            updatedAt = 34,
        )

        val decoded = BackupJsonCodec.decode(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 40,
                    settings = settings,
                    thoughts = listOf(thought),
                    favorites = listOf(favorite),
                    dateRecords = listOf(dateRecord),
                    categories = listOf(category),
                    poems = listOf(poem),
                ),
            ),
        )

        assertEquals(settings.copy(backupTreeUri = null), decoded.settings)
        assertNull(decoded.settings.backupTreeUri)
        assertEquals(listOf(thought), decoded.thoughts)
        assertEquals(listOf(category), decoded.categories)
        assertEquals(listOf(favorite), decoded.favorites)
        assertEquals(listOf(dateRecord), decoded.dateRecords)
        assertEquals(listOf(poem), decoded.poems)
        assertEquals(true, decoded.settings.mealButtonsUseIcons)
        assertEquals("书桌主人", decoded.settings.userName)
        assertEquals(false, decoded.settings.homeWidgetBordersEnabled)
        assertEquals(listOf("🥐", "🍜", "🍹", "🍲", "🍓", "🍢"), decoded.settings.mealButtonIcons)
        assertEquals(false, decoded.settings.mealImageCompressionEnabled)
        assertEquals(65, decoded.settings.mealImageCompressionQuality)
        assertEquals(VisualStyle.ORGANIC_FUTURE, decoded.settings.visualStyle)
        assertEquals(settings.themeSecondaryColorsArgb, decoded.settings.themeSecondaryColorsArgb)
        assertEquals(settings.fontScale, decoded.settings.fontScale)
        assertEquals(12, decoded.formatVersion)
        assertEquals(40L, decoded.exportedAt)
    }

    @Test
    fun versionElevenAiConfigurationsImportWithoutApiKeys() {
        val root = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 1,
                    settings = AppSettings(
                        aiConfigs = listOf(
                            AiModelConfig(
                                id = "text",
                                name = "文字",
                                type = AiModelType.TEXT,
                                endpointUrl = "https://example.com/v1/chat/completions",
                                model = "model",
                                apiKey = "must-not-be-read-from-v11",
                            ),
                        ),
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        ).apply {
            put("version", 11)
            getJSONObject("settings").getJSONArray("aiConfigs").getJSONObject(0).remove("apiKey")
        }

        val decoded = BackupJsonCodec.decode(root.toString())

        assertEquals(11, decoded.formatVersion)
        assertEquals("", decoded.settings.aiConfigs.single().apiKey)
    }

    @Test
    fun versionTwelveRequiresBoundedStringApiKey() {
        fun currentRoot() = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 1,
                    settings = AppSettings(
                        aiConfigs = listOf(
                            AiModelConfig(
                                id = "text",
                                name = "文字",
                                type = AiModelType.TEXT,
                                endpointUrl = "https://example.com/v1/chat/completions",
                                model = "model",
                                apiKey = "plain-key",
                            ),
                        ),
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )

        assertDecodeRejected(currentRoot().apply {
            getJSONObject("settings").getJSONArray("aiConfigs").getJSONObject(0).remove("apiKey")
        })
        assertDecodeRejected(currentRoot().apply {
            getJSONObject("settings").getJSONArray("aiConfigs").getJSONObject(0).put("apiKey", 123)
        })
        assertDecodeRejected(currentRoot().apply {
            getJSONObject("settings").getJSONArray("aiConfigs").getJSONObject(0)
                .put("apiKey", "k".repeat(8_193))
        })
    }

    @Test
    fun roundTripPreservesNonEmptyHomeLists() {
        val settings = AppSettings(
            homeWidgets = listOf("today", "recent_thought"),
            homeWidgetTitles = listOf("calendar", "website"),
        )

        val decoded = BackupJsonCodec.decode(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 1,
                    settings = settings,
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )

        assertEquals(settings.homeWidgets, decoded.settings.homeWidgets)
        assertEquals(settings.homeWidgetTitles, decoded.settings.homeWidgetTitles)
    }

    @Test
    fun rejectsUnsupportedFormatVersionBeforeImport() {
        val valid = BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 1,
                settings = AppSettings(),
                thoughts = emptyList(),
                favorites = emptyList(),
            ),
        )
        val unsupported = JSONObject(valid).apply {
            put("version", BackupJsonCodec.FORMAT_VERSION + 1)
        }.toString()

        try {
            BackupJsonCodec.decode(unsupported)
            fail("Expected an unsupported-version error")
        } catch (expected: IllegalArgumentException) {
            // Fully parsed and rejected before any repository mutation.
        }
    }

    @Test
    fun importsVersionOneBackupWithoutDateRecords() {
        val category = testCategory(id = 1, name = "旧分类")
        val thought = testThought(id = 2, categoryId = category.id)
        val current = BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 5,
                settings = AppSettings(),
                thoughts = listOf(thought),
                favorites = emptyList(),
                dateRecords = listOf(
                    DateRecordEntity(
                        id = 1,
                        name = "不会进入旧格式",
                        icon = "event",
                        dateIso = "2030-01-01",
                        createdAt = 1,
                        updatedAt = 1,
                    ),
                ),
                categories = listOf(category),
            ),
        )
        val versionOne = JSONObject(current).apply {
            put("version", 1)
            remove("dateRecords")
            remove("categories")
            remove("poems")
            getJSONObject("settings").apply {
                remove("mealButtonsUseIcons")
                removeVersionFiveSettings()
            }
        }.toString()

        val decoded = BackupJsonCodec.decode(versionOne)

        assertEquals(1, decoded.formatVersion)
        assertEquals(emptyList<DateRecordEntity>(), decoded.dateRecords)
        assertEquals(emptyList<ThoughtCategoryEntity>(), decoded.categories)
        assertEquals(emptyList<SavedPoemEntity>(), decoded.poems)
        assertEquals(false, decoded.settings.mealButtonsUseIcons)
        assertVersionFiveSettingsUseDefaults(decoded.settings)
        assertNull(decoded.thoughts.single().categoryId)
    }

    @Test
    fun importsVersionTwoBackupWithoutCategories() {
        val category = testCategory(id = 1, name = "不会进入旧格式")
        val thought = testThought(id = 2, categoryId = category.id)
        val versionTwo = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 5,
                    settings = AppSettings(),
                    thoughts = listOf(thought),
                    favorites = emptyList(),
                    categories = listOf(category),
                ),
            ),
        ).apply {
            put("version", 2)
            remove("categories")
            remove("poems")
            getJSONObject("settings").apply {
                remove("mealButtonsUseIcons")
                removeVersionFiveSettings()
            }
        }.toString()

        val decoded = BackupJsonCodec.decode(versionTwo)

        assertEquals(2, decoded.formatVersion)
        assertEquals(emptyList<ThoughtCategoryEntity>(), decoded.categories)
        assertEquals(emptyList<SavedPoemEntity>(), decoded.poems)
        assertEquals(false, decoded.settings.mealButtonsUseIcons)
        assertVersionFiveSettingsUseDefaults(decoded.settings)
        assertNull(decoded.thoughts.single().categoryId)
    }

    @Test
    fun importsVersionThreeBackupWithoutPoemsOrMealButtonStyle() {
        val versionThree = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 5,
                    settings = AppSettings(mealButtonsUseIcons = true),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                    poems = listOf(testPoem(id = 1)),
                ),
            ),
        ).apply {
            put("version", 3)
            remove("poems")
            getJSONObject("settings").apply {
                remove("mealButtonsUseIcons")
                removeVersionFiveSettings()
                put("homeWidgets", JSONArray(listOf("today", "quick_input", "website")))
                put("homeWidgetTitles", JSONArray(listOf("today", "quick_input")))
            }
        }.toString()

        val decoded = BackupJsonCodec.decode(versionThree)

        assertEquals(3, decoded.formatVersion)
        assertEquals(emptyList<SavedPoemEntity>(), decoded.poems)
        assertEquals(false, decoded.settings.mealButtonsUseIcons)
        assertVersionFiveSettingsUseDefaults(decoded.settings)
        assertEquals(
            listOf("today", "quick_input", "meal_photos", "website"),
            decoded.settings.homeWidgets,
        )
        assertEquals(listOf("today", "quick_input", "meal_photos"), decoded.settings.homeWidgetTitles)
    }

    @Test
    fun importsVersionFourBackupWithDefaultsForVersionFiveSettings() {
        val versionFour = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 5,
                    settings = AppSettings(
                        mealButtonsUseIcons = true,
                        userName = "不会进入旧格式",
                        homeWidgetBordersEnabled = false,
                        mealButtonIcons = listOf("1", "2", "3", "4", "5"),
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        ).apply {
            put("version", 4)
            getJSONObject("settings").removeVersionFiveSettings()
        }.toString()

        val decoded = BackupJsonCodec.decode(versionFour)

        assertEquals(4, decoded.formatVersion)
        assertEquals(true, decoded.settings.mealButtonsUseIcons)
        assertVersionFiveSettingsUseDefaults(decoded.settings)
    }

    @Test
    fun importsVersionFiveBackupWithDefaultsForVersionSixSettings() {
        val versionFive = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 5,
                    settings = AppSettings(
                        userName = "旧版用户",
                        mealImageCompressionEnabled = false,
                        mealImageCompressionQuality = 45,
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        ).apply {
            put("version", 5)
            getJSONObject("settings").removeVersionSixSettings()
        }.toString()

        val decoded = BackupJsonCodec.decode(versionFive)
        val defaults = AppSettings()

        assertEquals(5, decoded.formatVersion)
        assertEquals("旧版用户", decoded.settings.userName)
        assertEquals(defaults.mealImageCompressionEnabled, decoded.settings.mealImageCompressionEnabled)
        assertEquals(defaults.mealImageCompressionQuality, decoded.settings.mealImageCompressionQuality)
    }

    @Test
    fun importsVersionSixBackupWithLegacyVisualStyle() {
        val versionSix = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 6,
                    settings = AppSettings(visualStyle = VisualStyle.LIQUID_GLASS),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        ).apply {
            put("version", 6)
        }.toString()

        val decoded = BackupJsonCodec.decode(versionSix)

        assertEquals(6, decoded.formatVersion)
        assertEquals(VisualStyle.LIQUID_GLASS, decoded.settings.visualStyle)
    }

    @Test
    fun importsVersionSevenBackupWithDefaultsForVersionEightSettings() {
        val versionSeven = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 7,
                    settings = AppSettings(
                        visualStyle = VisualStyle.ORGANIC_FUTURE,
                        themeSecondaryColorsArgb = listOf(1, 2),
                        fontScale = 1.3f,
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        ).apply {
            put("version", 7)
            getJSONObject("settings").removeVersionEightSettings()
        }.toString()

        val decoded = BackupJsonCodec.decode(versionSeven)
        val defaults = AppSettings()

        assertEquals(7, decoded.formatVersion)
        assertEquals(VisualStyle.ORGANIC_FUTURE, decoded.settings.visualStyle)
        assertEquals(defaults.themeSecondaryColorsArgb, decoded.settings.themeSecondaryColorsArgb)
        assertEquals(defaults.fontScale, decoded.settings.fontScale)
    }

    @Test
    fun rejectsInvalidVersionEightThemeSecondaryColors() {
        val valid = validEmptyBackupJson()

        assertDecodeRejected(JSONObject(valid.toString()).apply {
            getJSONObject("settings").put("themeSecondaryColorsArgb", JSONArray(listOf(1)))
        })
        assertDecodeRejected(JSONObject(valid.toString()).apply {
            getJSONObject("settings").put(
                "themeSecondaryColorsArgb",
                JSONArray(listOf(1, 2, 3, 4, 5, 6)),
            )
        })
        assertDecodeRejected(JSONObject(valid.toString()).apply {
            getJSONObject("settings").put("themeSecondaryColorsArgb", JSONArray(listOf(1, 2.5)))
        })
        assertDecodeRejected(JSONObject(valid.toString()).apply {
            getJSONObject("settings").put("themeSecondaryColorsArgb", JSONArray(listOf(1, "2")))
        })
    }

    @Test
    fun rejectsInvalidVersionEightFontScale() {
        val valid = validEmptyBackupJson()

        assertDecodeRejected(JSONObject(valid.toString()).apply {
            getJSONObject("settings").put("fontScale", 0.79)
        })
        assertDecodeRejected(JSONObject(valid.toString()).apply {
            getJSONObject("settings").put("fontScale", 1.31)
        })
        assertDecodeRejected(JSONObject(valid.toString()).apply {
            getJSONObject("settings").put("fontScale", "1.0")
        })
    }

    @Test
    fun rejectsDuplicateCategoryIdsAndNamesCaseInsensitively() {
        val duplicateId = validCategorizedBackupJson().apply {
            getJSONArray("categories").getJSONObject(1).put("id", 1)
        }
        assertDecodeRejected(duplicateId)

        val duplicateName = validCategorizedBackupJson().apply {
            getJSONArray("categories").getJSONObject(1).put("name", "wOrK")
        }
        assertDecodeRejected(duplicateName)
    }

    @Test
    fun rejectsThoughtsReferencingMissingCategories() {
        val danglingReference = validCategorizedBackupJson().apply {
            getJSONArray("thoughts").getJSONObject(0).put("categoryId", 999)
        }

        assertDecodeRejected(danglingReference)
    }

    @Test
    fun rejectsInvalidDateRecordDates() {
        val invalid = DateRecordEntity(
            id = 1,
            name = "不存在的日期",
            icon = "event",
            dateIso = "2025-02-29",
            createdAt = 1,
            updatedAt = 1,
        )

        try {
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 1,
                    settings = AppSettings(),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                    dateRecords = listOf(invalid),
                ),
            )
            fail("Expected an invalid-date error")
        } catch (expected: IllegalArgumentException) {
            // Invalid calendar dates never reach the database.
        }
    }

    @Test
    fun rejectsInvalidPoemIdsContentSourcesAndTimestamps() {
        val duplicateId = validPoemBackupJson().apply {
            getJSONArray("poems").getJSONObject(1).put("id", 1)
        }
        assertDecodeRejected(duplicateId)

        val blankContent = validPoemBackupJson().apply {
            getJSONArray("poems").getJSONObject(0).put("content", "  \n")
        }
        assertDecodeRejected(blankContent)

        val oversizedSource = validPoemBackupJson().apply {
            getJSONArray("poems").getJSONObject(0).put("source", "作".repeat(4_097))
        }
        assertDecodeRejected(oversizedSource)

        val reversedTimestamps = validPoemBackupJson().apply {
            getJSONArray("poems").getJSONObject(0).put("updatedAt", 0)
        }
        assertDecodeRejected(reversedTimestamps)
    }

    @Test
    fun rejectsUnsafeBookmarkSchemes() {
        val unsafe = BrowserRecordEntity(
            url = "javascript://alert(1)",
            title = "unsafe",
            lastVisitedAt = 1,
            favorite = true,
        )

        try {
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 1,
                    settings = AppSettings(),
                    thoughts = emptyList(),
                    favorites = listOf(unsafe),
                ),
            )
            fail("Expected an unsafe-URL error")
        } catch (expected: IllegalArgumentException) {
            // Unsafe schemes never reach the browser database.
        }
    }

    @Test
    fun rejectsIntegersBeyondLongRange() {
        val valid = BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 1,
                settings = AppSettings(),
                thoughts = emptyList(),
                favorites = emptyList(),
            ),
        )
        val overflow = valid.replace("\"exportedAt\": 1", "\"exportedAt\": 9223372036854775808")

        try {
            BackupJsonCodec.decode(overflow)
            fail("Expected a 64-bit integer range error")
        } catch (expected: IllegalArgumentException) {
            // Prevents a parsed Double from saturating to Long.MAX_VALUE.
        }
    }

    private fun validCategorizedBackupJson(): JSONObject = JSONObject(
        BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 1,
                settings = AppSettings(),
                thoughts = listOf(testThought(id = 1, categoryId = 1)),
                favorites = emptyList(),
                categories = listOf(
                    testCategory(id = 1, name = "Work"),
                    testCategory(id = 2, name = "Home"),
                ),
            ),
        ),
    )

    private fun validPoemBackupJson(): JSONObject = JSONObject(
        BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 1,
                settings = AppSettings(),
                thoughts = emptyList(),
                favorites = emptyList(),
                poems = listOf(testPoem(id = 1), testPoem(id = 2)),
            ),
        ),
    )

    private fun validEmptyBackupJson(): JSONObject = JSONObject(
        BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 1,
                settings = AppSettings(),
                thoughts = emptyList(),
                favorites = emptyList(),
            ),
        ),
    )

    private fun testCategory(id: Long, name: String): ThoughtCategoryEntity = ThoughtCategoryEntity(
        id = id,
        name = name,
        colorArgb = 0xFF6750A4.toInt(),
        sortOrder = id,
        createdAt = id,
        updatedAt = id,
    )

    private fun testThought(id: Long, categoryId: Long?): FlashThoughtEntity = FlashThoughtEntity(
        id = id,
        content = "小巧思 $id",
        createdAt = id,
        updatedAt = id,
        sortOrder = id,
        categoryId = categoryId,
    )

    private fun testPoem(id: Long): SavedPoemEntity = SavedPoemEntity(
        id = id,
        content = "诗词 $id",
        source = "出处 $id",
        createdAt = id,
        updatedAt = id,
    )

    private fun assertDecodeRejected(json: JSONObject) {
        try {
            BackupJsonCodec.decode(json.toString())
            fail("Expected an invalid-backup error")
        } catch (expected: IllegalArgumentException) {
            // Invalid data never reaches Room's import transaction.
        }
    }

    private fun JSONObject.removeVersionFiveSettings() {
        remove("userName")
        remove("homeWidgetBordersEnabled")
        remove("mealButtonIcons")
        removeVersionSixSettings()
    }

    private fun JSONObject.removeVersionSixSettings() {
        remove("mealImageCompressionEnabled")
        remove("mealImageCompressionQuality")
    }

    private fun JSONObject.removeVersionEightSettings() {
        remove("themeSecondaryColorsArgb")
        remove("fontScale")
    }

    private fun assertVersionFiveSettingsUseDefaults(settings: AppSettings) {
        val defaults = AppSettings()
        assertEquals(defaults.userName, settings.userName)
        assertEquals(defaults.homeWidgetBordersEnabled, settings.homeWidgetBordersEnabled)
        assertEquals(defaults.mealButtonIcons, settings.mealButtonIcons)
    }
}
