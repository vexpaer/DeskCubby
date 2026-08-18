package com.deskcubby.app.data.structuredrecords

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalDayEngineTest {

    @Test
    fun boundaryBeforeIsPreviousDay() {
        val result = JournalDayEngine.resolveJournalDay(
            LocalDateTime.of(2026, 8, 19, 4, 59),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDate.of(2026, 8, 18), result)
    }

    @Test
    fun boundaryAtIsCurrentDay() {
        val result = JournalDayEngine.resolveJournalDay(
            LocalDateTime.of(2026, 8, 19, 5, 0),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDate.of(2026, 8, 19), result)
    }

    @Test
    fun boundaryAfterIsCurrentDay() {
        val result = JournalDayEngine.resolveJournalDay(
            LocalDateTime.of(2026, 8, 19, 18, 20),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDate.of(2026, 8, 19), result)
    }

    @Test
    fun invalidBoundaryFallsBackToDefault() {
        assertNull(JournalDayEngine.parseBoundary("25:00"))
        assertNull(JournalDayEngine.parseBoundary("ab:cd"))
        assertNull(JournalDayEngine.parseBoundary(null))
        assertEquals("05:00", JournalDayEngine.formatBoundary(300))
    }

    @Test
    fun resolveFieldDateTimeAcrossBoundary() {
        // Journal Day 2026-08-18 with 02:37 and boundary 05:00 → 2026-08-19 02:37.
        val result = JournalDayEngine.resolveFieldDateTime(
            LocalDate.of(2026, 8, 18),
            LocalTime.of(2, 37),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDateTime.of(2026, 8, 19, 2, 37), result)
    }

    @Test
    fun resolveFieldDateTimeAfterBoundaryStaysOnDay() {
        val result = JournalDayEngine.resolveFieldDateTime(
            LocalDate.of(2026, 8, 19),
            LocalTime.of(8, 12),
            JournalDayEngine.parseBoundary("05:00"),
        )
        assertEquals(LocalDateTime.of(2026, 8, 19, 8, 12), result)
    }

    @Test
    fun boundaryHistoryUsesEffectiveValue() {
        val history = listOf(
            DayBoundaryRecord(effectiveFromJournalDay = "2026-08-01", value = "05:00"),
            DayBoundaryRecord(effectiveFromJournalDay = "2026-08-10", value = "04:00"),
        )
        assertEquals(
            "04:00",
            JournalDayEngine.getEffectiveDayBoundary(LocalDate.of(2026, 8, 15), history),
        )
        assertEquals(
            "05:00",
            JournalDayEngine.getEffectiveDayBoundary(LocalDate.of(2026, 8, 5), history),
        )
        assertEquals(
            JournalDayEngine.DEFAULT_DAY_BOUNDARY,
            JournalDayEngine.getEffectiveDayBoundary(LocalDate.of(2026, 7, 1), history),
        )
    }
}
