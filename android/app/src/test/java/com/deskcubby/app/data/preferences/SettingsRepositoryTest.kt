package com.deskcubby.app.data.preferences

import com.deskcubby.app.data.model.AiModelConfig
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncServiceType
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.HomeGreetingTemplate
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.DEFAULT_THEME_SECONDARY_COLORS_ARGB
import com.deskcubby.app.data.model.DEFAULT_CALORIE_TEXT_PROMPT
import com.deskcubby.app.data.model.DEFAULT_CALORIE_VISION_PROMPT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun legacyDefaultCaloriePromptsUpgradeButCustomPromptsStayUntouched() {
        assertEquals(
            DEFAULT_CALORIE_VISION_PROMPT,
            normalizeCalorieVisionPrompt(LEGACY_DEFAULT_CALORIE_VISION_PROMPT),
        )
        assertEquals(
            DEFAULT_CALORIE_TEXT_PROMPT,
            normalizeCalorieTextPrompt(LEGACY_DEFAULT_CALORIE_TEXT_PROMPT),
        )
        assertEquals("custom vision", normalizeCalorieVisionPrompt("custom vision"))
        assertEquals("custom text", normalizeCalorieTextPrompt("custom text"))
    }

    @Test
    fun resolveAiConfigIdHonorsRequestedTypeAndMigratesEnabledConfig() {
        val text = AiModelConfig("text", "文字", AiModelType.TEXT, "https://example.com", "t", enabled = false)
        val image = AiModelConfig("image", "图片", AiModelType.IMAGE, "https://example.com", "i", enabled = true)
        assertEquals("text", resolveAiConfigId(listOf(text, image), "text", AiModelType.TEXT))
        assertEquals(null, resolveAiConfigId(listOf(text, image), "image", AiModelType.TEXT))
        assertEquals("image", resolveAiConfigId(listOf(text, image), null, AiModelType.IMAGE))
        assertEquals("text", resolveAiConfigId(listOf(text, image), null, AiModelType.TEXT, fallbackToAny = true))
    }
    @Test
    fun normalizeUrlAddsHttpsWhenSchemeMissing() {
        assertEquals("https://example.com/path", SettingsRepository.normalizeUrl(" example.com/path "))
    }

    @Test
    fun normalizeUrlPreservesExplicitScheme() {
        assertEquals("http://192.168.1.2", SettingsRepository.normalizeUrl("http://192.168.1.2"))
    }

    @Test
    fun normalizeUrlUsesBlankPageForEmptyInput() {
        assertEquals("about:blank", SettingsRepository.normalizeUrl("   "))
    }

    @Test
    fun normalizeUrlPreservesBlankPage() {
        assertEquals("about:blank", SettingsRepository.normalizeUrl("about:blank"))
    }

    @Test
    fun normalizeNavPreservesExistingRelativeOrderAndFirstDuplicate() {
        val firstThought = NavItemConfig(
            id = NavItemId.THOUGHT,
            label = "自定义小巧思",
            iconKey = "custom",
            visible = false,
        )
        val items = listOf(
            firstThought,
            NavItemConfig(NavItemId.HOME),
            NavItemConfig(NavItemId.THOUGHT, label = "重复项"),
            NavItemConfig(NavItemId.SETTINGS),
        )

        val normalized = normalizeNavItems(items)

        assertEquals(firstThought, normalized.first())
        assertEquals(1, normalized.count { it.id == NavItemId.THOUGHT })
        assertTrue(
            normalized.indexOfFirst { it.id == NavItemId.THOUGHT } <
                normalized.indexOfFirst { it.id == NavItemId.HOME },
        )
    }

    @Test
    fun normalizeNavInsertsMissingPagesBeforeSettingsWhenSettingsIsLast() {
        val existingIds = listOf(NavItemId.THOUGHT, NavItemId.HOME, NavItemId.SETTINGS)
        val expectedMissing = NavItemId.entries.filter { id ->
            id != NavItemId.SETTINGS && id !in existingIds
        }

        val normalized = normalizeNavItems(existingIds.map(::NavItemConfig))

        assertEquals(
            listOf(NavItemId.THOUGHT, NavItemId.HOME) + expectedMissing + NavItemId.SETTINGS,
            normalized.map(NavItemConfig::id),
        )
    }

    @Test
    fun normalizeNavAppendsMissingPagesWhenSettingsIsNotLast() {
        val existingIds = listOf(NavItemId.SETTINGS, NavItemId.THOUGHT, NavItemId.HOME)
        val expectedMissing = NavItemId.entries.filter { id ->
            id != NavItemId.SETTINGS && id !in existingIds
        }

        val normalized = normalizeNavItems(existingIds.map(::NavItemConfig))

        assertEquals(existingIds + expectedMissing, normalized.map(NavItemConfig::id))
        assertTrue(normalized.first { it.id == NavItemId.SETTINGS }.visible)
    }

    @Test
    fun normalizeNavAddsVisibleSettingsLastWhenSettingsIsMissing() {
        val existingIds = listOf(NavItemId.THOUGHT, NavItemId.HOME)
        val expectedMissing = NavItemId.entries.filter { id ->
            id != NavItemId.SETTINGS && id !in existingIds
        }

        val normalized = normalizeNavItems(existingIds.map(::NavItemConfig))

        assertEquals(
            existingIds + expectedMissing + NavItemId.SETTINGS,
            normalized.map(NavItemConfig::id),
        )
        assertEquals(NavItemId.SETTINGS, normalized.last().id)
        assertTrue(normalized.last().visible)
    }

    @Test
    fun normalizeMoreDescriptionCollapsesWhitespaceAndLimitsUnicodeCodePoints() {
        assertEquals(
            "日记与 小巧思",
            normalizeMoreDescription("  日记与\n\t小巧思  "),
        )
        val emoji = "😀".repeat(MAX_MORE_DESCRIPTION_CODE_POINTS + 1)
        assertEquals(
            "😀".repeat(MAX_MORE_DESCRIPTION_CODE_POINTS),
            normalizeMoreDescription(emoji),
        )
    }

    @Test
    fun normalizeCloudSyncConfigsTrimsMetadataNormalizesPathAndKeepsFirstId() {
        val first = CloudSyncConfig(
            id = " primary ",
            name = " Primary\nCloud ",
            endpointUrl = " https://cloud.example.com/dav ",
            remotePath = " /DeskCubby//photos/ ",
            userAgent = " Custom\nAgent ",
            webDavUsername = " alice ",
            s3Bucket = " archive-bucket ",
            s3Region = " cn-east-1 ",
            selectedContents = setOf(
                CloudSyncContent.DIARIES,
                CloudSyncContent.MEDIA,
            ),
        )
        val duplicate = first.copy(
            id = "primary",
            name = "Duplicate",
        )

        val normalized = normalizeCloudSyncConfigs(listOf(first, duplicate))

        assertEquals(1, normalized.size)
        assertEquals("primary", normalized.single().id)
        assertEquals("Primary Cloud", normalized.single().name)
        assertEquals("https://cloud.example.com/dav", normalized.single().endpointUrl)
        assertEquals("DeskCubby/photos", normalized.single().remotePath)
        assertEquals("Custom Agent", normalized.single().userAgent)
        assertEquals("alice", normalized.single().webDavUsername)
        assertEquals("archive-bucket", normalized.single().s3Bucket)
        assertEquals("cn-east-1", normalized.single().s3Region)
        assertEquals(first.selectedContents, normalized.single().selectedContents)
    }

    @Test
    fun normalizeCloudSyncConfigsStripsWebDavSecretAndKeepsS3Credentials() {
        val normalized = normalizeCloudSyncConfigs(
            listOf(
                CloudSyncConfig(
                    id = "private",
                    name = "Private cloud",
                    endpointUrl = "https://cloud.example.com/dav",
                    webDavUsername = "alice",
                    webDavPassword = "webdav-password",
                    s3AccessKey = "s3-access-key",
                    s3SecretKey = "s3-secret-key",
                    s3SessionToken = "s3-session-token",
                ),
            ),
        ).single()

        assertEquals("alice", normalized.webDavUsername)
        assertEquals("", normalized.webDavPassword)
        assertEquals("s3-access-key", normalized.s3AccessKey)
        assertEquals("s3-secret-key", normalized.s3SecretKey)
        assertEquals("s3-session-token", normalized.s3SessionToken)
    }

    @Test
    fun normalizeCloudSyncConfigsAddsS3SchemeFromTlsPreference() {
        val tls = CloudSyncConfig(
            id = "tls",
            name = "CSTCloud TLS",
            serviceType = CloudSyncServiceType.S3_COMPATIBLE,
            endpointUrl = "obss3.cstcloud.cn",
            s3Bucket = "archive",
            s3AccessKey = "access",
            s3SecretKey = "secret",
        )
        val localHttp = tls.copy(
            id = "http",
            name = "Local S3",
            endpointUrl = "192.168.1.2:9000",
            allowInsecureHttp = true,
        )

        val normalized = normalizeCloudSyncConfigs(listOf(tls, localHttp))

        assertEquals("https://obss3.cstcloud.cn", normalized[0].endpointUrl)
        assertEquals("http://192.168.1.2:9000", normalized[1].endpointUrl)
    }

    @Test
    fun s3CredentialsAreReusedOnlyForTheSameEndpointBucketAndRegion() {
        val local = CloudSyncConfig(
            id = "local",
            name = "Local",
            endpointUrl = "https://obss3.cstcloud.cn/",
            s3Bucket = "archive",
            s3Region = "cn-east-1",
        )

        assertEquals(
            true,
            hasSameS3CredentialScope(
                local,
                local.copy(endpointUrl = "https://obss3.cstcloud.cn"),
            ),
        )
        assertEquals(
            false,
            hasSameS3CredentialScope(local, local.copy(s3Bucket = "other")),
        )
        assertEquals(
            false,
            hasSameS3CredentialScope(local, local.copy(endpointUrl = "https://other.example")),
        )
    }

    @Test
    fun normalizeCloudSyncConfigsRequiresExplicitOptInForHttp() {
        val https = CloudSyncConfig(
            id = "https",
            name = "HTTPS",
            endpointUrl = "https://cloud.example.com/dav",
        )
        val blockedHttp = CloudSyncConfig(
            id = "http-blocked",
            name = "Blocked HTTP",
            endpointUrl = "http://192.168.1.2/dav",
            allowInsecureHttp = false,
        )
        val allowedHttp = blockedHttp.copy(
            id = "http-allowed",
            name = "Allowed HTTP",
            allowInsecureHttp = true,
        )
        val endpointWithUserInfo = CloudSyncConfig(
            id = "userinfo",
            name = "Unsafe user info",
            endpointUrl = "https://alice:secret@cloud.example.com/dav",
        )

        val normalized = normalizeCloudSyncConfigs(
            listOf(https, blockedHttp, allowedHttp, endpointWithUserInfo),
        )

        assertEquals(listOf("https", "http-allowed"), normalized.map(CloudSyncConfig::id))
    }

    @Test
    fun mealPhotosMigrationInsertsAfterQuickInputWithoutReordering() {
        assertEquals(
            listOf("today", "quick_input", "meal_photos", "website"),
            migrateMealPhotosWidget(
                items = listOf("today", "quick_input", "website"),
                migrated = false,
            ),
        )
    }

    @Test
    fun mealPhotosMigrationDoesNotDuplicateExistingWidget() {
        val items = listOf("meal_photos", "today", "quick_input")

        assertEquals(items, migrateMealPhotosWidget(items, migrated = false))
    }

    @Test
    fun mealPhotosMigrationAppendsWhenQuickInputIsMissing() {
        assertEquals(
            listOf("today", "website", "meal_photos"),
            migrateMealPhotosWidget(
                items = listOf("today", "website"),
                migrated = false,
            ),
        )
    }

    @Test
    fun mealPhotosMigrationDoesNotRestoreWidgetAfterUserRemoval() {
        val itemsAfterRemoval = listOf("today", "quick_input", "website")

        assertEquals(
            itemsAfterRemoval,
            migrateMealPhotosWidget(itemsAfterRemoval, migrated = true),
        )
    }

    @Test
    fun dailyRecordsMigrationInsertsAfterQuickInputOnce() {
        val migrated = migrateDailyRecordsWidget(
            items = listOf("today", "quick_input", "meal_photos", "website"),
            migrated = false,
        )

        assertEquals(
            listOf("today", "quick_input", "daily_records", "meal_photos", "website"),
            migrated,
        )
        assertEquals(migrated, migrateDailyRecordsWidget(migrated, migrated = false))
        assertEquals(
            listOf("today", "quick_input", "website"),
            migrateDailyRecordsWidget(
                items = listOf("today", "quick_input", "website"),
                migrated = true,
            ),
        )
    }

    @Test
    fun normalizeUserNameTrimsAndLimitsLength() {
        assertEquals("Ada", normalizeUserName("  Ada  "))
        assertEquals(32, normalizeUserName("a".repeat(40)).length)
        assertEquals("😀".repeat(32), normalizeUserName("😀".repeat(33)))
    }

    @Test
    fun normalizeHomeGreetingsSupportsDeletionFallbackAndUnicodeLimits() {
        val normalized = normalizeHomeGreetings(
            listOf(
                HomeGreetingTemplate("  今天开始  ", ""),
                HomeGreetingTemplate("", "Start today"),
                HomeGreetingTemplate("", ""),
                HomeGreetingTemplate(
                    "😀".repeat(MAX_HOME_GREETING_CODE_POINTS + 1),
                    "emoji",
                ),
            ),
        )

        assertEquals(
            HomeGreetingTemplate("今天开始", ""),
            normalized[0],
        )
        assertEquals(
            HomeGreetingTemplate("", "Start today"),
            normalized[1],
        )
        assertEquals(
            "😀".repeat(MAX_HOME_GREETING_CODE_POINTS),
            normalized[2].chinese,
        )
        assertTrue(normalizeHomeGreetings(emptyList()).isEmpty())
    }

    @Test
    fun normalizeHomeGreetingsLimitsCount() {
        val input = List(MAX_HOME_GREETINGS + 5) { index ->
            HomeGreetingTemplate("问候$index", "Greeting $index")
        }

        assertEquals(MAX_HOME_GREETINGS, normalizeHomeGreetings(input).size)
    }

    @Test
    fun normalizeMealButtonIconsFillsMissingOrBlankEntriesWithDefaults() {
        assertEquals(
            listOf("🥐", "🍱", "🍹", "🍜", "🍊", "🍤"),
            normalizeMealButtonIcons(listOf(" 🥐 ", "")),
        )
    }

    @Test
    fun normalizeThemeSecondaryColorsMakesOpaqueDeduplicatesAndLimitsCount() {
        val normalized = normalizeThemeSecondaryColors(
            listOf(
                0x00112233,
                0xFF112233.toInt(),
                0xFF223344.toInt(),
                0xFF334455.toInt(),
                0xFF445566.toInt(),
                0xFF556677.toInt(),
                0xFF667788.toInt(),
            ),
        )

        assertEquals(
            listOf(
                0xFF112233.toInt(),
                0xFF223344.toInt(),
                0xFF334455.toInt(),
                0xFF445566.toInt(),
                0xFF556677.toInt(),
            ),
            normalized,
        )
    }

    @Test
    fun normalizeThemeSecondaryColorsFillsSingleColorAndFallsBackWhenEmpty() {
        val oneColor = normalizeThemeSecondaryColors(listOf(0x00123456))

        assertEquals(0xFF123456.toInt(), oneColor.first())
        assertTrue(oneColor.size >= 2)
        assertEquals(
            DEFAULT_THEME_SECONDARY_COLORS_ARGB,
            normalizeThemeSecondaryColors(emptyList()),
        )
    }

    @Test
    fun normalizeFontScaleClampsRangeAndRejectsNonFiniteValues() {
        assertEquals(0.8f, normalizeFontScale(0.1f), 0f)
        assertEquals(1.3f, normalizeFontScale(9f), 0f)
        assertEquals(1f, normalizeFontScale(null), 0f)
        assertEquals(1f, normalizeFontScale(Float.NaN), 0f)
        assertEquals(1f, normalizeFontScale(Float.POSITIVE_INFINITY), 0f)
        assertEquals(1f, normalizeFontScale(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun normalizePoetryDisplayNumbersClampAndRejectNonFiniteValues() {
        assertEquals(14f, normalizePoetryFontSize(1f), 0f)
        assertEquals(36f, normalizePoetryFontSize(99f), 0f)
        assertEquals(18f, normalizePoetryFontSize(Float.NaN), 0f)
        assertEquals(1f, normalizePoetryLineSpacing(0.1f), 0f)
        assertEquals(2f, normalizePoetryLineSpacing(9f), 0f)
        assertEquals(1.45f, normalizePoetryLineSpacing(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun normalizeMusicVisualizerFrequencyBoundsKeepsAValidOrderedRange() {
        assertEquals(60 to 16_000, normalizeMusicVisualizerFrequencyBounds(60, 16_000))
        assertEquals(20 to 100, normalizeMusicVisualizerFrequencyBounds(-5, 100))
        assertEquals(1_000 to 1_001, normalizeMusicVisualizerFrequencyBounds(1_000, 10))
        assertEquals(19_999 to 20_000, normalizeMusicVisualizerFrequencyBounds(30_000, 40_000))
    }

    @Test
    fun normalizeDesktopWidgetConfigsBoundsAndSanitizesUntrustedFields() {
        val normalized = normalizeDesktopWidgetConfigs(
            listOf(
                DesktopWidgetConfig(
                    id = " card-1 ",
                    name = " My\nCard ",
                    widthCells = -4,
                    heightCells = 99,
                    backgroundColorArgb = 0x00112233,
                    textColorArgb = 0x00445566,
                    backgroundImageUri = " file:///private/image.jpg ",
                    homeModuleId = "not-a-module",
                    appLabel = " App\rName ",
                ),
            ),
        ).single()

        assertEquals("card-1", normalized.id)
        assertEquals("My Card", normalized.name)
        assertEquals(1, normalized.widthCells)
        assertEquals(6, normalized.heightCells)
        assertEquals(0xFF112233.toInt(), normalized.backgroundColorArgb)
        assertEquals(0xFF445566.toInt(), normalized.textColorArgb)
        assertEquals(null, normalized.backgroundImageUri)
        assertEquals("today", normalized.homeModuleId)
        assertEquals("App Name", normalized.appLabel)
    }

    @Test
    fun normalizeDesktopWidgetConfigsRejectsUnsafeIdsAndIncompleteShortcuts() {
        val normalized = normalizeDesktopWidgetConfigs(
            listOf(
                DesktopWidgetConfig(id = "unsafe id", name = "Unsafe"),
                DesktopWidgetConfig(
                    id = "shortcut",
                    name = "Shortcut",
                    contentType = DesktopWidgetContentType.APP_SHORTCUT,
                    appPackageName = "not a package",
                ),
                DesktopWidgetConfig(
                    id = "valid",
                    name = "Valid",
                    contentType = DesktopWidgetContentType.APP_SHORTCUT,
                    appPackageName = " com.example.reader ",
                ),
                DesktopWidgetConfig(id = "valid", name = "Duplicate"),
            ),
        )

        assertEquals(1, normalized.size)
        assertEquals("valid", normalized.single().id)
        assertEquals("com.example.reader", normalized.single().appPackageName)
    }
}
