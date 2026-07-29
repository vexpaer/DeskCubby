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
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.LauncherIcon
import com.deskcubby.app.data.model.MealPhotoFilterSettings
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.PoetryTextAlignment
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.model.ThoughtDisplayMode
import com.deskcubby.app.data.model.ThoughtReopenMode
import com.deskcubby.app.data.model.VisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            launcherIcon = LauncherIcon.DESK_CUBBY,
            backupTreeUri = "content://device-only-backup-folder",
            diaryTreeUri = "content://diaries",
            mediaTreeUri = "content://media",
            markdownTemplate = "# {title}\n\n正文",
            mealButtonsUseIcons = true,
            userName = "书桌主人",
            homeGreetings = listOf(
                HomeGreetingTemplate("{name}，开始", "Start, {name}"),
                HomeGreetingTemplate("看看今天", "Review today"),
            ),
            homeWidgetBordersEnabled = false,
            mealButtonIcons = listOf("🥐", "🍜", "🍹", "🍲", "🍓", "🍢"),
            mealImageCompressionEnabled = false,
            mealImageCompressionQuality = 65,
            thoughtReopenMode = ThoughtReopenMode.LAST_VISITED,
            thoughtDisplayMode = ThoughtDisplayMode.FULL,
            poetryFontUri = "content://com.example.fonts/document/poetry.otf",
            poetryFontSizeSp = 26f,
            poetryLineSpacing = 1.7f,
            poetryTextAlignment = PoetryTextAlignment.CENTER,
            poetryShowSource = false,
            poetryShowQuoteMark = false,
            poetrySevenCharacterWrapEnabled = true,
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
            usageTrackingEnabled = true,
            stepTrackingEnabled = true,
            morePageShowDescriptions = false,
            morePageOrder = AppSettings().morePageOrder.toMutableList().apply {
                remove(NavItemId.AI_CHAT)
                add(0, NavItemId.AI_CHAT)
            },
            navItems = AppSettings().navItems.map { item ->
                if (item.id == NavItemId.THOUGHT) {
                    item.copy(moreDescription = "随手记录，也可完整展开")
                } else {
                    item
                }
            },
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
        assertEquals(settings.homeGreetings, decoded.settings.homeGreetings)
        assertEquals(false, decoded.settings.homeWidgetBordersEnabled)
        assertEquals(listOf("🥐", "🍜", "🍹", "🍲", "🍓", "🍢"), decoded.settings.mealButtonIcons)
        assertEquals(false, decoded.settings.mealImageCompressionEnabled)
        assertEquals(65, decoded.settings.mealImageCompressionQuality)
        assertEquals(VisualStyle.ORGANIC_FUTURE, decoded.settings.visualStyle)
        assertEquals(settings.themeSecondaryColorsArgb, decoded.settings.themeSecondaryColorsArgb)
        assertEquals(settings.fontScale, decoded.settings.fontScale)
        assertEquals(BackupJsonCodec.FORMAT_VERSION, decoded.formatVersion)
        assertEquals(40L, decoded.exportedAt)
    }

    @Test
    fun versionSixteenUsesSafeDefaultsForVersionSeventeenPoetrySettings() {
        val current = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 16,
                    settings = AppSettings(
                        poetryFontUri = "content://com.example.fonts/document/poetry.ttf",
                        poetryFontSizeSp = 30f,
                        poetryLineSpacing = 1.8f,
                        poetryTextAlignment = PoetryTextAlignment.CENTER,
                        poetryShowSource = false,
                        poetryShowQuoteMark = false,
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )
        current.put("version", 16)
        current.getJSONObject("settings").apply {
            remove("poetryFontUri")
            remove("poetryFontSizeSp")
            remove("poetryLineSpacing")
            remove("poetryTextAlignment")
            remove("poetryShowSource")
            remove("poetryShowQuoteMark")
        }

        val decoded = BackupJsonCodec.decode(current.toString())
        val defaults = AppSettings()

        assertEquals(16, decoded.formatVersion)
        assertEquals(defaults.poetryFontUri, decoded.settings.poetryFontUri)
        assertEquals(defaults.poetryFontSizeSp, decoded.settings.poetryFontSizeSp)
        assertEquals(defaults.poetryLineSpacing, decoded.settings.poetryLineSpacing)
        assertEquals(defaults.poetryTextAlignment, decoded.settings.poetryTextAlignment)
        assertEquals(defaults.poetryShowSource, decoded.settings.poetryShowSource)
        assertEquals(defaults.poetryShowQuoteMark, decoded.settings.poetryShowQuoteMark)
    }

    @Test
    fun versionSeventeenRejectsInvalidPoetryDisplaySettings() {
        fun currentRoot() = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 17,
                    settings = AppSettings(),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )

        assertDecodeRejected(currentRoot().apply {
            getJSONObject("settings").put("poetryFontSizeSp", 100)
        })
        assertDecodeRejected(currentRoot().apply {
            getJSONObject("settings").put("poetryLineSpacing", 0.5)
        })
        assertDecodeRejected(currentRoot().apply {
            getJSONObject("settings").put("poetryTextAlignment", "DIAGONAL")
        })
        assertDecodeRejected(currentRoot().apply {
            getJSONObject("settings").put(
                "poetryFontUri",
                "content://" + "f".repeat(8_193),
            )
        })
    }

    @Test
    fun versionSeventeenUsesDefaultForSevenCharacterWrapping() {
        val current = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 18,
                    settings = AppSettings(poetrySevenCharacterWrapEnabled = true),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )
        current.put("version", 17)
        current.getJSONObject("settings").remove("poetrySevenCharacterWrapEnabled")

        val decoded = BackupJsonCodec.decode(current.toString())

        assertEquals(false, decoded.settings.poetrySevenCharacterWrapEnabled)
    }

    @Test
    fun currentVersionPreservesNavigationFilterAndCloudMetadataWithoutCredentials() {
        val webDav = CloudSyncConfig(
            id = "webdav",
            name = "Personal WebDAV",
            endpointUrl = "https://cloud.example.com/dav",
            remotePath = "DeskCubby/personal",
            webDavUsername = "alice",
            webDavPassword = "webdav-password-marker",
            s3AccessKey = "unused-access-key-marker",
            s3SecretKey = "unused-secret-key-marker",
            s3SessionToken = "unused-session-token-marker",
            selectedContents = setOf(
                CloudSyncContent.DIARIES,
                CloudSyncContent.MEDIA,
            ),
            direction = CloudSyncDirection.TWO_WAY,
        )
        val s3 = CloudSyncConfig(
            id = "s3",
            name = "Archive",
            enabled = false,
            serviceType = CloudSyncServiceType.S3_COMPATIBLE,
            endpointUrl = "https://s3.example.com",
            remotePath = "DeskCubby/archive",
            s3Bucket = "deskcubby-archive",
            s3Region = "cn-east-1",
            s3AccessKey = "s3-access-key-marker",
            s3SecretKey = "s3-secret-key-marker",
            s3SessionToken = "s3-session-token-marker",
            selectedContents = setOf(CloudSyncContent.JSON_BACKUP),
            direction = CloudSyncDirection.UPLOAD_ONLY,
        )
        val navItems = AppSettings().navItems.map { item ->
            when (item.id) {
                NavItemId.DIARY -> item.copy(visible = false, showInMore = true)
                NavItemId.BLOG -> item.copy(visible = true, showInMore = false)
                else -> item
            }
        }
        val filter = MealPhotoFilterSettings(
            enabled = true,
            brightness = 0.25f,
            contrast = 1.35f,
            saturation = 0.7f,
            warmth = -0.2f,
            tint = 0.15f,
        )
        val encoded = BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 13,
                settings = AppSettings(
                    cloudSyncEnabled = true,
                    cloudSyncConfigs = listOf(webDav, s3),
                    mealPhotoFilter = filter,
                    navItems = navItems,
                ),
                thoughts = emptyList(),
                favorites = emptyList(),
            ),
        )
        val encodedSettings = JSONObject(encoded).getJSONObject("settings")
        val encodedConfigs = encodedSettings.getJSONArray("cloudSyncConfigs")

        assertEquals(false, encodedSettings.getBoolean("cloudSyncEnabled"))
        for (index in 0 until encodedConfigs.length()) {
            val item = encodedConfigs.getJSONObject(index)
            assertFalse(item.has("webDavPassword"))
            assertFalse(item.has("s3AccessKey"))
            assertFalse(item.has("s3SecretKey"))
            assertFalse(item.has("s3SessionToken"))
        }
        listOf(
            "webdav-password-marker",
            "unused-access-key-marker",
            "unused-secret-key-marker",
            "unused-session-token-marker",
            "s3-access-key-marker",
            "s3-secret-key-marker",
            "s3-session-token-marker",
        ).forEach { credential ->
            assertFalse(encoded.contains(credential))
        }

        val decoded = BackupJsonCodec.decode(encoded)

        assertEquals(BackupJsonCodec.FORMAT_VERSION, decoded.formatVersion)
        assertEquals(false, decoded.settings.cloudSyncEnabled)
        assertEquals(
            listOf(
                webDav.copy(
                    webDavPassword = "",
                    s3AccessKey = "",
                    s3SecretKey = "",
                    s3SessionToken = "",
                ),
                s3.copy(
                    webDavPassword = "",
                    s3AccessKey = "",
                    s3SecretKey = "",
                    s3SessionToken = "",
                ),
            ),
            decoded.settings.cloudSyncConfigs,
        )
        assertEquals(filter, decoded.settings.mealPhotoFilter)
        assertEquals(navItems, decoded.settings.navItems)
    }

    @Test
    fun versionFourteenUsesSafeDefaultsForVersionFifteenSettings() {
        val current = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 14,
                    settings = AppSettings(
                        launcherIcon = LauncherIcon.MAGIC_BOOK,
                        usageTrackingEnabled = true,
                        stepTrackingEnabled = true,
                        morePageShowDescriptions = false,
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )
        current.put("version", 14)
        val settings = current.getJSONObject("settings")
        settings.remove("launcherIcon")
        settings.remove("usageTrackingEnabled")
        settings.remove("stepTrackingEnabled")
        settings.remove("morePageShowDescriptions")
        val navItems = settings.getJSONArray("navItems")
        for (index in 0 until navItems.length()) {
            navItems.getJSONObject(index).remove("moreDescription")
        }

        val decoded = BackupJsonCodec.decode(current.toString())

        assertEquals(14, decoded.formatVersion)
        assertEquals(LauncherIcon.CURRENT, decoded.settings.launcherIcon)
        assertFalse(decoded.settings.usageTrackingEnabled)
        assertFalse(decoded.settings.stepTrackingEnabled)
        assertEquals(true, decoded.settings.morePageShowDescriptions)
        decoded.settings.navItems.forEach { item ->
            assertEquals(item.id.defaultDescription, item.moreDescription)
        }
    }

    @Test
    fun versionFifteenUsesDefaultHomeGreetingsAndMigratesMoreOrderFromNavigation() {
        val legacyNavItems = AppSettings().navItems.toMutableList().apply {
            val moved = removeAt(indexOfFirst { it.id == NavItemId.AI_CHAT })
            add(1, moved)
        }
        val current = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 15,
                    settings = AppSettings(
                        homeGreetings = listOf(
                            HomeGreetingTemplate("自定义", "Custom"),
                        ),
                        navItems = legacyNavItems,
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )
        current.put("version", 15)
        current.getJSONObject("settings").apply {
            remove("homeGreetings")
            remove("morePageOrder")
        }

        val decoded = BackupJsonCodec.decode(current.toString())

        assertEquals(15, decoded.formatVersion)
        assertEquals(AppSettings().homeGreetings, decoded.settings.homeGreetings)
        assertEquals(
            legacyNavItems.map { it.id }.filter { id ->
                id != NavItemId.HOME &&
                    id != NavItemId.MORE &&
                    id != NavItemId.SETTINGS
            },
            decoded.settings.morePageOrder,
        )
    }

    @Test
    fun versionTwelveUsesSafeDefaultsForVersionThirteenSettings() {
        val navItems = AppSettings().navItems.map { item ->
            when (item.id) {
                NavItemId.BLOG -> item.copy(visible = false, showInMore = false)
                NavItemId.DIARY -> item.copy(visible = false, showInMore = true)
                else -> item
            }
        }
        val root = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 12,
                    settings = AppSettings(
                        cloudSyncEnabled = true,
                        cloudSyncConfigs = listOf(
                            CloudSyncConfig(
                                id = "legacy",
                                name = "Legacy cloud",
                                endpointUrl = "https://cloud.example.com/dav",
                            ),
                        ),
                        mealPhotoFilter = MealPhotoFilterSettings(
                            enabled = true,
                            brightness = 0.5f,
                            contrast = 1.5f,
                            saturation = 0.5f,
                            warmth = 0.25f,
                            tint = -0.25f,
                        ),
                        navItems = navItems,
                    ),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        ).apply {
            put("version", 12)
            getJSONObject("settings").removeVersionThirteenSettings()
        }

        val decoded = BackupJsonCodec.decode(root.toString())

        assertEquals(12, decoded.formatVersion)
        assertEquals(false, decoded.settings.cloudSyncEnabled)
        assertEquals(emptyList<CloudSyncConfig>(), decoded.settings.cloudSyncConfigs)
        assertEquals(MealPhotoFilterSettings(), decoded.settings.mealPhotoFilter)
        assertEquals(
            true,
            decoded.settings.navItems.single { it.id == NavItemId.BLOG }.showInMore,
        )
        assertEquals(
            false,
            decoded.settings.navItems.single { it.id == NavItemId.DIARY }.showInMore,
        )
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
    fun versionThirteenRequiresBoundedStringApiKey() {
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
    fun rejectsOversizedOrBlankHomeGreetings() {
        fun currentJson(): JSONObject = JSONObject(
            BackupJsonCodec.encode(
                AppBackup(
                    exportedAt = 16,
                    settings = AppSettings(),
                    thoughts = emptyList(),
                    favorites = emptyList(),
                ),
            ),
        )

        val oversized = currentJson().apply {
            getJSONObject("settings").put(
                "homeGreetings",
                JSONArray().put(
                    JSONObject()
                        .put("chinese", "问".repeat(41))
                        .put("english", "Greeting"),
                ),
            )
        }
        assertDecodeRejected(oversized)

        val blank = currentJson().apply {
            getJSONObject("settings").put(
                "homeGreetings",
                JSONArray().put(
                    JSONObject()
                        .put("chinese", " ")
                        .put("english", ""),
                ),
            )
        }
        assertDecodeRejected(blank)
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
    fun currentVersionRejectsDuplicateOrNonOrderableMorePageIds() {
        val duplicate = validEmptyBackupJson().apply {
            getJSONObject("settings").put(
                "morePageOrder",
                JSONArray()
                    .put(NavItemId.THOUGHT.name)
                    .put(NavItemId.THOUGHT.name),
            )
        }
        assertDecodeRejected(duplicate)

        val reserved = validEmptyBackupJson().apply {
            getJSONObject("settings").put(
                "morePageOrder",
                JSONArray().put(NavItemId.HOME.name),
            )
        }
        assertDecodeRejected(reserved)
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

    private fun JSONObject.removeVersionThirteenSettings() {
        remove("cloudSyncEnabled")
        remove("cloudSyncConfigs")
        remove("mealPhotoFilter")
        val navigation = getJSONArray("navItems")
        for (index in navigation.length() - 1 downTo 0) {
            val item = navigation.getJSONObject(index)
            if (item.getString("id") == NavItemId.MORE.name) {
                navigation.remove(index)
            } else {
                item.remove("showInMore")
            }
        }
    }

    private fun assertVersionFiveSettingsUseDefaults(settings: AppSettings) {
        val defaults = AppSettings()
        assertEquals(defaults.userName, settings.userName)
        assertEquals(defaults.homeWidgetBordersEnabled, settings.homeWidgetBordersEnabled)
        assertEquals(defaults.mealButtonIcons, settings.mealButtonIcons)
    }
}
