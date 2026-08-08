package com.deskcubby.app.data.statistics

import com.deskcubby.app.data.local.GameStatisticEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStatisticCatalogTest {

    @Test
    fun `2048 accepts move attempts and preserves legacy loss rows`() {
        for (gameId in listOf("2048", "2048_5", "2048_6")) {
            assertTrue(GameStatisticCatalog.supports(gameId, GameStatisticMetric.MOVE_ATTEMPTS))
            assertTrue(GameStatisticCatalog.supports(gameId, GameStatisticMetric.LOSSES))
            assertTrue(GameStatisticCatalog.isActive(gameId, GameStatisticMetric.MOVE_ATTEMPTS))
            assertFalse(GameStatisticCatalog.isActive(gameId, GameStatisticMetric.LOSSES))
        }
    }

    @Test
    fun `legacy 2048 losses stay out of runtime statistics`() {
        val metrics = snapshotOf(
            listOf(
                GameStatisticEntity("2048", GameStatisticMetric.LOSSES, 3L, 1L),
                GameStatisticEntity("2048", GameStatisticMetric.EFFECTIVE_MOVES, 7L, 2L),
            ),
        ).byGameId.getValue("2048")

        assertEquals(7L, metrics.value(GameStatisticMetric.EFFECTIVE_MOVES))
        assertFalse(metrics.asMap().containsKey(GameStatisticMetric.LOSSES))
    }
}
