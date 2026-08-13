package com.deskcubby.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.statistics.GamePersistenceCoordinator
import com.deskcubby.app.data.statistics.GameStatisticMetric
import com.deskcubby.app.data.statistics.GameStatisticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameStatisticDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: GameStatisticDao
    private lateinit var repository: GameStatisticsRepository

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.gameStatisticDao()
        repository = GameStatisticsRepository(dao)
    }

    @After
    fun closeDatabase() = runBlocking {
        repository.shutdownForTest()
        database.close()
    }

    @Test
    fun concurrentIncrementsAreNotLostAndMaximumNeverMovesBackward() = runBlocking {
        coroutineScope {
            repeat(100) {
                launch(Dispatchers.Default) {
                    repository.record(
                        gameId = "2048",
                        increments = mapOf(GameStatisticMetric.EFFECTIVE_MOVES to 1L),
                        maxima = mapOf(GameStatisticMetric.HIGHEST_TILE to (it + 2).toLong()),
                    )
                }
            }
        }
        repository.record(
            gameId = "2048",
            maxima = mapOf(GameStatisticMetric.HIGHEST_TILE to 16L),
        )

        val values = dao.getAllForBackup().associate { it.metricKey to it.value }
        assertEquals(100L, values[GameStatisticMetric.EFFECTIVE_MOVES])
        assertEquals(101L, values[GameStatisticMetric.HIGHEST_TILE])
    }

    @Test
    fun incrementSaturatesAtLongMaximum() = runBlocking {
        dao.upsertAll(
            listOf(
                GameStatisticEntity(
                    gameId = "spider",
                    metricKey = GameStatisticMetric.SPIDER_CARD_MOVES,
                    value = Long.MAX_VALUE - 1,
                    updatedAt = 1L,
                ),
            ),
        )

        repository.record(
            gameId = "spider",
            increments = mapOf(GameStatisticMetric.SPIDER_CARD_MOVES to 10L),
        )

        assertEquals(Long.MAX_VALUE, dao.getAllForBackup().single().value)
    }

    @Test
    fun invalidBatchIsRejectedBeforeAnyMetricIsWritten() = runBlocking {
        val invalidCalls = listOf<suspend () -> Unit>(
            {
                repository.record(
                    gameId = "unknown",
                    increments = mapOf(GameStatisticMetric.WINS to 1L),
                )
            },
            {
                repository.record(
                    gameId = "snake",
                    increments = mapOf(GameStatisticMetric.WINS to 1L),
                )
            },
            {
                repository.record(
                    gameId = "snake",
                    increments = mapOf(GameStatisticMetric.FOOD_EATEN to -1L),
                )
            },
            {
                repository.record(
                    gameId = "snake",
                    increments = mapOf(GameStatisticMetric.MAX_LENGTH to 1L),
                    maxima = mapOf(GameStatisticMetric.MAX_LENGTH to 2L),
                )
            },
        )

        invalidCalls.forEach { call ->
            var rejected = false
            try {
                call()
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        }
        assertEquals(emptyList<GameStatisticEntity>(), dao.getAllForBackup())
    }

    @Test
    fun applicationPersistenceQueueKeepsSaveStatisticsAndClearOrdered() = runBlocking {
        val coordinator = GamePersistenceCoordinator(database.gameStateDao(), repository)

        coordinator.saveProgress("spider", "{\"round\":1}", score = 10)
        coordinator.recordStatistics(
            gameId = "spider",
            increments = mapOf(GameStatisticMetric.SPIDER_CARD_MOVES to 3L),
        )
        coordinator.recordScore("spider", score = 25)

        // Loading is a queue barrier, so every command above has completed when it returns.
        assertEquals(null, coordinator.loadSave("spider"))
        assertEquals(25, database.gameStateDao().get("spider")?.highScore)
        val values = dao.getAllForBackup().associate { it.metricKey to it.value }
        assertEquals(3L, values[GameStatisticMetric.SPIDER_CARD_MOVES])

        coordinator.saveProgress("spider", "{\"round\":2}", score = 5)
        assertEquals("{\"round\":2}", coordinator.loadSave("spider"))
        assertEquals(25, database.gameStateDao().get("spider")?.highScore)
    }
}
