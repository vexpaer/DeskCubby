package com.deskcubby.app.data.backup

import com.deskcubby.app.data.local.GameStateEntity
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
}
