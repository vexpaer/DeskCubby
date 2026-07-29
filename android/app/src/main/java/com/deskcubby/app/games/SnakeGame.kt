package com.deskcubby.app.games

import kotlin.random.Random

/**
 * Pure-Kotlin snake engine on a [width] x [height] grid (16x16 by default). No Android/Compose
 * dependencies. Pass a seeded [Random] for deterministic food placement in tests.
 */
class SnakeGame private constructor(
    val width: Int,
    val height: Int,
    initialBody: List<Cell>,
    initialDirection: Direction,
    initialFood: Cell,
    initialScore: Int,
    initialGameOver: Boolean,
    private val random: Random,
) {

    /** Starts a fresh game: a short snake in the middle moving right, with one food spawned. */
    constructor(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        random: Random = Random.Default,
    ) : this(
        width = width,
        height = height,
        initialBody = startingBody(width, height),
        initialDirection = Direction.RIGHT,
        initialFood = Cell(0, 0),
        initialScore = 0,
        initialGameOver = false,
        random = random,
    ) {
        food = randomEmptyCell() ?: food
    }

    data class Cell(val x: Int, val y: Int)

    enum class Direction(val dx: Int, val dy: Int) {
        UP(0, -1),
        DOWN(0, 1),
        LEFT(-1, 0),
        RIGHT(1, 0),
        ;

        val opposite: Direction
            get() = when (this) {
                UP -> DOWN
                DOWN -> UP
                LEFT -> RIGHT
                RIGHT -> LEFT
            }
    }

    private val body = ArrayDeque(initialBody)

    /** Direction that will be applied on the next [tick]. */
    var direction: Direction = initialDirection
        private set

    /** Direction actually used by the latest tick; guards against 180-degree reversals. */
    private var movedDirection: Direction = initialDirection

    var food: Cell = initialFood
        private set

    var score: Int = initialScore
        private set

    var isGameOver: Boolean = initialGameOver
        private set

    /** Head-first snapshot of the snake body. */
    val snake: List<Cell> get() = body.toList()

    /** Requests a new heading; a direct reversal of the last moved direction is ignored. */
    fun setDirection(newDirection: Direction) {
        if (isGameOver) return
        if (body.size > 1 && newDirection == movedDirection.opposite) return
        direction = newDirection
    }

    /**
     * Advances one step: hitting a wall or the snake's own body ends the game, eating food adds
     * [EAT_SCORE] points and grows the snake. Returns false when the game is (now) over.
     */
    fun tick(): Boolean {
        if (isGameOver) return false
        movedDirection = direction
        val head = body.first()
        val next = Cell(head.x + direction.dx, head.y + direction.dy)
        if (next.x !in 0 until width || next.y !in 0 until height) {
            isGameOver = true
            return false
        }
        val growing = next == food
        val hitIndex = body.indexOf(next)
        // The tail cell vacates this tick unless the snake grows, so it does not block.
        if (hitIndex >= 0 && (growing || hitIndex < body.size - 1)) {
            isGameOver = true
            return false
        }
        body.addFirst(next)
        if (growing) {
            score += EAT_SCORE
            val nextFood = randomEmptyCell()
            if (nextFood == null) isGameOver = true else food = nextFood
        } else {
            body.removeLast()
        }
        return !isGameOver
    }

    /** Serializes the complete restorable state as JSON. */
    fun toJson(): String = buildString {
        append("{\"w\":").append(width)
        append(",\"h\":").append(height)
        append(",\"snake\":[")
        body.forEachIndexed { index, cell ->
            if (index > 0) append(',')
            append('[').append(cell.x).append(',').append(cell.y).append(']')
        }
        append("],\"dir\":\"").append(direction.name)
        append("\",\"food\":[").append(food.x).append(',').append(food.y)
        append("],\"score\":").append(score)
        append(",\"over\":").append(isGameOver)
        append('}')
    }

    private fun randomEmptyCell(): Cell? {
        val occupied = body.toHashSet()
        val empty = ArrayList<Cell>((width * height - occupied.size).coerceAtLeast(0))
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cell = Cell(x, y)
                if (cell !in occupied) empty.add(cell)
            }
        }
        return if (empty.isEmpty()) null else empty[random.nextInt(empty.size)]
    }

    companion object {
        const val DEFAULT_WIDTH = 16
        const val DEFAULT_HEIGHT = 16
        const val EAT_SCORE = 10
        private const val MIN_SIZE = 4

        private fun startingBody(width: Int, height: Int): List<Cell> {
            val centerX = width / 2
            val centerY = height / 2
            val length = minOf(3, centerX + 1)
            return List(length) { Cell(centerX - it, centerY) }
        }

        /** Restores a game from [toJson] output. Returns null (never throws) on invalid input. */
        fun fromJson(json: String, random: Random = Random.Default): SnakeGame? {
            val map = GameJson.objectOf(GameJson.parse(json)) ?: return null
            val width = GameJson.intOf(map["w"]) ?: return null
            val height = GameJson.intOf(map["h"]) ?: return null
            if (width < MIN_SIZE || height < MIN_SIZE) return null
            val rawSnake = map["snake"] as? List<*> ?: return null
            if (rawSnake.isEmpty() || rawSnake.size > width * height) return null
            val bodyCells = ArrayList<Cell>(rawSnake.size)
            for (entry in rawSnake) {
                val pair = GameJson.intListOf(entry) ?: return null
                if (pair.size != 2) return null
                val cell = Cell(pair[0], pair[1])
                if (cell.x !in 0 until width || cell.y !in 0 until height) return null
                bodyCells.add(cell)
            }
            if (bodyCells.size != bodyCells.toHashSet().size) return null
            val directionName = GameJson.stringOf(map["dir"]) ?: return null
            val direction = Direction.entries.firstOrNull { it.name == directionName } ?: return null
            val foodPair = GameJson.intListOf(map["food"]) ?: return null
            if (foodPair.size != 2) return null
            val food = Cell(foodPair[0], foodPair[1])
            if (food.x !in 0 until width || food.y !in 0 until height) return null
            val score = GameJson.intOf(map["score"]) ?: return null
            if (score < 0) return null
            val over = GameJson.boolOf(map["over"]) ?: return null
            return SnakeGame(width, height, bodyCells, direction, food, score, over, random)
        }
    }
}
