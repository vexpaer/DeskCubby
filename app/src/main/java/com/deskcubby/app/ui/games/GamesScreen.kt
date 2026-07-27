package com.deskcubby.app.ui.games

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deskcubby.app.games.Game2048
import com.deskcubby.app.games.SnakeGame
import com.deskcubby.app.games.TetrisGame
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun GamesScreen(padding: PaddingValues, viewModel: GamesViewModel) {
    var launch by remember { mutableStateOf<GameLaunch?>(null) }
    val onLaunch: (String, Boolean) -> Unit = { gameId, resume ->
        if (!resume) viewModel.clearSave(gameId)
        launch = GameLaunch(gameId, resume)
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        val current = launch
        if (current == null) {
            GameListPage(viewModel, onLaunch)
        } else {
            when (current.gameId) {
                GamesViewModel.GAME_2048 -> Game2048Page(viewModel, current.resume) { launch = null }
                GamesViewModel.GAME_SNAKE -> SnakePage(viewModel, current.resume) { launch = null }
                GamesViewModel.GAME_TETRIS -> TetrisPage(viewModel, current.resume) { launch = null }
                else -> GameListPage(viewModel, onLaunch)
            }
        }
    }
}

private data class GameLaunch(val gameId: String, val resume: Boolean)

// ---------------------------------------------------------------------------------------------
// Game list
// ---------------------------------------------------------------------------------------------

@Composable
private fun GameListPage(viewModel: GamesViewModel, onLaunch: (String, Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            tr("小游戏", "Mini games"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        GameCard(
            gameId = GamesViewModel.GAME_2048,
            title = tr("2048", "2048"),
            subtitle = tr("滑动合并数字，冲击 2048", "Swipe to merge tiles and reach 2048"),
            viewModel = viewModel,
            onLaunch = onLaunch,
        )
        GameCard(
            gameId = GamesViewModel.GAME_SNAKE,
            title = tr("贪吃蛇", "Snake"),
            subtitle = tr("吃食物长大，别撞墙或自己", "Eat food and avoid walls and yourself"),
            viewModel = viewModel,
            onLaunch = onLaunch,
        )
        GameCard(
            gameId = GamesViewModel.GAME_TETRIS,
            title = tr("俄罗斯方块", "Tetris"),
            subtitle = tr("旋转方块，消除整行", "Rotate pieces and clear lines"),
            viewModel = viewModel,
            onLaunch = onLaunch,
        )
    }
}

@Composable
private fun GameCard(
    gameId: String,
    title: String,
    subtitle: String,
    viewModel: GamesViewModel,
    onLaunch: (String, Boolean) -> Unit,
) {
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        tr("最高分", "Best"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        meta.highScore.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (meta.hasSave) {
                    Button(onClick = { onLaunch(gameId, true) }) { Text(tr("继续", "Resume")) }
                    OutlinedButton(onClick = { onLaunch(gameId, false) }) { Text(tr("新游戏", "New game")) }
                } else {
                    Button(onClick = { onLaunch(gameId, false) }) { Text(tr("开始", "Start")) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared game page chrome
// ---------------------------------------------------------------------------------------------

@Composable
private fun GameFrame(
    title: String,
    score: Int,
    highScore: Int,
    pauseVisible: Boolean,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (pauseVisible) {
                IconButton(onClick = onTogglePause) {
                    if (paused) {
                        Icon(Icons.Outlined.PlayArrow, tr("继续", "Resume"))
                    } else {
                        Icon(Icons.Outlined.Pause, tr("暂停", "Pause"))
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScoreChip(tr("分数", "Score"), score, Modifier.weight(1f))
            ScoreChip(tr("最高分", "Best"), highScore, Modifier.weight(1f))
        }
        content()
    }
}

@Composable
private fun ScoreChip(label: String, value: Int, modifier: Modifier = Modifier) {
    GlassPanel(
        modifier = modifier,
        cornerRadius = 14.dp,
        padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PauseOverlay() {
    GlassPanel(
        cornerRadius = 16.dp,
        padding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(tr("已暂停，进度已保存", "Paused, progress saved"), style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Pauses before the host leaves the foreground and persists once more when this game page leaves
 * composition. The latest callback is used without reinstalling the lifecycle observer on every
 * frame, which keeps fast-moving games from producing disposal writes during recomposition.
 */
@Composable
private fun GameAutoPauseEffect(onPauseAndSave: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestPauseAndSave by rememberUpdatedState(onPauseAndSave)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                latestPauseAndSave()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestPauseAndSave()
        }
    }
}

@Composable
private fun GameOverDialog(score: Int, onRestart: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onExit,
        title = { Text(tr("游戏结束", "Game over")) },
        text = { Text(tr("本局得分：$score", "Score: $score")) },
        confirmButton = {
            TextButton(onClick = onRestart) { Text(tr("再来一局", "Play again")) }
        },
        dismissButton = {
            TextButton(onClick = onExit) { Text(tr("返回", "Back")) }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Swipe handling
// ---------------------------------------------------------------------------------------------

private enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/** Accumulates one drag and reports a single dominant-axis swipe when it ends. */
private fun Modifier.swipeInput(enabled: Boolean, onSwipe: (SwipeDirection) -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        var dragX = 0f
        var dragY = 0f
        val threshold = 42.dp.toPx()
        detectDragGestures(
            onDragStart = {
                dragX = 0f
                dragY = 0f
            },
            onDrag = { change, amount ->
                change.consume()
                dragX += amount.x
                dragY += amount.y
            },
            onDragEnd = {
                val direction = when {
                    abs(dragX) < threshold && abs(dragY) < threshold -> null
                    abs(dragX) >= abs(dragY) ->
                        if (dragX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                    else ->
                        if (dragY > 0) SwipeDirection.DOWN else SwipeDirection.UP
                }
                direction?.let(onSwipe)
            },
        )
    }

private fun SwipeDirection.to2048Direction(): Game2048.Direction = when (this) {
    SwipeDirection.UP -> Game2048.Direction.UP
    SwipeDirection.DOWN -> Game2048.Direction.DOWN
    SwipeDirection.LEFT -> Game2048.Direction.LEFT
    SwipeDirection.RIGHT -> Game2048.Direction.RIGHT
}

private fun SwipeDirection.toSnakeDirection(): SnakeGame.Direction = when (this) {
    SwipeDirection.UP -> SnakeGame.Direction.UP
    SwipeDirection.DOWN -> SnakeGame.Direction.DOWN
    SwipeDirection.LEFT -> SnakeGame.Direction.LEFT
    SwipeDirection.RIGHT -> SnakeGame.Direction.RIGHT
}

// ---------------------------------------------------------------------------------------------
// 2048
// ---------------------------------------------------------------------------------------------

@Composable
private fun Game2048Page(viewModel: GamesViewModel, resume: Boolean, onExit: () -> Unit) {
    val gameId = GamesViewModel.GAME_2048
    var engine by remember { mutableStateOf<Game2048?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var scoreRecorded by remember { mutableStateOf(false) }
    var transition by remember { mutableStateOf<Game2048.MoveResult?>(null) }
    var transitionSequence by remember { mutableIntStateOf(0) }
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (engine == null) {
            val restored = if (resume) viewModel.loadSave(gameId)?.let { Game2048.fromJson(it) } else null
            engine = restored ?: Game2048()
        }
    }

    val board = remember(engine, frame) { engine?.board ?: List(Game2048.SIZE * Game2048.SIZE) { 0 } }
    val score = remember(engine, frame) { engine?.score ?: 0 }
    val gameOver = remember(engine, frame) { engine?.isGameOver == true }

    LaunchedEffect(gameOver) {
        if (gameOver && !scoreRecorded) {
            scoreRecorded = true
            viewModel.recordScore(gameId, engine?.score ?: 0)
        }
    }

    fun saveIfRunning() {
        val current = engine ?: return
        if (!current.isGameOver) viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    fun pauseAndSave() {
        paused = true
        transition = null
        transitionSequence++
        val current = engine ?: return
        if (current.isGameOver) {
            viewModel.recordScore(gameId, current.score)
            return
        }
        viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    GameAutoPauseEffect(onPauseAndSave = ::pauseAndSave)

    GameFrame(
        title = tr("2048", "2048"),
        score = score,
        highScore = maxOf(meta.highScore, score),
        pauseVisible = engine != null && !gameOver,
        paused = paused,
        onTogglePause = {
            paused = !paused
            if (paused) {
                transition = null
                transitionSequence++
                saveIfRunning()
            }
        },
        onBack = {
            saveIfRunning()
            onExit()
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Board2048(
                board = board,
                enabled = engine != null && !paused && !gameOver,
                transition = transition,
                transitionSequence = transitionSequence,
                animate = !paused,
                onMove = { direction ->
                    val current = engine
                    if (current != null && !paused && !current.isGameOver) {
                        current.moveWithResult(direction)?.let { result ->
                            // Commit every swipe immediately. A later swipe cancels only the
                            // previous visual transition, never its already-persistable state.
                            transition = result
                            transitionSequence++
                            frame++
                        }
                    }
                },
                modifier = Modifier.aspectRatio(1f),
            )
            if (paused) PauseOverlay()
        }
    }

    if (gameOver) {
        GameOverDialog(
            score = score,
            onRestart = {
                engine = Game2048()
                transition = null
                transitionSequence++
                frame++
                paused = false
                scoreRecorded = false
            },
            onExit = onExit,
        )
    }
}

internal fun shouldAnimate2048Transition(
    animate: Boolean,
    hasTransition: Boolean,
    systemAnimationsEnabled: Boolean,
): Boolean = animate && hasTransition && systemAnimationsEnabled

@Composable
private fun Board2048(
    board: List<Int>,
    enabled: Boolean,
    transition: Game2048.MoveResult?,
    transitionSequence: Int,
    animate: Boolean,
    onMove: (Game2048.Direction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shouldAnimate = shouldAnimate2048Transition(
        animate = animate,
        hasTransition = transition != null,
        systemAnimationsEnabled = ValueAnimator.areAnimatorsEnabled(),
    )
    val progress = remember(transitionSequence, shouldAnimate) {
        Animatable(if (shouldAnimate) 0f else 1f)
    }

    LaunchedEffect(progress, shouldAnimate) {
        if (!shouldAnimate) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = GAME_2048_ANIMATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .swipeInput(enabled) { swipe -> onMove(swipe.to2048Direction()) },
    ) {
        val cellGap = 8.dp
        val boardPadding = 8.dp
        val tileSize = (maxWidth - boardPadding * 2 - cellGap * (Game2048.SIZE - 1)) /
            Game2048.SIZE
        val density = LocalDensity.current
        val paddingPx = with(density) { boardPadding.toPx() }
        val stepPx = with(density) { (tileSize + cellGap).toPx() }

        fun cellOffset(index: Int): IntOffset {
            val column = index % Game2048.SIZE
            val row = index / Game2048.SIZE
            return IntOffset(
                x = (paddingPx + column * stepPx).roundToInt(),
                y = (paddingPx + row * stepPx).roundToInt(),
            )
        }

        repeat(Game2048.SIZE * Game2048.SIZE) { index ->
            Box(
                Modifier
                    .offset { cellOffset(index) }
                    .size(tileSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surface.copy(alpha = 0.6f)),
            )
        }

        val currentTransition = transition
        val animationProgress = progress.value
        if (currentTransition != null && animationProgress < GAME_2048_SLIDE_END) {
            val slideProgress = FastOutSlowInEasing.transform(
                (animationProgress / GAME_2048_SLIDE_END).coerceIn(0f, 1f),
            )
            currentTransition.motions.forEachIndexed { order, motion ->
                val start = cellOffset(motion.fromIndex)
                val end = cellOffset(motion.toIndex)
                val animatedOffset = IntOffset(
                    x = (start.x + (end.x - start.x) * slideProgress).roundToInt(),
                    y = (start.y + (end.y - start.y) * slideProgress).roundToInt(),
                )
                key(transitionSequence, motion.fromIndex, motion.toIndex, order) {
                    Tile2048(
                        value = motion.value,
                        modifier = Modifier
                            .offset { animatedOffset }
                            .size(tileSize),
                    )
                }
            }
        } else {
            val resultBoard = currentTransition?.after ?: board
            val popProgress = if (currentTransition == null) {
                1f
            } else {
                ((animationProgress - GAME_2048_SLIDE_END) /
                    (1f - GAME_2048_SLIDE_END)).coerceIn(0f, 1f)
            }
            val mergeDestinations = currentTransition?.merges?.mapTo(HashSet<Int>()) { it.toIndex }
                ?: emptySet()
            resultBoard.forEachIndexed { index, value ->
                if (value == 0) return@forEachIndexed
                val tileScale = when {
                    currentTransition == null -> 1f
                    index == currentTransition.spawn.index -> spawnTileScale(popProgress)
                    index in mergeDestinations -> mergedTileScale(popProgress)
                    else -> 1f
                }
                key(transitionSequence, index, value) {
                    Tile2048(
                        value = value,
                        modifier = Modifier
                            .offset { cellOffset(index) }
                            .size(tileSize)
                            .scale(tileScale),
                    )
                }
            }
        }
    }
}

/** A short overshoot makes a newly generated tile distinct without moving surrounding layout. */
private fun spawnTileScale(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return if (value < 0.72f) {
        1.08f * FastOutSlowInEasing.transform(value / 0.72f)
    } else {
        1.08f - 0.08f * ((value - 0.72f) / 0.28f)
    }
}

/** Merged tiles briefly compress and pop after both source tiles meet. */
private fun mergedTileScale(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return when {
        value < 0.46f -> 0.84f + 0.30f * FastOutSlowInEasing.transform(value / 0.46f)
        else -> 1.14f - 0.14f * ((value - 0.46f) / 0.54f)
    }
}

@Composable
private fun Tile2048(value: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Tile shade moves only between the user-selected secondary and primary colors.
    val fraction = if (value <= 0) 0f else (log2(value.toFloat()) / 11f).coerceIn(0.08f, 1f)
    val background = if (value <= 0) {
        scheme.surface.copy(alpha = 0.6f)
    } else {
        lerp(scheme.secondary, scheme.primary, fraction)
    }
    val textColor = if (fraction > 0.5f) scheme.onPrimary else scheme.onSecondary
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (value > 0) {
            Text(
                value.toString(),
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = when {
                    value < 100 -> 22.sp
                    value < 1000 -> 19.sp
                    else -> 16.sp
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Snake
// ---------------------------------------------------------------------------------------------

@Composable
private fun SnakePage(viewModel: GamesViewModel, resume: Boolean, onExit: () -> Unit) {
    val gameId = GamesViewModel.GAME_SNAKE
    var engine by remember { mutableStateOf<SnakeGame?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var scoreRecorded by remember { mutableStateOf(false) }
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (engine == null) {
            val restored = if (resume) viewModel.loadSave(gameId)?.let { SnakeGame.fromJson(it) } else null
            engine = restored ?: SnakeGame()
        }
    }

    val score = remember(engine, frame) { engine?.score ?: 0 }
    val gameOver = remember(engine, frame) { engine?.isGameOver == true }

    LaunchedEffect(gameOver) {
        if (gameOver && !scoreRecorded) {
            scoreRecorded = true
            viewModel.recordScore(gameId, engine?.score ?: 0)
        }
    }

    LaunchedEffect(engine, paused, gameOver) {
        val current = engine ?: return@LaunchedEffect
        if (paused || current.isGameOver) return@LaunchedEffect
        while (isActive && !current.isGameOver) {
            delay(SNAKE_TICK_MILLIS)
            if (paused || current.isGameOver) break
            current.tick()
            frame++
        }
    }

    fun saveIfRunning() {
        val current = engine ?: return
        if (!current.isGameOver) viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    fun pauseAndSave() {
        paused = true
        val current = engine ?: return
        if (current.isGameOver) {
            viewModel.recordScore(gameId, current.score)
            return
        }
        viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    fun steer(direction: SnakeGame.Direction) {
        val current = engine ?: return
        if (paused || current.isGameOver) return
        current.setDirection(direction)
    }

    GameAutoPauseEffect(onPauseAndSave = ::pauseAndSave)

    GameFrame(
        title = tr("贪吃蛇", "Snake"),
        score = score,
        highScore = maxOf(meta.highScore, score),
        pauseVisible = engine != null && !gameOver,
        paused = paused,
        onTogglePause = {
            paused = !paused
            if (paused) saveIfRunning()
        },
        onBack = {
            saveIfRunning()
            onExit()
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            SnakeBoard(
                engine = engine,
                frame = frame,
                enabled = engine != null && !paused && !gameOver,
                onSwipe = { swipe -> steer(swipe.toSnakeDirection()) },
                modifier = Modifier.aspectRatio(1f),
            )
            if (paused) PauseOverlay()
        }
        Spacer(Modifier.height(8.dp))
        DirectionPad(
            enabled = engine != null && !paused && !gameOver,
            onDirection = { direction -> steer(direction) },
        )
    }

    if (gameOver) {
        GameOverDialog(
            score = score,
            onRestart = {
                engine = SnakeGame()
                frame++
                paused = false
                scoreRecorded = false
            },
            onExit = onExit,
        )
    }
}

@Composable
private fun SnakeBoard(
    engine: SnakeGame?,
    frame: Int,
    enabled: Boolean,
    onSwipe: (SwipeDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val snake = remember(engine, frame) { engine?.snake ?: emptyList() }
    val food = remember(engine, frame) { engine?.food }
    val gridWidth = engine?.width ?: SnakeGame.DEFAULT_WIDTH
    val gridHeight = engine?.height ?: SnakeGame.DEFAULT_HEIGHT
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .swipeInput(enabled, onSwipe),
    ) {
        val cellW = size.width / gridWidth
        val cellH = size.height / gridHeight
        food?.let {
            drawCircle(
                color = scheme.secondary,
                radius = min(cellW, cellH) * 0.32f,
                center = Offset((it.x + 0.5f) * cellW, (it.y + 0.5f) * cellH),
            )
        }
        snake.forEachIndexed { index, cell ->
            drawRoundRect(
                color = if (index == 0) {
                    scheme.primary
                } else {
                    lerp(scheme.primary, scheme.secondary, 0.42f)
                },
                topLeft = Offset(cell.x * cellW + 1f, cell.y * cellH + 1f),
                size = Size(cellW - 2f, cellH - 2f),
                cornerRadius = CornerRadius(cellW * 0.3f),
            )
        }
    }
}

@Composable
private fun DirectionPad(enabled: Boolean, onDirection: (SnakeGame.Direction) -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledTonalIconButton(onClick = { onDirection(SnakeGame.Direction.UP) }, enabled = enabled) {
            Icon(Icons.Outlined.KeyboardArrowUp, tr("上", "Up"))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = { onDirection(SnakeGame.Direction.LEFT) }, enabled = enabled) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, tr("左", "Left"))
            }
            FilledTonalIconButton(onClick = { onDirection(SnakeGame.Direction.DOWN) }, enabled = enabled) {
                Icon(Icons.Outlined.KeyboardArrowDown, tr("下", "Down"))
            }
            FilledTonalIconButton(onClick = { onDirection(SnakeGame.Direction.RIGHT) }, enabled = enabled) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, tr("右", "Right"))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tetris
// ---------------------------------------------------------------------------------------------

@Composable
private fun TetrisPage(viewModel: GamesViewModel, resume: Boolean, onExit: () -> Unit) {
    val gameId = GamesViewModel.GAME_TETRIS
    var engine by remember { mutableStateOf<TetrisGame?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var scoreRecorded by remember { mutableStateOf(false) }
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    // All piece colors are interpolated strictly between the configured primary and secondary.
    val pieceColors = remember(scheme) {
        List(7) { index -> lerp(scheme.primary, scheme.secondary, index / 6f) }
    }

    LaunchedEffect(Unit) {
        if (engine == null) {
            val restored = if (resume) viewModel.loadSave(gameId)?.let { TetrisGame.fromJson(it) } else null
            engine = restored ?: TetrisGame()
        }
    }

    val score = remember(engine, frame) { engine?.score ?: 0 }
    val lines = remember(engine, frame) { engine?.lines ?: 0 }
    val level = remember(engine, frame) { engine?.level ?: 0 }
    val gameOver = remember(engine, frame) { engine?.isGameOver == true }
    val boardCells = remember(engine, frame) {
        val current = engine ?: return@remember IntArray(TetrisGame.WIDTH * TetrisGame.HEIGHT)
        val merged = current.boardSnapshot().toIntArray()
        current.currentPieceCells().forEach { cell ->
            if (cell.x in 0 until TetrisGame.WIDTH && cell.y in 0 until TetrisGame.HEIGHT) {
                merged[cell.y * TetrisGame.WIDTH + cell.x] = current.pieceType + 1
            }
        }
        merged
    }
    val nextCells = remember(engine, frame) { engine?.nextPiecePreviewCells() ?: emptyList() }
    val nextColor = remember(engine, frame, pieceColors) {
        pieceColors[(engine?.nextPieceType ?: 0) % pieceColors.size]
    }

    LaunchedEffect(gameOver) {
        if (gameOver && !scoreRecorded) {
            scoreRecorded = true
            viewModel.recordScore(gameId, engine?.score ?: 0)
        }
    }

    LaunchedEffect(engine, paused, gameOver) {
        val current = engine ?: return@LaunchedEffect
        if (paused || current.isGameOver) return@LaunchedEffect
        while (isActive && !current.isGameOver) {
            delay(
                (TETRIS_BASE_TICK_MILLIS - TETRIS_LEVEL_STEP_MILLIS * current.level)
                    .coerceAtLeast(TETRIS_MIN_TICK_MILLIS),
            )
            if (paused || current.isGameOver) break
            current.tick()
            frame++
        }
    }

    fun saveIfRunning() {
        val current = engine ?: return
        if (!current.isGameOver) viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    fun pauseAndSave() {
        paused = true
        val current = engine ?: return
        if (current.isGameOver) {
            viewModel.recordScore(gameId, current.score)
            return
        }
        viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    fun act(action: (TetrisGame) -> Unit) {
        val current = engine ?: return
        if (paused || current.isGameOver) return
        action(current)
        frame++
    }

    GameAutoPauseEffect(onPauseAndSave = ::pauseAndSave)

    GameFrame(
        title = tr("俄罗斯方块", "Tetris"),
        score = score,
        highScore = maxOf(meta.highScore, score),
        pauseVisible = engine != null && !gameOver,
        paused = paused,
        onTogglePause = {
            paused = !paused
            if (paused) saveIfRunning()
        },
        onBack = {
            saveIfRunning()
            onExit()
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                TetrisBoard(
                    cells = boardCells,
                    pieceColors = pieceColors,
                    modifier = Modifier.aspectRatio(TetrisGame.WIDTH.toFloat() / TetrisGame.HEIGHT),
                )
                if (paused) PauseOverlay()
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NextPiecePanel(cells = nextCells, color = nextColor)
                GlassPanel(
                    cornerRadius = 14.dp,
                    padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            tr("行数", "Lines") + ": $lines",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            tr("等级", "Level") + ": $level",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val controlsEnabled = engine != null && !paused && !gameOver
            FilledTonalIconButton(onClick = { act { it.moveLeft() } }, enabled = controlsEnabled) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, tr("左移", "Left"))
            }
            FilledTonalIconButton(onClick = { act { it.rotate() } }, enabled = controlsEnabled) {
                Icon(Icons.Outlined.RotateRight, tr("旋转", "Rotate"))
            }
            FilledTonalIconButton(onClick = { act { it.softDrop() } }, enabled = controlsEnabled) {
                Icon(Icons.Outlined.ArrowDownward, tr("加速下落", "Soft drop"))
            }
            FilledTonalIconButton(onClick = { act { it.hardDrop() } }, enabled = controlsEnabled) {
                Icon(Icons.Outlined.VerticalAlignBottom, tr("硬降", "Hard drop"))
            }
            FilledTonalIconButton(onClick = { act { it.moveRight() } }, enabled = controlsEnabled) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, tr("右移", "Right"))
            }
        }
    }

    if (gameOver) {
        GameOverDialog(
            score = score,
            onRestart = {
                engine = TetrisGame()
                frame++
                paused = false
                scoreRecorded = false
            },
            onExit = onExit,
        )
    }
}

@Composable
private fun TetrisBoard(cells: IntArray, pieceColors: List<Color>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        val cellW = size.width / TetrisGame.WIDTH
        val cellH = size.height / TetrisGame.HEIGHT
        for (y in 0 until TetrisGame.HEIGHT) {
            for (x in 0 until TetrisGame.WIDTH) {
                val value = cells[y * TetrisGame.WIDTH + x]
                if (value == 0) continue
                drawRoundRect(
                    color = pieceColors[(value - 1) % pieceColors.size],
                    topLeft = Offset(x * cellW + 1f, y * cellH + 1f),
                    size = Size(cellW - 2f, cellH - 2f),
                    cornerRadius = CornerRadius(cellW * 0.18f),
                )
            }
        }
    }
}

@Composable
private fun NextPiecePanel(cells: List<TetrisGame.Cell>, color: Color) {
    GlassPanel(cornerRadius = 14.dp, padding = PaddingValues(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tr("下一块", "Next"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Canvas(Modifier.size(64.dp)) {
                if (cells.isEmpty()) return@Canvas
                val unit = size.minDimension / 4f
                val spanX = (cells.maxOf { it.x } + 1) * unit
                val spanY = (cells.maxOf { it.y } + 1) * unit
                val offsetX = (size.width - spanX) / 2f
                val offsetY = (size.height - spanY) / 2f
                cells.forEach { cell ->
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(offsetX + cell.x * unit + 1f, offsetY + cell.y * unit + 1f),
                        size = Size(unit - 2f, unit - 2f),
                        cornerRadius = CornerRadius(unit * 0.2f),
                    )
                }
            }
        }
    }
}

private const val SNAKE_TICK_MILLIS = 220L
private const val GAME_2048_ANIMATION_MILLIS = 190
private const val GAME_2048_SLIDE_END = 0.64f
private const val TETRIS_BASE_TICK_MILLIS = 600L
private const val TETRIS_LEVEL_STEP_MILLIS = 40L
private const val TETRIS_MIN_TICK_MILLIS = 120L
