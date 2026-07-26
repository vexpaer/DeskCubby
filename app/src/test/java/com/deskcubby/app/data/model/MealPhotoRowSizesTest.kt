package com.deskcubby.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MealPhotoRowSizesTest {
    @Test
    fun smartModeNeverLeavesASinglePhotoOnTheLastRow() {
        for (count in 2..60) {
            val rows = mealPhotoRowSizes(count, MealPhotosPerRow.SMART)
            assertEquals("sum for $count", count, rows.sum())
            rows.forEach { size ->
                if (size !in 2..3) throw AssertionError("row of $size for count $count")
            }
        }
    }

    @Test
    fun smartModeMatchesDocumentedExamples() {
        assertEquals(listOf(1), mealPhotoRowSizes(1, MealPhotosPerRow.SMART))
        assertEquals(listOf(2, 2), mealPhotoRowSizes(4, MealPhotosPerRow.SMART))
        assertEquals(listOf(3, 2), mealPhotoRowSizes(5, MealPhotosPerRow.SMART))
        assertEquals(listOf(3, 3), mealPhotoRowSizes(6, MealPhotosPerRow.SMART))
        assertEquals(listOf(3, 2, 2), mealPhotoRowSizes(7, MealPhotosPerRow.SMART))
        assertEquals(listOf(3, 3, 2), mealPhotoRowSizes(8, MealPhotosPerRow.SMART))
        assertEquals(listOf(3, 3, 3), mealPhotoRowSizes(9, MealPhotosPerRow.SMART))
    }

    @Test
    fun fixedModesChunkAndKeepTheRemainder() {
        assertEquals(listOf(2, 2, 1), mealPhotoRowSizes(5, MealPhotosPerRow.TWO))
        assertEquals(listOf(3, 3, 1), mealPhotoRowSizes(7, MealPhotosPerRow.THREE))
        assertEquals(listOf(3, 3), mealPhotoRowSizes(6, MealPhotosPerRow.THREE))
    }

    @Test
    fun emptyAndNegativeCountsProduceNoRows() {
        assertEquals(emptyList<Int>(), mealPhotoRowSizes(0, MealPhotosPerRow.SMART))
        assertEquals(emptyList<Int>(), mealPhotoRowSizes(-3, MealPhotosPerRow.TWO))
    }
}
