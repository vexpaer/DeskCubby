package com.deskcubby.app.games

/**
 * Pure-Kotlin local two-player Go engine.
 *
 * It implements captures, suicide prevention, positional simple ko and two consecutive passes.
 * Territory scoring is deliberately left to the players; the completed-game view reports captures.
 */
class GoGame private constructor(
    val size: Int,
    private var cells: IntArray,
    currentPlayer: Stone,
    capturedByBlack: Int,
    capturedByWhite: Int,
    consecutivePasses: Int,
    isFinished: Boolean,
    turnCount: Int,
    private var previousCells: IntArray?,
    lastMove: Point?,
) {
    enum class Stone(val code: Int) {
        EMPTY(0),
        BLACK(1),
        WHITE(2),
        ;

        val opponent: Stone
            get() = when (this) {
                BLACK -> WHITE
                WHITE -> BLACK
                EMPTY -> EMPTY
            }

        companion object {
            fun fromCode(code: Int): Stone? = entries.firstOrNull { it.code == code }
        }
    }

    enum class MoveError {
        OUT_OF_BOUNDS,
        OCCUPIED,
        SUICIDE,
        KO,
        GAME_FINISHED,
    }

    data class Point(val x: Int, val y: Int)

    data class StatisticsDelta(
        val movesPlayed: Int = 0,
        val stonesCaptured: Int = 0,
        val passes: Int = 0,
        val gamesCompleted: Int = 0,
    ) {
        val isEmpty: Boolean
            get() = movesPlayed == 0 && stonesCaptured == 0 && passes == 0 &&
                gamesCompleted == 0
    }

    data class MoveResult(
        val accepted: Boolean,
        val error: MoveError? = null,
        val captured: Int = 0,
        val statisticsDelta: StatisticsDelta = StatisticsDelta(),
    )

    var currentPlayer: Stone = currentPlayer
        private set

    var capturedByBlack: Int = capturedByBlack
        private set

    var capturedByWhite: Int = capturedByWhite
        private set

    var consecutivePasses: Int = consecutivePasses
        private set

    var isFinished: Boolean = isFinished
        private set

    var turnCount: Int = turnCount
        private set

    var lastMove: Point? = lastMove
        private set

    constructor(size: Int = DEFAULT_SIZE) : this(
        size = size.also { requireSupportedSize(it) },
        cells = IntArray(size * size),
        currentPlayer = Stone.BLACK,
        capturedByBlack = 0,
        capturedByWhite = 0,
        consecutivePasses = 0,
        isFinished = false,
        turnCount = 0,
        previousCells = null,
        lastMove = null,
    )

    fun stoneAt(x: Int, y: Int): Stone {
        require(x in 0 until size && y in 0 until size) { "Point is outside the board" }
        return Stone.fromCode(cells[index(x, y)]) ?: Stone.EMPTY
    }

    fun boardSnapshot(): List<Stone> = cells.map { Stone.fromCode(it) ?: Stone.EMPTY }

    /**
     * Returns a detached copy of the current position.
     *
     * [GoGame] deliberately keeps its move implementation mutable, but UI state holders must
     * publish a new object after a successful move so observers such as Compose can reliably
     * detect the change. Both board arrays are copied to keep later moves on either instance from
     * leaking into the other one.
     */
    fun snapshotCopy(): GoGame = GoGame(
        size = size,
        cells = cells.copyOf(),
        currentPlayer = currentPlayer,
        capturedByBlack = capturedByBlack,
        capturedByWhite = capturedByWhite,
        consecutivePasses = consecutivePasses,
        isFinished = isFinished,
        turnCount = turnCount,
        previousCells = previousCells?.copyOf(),
        lastMove = lastMove,
    )

    fun play(x: Int, y: Int): MoveResult {
        if (isFinished) return rejected(MoveError.GAME_FINISHED)
        if (x !in 0 until size || y !in 0 until size) {
            return rejected(MoveError.OUT_OF_BOUNDS)
        }
        val playedIndex = index(x, y)
        if (cells[playedIndex] != Stone.EMPTY.code) return rejected(MoveError.OCCUPIED)

        val before = cells.copyOf()
        val candidate = before.copyOf()
        candidate[playedIndex] = currentPlayer.code

        var captured = 0
        val checkedOpponent = BooleanArray(candidate.size)
        neighbors(playedIndex).forEach { neighbor ->
            if (
                candidate[neighbor] == currentPlayer.opponent.code &&
                !checkedOpponent[neighbor]
            ) {
                val group = collectGroup(candidate, neighbor)
                group.stones.forEach { checkedOpponent[it] = true }
                if (!group.hasLiberty) {
                    group.stones.forEach { candidate[it] = Stone.EMPTY.code }
                    captured += group.stones.size
                }
            }
        }

        if (!collectGroup(candidate, playedIndex).hasLiberty) {
            return rejected(MoveError.SUICIDE)
        }
        if (previousCells?.contentEquals(candidate) == true) {
            return rejected(MoveError.KO)
        }

        cells = candidate
        previousCells = before
        if (currentPlayer == Stone.BLACK) {
            capturedByBlack += captured
        } else {
            capturedByWhite += captured
        }
        consecutivePasses = 0
        turnCount += 1
        lastMove = Point(x, y)
        currentPlayer = currentPlayer.opponent
        return MoveResult(
            accepted = true,
            captured = captured,
            statisticsDelta = StatisticsDelta(
                movesPlayed = 1,
                stonesCaptured = captured,
            ),
        )
    }

    fun pass(): MoveResult {
        if (isFinished) return rejected(MoveError.GAME_FINISHED)
        previousCells = cells.copyOf()
        consecutivePasses += 1
        turnCount += 1
        lastMove = null
        currentPlayer = currentPlayer.opponent
        if (consecutivePasses >= 2) isFinished = true
        return MoveResult(
            accepted = true,
            statisticsDelta = StatisticsDelta(
                passes = 1,
                gamesCompleted = if (isFinished) 1 else 0,
            ),
        )
    }

    fun toJson(): String = buildString {
        append("{\"v\":").append(SAVE_VERSION)
        append(",\"size\":").append(size)
        append(",\"board\":")
        appendIntArray(cells)
        append(",\"current\":").append(currentPlayer.code)
        append(",\"capturedByBlack\":").append(capturedByBlack)
        append(",\"capturedByWhite\":").append(capturedByWhite)
        append(",\"passes\":").append(consecutivePasses)
        append(",\"finished\":").append(isFinished)
        append(",\"turnCount\":").append(turnCount)
        append(",\"previousBoard\":")
        val previous = previousCells
        if (previous == null) append("null") else appendIntArray(previous)
        append(",\"lastMove\":")
        lastMove?.let { point ->
            append("{\"x\":").append(point.x)
            append(",\"y\":").append(point.y).append('}')
        } ?: append("null")
        append('}')
    }

    private fun StringBuilder.appendIntArray(values: IntArray) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append(']')
    }

    private fun rejected(error: MoveError): MoveResult = MoveResult(
        accepted = false,
        error = error,
    )

    private fun collectGroup(board: IntArray, start: Int): Group {
        val color = board[start]
        val seen = BooleanArray(board.size)
        val stack = ArrayDeque<Int>()
        val stones = ArrayList<Int>()
        var hasLiberty = false
        seen[start] = true
        stack.addLast(start)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            stones += current
            neighbors(current).forEach { neighbor ->
                when (board[neighbor]) {
                    Stone.EMPTY.code -> hasLiberty = true
                    color -> if (!seen[neighbor]) {
                        seen[neighbor] = true
                        stack.addLast(neighbor)
                    }
                }
            }
        }
        return Group(stones, hasLiberty)
    }

    private fun neighbors(cellIndex: Int): IntArray {
        val x = cellIndex % size
        val y = cellIndex / size
        val result = IntArray(4)
        var count = 0
        if (x > 0) result[count++] = cellIndex - 1
        if (x < size - 1) result[count++] = cellIndex + 1
        if (y > 0) result[count++] = cellIndex - size
        if (y < size - 1) result[count++] = cellIndex + size
        return result.copyOf(count)
    }

    private fun index(x: Int, y: Int): Int = y * size + x

    private data class Group(val stones: List<Int>, val hasLiberty: Boolean)

    companion object {
        const val DEFAULT_SIZE = 9
        val SUPPORTED_SIZES = listOf(9, 13, 19)

        private const val SAVE_VERSION = 1
        private const val MAX_COUNTER = 10_000_000

        fun fromJson(json: String): GoGame? {
            return try {
                val root = GameJson.objectOf(GameJson.parse(json)) ?: return null
                val version = root["v"]?.let(GameJson::intOf) ?: SAVE_VERSION
                if (version != SAVE_VERSION) return null
                val size = GameJson.intOf(root["size"]) ?: return null
                requireSupportedSize(size)
                val cells = root["board"].toCells(size) ?: return null
                val current = Stone.fromCode(GameJson.intOf(root["current"]) ?: return null)
                    ?.takeIf { it != Stone.EMPTY }
                    ?: return null
                val capturedByBlack = GameJson.intOf(root["capturedByBlack"]) ?: return null
                val capturedByWhite = GameJson.intOf(root["capturedByWhite"]) ?: return null
                val passes = GameJson.intOf(root["passes"]) ?: return null
                val finished = GameJson.boolOf(root["finished"]) ?: return null
                val turnCount = GameJson.intOf(root["turnCount"]) ?: return null
                if (
                    capturedByBlack !in 0..MAX_COUNTER ||
                    capturedByWhite !in 0..MAX_COUNTER ||
                    passes !in 0..2 ||
                    turnCount !in 0..MAX_COUNTER ||
                    finished != (passes >= 2)
                ) {
                    return null
                }
                val previous = root["previousBoard"]?.toCells(size) ?: run {
                    if (root["previousBoard"] == null) null else return null
                }
                val lastMove = root["lastMove"]?.let { rawPoint ->
                    val point = GameJson.objectOf(rawPoint) ?: return null
                    Point(
                        GameJson.intOf(point["x"]) ?: return null,
                        GameJson.intOf(point["y"]) ?: return null,
                    )
                            .takeIf { it.x in 0 until size && it.y in 0 until size }
                            ?: return null
                }
                if (lastMove != null && cells[lastMove.y * size + lastMove.x] == Stone.EMPTY.code) {
                    return null
                }
                GoGame(
                    size = size,
                    cells = cells,
                    currentPlayer = current,
                    capturedByBlack = capturedByBlack,
                    capturedByWhite = capturedByWhite,
                    consecutivePasses = passes,
                    isFinished = finished,
                    turnCount = turnCount,
                    previousCells = previous,
                    lastMove = lastMove,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun requireSupportedSize(size: Int) {
            require(size in SUPPORTED_SIZES) { "Unsupported Go board size" }
        }

        private fun Any?.toCells(size: Int): IntArray? {
            val values = GameJson.intListOf(this) ?: return null
            if (values.size != size * size) return null
            val result = IntArray(values.size)
            for (index in result.indices) {
                val code = values[index]
                if (Stone.fromCode(code) == null) return null
                result[index] = code
            }
            return result
        }
    }
}
