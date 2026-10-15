package com.deskcubby.app.ui.diary.filter

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        isDateInMealExportRange(
            date = LocalDate.parse("2026-07-15"),
            startInclusive = LocalDate.parse("2026-07-31"),
            endInclusive = LocalDate.parse("2026-07-01"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyExportIsRejected() {
        mealCalendarPdfLayout(emptyList())
    }

    @Test
    fun pageMarginsAreNarrowAndColumnsFillTheWidth() {
        assertTrue(spec.margin <= 16f)
        assertEquals(
            spec.width,
            spec.margin * 2f + spec.columnWidth * 2f + spec.gutter,
            0.001f,
        )
    }

    @Test
    fun photosKeepAspectRatioAndAlternateToTheShorterColumn() {
        val layout = mealCalendarPdfLayout(
            listOf(MealPdfDayInput("2026-10-03", listOf(0.75f, 1.2f, 0.5f))),
        )

        val (first, second, third) = layout.photos
        assertEquals(0, first.column)
        assertEquals(1, second.column)
        // The left column is shorter after a landscape and a portrait photo.
        assertEquals(0, third.column)
        assertEquals(spec.columnWidth * 0.75f, first.height, 0.001f)
        assertEquals(spec.columnWidth * 1.2f, second.height, 0.001f)
        assertEquals(first.y + first.height + spec.photoGap, third.y, 0.001f)
        assertEquals(1, layout.pageCount)
    }

    @Test
    fun separatorIsSteppedAndLabelUsesTheLongerColumn() {
        // Day one leaves the left column much longer, matching the sketch: the next separator is
        // lower on the left and higher on the right, and day two only uses the right column.
        val layout = mealCalendarPdfLayout(
            listOf(
                MealPdfDayInput("2026-10-03", listOf(1.4f, 0.5f, 0.5f)),
                MealPdfDayInput("2026-10-05", listOf(0.6f)),
                MealPdfDayInput("2026-10-06", listOf(0.9f, 0.9f)),
            ),
        )

        val second = layout.separators[1]
        assertEquals("2026-10-05", second.dateIso)
        assertNotEquals(second.segments[0].y, second.segments[1].y)
        assertTrue(second.segments[1].y < second.segments[0].y)
        assertEquals(1, layout.photos.single { it.dayIndex == 1 }.column)
        assertEquals(1, second.labelColumn)
        // Every day, including the first, gets a dated separator above its photos.
        assertEquals(listOf("2026-10-03", "2026-10-05", "2026-10-06"), layout.separators.map { it.dateIso })
        layout.photos.forEach { photo ->
            val separator = layout.separators[photo.dayIndex].segments[photo.column]
            assertTrue(separator.page < photo.page || separator.y < photo.y)
        }
    }

    @Test
    fun labelTieFallsBackToLeftColumn() {
        val layout = mealCalendarPdfLayout(
            listOf(MealPdfDayInput("2026-10-03", listOf(1f, 1f))),
        )

        assertEquals(0, layout.separators.single().labelColumn)
    }

    @Test
    fun unknownAndExtremeAspectsAreClampedToPage() {
        assertEquals(spec.columnWidth * 0.75f, mealPdfPhotoHeight(null, spec), 0.001f)
        assertEquals(spec.columnWidth * 0.75f, mealPdfPhotoHeight(Float.NaN, spec), 0.001f)
        assertEquals(spec.maxPhotoHeight, mealPdfPhotoHeight(40f, spec), 0.001f)
        assertEquals(spec.minPhotoHeight, mealPdfPhotoHeight(0.01f, spec), 0.001f)
    }

    @Test
    fun veryLongRangesPaginateWithoutLimitAndStayInsidePages() {
        val days = List(2_000) { index ->
            MealPdfDayInput(
                dateIso = LocalDate.parse("2020-01-01").plusDays(index.toLong()).toString(),
                photoAspects = List(index % 5 + 1) { photo -> listOf(0.75f, 1.33f, 1f, 40f)[photo % 4] },
            )
        }
        val layout = mealCalendarPdfLayout(days)

        assertEquals(days.sumOf { it.photoAspects.size }, layout.photos.size)
        assertTrue(layout.pageCount > 100)
        layout.photos.forEach { photo ->
            assertTrue(photo.page in 0 until layout.pageCount)
            assertTrue(photo.y >= spec.contentTop)
            assertTrue(photo.y + photo.height <= spec.contentBottom + 0.001f)
        }
        layout.separators.forEach { separator ->
            separator.segments.forEach { segment ->
                assertTrue(segment.page in 0 until layout.pageCount)
                assertTrue(segment.y > spec.contentTop && segment.y < spec.contentBottom)
            }
        }
    }

    @Test
    fun separatorFollowsFirstPhotoThatDoesNotFitOnThePage() {
        // Fill the left column almost to the bottom, then start a new day with a tall photo.
        val layout = mealCalendarPdfLayout(
            listOf(
                MealPdfDayInput("2026-10-01", listOf(2.6f, 2.6f)),
                MealPdfDayInput("2026-10-02", listOf(1.4f)),
            ),
        )
        val photo = layout.photos.last()
        val segment = layout.separators[1].segments[photo.column]

        assertEquals(photo.page, segment.page)
        assertTrue(segment.y < photo.y)
    }

    @Test
    fun everyPageWithPhotosOfADayShowsThatDate() {
        val days = List(60) { index ->
            MealPdfDayInput(
                dateIso = LocalDate.parse("2026-01-01").plusDays(index.toLong()).toString(),
                photoAspects = List(index % 4 + 1) { photo -> listOf(0.75f, 1.33f, 0.6f, 1f)[photo] },
            )
        }
        val layout = mealCalendarPdfLayout(days)

        layout.photos.forEach { photo ->
            val separator = layout.separators[photo.dayIndex]
            val labelledPages = separator.segments.filterIndexed { column, _ ->
                column == separator.labelColumn || separator.repeatLabelInOtherColumn
            }.map { it.page } + layout.continuations
                .filter { it.dayIndex == photo.dayIndex && it.column == photo.column }
                .map { it.page }
            assertTrue(photo.page in labelledPages)
        }
        layout.separators.forEach { separator ->
            assertTrue(
                layout.photos.any {
                    it.dayIndex == separator.dayIndex && it.column == separator.labelColumn
                },
            )
        }
    }

    @Test
    fun labelNeverSitsAboveAColumnWithoutThatDaysPhotos() {
        val layout = mealCalendarPdfLayout(
            listOf(
                MealPdfDayInput("2026-10-01", listOf(0.3f, 0.4f, 2.6f, 1.0f)),
                MealPdfDayInput("2026-10-02", listOf(0.75f)),
                MealPdfDayInput("2026-10-03", listOf(0.4f, 2.6f, 0.4f)),
                MealPdfDayInput("2026-10-04", listOf(0.75f)),
                MealPdfDayInput("2026-10-05", listOf(2.6f, 1.6f)),
            ),
        )

        layout.separators.forEach { separator ->
            assertTrue(
                layout.photos.any {
                    it.dayIndex == separator.dayIndex && it.column == separator.labelColumn
                },
            )
        }
    }

    @Test
    fun pageContentDrawsImagesPlaceholdersDashesAndDate() {
        val layout = mealCalendarPdfLayout(
            listOf(
                MealPdfDayInput("2026-10-03", listOf(1f, 0.6f)),
                MealPdfDayInput("2026-10-05", listOf(0.75f)),
            ),
        )
        val content = mealPdfPageContent(layout, 0, listOf("Im4", null, "Im5"), "F1")

        assertTrue(content.contains("/Im4 Do"))
        assertTrue(content.contains("/Im5 Do"))
        assertTrue(content.contains(" re f"))
        assertTrue(content.contains("[3 2] 0 d"))
        assertTrue(content.contains("(2026-10-03) Tj"))
        assertTrue(content.contains("(2026-10-05) Tj"))
        assertEquals("", mealPdfPageContent(layout, 1, listOf("Im4", null, "Im5"), "F1"))
    }

    @Test
    fun pdfTextAndNumbersAreSafe() {
        assertEquals("a\\(b\\)\\\\?", pdfAsciiText("a(b)\\吃"))
        assertEquals("12", pdfNumber(12f))
        assertEquals("12.5", pdfNumber(12.5f))
        assertEquals("0", pdfNumber(-0.001f))
        assertEquals(10 * 0.556f * 9f - 2 * (0.556f - 0.333f) * 9f, helveticaTextWidth("2026-10-03", 9f), 0.001f)
    }
}
