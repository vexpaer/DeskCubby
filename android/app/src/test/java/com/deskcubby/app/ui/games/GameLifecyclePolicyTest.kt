package com.deskcubby.app.ui.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLifecyclePolicyTest {
    @Test
    fun `play time counts only an initialized actively playing board`() {
        assertTrue(shouldCountGamePlay(engineReady = true))
        assertFalse(shouldCountGamePlay(engineReady = false))
        assertFalse(shouldCountGamePlay(engineReady = true, paused = true))
        assertFalse(shouldCountGamePlay(engineReady = true, finished = true))
        assertFalse(shouldCountGamePlay(engineReady = true, setupVisible = true))
    }

    @Test
    fun `landscape lock survives recreation and clears on normal page exit`() {
        assertFalse(
            shouldRestoreGameOrientation(
                isFinishing = false,
                isChangingConfigurations = true,
            ),
        )
        assertFalse(
            shouldRestoreGameOrientation(
                isFinishing = true,
                isChangingConfigurations = false,
            ),
        )
        assertTrue(
            shouldRestoreGameOrientation(
                isFinishing = false,
                isChangingConfigurations = false,
            ),
        )
    }
}
