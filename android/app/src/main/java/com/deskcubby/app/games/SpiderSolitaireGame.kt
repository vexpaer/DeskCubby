package com.deskcubby.app.games

import kotlin.random.Random

/** A complete one-suit Spider Solitaire engine designed for tap-to-move landscape play. */
class SpiderSolitaireGame private constructor(
    initialColumns: List<List<Card>>,
    initialStock: List<Card>,
    initialCompletedRuns: Int,
    initialScore: Int,
    initialMoves: Int,
    initialHasPlayedAction: Boolean,
    initialOutcomeRecorded: Boolean,
) {
    private data class Deal(val columns: List<List<Card>>, val stock: List<Card>)

    private constructor(deal: Deal) : this(
        initialColumns = deal.columns,
        initialStock = deal.stock,
        initialCompletedRuns = 0,
        initialScore = START_SCORE,
        initialMoves = 0,
        initialHasPlayedAction = false,
        initialOutcomeRecorded = false,
    )

    constructor(random: Random = Random.Default) : this(deal(random))

    data class Card(
        val id: Int,
        val rank: Int,
        val suit: Int,
        var faceUp: Boolean,
    )

    enum class Action {
        MOVE,
        DEAL_STOCK,
        UNDO,
        ABANDON,
    }

    /** Lifetime-statistics increments caused by exactly one accepted player action. */
    data class StatisticsDelta(
        val cardMoves: Int = 0,
        val deals: Int = 0,
        val undos: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
    ) {
        val isEmpty: Boolean
            get() = cardMoves == 0 && deals == 0 && undos == 0 && wins == 0 && losses == 0
    }

    data class ActionResult(
        val action: Action,
        val changed: Boolean,
        val statisticsDelta: StatisticsDelta = StatisticsDelta(),
    )

    private data class Snapshot(
        val columns: List<List<Card>>,
        val stock: List<Card>,
        val completedRuns: Int,
        val score: Int,
        val moves: Int,
    )

    private val columns = initialColumns.map { column -> column.map(Card::copy).toMutableList() }
        .toMutableList()
    private val stock = initialStock.map(Card::copy).toMutableList()
    private val history = ArrayDeque<Snapshot>()

    var completedRuns: Int = initialCompletedRuns
        private set
    var score: Int = initialScore
        private set
    var moves: Int = initialMoves
        private set

    /**
     * Whether this round has ever accepted a card move or stock deal.
     *
     * Unlike [moves], this is intentionally not rewound by [undo]. It lets the caller distinguish
     * abandoning a played round from replacing an untouched deal without treating a normal page
     * exit as a loss. The flag is round state, not a lifetime-statistics counter.
     */
    var hasPlayedAction: Boolean = initialHasPlayedAction
        private set

    /** Single-round idempotency marker; it prevents win/loss replay after undo or restore. */
    var outcomeRecorded: Boolean = initialOutcomeRecorded
        private set

    val isWon: Boolean get() = completedRuns == TOTAL_RUNS
    val canUndo: Boolean get() = history.isNotEmpty()
    val stockDealsRemaining: Int get() = stock.size / COLUMN_COUNT
    val canDealStock: Boolean
        get() = !isWon && stock.size >= COLUMN_COUNT && columns.none { it.isEmpty() }

    fun column(index: Int): List<Card> = columns[index].map(Card::copy)

    fun canSelect(column: Int, cardIndex: Int): Boolean {
        val cards = columns.getOrNull(column) ?: return false
        if (cardIndex !in cards.indices || !cards[cardIndex].faceUp) return false
        for (index in cardIndex until cards.lastIndex) {
            val upper = cards[index]
            val lower = cards[index + 1]
            if (!lower.faceUp || upper.suit != lower.suit || upper.rank != lower.rank + 1) return false
        }
        return true
    }

    /** Compatibility wrapper for callers that only need to know whether a card move succeeded. */
    fun move(fromColumn: Int, cardIndex: Int, toColumn: Int): Boolean =
        moveWithResult(fromColumn, cardIndex, toColumn).changed

    fun moveWithResult(fromColumn: Int, cardIndex: Int, toColumn: Int): ActionResult {
        if (isWon || fromColumn == toColumn || !canSelect(fromColumn, cardIndex)) {
            return unchanged(Action.MOVE)
        }
        val source = columns.getOrNull(fromColumn) ?: return unchanged(Action.MOVE)
        val target = columns.getOrNull(toColumn) ?: return unchanged(Action.MOVE)
        val movingFirst = source[cardIndex]
        if (target.isNotEmpty() && target.last().rank != movingFirst.rank + 1) {
            return unchanged(Action.MOVE)
        }
        rememberSnapshot()
        val moving = source.subList(cardIndex, source.size).map(Card::copy)
        repeat(source.size - cardIndex) { source.removeAt(source.size - 1) }
        target.addAll(moving)
        revealTop(source)
        score = (score - MOVE_COST).coerceAtLeast(0)
        moves++
        hasPlayedAction = true
        removeCompletedRuns(fromColumn)
        removeCompletedRuns(toColumn)
        val wonThisAction = isWon && !outcomeRecorded
        if (wonThisAction) outcomeRecorded = true
        return ActionResult(
            action = Action.MOVE,
            changed = true,
            statisticsDelta = StatisticsDelta(
                cardMoves = 1,
                wins = if (wonThisAction) 1 else 0,
            ),
        )
    }

    /** Compatibility wrapper for callers that only need to know whether stock was dealt. */
    fun dealStock(): Boolean = dealStockWithResult().changed

    fun dealStockWithResult(): ActionResult {
        if (!canDealStock) return unchanged(Action.DEAL_STOCK)
        rememberSnapshot()
        repeat(COLUMN_COUNT) { column ->
            columns[column].add(stock.removeAt(0).copy(faceUp = true))
        }
        score = (score - MOVE_COST).coerceAtLeast(0)
        moves++
        hasPlayedAction = true
        repeat(COLUMN_COUNT, ::removeCompletedRuns)
        val wonThisAction = isWon && !outcomeRecorded
        if (wonThisAction) outcomeRecorded = true
        return ActionResult(
            action = Action.DEAL_STOCK,
            changed = true,
            statisticsDelta = StatisticsDelta(
                deals = 1,
                wins = if (wonThisAction) 1 else 0,
            ),
        )
    }

    /** Compatibility wrapper for callers that only need to know whether an undo succeeded. */
    fun undo(): Boolean = undoWithResult().changed

    fun undoWithResult(): ActionResult {
        val previous = history.removeLastOrNull() ?: return unchanged(Action.UNDO)
        columns.clear()
        columns.addAll(previous.columns.map { it.map(Card::copy).toMutableList() })
        stock.clear()
        stock.addAll(previous.stock.map(Card::copy))
        completedRuns = previous.completedRuns
        score = previous.score
        moves = previous.moves
        return ActionResult(
            action = Action.UNDO,
            changed = true,
            statisticsDelta = StatisticsDelta(undos = 1),
        )
    }

    /**
     * Explicitly abandons this round before the caller replaces it with a new deal.
     *
     * Merely leaving the page must not call this method: an in-progress save is not a loss.
     * Untouched deals and already-recorded outcomes also produce no increment.
     */
    fun abandonWithResult(): ActionResult {
        if (!hasPlayedAction || isWon || outcomeRecorded) return unchanged(Action.ABANDON)
        outcomeRecorded = true
        return ActionResult(
            action = Action.ABANDON,
            changed = true,
            statisticsDelta = StatisticsDelta(losses = 1),
        )
    }

    fun toJson(): String = buildString {
        append("{\"schemaVersion\":").append(SAVE_SCHEMA_VERSION)
        append(",\"columns\":[")
        columns.forEachIndexed { columnIndex, column ->
            if (columnIndex > 0) append(',')
            appendCards(column)
        }
        append("],\"stock\":")
        appendCards(stock)
        append(",\"completed\":").append(completedRuns)
        append(",\"score\":").append(score)
        append(",\"moves\":").append(moves)
        append(",\"hasPlayedAction\":").append(hasPlayedAction)
        append(",\"outcomeRecorded\":").append(outcomeRecorded)
        append(",\"history\":[")
        history.forEachIndexed { index, snapshot ->
            if (index > 0) append(',')
            appendSnapshot(snapshot)
        }
        append(']')
        append('}')
    }

    private fun StringBuilder.appendSnapshot(snapshot: Snapshot) {
        append("{\"columns\":[")
        snapshot.columns.forEachIndexed { columnIndex, column ->
            if (columnIndex > 0) append(',')
            appendCards(column)
        }
        append("],\"stock\":")
        appendCards(snapshot.stock)
        append(",\"completed\":").append(snapshot.completedRuns)
        append(",\"score\":").append(snapshot.score)
        append(",\"moves\":").append(snapshot.moves)
        append('}')
    }

    private fun StringBuilder.appendCards(cards: List<Card>) {
        append('[')
        cards.forEachIndexed { index, card ->
            if (index > 0) append(',')
            append('[').append(card.id).append(',').append(card.rank).append(',')
                .append(card.suit).append(',').append(card.faceUp).append(']')
        }
        append(']')
    }

    private fun removeCompletedRuns(columnIndex: Int) {
        val column = columns[columnIndex]
        while (column.size >= RUN_LENGTH) {
            val tail = column.takeLast(RUN_LENGTH)
            if (!tail.all { it.faceUp } || tail.first().rank != 13 || tail.last().rank != 1) return
            if (tail.map { it.suit }.distinct().size != 1) return
            if (tail.zipWithNext().any { (first, second) -> first.rank != second.rank + 1 }) return
            repeat(RUN_LENGTH) { column.removeAt(column.size - 1) }
            completedRuns++
            score += COMPLETED_RUN_SCORE
            revealTop(column)
        }
    }

    private fun revealTop(column: MutableList<Card>) {
        if (column.isNotEmpty()) column.last().faceUp = true
    }

    private fun rememberSnapshot() {
        if (history.size >= MAX_UNDO) history.removeFirst()
        history.addLast(
            Snapshot(
                columns = columns.map { it.map(Card::copy) },
                stock = stock.map(Card::copy),
                completedRuns = completedRuns,
                score = score,
                moves = moves,
            ),
        )
    }

    private fun unchanged(action: Action): ActionResult =
        ActionResult(action = action, changed = false)

    companion object {
        const val COLUMN_COUNT = 10
        const val TOTAL_RUNS = 8
        const val RUN_LENGTH = 13
        private const val START_SCORE = 500
        private const val MOVE_COST = 1
        private const val COMPLETED_RUN_SCORE = 100
        private const val MAX_UNDO = 100
        private const val SAVE_SCHEMA_VERSION = 2
        private const val MAX_SAVE_CHARS = 1_000_000

        private fun shuffledDeck(random: Random): List<Card> = buildList(TOTAL_RUNS * RUN_LENGTH) {
            repeat(TOTAL_RUNS) { deck ->
                for (rank in 1..RUN_LENGTH) add(Card(deck * RUN_LENGTH + rank - 1, rank, 0, false))
            }
        }.shuffled(random)

        private fun deal(random: Random): Deal {
            val deck = shuffledDeck(random)
            val columns = List(COLUMN_COUNT) { mutableListOf<Card>() }
            repeat(54) { index -> columns[index % COLUMN_COUNT].add(deck[index].copy()) }
            columns.forEach { it.last().faceUp = true }
            return Deal(
                columns = columns,
                stock = deck.drop(54).map(Card::copy),
            )
        }

        fun fromJson(json: String): SpiderSolitaireGame? {
            if (json.length !in 2..MAX_SAVE_CHARS) return null
            val map = GameJson.objectOf(GameJson.parse(json)) ?: return null
            val schemaVersion = map["schemaVersion"]?.let(GameJson::intOf) ?: 1
            if (schemaVersion !in 1..SAVE_SCHEMA_VERSION) return null

            fun decodeCards(raw: Any?): List<Card>? {
                val list = raw as? List<*> ?: return null
                if (list.size > TOTAL_RUNS * RUN_LENGTH) return null
                return buildList {
                    for (item in list) {
                        val values = item as? List<*> ?: return null
                        if (values.size != 4) return null
                        val id = GameJson.intOf(values[0]) ?: return null
                        val rank = GameJson.intOf(values[1]) ?: return null
                        val suit = GameJson.intOf(values[2]) ?: return null
                        val faceUp = GameJson.boolOf(values[3]) ?: return null
                        if (
                            id !in 0 until TOTAL_RUNS * RUN_LENGTH ||
                            rank !in 1..RUN_LENGTH ||
                            rank != id % RUN_LENGTH + 1 ||
                            suit != 0
                        ) return null
                        add(Card(id, rank, suit, faceUp))
                    }
                }
            }

            fun decodeSnapshot(raw: Any?): Snapshot? {
                val state = GameJson.objectOf(raw) ?: return null
                val rawColumns = state["columns"] as? List<*> ?: return null
                if (rawColumns.size != COLUMN_COUNT) return null
                val columns = rawColumns.map { decodeCards(it) ?: return null }
                val stock = decodeCards(state["stock"]) ?: return null
                val completed = GameJson.intOf(state["completed"]) ?: return null
                val score = GameJson.intOf(state["score"]) ?: return null
                val moves = GameJson.intOf(state["moves"]) ?: return null
                if (
                    completed !in 0..TOTAL_RUNS ||
                    score < 0 ||
                    moves < 0 ||
                    stock.size % COLUMN_COUNT != 0 ||
                    stock.any { it.faceUp }
                ) return null
                val allCards = columns.flatten() + stock
                if (allCards.map(Card::id).distinct().size != allCards.size) return null
                if (allCards.size + completed * RUN_LENGTH != TOTAL_RUNS * RUN_LENGTH) return null
                if (
                    columns.any { column ->
                        (column.isNotEmpty() && !column.last().faceUp) ||
                            !column.dropWhile { !it.faceUp }.all { it.faceUp }
                    }
                ) return null
                return Snapshot(columns, stock, completed, score, moves)
            }

            val current = decodeSnapshot(map) ?: return null
            val hasPlayedAction = if (map.containsKey("hasPlayedAction")) {
                GameJson.boolOf(map["hasPlayedAction"]) ?: return null
            } else {
                // Older saves did not retain this non-rewindable flag. A positive effective move
                // count is the strongest safe indication that the existing round was played.
                current.moves > 0
            }
            val outcomeRecorded = if (map.containsKey("outcomeRecorded")) {
                GameJson.boolOf(map["outcomeRecorded"]) ?: return null
            } else {
                // A restored completed legacy round must not emit another win.
                current.completedRuns == TOTAL_RUNS
            }
            val rawHistory = when {
                !map.containsKey("history") -> emptyList<Any?>()
                schemaVersion < SAVE_SCHEMA_VERSION -> return null
                else -> map["history"] as? List<*> ?: return null
            }
            if (rawHistory.size > MAX_UNDO) return null
            val restoredHistory = rawHistory.map { decodeSnapshot(it) ?: return null }
            val game = SpiderSolitaireGame(
                current.columns,
                current.stock,
                current.completedRuns,
                current.score,
                current.moves,
                hasPlayedAction,
                outcomeRecorded,
            )
            restoredHistory.forEach(game.history::addLast)
            return game
        }
    }
}
