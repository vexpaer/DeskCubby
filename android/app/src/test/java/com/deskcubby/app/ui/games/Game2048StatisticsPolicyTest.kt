package com.deskcubby.app.ui.games

import com.deskcubby.app.data.statistics.GameStatisticMetric
import com.deskcubby.app.games.Game2048
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Game2048StatisticsPolicyTest {

    @Test
    fun `blocked direction still counts as one move attempt`() {
        assertEquals(
            mapOf(GameStatisticMetric.MOVE_ATTEMPTS to 1L),
            game2048StatisticIncrements(delta = null),
        )
    }

    @Test
    fun `effective direction adds gameplay metrics but no legacy loss`() {
        val increments = game2048StatisticIncrements(
            Game2048.StatisticsDelta(
                effectiveMoves = 1,
                merges = 2,
                highestTile = 4_096,
                wins = 1,
                losses = 1,
            ),
        )

        assertEquals(1L, increments[GameStatisticMetric.MOVE_ATTEMPTS])
        assertEquals(1L, increments[GameStatisticMetric.EFFECTIVE_MOVES])
        assertEquals(2L, increments[GameStatisticMetric.MERGES])
        assertEquals(1L, increments[GameStatisticMetric.WINS])
        assertFalse(increments.containsKey(GameStatisticMetric.LOSSES))
    }
}
