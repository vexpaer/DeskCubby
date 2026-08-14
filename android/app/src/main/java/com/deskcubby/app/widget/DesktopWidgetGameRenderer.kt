package com.deskcubby.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.deskcubby.app.R
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.statistics.GamePersistenceCoordinator
import com.deskcubby.app.data.statistics.GameStatisticMetric
import com.deskcubby.app.games.Game2048
import com.deskcubby.app.games.GoGame
import com.deskcubby.app.games.MinesweeperGame
import com.deskcubby.app.games.SnakeGame
import com.deskcubby.app.games.SpiderSolitaireGame
import com.deskcubby.app.games.TetrisGame
import com.deskcubby.app.ui.theme.translate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

enum class WidgetGameAction {
    NEW, UP, DOWN, LEFT, RIGHT, ROTATE, DROP, DEAL, UNDO, FLAG, PASS, CELL, TICK,
}

fun desktopGameIdForModule(moduleId: String): String? = when (moduleId) {
    "game_2048" -> "2048"
    "game_snake" -> "snake"
    "game_tetris" -> "tetris"
    "game_minesweeper" -> "minesweeper"
    "game_spider" -> "spider"
    "game_go" -> "go"
    else -> null
}

@Singleton
class DesktopWidgetGameRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gamePersistence: GamePersistenceCoordinator,
) {
    private val boardWidthPx = 480
    private val boardHeightPx = 320
    private val widgetPreferences = context.getSharedPreferences("widget_game_state", Context.MODE_PRIVATE)

    suspend fun render(
        appWidgetId: Int,
        gameId: String,
        action: WidgetGameAction?,
        cell: Int,
        settings: AppSettings,
    ): RemoteViews? {
        val views = RemoteViews(context.packageName, R.layout.desktop_widget_apps)
        applyAppPanelBase(views, settings, boardTitle(gameId, settings))
        val board = Bitmap.createBitmap(boardWidthPx, boardHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(board)
        try {
            when (gameId) {
                "2048" -> render2048(canvas, action)
                "snake" -> renderSnake(canvas, action)
                "tetris" -> renderTetris(canvas, action)
                "minesweeper" -> renderMinesweeper(canvas, action, cell)
                "spider" -> renderSpider(canvas, action, cell)
                "go" -> renderGo(canvas, action, cell)
                else -> return null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A damaged save must never blank the whole launcher update.
        }
        views.setImageViewBitmap(R.id.widget_apps_board, board)
        configureGameButtons(views, appWidgetId, gameId, settings)
        return views
    }

    private suspend fun render2048(canvas: Canvas, action: WidgetGameAction?) {
        var game = gamePersistence.loadSave("2048")?.let(Game2048::fromJson) ?: Game2048()
        if (action == WidgetGameAction.NEW) {
            game = Game2048()
        } else if (action != null) {
            val direction = when (action) {
                WidgetGameAction.UP -> Game2048.Direction.UP
                WidgetGameAction.DOWN -> Game2048.Direction.DOWN
                WidgetGameAction.LEFT -> Game2048.Direction.LEFT
                WidgetGameAction.RIGHT -> Game2048.Direction.RIGHT
                else -> null
            }
            direction?.let { d ->
                val result = game.moveWithResult(d)
                if (result != null) {
                    gamePersistence.recordStatistics(
                        "2048",
                        mapOf(GameStatisticMetric.EFFECTIVE_MOVES to 1L),
                        mapOf(GameStatisticMetric.HIGHEST_TILE to result.statisticsDelta.highestTile.toLong()),
                    )
                }
            }
        }
        if (action != null) gamePersistence.saveProgress("2048", game.toJson(), game.score)
        draw2048(canvas, game)
    }

    private suspend fun renderSnake(canvas: Canvas, action: WidgetGameAction?) {
        var game = gamePersistence.loadSave("snake")?.let(SnakeGame::fromJson) ?: SnakeGame()
        if (action == WidgetGameAction.NEW) {
            game = SnakeGame()
        } else if (action != null && !game.isGameOver) {
            when (action) {
                WidgetGameAction.UP -> game.setDirection(SnakeGame.Direction.UP)
                WidgetGameAction.DOWN -> game.setDirection(SnakeGame.Direction.DOWN)
                WidgetGameAction.LEFT -> game.setDirection(SnakeGame.Direction.LEFT)
                WidgetGameAction.RIGHT -> game.setDirection(SnakeGame.Direction.RIGHT)
                WidgetGameAction.TICK -> {
                    val result = game.tickWithResult()
                    gamePersistence.recordStatistics(
                        "snake",
                        mapOf(
                            GameStatisticMetric.FOOD_EATEN to result.statisticsDelta.foodEaten.toLong(),
                            GameStatisticMetric.LOSSES to result.statisticsDelta.losses.toLong(),
                        ),
                        mapOf(GameStatisticMetric.MAX_LENGTH to result.statisticsDelta.maxLength.toLong()),
                    )
                }
                else -> Unit
            }
        }
        if (action != null) {
            if (game.isGameOver) {
                gamePersistence.recordScore("snake", game.score)
                gamePersistence.clearSave("snake")
            } else {
                gamePersistence.saveProgress("snake", game.toJson(), game.score)
            }
        }
        drawSnake(canvas, game)
    }

    private suspend fun renderTetris(canvas: Canvas, action: WidgetGameAction?) {
        var game = gamePersistence.loadSave("tetris")?.let(TetrisGame::fromJson) ?: TetrisGame()
        if (action == WidgetGameAction.NEW) {
            game = TetrisGame()
        } else if (action != null && !game.isGameOver) {
            when (action) {
                WidgetGameAction.LEFT -> game.moveLeft()
                WidgetGameAction.RIGHT -> game.moveRight()
                WidgetGameAction.ROTATE, WidgetGameAction.UP -> game.rotate()
                WidgetGameAction.DROP -> game.hardDrop()
                WidgetGameAction.DOWN, WidgetGameAction.TICK -> {
                    val step = game.softDropWithResult()
                    gamePersistence.recordStatistics(
                        "tetris",
                        mapOf(
                            GameStatisticMetric.PIECES_LOCKED to step.statisticsDelta.piecesLocked.toLong(),
                            GameStatisticMetric.LINES_CLEARED to step.statisticsDelta.linesCleared.toLong(),
                            GameStatisticMetric.TETRISES to step.statisticsDelta.tetrises.toLong(),
                            GameStatisticMetric.LOSSES to step.statisticsDelta.losses.toLong(),
                        ),
                    )
                }
                else -> Unit
            }
        }
        if (action != null) {
            if (game.isGameOver) {
                gamePersistence.recordScore("tetris", game.score)
                gamePersistence.clearSave("tetris")
            } else {
                gamePersistence.saveProgress("tetris", game.toJson(), game.score)
            }
        }
        drawTetris(canvas, game)
    }

    private suspend fun renderMinesweeper(canvas: Canvas, action: WidgetGameAction?, cell: Int) {
        var game = gamePersistence.loadSave("minesweeper")?.let(MinesweeperGame::fromJson)
            ?: MinesweeperGame()
        if (action == WidgetGameAction.NEW) {
            game = MinesweeperGame()
        } else if (action == WidgetGameAction.CELL) {
            val x = (cell % 9).coerceIn(0, 8)
            val y = (cell / 9).coerceIn(0, 8)
            val result = if (flagMode()) game.toggleFlagWithResult(x, y) else game.revealWithResult(x, y)
            if (result.changed) {
                gamePersistence.recordStatistics(
                    "minesweeper",
                    mapOf(
                        GameStatisticMetric.MINES_CELLS_REVEALED to result.statisticsDelta.minesCellsRevealed.toLong(),
                        GameStatisticMetric.MINES_SWEPT to result.statisticsDelta.minesSwept.toLong(),
                        GameStatisticMetric.FLAGS_PLACED to result.statisticsDelta.flagsPlaced.toLong(),
                        GameStatisticMetric.WINS to result.statisticsDelta.wins.toLong(),
                        GameStatisticMetric.LOSSES to result.statisticsDelta.losses.toLong(),
                    ),
                )
            }
        } else if (action == WidgetGameAction.FLAG) {
            setFlagMode(!flagMode())
        }
        if (action != null && action != WidgetGameAction.FLAG) {
            if (game.isGameOver || game.isWon) {
                gamePersistence.recordScore("minesweeper", 0)
                gamePersistence.clearSave("minesweeper")
            } else {
                gamePersistence.saveProgress("minesweeper", game.toJson(), 0)
            }
        }
        drawMinesweeper(canvas, game)
    }

    private suspend fun renderSpider(canvas: Canvas, action: WidgetGameAction?, cell: Int) {
        var game = gamePersistence.loadSave("spider")?.let(SpiderSolitaireGame::fromJson)
            ?: SpiderSolitaireGame()
        if (action == WidgetGameAction.NEW) {
            game = SpiderSolitaireGame()
            setSelectedSpiderColumn(-1)
        } else if (action == WidgetGameAction.CELL) {
            val column = cell.coerceIn(0, 9)
            val selected = selectedSpiderColumn()
            if (selected < 0) {
                setSelectedSpiderColumn(column)
            } else {
                setSelectedSpiderColumn(-1)
                if (selected != column) {
                    val cards = game.column(selected)
                    val cardIndex = cards.indices.reversed().firstOrNull { index ->
                        game.canSelect(selected, index)
                    }
                    if (cardIndex != null) {
                        val result = game.moveWithResult(selected, cardIndex, column)
                        if (result.changed) {
                            gamePersistence.recordStatistics(
                                "spider",
                                mapOf(GameStatisticMetric.SPIDER_CARD_MOVES to result.statisticsDelta.cardMoves.toLong()),
                            )
                        }
                    }
                }
            }
        } else if (action == WidgetGameAction.DEAL) {
            val result = game.dealStockWithResult()
            if (result.changed) {
                gamePersistence.recordStatistics(
                    "spider",
                    mapOf(GameStatisticMetric.SPIDER_DEALS to result.statisticsDelta.deals.toLong()),
                )
            }
        } else if (action == WidgetGameAction.UNDO) {
            val result = game.undoWithResult()
            if (result.changed) {
                gamePersistence.recordStatistics(
                    "spider",
                    mapOf(GameStatisticMetric.SPIDER_UNDOS to result.statisticsDelta.undos.toLong()),
                )
            }
        }
        if (action != null && action != WidgetGameAction.CELL && game.hasPlayedAction) {
            gamePersistence.saveProgress("spider", game.toJson(), game.score)
        }
        drawSpider(canvas, game)
    }

    private suspend fun renderGo(canvas: Canvas, action: WidgetGameAction?, cell: Int) {
        var game = gamePersistence.loadSave("go")?.let(GoGame::fromJson) ?: GoGame()
        if (action == WidgetGameAction.NEW) {
            game = GoGame()
        } else if (action != null && !game.isFinished) {
            when (action) {
                WidgetGameAction.CELL -> {
                    val result = game.play((cell % 9).coerceIn(0, 8), (cell / 9).coerceIn(0, 8))
                    if (result.accepted) {
                        gamePersistence.recordStatistics(
                            "go",
                            mapOf(
                                GameStatisticMetric.GO_MOVES_PLAYED to result.statisticsDelta.movesPlayed.toLong(),
                                GameStatisticMetric.GO_STONES_CAPTURED to result.statisticsDelta.stonesCaptured.toLong(),
                            ),
                        )
                    }
                }
                WidgetGameAction.PASS -> {
                    val result = game.pass()
                    if (result.accepted) {
                        gamePersistence.recordStatistics(
                            "go",
                            mapOf(GameStatisticMetric.GO_PASSES to result.statisticsDelta.passes.toLong()),
                        )
                    }
                    if (game.isFinished) {
                        gamePersistence.recordStatistics(
                            "go",
                            mapOf(GameStatisticMetric.GO_GAMES_COMPLETED to 1L),
                        )
                        gamePersistence.clearSave("go")
                    }
                }
                else -> Unit
            }
        }
        if (action != null && action != WidgetGameAction.PASS && !game.isFinished) {
            gamePersistence.saveProgress("go", game.toJson(), 0)
        }
        drawGo(canvas, game)
    }

    // ---- drawing ---------------------------------------------------------

    private fun draw2048(canvas: Canvas, game: Game2048) {
        val size = game.size
        val padding = 6f
        val cell = (boardWidthPx - padding * (size + 1)) / size
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        for (row in 0 until size) {
            for (col in 0 until size) {
                val value = game.cellAt(row, col)
                val left = padding + col * (cell + padding)
                val top = padding + row * (cell + padding)
                paint.color = tileColor(value)
                canvas.drawRoundRect(RectF(left, top, left + cell, top + cell), 8f, 8f, paint)
                if (value > 0) {
                    textPaint.color = if (value <= 4) Color.rgb(0x66, 0x66, 0x66) else Color.WHITE
                    textPaint.textSize = when {
                        value >= 1024 -> cell * 0.32f
                        value >= 128 -> cell * 0.38f
                        else -> cell * 0.46f
                    }
                    canvas.drawText(
                        value.toString(),
                        left + cell / 2f,
                        top + (cell - textPaint.textSize) / 2f + textPaint.textSize * 0.86f,
                        textPaint,
                    )
                }
            }
        }
    }

    private fun tileColor(value: Int): Int = when (value) {
        0 -> Color.rgb(0xBB, 0xAF, 0xA0)
        2 -> Color.rgb(0xEE, 0xE4, 0xDA)
        4 -> Color.rgb(0xED, 0xE0, 0xC8)
        8 -> Color.rgb(0xF2, 0xB1, 0x79)
        16 -> Color.rgb(0xF5, 0x95, 0x63)
        32 -> Color.rgb(0xF6, 0x7C, 0x5F)
        64 -> Color.rgb(0xF6, 0x5E, 0x3B)
        128 -> Color.rgb(0xED, 0xCF, 0x72)
        256 -> Color.rgb(0xED, 0xCC, 0x61)
        512 -> Color.rgb(0xED, 0xC8, 0x50)
        1024 -> Color.rgb(0xED, 0xC5, 0x3F)
        else -> Color.rgb(0xED, 0xC2, 0x2E)
    }

    private fun drawSnake(canvas: Canvas, game: SnakeGame) {
        val cols = game.width
        val rows = game.height
        val cell = minOf(boardWidthPx / cols, boardHeightPx / rows).coerceAtLeast(4)
        val offsetX = (boardWidthPx - cell * cols) / 2f
        val offsetY = (boardHeightPx - cell * rows) / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2E, 0x3A, 0x2E) }
        canvas.drawRect(0f, 0f, boardWidthPx.toFloat(), boardHeightPx.toFloat(), bg)
        val grid = Paint().apply {
            color = Color.rgb(0x3A, 0x4A, 0x3A)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (x in 0..cols) canvas.drawLine(offsetX + x * cell, offsetY, offsetX + x * cell, offsetY + rows * cell, grid)
        for (y in 0..rows) canvas.drawLine(offsetX, offsetY + y * cell, offsetX + cols * cell, offsetY + y * cell, grid)
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x7C, 0xE0, 0x8C) }
        val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x4C, 0xC9, 0x66) }
        val foodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF4, 0x6A, 0x5E) }
        val body = game.snake
        body.forEachIndexed { index, point ->
            val paint = if (index == body.lastIndex) headPaint else bodyPaint
            canvas.drawRoundRect(
                RectF(
                    offsetX + point.x * cell + 1,
                    offsetY + point.y * cell + 1,
                    offsetX + (point.x + 1) * cell - 1,
                    offsetY + (point.y + 1) * cell - 1,
                ),
                cell * 0.25f,
                cell * 0.25f,
                paint,
            )
        }
        val food = game.food
        canvas.drawCircle(
            offsetX + food.x * cell + cell / 2f,
            offsetY + food.y * cell + cell / 2f,
            cell * 0.32f,
            foodPaint,
        )
    }

    private fun drawTetris(canvas: Canvas, game: TetrisGame) {
        val cols = 10
        val rows = 20
        val cell = minOf(boardWidthPx / cols, boardHeightPx / rows).coerceAtLeast(6)
        val offsetX = (boardWidthPx - cell * cols) / 2f
        val offsetY = (boardHeightPx - cell * rows) / 2f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x22, 0x28, 0x34) }
        canvas.drawRect(0f, 0f, boardWidthPx.toFloat(), boardHeightPx.toFloat(), bg)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val value = game.boardCell(x, y)
                paint.color = if (value == 0) Color.rgb(0x2A, 0x31, 0x3E) else tetrominoColor(value)
                canvas.drawRoundRect(
                    RectF(
                        offsetX + x * cell + 1,
                        offsetY + y * cell + 1,
                        offsetX + (x + 1) * cell - 1,
                        offsetY + (y + 1) * cell - 1,
                    ),
                    cell * 0.18f,
                    cell * 0.18f,
                    paint,
                )
            }
        }
        val preview = game.nextPieceType + 1
        if (preview in 1..7) {
            paint.color = tetrominoColor(preview)
            canvas.drawCircle(boardWidthPx - 18f, 18f, 9f, paint)
        }
    }

    private fun tetrominoColor(type: Int): Int = when (type) {
        1 -> Color.rgb(0x61, 0xD2, 0xF7)
        2 -> Color.rgb(0xF7, 0xD6, 0x5E)
        3 -> Color.rgb(0xA2, 0x8B, 0xF0)
        4 -> Color.rgb(0x5E, 0xC9, 0x7E)
        5 -> Color.rgb(0xF0, 0x6B, 0x6B)
        6 -> Color.rgb(0x5E, 0xA8, 0xF7)
        7 -> Color.rgb(0xF7, 0xA2, 0x5E)
        else -> Color.rgb(0x2A, 0x31, 0x3E)
    }

    private fun drawMinesweeper(canvas: Canvas, game: MinesweeperGame) {
        val cols = game.width
        val rows = game.height
        val cell = minOf(boardWidthPx / cols, boardHeightPx / rows).coerceAtLeast(6)
        val offsetX = (boardWidthPx - cell * cols) / 2f
        val offsetY = (boardHeightPx - cell * rows) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val info = game.cell(x, y)
                paint.color = if (info.revealed) Color.rgb(0x9A, 0xA3, 0xB0) else Color.rgb(0x5A, 0x64, 0x74)
                val left = offsetX + x * cell
                val top = offsetY + y * cell
                canvas.drawRoundRect(RectF(left + 1, top + 1, left + cell - 1, top + cell - 1), cell * 0.12f, cell * 0.12f, paint)
                if (info.revealed && info.mine) {
                    paint.color = Color.RED
                    canvas.drawCircle(left + cell / 2f, top + cell / 2f, cell * 0.3f, paint)
                } else if (info.revealed && info.adjacentMines > 0) {
                    textPaint.color = when (info.adjacentMines) {
                        1 -> Color.rgb(0x2E, 0x6E, 0xE6)
                        2 -> Color.rgb(0x2E, 0x9E, 0x4E)
                        else -> Color.rgb(0xD6, 0x4E, 0x4E)
                    }
                    textPaint.textSize = cell * 0.5f
                    canvas.drawText(
                        info.adjacentMines.toString(),
                        left + cell / 2f,
                        top + cell / 2f + textPaint.textSize * 0.36f,
                        textPaint,
                    )
                } else if (info.flagged) {
                    textPaint.textSize = cell * 0.55f
                    textPaint.color = Color.rgb(0xE6, 0xB8, 0x4E)
                    canvas.drawText("F", left + cell / 2f, top + cell / 2f + textPaint.textSize * 0.36f, textPaint)
                }
            }
        }
    }

    private fun drawSpider(canvas: Canvas, game: SpiderSolitaireGame) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2A, 0x6E, 0x3F) }
        canvas.drawRect(0f, 0f, boardWidthPx.toFloat(), boardHeightPx.toFloat(), bg)
        val columns = SpiderSolitaireGame.COLUMN_COUNT
        val cardW = (boardWidthPx - (columns + 1) * 4f) / columns
        val cardH = cardW * 1.45f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        for (col in 0 until columns) {
            val cards = game.column(col)
            val left = 4f + col * (cardW + 4f)
            cards.forEachIndexed { index, card ->
                val top = 4f + index * (cardH * 0.22f).coerceAtMost(14f)
                if (top > boardHeightPx - 8f) return@forEachIndexed
                paint.color = if (card.faceUp) Color.WHITE else Color.rgb(0x4A, 0x8A, 0xC4)
                canvas.drawRoundRect(RectF(left, top, left + cardW, top + cardH), 4f, 4f, paint)
                if (card.faceUp) {
                    textPaint.color = Color.rgb(0x33, 0x33, 0x33)
                    textPaint.textSize = cardW * 0.5f
                    canvas.drawText(spiderCardLabel(card.rank), left + cardW / 2f, top + cardH * 0.55f, textPaint)
                }
            }
        }
        textPaint.color = Color.WHITE
        textPaint.textSize = 13f
        canvas.drawText(game.stockDealsRemaining.toString() + "x", 8f, boardHeightPx - 8f, textPaint)
    }

    private fun spiderCardLabel(rank: Int): String = when (rank) {
        1 -> "A"
        11 -> "J"
        12 -> "Q"
        13 -> "K"
        else -> rank.toString()
    }

    private fun drawGo(canvas: Canvas, game: GoGame) {
        val size = game.size
        val margin = 20f
        val cell = (boardWidthPx - margin * 2) / size
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(0xD9, 0xB3, 0x70)
        canvas.drawRect(0f, 0f, boardWidthPx.toFloat(), boardHeightPx.toFloat(), paint)
        paint.color = Color.rgb(0x8A, 0x6E, 0x3E)
        paint.strokeWidth = 1.5f
        for (i in 0 until size) {
            val x = margin + i * cell
            val y = margin + i * cell
            canvas.drawLine(x, margin, x, margin + (size - 1) * cell, paint)
            canvas.drawLine(margin, y, margin + (size - 1) * cell, y, paint)
        }
        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x22, 0x22, 0x22) }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        for (y in 0 until size) {
            for (x in 0 until size) {
                val stone = game.stoneAt(x, y)
                if (stone == GoGame.Stone.BLACK || stone == GoGame.Stone.WHITE) {
                    val cx = margin + x * cell
                    val cy = margin + y * cell
                    canvas.drawCircle(cx, cy, cell * 0.42f, if (stone == GoGame.Stone.BLACK) black else white)
                }
            }
        }
        val last = game.lastMove
        if (last != null) {
            paint.color = Color.rgb(0xE6, 0x4E, 0x4E)
            paint.strokeWidth = 2f
            canvas.drawCircle(margin + last.x * cell, margin + last.y * cell, cell * 0.16f, paint)
        }
    }

    // ---- buttons ---------------------------------------------------------

    private fun configureGameButtons(
        views: RemoteViews,
        appWidgetId: Int,
        gameId: String,
        settings: AppSettings,
    ) {
        val textColor = appPanelTextColor(settings)
        val actionIds = listOf(
            R.id.widget_apps_btn_up,
            R.id.widget_apps_btn_left,
            R.id.widget_apps_btn_right,
            R.id.widget_apps_btn_down,
            R.id.widget_apps_action_1,
            R.id.widget_apps_action_2,
        )
        actionIds.forEach { id ->
            views.setTextColor(id, textColor)
            views.setInt(id, "setBackgroundColor", textColor.withAlpha(0x33))
        }
        views.setViewVisibility(R.id.widget_apps_dpad, View.GONE)
        views.setViewVisibility(R.id.widget_apps_actions, View.GONE)
        views.setViewVisibility(R.id.widget_apps_grid, View.GONE)
        views.setViewVisibility(R.id.widget_apps_columns, View.GONE)
        when (gameId) {
            "2048" -> {
                views.setViewVisibility(R.id.widget_apps_dpad, View.VISIBLE)
                views.setViewVisibility(R.id.widget_apps_actions, View.VISIBLE)
                bindGameAction(views, R.id.widget_apps_btn_up, "UP", appWidgetId, gameId, WidgetGameAction.UP)
                bindGameAction(views, R.id.widget_apps_btn_down, "DOWN", appWidgetId, gameId, WidgetGameAction.DOWN)
                bindGameAction(views, R.id.widget_apps_btn_left, "LEFT", appWidgetId, gameId, WidgetGameAction.LEFT)
                bindGameAction(views, R.id.widget_apps_btn_right, "RIGHT", appWidgetId, gameId, WidgetGameAction.RIGHT)
                bindGameAction(
                    views,
                    R.id.widget_apps_action_1,
                    translate("新游戏", "New", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.NEW,
                )
                views.setViewVisibility(R.id.widget_apps_action_2, View.GONE)
            }
            "snake" -> {
                views.setViewVisibility(R.id.widget_apps_dpad, View.VISIBLE)
                views.setViewVisibility(R.id.widget_apps_actions, View.VISIBLE)
                bindGameAction(views, R.id.widget_apps_btn_up, "UP", appWidgetId, gameId, WidgetGameAction.UP)
                bindGameAction(views, R.id.widget_apps_btn_down, "DOWN", appWidgetId, gameId, WidgetGameAction.DOWN)
                bindGameAction(views, R.id.widget_apps_btn_left, "LEFT", appWidgetId, gameId, WidgetGameAction.LEFT)
                bindGameAction(views, R.id.widget_apps_btn_right, "RIGHT", appWidgetId, gameId, WidgetGameAction.RIGHT)
                bindGameAction(
                    views,
                    R.id.widget_apps_action_1,
                    translate("新游戏", "New", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.NEW,
                )
                views.setViewVisibility(R.id.widget_apps_action_2, View.GONE)
            }
            "tetris" -> {
                views.setViewVisibility(R.id.widget_apps_dpad, View.VISIBLE)
                views.setViewVisibility(R.id.widget_apps_actions, View.VISIBLE)
                bindGameAction(views, R.id.widget_apps_btn_up, "ROT", appWidgetId, gameId, WidgetGameAction.ROTATE)
                bindGameAction(views, R.id.widget_apps_btn_down, "DOWN", appWidgetId, gameId, WidgetGameAction.DOWN)
                bindGameAction(views, R.id.widget_apps_btn_left, "LEFT", appWidgetId, gameId, WidgetGameAction.LEFT)
                bindGameAction(views, R.id.widget_apps_btn_right, "RIGHT", appWidgetId, gameId, WidgetGameAction.RIGHT)
                bindGameAction(
                    views,
                    R.id.widget_apps_action_1,
                    translate("硬降", "Drop", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.DROP,
                )
                bindGameAction(
                    views,
                    R.id.widget_apps_action_2,
                    translate("新游戏", "New", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.NEW,
                )
            }
            "minesweeper" -> {
                views.setViewVisibility(R.id.widget_apps_grid, View.VISIBLE)
                views.setViewVisibility(R.id.widget_apps_actions, View.VISIBLE)
                bindGridCells(views, appWidgetId, gameId)
                bindGameAction(
                    views,
                    R.id.widget_apps_action_1,
                    translate(if (flagMode()) "标旗中" else "标旗", if (flagMode()) "Flagging" else "Flag", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.FLAG,
                )
                bindGameAction(
                    views,
                    R.id.widget_apps_action_2,
                    translate("新游戏", "New", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.NEW,
                )
            }
            "spider" -> {
                views.setViewVisibility(R.id.widget_apps_columns, View.VISIBLE)
                views.setViewVisibility(R.id.widget_apps_actions, View.VISIBLE)
                val selected = selectedSpiderColumn()
                SPIDER_COLUMN_VIEW_IDS.forEachIndexed { index, viewId ->
                    val label = if (selected == index) "> " + (index + 1) else (index + 1).toString()
                    bindGameAction(views, viewId, label, appWidgetId, gameId, WidgetGameAction.CELL, index)
                }
                bindGameAction(
                    views,
                    R.id.widget_apps_action_1,
                    translate("发牌", "Deal", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.DEAL,
                )
                bindGameAction(
                    views,
                    R.id.widget_apps_action_2,
                    translate("撤销", "Undo", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.UNDO,
                )
            }
            "go" -> {
                views.setViewVisibility(R.id.widget_apps_grid, View.VISIBLE)
                views.setViewVisibility(R.id.widget_apps_actions, View.VISIBLE)
                bindGridCells(views, appWidgetId, gameId)
                bindGameAction(
                    views,
                    R.id.widget_apps_action_1,
                    translate("停着", "Pass", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.PASS,
                )
                bindGameAction(
                    views,
                    R.id.widget_apps_action_2,
                    translate("新游戏", "New", settings.appLanguage),
                    appWidgetId,
                    gameId,
                    WidgetGameAction.NEW,
                )
            }
        }
    }

    private fun bindGridCells(views: RemoteViews, appWidgetId: Int, gameId: String) {
        GRID_CELL_VIEW_IDS.forEachIndexed { index, viewId ->
            views.setInt(viewId, "setBackgroundColor", 0x00000000)
            views.setContentDescription(viewId, "")
            bindGameAction(views, viewId, "", appWidgetId, gameId, WidgetGameAction.CELL, index)
        }
    }

    private fun bindGameAction(
        views: RemoteViews,
        viewId: Int,
        label: String,
        appWidgetId: Int,
        gameId: String,
        action: WidgetGameAction,
        cell: Int = -1,
    ) {
        views.setTextViewText(viewId, label)
        views.setContentDescription(viewId, label.ifBlank { gameId })
        val identity = appWidgetId.toString() + "/" + gameId + "/" + action.name + "/" + cell
        views.setOnClickPendingIntent(
            viewId,
            PendingIntent.getBroadcast(
                context,
                identity.hashCode(),
                Intent(context, DesktopWidgetGameActionReceiver::class.java)
                    .setAction(DesktopWidgetGameActionReceiver.ACTION_GAME_ACTION)
                    .setData(Uri.parse("deskcubby://widget-game/" + identity))
                    .putExtra(EXTRA_GAME_ID, gameId)
                    .putExtra(EXTRA_GAME_ACTION, action.name)
                    .putExtra(EXTRA_GAME_CELL, cell),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun applyAppPanelBase(views: RemoteViews, settings: AppSettings, title: String) {
        views.setInt(R.id.widget_apps_root, "setBackgroundColor", settings.themeColorArgb or 0xFF000000.toInt())
        views.setTextViewText(R.id.widget_apps_title, title)
        views.setTextColor(R.id.widget_apps_title, appPanelTextColor(settings))
    }

    private fun appPanelTextColor(settings: AppSettings): Int =
        if (androidx.core.graphics.ColorUtils.calculateLuminance(settings.themeColorArgb or 0xFF000000.toInt()) > 0.48) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

    private fun boardTitle(gameId: String, settings: AppSettings): String = when (gameId) {
        "2048" -> "2048"
        "snake" -> translate("贪吃蛇", "Snake", settings.appLanguage)
        "tetris" -> translate("俄罗斯方块", "Tetris", settings.appLanguage)
        "minesweeper" -> translate("扫雷", "Minesweeper", settings.appLanguage)
        "spider" -> translate("蜘蛛纸牌", "Spider", settings.appLanguage)
        "go" -> translate("围棋", "Go", settings.appLanguage)
        else -> gameId
    }

    private fun flagMode(): Boolean = widgetPreferences.getBoolean("flag_mode", false)

    private fun setFlagMode(value: Boolean) {
        widgetPreferences.edit().putBoolean("flag_mode", value).apply()
    }

    private fun selectedSpiderColumn(): Int = widgetPreferences.getInt("spider_column", -1)

    private fun setSelectedSpiderColumn(value: Int) {
        widgetPreferences.edit().putInt("spider_column", value).apply()
    }

    companion object {
        const val EXTRA_GAME_ID = "com.deskcubby.app.extra.WIDGET_GAME_ID"
        const val EXTRA_GAME_ACTION = "com.deskcubby.app.extra.WIDGET_GAME_ACTION"
        const val EXTRA_GAME_CELL = "com.deskcubby.app.extra.WIDGET_GAME_CELL"

        private val SPIDER_COLUMN_VIEW_IDS = listOf(
            R.id.widget_apps_column_1,
            R.id.widget_apps_column_2,
            R.id.widget_apps_column_3,
            R.id.widget_apps_column_4,
            R.id.widget_apps_column_5,
            R.id.widget_apps_column_6,
            R.id.widget_apps_column_7,
            R.id.widget_apps_column_8,
            R.id.widget_apps_column_9,
            R.id.widget_apps_column_10,
        )
        private val GRID_CELL_VIEW_IDS: List<Int> = (1..81).map(::gridCellId)
        private fun gridCellId(index: Int): Int = when (index) {
            1 -> R.id.widget_apps_cell_1
            2 -> R.id.widget_apps_cell_2
            3 -> R.id.widget_apps_cell_3
            4 -> R.id.widget_apps_cell_4
            5 -> R.id.widget_apps_cell_5
            6 -> R.id.widget_apps_cell_6
            7 -> R.id.widget_apps_cell_7
            8 -> R.id.widget_apps_cell_8
            9 -> R.id.widget_apps_cell_9
            10 -> R.id.widget_apps_cell_10
            11 -> R.id.widget_apps_cell_11
            12 -> R.id.widget_apps_cell_12
            13 -> R.id.widget_apps_cell_13
            14 -> R.id.widget_apps_cell_14
            15 -> R.id.widget_apps_cell_15
            16 -> R.id.widget_apps_cell_16
            17 -> R.id.widget_apps_cell_17
            18 -> R.id.widget_apps_cell_18
            19 -> R.id.widget_apps_cell_19
            20 -> R.id.widget_apps_cell_20
            21 -> R.id.widget_apps_cell_21
            22 -> R.id.widget_apps_cell_22
            23 -> R.id.widget_apps_cell_23
            24 -> R.id.widget_apps_cell_24
            25 -> R.id.widget_apps_cell_25
            26 -> R.id.widget_apps_cell_26
            27 -> R.id.widget_apps_cell_27
            28 -> R.id.widget_apps_cell_28
            29 -> R.id.widget_apps_cell_29
            30 -> R.id.widget_apps_cell_30
            31 -> R.id.widget_apps_cell_31
            32 -> R.id.widget_apps_cell_32
            33 -> R.id.widget_apps_cell_33
            34 -> R.id.widget_apps_cell_34
            35 -> R.id.widget_apps_cell_35
            36 -> R.id.widget_apps_cell_36
            37 -> R.id.widget_apps_cell_37
            38 -> R.id.widget_apps_cell_38
            39 -> R.id.widget_apps_cell_39
            40 -> R.id.widget_apps_cell_40
            41 -> R.id.widget_apps_cell_41
            42 -> R.id.widget_apps_cell_42
            43 -> R.id.widget_apps_cell_43
            44 -> R.id.widget_apps_cell_44
            45 -> R.id.widget_apps_cell_45
            46 -> R.id.widget_apps_cell_46
            47 -> R.id.widget_apps_cell_47
            48 -> R.id.widget_apps_cell_48
            49 -> R.id.widget_apps_cell_49
            50 -> R.id.widget_apps_cell_50
            51 -> R.id.widget_apps_cell_51
            52 -> R.id.widget_apps_cell_52
            53 -> R.id.widget_apps_cell_53
            54 -> R.id.widget_apps_cell_54
            55 -> R.id.widget_apps_cell_55
            56 -> R.id.widget_apps_cell_56
            57 -> R.id.widget_apps_cell_57
            58 -> R.id.widget_apps_cell_58
            59 -> R.id.widget_apps_cell_59
            60 -> R.id.widget_apps_cell_60
            61 -> R.id.widget_apps_cell_61
            62 -> R.id.widget_apps_cell_62
            63 -> R.id.widget_apps_cell_63
            64 -> R.id.widget_apps_cell_64
            65 -> R.id.widget_apps_cell_65
            66 -> R.id.widget_apps_cell_66
            67 -> R.id.widget_apps_cell_67
            68 -> R.id.widget_apps_cell_68
            69 -> R.id.widget_apps_cell_69
            70 -> R.id.widget_apps_cell_70
            71 -> R.id.widget_apps_cell_71
            72 -> R.id.widget_apps_cell_72
            73 -> R.id.widget_apps_cell_73
            74 -> R.id.widget_apps_cell_74
            75 -> R.id.widget_apps_cell_75
            76 -> R.id.widget_apps_cell_76
            77 -> R.id.widget_apps_cell_77
            78 -> R.id.widget_apps_cell_78
            79 -> R.id.widget_apps_cell_79
            80 -> R.id.widget_apps_cell_80
            else -> R.id.widget_apps_cell_81
        }
    }
}

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
