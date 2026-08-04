package com.deskcubby.app.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.GameStateDao
import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.model.Game2048AnimationSpeed
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.statistics.EngagementKind
import com.deskcubby.app.data.statistics.EngagementTimeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
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

    private sealed interface PersistenceCommand {
        val gameId: String

        data class Load(
            override val gameId: String,
            val result: CompletableDeferred<String?>,
        ) : PersistenceCommand

        data class Save(
            override val gameId: String,
            val saveJson: String,
            val score: Int,
        ) : PersistenceCommand

        data class Finish(
            override val gameId: String,
            val score: Int,
        ) : PersistenceCommand

        data class Clear(override val gameId: String) : PersistenceCommand
    }

    /**
     * A single consumer preserves the exact order of pause, finish, clear and subsequent load
     * operations. In particular, immediately reopening a game cannot read past a queued pause
     * snapshot, and an older pause write cannot overtake a newer game-over write.
     */
    private val persistenceCommands = Channel<PersistenceCommand>(Channel.UNLIMITED)

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

    init {
        viewModelScope.launch {
            for (command in persistenceCommands) {
                try {
                    when (command) {
                        is PersistenceCommand.Load -> {
                            command.result.complete(gameStateDao.get(command.gameId)?.saveJson)
                        }

                        is PersistenceCommand.Save -> {
                            upsert(command.gameId, command.saveJson, command.score)
                        }

                        is PersistenceCommand.Finish -> {
                            upsert(command.gameId, saveJson = null, score = command.score)
                        }

                        is PersistenceCommand.Clear -> {
                            gameStateDao.clearSave(command.gameId, System.currentTimeMillis())
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (command is PersistenceCommand.Load) {
                        command.result.complete(null)
                    }
                    // Persistence remains best-effort; gameplay must never crash on a DB failure.
                }
            }
        }
    }

    /** High score and saved-game availability for one of [GAME_IDS]. */
    fun meta(gameId: String): StateFlow<GameMeta> = metas.getValue(gameId)

    /**
     * Returns the serialized paused-game snapshot after every previously enqueued persistence
     * operation has completed, or null when there is no usable snapshot.
     */
    suspend fun loadSave(gameId: String): String? {
        val result = CompletableDeferred<String?>()
        persistenceCommands.send(PersistenceCommand.Load(gameId, result))
        return result.await()
    }

    /** Stores an in-progress snapshot and raises the high score when [score] exceeds it. */
    fun saveProgress(gameId: String, saveJson: String, score: Int) {
        persistenceCommands.trySend(PersistenceCommand.Save(gameId, saveJson, score))
    }

    /** Called when a run ends: updates the high score and clears the saved snapshot. */
    fun recordScore(gameId: String, score: Int) {
        persistenceCommands.trySend(PersistenceCommand.Finish(gameId, score))
    }

    fun clearSave(gameId: String) {
        persistenceCommands.trySend(PersistenceCommand.Clear(gameId))
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

    private suspend fun upsert(gameId: String, saveJson: String?, score: Int) {
        val existing = gameStateDao.get(gameId)
        gameStateDao.upsert(
            GameStateEntity(
                gameId = gameId,
                highScore = maxOf(existing?.highScore ?: 0, score),
                saveJson = saveJson,
                updatedAt = System.currentTimeMillis(),
            ),
        )
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
