package com.deskcubby.app.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicGameStatisticsEventsTest {

    @Test
    fun `2048 reports effective moves merges and highest tile`() {
        val game = game2048(
            listOf(
                2, 2, 4, 4,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        )

        val result = requireNotNull(game.moveWithResult(Game2048.Direction.LEFT))

        assertEquals(
            Game2048.StatisticsDelta(
                effectiveMoves = 1,
                merges = 2,
                highestTile = 8,
                wins = 0,
                losses = 0,
            ),
            result.statisticsDelta,
        )
    }

    @Test
    fun `2048 win is emitted once across undo and save restore`() {
        val before = listOf(
            1024, 1024, 4, 8,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        val game = game2048(before)

        val firstWin = requireNotNull(game.moveWithResult(Game2048.Direction.LEFT))
        assertEquals(1, firstWin.statisticsDelta.wins)
        assertEquals(2048, firstWin.statisticsDelta.highestTile)

        assertTrue(game.undo())
        val restored = requireNotNull(Game2048.fromJson(game.toJson(), ZeroRandom))
        val replayedWin = requireNotNull(restored.moveWithResult(Game2048.Direction.LEFT))

        assertEquals(0, replayedWin.statisticsDelta.wins)
    }

    @Test
    fun `2048 terminal move emits one loss even when replayed after undo`() {
        val game = game2048(
            listOf(
                4, 2, 4, 0,
                4, 2, 4, 2,
                2, 4, 2, 4,
                4, 2, 4, 2,
            ),
        )

        val finished = requireNotNull(game.moveWithResult(Game2048.Direction.RIGHT))
        assertTrue(game.isGameOver)
        assertEquals(1, finished.statisticsDelta.losses)

        assertTrue(game.undo())
        val replayed = requireNotNull(game.moveWithResult(Game2048.Direction.RIGHT))
        assertTrue(game.isGameOver)
        assertEquals(0, replayed.statisticsDelta.losses)
    }

    @Test
    fun `snake eating reports food and maximum length candidate`() {
        val game = requireNotNull(
            SnakeGame.fromJson(
                snakeJson(
                    width = 16,
                    height = 16,
                    body = listOf(8 to 8, 7 to 8, 6 to 8),
                    direction = "RIGHT",
                    food = 9 to 8,
                ),
                Random(3),
            ),
        )

        val result = game.tickWithResult()

        assertTrue(result.moved)
        assertTrue(result.ateFood)
        assertEquals(null, result.endReason)
        assertEquals(
            SnakeGame.StatisticsDelta(foodEaten = 1, maxLength = 4, losses = 0),
            result.statisticsDelta,
        )
    }

    @Test
    fun `snake collision emits exactly one loss`() {
        val game = requireNotNull(
            SnakeGame.fromJson(
                snakeJson(
                    width = 16,
                    height = 16,
                    body = listOf(15 to 8, 14 to 8, 13 to 8),
                    direction = "RIGHT",
                    food = 0 to 0,
                ),
                Random(3),
            ),
        )

        val collision = game.tickWithResult()
        assertEquals(SnakeGame.EndReason.WALL, collision.endReason)
        assertEquals(3, collision.statisticsDelta.maxLength)
        assertEquals(1, collision.statisticsDelta.losses)

        val repeated = game.tickWithResult()
        assertEquals(null, repeated.endReason)
        assertTrue(repeated.statisticsDelta.isEmpty)
    }

    @Test
    fun `snake filling board is completion and not a collision loss`() {
        val body = buildList {
            add(1 to 0)
            for (y in 0 until 4) {
                for (x in 0 until 4) {
                    if ((x to y) != (0 to 0) && (x to y) != (1 to 0)) add(x to y)
                }
            }
        }
        val game = requireNotNull(
            SnakeGame.fromJson(
                snakeJson(
                    width = 4,
                    height = 4,
                    body = body,
                    direction = "LEFT",
                    food = 0 to 0,
                ),
                Random(3),
            ),
        )

        val result = game.tickWithResult()

        assertEquals(SnakeGame.EndReason.BOARD_FILLED, result.endReason)
        assertEquals(1, result.statisticsDelta.foodEaten)
        assertEquals(16, result.statisticsDelta.maxLength)
        assertEquals(0, result.statisticsDelta.losses)
    }

    @Test
    fun `tetris lock reports pieces lines and a tetris`() {
        val board = IntArray(TetrisGame.WIDTH * TetrisGame.HEIGHT)
        for (y in 16..19) {
            for (x in 0 until TetrisGame.WIDTH) {
                if (x != 4) board[y * TetrisGame.WIDTH + x] = 2
            }
        }
        val game = requireNotNull(
            TetrisGame.fromJson(
                tetrisJson(board, type = 0, rotation = 1, x = 2, y = 16, next = 1),
                Random(7),
            ),
        )

        val result = game.hardDropWithResult()

        assertTrue(result.lockedPiece)
        assertEquals(4, result.linesCleared)
        assertEquals(
            TetrisGame.StatisticsDelta(
                piecesLocked = 1,
                linesCleared = 4,
                tetrises = 1,
                losses = 0,
            ),
            result.statisticsDelta,
        )
    }

    @Test
    fun `tetris blocked next spawn emits exactly one loss`() {
        val board = IntArray(TetrisGame.WIDTH * TetrisGame.HEIGHT).apply {
            this[0 * TetrisGame.WIDTH + 4] = 3
            this[0 * TetrisGame.WIDTH + 5] = 3
            this[1 * TetrisGame.WIDTH + 4] = 3
            this[1 * TetrisGame.WIDTH + 5] = 3
        }
        val game = requireNotNull(
            TetrisGame.fromJson(
                tetrisJson(board, type = 1, rotation = 0, x = 4, y = 18, next = 1),
                Random(7),
            ),
        )

        val finished = game.hardDropWithResult()
        assertTrue(finished.gameEnded)
        assertEquals(1, finished.statisticsDelta.piecesLocked)
        assertEquals(1, finished.statisticsDelta.losses)

        val repeated = game.hardDropWithResult()
        assertTrue(repeated.gameEnded)
        assertFalse(repeated.lockedPiece)
        assertTrue(repeated.statisticsDelta.isEmpty)
    }

    private fun game2048(cells: List<Int>): Game2048 = requireNotNull(
        Game2048.fromJson(
            """{"cells":[${cells.joinToString(",")}],"score":0}""",
            ZeroRandom,
        ),
    )

    private fun snakeJson(
        width: Int,
        height: Int,
        body: List<Pair<Int, Int>>,
        direction: String,
        food: Pair<Int, Int>,
    ): String {
        val cells = body.joinToString(",") { "[${it.first},${it.second}]" }
        return """{"w":$width,"h":$height,"snake":[$cells],"dir":"$direction","food":[${food.first},${food.second}],"score":0,"over":false}"""
    }

    private fun tetrisJson(
        board: IntArray,
        type: Int,
        rotation: Int,
        x: Int,
        y: Int,
        next: Int,
    ): String =
        """{"board":[${board.joinToString(",")}],"score":0,"lines":0,"type":$type,"rot":$rotation,"x":$x,"y":$y,"next":$next,"over":false}"""

    private object ZeroRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }
}
