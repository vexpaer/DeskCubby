package com.deskcubby.app.data.statistics

import com.deskcubby.app.data.local.GameStateDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Application-lifetime ordered writer for game saves, scores, and aggregate statistics.
 *
 * A navigation destination owns [com.deskcubby.app.ui.games.GamesViewModel], so using its scope
 * for pending writes can lose a pause snapshot when the user immediately changes bottom tabs.
 * This singleton outlives that destination. A queued [loadSave] also acts as a barrier: it cannot
 * read past an earlier save, finish, clear, or statistics update.
 */
@Singleton
class GamePersistenceCoordinator @Inject constructor(
    private val gameStateDao: GameStateDao,
    private val gameStatisticsRepository: GameStatisticsRepository,
) {
    private sealed interface Command {
        val gameId: String

        data class Load(
            override val gameId: String,
            val result: CompletableDeferred<String?>,
        ) : Command

        data class Save(
            override val gameId: String,
            val saveJson: String,
            val score: Int,
        ) : Command

        data class Finish(
            override val gameId: String,
            val score: Int,
        ) : Command

        data class Statistics(
            override val gameId: String,
            val increments: Map<String, Long>,
            val maxima: Map<String, Long>,
        ) : Command

        data class Clear(override val gameId: String) : Command
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Load -> {
                            command.result.complete(gameStateDao.get(command.gameId)?.saveJson)
                        }

                        is Command.Save -> {
                            upsert(command.gameId, command.saveJson, command.score)
                        }

                        is Command.Finish -> {
                            upsert(command.gameId, saveJson = null, score = command.score)
                        }

                        is Command.Statistics -> {
                            gameStatisticsRepository.record(
                                gameId = command.gameId,
                                increments = command.increments,
                                maxima = command.maxima,
                            )
                        }

                        is Command.Clear -> {
                            gameStateDao.clearSave(command.gameId, System.currentTimeMillis())
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (command is Command.Load) command.result.complete(null)
                    // A full or damaged local database must not crash active gameplay.
                }
            }
        }
    }

    suspend fun loadSave(gameId: String): String? {
        val result = CompletableDeferred<String?>()
        commands.send(Command.Load(gameId, result))
        return result.await()
    }

    fun saveProgress(gameId: String, saveJson: String, score: Int) {
        enqueue(Command.Save(gameId, saveJson, score))
    }

    fun recordScore(gameId: String, score: Int) {
        enqueue(Command.Finish(gameId, score))
    }

    fun clearSave(gameId: String) {
        enqueue(Command.Clear(gameId))
    }

    fun recordStatistics(
        gameId: String,
        increments: Map<String, Long>,
        maxima: Map<String, Long> = emptyMap(),
    ) {
        if (increments.isEmpty() && maxima.isEmpty()) return
        enqueue(Command.Statistics(gameId, increments, maxima))
    }

    private fun enqueue(command: Command) {
        check(commands.trySend(command).isSuccess) {
            "The application game-persistence queue is unavailable"
        }
    }

    private suspend fun upsert(gameId: String, saveJson: String?, score: Int) {
        gameStateDao.upsertPreservingHighScore(
            gameId = gameId,
            saveJson = saveJson,
            score = score,
            now = System.currentTimeMillis(),
        )
    }
}
