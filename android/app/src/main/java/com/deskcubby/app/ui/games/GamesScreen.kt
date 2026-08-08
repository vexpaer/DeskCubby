package com.deskcubby.app.ui.games

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.deskcubby.app.data.model.Game2048AnimationSpeed
import com.deskcubby.app.ui.components.PageTutorialTarget
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun GamesScreen(
    padding: PaddingValues,
    viewModel: GamesViewModel,
    initialGameId: String? = null,
    onGameOpenChanged: (Boolean) -> Unit = {},
    onTutorialTargetChanged: (PageTutorialTarget?) -> Unit = {},
) {
    var launch by rememberSaveable(
        initialGameId,
        stateSaver = Saver(
            save = { active -> active?.gameId.orEmpty() },
            restore = { gameId ->
                gameId.takeIf(GamesViewModel.GAME_IDS::contains)
                    ?.let { GameLaunch(it, resume = true) }
            },
        ),
    ) {
        mutableStateOf(
            initialGameId
                ?.takeIf(GamesViewModel.GAME_IDS::contains)
                ?.let { GameLaunch(it, resume = true) },
        )
    }
    val onLaunch: (String, Boolean) -> Unit = { gameId, resume ->
        if (!resume && gameId == GamesViewModel.GAME_SPIDER) {
            viewModel.discardSavedSpiderAndThen {
                launch = GameLaunch(gameId, resume = false)
            }
        } else {
            if (!resume) viewModel.clearSave(gameId)
            launch = GameLaunch(gameId, resume)
        }
    }
    val gameOpen = launch != null
    val tutorialTarget = gameTutorialTarget(launch)
    LaunchedEffect(gameOpen) { onGameOpenChanged(gameOpen) }
    LaunchedEffect(tutorialTarget) { onTutorialTargetChanged(tutorialTarget) }
    DisposableEffect(Unit) {
        onDispose {
            onGameOpenChanged(false)
            onTutorialTargetChanged(null)
        }
    }
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = if (gameOpen) 0.dp else padding.calculateBottomPadding())
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .then(
                    if (gameOpen) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            val current = launch
            if (current == null) {
                GameListPage(viewModel, onLaunch)
            } else {
                when (current.gameId) {
                    GamesViewModel.GAME_2048 -> Game2048Page(
                        viewModel = viewModel,
                        gameId = current.gameId,
                        boardSize = 4,
                        resume = current.resume,
                        onExit = { launch = null },
                    )
                    GamesViewModel.GAME_2048_5 -> Game2048Page(
                        viewModel = viewModel,
                        gameId = current.gameId,
                        boardSize = 5,
                        resume = current.resume,
                        onExit = { launch = null },
                    )
                    GamesViewModel.GAME_2048_6 -> Game2048Page(
                        viewModel = viewModel,
                        gameId = current.gameId,
                        boardSize = 6,
                        resume = current.resume,
                        onExit = { launch = null },
                    )
                    GamesViewModel.GAME_SNAKE ->
                        SnakePage(viewModel, current.resume) { launch = null }
                    GamesViewModel.GAME_TETRIS ->
                        TetrisPage(viewModel, current.resume) { launch = null }
                    GamesViewModel.GAME_MINESWEEPER -> MinesweeperPage(
                        viewModel = viewModel,
                        resume = current.resume,
                        onExit = { launch = null },
                        onStatisticsDelta = viewModel::recordMinesweeperStatistics,
                    )
                    GamesViewModel.GAME_SPIDER -> SpiderSolitairePage(
                        viewModel = viewModel,
                        resume = current.resume,
                        onExit = { launch = null },
                        onStatisticsDelta = viewModel::recordSpiderStatistics,
                    )
                    else -> GameListPage(viewModel, onLaunch)
                }
            }
        }
    }
}

private data class GameLaunch(val gameId: String, val resume: Boolean)

@Composable
private fun gameTutorialTarget(launch: GameLaunch?): PageTutorialTarget {
    if (launch == null) {
        return PageTutorialTarget(
            pageId = "games/list",
            title = tr("小游戏", "Mini games"),
            description = tr("选择游戏开始新局；存在存档时也可继续上次进度。", "Choose a game to start a new run, or resume when a save is available."),
            hints = listOf(
                tr("新游戏会替换对应游戏的旧存档。", "Starting fresh replaces that game's previous save."),
                tr("游戏内返回会保存支持续玩的当前局。", "Leaving a resumable game saves the current run."),
            ),
        )
    }
    val (title, description, hints) = when (launch.gameId) {
        GamesViewModel.GAME_2048,
        GamesViewModel.GAME_2048_5,
        GamesViewModel.GAME_2048_6,
        -> Triple(
            "2048",
            tr("向四个方向滑动，让相同数字合并；无法移动时游戏结束。", "Swipe in four directions to merge matching tiles; the run ends when no move remains."),
            listOf(tr("顶部可撤销一步，也可调节动画节奏。", "Use the top controls to undo one move or adjust animation speed.")),
        )
        GamesViewModel.GAME_SNAKE -> Triple(
            tr("贪吃蛇", "Snake"),
            tr("用方向按钮或手势控制蛇，吃到食物会变长；避免撞墙和自己。", "Use direction controls or gestures, eat food to grow, and avoid walls and your own body."),
            listOf(tr("暂停后可安全离开，继续游戏会恢复当前局。", "Pause before leaving; Resume restores the current run.")),
        )
        GamesViewModel.GAME_TETRIS -> Triple(
            tr("俄罗斯方块", "Tetris"),
            tr("移动、旋转并落下方块，填满一整行即可消除。", "Move, rotate, and drop pieces; complete a row to clear it."),
            listOf(tr("使用快速下落提高节奏，暂停可暂存当前局面。", "Use fast drop for pace; pausing preserves the current board.")),
        )
        GamesViewModel.GAME_MINESWEEPER -> Triple(
            tr("扫雷", "Minesweeper"),
            tr("点按翻开方格，长按标记地雷；数字表示周围地雷数量。", "Tap to reveal, long-press to flag mines; each number counts nearby mines."),
            listOf(tr("先从空白区域扩展，避免直接猜测。", "Expand from clear areas before resorting to guesses.")),
        )
        GamesViewModel.GAME_SPIDER -> Triple(
            tr("蜘蛛纸牌", "Spider Solitaire"),
            tr("按降序移动牌列；同花色从 K 到 A 的完整序列会自动收走。", "Build descending stacks; a same-suit K-to-A sequence is removed automatically."),
            listOf(
                tr("点牌堆向每列发牌；所有列都必须至少有一张牌。", "Tap the stock to deal to every column; no column may be empty."),
                tr("重开按钮会先确认，确认后当前进度不可恢复。", "Restart asks for confirmation because the current run cannot be restored afterward."),
            ),
        )
        else -> Triple(tr("小游戏", "Mini game"), tr("按照页面中的控制开始游戏。", "Use the controls on this page to play."), emptyList())
    }
    return PageTutorialTarget(
        pageId = "games/${launch.gameId}",
        title = title,
        description = description,
        hints = hints,
    )
}

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
            title = "2048 · 4×4",
            subtitle = tr("经典棋盘，节奏紧凑", "Classic compact board"),
            viewModel = viewModel,
            onLaunch = onLaunch,
        )
        GameCard(
            gameId = GamesViewModel.GAME_2048_5,
            title = "2048 · 5×5",
            subtitle = tr("空间更大，适合长局", "More space for longer games"),
            viewModel = viewModel,
            onLaunch = onLaunch,
        )
        GameCard(
            gameId = GamesViewModel.GAME_2048_6,
            title = "2048 · 6×6",
            subtitle = tr("最大棋盘，挑战高分", "Largest board for high scores"),
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
        GameCard(
            gameId = GamesViewModel.GAME_MINESWEEPER,
            title = tr("扫雷", "Minesweeper"),
            subtitle = tr("自定义行数、列数和雷数", "Custom rows, columns, and mine count"),
            viewModel = viewModel,
            onLaunch = onLaunch,
        )
        GameCard(
            gameId = GamesViewModel.GAME_SPIDER,
            title = tr("蜘蛛纸牌", "Spider Solitaire"),
            subtitle = tr("横屏一花色玩法，支持保存与撤回", "Landscape one-suit play with save and undo"),
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
                    Text(
                        tr("累计游玩 ", "Total play ") + formatGameDuration(meta.totalPlayMillis),
                        style = MaterialTheme.typography.labelSmall,
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
    undoVisible: Boolean = false,
    undoEnabled: Boolean = false,
    onUndo: () -> Unit = {},
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
            if (undoVisible) {
                IconButton(onClick = onUndo, enabled = undoEnabled) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, tr("撤回上一步", "Undo last move"))
                }
            }
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

/** Counts only live play, excluding pause, setup and result overlays. */
@Composable
internal fun GamePlayTimeEffect(
    gameId: String,
    viewModel: GamesViewModel,
    active: Boolean,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, gameId, active) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (active) viewModel.beginPlayTime(gameId)
                Lifecycle.Event.ON_PAUSE -> viewModel.endPlayTime(gameId)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (active && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.beginPlayTime(gameId)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.endPlayTime(gameId)
        }
    }
    LaunchedEffect(gameId, active) {
        if (!active) return@LaunchedEffect
        while (isActive) {
            delay(30_000L)
            viewModel.checkpointPlayTime(gameId)
        }
    }
}

internal fun shouldCountGamePlay(
    engineReady: Boolean,
    paused: Boolean = false,
    finished: Boolean = false,
    setupVisible: Boolean = false,
): Boolean = engineReady && !paused && !finished && !setupVisible

@Composable
private fun GameOverDialog(
    score: Int,
    onRestart: () -> Unit,
    onExit: () -> Unit,
    onUndo: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onExit,
        title = { Text(tr("游戏结束", "Game over")) },
        text = { Text(tr("本局得分：$score", "Score: $score")) },
        confirmButton = {
            TextButton(onClick = onRestart) { Text(tr("再来一局", "Play again")) }
        },
        dismissButton = {
            Row {
                onUndo?.let { undo ->
                    TextButton(onClick = undo) { Text(tr("撤回", "Undo")) }
                }
                TextButton(onClick = onExit) { Text(tr("返回", "Back")) }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Swipe handling
// ---------------------------------------------------------------------------------------------

internal enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Observes one drag and reports a single dominant-axis swipe when it ends.
 *
 * The final pointer pass is intentional. The 2048 page can be vertically scrollable on a short
 * screen, so its scroll container may consume the vertical position change before an outer drag
 * detector sees it. Absolute pointer positions remain available on the final pass and let all
 * four directions work without preventing the page itself from scrolling.
 */
private fun Modifier.swipeInput(enabled: Boolean, onSwipe: (SwipeDirection) -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val threshold = 42.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            val start = down.position
            var end = start
            var pressed = true
            while (pressed) {
                val change = awaitPointerEvent(PointerEventPass.Final)
                    .changes
                    .firstOrNull { it.id == down.id }
                    ?: break
                end = change.position
                pressed = change.pressed
            }
            swipeDirectionForDrag(
                dragX = end.x - start.x,
                dragY = end.y - start.y,
                threshold = threshold,
            )?.let(onSwipe)
        }
    }

internal fun swipeDirectionForDrag(
    dragX: Float,
    dragY: Float,
    threshold: Float,
): SwipeDirection? = when {
    abs(dragX) < threshold && abs(dragY) < threshold -> null
    abs(dragX) >= abs(dragY) ->
        if (dragX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
    else ->
        if (dragY > 0) SwipeDirection.DOWN else SwipeDirection.UP
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

private data class Game2048Palette(
    val pageBackground: Color,
    val heading: Color,
    val board: Color,
    val emptyTile: Color,
    val button: Color,
    val scoreLabel: Color,
    val lightTileText: Color,
    val darkTileText: Color,
)

private val GAME_2048_DAY_PALETTE = Game2048Palette(
    pageBackground = Color(0xFFFAF8EF),
    heading = Color(0xFF776E65),
    board = Color(0xFFBBADA0),
    emptyTile = Color(0x59EEE4DA),
    button = Color(0xFF8F7A66),
    scoreLabel = Color(0xFFEEE4DA),
    lightTileText = Color(0xFFF9F6F2),
    darkTileText = Color(0xFF776E65),
)

private val GAME_2048_NIGHT_PALETTE = Game2048Palette(
    pageBackground = Color(0xFF1B1917),
    heading = Color(0xFFF3EDE6),
    board = Color(0xFF4E453E),
    emptyTile = Color(0x334E453E),
    button = Color(0xFFB58B67),
    scoreLabel = Color(0xFFF3EDE6),
    lightTileText = Color(0xFFF9F6F2),
    darkTileText = Color(0xFF5B5047),
)

@Composable
private fun Game2048Page(
    viewModel: GamesViewModel,
    gameId: String,
    boardSize: Int,
    resume: Boolean,
    onExit: () -> Unit,
) {
    var engine by rememberSaveable(
        gameId,
        stateSaver = Saver(
            save = { game ->
                game?.toJson()
                    ?.takeIf { it.length <= MAX_SAVED_INSTANCE_GAME_CHARS }
                    .orEmpty()
            },
            restore = { encoded ->
                encoded.takeIf(String::isNotBlank)?.let {
                    Game2048.fromJson(it, expectedSize = boardSize)
                }
            },
        ),
    ) { mutableStateOf<Game2048?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var scoreRecorded by rememberSaveable { mutableStateOf(false) }
    var transition by remember { mutableStateOf<Game2048.MoveResult?>(null) }
    var transitionSequence by remember { mutableIntStateOf(0) }
    val themeDarkMode = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var darkModeOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val darkMode = darkModeOverride ?: themeDarkMode
    var animationsEnabled by rememberSaveable { mutableStateOf(true) }
    val animationSpeed by viewModel.animationSpeed.collectAsStateWithLifecycle()
    val animationDurationMillis = animationSpeed.durationMillis
    val palette = if (darkMode) GAME_2048_NIGHT_PALETTE else GAME_2048_DAY_PALETTE

    LaunchedEffect(Unit) {
        if (engine == null) {
            val restored = if (resume) {
                viewModel.loadSave(gameId)?.let {
                    Game2048.fromJson(it, expectedSize = boardSize)
                }
            } else {
                null
            }
            engine = restored ?: Game2048(boardSize)
        }
    }

    val board = remember(engine, frame) { engine?.board ?: List(boardSize * boardSize) { 0 } }
    val score = remember(engine, frame) { engine?.score ?: 0 }
    val gameOver = remember(engine, frame) { engine?.isGameOver == true }
    val canUndo = remember(engine, frame) { engine?.canUndo == true }

    GamePlayTimeEffect(
        gameId,
        viewModel,
        active = shouldCountGamePlay(engineReady = engine != null, finished = gameOver),
    )

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
        transition = null
        transitionSequence++
        val current = engine ?: return
        if (current.isGameOver) {
            viewModel.recordScore(gameId, current.score)
            return
        }
        viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    fun undoLastMove() {
        val current = engine ?: return
        if (!current.undo()) return
        transition = null
        transitionSequence++
        frame++
        scoreRecorded = false
        viewModel.saveProgress(gameId, current.toJson(), current.score)
    }

    fun startNewGame() {
        engine = Game2048(boardSize)
        transition = null
        transitionSequence++
        frame++
        scoreRecorded = false
        viewModel.clearSave(gameId)
    }

    fun attemptMove(direction: Game2048.Direction) {
        val current = engine ?: return
        if (current.isGameOver) return
        val result = current.moveWithResult(direction)
        // A direction the active game accepts is an operation even when no tile can move.
        viewModel.record2048MoveAttempt(gameId, result?.statisticsDelta)
        if (result != null) {
            // State and undo history commit before the visual transition.
            transition = if (animationsEnabled) result else null
            transitionSequence++
            frame++
        }
    }

    GameAutoPauseEffect(onPauseAndSave = ::pauseAndSave)
    BackHandler {
        saveIfRunning()
        onExit()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.pageBackground)
            .swipeInput(engine != null && !gameOver) { swipe ->
                attemptMove(swipe.to2048Direction())
            },
        contentAlignment = Alignment.Center,
    ) {
        val contentWidth = minOf(600.dp, (maxWidth - 20.dp).coerceAtLeast(280.dp))
        val largeLayout = contentWidth >= 480.dp
        Column(
            Modifier
                .width(contentWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        saveIfRunning()
                        onExit()
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        tint = palette.heading,
                    )
                    Text(tr("返回", "Back"), color = palette.heading)
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(
                    onClick = { darkModeOverride = !darkMode },
                ) {
                    Icon(
                        imageVector = if (darkMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                        contentDescription = if (darkMode) {
                            tr("切换白天模式", "Switch to day mode")
                        } else {
                            tr("切换黑夜模式", "Switch to night mode")
                        },
                    )
                }
                TextButton(
                    onClick = { viewModel.setAnimationSpeed(animationSpeed.next()) },
                ) {
                    Text(
                        when (animationSpeed) {
                            Game2048AnimationSpeed.SLOW -> tr("慢速", "Slow")
                            Game2048AnimationSpeed.NORMAL -> tr("标准", "Normal")
                            Game2048AnimationSpeed.FAST -> tr("快速", "Fast")
                        },
                        color = palette.heading,
                    )
                }
                FilledTonalIconButton(
                    onClick = { animationsEnabled = !animationsEnabled },
                ) {
                    Icon(
                        imageVector = if (animationsEnabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (animationsEnabled) {
                            tr("关闭动画", "Disable animations")
                        } else {
                            tr("开启动画", "Enable animations")
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "2048",
                    modifier = Modifier.weight(1f),
                    color = palette.heading,
                    fontSize = if (largeLayout) 72.sp else 27.sp,
                    fontWeight = FontWeight.Bold,
                )
                Site2048ScoreBox(
                    label = tr("分数", "Score"),
                    value = score,
                    addition = transition?.scoreGained ?: 0,
                    transitionSequence = transitionSequence,
                    large = largeLayout,
                    animate = animationsEnabled,
                    animationDurationMillis = animationDurationMillis,
                    palette = palette,
                )
            }
            Spacer(Modifier.height(if (largeLayout) 26.dp else 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr(
                        "合并数字，得到 2048 方块！",
                        "Join the numbers and get to the 2048 tile!",
                    ),
                    modifier = Modifier.weight(1f),
                    color = palette.heading,
                    fontSize = if (largeLayout) 18.sp else 13.sp,
                    lineHeight = if (largeLayout) 24.sp else 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Site2048Button(
                    text = tr("新游戏", "New Game"),
                    large = largeLayout,
                    palette = palette,
                    onClick = ::startNewGame,
                )
            }
            Spacer(Modifier.height(if (largeLayout) 32.dp else 12.dp))
            Box(
                modifier = Modifier
                    .size(contentWidth)
                    .align(Alignment.CenterHorizontally),
            ) {
                Board2048(
                    board = board,
                    boardSize = boardSize,
                    enabled = engine != null && !gameOver,
                    transition = transition,
                    transitionSequence = transitionSequence,
                    animate = animationsEnabled,
                    animationDurationMillis = animationDurationMillis,
                    palette = palette,
                    onMove = ::attemptMove,
                    modifier = Modifier.fillMaxSize(),
                )
                if (gameOver) {
                    Site2048GameOverOverlay(
                        canUndo = canUndo,
                        large = largeLayout,
                        palette = palette,
                        onUndo = ::undoLastMove,
                        onRestart = ::startNewGame,
                    )
                }
            }
        }
        FilledTonalIconButton(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            onClick = ::undoLastMove,
            enabled = canUndo,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Undo, tr("无限撤回", "Undo"))
        }
    }
}

@Composable
private fun Site2048Button(
    text: String,
    enabled: Boolean = true,
    large: Boolean = false,
    palette: Game2048Palette,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(3.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.button,
            contentColor = Color.White,
            disabledContainerColor = palette.button.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
        contentPadding = if (large) {
            PaddingValues(horizontal = 20.dp, vertical = 9.dp)
        } else {
            PaddingValues(horizontal = 12.dp, vertical = 7.dp)
        },
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = if (large) 18.sp else 12.sp)
    }
}

@Composable
private fun Site2048ScoreBox(
    label: String,
    value: Int,
    addition: Int = 0,
    transitionSequence: Int = 0,
    large: Boolean = false,
    animate: Boolean = true,
    animationDurationMillis: Int,
    palette: Game2048Palette,
) {
    val additionProgress = remember(transitionSequence, addition) {
        Animatable(if (addition > 0 && animate && ValueAnimator.areAnimatorsEnabled()) 0f else 1f)
    }
    LaunchedEffect(additionProgress, transitionSequence, addition, animate) {
        if (addition > 0 && animate && ValueAnimator.areAnimatorsEnabled()) {
            additionProgress.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = animationDurationMillis * 2,
                    easing = CubicBezierEasing(0.42f, 0f, 1f, 1f),
                ),
            )
        } else {
            additionProgress.snapTo(1f)
        }
    }
    Box {
        Column(
            modifier = Modifier
                .widthIn(min = if (large) 92.dp else 55.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.board)
                .padding(
                    horizontal = if (large) 15.dp else 8.dp,
                    vertical = if (large) 8.dp else 5.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label.uppercase(),
                color = palette.scoreLabel,
                fontSize = if (large) 13.sp else 10.sp,
                lineHeight = if (large) 14.sp else 11.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                value.toString(),
                color = Color.White,
                fontSize = if (large) 25.sp else 17.sp,
                lineHeight = if (large) 27.sp else 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (addition > 0 && additionProgress.value < 1f) {
            Text(
                text = "+$addition",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (-40.dp.toPx() * additionProgress.value).roundToInt(),
                        )
                    }
                    .graphicsLayer { alpha = 1f - additionProgress.value },
                color = palette.heading,
                fontSize = if (large) 25.sp else 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Site2048GameOverOverlay(
    canUndo: Boolean,
    large: Boolean,
    palette: Game2048Palette,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.pageBackground.copy(alpha = 0.82f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tr("游戏结束！", "Game over!"),
                color = palette.heading,
                fontSize = if (large) 55.sp else 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canUndo) {
                    Site2048Button(
                        tr("撤回", "Undo"),
                        large = large,
                        palette = palette,
                        onClick = onUndo,
                    )
                }
                Site2048Button(
                    tr("再试一次", "Try again"),
                    large = large,
                    palette = palette,
                    onClick = onRestart,
                )
            }
        }
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
    boardSize: Int,
    enabled: Boolean,
    transition: Game2048.MoveResult?,
    transitionSequence: Int,
    animate: Boolean,
    animationDurationMillis: Int,
    palette: Game2048Palette,
    onMove: (Game2048.Direction) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    durationMillis = animationDurationMillis,
                    easing = LinearEasing,
                ),
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(palette.board),
    ) {
        val cellGap = when (boardSize) {
            4 -> 10.dp
            5 -> 8.dp
            else -> 7.dp
        }
        val boardPadding = cellGap
        val tileSize = (maxWidth - boardPadding * 2 - cellGap * (boardSize - 1)) /
            boardSize
        val density = LocalDensity.current
        val paddingPx = with(density) { boardPadding.toPx() }
        val stepPx = with(density) { (tileSize + cellGap).toPx() }

        fun cellOffset(index: Int): IntOffset {
            val column = index % boardSize
            val row = index / boardSize
            return IntOffset(
                x = (paddingPx + column * stepPx).roundToInt(),
                y = (paddingPx + row * stepPx).roundToInt(),
            )
        }

        repeat(boardSize * boardSize) { index ->
            Box(
                Modifier
                    .offset { cellOffset(index) }
                    .size(tileSize)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.emptyTile),
            )
        }

        val currentTransition = transition
        val animationProgress = progress.value
        if (currentTransition != null && animationProgress < GAME_2048_SLIDE_END) {
            val slideProgress = GAME_2048_EASE_IN_OUT.transform(
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
                        boardSize = boardSize,
                        palette = palette,
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
                val tileAlpha = if (
                    currentTransition != null &&
                    index == currentTransition.spawn.index
                ) {
                    popProgress.coerceIn(0f, 1f)
                } else {
                    1f
                }
                key(transitionSequence, index, value) {
                    Tile2048(
                        value = value,
                        boardSize = boardSize,
                        palette = palette,
                        modifier = Modifier
                            .offset { cellOffset(index) }
                            .size(tileSize)
                            .graphicsLayer {
                                scaleX = tileScale
                                scaleY = tileScale
                                alpha = tileAlpha
                            },
                    )
                }
            }
        }
    }
}

/** 2048.org's `appear` keyframe: scale 0 to 1 over 200 ms after the slide. */
private fun spawnTileScale(progress: Float): Float {
    return GAME_2048_EASE.transform(progress.coerceIn(0f, 1f))
}

/** 2048.org's `pop` keyframe: scale 0 → 1.2 → 1 over 200 ms. */
private fun mergedTileScale(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return when {
        value < 0.5f -> 1.2f * GAME_2048_EASE.transform(value / 0.5f)
        else -> 1.2f - 0.2f * GAME_2048_EASE.transform((value - 0.5f) / 0.5f)
    }
}

@Composable
private fun Tile2048(
    value: Int,
    boardSize: Int,
    palette: Game2048Palette,
    modifier: Modifier = Modifier,
) {
    val background = when (value) {
        2 -> Color(0xFFEEE4DA)
        4 -> Color(0xFFEDE0C8)
        8 -> Color(0xFFF2B179)
        16 -> Color(0xFFF59563)
        32 -> Color(0xFFF67C5F)
        64 -> Color(0xFFF65E3B)
        128 -> Color(0xFFEDCF72)
        256 -> Color(0xFFEDCC61)
        512 -> Color(0xFFEDC850)
        1024 -> Color(0xFFEDC53F)
        2048 -> Color(0xFFEDC22E)
        else -> Color(0xFF3C3A32)
    }
    val textColor = if (value <= 4) palette.darkTileText else palette.lightTileText
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(3.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (value > 0) {
            Text(
                value.toString(),
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = tileFontSize(value, boardSize, maxWidth.value).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

internal fun tileFontSize(value: Int, boardSize: Int, tileWidthDp: Float): Int {
    val boardScale = when (boardSize) {
        4 -> 1f
        5 -> 0.98f
        else -> 0.96f
    }
    val base = (tileWidthDp * 0.47f * boardScale)
        .roundToInt()
        .coerceIn(16, 55)
    val digitCount = value.toString().length.coerceAtLeast(1)
    val original2048Scale = when (digitCount) {
        1, 2 -> 1f
        3 -> 0.82f
        4 -> 0.64f
        else -> 0.54f
    }
    // Keep roughly 82% of the cell width available. Decimal digits in the game's bold typeface
    // average about 0.6 em, so this also scales values beyond 65536 instead of clipping them.
    val widthBound = (tileWidthDp * 0.82f / (digitCount * 0.6f)).toInt()
    return minOf(
        (base * original2048Scale).roundToInt(),
        widthBound,
    ).coerceAtLeast(9)
}

// ---------------------------------------------------------------------------------------------
// Snake
// ---------------------------------------------------------------------------------------------

@Composable
private fun SnakePage(viewModel: GamesViewModel, resume: Boolean, onExit: () -> Unit) {
    val gameId = GamesViewModel.GAME_SNAKE
    var engine by rememberSaveable(
        stateSaver = Saver(
            save = { game -> game?.toJson().orEmpty() },
            restore = { encoded ->
                encoded.takeIf(String::isNotBlank)?.let { SnakeGame.fromJson(it) }
            },
        ),
    ) { mutableStateOf<SnakeGame?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var scoreRecorded by rememberSaveable { mutableStateOf(false) }
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (engine == null) {
            val restored = if (resume) viewModel.loadSave(gameId)?.let { SnakeGame.fromJson(it) } else null
            engine = restored ?: SnakeGame()
        }
    }

    val score = remember(engine, frame) { engine?.score ?: 0 }
    val gameOver = remember(engine, frame) { engine?.isGameOver == true }

    GamePlayTimeEffect(
        gameId,
        viewModel,
        active = shouldCountGamePlay(
            engineReady = engine != null,
            paused = paused,
            finished = gameOver,
        ),
    )

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
            viewModel.recordSnakeStatistics(current.tickWithResult().statisticsDelta)
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
    var engine by rememberSaveable(
        stateSaver = Saver(
            save = { game -> game?.toJson().orEmpty() },
            restore = { encoded ->
                encoded.takeIf(String::isNotBlank)?.let { TetrisGame.fromJson(it) }
            },
        ),
    ) { mutableStateOf<TetrisGame?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var scoreRecorded by rememberSaveable { mutableStateOf(false) }
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

    GamePlayTimeEffect(
        gameId,
        viewModel,
        active = shouldCountGamePlay(
            engineReady = engine != null,
            paused = paused,
            finished = gameOver,
        ),
    )

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
            viewModel.recordTetrisStatistics(current.tickWithResult().statisticsDelta)
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

    fun act(action: (TetrisGame) -> TetrisGame.StepResult?) {
        val current = engine ?: return
        if (paused || current.isGameOver) return
        action(current)?.let { viewModel.recordTetrisStatistics(it.statisticsDelta) }
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
            FilledTonalIconButton(onClick = { act { it.moveLeft(); null } }, enabled = controlsEnabled) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, tr("左移", "Left"))
            }
            FilledTonalIconButton(onClick = { act { it.rotate(); null } }, enabled = controlsEnabled) {
                Icon(Icons.Outlined.RotateRight, tr("旋转", "Rotate"))
            }
            FilledTonalIconButton(onClick = { act(TetrisGame::softDropWithResult) }, enabled = controlsEnabled) {
                Icon(Icons.Outlined.ArrowDownward, tr("加速下落", "Soft drop"))
            }
            FilledTonalIconButton(onClick = { act(TetrisGame::hardDropWithResult) }, enabled = controlsEnabled) {
                Icon(Icons.Outlined.VerticalAlignBottom, tr("硬降", "Hard drop"))
            }
            FilledTonalIconButton(onClick = { act { it.moveRight(); null } }, enabled = controlsEnabled) {
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
private const val GAME_2048_SLIDE_END = 1f / 3f
private val GAME_2048_EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val GAME_2048_EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private const val TETRIS_BASE_TICK_MILLIS = 600L
private const val TETRIS_LEVEL_STEP_MILLIS = 40L
private const val TETRIS_MIN_TICK_MILLIS = 120L
private const val MAX_SAVED_INSTANCE_GAME_CHARS = 512 * 1024

private val Game2048AnimationSpeed.durationMillis: Int
    get() = when (this) {
        Game2048AnimationSpeed.SLOW -> 500
        Game2048AnimationSpeed.NORMAL -> 300
        Game2048AnimationSpeed.FAST -> 150
    }

private fun Game2048AnimationSpeed.next(): Game2048AnimationSpeed = when (this) {
    Game2048AnimationSpeed.SLOW -> Game2048AnimationSpeed.NORMAL
    Game2048AnimationSpeed.NORMAL -> Game2048AnimationSpeed.FAST
    Game2048AnimationSpeed.FAST -> Game2048AnimationSpeed.SLOW
}

@Composable
private fun formatGameDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0L) / 60_000L
    val hours = minutes / 60L
    val remainder = minutes % 60L
    return when {
        hours > 0 -> tr("${hours}小时${remainder}分钟", "${hours}h ${remainder}m")
        minutes > 0 -> tr("${minutes}分钟", "${minutes}m")
        else -> tr("不足 1 分钟", "< 1m")
    }
}
