package com.deskcubby.app.ui.desk

import com.deskcubby.app.ui.desk.model.DeskAmbient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskSelectionTest {

    @Test
    fun seedRotation_isStableAndBounded() {
        val a1 = seedRotation("abc:2024-08-14", -0.5f..0.5f)
        val a2 = seedRotation("abc:2024-08-14", -0.5f..0.5f)
        val b = seedRotation("xyz:2024-08-14", -0.9f..0.9f)
        // Same input -> same output (reproducible).
        assertEquals(a1, a2, 0.0001f)
        assertTrue(a1 in -0.5f..0.5f)
        assertTrue(b in -0.9f..0.9f)
    }

    @Test
    fun ambientFor_mapsHoursToPeriods() {
        assertEquals(DeskAmbient.MORNING, ambientFor(7))
        assertEquals(DeskAmbient.AFTERNOON, ambientFor(13))
        assertEquals(DeskAmbient.EVENING, ambientFor(19))
        assertEquals(DeskAmbient.LATE_NIGHT, ambientFor(2))
        assertEquals(DeskAmbient.LATE_NIGHT, ambientFor(23))
    }

    @Test
    fun plainExcerpt_stripsMarkdownAndTruncates() {
        val markdown = "# 今天下午突然下雨了\n\n**从实验室出来**，才发现已经快七点……"
        val result = plainExcerpt(markdown, maxChars = 40)
        assertTrue(!result.contains('#'))
        assertTrue(!result.contains('*'))
        assertTrue(result.length <= 41) // maxChars + "…"
    }

    @Test
    fun plainExcerpt_preservesPlainText() {
        val markdown = "keep this as is"
        assertEquals("keep this as is", plainExcerpt(markdown))
    }
}
