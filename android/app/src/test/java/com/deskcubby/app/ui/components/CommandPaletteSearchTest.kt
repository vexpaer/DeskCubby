package com.deskcubby.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPaletteSearchTest {
    @Test
    fun `characters must appear in order`() {
        assertNotNull(paletteScore("stt", "Statistics"))
        assertNull(paletteScore("tsx", "Statistics"))
        assertNull(paletteScore("a", ""))
        assertEquals(0, paletteScore("  ", "anything"))
    }

    @Test
    fun `prefix and contiguous matches beat scattered ones`() {
        val prefix = requireNotNull(paletteScore("dia", "Diary"))
        val scattered = requireNotNull(paletteScore("dia", "Daily records archive"))
        assertTrue(prefix > scattered)
    }

    @Test
    fun `cjk input and case are handled`() {
        assertNotNull(paletteScore("日记", "写今天的日记"))
        assertNotNull(paletteScore("DIARY", "diary"))
        assertNotNull(paletteScore("今 日记", "今天的日记"))
    }

    @Test
    fun `ranking uses terms and keeps pages above equally good diaries`() {
        val entries = listOf(
            PaletteEntry("diary:1", PaletteEntryKind.DIARY, "Diary about rain", "2026-10-01"),
            PaletteEntry("page:diary", PaletteEntryKind.PAGE, "日记", terms = listOf("Diary")),
            PaletteEntry("page:rss", PaletteEntryKind.PAGE, "RSS"),
        )
        val ranked = rankPaletteEntries("diary", entries)
        assertEquals(listOf("page:diary", "diary:1"), ranked.map { it.id })
        assertEquals(entries.take(2), rankPaletteEntries("", entries, limit = 2))
    }
}
