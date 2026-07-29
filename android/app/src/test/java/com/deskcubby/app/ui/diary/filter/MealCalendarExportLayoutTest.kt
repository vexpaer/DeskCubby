package com.deskcubby.app.ui.diary.filter

import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.mealPhotoRowSizes
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealCalendarExportLayoutTest {
    @Test
    fun rangeIncludesBothBoundaryDays() {
        val start = LocalDate.parse("2026-07-01")
        val end = LocalDate.parse("2026-07-31")

        assertTrue(isDateInMealExportRange(start, start, end))
        assertTrue(isDateInMealExportRange(end, start, end))
        assertTrue(isDateInMealExportRange(LocalDate.parse("2026-07-15"), start, end))
        assertFalse(isDateInMealExportRange(LocalDate.parse("2026-06-30"), start, end))
        assertFalse(isDateInMealExportRange(LocalDate.parse("2026-08-01"), start, end))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedRangeIsRejected() {
        isDateInMealExportRange(
            date = LocalDate.parse("2026-07-15"),
            startInclusive = LocalDate.parse("2026-07-31"),
            endInclusive = LocalDate.parse("2026-07-01"),
        )
    }

    @Test
    fun layoutUsesExistingRowPolicyAndStaysWithinAllocationLimits() {
        val layout = mealCalendarExportLayout(
            photoCounts = listOf(4, 5),
            imageMaxHeight = 124,
            showCaptions = true,
            photosPerRow = MealPhotosPerRow.SMART,
        )

        assertEquals(EXPORT_WIDTH_PX, layout.width)
        assertEquals(listOf(listOf(2, 2), listOf(3, 2)), layout.rowsPerDay)
        assertTrue(layout.height <= EXPORT_MAX_HEIGHT_PX)
        assertTrue(layout.pixelCount <= EXPORT_MAX_PIXELS)
    }

    @Test
    fun captionsIncreasePreflightHeight() {
        val withoutCaptions = mealCalendarExportLayout(
            photoCounts = listOf(6),
            imageMaxHeight = 124,
            showCaptions = false,
            photosPerRow = MealPhotosPerRow.THREE,
        )
        val withCaptions = mealCalendarExportLayout(
            photoCounts = listOf(6),
            imageMaxHeight = 124,
            showCaptions = true,
            photosPerRow = MealPhotosPerRow.THREE,
        )

        assertEquals(2 * 44, withCaptions.height - withoutCaptions.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun excessiveHeightIsRejectedByPurePreflight() {
        mealCalendarExportLayout(
            photoCounts = List(200) { 6 },
            imageMaxHeight = 320,
            showCaptions = true,
            photosPerRow = MealPhotosPerRow.TWO,
        )
    }

    @Test
    fun arithmeticPreflightMatchesEveryGeneratedRowPolicy() {
        MealPhotosPerRow.entries.forEach { mode ->
            (1..30).forEach { photoCount ->
                val layout = mealCalendarExportLayout(
                    photoCounts = listOf(photoCount),
                    imageMaxHeight = 124,
                    showCaptions = true,
                    photosPerRow = mode,
                )
                val rows = mealPhotoRowSizes(photoCount, mode)
                val expectedHeight =
                    MealCalendarExportLayout.CONTENT_PADDING +
                        MealCalendarExportLayout.HEADER_HEIGHT +
                        MealCalendarExportLayout.CONTENT_PADDING +
                        MealCalendarExportLayout.DAY_HEADER_HEIGHT +
                        rows.size * (124 + 44) +
                        (rows.size - 1).coerceAtLeast(0) * MealCalendarExportLayout.ROW_GAP +
                        MealCalendarExportLayout.DAY_GAP

                assertEquals("$mode with $photoCount photos", expectedHeight, layout.height)
                assertEquals(rows, layout.rowsPerDay.single())
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathologicalPhotoCountIsRejectedBeforeRowListAllocation() {
        mealCalendarExportLayout(
            photoCounts = listOf(Int.MAX_VALUE),
            imageMaxHeight = 320,
            showCaptions = true,
            photosPerRow = MealPhotosPerRow.SMART,
        )
    }
}
