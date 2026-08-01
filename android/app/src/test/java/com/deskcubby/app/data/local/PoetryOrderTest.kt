package com.deskcubby.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class PoetryOrderTest {
    @Test
    fun firstPoemCanMoveAndAnotherPoemCanBecomeFirst() {
        val original = listOf(11L, 22L, 33L, 44L)

        assertEquals(
            listOf(22L, 33L, 44L, 11L),
            movePoemIdToIndex(original, id = 11L, targetIndex = 3),
        )
        assertEquals(
            listOf(44L, 11L, 22L, 33L),
            movePoemIdToIndex(original, id = 44L, targetIndex = 0),
        )
    }

    @Test
    fun filteredOrderKeepsOtherCategoriesInTheirGlobalSlots() {
        assertEquals(
            listOf(33L, 22L, 11L, 44L),
            replacePoemSubsetOrder(
                allIds = listOf(11L, 22L, 33L, 44L),
                orderedSubsetIds = listOf(33L, 11L),
            ),
        )
    }
}
