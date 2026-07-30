package com.deskcubby.app.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEnginesTest {

    // ------------------------------------------------------------------ 2048

    private fun game2048Json(cells: List<Int>, score: Int = 0): String =
        """{"cells":[${cells.joinToString(",")}],"score":$score}"""

    @Test
    fun game2048MoveLeftMergesEachPairOnceAndScoresTheMerges() {
        val cells = listOf(
            2, 2, 4, 4,
            2, 0, 2, 2,
            0, 0, 0, 0,
            8, 8, 8, 8,
        )
        val game = Game2048.fromJson(game2048Json(cells), Random(7))!!

        assertTrue(game.move(Game2048.Direction.LEFT))

        val board = game.board
        // Row 0: [2,2,4,4] -> [4,8]
        assertEquals(4, board[0])
        assertEquals(8, board[1])
        // Row 1: [2,2,2] merges only the leading pair -> [4,2]
        assertEquals(4, board[4])
        assertEquals(2, board[5])
        // Row 3: [8,8,8,8] -> [16,16], never [32]
        assertEquals(16, board[12])
        assertEquals(16, board[13])
        // Merge score: 4 + 8 + 4 + 16 + 16 = 48
        assertEquals(48, game.score)
        // Six tiles remain after merging plus exactly one spawned tile.
        assertEquals(7, board.count { it != 0 })
        assertTrue(board.all { it == 0 || (it >= 2 && Integer.bitCount(it) == 1) })
    }

    @Test
    fun game2048MoveThatChangesNothingSpawnsNoTile() {
        val cells = listOf(
            2, 4, 8, 16,
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        val game = Game2048.fromJson(game2048Json(cells, score = 5), Random(7))!!

        assertFalse(game.move(Game2048.Direction.LEFT))

        assertEquals(cells, game.board)
        assertEquals(5, game.score)
        assertFalse(game.isGameOver)
    }

    @Test
    fun game2048FullBoardWithoutMergesIsGameOver() {
        val cells = listOf(
            2, 4, 2, 4,
            4, 2, 4, 2,
            2, 4, 2, 4,
            4, 2, 4, 2,
        )
        val game = Game2048.fromJson(game2048Json(cells, score = 10), Random(1))!!

        assertTrue(game.isGameOver)
        assertFalse(game.move(Game2048.Direction.LEFT))
        assertFalse(game.move(Game2048.Direction.UP))
        assertEquals(cells, game.board)
        assertEquals(10, game.score)
    }

    // ----------------------------------------------------------------- Snake

    private fun snakeJson(
        snake: List<Pair<Int, Int>>,
        dir: String = "RIGHT",
        food: Pair<Int, Int> = 0 to 0,
        score: Int = 0,
    ): String {
        val body = snake.joinToString(",") { "[${it.first},${it.second}]" }
        return """{"w":16,"h":16,"snake":[$body],"dir":"$dir","food":[${food.first},${food.second}],""" +
            """"score":$score,"over":false}"""
    }

    @Test
    fun snakeRejectsReverseDirectionButAcceptsTurns() {
        val game = SnakeGame.fromJson(snakeJson(listOf(8 to 8, 7 to 8, 6 to 8)), Random(3))!!

        game.setDirection(SnakeGame.Direction.LEFT)
        assertEquals(SnakeGame.Direction.RIGHT, game.direction)
        assertTrue(game.tick())
        assertEquals(SnakeGame.Cell(9, 8), game.snake.first())

        game.setDirection(SnakeGame.Direction.UP)
        assertTrue(game.tick())
        assertEquals(SnakeGame.Cell(9, 7), game.snake.first())
    }

    @Test
    fun snakeDiesWhenHittingTheWall() {
        val game = SnakeGame.fromJson(snakeJson(listOf(15 to 8, 14 to 8)), Random(3))!!

        assertFalse(game.tick())

        assertTrue(game.isGameOver)
        assertEquals(SnakeGame.Cell(15, 8), game.snake.first())
        assertFalse(game.tick())
    }

    @Test
    fun snakeEatingFoodScoresGrowsAndRespawnsFood() {
        val game = SnakeGame.fromJson(
            snakeJson(listOf(8 to 8, 7 to 8, 6 to 8), food = 9 to 8, score = 20),
            Random(3),
        )!!

        assertTrue(game.tick())

        assertEquals(20 + SnakeGame.EAT_SCORE, game.score)
        assertEquals(4, game.snake.size)
        assertEquals(SnakeGame.Cell(9, 8), game.snake.first())
        assertNotEquals(SnakeGame.Cell(9, 8), game.food)
        assertFalse(game.snake.contains(game.food))
        assertTrue(game.food.x in 0 until game.width && game.food.y in 0 until game.height)
    }

    // ---------------------------------------------------------------- Tetris

    private fun tetrisJson(
        board: IntArray,
        type: Int,
        rot: Int,
        x: Int,
        y: Int,
        next: Int = 1,
        score: Int = 0,
        lines: Int = 0,
    ): String =
        """{"board":[${board.joinToString(",")}],"score":$score,"lines":$lines,"type":$type,""" +
            """"rot":$rot,"x":$x,"y":$y,"next":$next,"over":false}"""

    @Test
    fun tetrisSingleLineClearScores100AndRaisesLevel() {
        // Bottom row filled except columns 3..6; a horizontal I piece hovers right above them.
        val board = IntArray(TetrisGame.WIDTH * TetrisGame.HEIGHT)
        for (x in intArrayOf(0, 1, 2, 7, 8, 9)) board[19 * TetrisGame.WIDTH + x] = 3
        val game = TetrisGame.fromJson(
            tetrisJson(board, type = 0, rot = 0, x = 3, y = 18, lines = 9),
            Random(5),
        )!!

        game.hardDrop()

        assertEquals(100, game.score)
        assertEquals(10, game.lines)
        assertEquals(1, game.level)
        assertTrue((0 until TetrisGame.WIDTH).all { game.boardCell(it, 19) == 0 })
        assertFalse(game.isGameOver)
    }

    @Test
    fun tetrisDoubleLineClearScores300() {
        // Two bottom rows filled except columns 4 and 5; an O piece drops into the gap.
        val board = IntArray(TetrisGame.WIDTH * TetrisGame.HEIGHT)
        for (y in intArrayOf(18, 19)) {
            for (x in 0 until TetrisGame.WIDTH) {
                if (x != 4 && x != 5) board[y * TetrisGame.WIDTH + x] = 2
            }
        }
        val game = TetrisGame.fromJson(tetrisJson(board, type = 1, rot = 0, x = 4, y = 0), Random(5))!!

        game.hardDrop()

        assertEquals(300, game.score)
        assertEquals(2, game.lines)
        assertTrue((0 until TetrisGame.WIDTH).all { game.boardCell(it, 18) == 0 })
        assertTrue((0 until TetrisGame.WIDTH).all { game.boardCell(it, 19) == 0 })
    }

    // ------------------------------------------------------------ Round trips

    @Test
    fun game2048JsonRoundTripPreservesState() {
        val game = Game2048(Random(11))
        game.move(Game2048.Direction.LEFT)
        game.move(Game2048.Direction.UP)

        val json = game.toJson()
        val restored = Game2048.fromJson(json, Random(1))!!

        assertEquals(json, restored.toJson())
        assertEquals(game.board, restored.board)
        assertEquals(game.score, restored.score)
        assertEquals(game.canUndo, restored.canUndo)
    }

    @Test
    fun game2048UndoRestoresEveryPriorBoardAndScore() {
        val cells = listOf(
            2, 2, 0, 0,
            4, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        val game = Game2048.fromJson(game2048Json(cells, score = 12), Random(7))!!

        assertTrue(game.move(Game2048.Direction.LEFT))
        val afterFirstMove = game.board
        val afterFirstScore = game.score
        assertTrue(game.move(Game2048.Direction.DOWN))
        assertTrue(game.canUndo)
        assertTrue(game.undo())

        assertEquals(afterFirstMove, game.board)
        assertEquals(afterFirstScore, game.score)
        assertTrue(game.canUndo)
        assertTrue(game.undo())
        assertEquals(cells, game.board)
        assertEquals(12, game.score)
        assertFalse(game.canUndo)
        assertFalse(game.undo())
    }

    @Test
    fun game2048NoOpDoesNotDiscardAvailableUndo() {
        val previous = listOf(
            2, 2, 0, 0,
            4, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        val current = listOf(
            4, 2, 0, 0,
            4, 0, 0, 0,
            0, 0, 0, 0,
            0, 0, 0, 0,
        )
        val game = Game2048.fromJson(
            """{"size":4,"cells":[${current.joinToString(",")}],"score":4,""" +
                """"undoCells":[${previous.joinToString(",")}],"undoScore":0}""",
            Random(7),
        )!!

        val afterMove = game.board
        assertFalse(game.move(Game2048.Direction.LEFT))
        assertEquals(afterMove, game.board)
        assertTrue(game.canUndo)
        assertTrue(game.undo())
        assertEquals(previous, game.board)
    }

    @Test
    fun game2048SavePreservesUndoAcrossAllBoardSizes() {
        listOf(4, 5, 6).forEach { size ->
            val cells = MutableList(size * size) { 0 }.apply {
                this[0] = 2
                this[1] = 2
            }
            val game = Game2048.fromJson(
                """{"size":$size,"cells":[${cells.joinToString(",")}],"score":8}""",
                Random(size),
                expectedSize = size,
            )!!
            assertTrue(game.move(Game2048.Direction.LEFT))

            val restored = Game2048.fromJson(
                game.toJson(),
                Random(99),
                expectedSize = size,
            )!!

            assertTrue(restored.canUndo)
            assertTrue(restored.undo())
            assertEquals(cells, restored.board)
            assertEquals(8, restored.score)
        }
    }

    @Test
    fun game2048SupportsFiveAndSixCellBoards() {
        listOf(5, 6).forEach { size ->
            val game = Game2048(size, Random(size))
            val restored = Game2048.fromJson(
                game.toJson(),
                random = Random(1),
                expectedSize = size,
            )!!

            assertEquals(size, restored.size)
            assertEquals(size * size, restored.board.size)
            assertEquals(game.board, restored.board)
        }
    }

    @Test
    fun legacy2048SaveDefaultsToFourByFourAndRejectsWrongVariant() {
        val legacy = game2048Json(List(16) { 0 })

        assertEquals(4, Game2048.fromJson(legacy)!!.size)
        assertNull(Game2048.fromJson(legacy, expectedSize = 5))
    }

    @Test
    fun snakeJsonRoundTripPreservesState() {
        val game = SnakeGame(random = Random(11))
        game.setDirection(SnakeGame.Direction.DOWN)
        game.tick()

        val json = game.toJson()
        val restored = SnakeGame.fromJson(json, Random(1))!!

        assertEquals(json, restored.toJson())
        assertEquals(game.snake, restored.snake)
        assertEquals(game.direction, restored.direction)
        assertEquals(game.food, restored.food)
        assertEquals(game.score, restored.score)
        assertEquals(game.isGameOver, restored.isGameOver)
    }

    @Test
    fun tetrisJsonRoundTripPreservesState() {
        val game = TetrisGame(Random(11))
        game.moveLeft()
        game.rotate()
        game.tick()

        val json = game.toJson()
        val restored = TetrisGame.fromJson(json, Random(1))!!

        assertEquals(json, restored.toJson())
        assertEquals(game.boardSnapshot(), restored.boardSnapshot())
        assertEquals(game.currentPieceCells(), restored.currentPieceCells())
        assertEquals(game.nextPieceType, restored.nextPieceType)
        assertEquals(game.score, restored.score)
        assertEquals(game.lines, restored.lines)
    }

    @Test
    fun invalidJsonReturnsNullInsteadOfThrowing() {
        assertNull(Game2048.fromJson("not json"))
        assertNull(Game2048.fromJson("""{"cells":[1,2],"score":0}"""))
        assertNull(SnakeGame.fromJson("""{"w":16}"""))
        assertNull(SnakeGame.fromJson(snakeJson(listOf(99 to 99))))
        assertNull(TetrisGame.fromJson("{}"))
        assertNull(TetrisGame.fromJson(""))
    }
}
