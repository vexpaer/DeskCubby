package com.deskcubby.app.games

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditionalGameEnginesTest {
    @Test
    fun `Minesweeper first reveal is safe and opens a zero-mine neighborhood`() {
        val game = MinesweeperGame(width = 9, height = 9, mineCount = 10, random = Random(7))

        val result = game.revealWithResult(4, 4)

        assertTrue(result.changed)
        val first = game.cell(4, 4)
        assertTrue(first.revealed)
        assertFalse(first.mine)
        assertEquals(0, first.adjacentMines)
        assertEquals(game.revealedSafeCount, result.statisticsDelta.minesCellsRevealed)
        assertEquals(0, result.statisticsDelta.losses)
    }

    @Test
    fun `Minesweeper respects the configured flag count and round trips JSON`() {
        val game = MinesweeperGame(width = 6, height = 6, mineCount = 1, random = Random(9))

        assertTrue(game.toggleFlag(0, 0))
        assertFalse(game.toggleFlag(1, 0))
        assertEquals(0, game.remainingMines)
        assertTrue(game.toggleFlag(0, 0))
        assertTrue(game.reveal(3, 3))

        val encoded = game.toJson()
        val restored = MinesweeperGame.fromJson(encoded, Random(1))

        assertNotNull(restored)
        assertEquals(encoded, restored!!.toJson())
    }

    @Test
    fun `Minesweeper rejects contradictory finished saves`() {
        assertNull(
            MinesweeperGame.fromJson(
                """{"w":6,"h":6,"count":1,"initialized":false,"over":true,"won":false,"mines":[],"revealed":[],"flagged":[]}""",
            ),
        )
    }

    @Test
    fun `Spider undo restores the board but retains that the round was played`() {
        val game = SpiderSolitaireGame(Random(11))
        val before = game.toJson()

        assertFalse(game.hasPlayedAction)
        assertEquals(5, game.stockDealsRemaining)
        assertTrue(game.canDealStock)
        assertTrue(game.dealStock())
        assertTrue(game.hasPlayedAction)
        assertEquals(4, game.stockDealsRemaining)
        assertTrue(game.canUndo)
        assertTrue(game.undo())
        assertTrue(game.hasPlayedAction)
        assertEquals(
            before.replace("\"hasPlayedAction\":false", "\"hasPlayedAction\":true"),
            game.toJson(),
        )
    }

    @Test
    fun `Spider statistics only report accepted actions and explicit abandonment`() {
        val game = SpiderSolitaireGame(Random(13))

        val untouchedAbandon = game.abandonWithResult()
        val invalidMove = game.moveWithResult(fromColumn = 0, cardIndex = 0, toColumn = 0)
        assertFalse(untouchedAbandon.changed)
        assertTrue(untouchedAbandon.statisticsDelta.isEmpty)
        assertFalse(invalidMove.changed)
        assertTrue(invalidMove.statisticsDelta.isEmpty)
        assertFalse(game.hasPlayedAction)

        val deal = game.dealStockWithResult()
        assertTrue(deal.changed)
        assertEquals(
            SpiderSolitaireGame.StatisticsDelta(deals = 1),
            deal.statisticsDelta,
        )

        val undo = game.undoWithResult()
        assertTrue(undo.changed)
        assertEquals(
            SpiderSolitaireGame.StatisticsDelta(undos = 1),
            undo.statisticsDelta,
        )

        val abandon = game.abandonWithResult()
        assertTrue(abandon.changed)
        assertEquals(
            SpiderSolitaireGame.StatisticsDelta(losses = 1),
            abandon.statisticsDelta,
        )
        assertFalse(game.abandonWithResult().changed)
    }

    @Test
    fun `Spider winning move reports one win even after undo and replay`() {
        val columns: List<List<Int>> =
            listOf(listOf(0), (12 downTo 1).toList()) + List(8) { emptyList() }
        val game = requireNotNull(
            SpiderSolitaireGame.fromJson(
                spiderJson(columns = columns, stock = emptyList(), completed = 7),
            ),
        )

        val firstWin = game.moveWithResult(fromColumn = 0, cardIndex = 0, toColumn = 1)

        assertTrue(firstWin.changed)
        assertTrue(game.isWon)
        assertEquals(
            SpiderSolitaireGame.StatisticsDelta(cardMoves = 1, wins = 1),
            firstWin.statisticsDelta,
        )
        assertTrue(game.outcomeRecorded)

        assertEquals(1, game.undoWithResult().statisticsDelta.undos)
        assertFalse(game.isWon)
        val replayedWin = game.moveWithResult(fromColumn = 0, cardIndex = 0, toColumn = 1)
        assertTrue(game.isWon)
        assertEquals(1, replayedWin.statisticsDelta.cardMoves)
        assertEquals(0, replayedWin.statisticsDelta.wins)
        assertFalse(game.abandonWithResult().changed)
    }

    @Test
    fun `Minesweeper chord only accepts a revealed positive number`() {
        val numbered = restoreMinesweeper(mines = listOf(0), revealed = listOf(7))
        val numberedBefore = numbered.toJson()

        val hiddenResult = numbered.chordWithResult(2, 2)

        assertFalse(hiddenResult.changed)
        assertEquals(numberedBefore, numbered.toJson())

        val zero = restoreMinesweeper(mines = listOf(35), revealed = listOf(0))
        val zeroBefore = zero.toJson()

        val zeroResult = zero.chordWithResult(0, 0)

        assertFalse(zeroResult.changed)
        assertEquals(zeroBefore, zero.toJson())
    }

    @Test
    fun `Minesweeper chord skips flags and winning delta counts the cleared mines`() {
        val game = restoreMinesweeper(
            mines = listOf(0),
            revealed = listOf(7),
            flagged = listOf(0),
        )

        val result = game.chordWithResult(1, 1)

        assertTrue(result.changed)
        assertTrue(game.isWon)
        assertFalse(game.isGameOver)
        assertTrue(game.cell(0, 0).flagged)
        assertFalse(game.cell(0, 0).revealed)
        assertEquals(34, result.statisticsDelta.minesCellsRevealed)
        assertEquals(1, result.statisticsDelta.minesSwept)
        assertEquals(1, result.statisticsDelta.wins)
        assertEquals(0, result.statisticsDelta.losses)
        assertNotNull(MinesweeperGame.fromJson(game.toJson()))
    }

    @Test
    fun `Minesweeper chord with a wrong flag exposes mines and records one loss`() {
        val game = restoreMinesweeper(mines = listOf(0), revealed = listOf(7))

        val result = game.chordWithResult(1, 1)

        assertTrue(result.changed)
        assertTrue(game.isGameOver)
        assertFalse(game.isWon)
        assertTrue(game.cell(0, 0).revealed)
        assertEquals(35, result.statisticsDelta.minesCellsRevealed)
        assertEquals(0, result.statisticsDelta.minesSwept)
        assertEquals(0, result.statisticsDelta.wins)
        assertEquals(1, result.statisticsDelta.losses)
        // A chord may expose the last safe cells and a mine atomically; this loss must remain a
        // valid resumable/inspectable serialized result.
        assertEquals(35, game.revealedSafeCount)
        assertNotNull(MinesweeperGame.fromJson(game.toJson()))

        val repeated = game.chordWithResult(1, 1)
        assertFalse(repeated.changed)
        assertEquals(0, repeated.statisticsDelta.losses)
    }

    @Test
    fun `Minesweeper statistics count placed flags without subtracting removals`() {
        val game = MinesweeperGame(width = 6, height = 6, mineCount = 1, random = Random(31))

        val placed = game.toggleFlagWithResult(0, 0)
        val removed = game.toggleFlagWithResult(0, 0)

        assertTrue(placed.changed)
        assertEquals(1, placed.statisticsDelta.flagsPlaced)
        assertTrue(removed.changed)
        assertEquals(0, removed.statisticsDelta.flagsPlaced)
    }

    @Test
    fun `Spider JSON round trip preserves undo history across recreation`() {
        val game = SpiderSolitaireGame(Random(17))
        val beforeDeal = game.toJson()
        assertTrue(game.dealStock())

        val saved = game.toJson()
        val restored = SpiderSolitaireGame.fromJson(saved)

        assertNotNull(restored)
        assertEquals(saved, restored!!.toJson())
        assertTrue(restored.canUndo)
        assertTrue(restored.undo())
        assertTrue(restored.hasPlayedAction)
        assertEquals(
            beforeDeal.replace("\"hasPlayedAction\":false", "\"hasPlayedAction\":true"),
            restored.toJson(),
        )
    }

    @Test
    fun `Spider still accepts legacy save without undo history`() {
        val versioned = SpiderSolitaireGame(Random(19)).toJson()
            .replaceFirst("\"schemaVersion\":2,", "")
        val legacy = versioned.substringBefore(",\"history\":") + "}"

        val restored = SpiderSolitaireGame.fromJson(legacy)

        assertNotNull(restored)
        assertFalse(restored!!.canUndo)
    }

    @Test
    fun `Spider legacy save derives played state and new save validates it strictly`() {
        val game = SpiderSolitaireGame(Random(29))
        assertTrue(game.dealStock())
        val withoutPlayedFlag = game.toJson().replace(",\"hasPlayedAction\":true", "")

        val restored = SpiderSolitaireGame.fromJson(withoutPlayedFlag)

        assertNotNull(restored)
        assertTrue(restored!!.hasPlayedAction)
        assertNull(
            SpiderSolitaireGame.fromJson(
                game.toJson().replace("\"hasPlayedAction\":true", "\"hasPlayedAction\":1"),
            ),
        )
        assertNull(
            SpiderSolitaireGame.fromJson(
                game.toJson().replace("\"outcomeRecorded\":false", "\"outcomeRecorded\":1"),
            ),
        )
    }

    @Test
    fun `Spider rejects oversized undo history and deeply nested JSON`() {
        val valid = SpiderSolitaireGame(Random(23)).toJson()
        val stateBody = valid
            .substringAfter("\"schemaVersion\":2,")
            .substringBefore(",\"history\":")
        val snapshot = "{$stateBody}"
        val excessiveHistory = valid.substringBefore("\"history\":[") +
            "\"history\":[" + List(101) { snapshot }.joinToString(",") + "]}"

        assertNull(SpiderSolitaireGame.fromJson(excessiveHistory))
        assertNull(SpiderSolitaireGame.fromJson("[".repeat(80) + "0" + "]".repeat(80)))
        assertNull(SpiderSolitaireGame.fromJson(valid + " ".repeat(1_100_000)))
    }

    @Test
    fun `Spider removes a completed run exposed in the source column`() {
        val columns = mutableListOf<List<Int>>()
        columns += (12 downTo 0).toList() + 13
        columns += listOf(14)
        columns += listOf(15, 23)
        for (id in 16..22) columns += listOf(id)
        val stock = (24..103).toList()
        val encoded = spiderJson(columns, stock)
        val game = SpiderSolitaireGame.fromJson(encoded)

        assertNotNull(game)
        assertTrue(game!!.move(fromColumn = 0, cardIndex = 13, toColumn = 1))
        assertEquals(1, game.completedRuns)
        assertTrue(game.column(0).isEmpty())
        assertEquals(599, game.score)
    }

    @Test
    fun `Spider rejects a card whose rank does not match its stable id`() {
        val valid = SpiderSolitaireGame(Random(3)).toJson()
        val corrupted = valid.replaceFirst(",1,0,", ",13,0,")

        assertNull(SpiderSolitaireGame.fromJson(corrupted))
    }

    private fun spiderJson(
        columns: List<List<Int>>,
        stock: List<Int>,
        completed: Int = 0,
    ): String = buildString {
        append("{\"columns\":[")
        columns.forEachIndexed { index, cards ->
            if (index > 0) append(',')
            appendCards(cards, faceUp = true)
        }
        append("],\"stock\":")
        appendCards(stock, faceUp = false)
        append(",\"completed\":").append(completed)
        append(",\"score\":500,\"moves\":0}")
    }

    private fun restoreMinesweeper(
        mines: List<Int>,
        revealed: List<Int>,
        flagged: List<Int> = emptyList(),
    ): MinesweeperGame {
        val encoded = buildString {
            append("{\"w\":6,\"h\":6,\"count\":").append(mines.size)
            append(",\"initialized\":true,\"over\":false,\"won\":false")
            append(",\"mines\":").append(mines)
            append(",\"revealed\":").append(revealed)
            append(",\"flagged\":").append(flagged)
            append('}')
        }
        return requireNotNull(MinesweeperGame.fromJson(encoded))
    }

    private fun StringBuilder.appendCards(ids: List<Int>, faceUp: Boolean) {
        append('[')
        ids.forEachIndexed { index, id ->
            if (index > 0) append(',')
            append('[').append(id).append(',').append(id % 13 + 1).append(",0,")
                .append(faceUp).append(']')
        }
        append(']')
    }
}
