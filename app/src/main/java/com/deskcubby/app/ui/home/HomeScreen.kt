@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.ui.theme.GlassPanel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

private data class WidgetInfo(val id: String, val title: String)

private val availableWidgets = listOf(
    WidgetInfo("calendar", "日历"),
    WidgetInfo("weather", "天气缓存"),
    WidgetInfo("poem", "每日诗词"),
    WidgetInfo("today", "今天日期"),
    WidgetInfo("streak", "连续记录天数"),
    WidgetInfo("month_diaries", "本月日记数量"),
    WidgetInfo("total_words", "日记总字数"),
    WidgetInfo("recent_diary", "最近日记"),
    WidgetInfo("recent_thought", "最近闪思"),
    WidgetInfo("quick_input", "快速输入"),
    WidgetInfo("random_diary", "随机旧日记"),
    WidgetInfo("year_progress", "年度进度"),
    WidgetInfo("website", "网站快捷入口"),
)

@Composable
fun HomeScreen(
    padding: PaddingValues,
    settings: AppSettings,
    viewModel: HomeViewModel,
    onOpenDiary: (String) -> Unit,
    onOpenThoughts: () -> Unit,
    onOpenWebsite: () -> Unit,
    onQuickThought: (String) -> Unit,
    onWidgetsChanged: (List<String>) -> Unit,
) {
    val diaries by viewModel.diaries.collectAsStateWithLifecycle()
    val thoughts by viewModel.thoughts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("DeskCubby") },
                actions = {
                    if (editing) IconButton(onClick = { addDialog = true }) { Icon(Icons.Outlined.Add, "添加组件") }
                    IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Outlined.Close else Icons.Outlined.Edit, "编辑组件") }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(settings.homeWidgets, key = { it }) { id ->
                Column {
                    if (editing) {
                        val index = settings.homeWidgets.indexOf(id)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(
                                enabled = index > 0,
                                onClick = { onWidgetsChanged(settings.homeWidgets.moved(index, index - 1)) },
                            ) { Icon(Icons.Outlined.ArrowUpward, "上移") }
                            IconButton(
                                enabled = index < settings.homeWidgets.lastIndex,
                                onClick = { onWidgetsChanged(settings.homeWidgets.moved(index, index + 1)) },
                            ) { Icon(Icons.Outlined.ArrowDownward, "下移") }
                            IconButton(onClick = { onWidgetsChanged(settings.homeWidgets - id) }) { Icon(Icons.Outlined.Close, "移除") }
                        }
                    }
                    HomeWidget(
                        id = id,
                        settings = settings,
                        diaries = diaries,
                        thoughts = thoughts,
                        onOpenDiary = onOpenDiary,
                        onOpenThoughts = onOpenThoughts,
                        onOpenWebsite = onOpenWebsite,
                        onQuickThought = onQuickThought,
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (addDialog) {
        val missing = availableWidgets.filterNot { it.id in settings.homeWidgets }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("添加小组件") },
            text = {
                Column {
                    missing.forEach { widget ->
                        TextButton(
                            onClick = { onWidgetsChanged(settings.homeWidgets + widget.id); addDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(widget.title) }
                    }
                    if (missing.isEmpty()) Text("所有组件都已添加")
                }
            },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text("完成") } },
        )
    }
}

@Composable
private fun HomeWidget(
    id: String,
    settings: AppSettings,
    diaries: List<DiaryIndexEntity>,
    thoughts: List<FlashThoughtEntity>,
    onOpenDiary: (String) -> Unit,
    onOpenThoughts: () -> Unit,
    onOpenWebsite: () -> Unit,
    onQuickThought: (String) -> Unit,
) {
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    when (id) {
        "calendar" -> WidgetCard("日历") { MonthCalendar(today) }
        "weather" -> WidgetCard("天气") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WbSunny, null)
                Spacer(Modifier.width(10.dp))
                Column { Text("离线模式"); Text("暂无上次天气缓存", style = MaterialTheme.typography.bodySmall) }
            }
        }
        "poem" -> WidgetCard("每日诗词") {
            Text("山中何事？松花酿酒，春水煎茶。", style = MaterialTheme.typography.titleMedium)
            Text("— 张可久《人月圆·山中书事》", style = MaterialTheme.typography.bodySmall)
        }
        "today" -> WidgetCard("今天") {
            Text(today.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", locale)), style = MaterialTheme.typography.headlineSmall)
        }
        "streak" -> WidgetCard("连续记录") {
            Text("${streakDays(diaries, today)} 天", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        "month_diaries" -> WidgetCard("本月日记") {
            val prefix = today.toString().take(7)
            Text("${diaries.count { it.dateIso.startsWith(prefix) }} 篇", style = MaterialTheme.typography.headlineMedium)
        }
        "total_words" -> WidgetCard("日记总字数") {
            Text("${diaries.sumOf { it.wordCount }}", style = MaterialTheme.typography.headlineMedium)
        }
        "recent_diary" -> WidgetCard("最近日记") {
            diaries.take(3).forEach { item ->
                TextButton(onClick = { onOpenDiary(item.uri) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.dateIso, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (diaries.isEmpty()) Text("还没有日记")
        }
        "recent_thought" -> WidgetCard("最近闪思") {
            thoughts.take(3).forEach { Text(it.content, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = onOpenThoughts) { Text("查看全部") }
        }
        "quick_input" -> QuickInputWidget(onQuickThought)
        "random_diary" -> WidgetCard("随机旧日记") {
            val item = remember(diaries) { diaries.takeIf { it.isNotEmpty() }?.get(Random.nextInt(diaries.size)) }
            if (item == null) Text("还没有可回顾的日记") else TextButton(onClick = { onOpenDiary(item.uri) }) { Text(item.title) }
        }
        "year_progress" -> WidgetCard("年度进度") {
            val total = if (today.isLeapYear) 366 else 365
            val progress = today.dayOfYear / total.toFloat()
            Text("${(progress * 100).toInt()}% · 第 ${today.dayOfYear} / $total 天")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        "website" -> WidgetCard("网站快捷入口") {
            AssistChip(onClick = onOpenWebsite, label = { Text(settings.browserHomeUrl) }, leadingIcon = { Icon(Icons.Outlined.Language, null) })
        }
    }
}

@Composable
private fun WidgetCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun QuickInputWidget(onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    WidgetCard("快速输入") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.weight(1f), placeholder = { Text("记一条闪思") })
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { onSubmit(value); value = "" },
                enabled = value.isNotBlank(),
            ) { Icon(Icons.Outlined.Send, "发送") }
        }
    }
}

@Composable
private fun MonthCalendar(today: LocalDate) {
    val month = YearMonth.from(today)
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = List(firstOffset) { 0 } + (1..month.lengthOfMonth()).toList()
    Text("${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center) }
    }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            week.forEach { day ->
                Text(
                    text = if (day == 0) "" else day.toString(),
                    modifier = Modifier.weight(1f).padding(5.dp),
                    textAlign = TextAlign.Center,
                    color = if (day == today.dayOfMonth) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun streakDays(diaries: List<DiaryIndexEntity>, today: LocalDate): Int {
    val dates = diaries.mapNotNull { runCatching { LocalDate.parse(it.dateIso) }.getOrNull() }.toSet()
    var cursor = if (today in dates) today else today.minusDays(1)
    var count = 0
    while (cursor in dates) { count++; cursor = cursor.minusDays(1) }
    return count
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices) return this
    return toMutableList().apply { val value = removeAt(from); add(to, value) }
}
