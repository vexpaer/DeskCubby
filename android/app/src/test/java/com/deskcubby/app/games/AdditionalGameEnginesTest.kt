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

        assertTrue(game.reveal(4, 4))

        val first = game.cell(4, 4)
        assertTrue(first.revealed)
        assertFalse(first.mine)
        assertEquals(0, first.adjacentMines)
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
    fun `Spider initial deal can be dealt and undone without changing serialized state`() {
        val game = SpiderSolitaireGame(Random(11))
        val before = game.toJson()

        assertEquals(5, game.stockDealsRemaining)
        assertTrue(game.canDealStock)
        assertTrue(game.dealStock())
        assertEquals(4, game.stockDealsRemaining)
        assertTrue(game.canUndo)
        assertTrue(game.undo())
        assertEquals(before, game.toJson())
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
        assertEquals(beforeDeal, restored.toJson())
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

    private fun spiderJson(columns: List<List<Int>>, stock: List<Int>): String = buildString {
        append("{\"columns\":[")
        columns.forEachIndexed { index, cards ->
            if (index > 0) append(',')
            appendCards(cards, faceUp = true)
        }
        append("],\"stock\":")
        appendCards(stock, faceUp = false)
        append(",\"completed\":0,\"score\":500,\"moves\":0}")
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
