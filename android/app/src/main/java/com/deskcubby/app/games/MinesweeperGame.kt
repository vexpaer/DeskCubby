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

    enum class Action {
        REVEAL,
        CHORD,
        TOGGLE_FLAG,
    }

    /**
     * Lifetime-statistics increments produced by exactly one accepted player action.
     *
     * These values deliberately do not live in the saved board. Callers can persist them once
     * when the action happens without replaying old progress after restoring a game.
     * [minesCellsRevealed] includes newly revealed safe cells and any mine exposed by the action.
     * [minesSwept] means mines on a successfully cleared board, so a winning action contributes
     * [mineCount] and every other action contributes zero. Removing a flag never subtracts from
     * [flagsPlaced], and automatic flags added after a win are not player placements.
     */
    data class StatisticsDelta(
        val minesCellsRevealed: Int = 0,
        val minesSwept: Int = 0,
        val flagsPlaced: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
    )

    data class ActionResult(
        val action: Action,
        val changed: Boolean,
        val statisticsDelta: StatisticsDelta = StatisticsDelta(),
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

    /** Compatibility wrapper for callers that only need to know whether the board changed. */
    fun reveal(x: Int, y: Int): Boolean = revealWithResult(x, y).changed

    fun revealWithResult(x: Int, y: Int): ActionResult {
        if (isGameOver || isWon || x !in 0 until width || y !in 0 until height) {
            return unchanged(Action.REVEAL)
        }
        val start = index(x, y)
        if (flagged[start] || revealed[start]) return unchanged(Action.REVEAL)
        if (!initialized) placeMines(firstX = x, firstY = y)
        if (mines[start]) {
            revealed[start] = true
            isGameOver = true
            return ActionResult(
                action = Action.REVEAL,
                changed = true,
                statisticsDelta = StatisticsDelta(
                    minesCellsRevealed = 1,
                    losses = 1,
                ),
            )
        }

        val newlyRevealed = revealSafeRegion(start)
        val wonThisAction = finishWinIfCleared()
        return ActionResult(
            action = Action.REVEAL,
            changed = newlyRevealed > 0,
            statisticsDelta = StatisticsDelta(
                minesCellsRevealed = newlyRevealed,
                minesSwept = if (wonThisAction) mineCount else 0,
                wins = if (wonThisAction) 1 else 0,
            ),
        )
    }

    /** Compatibility wrapper for the double-tap/chord action. */
    fun chord(x: Int, y: Int): Boolean = chordWithResult(x, y).changed

    /**
     * Reveals every unrevealed, unflagged neighbor of an already revealed numbered cell.
     *
     * Unlike the classic flag-count shortcut, this interaction intentionally does not require
     * the adjacent flag count to match the number. A wrong flag can therefore expose a mine and
     * end the run. Flood reveal still applies to any zero-valued neighbor opened by the chord.
     */
    fun chordWithResult(x: Int, y: Int): ActionResult {
        if (isGameOver || isWon || x !in 0 until width || y !in 0 until height) {
            return unchanged(Action.CHORD)
        }
        val center = index(x, y)
        if (!revealed[center] || mines[center] || adjacentMineCount(x, y) <= 0) {
            return unchanged(Action.CHORD)
        }
        val targets = neighbors(x, y).filter { !revealed[it] && !flagged[it] }
        if (targets.isEmpty()) return unchanged(Action.CHORD)

        val revealedBefore = revealed.count { it }
        var triggeredMine = false
        targets.forEach { target ->
            if (mines[target]) {
                revealed[target] = true
                triggeredMine = true
            } else {
                revealSafeRegion(target)
            }
        }
        if (triggeredMine) isGameOver = true
        val wonThisAction = !triggeredMine && finishWinIfCleared()
        val newlyRevealed = revealed.count { it } - revealedBefore
        return ActionResult(
            action = Action.CHORD,
            changed = newlyRevealed > 0,
            statisticsDelta = StatisticsDelta(
                minesCellsRevealed = newlyRevealed,
                minesSwept = if (wonThisAction) mineCount else 0,
                wins = if (wonThisAction) 1 else 0,
                losses = if (triggeredMine) 1 else 0,
            ),
        )
    }

    /** Compatibility wrapper for callers that only need to know whether the board changed. */
    fun toggleFlag(x: Int, y: Int): Boolean = toggleFlagWithResult(x, y).changed

    fun toggleFlagWithResult(x: Int, y: Int): ActionResult {
        if (isGameOver || isWon || x !in 0 until width || y !in 0 until height) {
            return unchanged(Action.TOGGLE_FLAG)
        }
        val index = index(x, y)
        if (revealed[index]) return unchanged(Action.TOGGLE_FLAG)
        if (!flagged[index] && flagged.count { it } >= mineCount) {
            return unchanged(Action.TOGGLE_FLAG)
        }
        val placingFlag = !flagged[index]
        flagged[index] = !flagged[index]
        return ActionResult(
            action = Action.TOGGLE_FLAG,
            changed = true,
            statisticsDelta = StatisticsDelta(flagsPlaced = if (placingFlag) 1 else 0),
        )
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

    private fun revealSafeRegion(start: Int): Int {
        var newlyRevealed = 0
        val queue = ArrayDeque<Int>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (revealed[current] || flagged[current] || mines[current]) continue
            revealed[current] = true
            newlyRevealed++
            val cx = current % width
            val cy = current / width
            if (adjacentMineCount(cx, cy) == 0) {
                neighbors(cx, cy).forEach { neighbor ->
                    if (!revealed[neighbor] && !flagged[neighbor] && !mines[neighbor]) {
                        queue.add(neighbor)
                    }
                }
            }
        }
        return newlyRevealed
    }

    private fun finishWinIfCleared(): Boolean {
        if (isGameOver || isWon || revealedSafeCount != width * height - mineCount) return false
        isWon = true
        mines.indices.filter { mines[it] }.forEach { flagged[it] = true }
        return true
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

    private fun unchanged(action: Action): ActionResult = ActionResult(action, changed = false)

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
            // A chord can expose a mine and the final safe cells in the same atomic action. That
            // is a valid loss, whereas an unfinished board with every safe cell open is not.
            if (!won && !over && revealed.indices.count { revealed[it] && !mines[it] } == size - count) return null
            return MinesweeperGame(
                width, height, count, random, mines, revealed, flagged,
                initialized, over, won,
            )
        }
    }
}
