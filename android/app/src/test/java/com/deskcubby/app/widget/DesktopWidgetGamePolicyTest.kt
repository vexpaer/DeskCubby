package com.deskcubby.app.widget

import com.deskcubby.app.games.Game2048
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopWidgetGamePolicyTest {
    @Test
    fun desktop2048AcceptsOnlyFourDirections() {
        assertEquals(Game2048.Direction.UP, widget2048Direction(WidgetGameAction.UP))
        assertEquals(Game2048.Direction.DOWN, widget2048Direction(WidgetGameAction.DOWN))
        assertEquals(Game2048.Direction.LEFT, widget2048Direction(WidgetGameAction.LEFT))
        assertEquals(Game2048.Direction.RIGHT, widget2048Direction(WidgetGameAction.RIGHT))
    }

    @Test
    fun desktop2048RejectsNewAndEveryNonMovementAction() {
        assertNull(widget2048Direction(WidgetGameAction.NEW))
        assertNull(widget2048Direction(WidgetGameAction.UNDO))
        assertNull(widget2048Direction(WidgetGameAction.CELL))
        assertNull(widget2048Direction(null))
    }
}
