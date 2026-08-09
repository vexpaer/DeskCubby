package com.deskcubby.app.data.statistics

import com.deskcubby.app.data.local.GameStatisticDao
import com.deskcubby.app.data.local.GameStatisticEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

object GameStatisticMetric {
    const val WINS = "wins"
    const val LOSSES = "losses"

    const val MOVE_ATTEMPTS = "moveAttempts"
    const val EFFECTIVE_MOVES = "effectiveMoves"
    const val MERGES = "merges"
    const val HIGHEST_TILE = "highestTile"

    const val FOOD_EATEN = "foodEaten"
    const val MAX_LENGTH = "maxLength"

    const val PIECES_LOCKED = "piecesLocked"
    const val LINES_CLEARED = "linesCleared"
    const val TETRISES = "tetrises"

    const val MINES_CELLS_REVEALED = "minesCellsRevealed"
    const val MINES_SWEPT = "minesSwept"
    const val FLAGS_PLACED = "flagsPlaced"

    const val SPIDER_CARD_MOVES = "spiderCardMoves"
    const val SPIDER_DEALS = "spiderDeals"
    const val SPIDER_UNDOS = "spiderUndos"

    const val GO_MOVES_PLAYED = "goMovesPlayed"
    const val GO_STONES_CAPTURED = "goStonesCaptured"
    const val GO_PASSES = "goPasses"
    const val GO_GAMES_COMPLETED = "goGamesCompleted"
}

object GameStatisticCatalog {
    const val GAME_2048 = "2048"
    const val GAME_2048_5 = "2048_5"
    const val GAME_2048_6 = "2048_6"
    const val GAME_SNAKE = "snake"
    const val GAME_TETRIS = "tetris"
    const val GAME_MINESWEEPER = "minesweeper"
    const val GAME_SPIDER = "spider"
    const val GAME_GO = "go"

    private val game2048Ids = setOf(GAME_2048, GAME_2048_5, GAME_2048_6)
    private val commonOutcomes = setOf(
        GameStatisticMetric.WINS,
        GameStatisticMetric.LOSSES,
    )
    private val game2048 = commonOutcomes + setOf(
        GameStatisticMetric.MOVE_ATTEMPTS,
        GameStatisticMetric.EFFECTIVE_MOVES,
        GameStatisticMetric.MERGES,
        GameStatisticMetric.HIGHEST_TILE,
    )

    val supportedMetricsByGameId: Map<String, Set<String>> = linkedMapOf(
        GAME_2048 to game2048,
        GAME_2048_5 to game2048,
        GAME_2048_6 to game2048,
        GAME_SNAKE to setOf(
            GameStatisticMetric.LOSSES,
            GameStatisticMetric.FOOD_EATEN,
            GameStatisticMetric.MAX_LENGTH,
        ),
        GAME_TETRIS to setOf(
            GameStatisticMetric.LOSSES,
            GameStatisticMetric.PIECES_LOCKED,
            GameStatisticMetric.LINES_CLEARED,
            GameStatisticMetric.TETRISES,
        ),
        GAME_MINESWEEPER to commonOutcomes + setOf(
            GameStatisticMetric.MINES_CELLS_REVEALED,
            GameStatisticMetric.MINES_SWEPT,
            GameStatisticMetric.FLAGS_PLACED,
        ),
        GAME_SPIDER to commonOutcomes + setOf(
            GameStatisticMetric.SPIDER_CARD_MOVES,
            GameStatisticMetric.SPIDER_DEALS,
            GameStatisticMetric.SPIDER_UNDOS,
        ),
        GAME_GO to setOf(
            GameStatisticMetric.GO_MOVES_PLAYED,
            GameStatisticMetric.GO_STONES_CAPTURED,
            GameStatisticMetric.GO_PASSES,
            GameStatisticMetric.GO_GAMES_COMPLETED,
        ),
    )

    val gameIds: Set<String> = supportedMetricsByGameId.keys
    val metricKeys: Set<String> = supportedMetricsByGameId.values.flatten().toSet()

    fun supports(gameId: String, metricKey: String): Boolean =
        metricKey in supportedMetricsByGameId[gameId].orEmpty()

    /**
     * Metrics used by current gameplay and statistics screens. 2048 loss rows remain supported
     * only so older Room data and JSON backups can round-trip without being deleted or rejected.
     */
    fun isActive(gameId: String, metricKey: String): Boolean =
        supports(gameId, metricKey) && !(
            gameId in game2048Ids &&
                metricKey == GameStatisticMetric.LOSSES
        )
}

data class GameMetricValues internal constructor(
    private val values: Map<String, Long> = emptyMap(),
) {
    fun value(metricKey: String): Long = values[metricKey] ?: 0L
    fun asMap(): Map<String, Long> = values
}

data class GameStatisticsSnapshot(
    val byGameId: Map<String, GameMetricValues> = emptyMap(),
) {
    fun total(metricKey: String, gameIds: Iterable<String> = byGameId.keys): Long =
        gameIds.fold(0L) { total, gameId ->
            saturatingAdd(total, byGameId[gameId]?.value(metricKey) ?: 0L)
        }
}

@Singleton
class GameStatisticsRepository @Inject constructor(
    private val dao: GameStatisticDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val statistics: StateFlow<GameStatisticsSnapshot> = dao.observeAll()
        .map(::snapshotOf)
        .stateIn(scope, SharingStarted.Eagerly, GameStatisticsSnapshot())

    suspend fun record(
        gameId: String,
        increments: Map<String, Long> = emptyMap(),
        maxima: Map<String, Long> = emptyMap(),
    ) {
        require(gameId in GameStatisticCatalog.gameIds) { "Unsupported game statistic ID" }
        require(increments.keys.intersect(maxima.keys).isEmpty()) {
            "A metric cannot be incremented and maximized in the same update"
        }
        increments.forEach { (metricKey, delta) ->
            require(GameStatisticCatalog.isActive(gameId, metricKey)) {
                "Unsupported game statistic metric"
            }
            require(delta > 0L) { "Statistic increments must be positive" }
        }
        maxima.forEach { (metricKey, candidate) ->
            require(GameStatisticCatalog.isActive(gameId, metricKey)) {
                "Unsupported game statistic metric"
            }
            require(candidate >= 0L) { "Statistic maxima must be non-negative" }
        }
        if (increments.isEmpty() && maxima.isEmpty()) return
        dao.applyMetrics(gameId, increments, maxima, System.currentTimeMillis())
    }
}

internal fun snapshotOf(items: List<GameStatisticEntity>): GameStatisticsSnapshot =
    GameStatisticsSnapshot(
        byGameId = items
            .filter { item ->
                item.value >= 0L && GameStatisticCatalog.isActive(item.gameId, item.metricKey)
            }
            .groupBy(GameStatisticEntity::gameId)
            .mapValues { (_, metrics) ->
                GameMetricValues(
                    metrics.associate { item -> item.metricKey to item.value },
                )
            },
    )

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
