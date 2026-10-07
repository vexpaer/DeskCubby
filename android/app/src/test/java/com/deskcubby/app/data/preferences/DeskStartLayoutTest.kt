package com.deskcubby.app.data.preferences

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskStartLayoutTest {
    @Test
    fun `fresh install bar leads with desk and keeps five entries`() {
        val nav = normalizeNavItems(freshInstallDeskNavItems(AppSettings().navItems))
        val bar = nav.filter { it.visible }.map { it.id }

        assertEquals(NavItemId.DESK, bar.first())
        assertEquals(NavItemId.SETTINGS, nav.last().id)
        assertEquals(
            listOf(NavItemId.DESK, NavItemId.HOME, NavItemId.DIARY, NavItemId.MORE, NavItemId.SETTINGS),
            bar,
        )
        val thought = nav.single { it.id == NavItemId.THOUGHT }
        assertFalse(thought.visible)
        assertTrue(thought.showInMore)
        assertFalse(nav.single { it.id == NavItemId.DESK }.showInMore)
        assertEquals(NavItemId.entries.size, nav.size)
    }

    @Test
    fun `promoting desk keeps every other customization untouched`() {
        val custom = listOf(
            NavItemConfig(NavItemId.HOME, label = "Start"),
            NavItemConfig(NavItemId.RSS, visible = true, showInMore = false),
            NavItemConfig(NavItemId.DESK, label = "Table", visible = false, showInMore = true),
            NavItemConfig(NavItemId.SETTINGS),
        )
        val promoted = promoteDeskToStart(custom)

        assertEquals(NavItemId.DESK, promoted.first().id)
        assertEquals("Table", promoted.first().label)
        assertTrue(promoted.first().visible)
        assertFalse(promoted.first().showInMore)
        assertEquals(custom.filterNot { it.id == NavItemId.DESK }, promoted.drop(1))
    }

    @Test
    fun `promoting desk adds it when an old configuration lacks it`() {
        val promoted = promoteDeskToStart(listOf(NavItemConfig(NavItemId.HOME), NavItemConfig(NavItemId.SETTINGS)))
        assertEquals(listOf(NavItemId.DESK, NavItemId.HOME, NavItemId.SETTINGS), promoted.map { it.id })
        assertEquals(1, promoted.count { it.id == NavItemId.DESK })
    }
}
