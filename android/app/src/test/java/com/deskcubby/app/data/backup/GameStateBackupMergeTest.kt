package com.deskcubby.app.data.backup

import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.local.GameStatisticEntity
import com.deskcubby.app.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class GameStateBackupMergeTest {
    @Test
    fun v28ExportProjectionOmitsAndroidOnlyGoDataAndShortcut() {
        val content = AppBackupContent(
            settings = AppSettings(homeGameShortcuts = listOf("go", "snake")),
            thoughts = emptyList(),
            categories = emptyList(),
            favorites = emptyList(),
            dateRecords = emptyList(),
            poetryCategories = emptyList(),
            poems = emptyList(),
            gameStates = listOf(
                GameStateEntity("go", 8, """{"size":9}""", 2),
                GameStateEntity("snake", 10, null, 3),
            ),
            gameStatistics = listOf(
                GameStatisticEntity("go", "goStonesCaptured", 8, 2),
                GameStatisticEntity("snake", "foodEaten", 4, 3),
            ),
        )

        val projected = content.projectForV28Export()

        assertEquals(listOf("snake"), projected.settings.homeGameShortcuts)
        assertEquals(listOf("snake"), projected.gameStates.map(GameStateEntity::gameId))
        assertEquals(listOf("snake"), projected.gameStatistics.map(GameStatisticEntity::gameId))
        assertEquals(listOf("go", "snake"), content.settings.homeGameShortcuts)
        assertEquals(2, content.gameStates.size)
        assertEquals(2, content.gameStatistics.size)
    }

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
    fun importingV28WithoutGoPreservesLocalGoRoomData() {
        val localGo = GameStateEntity("go", 9, """{"size":9}""", 3)
        val importedSnake = GameStateEntity("snake", 10, null, 4)
        val localGoMetric = GameStatisticEntity("go", "goMovesPlayed", 12, 3)
        val importedSnakeMetric = GameStatisticEntity("snake", "foodEaten", 4, 4)

        assertEquals(
            listOf(localGo, importedSnake),
            mergeGameStateBackups(listOf(localGo), listOf(importedSnake)),
        )
        assertEquals(
            listOf(localGoMetric, importedSnakeMetric),
            mergeGameStatisticBackups(listOf(localGoMetric), listOf(importedSnakeMetric)),
        )
    }

    @Test
    fun importingV28ProjectionPreservesOnlyTheCurrentDevicesGoShortcutChoice() {
        val imported = AppSettings(homeGameShortcuts = listOf("2048", "snake"))

        assertEquals(
            listOf("2048", "snake", "go"),
            mergeAndroidOnlyGameShortcut(
                imported = imported,
                current = AppSettings(homeGameShortcuts = listOf("go")),
            ).homeGameShortcuts,
        )
        assertEquals(
            listOf("2048", "snake"),
            mergeAndroidOnlyGameShortcut(
                imported = imported,
                current = AppSettings(homeGameShortcuts = listOf("snake")),
            ).homeGameShortcuts,
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
