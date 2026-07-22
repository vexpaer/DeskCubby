package com.deskcubby.app.data.preferences

import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.DEFAULT_THEME_SECONDARY_COLORS_ARGB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {
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
    fun normalizeUserNameTrimsAndLimitsLength() {
        assertEquals("Ada", normalizeUserName("  Ada  "))
        assertEquals(32, normalizeUserName("a".repeat(40)).length)
        assertEquals("😀".repeat(32), normalizeUserName("😀".repeat(33)))
    }

    @Test
    fun normalizeMealButtonIconsFillsMissingOrBlankEntriesWithDefaults() {
        assertEquals(
            listOf("🥐", "🥗", "🍚", "🍎", "🌙"),
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
}
