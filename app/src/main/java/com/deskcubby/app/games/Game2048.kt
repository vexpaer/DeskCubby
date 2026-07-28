package com.deskcubby.app.games

import kotlin.random.Random

/**
 * Pure-Kotlin 2048 engine on a 4x4, 5x5 or 6x6 board. No Android/Compose dependencies so the rules can be
 * unit tested on the JVM. Pass a seeded [Random] for deterministic tile spawning in tests.
 */
class Game2048 private constructor(
    private val cells: IntArray,
    val size: Int,
    initialScore: Int,
    private val random: Random,
) {

    /** Starts a fresh game with two spawned tiles. */
    constructor(random: Random = Random.Default) : this(SIZE, random)

    constructor(size: Int, random: Random = Random.Default) :
        this(IntArray(validatedCellCount(size)), size, 0, random) {
        spawnTile()
        spawnTile()
    }

    var score: Int = initialScore
        private set

    /** Row-major snapshot of all tile values (0 = empty). */
    val board: List<Int> get() = cells.toList()

    fun cellAt(row: Int, column: Int): Int = cells[row * size + column]

    /** True when no move in any direction can change the board. */
    val isGameOver: Boolean get() = !hasAnyMove()

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    /**
     * One source tile's visual path during a successful move.
     *
     * [fromIndex] and [toIndex] are row-major board indices. Two motions with the same
     * [toIndex] and [merged] set merge into the corresponding [Merge] event.
     */
    data class TileMotion(
        val fromIndex: Int,
        val toIndex: Int,
        val value: Int,
        val merged: Boolean,
    )

    /** The result tile produced by two source tiles converging on [toIndex]. */
    data class Merge(
        val toIndex: Int,
        val value: Int,
    )

    /** The new random tile added only after a successful slide/merge. */
    data class Spawn(
        val index: Int,
        val value: Int,
    )

    /**
     * Immutable transition data for one successful move.
     *
     * The engine has already committed [after], including [spawn], when this is returned. That
     * keeps persistence authoritative even if a UI animation is cancelled or replaced.
     */
    data class MoveResult(
        val before: List<Int>,
        val after: List<Int>,
        val motions: List<TileMotion>,
        val merges: List<Merge>,
        val spawn: Spawn,
        val scoreGained: Int,
    )

    /**
     * Applies one move with the standard merge rules (each pair merges at most once per move).
     * When the board changed, one random tile (2 with 90% probability, else 4) is spawned and
     * true is returned; otherwise the board is untouched and false is returned.
     */
    fun move(direction: Direction): Boolean = moveWithResult(direction) != null

    /**
     * Applies one move and returns the exact source-to-destination mapping needed for animation.
     * Returns null without changing the board, score or random generator when the move is invalid.
     */
    fun moveWithResult(direction: Direction): MoveResult? {
        val before = cells.toList()
        val next = IntArray(cells.size)
        val motions = ArrayList<TileMotion>(cells.size)
        val merges = ArrayList<Merge>(size * 2)
        var scoreGained = 0

        for (line in 0 until size) {
            val indices = lineIndices(direction, line)
            val tiles = ArrayList<Pair<Int, Int>>(size)
            for (index in indices) {
                val value = cells[index]
                if (value != 0) tiles += index to value
            }
            var sourcePosition = 0
            var destinationPosition = 0
            while (sourcePosition < tiles.size) {
                val first = tiles[sourcePosition]
                val second = tiles.getOrNull(sourcePosition + 1)
                val destination = indices[destinationPosition]
                if (second != null && first.second == second.second) {
                    val mergedValue = first.second * 2
                    next[destination] = mergedValue
                    motions += TileMotion(
                        fromIndex = first.first,
                        toIndex = destination,
                        value = first.second,
                        merged = true,
                    )
                    motions += TileMotion(
                        fromIndex = second.first,
                        toIndex = destination,
                        value = second.second,
                        merged = true,
                    )
                    merges += Merge(toIndex = destination, value = mergedValue)
                    scoreGained += mergedValue
                    sourcePosition += 2
                } else {
                    next[destination] = first.second
                    motions += TileMotion(
                        fromIndex = first.first,
                        toIndex = destination,
                        value = first.second,
                        merged = false,
                    )
                    sourcePosition++
                }
                destinationPosition++
            }
        }

        if (cells.contentEquals(next)) return null

        next.copyInto(cells)
        score += scoreGained
        val spawn = spawnTile()
            ?: error("A successful 2048 move must leave room for exactly one spawned tile")
        return MoveResult(
            before = before,
            after = cells.toList(),
            motions = motions,
            merges = merges,
            spawn = spawn,
            scoreGained = scoreGained,
        )
    }

    /** Serializes the complete restorable state as JSON. */
    fun toJson(): String = buildString {
        append("{\"size\":").append(size).append(",\"cells\":[")
        cells.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append("],\"score\":").append(score).append('}')
    }

    /** Cell indices of one line, ordered from the edge the tiles slide towards. */
    private fun lineIndices(direction: Direction, line: Int): IntArray = when (direction) {
        Direction.LEFT -> IntArray(size) { line * size + it }
        Direction.RIGHT -> IntArray(size) { line * size + (size - 1 - it) }
        Direction.UP -> IntArray(size) { it * size + line }
        Direction.DOWN -> IntArray(size) { (size - 1 - it) * size + line }
    }

    private fun spawnTile(): Spawn? {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return null
        val index = empty[random.nextInt(empty.size)]
        val value = if (random.nextFloat() < 0.9f) 2 else 4
        cells[index] = value
        return Spawn(index = index, value = value)
    }

    private fun hasAnyMove(): Boolean {
        for (index in cells.indices) {
            if (cells[index] == 0) return true
            val row = index / size
            val column = index % size
            if (column + 1 < size && cells[index] == cells[index + 1]) return true
            if (row + 1 < size && cells[index] == cells[index + size]) return true
        }
        return false
    }

    companion object {
        const val SIZE = 4
        const val MIN_SIZE = 4
        const val MAX_SIZE = 6

        /** Restores a game from [toJson] output. Returns null (never throws) on invalid input. */
        fun fromJson(
            json: String,
            random: Random = Random.Default,
            expectedSize: Int? = null,
        ): Game2048? {
            val map = GameJson.objectOf(GameJson.parse(json)) ?: return null
            val size = map["size"]?.let(GameJson::intOf) ?: SIZE
            if (size !in MIN_SIZE..MAX_SIZE || expectedSize?.let { it != size } == true) return null
            val cells = GameJson.intListOf(map["cells"]) ?: return null
            if (cells.size != size * size || cells.any { it < 0 }) return null
            val score = GameJson.intOf(map["score"]) ?: return null
            if (score < 0) return null
            return Game2048(cells.toIntArray(), size, score, random)
        }

        private fun validatedCellCount(size: Int): Int {
            require(size in MIN_SIZE..MAX_SIZE) { "2048 board size must be 4, 5, or 6" }
            return size * size
        }
    }
}

/**
 * Minimal JSON reader shared by the game engines in this package.
 *
 * The engines intentionally do not use org.json: that package ships inside the Android platform
 * jar and is stubbed out ("method ... not mocked") in local JVM unit tests, which would make the
 * required save/restore round-trip tests fail. The engines emit standard JSON via plain string
 * building and this tiny recursive-descent parser reads it back, keeping everything pure Kotlin
 * without adding any new dependency.
 */
internal object GameJson {

    /** Parses [text] into Map<String, Any?> / List<Any?> / String / Long / Double / Boolean / null. */
    fun parse(text: String): Any? = try {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (parser.atEnd) value else null
    } catch (_: Exception) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun objectOf(value: Any?): Map<String, Any?>? =
        if (value is Map<*, *> && value.keys.all { it is String }) value as Map<String, Any?> else null

    fun intOf(value: Any?): Int? =
        if (value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null

    fun boolOf(value: Any?): Boolean? = value as? Boolean

    fun stringOf(value: Any?): String? = value as? String

    fun intListOf(value: Any?): List<Int>? {
        val list = value as? List<*> ?: return null
        val result = ArrayList<Int>(list.size)
        for (item in list) result.add(intOf(item) ?: return null)
        return result
    }

    private class Parser(private val text: String) {
        private var index = 0

        val atEnd: Boolean get() = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, index))
            index += literal.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                val terminator = next()
                if (terminator == '}') return result
                require(terminator == ',')
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                val terminator = next()
                if (terminator == ']') return result
                require(terminator == ',')
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                when (val ch = next()) {
                    '"' -> return builder.toString()
                    '\\' -> when (val escaped = next()) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append(0x0C.toChar())
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length)
                            builder.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("Unsupported escape: $escaped")
                    }
                    else -> builder.append(ch)
                }
            }
        }

        private fun readNumber(): Any {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun peek(): Char {
            require(index < text.length)
            return text[index]
        }

        private fun next(): Char {
            require(index < text.length)
            return text[index++]
        }

        private fun expect(expected: Char) {
            require(next() == expected)
        }
    }
}
