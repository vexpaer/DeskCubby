package com.deskcubby.app.ui.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.games.GoGame
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun GoPage(
    viewModel: GamesViewModel,
    resume: Boolean,
    onExit: () -> Unit,
) {
    val gameId = GamesViewModel.GAME_GO
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()
    var engine by remember { mutableStateOf<GoGame?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var lastError by remember { mutableStateOf<GoGame.MoveError?>(null) }
    var pendingRestartSize by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(gameId, resume) {
        val restored = if (resume) viewModel.loadSave(gameId)?.let(GoGame::fromJson) else null
        val current = restored ?: GoGame()
        engine = current
        if (restored == null) {
            viewModel.saveProgress(gameId, current.toJson(), current.captureScore())
        }
    }

    val current = engine
    GamePlayTimeEffect(
        gameId = gameId,
        viewModel = viewModel,
        active = shouldCountGamePlay(
            engineReady = current != null,
            finished = current?.isFinished == true,
        ),
    )

    if (current == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    fun saveOrFinish(game: GoGame) {
        if (game.isFinished) {
            viewModel.recordScore(gameId, game.captureScore())
        } else {
            viewModel.saveProgress(gameId, game.toJson(), game.captureScore())
        }
    }

    fun replaceGame(size: Int) {
        val replacement = GoGame(size)
        engine = replacement
        revision += 1
        lastError = null
        pendingRestartSize = null
        viewModel.saveProgress(gameId, replacement.toJson(), 0)
    }

    fun leaveGame() {
        if (!current.isFinished) {
            viewModel.saveProgress(gameId, current.toJson(), current.captureScore())
        }
        onExit()
    }

    val boardRevision = revision
    GameFrame(
        title = tr("围棋", "Go"),
        score = current.captureScore(),
        highScore = meta.highScore,
        scoreLabel = tr("本局提子", "Captured"),
        highScoreLabel = tr("最高提子", "Best captures"),
        pauseVisible = false,
        paused = false,
        onTogglePause = {},
        onBack = ::leaveGame,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GoStatusPanel(current)
            GoSizeSelector(
                selectedSize = current.size,
                onSelect = { size ->
                    if (size != current.size || current.turnCount > 0) {
                        pendingRestartSize = size
                    }
                },
            )
            GoBoard(
                game = current,
                revision = boardRevision,
                onPlay = { x, y ->
                    val result = current.play(x, y)
                    if (result.accepted) {
                        revision += 1
                        lastError = null
                        viewModel.recordGoStatistics(result.statisticsDelta)
                        saveOrFinish(current)
                    } else {
                        lastError = result.error
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
            lastError?.let { error ->
                Text(
                    text = goMoveErrorText(error),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = {
                        val result = current.pass()
                        if (result.accepted) {
                            revision += 1
                            lastError = null
                            viewModel.recordGoStatistics(result.statisticsDelta)
                            saveOrFinish(current)
                        }
                    },
                    enabled = !current.isFinished,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("停一手", "Pass"))
                }
                OutlinedButton(
                    onClick = { pendingRestartSize = current.size },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("清空重开", "Clear & restart"))
                }
            }
            Text(
                tr(
                    "连续两次停着结束棋局；本页记录提子数，不自动判定地域胜负。",
                    "Two consecutive passes end the game. Captures are tracked; territory is not scored automatically.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    pendingRestartSize?.let { size ->
        AlertDialog(
            onDismissRequest = { pendingRestartSize = null },
            title = { Text(tr("重新开始？", "Start over?")) },
            text = {
                Text(
                    tr(
                        "当前棋局会被清空，并开始一局 ${size}×${size} 围棋。",
                        "The current board will be cleared and a ${size}×${size} game will begin.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { replaceGame(size) }) { Text(tr("重开", "Restart")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestartSize = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    if (current.isFinished && pendingRestartSize == null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("棋局结束", "Game finished")) },
            text = {
                Text(
                    tr(
                        "双方连续停着。黑方提子 ${current.capturedByBlack}，白方提子 ${current.capturedByWhite}。请按你们采用的数子或数目规则判断胜负。",
                        "Both players passed. Black captured ${current.capturedByBlack}; White captured ${current.capturedByWhite}. Use your chosen territory or area rules to determine the result.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { replaceGame(current.size) }) {
                    Text(tr("再来一局", "Play again"))
                }
            },
            dismissButton = {
                TextButton(onClick = ::leaveGame) { Text(tr("返回", "Back")) }
            },
        )
    }
}

@Composable
private fun GoStatusPanel(game: GoGame) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GoStoneDot(game.currentPlayer)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (game.currentPlayer == GoGame.Stone.BLACK) {
                            tr("黑方落子", "Black to play")
                        } else {
                            tr("白方落子", "White to play")
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    tr("第 ${game.turnCount + 1} 手", "Turn ${game.turnCount + 1}"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                tr(
                    "黑提 ${game.capturedByBlack} · 白提 ${game.capturedByWhite}",
                    "Black captures ${game.capturedByBlack} · White captures ${game.capturedByWhite}",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (game.consecutivePasses == 1) {
                Text(
                    tr("上一方已停着；再次停着将结束棋局。", "The last player passed; another pass ends the game."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun GoStoneDot(stone: GoGame.Stone) {
    val fill = if (stone == GoGame.Stone.BLACK) Color(0xFF171717) else Color(0xFFF7F7F2)
    val border = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(18.dp)) {
        drawCircle(fill)
        drawCircle(border, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun GoSizeSelector(selectedSize: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GoGame.SUPPORTED_SIZES.forEach { size ->
            if (size == selectedSize) {
                Button(onClick = { onSelect(size) }, modifier = Modifier.weight(1f)) {
                    Text("${size}×${size}")
                }
            } else {
                OutlinedButton(onClick = { onSelect(size) }, modifier = Modifier.weight(1f)) {
                    Text("${size}×${size}")
                }
            }
        }
    }
}

@Composable
private fun GoBoard(
    game: GoGame,
    revision: Int,
    onPlay: (x: Int, y: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var accessibilityX by remember(game.size) { mutableIntStateOf(game.size / 2) }
    var accessibilityY by remember(game.size) { mutableIntStateOf(game.size / 2) }
    val boardColor = MaterialTheme.colorScheme.tertiaryContainer
    val lineColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
    val outlineColor = MaterialTheme.colorScheme.outline
    val boardDescription = tr(
        "${game.size}路围棋棋盘，黑子 ${game.boardSnapshot().count { it == GoGame.Stone.BLACK }}，白子 ${game.boardSnapshot().count { it == GoGame.Stone.WHITE }}。点击交叉点落子。",
        "${game.size} by ${game.size} Go board with ${game.boardSnapshot().count { it == GoGame.Stone.BLACK }} black and ${game.boardSnapshot().count { it == GoGame.Stone.WHITE }} white stones. Tap an intersection to play.",
    )
    val turnDescription = if (game.currentPlayer == GoGame.Stone.BLACK) {
        tr("黑方落子", "Black to play")
    } else {
        tr("白方落子", "White to play")
    }
    val selectedIntersectionDescription = tr(
        "已选择第 ${accessibilityY + 1} 行、第 ${accessibilityX + 1} 列",
        "Selected row ${accessibilityY + 1}, column ${accessibilityX + 1}",
    )
    val selectedStoneDescription = when (game.stoneAt(accessibilityX, accessibilityY)) {
        GoGame.Stone.EMPTY -> tr("当前为空位", "The intersection is empty")
        GoGame.Stone.BLACK -> tr("当前有黑子", "The intersection has a black stone")
        GoGame.Stone.WHITE -> tr("当前有白子", "The intersection has a white stone")
    }
    val placeStoneLabel = tr("在选中交叉点落子", "Play at the selected intersection")
    val moveLeftLabel = tr("选择左侧交叉点", "Select the intersection to the left")
    val moveRightLabel = tr("选择右侧交叉点", "Select the intersection to the right")
    val moveUpLabel = tr("选择上方交叉点", "Select the intersection above")
    val moveDownLabel = tr("选择下方交叉点", "Select the intersection below")
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(boardColor)
            .semantics {
                contentDescription = boardDescription
                stateDescription =
                    "$turnDescription；$selectedIntersectionDescription；$selectedStoneDescription"
                onClick(label = placeStoneLabel) {
                    if (game.isFinished) {
                        false
                    } else {
                        onPlay(accessibilityX, accessibilityY)
                        true
                    }
                }
                customActions = listOf(
                    CustomAccessibilityAction(moveLeftLabel) {
                        if (accessibilityX <= 0) false else {
                            accessibilityX -= 1
                            true
                        }
                    },
                    CustomAccessibilityAction(moveRightLabel) {
                        if (accessibilityX >= game.size - 1) false else {
                            accessibilityX += 1
                            true
                        }
                    },
                    CustomAccessibilityAction(moveUpLabel) {
                        if (accessibilityY <= 0) false else {
                            accessibilityY -= 1
                            true
                        }
                    },
                    CustomAccessibilityAction(moveDownLabel) {
                        if (accessibilityY >= game.size - 1) false else {
                            accessibilityY += 1
                            true
                        }
                    },
                )
            }
            .pointerInput(game.size, revision, game.isFinished) {
                detectTapGestures { tap ->
                    if (game.isFinished) return@detectTapGestures
                    val side = min(size.width, size.height).toFloat()
                    val boardPadding = side * BOARD_PADDING_FRACTION
                    val spacing = (side - boardPadding * 2f) / (game.size - 1)
                    val x = ((tap.x - boardPadding) / spacing).roundToInt()
                    val y = ((tap.y - boardPadding) / spacing).roundToInt()
                    if (x !in 0 until game.size || y !in 0 until game.size) {
                        return@detectTapGestures
                    }
                    val centerX = boardPadding + x * spacing
                    val centerY = boardPadding + y * spacing
                    val deltaX = tap.x - centerX
                    val deltaY = tap.y - centerY
                    val hitRadius = spacing * 0.48f
                    if (deltaX * deltaX + deltaY * deltaY <= hitRadius * hitRadius) {
                        onPlay(x, y)
                    }
                }
            },
    ) {
        val side = min(size.width, size.height)
        val boardPadding = side * BOARD_PADDING_FRACTION
        val spacing = (side - boardPadding * 2f) / (game.size - 1)
        val lineWidth = (spacing * 0.055f).coerceIn(1.dp.toPx(), 2.dp.toPx())

        repeat(game.size) { index ->
            val position = boardPadding + index * spacing
            drawLine(
                lineColor,
                Offset(boardPadding, position),
                Offset(side - boardPadding, position),
                lineWidth,
            )
            drawLine(
                lineColor,
                Offset(position, boardPadding),
                Offset(position, side - boardPadding),
                lineWidth,
            )
        }
        starPoints(game.size).forEach { point ->
            drawCircle(
                color = lineColor,
                radius = (spacing * 0.10f).coerceAtLeast(2.dp.toPx()),
                center = Offset(
                    boardPadding + point.x * spacing,
                    boardPadding + point.y * spacing,
                ),
            )
        }
        repeat(game.size) { y ->
            repeat(game.size) { x ->
                val stone = game.stoneAt(x, y)
                if (stone != GoGame.Stone.EMPTY) {
                    val center = Offset(
                        boardPadding + x * spacing,
                        boardPadding + y * spacing,
                    )
                    val radius = spacing * 0.43f
                    val fill = if (stone == GoGame.Stone.BLACK) {
                        Color(0xFF171717)
                    } else {
                        Color(0xFFF7F7F2)
                    }
                    drawCircle(fill, radius, center)
                    drawCircle(
                        outlineColor.copy(alpha = 0.75f),
                        radius,
                        center,
                        style = Stroke(lineWidth),
                    )
                    if (game.lastMove == GoGame.Point(x, y)) {
                        drawCircle(
                            color = if (stone == GoGame.Stone.BLACK) Color.White else Color.Black,
                            radius = radius * 0.22f,
                            center = center,
                        )
                    }
                }
            }
        }
    }
}

private fun GoGame.captureScore(): Int = capturedByBlack + capturedByWhite

@Composable
private fun goMoveErrorText(error: GoGame.MoveError): String = when (error) {
    GoGame.MoveError.OUT_OF_BOUNDS -> tr("请点击棋盘交叉点。", "Tap a board intersection.")
    GoGame.MoveError.OCCUPIED -> tr("这个交叉点已有棋子。", "That intersection is occupied.")
    GoGame.MoveError.SUICIDE -> tr("不能下自杀棋。", "Suicide moves are not allowed.")
    GoGame.MoveError.KO -> tr("简单劫：不能立即还原上一局面。", "Simple ko: the previous position cannot be repeated immediately.")
    GoGame.MoveError.GAME_FINISHED -> tr("棋局已经结束。", "The game has finished.")
}

private fun starPoints(size: Int): List<GoGame.Point> = when (size) {
    9 -> listOf(
        GoGame.Point(2, 2), GoGame.Point(6, 2), GoGame.Point(4, 4),
        GoGame.Point(2, 6), GoGame.Point(6, 6),
    )
    13 -> listOf(3, 6, 9).flatMap { y -> listOf(3, 6, 9).map { x -> GoGame.Point(x, y) } }
    19 -> listOf(3, 9, 15).flatMap { y -> listOf(3, 9, 15).map { x -> GoGame.Point(x, y) } }
    else -> emptyList()
}

private const val BOARD_PADDING_FRACTION = 0.065f
