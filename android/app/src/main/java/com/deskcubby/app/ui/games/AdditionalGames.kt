@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.games

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.games.MinesweeperGame
import com.deskcubby.app.games.SpiderSolitaireGame
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr

@Composable
internal fun MinesweeperPage(
    viewModel: GamesViewModel,
    resume: Boolean,
    onExit: () -> Unit,
) {
    val gameId = GamesViewModel.GAME_MINESWEEPER
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()
    var engine by rememberSaveable(
        stateSaver = Saver(
            save = { game -> game?.toJson().orEmpty() },
            restore = { encoded -> encoded.takeIf(String::isNotBlank)?.let(MinesweeperGame::fromJson) },
        ),
    ) { mutableStateOf<MinesweeperGame?>(null) }
    var frame by remember { mutableIntStateOf(0) }
    var showConfiguration by rememberSaveable { mutableStateOf(!resume) }
    var resultRecorded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (engine == null && resume) {
            engine = viewModel.loadSave(gameId)?.let(MinesweeperGame::fromJson)
            if (engine == null) showConfiguration = true
        }
    }

    fun saveOrFinish() {
        val current = engine ?: return
        if (current.isGameOver || current.isWon) {
            val score = if (current.isWon) current.mineCount * 100 + current.revealedSafeCount else 0
            viewModel.recordScore(gameId, score)
        } else {
            viewModel.saveProgress(gameId, current.toJson(), 0)
        }
    }

    val current = remember(engine, frame) { engine }
    val finished = current?.isGameOver == true || current?.isWon == true
    GamePlayTimeEffect(
        gameId = gameId,
        viewModel = viewModel,
        active = shouldCountGamePlay(
            engineReady = current != null,
            finished = finished,
            setupVisible = showConfiguration,
        ),
    )
    LaunchedEffect(finished) {
        if (finished && !resultRecorded) {
            resultRecorded = true
            saveOrFinish()
        }
    }
    AdditionalGameAutoSaveEffect(::saveOrFinish)
    BackHandler { saveOrFinish(); onExit() }

    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { saveOrFinish(); onExit() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
            }
            Text(tr("扫雷", "Minesweeper"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (current != null) {
                Text(
                    tr("剩余 ${current.remainingMines}", "${current.remainingMines} left"),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { showConfiguration = true }) {
                    Icon(Icons.Outlined.Refresh, tr("新游戏", "New game"))
                }
            }
        }
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Button(onClick = { showConfiguration = true }) { Text(tr("设置棋盘", "Configure board")) }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        repeat(current.height) { y ->
                            Row {
                                repeat(current.width) { x ->
                                    val cell = current.cell(x, y)
                                    MinesweeperCell(
                                        cell = cell,
                                        onReveal = {
                                            if (current.reveal(x, y)) {
                                                frame++
                                                if (!current.isGameOver && !current.isWon) {
                                                    viewModel.saveProgress(gameId, current.toJson(), 0)
                                                }
                                            }
                                        },
                                        onFlag = {
                                            if (current.toggleFlag(x, y)) {
                                                frame++
                                                viewModel.saveProgress(gameId, current.toJson(), 0)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Text(
                tr("点按翻开，长按插旗", "Tap to reveal; long-press to flag"),
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showConfiguration) {
        MinesweeperConfigurationDialog(
            initialWidth = current?.width ?: 9,
            initialHeight = current?.height ?: 9,
            initialMines = current?.mineCount ?: 10,
            onDismiss = {
                showConfiguration = false
                if (engine == null) onExit()
            },
            onStart = { width, height, mines ->
                engine = MinesweeperGame(width, height, mines)
                frame++
                resultRecorded = false
                showConfiguration = false
                viewModel.clearSave(gameId)
            },
        )
    }
    if (finished && !showConfiguration) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (current.isWon) tr("扫雷成功", "Board cleared") else tr("踩到雷了", "Mine triggered")) },
            text = {
                Text(
                    if (current.isWon) tr("最高分：${maxOf(meta.highScore, current.mineCount * 100 + current.revealedSafeCount)}", "Best: ${maxOf(meta.highScore, current.mineCount * 100 + current.revealedSafeCount)}")
                    else tr("可以重新配置棋盘再试一次。", "Configure a new board and try again."),
                )
            },
            confirmButton = { TextButton(onClick = { showConfiguration = true }) { Text(tr("新游戏", "New game")) } },
            dismissButton = { TextButton(onClick = onExit) { Text(tr("返回", "Back")) } },
        )
    }
}

@Composable
private fun MinesweeperCell(
    cell: MinesweeperGame.Cell,
    onReveal: () -> Unit,
    onFlag: () -> Unit,
) {
    val background = when {
        cell.mine -> MaterialTheme.colorScheme.errorContainer
        cell.revealed -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(
        modifier = Modifier
            .padding(1.dp)
            .size(34.dp)
            .combinedClickable(onClick = onReveal, onLongClick = onFlag),
        shape = RoundedCornerShape(4.dp),
        color = background,
        tonalElevation = if (cell.revealed) 0.dp else 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                cell.flagged -> Icon(Icons.Outlined.Flag, tr("旗帜", "Flag"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                cell.mine -> Text("●", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                cell.revealed && cell.adjacentMines > 0 -> Text(
                    cell.adjacentMines.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun MinesweeperConfigurationDialog(
    initialWidth: Int,
    initialHeight: Int,
    initialMines: Int,
    onDismiss: () -> Unit,
    onStart: (Int, Int, Int) -> Unit,
) {
    var width by rememberSaveable { mutableIntStateOf(initialWidth) }
    var height by rememberSaveable { mutableIntStateOf(initialHeight) }
    var mines by rememberSaveable { mutableIntStateOf(initialMines.coerceIn(1, width * height - 1)) }

    fun setPreset(w: Int, h: Int, m: Int) {
        width = w
        height = h
        mines = m
    }
    val maxMines = (width * height - 1).coerceAtLeast(1)
    if (mines > maxMines) mines = maxMines

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("自定义扫雷", "Custom Minesweeper")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { setPreset(9, 9, 10) }) { Text(tr("初级", "Easy")) }
                    OutlinedButton(onClick = { setPreset(16, 16, 40) }) { Text(tr("中级", "Medium")) }
                    OutlinedButton(onClick = { setPreset(30, 16, 99) }) { Text(tr("高级", "Expert")) }
                }
                ValueSlider(tr("列数", "Columns"), width, MinesweeperGame.MIN_WIDTH..MinesweeperGame.MAX_WIDTH) { width = it }
                ValueSlider(tr("行数", "Rows"), height, MinesweeperGame.MIN_HEIGHT..MinesweeperGame.MAX_HEIGHT) { height = it }
                ValueSlider(tr("雷数", "Mines"), mines, 1..maxMines) { mines = it }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(width, height, mines) }) { Text(tr("开始", "Start")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun ValueSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value.toString(), color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
internal fun SpiderSolitairePage(
    viewModel: GamesViewModel,
    resume: Boolean,
    onExit: () -> Unit,
) {
    val gameId = GamesViewModel.GAME_SPIDER
    val activity = LocalContext.current.findActivityForGame()
    LandscapeGameEffect(activity)
    val meta by viewModel.meta(gameId).collectAsStateWithLifecycle()
    var engine by rememberSaveable(
        stateSaver = Saver(
            save = { game -> game?.toJson().orEmpty() },
            restore = { encoded -> encoded.takeIf(String::isNotBlank)?.let(SpiderSolitaireGame::fromJson) },
        ),
    ) { mutableStateOf(if (resume) null else SpiderSolitaireGame()) }
    var frame by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var scoreRecorded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (engine == null) {
            engine = viewModel.loadSave(gameId)?.let(SpiderSolitaireGame::fromJson)
                ?: SpiderSolitaireGame()
        }
    }
    val current = engine

    GamePlayTimeEffect(
        gameId = gameId,
        viewModel = viewModel,
        active = shouldCountGamePlay(
            engineReady = current != null,
            finished = current?.isWon == true,
        ),
    )

    fun saveOrFinish() {
        val game = engine ?: return
        if (game.isWon) viewModel.recordScore(gameId, game.score)
        else viewModel.saveProgress(gameId, game.toJson(), 0)
    }
    LaunchedEffect(current?.isWon) {
        if (current?.isWon == true && !scoreRecorded) {
            scoreRecorded = true
            viewModel.recordScore(gameId, current.score)
        }
    }
    AdditionalGameAutoSaveEffect(::saveOrFinish)
    BackHandler { saveOrFinish(); onExit() }

    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { saveOrFinish(); onExit() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
            }
            Text(tr("蜘蛛纸牌 · 一花色", "Spider · One suit"), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text(tr("分数 ${current?.score ?: 0}", "Score ${current?.score ?: 0}"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(10.dp))
            Text(tr("完成 ${current?.completedRuns ?: 0}/8", "Runs ${current?.completedRuns ?: 0}/8"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            IconButton(
                enabled = current?.canUndo == true,
                onClick = {
                    if (current?.undo() == true) {
                        selected = null
                        frame++
                        saveOrFinish()
                    }
                },
            ) { Icon(Icons.AutoMirrored.Outlined.Undo, tr("撤回", "Undo")) }
            FilledTonalButton(
                enabled = current?.canDealStock == true,
                onClick = {
                    if (current?.dealStock() == true) {
                        selected = null
                        frame++
                        saveOrFinish()
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("发牌 ${current?.stockDealsRemaining ?: 0}", "Deal ${current?.stockDealsRemaining ?: 0}"))
            }
            IconButton(onClick = {
                engine = SpiderSolitaireGame()
                selected = null
                scoreRecorded = false
                frame++
                viewModel.clearSave(gameId)
            }) { Icon(Icons.Outlined.Refresh, tr("新游戏", "New game")) }
        }

        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                repeat(SpiderSolitaireGame.COLUMN_COUNT) { columnIndex ->
                    val cards = remember(current, frame) { current.column(columnIndex) }
                    SpiderColumn(
                        cards = cards,
                        selectedIndex = selected?.takeIf { it.first == columnIndex }?.second,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onCardClick = { cardIndex ->
                            val source = selected
                            if (source == null) {
                                selected = if (current.canSelect(columnIndex, cardIndex)) columnIndex to cardIndex else null
                            } else if (current.move(source.first, source.second, columnIndex)) {
                                selected = null
                                frame++
                                saveOrFinish()
                            } else {
                                selected = if (current.canSelect(columnIndex, cardIndex)) columnIndex to cardIndex else null
                            }
                        },
                        onEmptyClick = {
                            val source = selected
                            if (source != null && current.move(source.first, source.second, columnIndex)) {
                                selected = null
                                frame++
                                saveOrFinish()
                            }
                        },
                    )
                }
            }
        }
    }

    if (current?.isWon == true) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("完成蜘蛛纸牌", "Spider completed")) },
            text = { Text(tr("本局 ${current.score} 分，最高 ${maxOf(meta.highScore, current.score)} 分。", "Score ${current.score}; best ${maxOf(meta.highScore, current.score)}.")) },
            confirmButton = {
                TextButton(onClick = {
                    engine = SpiderSolitaireGame()
                    selected = null
                    scoreRecorded = false
                    frame++
                }) { Text(tr("再来一局", "Play again")) }
            },
            dismissButton = { TextButton(onClick = onExit) { Text(tr("返回", "Back")) } },
        )
    }
}

@Composable
private fun SpiderColumn(
    cards: List<SpiderSolitaireGame.Card>,
    selectedIndex: Int?,
    modifier: Modifier,
    onCardClick: (Int) -> Unit,
    onEmptyClick: () -> Unit,
) {
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .combinedClickable(onClick = onEmptyClick, onLongClick = {}),
    ) {
        val cardHeight = 54.dp
        val availableStep = if (cards.size <= 1) {
            22.dp
        } else {
            ((maxHeight - cardHeight - 6.dp) / (cards.size - 1)).coerceIn(5.dp, 22.dp)
        }
        var offset = 3.dp
        cards.forEachIndexed { index, card ->
            val cardOffset = offset
            val step = if (card.faceUp) availableStep else minOf(12.dp, availableStep)
            SpiderCard(
                card = card,
                selected = selectedIndex != null && index >= selectedIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .offset(y = cardOffset)
                    .padding(horizontal = 1.dp),
                onClick = { onCardClick(index) },
            )
            offset += step
        }
    }
}

@Composable
private fun SpiderCard(
    card: SpiderSolitaireGame.Card,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val faceColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val faceContentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(5.dp),
        color = if (card.faceUp) faceColor else MaterialTheme.colorScheme.primary,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shadowElevation = 1.dp,
    ) {
        if (card.faceUp) {
            Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
                Text(
                    text = spiderRank(card.rank),
                    color = faceContentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                )
                Text("♠", color = faceContentColor, fontSize = 11.sp, lineHeight = 11.sp)
            }
        } else {
            Box(Modifier.fillMaxSize().padding(4.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(3.dp)))
        }
    }
}

private fun spiderRank(rank: Int): String = when (rank) {
    1 -> "A"
    11 -> "J"
    12 -> "Q"
    13 -> "K"
    else -> rank.toString()
}

@Composable
private fun AdditionalGameAutoSaveEffect(onSave: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestSave by rememberUpdatedState(onSave)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) latestSave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            latestSave()
        }
    }
}

@Composable
private fun LandscapeGameEffect(activity: Activity?) {
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            // Keep the lock while Android recreates the Activity for the requested landscape
            // configuration. Clearing it from the retiring Activity can immediately rotate back
            // to portrait and repeatedly destroy the freshly restored game.
            if (
                activity != null &&
                shouldRestoreGameOrientation(
                    isFinishing = activity.isFinishing,
                    isChangingConfigurations = activity.isChangingConfigurations,
                )
            ) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

internal fun shouldRestoreGameOrientation(
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
): Boolean = !isFinishing && !isChangingConfigurations

private fun Context.findActivityForGame(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityForGame()
    else -> null
}
