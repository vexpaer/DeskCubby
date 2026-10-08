package com.deskcubby.app.ui.statshub

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YearInPixelsTest {
    @Test
    fun `pixels cover the whole year and sum words per day`() {
        val today = LocalDate.of(2028, 3, 1)
        val pixels = diaryYearPixels(
            listOf(
                LocalDate.of(2028, 1, 1) to 100L,
                LocalDate.of(2028, 1, 1) to 50L,
                LocalDate.of(2028, 2, 29) to 0L,
                LocalDate.of(2027, 12, 31) to 999L,
            ),
            today,
        )
        assertEquals(366, pixels.dailyWords.size)
        assertEquals(150L, pixels.dailyWords[0])
        assertEquals(2, pixels.writtenDays)
        assertEquals(60, pixels.todayIndex)
        assertEquals(4, pixels.levelAt(0))
        // A diary with zero counted words still lights its day at the lowest level.
        assertEquals(1, pixels.levelAt(59))
        assertEquals(0, pixels.levelAt(1))
    }

    @Test
    fun `levels split the busiest day into quarters`() {
        assertEquals(0, yearPixelLevel(0, 100))
        assertEquals(1, yearPixelLevel(10, 100))
        assertEquals(2, yearPixelLevel(25, 100))
        assertEquals(3, yearPixelLevel(50, 100))
        assertEquals(4, yearPixelLevel(75, 100))
        assertEquals(4, yearPixelLevel(100, 100))
        assertEquals(0, yearPixelLevel(10, 0))
    }

    @Test
    fun `grid cells map to real dates only`() {
        assertEquals(0, yearPixelIndex(2026, 1, 1))
        assertEquals(58, yearPixelIndex(2026, 2, 28))
        assertNull(yearPixelIndex(2026, 2, 29))
        assertEquals(59, yearPixelIndex(2028, 2, 29))
        assertNull(yearPixelIndex(2026, 4, 31))
        assertNull(yearPixelIndex(2026, 13, 1))
        assertNull(yearPixelIndex(2026, 1, 0))
    }
}
