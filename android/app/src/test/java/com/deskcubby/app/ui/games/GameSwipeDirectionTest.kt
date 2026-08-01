package com.deskcubby.app.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSwipeDirectionTest {
    @Test
    fun dominantAxisRecognizesAllFourDirections() {
        assertEquals(SwipeDirection.LEFT, swipeDirectionForDrag(-80f, 12f, 42f))
        assertEquals(SwipeDirection.RIGHT, swipeDirectionForDrag(80f, -12f, 42f))
        assertEquals(SwipeDirection.UP, swipeDirectionForDrag(12f, -80f, 42f))
        assertEquals(SwipeDirection.DOWN, swipeDirectionForDrag(-12f, 80f, 42f))
    }

    @Test
    fun dragMustCrossThresholdOnAtLeastOneAxis() {
        assertNull(swipeDirectionForDrag(41f, -41f, 42f))
    }
}
