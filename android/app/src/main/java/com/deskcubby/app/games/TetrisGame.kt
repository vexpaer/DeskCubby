package com.deskcubby.app.games

import kotlin.random.Random

/**
 * Pure-Kotlin Tetris engine on a 10x20 board with the 7 classic tetrominoes and simple classic
 * rotation (rotation just fails when blocked; no wall kicks). No Android/Compose dependencies.
 * Pass a seeded [Random] for a deterministic piece sequence in tests.
 *
 * Speed is owned by the UI layer: it derives the tick interval from [level].
 */
class TetrisGame private constructor(
    private val board: IntArray,
    initialScore: Int,
    initialLines: Int,
    initialPieceType: Int,
    initialRotation: Int,
    initialX: Int,
    initialY: Int,
    initialNextPieceType: Int,
    initialGameOver: Boolean,
    private val random: Random,
) {

    /** Starts a fresh game with a random current and next piece. */
    constructor(random: Random = Random.Default) : this(
        board = IntArray(WIDTH * HEIGHT),
        initialScore = 0,
        initialLines = 0,
        initialPieceType = 0,
        initialRotation = 0,
        initialX = 0,
        initialY = 0,
        initialNextPieceType = 0,
        initialGameOver = false,
        random = random,
    ) {
        spawnPiece(random.nextInt(PIECE_COUNT))
        nextPieceType = random.nextInt(PIECE_COUNT)
    }

    data class Cell(val x: Int, val y: Int)

    var score: Int = initialScore
        private set

    var lines: Int = initialLines
        private set

    /** Increases every [LINES_PER_LEVEL] cleared lines; the UI shortens the tick interval with it. */
    val level: Int get() = lines / LINES_PER_LEVEL

    var isGameOver: Boolean = initialGameOver
        private set

    var pieceType: Int = initialPieceType
        private set

    var pieceRotation: Int = initialRotation
        private set

    var pieceX: Int = initialX
        private set

    var pieceY: Int = initialY
        private set

    var nextPieceType: Int = initialNextPieceType
        private set

    /** Locked cells only: 0 = empty, otherwise pieceType + 1 of the locked piece. */
    fun boardCell(x: Int, y: Int): Int = board[y * WIDTH + x]

    fun boardSnapshot(): List<Int> = board.toList()

    /** Absolute board coordinates of the falling piece. */
    fun currentPieceCells(): List<Cell> =
        SHAPES[pieceType][pieceRotation].map { Cell(pieceX + it.x, pieceY + it.y) }

    /** Next piece in spawn rotation, normalized so its occupied cells start at (0, 0). */
    fun nextPiecePreviewCells(): List<Cell> {
        val cells = SHAPES[nextPieceType][0]
        val minX = cells.minOf { it.x }
        val minY = cells.minOf { it.y }
        return cells.map { Cell(it.x - minX, it.y - minY) }
    }

    fun moveLeft(): Boolean = applyMove(-1, 0, pieceRotation)

    fun moveRight(): Boolean = applyMove(1, 0, pieceRotation)

    /** Clockwise rotation; fails (returns false) when the rotated piece would collide. */
    fun rotate(): Boolean = applyMove(0, 0, (pieceRotation + 1) % ROTATION_COUNT)

    /** Moves down one row; locks the piece when it cannot fall. Returns true when it moved. */
    fun softDrop(): Boolean = descend()

    /** Gravity step driven by the UI loop. */
    fun tick() {
        descend()
    }

    /** Drops the piece to the bottom and locks it immediately. */
    fun hardDrop() {
        if (isGameOver) return
        while (applyMove(0, 1, pieceRotation)) {
            // keep falling
        }
        lockPiece()
    }

    /** Serializes the complete restorable state as JSON. */
    fun toJson(): String = buildString {
        append("{\"board\":[")
        board.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append("],\"score\":").append(score)
        append(",\"lines\":").append(lines)
        append(",\"type\":").append(pieceType)
        append(",\"rot\":").append(pieceRotation)
        append(",\"x\":").append(pieceX)
        append(",\"y\":").append(pieceY)
        append(",\"next\":").append(nextPieceType)
        append(",\"over\":").append(isGameOver)
        append('}')
    }

    private fun descend(): Boolean {
        if (isGameOver) return false
        if (applyMove(0, 1, pieceRotation)) return true
        lockPiece()
        return false
    }

    private fun applyMove(dx: Int, dy: Int, rotation: Int): Boolean {
        if (isGameOver) return false
        val newX = pieceX + dx
        val newY = pieceY + dy
        if (collides(pieceType, rotation, newX, newY)) return false
        pieceX = newX
        pieceY = newY
        pieceRotation = rotation
        return true
    }

    private fun collides(type: Int, rotation: Int, originX: Int, originY: Int): Boolean {
        for (cell in SHAPES[type][rotation]) {
            val x = originX + cell.x
            val y = originY + cell.y
            if (x !in 0 until WIDTH || y !in 0 until HEIGHT) return true
            if (board[y * WIDTH + x] != 0) return true
        }
        return false
    }

    private fun lockPiece() {
        for (cell in currentPieceCells()) {
            board[cell.y * WIDTH + cell.x] = pieceType + 1
        }
        val cleared = clearFullRows()
        if (cleared > 0) {
            lines += cleared
            score += LINE_SCORES[cleared - 1]
        }
        spawnPiece(nextPieceType)
        nextPieceType = random.nextInt(PIECE_COUNT)
    }

    private fun clearFullRows(): Int {
        var cleared = 0
        var writeY = HEIGHT - 1
        for (y in HEIGHT - 1 downTo 0) {
            val full = (0 until WIDTH).all { board[y * WIDTH + it] != 0 }
            if (full) {
                cleared++
            } else {
                if (writeY != y) {
                    for (x in 0 until WIDTH) board[writeY * WIDTH + x] = board[y * WIDTH + x]
                }
                writeY--
            }
        }
        for (y in writeY downTo 0) {
            for (x in 0 until WIDTH) board[y * WIDTH + x] = 0
        }
        return cleared
    }

    private fun spawnPiece(type: Int) {
        pieceType = type
        pieceRotation = 0
        pieceX = (WIDTH - BOX_SIZES[type]) / 2
        pieceY = 0
        if (collides(pieceType, pieceRotation, pieceX, pieceY)) isGameOver = true
    }

    companion object {
        const val WIDTH = 10
        const val HEIGHT = 20
        const val PIECE_COUNT = 7
        private const val ROTATION_COUNT = 4
        private const val LINES_PER_LEVEL = 10

        /** Points for clearing 1 / 2 / 3 / 4 rows at once. */
        private val LINE_SCORES = intArrayOf(100, 300, 500, 800)

        /** Bounding-box edge per piece, used to rotate and to center the spawn position. */
        private val BOX_SIZES = intArrayOf(4, 2, 3, 3, 3, 3, 3)

        /** Base cells per piece, in order: I, O, T, S, Z, J, L. */
        private val BASE_CELLS = listOf(
            listOf(Cell(0, 1), Cell(1, 1), Cell(2, 1), Cell(3, 1)),
            listOf(Cell(0, 0), Cell(1, 0), Cell(0, 1), Cell(1, 1)),
            listOf(Cell(1, 0), Cell(0, 1), Cell(1, 1), Cell(2, 1)),
            listOf(Cell(1, 0), Cell(2, 0), Cell(0, 1), Cell(1, 1)),
            listOf(Cell(0, 0), Cell(1, 0), Cell(1, 1), Cell(2, 1)),
            listOf(Cell(0, 0), Cell(0, 1), Cell(1, 1), Cell(2, 1)),
            listOf(Cell(2, 0), Cell(0, 1), Cell(1, 1), Cell(2, 1)),
        )

        /** All four classic clockwise rotations per piece, precomputed inside each bounding box. */
        private val SHAPES: List<List<List<Cell>>> = BASE_CELLS.mapIndexed { type, base ->
            val box = BOX_SIZES[type]
            val rotations = ArrayList<List<Cell>>(ROTATION_COUNT)
            var current = base
            repeat(ROTATION_COUNT) {
                rotations.add(current)
                current = current
                    .map { Cell(box - 1 - it.y, it.x) }
                    .sortedWith(compareBy({ it.y }, { it.x }))
            }
            rotations
        }

        /** Restores a game from [toJson] output. Returns null (never throws) on invalid input. */
        fun fromJson(json: String, random: Random = Random.Default): TetrisGame? {
            val map = GameJson.objectOf(GameJson.parse(json)) ?: return null
            val boardValues = GameJson.intListOf(map["board"]) ?: return null
            if (boardValues.size != WIDTH * HEIGHT) return null
            if (boardValues.any { it !in 0..PIECE_COUNT }) return null
            val score = GameJson.intOf(map["score"]) ?: return null
            val lines = GameJson.intOf(map["lines"]) ?: return null
            if (score < 0 || lines < 0) return null
            val type = GameJson.intOf(map["type"]) ?: return null
            val rotation = GameJson.intOf(map["rot"]) ?: return null
            val x = GameJson.intOf(map["x"]) ?: return null
            val y = GameJson.intOf(map["y"]) ?: return null
            val next = GameJson.intOf(map["next"]) ?: return null
            val over = GameJson.boolOf(map["over"]) ?: return null
            if (type !in 0 until PIECE_COUNT || next !in 0 until PIECE_COUNT) return null
            if (rotation !in 0 until ROTATION_COUNT) return null
            val game = TetrisGame(
                board = boardValues.toIntArray(),
                initialScore = score,
                initialLines = lines,
                initialPieceType = type,
                initialRotation = rotation,
                initialX = x,
                initialY = y,
                initialNextPieceType = next,
                initialGameOver = over,
                random = random,
            )
            if (!over && game.collides(type, rotation, x, y)) return null
            return game
        }
    }
}
