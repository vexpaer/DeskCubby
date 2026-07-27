package com.deskcubby.app.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContextSelectionTest {
    @Test
    fun importsWholeCategoryAndKeepsExistingSelection() {
        val result = toggleAiContextGroup(
            currentSelection = linkedSetOf("diary:1", "thought:1"),
            groupKeys = listOf("thought:1", "thought:2", "thought:3"),
            maxItems = 50,
        )

        assertFalse(result.limitExceeded)
        assertEquals(
            linkedSetOf("diary:1", "thought:1", "thought:2", "thought:3"),
            result.selection,
        )
    }

    @Test
    fun clickingFullySelectedCategoryClearsOnlyThatCategory() {
        val result = toggleAiContextGroup(
            currentSelection = linkedSetOf("diary:1", "thought:1", "thought:2"),
            groupKeys = listOf("thought:1", "thought:2"),
            maxItems = 50,
        )

        assertFalse(result.limitExceeded)
        assertEquals(setOf("diary:1"), result.selection)
    }

    @Test
    fun overLimitCategoryIsRejectedAtomically() {
        val original = (1..49).mapTo(linkedSetOf()) { "existing:$it" }
        val result = toggleAiContextGroup(
            currentSelection = original,
            groupKeys = listOf("thought:1", "thought:2", "thought:2"),
            maxItems = 50,
        )

        assertTrue(result.limitExceeded)
        assertEquals(51, result.resultingItemCount)
        assertEquals(original, result.selection)
    }
}
