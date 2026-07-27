package com.deskcubby.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MorePageOrderModelTest {
    @Test
    fun emptyLegacyOrderInheritsNavigationOrderBeforeFillingMissingPages() {
        val legacyNavigation = listOf(
            NavItemConfig(NavItemId.HOME),
            NavItemConfig(NavItemId.AI_CHAT),
            NavItemConfig(NavItemId.THOUGHT),
            NavItemConfig(NavItemId.SETTINGS),
        )

        val normalized = AppSettings(navItems = legacyNavigation).morePageOrder

        assertEquals(
            listOf(NavItemId.AI_CHAT, NavItemId.THOUGHT),
            normalized.take(2),
        )
        assertEquals(MORE_PAGE_ORDERABLE_IDS.toSet(), normalized.toSet())
        assertEquals(normalized.size, normalized.distinct().size)
    }

    @Test
    fun normalizationDropsReservedAndDuplicateIds() {
        val normalized = normalizeMorePageOrder(
            order = listOf(
                NavItemId.HOME,
                NavItemId.RSS,
                NavItemId.RSS,
                NavItemId.MORE,
                NavItemId.SETTINGS,
            ),
            navItems = AppSettings().navItems,
        )

        assertEquals(NavItemId.RSS, normalized.first())
        assertFalse(NavItemId.HOME in normalized)
        assertFalse(NavItemId.MORE in normalized)
        assertFalse(NavItemId.SETTINGS in normalized)
        assertEquals(MORE_PAGE_ORDERABLE_IDS.toSet(), normalized.toSet())
        assertEquals(normalized.size, normalized.distinct().size)
    }
}
