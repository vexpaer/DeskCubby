package com.deskcubby.app.games

import kotlin.random.Random

/** A complete one-suit Spider Solitaire engine designed for tap-to-move landscape play. */
class SpiderSolitaireGame private constructor(
    initialColumns: List<List<Card>>,
    initialStock: List<Card>,
    initialCompletedRuns: Int,
    initialScore: Int,
    initialMoves: Int,
) {
    private data class Deal(val columns: List<List<Card>>, val stock: List<Card>)

    private constructor(deal: Deal) : this(
        initialColumns = deal.columns,
        initialStock = deal.stock,
        initialCompletedRuns = 0,
        initialScore = START_SCORE,
        initialMoves = 0,
    )

    constructor(random: Random = Random.Default) : this(deal(random))

    data class Card(
        val id: Int,
        val rank: Int,
        val suit: Int,
        var faceUp: Boolean,
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

    fun move(fromColumn: Int, cardIndex: Int, toColumn: Int): Boolean {
        if (isWon || fromColumn == toColumn || !canSelect(fromColumn, cardIndex)) return false
        val source = columns.getOrNull(fromColumn) ?: return false
        val target = columns.getOrNull(toColumn) ?: return false
        val movingFirst = source[cardIndex]
        if (target.isNotEmpty() && target.last().rank != movingFirst.rank + 1) return false
        rememberSnapshot()
        val moving = source.subList(cardIndex, source.size).map(Card::copy)
        repeat(source.size - cardIndex) { source.removeAt(source.size - 1) }
        target.addAll(moving)
        revealTop(source)
        score = (score - MOVE_COST).coerceAtLeast(0)
        moves++
        removeCompletedRuns(fromColumn)
        removeCompletedRuns(toColumn)
        return true
    }

    fun dealStock(): Boolean {
        if (!canDealStock) return false
        rememberSnapshot()
        repeat(COLUMN_COUNT) { column ->
            columns[column].add(stock.removeAt(0).copy(faceUp = true))
        }
        score = (score - MOVE_COST).coerceAtLeast(0)
        moves++
        repeat(COLUMN_COUNT, ::removeCompletedRuns)
        return true
    }

    fun undo(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        columns.clear()
        columns.addAll(previous.columns.map { it.map(Card::copy).toMutableList() })
        stock.clear()
        stock.addAll(previous.stock.map(Card::copy))
        completedRuns = previous.completedRuns
        score = previous.score
        moves = previous.moves
        return true
    }

    fun toJson(): String = buildString {
        append("{\"columns\":[")
        columns.forEachIndexed { columnIndex, column ->
            if (columnIndex > 0) append(',')
            appendCards(column)
        }
        append("],\"stock\":")
        appendCards(stock)
        append(",\"completed\":").append(completedRuns)
        append(",\"score\":").append(score)
        append(",\"moves\":").append(moves)
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

    companion object {
        const val COLUMN_COUNT = 10
        const val TOTAL_RUNS = 8
        const val RUN_LENGTH = 13
        private const val START_SCORE = 500
        private const val MOVE_COST = 1
        private const val COMPLETED_RUN_SCORE = 100
        private const val MAX_UNDO = 100

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
            val map = GameJson.objectOf(GameJson.parse(json)) ?: return null
            val rawColumns = map["columns"] as? List<*> ?: return null
            if (rawColumns.size != COLUMN_COUNT) return null

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

            val columns = rawColumns.map { decodeCards(it) ?: return null }
            val stock = decodeCards(map["stock"]) ?: return null
            val completed = GameJson.intOf(map["completed"]) ?: return null
            val score = GameJson.intOf(map["score"]) ?: return null
            val moves = GameJson.intOf(map["moves"]) ?: return null
            if (completed !in 0..TOTAL_RUNS || score < 0 || moves < 0 || stock.size % COLUMN_COUNT != 0) return null
            val allCards = columns.flatten() + stock
            if (allCards.map(Card::id).distinct().size != allCards.size) return null
            if (allCards.size + completed * RUN_LENGTH != TOTAL_RUNS * RUN_LENGTH) return null
            if (columns.any { column -> !column.dropWhile { !it.faceUp }.all { it.faceUp } }) {
                return null
            }
            return SpiderSolitaireGame(columns, stock, completed, score, moves)
        }
    }
}
