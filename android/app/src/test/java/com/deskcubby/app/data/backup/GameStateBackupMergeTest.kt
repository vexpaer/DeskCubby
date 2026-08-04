package com.deskcubby.app.data.backup

import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.local.GameStatisticEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GameStateBackupMergeTest {
    @Test
    fun mergeKeepsMaximumScoreAndNewestSave() {
        val local = GameStateEntity(
            gameId = "2048",
            highScore = 8_192,
            saveJson = """{"source":"local"}""",
            updatedAt = 10,
        )
        val imported = GameStateEntity(
            gameId = "2048",
            highScore = 4_096,
            saveJson = """{"source":"imported"}""",
            updatedAt = 20,
        )

        val merged = mergeGameStateBackups(listOf(local), listOf(imported)).single()

        assertEquals(8_192, merged.highScore)
        assertEquals(imported.saveJson, merged.saveJson)
        assertEquals(20, merged.updatedAt)
    }

    @Test
    fun mergeRetainsGamesMissingFromEitherSide() {
        val snake = GameStateEntity("snake", 10, null, 1)
        val tetris = GameStateEntity("tetris", 20, null, 2)

        assertEquals(
            listOf(snake, tetris),
            mergeGameStateBackups(listOf(snake), listOf(tetris)),
        )
    }

    @Test
    fun statisticMergeUsesMaximumValuesAndIsIdempotent() {
        val local = listOf(
            GameStatisticEntity("minesweeper", "wins", 7, 10),
            GameStatisticEntity("snake", "foodEaten", 4, 11),
        )
        val imported = listOf(
            GameStatisticEntity("minesweeper", "wins", 5, 20),
            GameStatisticEntity("spider", "spiderCardMoves", 12, 21),
        )

        val first = mergeGameStatisticBackups(local, imported)
        val second = mergeGameStatisticBackups(first, imported)

        assertEquals(first, second)
        assertEquals(7L, first.single { it.gameId == "minesweeper" }.value)
        assertEquals(20L, first.single { it.gameId == "minesweeper" }.updatedAt)
        assertEquals(4L, first.single { it.gameId == "snake" }.value)
        assertEquals(12L, first.single { it.gameId == "spider" }.value)
    }
}
