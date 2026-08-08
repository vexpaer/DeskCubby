package com.deskcubby.app.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.GameStateDao
import com.deskcubby.app.data.model.Game2048AnimationSpeed
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.statistics.EngagementKind
import com.deskcubby.app.data.statistics.EngagementTimeRepository
import com.deskcubby.app.data.statistics.GamePersistenceCoordinator
import com.deskcubby.app.data.statistics.GameStatisticMetric
import com.deskcubby.app.games.Game2048
import com.deskcubby.app.games.MinesweeperGame
import com.deskcubby.app.games.SnakeGame
import com.deskcubby.app.games.SpiderSolitaireGame
import com.deskcubby.app.games.TetrisGame
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val gameStateDao: GameStateDao,
    private val engagementTimeRepository: EngagementTimeRepository,
    private val settingsRepository: SettingsRepository,
    private val gamePersistenceCoordinator: GamePersistenceCoordinator,
) : ViewModel() {

    data class GameMeta(
        val highScore: Int = 0,
        val hasSave: Boolean = false,
        val totalPlayMillis: Long = 0L,
    )

    val animationSpeed: StateFlow<Game2048AnimationSpeed> = settingsRepository.settings
        .map { settings -> settings.game2048AnimationSpeed }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Game2048AnimationSpeed.NORMAL,
        )

    private var spiderReplacementJob: Job? = null

    private val metas: Map<String, StateFlow<GameMeta>> = GAME_IDS.associateWith { gameId ->
        combine(
            gameStateDao.observe(gameId),
            engagementTimeRepository.snapshot,
        ) { entity, times ->
                GameMeta(
                    highScore = entity?.highScore ?: 0,
                    hasSave = entity?.saveJson?.isNotBlank() == true,
                    totalPlayMillis = times.total(EngagementKind.GAME, gameId),
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = GameMeta(),
            )
    }

    /** High score and saved-game availability for one of [GAME_IDS]. */
    fun meta(gameId: String): StateFlow<GameMeta> = metas.getValue(gameId)

    /**
     * Returns the serialized paused-game snapshot after every previously enqueued persistence
     * operation has completed, or null when there is no usable snapshot.
     */
    suspend fun loadSave(gameId: String): String? {
        return gamePersistenceCoordinator.loadSave(gameId)
    }

    /** Stores an in-progress snapshot and raises the high score when [score] exceeds it. */
    fun saveProgress(gameId: String, saveJson: String, score: Int) {
        gamePersistenceCoordinator.saveProgress(gameId, saveJson, score)
    }

    /** Called when a run ends: updates the high score and clears the saved snapshot. */
    fun recordScore(gameId: String, score: Int) {
        gamePersistenceCoordinator.recordScore(gameId, score)
    }

    fun clearSave(gameId: String) {
        gamePersistenceCoordinator.clearSave(gameId)
    }

    /**
     * Replaces a saved Spider round with a fresh deal. Only this explicit replacement is a loss;
     * leaving the page keeps the resumable save and never changes the outcome counters.
     */
    fun discardSavedSpiderAndThen(onReady: () -> Unit) {
        if (spiderReplacementJob?.isActive == true) return
        spiderReplacementJob = viewModelScope.launch {
            try {
                val saved = loadSave(GAME_SPIDER)
                val delta = saved
                    ?.let(SpiderSolitaireGame::fromJson)
                    ?.abandonWithResult()
                    ?.statisticsDelta
                if (delta != null) enqueueSpiderStatistics(delta)
                gamePersistenceCoordinator.clearSave(GAME_SPIDER)
                onReady()
            } finally {
                spiderReplacementJob = null
            }
        }
    }

    /**
     * Records one accepted direction input. [delta] is null when that direction cannot move any
     * tile, but the input still belongs in the total-move count.
     */
    fun record2048MoveAttempt(gameId: String, delta: Game2048.StatisticsDelta?) {
        recordStatistics(
            gameId = gameId,
            increments = game2048StatisticIncrements(delta),
            maxima = delta?.let {
                mapOf(GameStatisticMetric.HIGHEST_TILE to it.highestTile.toLong())
            }.orEmpty(),
        )
    }

    fun recordSnakeStatistics(delta: SnakeGame.StatisticsDelta) {
        recordStatistics(
            gameId = GAME_SNAKE,
            increments = mapOf(
                GameStatisticMetric.FOOD_EATEN to delta.foodEaten.toLong(),
                GameStatisticMetric.LOSSES to delta.losses.toLong(),
            ),
            maxima = mapOf(GameStatisticMetric.MAX_LENGTH to delta.maxLength.toLong()),
        )
    }

    fun recordTetrisStatistics(delta: TetrisGame.StatisticsDelta) {
        recordStatistics(
            gameId = GAME_TETRIS,
            increments = mapOf(
                GameStatisticMetric.PIECES_LOCKED to delta.piecesLocked.toLong(),
                GameStatisticMetric.LINES_CLEARED to delta.linesCleared.toLong(),
                GameStatisticMetric.TETRISES to delta.tetrises.toLong(),
                GameStatisticMetric.LOSSES to delta.losses.toLong(),
            ),
        )
    }

    fun recordMinesweeperStatistics(delta: MinesweeperGame.StatisticsDelta) {
        recordStatistics(
            gameId = GAME_MINESWEEPER,
            increments = mapOf(
                GameStatisticMetric.MINES_CELLS_REVEALED to delta.minesCellsRevealed.toLong(),
                GameStatisticMetric.MINES_SWEPT to delta.minesSwept.toLong(),
                GameStatisticMetric.FLAGS_PLACED to delta.flagsPlaced.toLong(),
                GameStatisticMetric.WINS to delta.wins.toLong(),
                GameStatisticMetric.LOSSES to delta.losses.toLong(),
            ),
        )
    }

    fun recordSpiderStatistics(delta: SpiderSolitaireGame.StatisticsDelta) {
        enqueueSpiderStatistics(delta)
    }

    fun beginPlayTime(gameId: String) {
        engagementTimeRepository.begin(EngagementKind.GAME, gameId)
    }

    fun checkpointPlayTime(gameId: String) = viewModelScope.launch {
        persistEngagementTime {
            engagementTimeRepository.checkpoint(EngagementKind.GAME, gameId)
        }
    }

    fun endPlayTime(gameId: String) {
        // Detach on the lifecycle-callback thread. If a configuration change immediately calls
        // beginPlayTime again, a delayed persistence coroutine can no longer remove that session.
        try {
            engagementTimeRepository.endAndCommit(EngagementKind.GAME, gameId)
        } catch (_: Exception) {
            // Invalid/damaged local state must not make a lifecycle callback crash the game.
        }
    }

    fun setAnimationSpeed(value: Game2048AnimationSpeed) = viewModelScope.launch {
        settingsRepository.setGame2048AnimationSpeed(value)
    }

    private fun recordStatistics(
        gameId: String,
        increments: Map<String, Long>,
        maxima: Map<String, Long> = emptyMap(),
    ) {
        val filteredIncrements = increments.filterValues { it > 0L }
        val filteredMaxima = maxima.filterValues { it > 0L }
        if (filteredIncrements.isEmpty() && filteredMaxima.isEmpty()) return
        gamePersistenceCoordinator.recordStatistics(
            gameId,
            filteredIncrements,
            filteredMaxima,
        )
    }

    private fun enqueueSpiderStatistics(delta: SpiderSolitaireGame.StatisticsDelta) {
        val increments = mapOf(
            GameStatisticMetric.SPIDER_CARD_MOVES to delta.cardMoves.toLong(),
            GameStatisticMetric.SPIDER_DEALS to delta.deals.toLong(),
            GameStatisticMetric.SPIDER_UNDOS to delta.undos.toLong(),
            GameStatisticMetric.WINS to delta.wins.toLong(),
            GameStatisticMetric.LOSSES to delta.losses.toLong(),
        ).filterValues { it > 0L }
        if (increments.isEmpty()) return
        gamePersistenceCoordinator.recordStatistics(GAME_SPIDER, increments)
    }

    private suspend fun persistEngagementTime(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A damaged/full private store must not terminate active gameplay.
        }
    }

    companion object {
        const val GAME_2048 = "2048"
        const val GAME_2048_5 = "2048_5"
        const val GAME_2048_6 = "2048_6"
        const val GAME_SNAKE = "snake"
        const val GAME_TETRIS = "tetris"
        const val GAME_MINESWEEPER = "minesweeper"
        const val GAME_SPIDER = "spider"
        val GAME_IDS = listOf(
            GAME_2048,
            GAME_2048_5,
            GAME_2048_6,
            GAME_SNAKE,
            GAME_TETRIS,
            GAME_MINESWEEPER,
            GAME_SPIDER,
        )
    }
}

/** Pure policy kept outside the ViewModel so no-op direction inputs remain unit-testable. */
internal fun game2048StatisticIncrements(
    delta: Game2048.StatisticsDelta?,
): Map<String, Long> = buildMap {
    put(GameStatisticMetric.MOVE_ATTEMPTS, 1L)
    if (delta != null) {
        put(GameStatisticMetric.EFFECTIVE_MOVES, delta.effectiveMoves.toLong())
        put(GameStatisticMetric.MERGES, delta.merges.toLong())
        put(GameStatisticMetric.WINS, delta.wins.toLong())
    }
}
