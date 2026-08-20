package com.deskcubby.app.data.structuredrecords

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalDayEngineTest {

    @Test
    fun enterTodayDiaryAt0200OpensYesterday() {
        val result = JournalDayEngine.resolveTodayDiaryDate(
            LocalDateTime.of(2026, 8, 20, 2, 0),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDate.of(2026, 8, 19), result)
    }

    @Test
    fun enterTodayDiaryAt0800OpensToday() {
        val result = JournalDayEngine.resolveTodayDiaryDate(
            LocalDateTime.of(2026, 8, 20, 8, 0),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDate.of(2026, 8, 20), result)
    }

    @Test
    fun exactSwitchTimeOpensToday() {
        val result = JournalDayEngine.resolveTodayDiaryDate(
            LocalDateTime.of(2026, 8, 20, 5, 0),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDate.of(2026, 8, 20), result)
    }

    @Test
    fun switchTimeParsingIsLenientAndValidated() {
        assertEquals(300, JournalDayEngine.parseBoundary("5:00"))
        assertEquals("05:00", JournalDayEngine.formatBoundary(300))
        assertNull(JournalDayEngine.parseBoundary("25:00"))
        assertNull(JournalDayEngine.parseBoundary("ab:cd"))
        assertNull(JournalDayEngine.parseBoundary(null))
    }
}
