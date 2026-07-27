package com.deskcubby.app.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Game2048TransitionTest {

    private fun game(cells: List<Int>, score: Int = 0, seed: Int = 19): Game2048 =
        Game2048.fromJson(
            """{"cells":[${cells.joinToString(",")}],"score":$score}""",
            Random(seed),
        )!!

    @Test
    fun moveResultMapsEverySourceTileToItsAnimatedDestination() {
        val before = listOf(
            2, 0, 2, 4,
            8, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        val game = game(before, score = 12)

        val result = requireNotNull(game.moveWithResult(Game2048.Direction.LEFT))

        assertEquals(before, result.before)
        assertEquals(
            listOf(
                Game2048.TileMotion(0, 0, 2, merged = true),
                Game2048.TileMotion(2, 0, 2, merged = true),
                Game2048.TileMotion(3, 1, 4, merged = false),
                Game2048.TileMotion(4, 4, 8, merged = false),
            ),
            result.motions,
        )
        assertEquals(listOf(Game2048.Merge(toIndex = 0, value = 4)), result.merges)
        assertEquals(4, result.scoreGained)
        assertEquals(16, game.score)

        // The committed board and animation result are the same immutable final snapshot.
        assertEquals(game.board, result.after)
        assertEquals(result.spawn.value, result.after[result.spawn.index])
        assertTrue(result.spawn.value == 2 || result.spawn.value == 4)
        assertEquals(4, result.after[0])
        assertEquals(4, result.after[1])
        assertEquals(8, result.after[4])
    }

    @Test
    fun moveResultPreservesEdgeOrderForTwoIndependentRightMerges() {
        val game = game(
            listOf(
                2, 2, 2, 2,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        )

        val result = requireNotNull(game.moveWithResult(Game2048.Direction.RIGHT))

        assertEquals(
            listOf(
                Game2048.TileMotion(3, 3, 2, merged = true),
                Game2048.TileMotion(2, 3, 2, merged = true),
                Game2048.TileMotion(1, 2, 2, merged = true),
                Game2048.TileMotion(0, 2, 2, merged = true),
            ),
            result.motions.take(4),
        )
        assertEquals(
            listOf(
                Game2048.Merge(toIndex = 3, value = 4),
                Game2048.Merge(toIndex = 2, value = 4),
            ),
            result.merges.take(2),
        )
        assertEquals(8, result.scoreGained)
        assertEquals(4, result.after[2])
        assertEquals(4, result.after[3])
        assertEquals((0..3).toSet(), result.motions.take(4).map { it.fromIndex }.toSet())
    }

    @Test
    fun invalidMoveEmitsNoTransitionAndDoesNotConsumeSpawnRandomness() {
        val cells = listOf(
            2, 4, 8, 16,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        val afterNoOp = game(cells, score = 9, seed = 73)
        val direct = game(cells, score = 9, seed = 73)

        assertNull(afterNoOp.moveWithResult(Game2048.Direction.LEFT))
        assertEquals(cells, afterNoOp.board)
        assertEquals(9, afterNoOp.score)

        val afterNoOpResult = requireNotNull(afterNoOp.moveWithResult(Game2048.Direction.DOWN))
        val directResult = requireNotNull(direct.moveWithResult(Game2048.Direction.DOWN))
        assertEquals(directResult.spawn, afterNoOpResult.spawn)
        assertEquals(directResult.after, afterNoOpResult.after)
    }
}
