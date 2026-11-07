package com.deskcubby.app.ui.diary.filter

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealCalendarPdfLayoutTest {
    private val spec = MealPdfPageSpec()

    @Test
    fun rangeIncludesBothBoundaryDays() {
        val start = LocalDate.parse("2026-07-01")
        val end = LocalDate.parse("2026-07-31")
        assertTrue(isDateInMealExportRange(start, start, end))
        assertTrue(isDateInMealExportRange(end, start, end))
        assertFalse(isDateInMealExportRange(LocalDate.parse("2026-06-30"), start, end))
        assertFalse(isDateInMealExportRange(LocalDate.parse("2026-08-01"), start, end))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedRangeIsRejected() {
        isDateInMealExportRange(LocalDate.parse("2026-07-15"),
            LocalDate.parse("2026-07-31"), LocalDate.parse("2026-07-01"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyExportIsRejected() {
        mealCalendarPdfLayout(emptyList())
    }

    @Test
    fun eachOrdinaryDayGetsExactlyOnePageAndItsOwnDate() {
        val days = listOf(
            MealPdfDayInput("2026-10-16", listOf(0.75f, 1.2f, 0.5f)),
            MealPdfDayInput("2026-10-17", listOf(1.4f)),
        )
        val layout = mealCalendarPdfLayout(days)
        assertEquals(2, layout.pageCount)
        assertEquals(listOf("2026-10-16", "2026-10-17"), layout.dates.map { it.dateIso })
        assertEquals(listOf(0, 0, 0, 1), layout.photos.map { it.page })
    }

    @Test
    fun collagePreservesEveryOriginalRatioWithoutCroppingOrOverlap() {
        val aspects = listOf(0.75f, 1.2f, 0.5f, 1.75f, 0.9f)
        val layout = mealCalendarPdfLayout(listOf(MealPdfDayInput("2026-10-16", aspects)))
        assertEquals(aspects.size, layout.photos.size)
        layout.photos.forEach { photo ->
            assertEquals(aspects[photo.photoIndex], photo.height / photo.width, 0.002f)
            assertTrue(photo.x >= -0.01f && photo.y >= -0.01f)
            assertTrue(photo.width > 0f && photo.height > 0f)
            assertTrue(photo.x + photo.width <= spec.width + 0.02f)
            assertTrue(photo.y + photo.height <= spec.contentHeight + 0.02f)
        }
        for (i in layout.photos.indices) {
            for (j in i + 1 until layout.photos.size) {
                val a = layout.photos[i]
                val b = layout.photos[j]
                val overlapX = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
                val overlapY = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
                assertFalse("Photos overlap", overlapX > 0.02f && overlapY > 0.02f)
            }
        }
    }

    @Test
    fun twoPhotosRemainVisuallyBalanced() {
        val layout = mealCalendarPdfLayout(
            listOf(MealPdfDayInput("2026-10-16", listOf(0.75f, 1.2f))),
        )
        val area = layout.photos.map { it.width * it.height }
        assertTrue(area.min() / area.sum() >= 0.25f)
    }

    @Test
    fun moreThanNinePhotosUseBalancedContinuationPages() {
        val photos = List(22) { index -> listOf(0.75f, 1.4f, 0.6f)[index % 3] }
        val layout = mealCalendarPdfLayout(listOf(MealPdfDayInput("2026-10-16", photos)))
        assertEquals(3, layout.pageCount)
        assertEquals(listOf(8, 7, 7), (0..2).map { page ->
            layout.photos.count { it.page == page }
        })
        assertTrue(layout.dates.all { it.dateIso == "2026-10-16" })
        assertEquals((0..21).toList(), layout.photos.map { it.photoIndex }.sorted())
    }

    @Test
    fun aSingleTallPhotoIsNeverCroppedToAnArtificialAspectLimit() {
        val layout = mealCalendarPdfLayout(
            listOf(MealPdfDayInput("2026-10-16", listOf(40f))),
        )
        assertEquals(40f, layout.photos.single().height / layout.photos.single().width, 0.01f)
    }

    @Test
    fun missingPhotoAspectUsesSafeFallback() {
        val layout = mealCalendarPdfLayout(
            listOf(MealPdfDayInput("2026-10-16", listOf(null, Float.NaN, 1f))),
        )
        assertEquals(0.75f, layout.photos.single { it.photoIndex == 0 }.let {
            it.height / it.width
        }, 0.002f)
    }

    @Test
    fun longRangesKeepOnePagePerOrdinaryDay() {
        val days = List(200) { index ->
            MealPdfDayInput(
                LocalDate.parse("2020-01-01").plusDays(index.toLong()).toString(),
                List(index % 4 + 1) { photo -> listOf(0.75f, 1.33f, 1f, 0.5f)[photo] },
            )
        }
        val layout = mealCalendarPdfLayout(days)
        assertEquals(200, layout.pageCount)
        assertEquals(days.sumOf { it.photoAspects.size }, layout.photos.size)
        assertEquals(days.map { it.dateIso }, layout.dates.map { it.dateIso })
    }

    @Test
    fun contentPaintsFullBackgroundPhotosAndBottomRightDateWithoutSeparators() {
        val layout = mealCalendarPdfLayout(
            listOf(MealPdfDayInput("2026-10-16", listOf(1f, 0.6f))),
        )
        val content = mealPdfPageContent(
            layout, 0, listOf("Im4", null), "F1", 0xFF252525.toInt(),
        )
        assertTrue(content.contains("/Im4 Do"))
        assertTrue(content.contains("0 0 595 842 re f Q"))
        assertTrue(content.contains("(2026.10.16) Tj"))
        assertFalse(content.contains("[3 2]"))
        assertEquals("", mealPdfPageContent(layout, 1, listOf("Im4", null), "F1"))
    }

    @Test
    fun pdfStringsAndNumericCoordinatesAreSafe() {
        assertEquals("a\\(b\\)\\\\?", pdfAsciiText("a(b)\\吃"))
        assertEquals("12", pdfNumber(12f))
        assertEquals("12.5", pdfNumber(12.5f))
        assertEquals("0", pdfNumber(-0.0001f))
        assertTrue(helveticaTextWidth("2026.10.16", 9f) > 0f)
    }
}
