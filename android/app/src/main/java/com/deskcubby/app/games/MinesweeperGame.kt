package com.deskcubby.app.games

import kotlin.random.Random

/** Pure-Kotlin, first-tap-safe Minesweeper engine with bounded custom dimensions. */
class MinesweeperGame private constructor(
    val width: Int,
    val height: Int,
    val mineCount: Int,
    private val random: Random,
    private val mines: BooleanArray,
    private val revealed: BooleanArray,
    private val flagged: BooleanArray,
    initialized: Boolean,
    gameOver: Boolean,
    won: Boolean,
) {
    constructor(
        width: Int = 9,
        height: Int = 9,
        mineCount: Int = 10,
        random: Random = Random.Default,
    ) : this(
        width = width,
        height = height,
        mineCount = mineCount,
        random = random,
        mines = BooleanArray(width * height),
        revealed = BooleanArray(width * height),
        flagged = BooleanArray(width * height),
        initialized = false,
        gameOver = false,
        won = false,
    ) {
        requireValidDimensions(width, height, mineCount)
    }

    data class Cell(
        val revealed: Boolean,
        val flagged: Boolean,
        val adjacentMines: Int,
        val mine: Boolean,
    )

    var initialized: Boolean = initialized
        private set
    var isGameOver: Boolean = gameOver
        private set
    var isWon: Boolean = won
        private set

    val remainingMines: Int
        get() = (mineCount - flagged.count { it }).coerceAtLeast(0)

    val revealedSafeCount: Int
        get() = revealed.indices.count { revealed[it] && !mines[it] }

    fun cell(x: Int, y: Int): Cell {
        require(x in 0 until width && y in 0 until height)
        val index = index(x, y)
        val exposeMine = mines[index] && (isGameOver || isWon || revealed[index])
        return Cell(
            revealed = revealed[index],
            flagged = flagged[index],
            adjacentMines = if (revealed[index] && !mines[index]) adjacentMineCount(x, y) else 0,
            mine = exposeMine,
        )
    }

    fun reveal(x: Int, y: Int): Boolean {
        if (isGameOver || isWon || x !in 0 until width || y !in 0 until height) return false
        val start = index(x, y)
        if (flagged[start] || revealed[start]) return false
        if (!initialized) placeMines(firstX = x, firstY = y)
        if (mines[start]) {
            revealed[start] = true
            isGameOver = true
            return true
        }

        val queue = ArrayDeque<Int>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (revealed[current] || flagged[current] || mines[current]) continue
            revealed[current] = true
            val cx = current % width
            val cy = current / width
            if (adjacentMineCount(cx, cy) == 0) {
                neighbors(cx, cy).forEach { neighbor ->
                    if (!revealed[neighbor] && !flagged[neighbor] && !mines[neighbor]) queue.add(neighbor)
                }
            }
        }
        if (revealedSafeCount == width * height - mineCount) {
            isWon = true
            mines.indices.filter { mines[it] }.forEach { flagged[it] = true }
        }
        return true
    }

    fun toggleFlag(x: Int, y: Int): Boolean {
        if (isGameOver || isWon || x !in 0 until width || y !in 0 until height) return false
        val index = index(x, y)
        if (revealed[index]) return false
        if (!flagged[index] && flagged.count { it } >= mineCount) return false
        flagged[index] = !flagged[index]
        return true
    }

    fun toJson(): String = buildString {
        append("{\"w\":").append(width)
        append(",\"h\":").append(height)
        append(",\"count\":").append(mineCount)
        append(",\"initialized\":").append(initialized)
        append(",\"over\":").append(isGameOver)
        append(",\"won\":").append(isWon)
        append(",\"mines\":").append(indexArrayJson(mines))
        append(",\"revealed\":").append(indexArrayJson(revealed))
        append(",\"flagged\":").append(indexArrayJson(flagged))
        append('}')
    }

    private fun placeMines(firstX: Int, firstY: Int) {
        val broadExclusion = buildSet {
            add(index(firstX, firstY))
            addAll(neighbors(firstX, firstY))
        }
        val exclusion = if (width * height - broadExclusion.size >= mineCount) {
            broadExclusion
        } else {
            setOf(index(firstX, firstY))
        }
        val candidates = mines.indices.filterNot(exclusion::contains).shuffled(random)
        candidates.take(mineCount).forEach { mines[it] = true }
        initialized = true
    }

    private fun adjacentMineCount(x: Int, y: Int): Int = neighbors(x, y).count { mines[it] }

    private fun neighbors(x: Int, y: Int): List<Int> = buildList(8) {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until width && ny in 0 until height) add(index(nx, ny))
        }
    }

    private fun index(x: Int, y: Int): Int = y * width + x

    companion object {
        const val MIN_WIDTH = 6
        const val MAX_WIDTH = 30
        const val MIN_HEIGHT = 6
        const val MAX_HEIGHT = 30

        private fun requireValidDimensions(width: Int, height: Int, mineCount: Int) {
            require(width in MIN_WIDTH..MAX_WIDTH)
            require(height in MIN_HEIGHT..MAX_HEIGHT)
            require(mineCount in 1 until width * height)
        }

        private fun indexArrayJson(values: BooleanArray): String = buildString {
            append('[')
            var first = true
            values.forEachIndexed { index, value ->
                if (value) {
                    if (!first) append(',')
                    append(index)
                    first = false
                }
            }
            append(']')
        }

        fun fromJson(json: String, random: Random = Random.Default): MinesweeperGame? {
            val map = GameJson.objectOf(GameJson.parse(json)) ?: return null
            val width = GameJson.intOf(map["w"]) ?: return null
            val height = GameJson.intOf(map["h"]) ?: return null
            val count = GameJson.intOf(map["count"]) ?: return null
            runCatching { requireValidDimensions(width, height, count) }.getOrElse { return null }
            val size = width * height
            fun decodeFlags(name: String): BooleanArray? {
                val indices = GameJson.intListOf(map[name]) ?: return null
                if (indices.size != indices.distinct().size || indices.any { it !in 0 until size }) return null
                return BooleanArray(size).also { values -> indices.forEach { values[it] = true } }
            }
            val mines = decodeFlags("mines") ?: return null
            val revealed = decodeFlags("revealed") ?: return null
            val flagged = decodeFlags("flagged") ?: return null
            val initialized = GameJson.boolOf(map["initialized"]) ?: return null
            val over = GameJson.boolOf(map["over"]) ?: return null
            val won = GameJson.boolOf(map["won"]) ?: return null
            if (initialized && mines.count { it } != count) return null
            if (!initialized && mines.any { it }) return null
            if (revealed.indices.any { revealed[it] && flagged[it] }) return null
            if ((over || won) && !initialized) return null
            if (over && won) return null
            if (over != revealed.indices.any { revealed[it] && mines[it] }) return null
            if (!over && revealed.indices.any { revealed[it] && mines[it] }) return null
            if (flagged.count { it } > count) return null
            if (won && revealed.indices.count { revealed[it] && !mines[it] } != size - count) return null
            if (!won && revealed.indices.count { revealed[it] && !mines[it] } == size - count) return null
            return MinesweeperGame(
                width, height, count, random, mines, revealed, flagged,
                initialized, over, won,
            )
        }
    }
}
