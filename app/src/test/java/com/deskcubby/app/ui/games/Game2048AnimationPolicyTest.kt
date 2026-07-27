package com.deskcubby.app.ui.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Game2048AnimationPolicyTest {
    @Test
    fun animatesOnlyWhenPageTransitionAndSystemAnimationsAreEnabled() {
        assertTrue(
            shouldAnimate2048Transition(
                animate = true,
                hasTransition = true,
                systemAnimationsEnabled = true,
            ),
        )
        assertFalse(
            shouldAnimate2048Transition(
                animate = true,
                hasTransition = true,
                systemAnimationsEnabled = false,
            ),
        )
        assertFalse(
            shouldAnimate2048Transition(
                animate = false,
                hasTransition = true,
                systemAnimationsEnabled = true,
            ),
        )
        assertFalse(
            shouldAnimate2048Transition(
                animate = true,
                hasTransition = false,
                systemAnimationsEnabled = true,
            ),
        )
    }
}
