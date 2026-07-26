package com.deskcubby.app.ui.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.GameStateDao
import com.deskcubby.app.data.local.GameStateEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val gameStateDao: GameStateDao,
) : ViewModel() {

    data class GameMeta(val highScore: Int = 0, val hasSave: Boolean = false)

    private val metas: Map<String, StateFlow<GameMeta>> = GAME_IDS.associateWith { gameId ->
        gameStateDao.observe(gameId)
            .map { entity ->
                GameMeta(
                    highScore = entity?.highScore ?: 0,
                    hasSave = entity?.saveJson?.isNotBlank() == true,
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

    /** Returns the serialized paused-game snapshot, or null when there is none. */
    suspend fun loadSave(gameId: String): String? = try {
        gameStateDao.get(gameId)?.saveJson
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** Stores an in-progress snapshot and raises the high score when [score] exceeds it. */
    fun saveProgress(gameId: String, saveJson: String, score: Int) {
        launchWrite { upsert(gameId, saveJson, score) }
    }

    /** Called when a run ends: updates the high score and clears the saved snapshot. */
    fun recordScore(gameId: String, score: Int) {
        launchWrite { upsert(gameId, saveJson = null, score = score) }
    }

    fun clearSave(gameId: String) {
        launchWrite { gameStateDao.clearSave(gameId, System.currentTimeMillis()) }
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

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Game persistence is best-effort; a failed write must never crash gameplay.
            }
        }
    }

    companion object {
        const val GAME_2048 = "2048"
        const val GAME_SNAKE = "snake"
        const val GAME_TETRIS = "tetris"
        val GAME_IDS = listOf(GAME_2048, GAME_SNAKE, GAME_TETRIS)
    }
}
