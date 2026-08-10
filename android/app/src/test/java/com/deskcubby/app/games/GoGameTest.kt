package com.deskcubby.app.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoGameTest {
    @Test
    fun `players alternate and surrounded stones are captured`() {
        val game = gameFrom(
            current = GoGame.Stone.BLACK,
            stones = mapOf(
                point(1, 1) to GoGame.Stone.WHITE,
                point(0, 1) to GoGame.Stone.BLACK,
                point(1, 0) to GoGame.Stone.BLACK,
                point(2, 1) to GoGame.Stone.BLACK,
            ),
        )

        val result = game.play(1, 2)

        assertTrue(result.accepted)
        assertEquals(1, result.captured)
        assertEquals(1, result.statisticsDelta.movesPlayed)
        assertEquals(1, result.statisticsDelta.stonesCaptured)
        assertEquals(GoGame.Stone.EMPTY, game.stoneAt(1, 1))
        assertEquals(GoGame.Stone.BLACK, game.stoneAt(1, 2))
        assertEquals(GoGame.Stone.WHITE, game.currentPlayer)
        assertEquals(1, game.capturedByBlack)
    }

    @Test
    fun `suicide is rejected without changing the board or turn`() {
        val game = gameFrom(
            current = GoGame.Stone.WHITE,
            stones = mapOf(
                point(1, 0) to GoGame.Stone.BLACK,
                point(0, 1) to GoGame.Stone.BLACK,
                point(2, 1) to GoGame.Stone.BLACK,
                point(1, 2) to GoGame.Stone.BLACK,
            ),
        )
        val before = game.boardSnapshot()

        val result = game.play(1, 1)

        assertFalse(result.accepted)
        assertEquals(GoGame.MoveError.SUICIDE, result.error)
        assertEquals(before, game.boardSnapshot())
        assertEquals(GoGame.Stone.WHITE, game.currentPlayer)
        assertEquals(0, game.turnCount)
    }

    @Test
    fun `simple ko rejects an immediate position repetition`() {
        val game = gameFrom(
            current = GoGame.Stone.BLACK,
            stones = mapOf(
                point(1, 0) to GoGame.Stone.BLACK,
                point(0, 1) to GoGame.Stone.BLACK,
                point(2, 1) to GoGame.Stone.BLACK,
                point(1, 1) to GoGame.Stone.WHITE,
                point(0, 2) to GoGame.Stone.WHITE,
                point(2, 2) to GoGame.Stone.WHITE,
                point(1, 3) to GoGame.Stone.WHITE,
            ),
        )

        assertTrue(game.play(1, 2).accepted)
        val recapture = game.play(1, 1)

        assertFalse(recapture.accepted)
        assertEquals(GoGame.MoveError.KO, recapture.error)
        assertEquals(GoGame.Stone.EMPTY, game.stoneAt(1, 1))
        assertEquals(GoGame.Stone.BLACK, game.stoneAt(1, 2))
        assertEquals(GoGame.Stone.WHITE, game.currentPlayer)
    }

    @Test
    fun `two consecutive passes finish and lock the game`() {
        val game = GoGame()

        val first = game.pass()
        val second = game.pass()

        assertTrue(first.accepted)
        assertEquals(1, first.statisticsDelta.passes)
        assertFalse(game.play(4, 4).accepted)
        assertTrue(second.accepted)
        assertEquals(1, second.statisticsDelta.gamesCompleted)
        assertTrue(game.isFinished)
        assertEquals(2, game.consecutivePasses)
        assertEquals(GoGame.MoveError.GAME_FINISHED, game.play(4, 4).error)
    }

    @Test
    fun `save round trip preserves ko history and counters`() {
        val game = gameFrom(
            size = 13,
            current = GoGame.Stone.BLACK,
            capturedByBlack = 7,
            capturedByWhite = 4,
            stones = mapOf(
                point(1, 0) to GoGame.Stone.BLACK,
                point(0, 1) to GoGame.Stone.BLACK,
                point(2, 1) to GoGame.Stone.BLACK,
                point(1, 1) to GoGame.Stone.WHITE,
                point(0, 2) to GoGame.Stone.WHITE,
                point(2, 2) to GoGame.Stone.WHITE,
                point(1, 3) to GoGame.Stone.WHITE,
            ),
        )
        assertTrue(game.play(1, 2).accepted)

        val restored = GoGame.fromJson(game.toJson())

        assertNotNull(restored)
        restored!!
        assertEquals(13, restored.size)
        assertEquals(8, restored.capturedByBlack)
        assertEquals(4, restored.capturedByWhite)
        assertEquals(game.boardSnapshot(), restored.boardSnapshot())
        assertEquals(game.lastMove, restored.lastMove)
        assertEquals(GoGame.MoveError.KO, restored.play(1, 1).error)
    }

    @Test
    fun `snapshot copy is detached and preserves the visible position`() {
        val original = GoGame()
        assertTrue(original.play(4, 4).accepted)

        val snapshot = original.snapshotCopy()

        assertNotSame(original, snapshot)
        assertEquals(original.boardSnapshot(), snapshot.boardSnapshot())
        assertEquals(original.currentPlayer, snapshot.currentPlayer)
        assertEquals(original.lastMove, snapshot.lastMove)
        assertEquals(original.turnCount, snapshot.turnCount)

        assertTrue(snapshot.play(0, 0).accepted)
        assertEquals(GoGame.Stone.EMPTY, original.stoneAt(0, 0))
        assertEquals(GoGame.Stone.WHITE, snapshot.stoneAt(0, 0))
        assertEquals(1, original.turnCount)
        assertEquals(2, snapshot.turnCount)
    }

    @Test
    fun `corrupt and unsupported saves are rejected`() {
        assertNull(GoGame.fromJson("not-json"))
        assertNull(GoGame.fromJson("""{"v":1,"size":7}"""))
        assertNull(
            GoGame.fromJson(
                gameJson(
                    size = 9,
                    board = IntArray(81) { 9 },
                    current = GoGame.Stone.BLACK,
                ),
            ),
        )
    }

    private fun gameFrom(
        size: Int = 9,
        current: GoGame.Stone,
        stones: Map<Pair<Int, Int>, GoGame.Stone>,
        capturedByBlack: Int = 0,
        capturedByWhite: Int = 0,
    ): GoGame {
        val board = IntArray(size * size)
        stones.forEach { (point, stone) ->
            board[point.second * size + point.first] = stone.code
        }
        return requireNotNull(
            GoGame.fromJson(
                gameJson(
                    size = size,
                    board = board,
                    current = current,
                    capturedByBlack = capturedByBlack,
                    capturedByWhite = capturedByWhite,
                ),
            ),
        )
    }

    private fun gameJson(
        size: Int,
        board: IntArray,
        current: GoGame.Stone,
        capturedByBlack: Int = 0,
        capturedByWhite: Int = 0,
    ): String = buildString {
        append("{\"v\":1,\"size\":").append(size)
        append(",\"board\":[")
        board.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append("],\"current\":").append(current.code)
        append(",\"capturedByBlack\":").append(capturedByBlack)
        append(",\"capturedByWhite\":").append(capturedByWhite)
        append(",\"passes\":0,\"finished\":false,\"turnCount\":0")
        append(",\"previousBoard\":null,\"lastMove\":null}")
    }

    private fun point(x: Int, y: Int): Pair<Int, Int> = x to y
}
