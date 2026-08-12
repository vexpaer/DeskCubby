@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.statshub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.statistics.StatisticsChartType
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.ui.statistics.StatisticsChart
import com.deskcubby.app.ui.statistics.StatisticsMessagePanel
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun StatisticsHubScreen(
    padding: PaddingValues,
    viewModel: StatisticsHubViewModel,
    onOpenUsage: () -> Unit,
    onOpenHealth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatisticsHubScreen(
        padding = padding,
        state = state,
        onOpenUsage = onOpenUsage,
        onOpenHealth = onOpenHealth,
        modifier = modifier,
    )
}

@Composable
internal fun StatisticsHubScreen(
    padding: PaddingValues,
    state: StatisticsHubUiState,
    onOpenUsage: () -> Unit,
    onOpenHealth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pageName by rememberSaveable { mutableStateOf(StatisticsHubPage.OVERVIEW.name) }
    val page = runCatching { StatisticsHubPage.valueOf(pageName) }
        .getOrDefault(StatisticsHubPage.OVERVIEW)
    val goBack = { pageName = StatisticsHubPage.OVERVIEW.name }
    BackHandler(enabled = page != StatisticsHubPage.OVERVIEW, onBack = goBack)

    Scaffold(
        modifier = modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            StatisticsHubPage.OVERVIEW -> tr("统计", "Statistics")
                            StatisticsHubPage.DIARY -> tr("日记统计", "Diary statistics")
                            StatisticsHubPage.READING -> tr("阅读统计", "Reading statistics")
                            StatisticsHubPage.GAMES -> tr("小游戏战绩", "Game statistics")
                        },
                    )
                },
                navigationIcon = {
                    if (page != StatisticsHubPage.OVERVIEW) {
                        IconButton(onClick = goBack) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = tr("返回统计", "Back to statistics"),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when (page) {
            StatisticsHubPage.OVERVIEW -> StatisticsOverviewPage(
                state = state,
                innerPadding = innerPadding,
                onOpenDiary = { pageName = StatisticsHubPage.DIARY.name },
                onOpenUsage = onOpenUsage,
                onOpenHealth = onOpenHealth,
                onOpenReading = { pageName = StatisticsHubPage.READING.name },
                onOpenGames = { pageName = StatisticsHubPage.GAMES.name },
            )
            StatisticsHubPage.DIARY -> DiaryStatisticsPage(state.diary, innerPadding)
            StatisticsHubPage.READING -> ReadingStatisticsPage(state, innerPadding)
            StatisticsHubPage.GAMES -> GameStatisticsPage(state, innerPadding)
        }
    }
}

@Composable
private fun StatisticsOverviewPage(
    state: StatisticsHubUiState,
    innerPadding: PaddingValues,
    onOpenDiary: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenGames: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(280.dp),
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    tr("一处查看所有长期记录", "All of your long-term records in one place"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    tr(
                        "点按卡片进入详情。缺少授权或尚无记录时，会保留已有历史，不把未知数据当作 0。",
                        "Open a card for details. Missing access or records stay unknown instead of being counted as zero.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.initializing) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else {
            item {
                StatisticsHubCard(
                    icon = Icons.Outlined.Article,
                    title = tr("日记", "Diary"),
                    primaryValue = localizedWordCount(state.diary.totalWords),
                    secondaryValue = tr(
                        "${state.diary.entryCount} 篇 · 连续 ${state.diary.currentStreakDays} 天",
                        "${state.diary.entryCount} entries · ${state.diary.currentStreakDays}-day streak",
                    ),
                    chartValues = state.diary.monthlyWords.takeLast(8).map(StatisticsPoint::value),
                    chartDescription = tr("最近月份的日记字数", "Diary words in recent months"),
                    onClick = onOpenDiary,
                )
            }
            item {
                StatisticsHubCard(
                    icon = Icons.Outlined.AccessTime,
                    title = tr("手机使用时间", "Screen time"),
                    primaryValue = if (state.usage.lastSevenRecordedDays == 0) {
                        "—"
                    } else {
                        localizedDuration(state.usage.lastSevenTotal.roundToLong())
                    },
                    secondaryValue = if (!state.usage.enabled) {
                        tr("统计已关闭 · 历史仍保留", "Tracking off · history preserved")
                    } else {
                        tr(
                            "近 7 天已记录 ${state.usage.lastSevenRecordedDays}/7 天 · 今日 ${localizedOptionalDuration(state.usage.todayValue)}",
                            "Recorded ${state.usage.lastSevenRecordedDays}/7 recent days · today ${localizedOptionalDuration(state.usage.todayValue)}",
                        )
                    },
                    chartValues = state.usage.lastSevenPoints.map(StatisticsPoint::value),
                    chartDescription = recentDurationChartDescription(state.usage.lastSevenPoints),
                    onClick = onOpenUsage,
                )
            }
            item {
                val todaySteps = state.health.todayValue
                StatisticsHubCard(
                    icon = Icons.Outlined.MonitorHeart,
                    title = tr("健康", "Health"),
                    primaryValue = if (todaySteps == null) "—" else localizedStepCount(todaySteps),
                    secondaryValue = if (!state.health.enabled) {
                        tr("统计已关闭 · 历史仍保留", "Tracking off · history preserved")
                    } else {
                        tr(
                            "今日步数 · 近 7 天已记录 ${state.health.lastSevenRecordedDays}/7 天，共 ${localizedStepCount(state.health.lastSevenTotal)}",
                            "Steps today · recorded ${state.health.lastSevenRecordedDays}/7 recent days, ${localizedStepCount(state.health.lastSevenTotal)} total",
                        )
                    },
                    chartValues = state.health.lastSevenPoints.map(StatisticsPoint::value),
                    chartDescription = recentStepsChartDescription(state.health.lastSevenPoints),
                    onClick = onOpenHealth,
                )
            }
            item {
                StatisticsHubCard(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = tr("阅读时长", "Reading time"),
                    primaryValue = localizedDuration(state.totalReadingMillis),
                    secondaryValue = tr(
                        "${state.reading.size} 本书有阅读记录",
                        "${state.reading.size} books with reading time",
                    ),
                    chartValues = state.reading.take(8).map { it.totalMillis.toDouble() },
                    chartDescription = tr("阅读时长最高的书籍对比", "Books ranked by reading time"),
                    onClick = onOpenReading,
                )
            }
            item {
                StatisticsHubCard(
                    icon = Icons.Outlined.SportsEsports,
                    title = tr("小游戏战绩", "Game statistics"),
                    primaryValue = localizedDuration(state.totalGameMillis),
                    secondaryValue = tr(
                        "${state.games.count(GameStatisticsHubItem::hasAnyStatistics)} 款游戏已有记录",
                        "${state.games.count(GameStatisticsHubItem::hasAnyStatistics)} games with records",
                    ),
                    chartValues = state.games.map { it.totalPlayMillis.toDouble() },
                    chartDescription = tr("各小游戏累计游玩时长", "Total play time by game"),
                    onClick = onOpenGames,
                )
            }
        }
    }
}

@Composable
private fun StatisticsHubCard(
    icon: ImageVector,
    title: String,
    primaryValue: String,
    secondaryValue: String,
    chartValues: List<Double?>,
    chartDescription: String,
    onClick: () -> Unit,
) {
    val openLabel = tr("查看$title", "Open $title")
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 190.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = openLabel,
                onClick = onClick,
            ),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.padding(9.dp).size(20.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
            Text(
                primaryValue,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                secondaryValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MiniBarChart(
                values = chartValues,
                description = chartDescription,
                modifier = Modifier.fillMaxWidth().height(44.dp),
            )
        }
    }
}

@Composable
private fun MiniBarChart(
    values: List<Double?>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val maximum = values.mapNotNull { it?.coerceAtLeast(0.0) }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (values.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        } else {
            values.forEach { rawValue ->
                val fraction = rawValue
                    ?.coerceAtLeast(0.0)
                    ?.div(maximum)
                    ?.toFloat()
                    ?.coerceIn(0f, 1f)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(if (fraction == null) 0.05f else fraction.coerceAtLeast(0.08f))
                        .background(
                            if (fraction == null) MaterialTheme.colorScheme.outlineVariant
                            else MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                        ),
                )
            }
        }
    }
}

@Composable
private fun DiaryStatisticsPage(
    summary: DiaryStatisticsSummary,
    innerPadding: PaddingValues,
) {
    var selectedPoint by remember(summary.monthlyWords) { mutableStateOf<StatisticsPoint?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SummaryMetricPanel(
                metrics = listOf(
                    tr("日记总数", "Entries") to formatInteger(summary.entryCount.toLong()),
                    tr("总字数", "Total words") to formatInteger(summary.totalWords),
                    tr("当前连续", "Current streak") to tr("${summary.currentStreakDays} 天", "${summary.currentStreakDays} days"),
                    tr("最长连续", "Longest streak") to tr("${summary.longestStreakDays} 天", "${summary.longestStreakDays} days"),
                ),
            )
        }
        if (summary.monthlyWords.isEmpty()) {
            item {
                StatisticsMessagePanel(
                    title = tr("还没有日记统计", "No diary statistics yet"),
                    message = tr("完成日记目录扫描后，这里会按月显示字数。", "After the diary folder is scanned, monthly word counts appear here."),
                )
            }
        } else {
            item {
                SectionHeading(
                    tr("每月字数", "Words by month"),
                    tr("显示最近 24 个月；点按柱形查看具体月份。", "Shows the latest 24 months. Tap a bar for its month."),
                )
            }
            item {
                val points = summary.monthlyWords.takeLast(24)
                val wordSuffix = tr(" 字", " words")
                GlassPanel(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                    padding = PaddingValues(12.dp),
                ) {
                    StatisticsChart(
                        points = points,
                        chartType = StatisticsChartType.BARS,
                        valueDescription = { value ->
                            value?.let { "${formatInteger(it.roundToLong())}$wordSuffix" } ?: "—"
                        },
                        selectedPoint = selectedPoint?.takeIf(points::contains),
                        onPointSelected = { selectedPoint = it },
                    )
                }
            }
            item {
                val latest = summary.monthlyWords.last()
                Text(
                    tr(
                        "最近月份：${latest.date.format(MONTH_FORMATTER)} · ${localizedWordCount(latest.value?.roundToLong() ?: 0L)}",
                        "Latest month: ${latest.date.format(MONTH_FORMATTER)} · ${localizedWordCount(latest.value?.roundToLong() ?: 0L)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReadingStatisticsPage(
    state: StatisticsHubUiState,
    innerPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SummaryMetricPanel(
                metrics = listOf(
                    tr("累计阅读", "Total reading") to localizedDuration(state.totalReadingMillis),
                    tr("有记录书籍", "Books tracked") to formatInteger(state.reading.size.toLong()),
                ),
            )
        }
        if (state.reading.isEmpty()) {
            item {
                StatisticsMessagePanel(
                    title = tr("还没有阅读时长", "No reading time yet"),
                    message = tr("打开书籍并实际阅读后，前台阅读时长会显示在这里。", "Open a book and read it to build foreground reading time here."),
                )
            }
        } else {
            item {
                SectionHeading(
                    tr("书籍对比", "Book comparison"),
                    tr(
                        "按累计前台阅读时长排序。书籍移出书架后，已记录的书名和历史时长仍会保留。",
                        "Ranked by foreground reading time. Recorded titles and history remain after books leave the shelf.",
                    ),
                )
            }
            items(state.reading, key = EngagementStatisticsItem::id) { item ->
                RankedBarRow(
                    label = item.title ?: tr(
                        "旧版未记录书名",
                        "Title not recorded by earlier version",
                    ),
                    value = localizedDuration(item.totalMillis),
                    fraction = item.totalMillis.toDouble() /
                        state.reading.first().totalMillis.coerceAtLeast(1L).toDouble(),
                )
            }
        }
    }
}

@Composable
private fun GameStatisticsPage(
    state: StatisticsHubUiState,
    innerPadding: PaddingValues,
) {
    val gamesWithRecords = state.games.count(GameStatisticsHubItem::hasAnyStatistics)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SummaryMetricPanel(
                metrics = listOf(
                    tr("累计游玩", "Total play time") to localizedDuration(state.totalGameMillis),
                    tr("已有记录", "Games tracked") to tr("$gamesWithRecords 款", "$gamesWithRecords games"),
                ),
            )
        }
        item {
            SectionHeading(
                tr("时长对比", "Play-time comparison"),
                tr("仅累计小游戏实际位于前台且正在游玩的时间。", "Counts only time spent actively playing a game in the foreground."),
            )
        }
        val maximumDuration = state.games.maxOfOrNull(GameStatisticsHubItem::totalPlayMillis)
            ?.coerceAtLeast(1L)
            ?: 1L
        items(state.games, key = GameStatisticsHubItem::gameId) { game ->
            RankedBarRow(
                label = localizedGameTitle(game.gameId),
                value = localizedDuration(game.totalPlayMillis),
                fraction = game.totalPlayMillis.toDouble() / maximumDuration.toDouble(),
            )
        }
        item {
            SectionHeading(
                tr("特色战绩", "Game-specific records"),
                tr("战绩为终身累计；胜负只在明确结算时记录，普通离开页面不会算失败。", "Lifetime totals update only on explicit outcomes; simply leaving a game does not count as a loss."),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(state.games, key = { "metrics:${it.gameId}" }) { game ->
            GameMetricPanel(game)
        }
    }
}

@Composable
private fun GameMetricPanel(game: GameStatisticsHubItem) {
    val metricValues = localizedGameMetrics(game)
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        role = GAME_PANEL_ROLES[gameCatalogIndex(game.gameId) % GAME_PANEL_ROLES.size],
        padding = PaddingValues(16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SportsEsports,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    localizedGameTitle(game.gameId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            if (metricValues.isEmpty() && game.highScore <= 0L) {
                Text(
                    tr("完成一局后会显示这款游戏的特色战绩。", "Finish a game to see its game-specific records."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = 2,
                ) {
                    if (game.highScore > 0L) {
                        CompactMetric(
                            label = tr("最高分", "High score"),
                            value = formatInteger(game.highScore),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    metricValues.forEach { metric ->
                        CompactMetric(
                            label = metric.label,
                            value = metric.value,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricPanel(metrics: List<Pair<String, String>>) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(16.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            maxItemsInEachRow = 2,
        ) {
            metrics.forEach { (label, value) ->
                CompactMetric(label, value, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompactMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionHeading(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RankedBarRow(label: String, value: String, fraction: Double) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp,
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.toFloat().coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
    }
}

private data class LocalizedMetric(val label: String, val value: String)

@Composable
private fun localizedGameMetrics(game: GameStatisticsHubItem): List<LocalizedMetric> {
    val orderedKeys = GAME_METRIC_ORDER[game.gameId].orEmpty()
    if (!game.hasAnyStatistics) return emptyList()
    val metrics = orderedKeys.map { key ->
        LocalizedMetric(gameMetricLabel(key), formatInteger(game.metrics[key] ?: 0L))
    }.toMutableList()
    val wins = game.metrics["wins"] ?: 0L
    val losses = game.metrics["losses"] ?: 0L
    val finished = if (Long.MAX_VALUE - wins < losses) Long.MAX_VALUE else wins + losses
    if (game.gameId in GAMES_WITH_WIN_RATE && finished > 0L) {
        val percentage = wins.toDouble() / finished.toDouble() * 100.0
        metrics += LocalizedMetric(
            tr("胜率", "Win rate"),
            String.format(Locale.ROOT, "%.1f%%", percentage),
        )
    }
    return metrics
}

@Composable
private fun gameMetricLabel(key: String): String = when (key) {
    "moveAttempts" -> tr("总操作次数", "Total moves")
    "effectiveMoves" -> tr("有效移动", "Effective moves")
    "merges" -> tr("合并次数", "Tile merges")
    "highestTile" -> tr("最高方块", "Highest tile")
    "wins" -> tr("获胜次数", "Wins")
    "losses" -> tr("失败次数", "Losses")
    "foodEaten" -> tr("吃到食物", "Food eaten")
    "maxLength" -> tr("最长蛇身", "Maximum length")
    "piecesLocked" -> tr("落定方块", "Pieces locked")
    "linesCleared" -> tr("消除行数", "Lines cleared")
    "tetrises" -> tr("四消次数", "Tetrises")
    "minesCellsRevealed" -> tr("揭开格数", "Cells revealed")
    "minesSwept" -> tr("累计排雷", "Mines swept")
    "flagsPlaced" -> tr("插旗次数", "Flags placed")
    "spiderCardMoves" -> tr("移动次数", "Card moves")
    "spiderDeals" -> tr("发牌次数", "Deals")
    "spiderUndos" -> tr("撤回次数", "Undos")
    "goMovesPlayed" -> tr("落子次数", "Stones played")
    "goStonesCaptured" -> tr("提子总数", "Stones captured")
    "goPasses" -> tr("停着次数", "Passes")
    "goGamesCompleted" -> tr("完成棋局", "Games completed")
    else -> key
}

@Composable
private fun localizedGameTitle(gameId: String): String = when (gameId) {
    "2048" -> "2048 · 4×4"
    "2048_5" -> "2048 · 5×5"
    "2048_6" -> "2048 · 6×6"
    "snake" -> tr("贪吃蛇", "Snake")
    "tetris" -> tr("俄罗斯方块", "Tetris")
    "minesweeper" -> tr("扫雷", "Minesweeper")
    "spider" -> tr("蜘蛛纸牌", "Spider Solitaire")
    "go" -> tr("围棋", "Go")
    else -> tr("小游戏", "Mini game")
}

private fun gameCatalogIndex(gameId: String): Int =
    GAME_CATALOG_IDS.indexOf(gameId).takeIf { it >= 0 } ?: 0

@Composable
private fun localizedDuration(milliseconds: Long): String {
    val totalMinutes = (milliseconds.coerceAtLeast(0L) / 60_000L)
    val days = totalMinutes / (24L * 60L)
    val hours = totalMinutes / 60L % 24L
    val minutes = totalMinutes % 60L
    return when {
        days > 0L -> tr("${days}天 ${hours}小时", "${days}d ${hours}h")
        hours > 0L -> tr("${hours}小时 ${minutes}分钟", "${hours}h ${minutes}m")
        else -> tr("${minutes}分钟", "${minutes}m")
    }
}

@Composable
private fun localizedOptionalDuration(value: Double?): String =
    value?.roundToLong()?.let { localizedDuration(it) } ?: "—"

@Composable
private fun localizedWordCount(words: Long): String =
    tr("${formatInteger(words)} 字", "${formatInteger(words)} words")

@Composable
private fun localizedStepCount(steps: Double): String =
    tr("${formatInteger(steps.roundToLong())} 步", "${formatInteger(steps.roundToLong())} steps")

private fun formatInteger(value: Long): String =
    NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.coerceAtLeast(0L))

@Composable
private fun recentDurationChartDescription(points: List<StatisticsPoint>): String {
    val parts = ArrayList<String>(points.size)
    for (point in points) {
        parts += "${point.date.format(DAY_FORMATTER)} ${localizedOptionalDuration(point.value)}"
    }
    return tr("近 7 天手机使用时间：", "Screen time over the latest 7 days: ") +
        parts.joinToString(tr("；", "; "))
}

@Composable
private fun recentStepsChartDescription(points: List<StatisticsPoint>): String {
    val parts = ArrayList<String>(points.size)
    for (point in points) {
        val value = if (point.value == null) "—" else localizedStepCount(point.value)
        parts += "${point.date.format(DAY_FORMATTER)} $value"
    }
    return tr("近 7 天步数：", "Steps over the latest 7 days: ") +
        parts.joinToString(tr("；", "; "))
}

private enum class StatisticsHubPage { OVERVIEW, DIARY, READING, GAMES }

private val MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

private val GAMES_WITH_WIN_RATE = setOf(
    "minesweeper", "spider",
)

private val GAME_CATALOG_IDS = listOf(
    "2048", "2048_5", "2048_6", "snake", "tetris", "minesweeper", "spider", "go",
)

private val GAME_PANEL_ROLES = listOf(PanelRole.STANDARD, PanelRole.FEATURE, PanelRole.MEDIA)

private val GAME_METRIC_ORDER: Map<String, List<String>> = mapOf(
    "2048" to listOf("moveAttempts", "effectiveMoves", "merges", "highestTile", "wins"),
    "2048_5" to listOf("moveAttempts", "effectiveMoves", "merges", "highestTile", "wins"),
    "2048_6" to listOf("moveAttempts", "effectiveMoves", "merges", "highestTile", "wins"),
    "snake" to listOf("foodEaten", "maxLength", "losses"),
    "tetris" to listOf("piecesLocked", "linesCleared", "tetrises", "losses"),
    "minesweeper" to listOf(
        "minesCellsRevealed", "minesSwept", "flagsPlaced", "wins", "losses",
    ),
    "spider" to listOf("spiderCardMoves", "spiderDeals", "spiderUndos", "wins", "losses"),
    "go" to listOf("goMovesPlayed", "goStonesCaptured", "goPasses", "goGamesCompleted"),
)
