package com.deskcubby.app.games

import kotlin.random.Random

/**
 * Pure-Kotlin 2048 engine on a 4x4 board. No Android/Compose dependencies so the rules can be
 * unit tested on the JVM. Pass a seeded [Random] for deterministic tile spawning in tests.
 */
class Game2048 private constructor(
    private val cells: IntArray,
    initialScore: Int,
    private val random: Random,
) {

    /** Starts a fresh game with two spawned tiles. */
    constructor(random: Random = Random.Default) : this(IntArray(CELL_COUNT), 0, random) {
        spawnTile()
        spawnTile()
    }

    var score: Int = initialScore
        private set

    /** Row-major snapshot of the 16 tile values (0 = empty). */
    val board: List<Int> get() = cells.toList()

    fun cellAt(row: Int, column: Int): Int = cells[row * SIZE + column]

    /** True when no move in any direction can change the board. */
    val isGameOver: Boolean get() = !hasAnyMove()

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    /**
     * Applies one move with the standard merge rules (each pair merges at most once per move).
     * When the board changed, one random tile (2 with 90% probability, else 4) is spawned and
     * true is returned; otherwise the board is untouched and false is returned.
     */
    fun move(direction: Direction): Boolean {
        val changed = slideAndMerge(direction)
        if (changed) spawnTile()
        return changed
    }

    /** Serializes the complete restorable state as JSON. */
    fun toJson(): String = buildString {
        append("{\"cells\":[")
        cells.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append("],\"score\":").append(score).append('}')
    }

    private fun slideAndMerge(direction: Direction): Boolean {
        var changed = false
        for (line in 0 until SIZE) {
            val indices = lineIndices(direction, line)
            val values = indices.map { cells[it] }.filter { it != 0 }
            val merged = ArrayList<Int>(SIZE)
            var i = 0
            while (i < values.size) {
                if (i + 1 < values.size && values[i] == values[i + 1]) {
                    val sum = values[i] * 2
                    merged.add(sum)
                    score += sum
                    i += 2
                } else {
                    merged.add(values[i])
                    i++
                }
            }
            for (position in 0 until SIZE) {
                val value = merged.getOrElse(position) { 0 }
                if (cells[indices[position]] != value) changed = true
                cells[indices[position]] = value
            }
        }
        return changed
    }

    /** Cell indices of one line, ordered from the edge the tiles slide towards. */
    private fun lineIndices(direction: Direction, line: Int): IntArray = when (direction) {
        Direction.LEFT -> IntArray(SIZE) { line * SIZE + it }
        Direction.RIGHT -> IntArray(SIZE) { line * SIZE + (SIZE - 1 - it) }
        Direction.UP -> IntArray(SIZE) { it * SIZE + line }
        Direction.DOWN -> IntArray(SIZE) { (SIZE - 1 - it) * SIZE + line }
    }

    private fun spawnTile() {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return
        cells[empty[random.nextInt(empty.size)]] = if (random.nextFloat() < 0.9f) 2 else 4
    }

    private fun hasAnyMove(): Boolean {
        for (index in cells.indices) {
            if (cells[index] == 0) return true
            val row = index / SIZE
            val column = index % SIZE
            if (column + 1 < SIZE && cells[index] == cells[index + 1]) return true
            if (row + 1 < SIZE && cells[index] == cells[index + SIZE]) return true
        }
        return false
    }

    companion object {
        const val SIZE = 4
        private const val CELL_COUNT = SIZE * SIZE

        /** Restores a game from [toJson] output. Returns null (never throws) on invalid input. */
        fun fromJson(json: String, random: Random = Random.Default): Game2048? {
            val map = GameJson.objectOf(GameJson.parse(json)) ?: return null
            val cells = GameJson.intListOf(map["cells"]) ?: return null
            if (cells.size != CELL_COUNT || cells.any { it < 0 }) return null
            val score = GameJson.intOf(map["score"]) ?: return null
            if (score < 0) return null
            return Game2048(cells.toIntArray(), score, random)
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
