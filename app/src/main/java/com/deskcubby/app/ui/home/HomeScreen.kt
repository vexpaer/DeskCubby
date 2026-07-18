@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.home

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.repository.DailyPoem
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import com.deskcubby.app.ui.components.FourDotDragHandle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

private data class WidgetInfo(val id: String, val chinese: String, val english: String)

private val availableWidgets = listOf(
    WidgetInfo("calendar", "日历", "Calendar"),
    WidgetInfo("weather", "天气缓存", "Weather cache"),
    WidgetInfo("poem", "每日诗词", "Daily poem"),
    WidgetInfo("today", "今天日期", "Today"),
    WidgetInfo("streak", "连续记录天数", "Writing streak"),
    WidgetInfo("month_diaries", "本月日记数量", "Diaries this month"),
    WidgetInfo("total_words", "日记总字数", "Total diary words"),
    WidgetInfo("recent_diary", "最近日记", "Recent diary"),
    WidgetInfo("recent_thought", "最近小巧思", "Recent thought"),
    WidgetInfo("quick_input", "快速输入", "Quick input"),
    WidgetInfo("random_diary", "随机旧日记", "Random old diary"),
    WidgetInfo("year_progress", "年度进度", "Year progress"),
    WidgetInfo("website", "网站快捷入口", "Website shortcut"),
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
    val poem by viewModel.poem.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    val widgetCenters = remember { mutableStateMapOf<String, Float>() }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("DeskCubby") },
                actions = {
                    if (editing) IconButton(onClick = { addDialog = true }) { Icon(Icons.Outlined.Add, tr("添加组件", "Add widget")) }
                    IconButton(onClick = { editing = !editing }) { Icon(if (editing) Icons.Outlined.Close else Icons.Outlined.Edit, tr("编辑组件", "Edit widgets")) }
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
                DisposableEffect(id) {
                    onDispose { widgetCenters.remove(id) }
                }
                Column(
                    Modifier.onGloballyPositioned { widgetCenters[id] = it.boundsInRoot().center.y },
                ) {
                    if (editing) {
                        val index = settings.homeWidgets.indexOf(id)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FourDotDragHandle { distance ->
                                val start = widgetCenters[id]
                                val targetId = start?.let { origin ->
                                    widgetCenters.minByOrNull { (_, center) -> kotlin.math.abs(center - (origin + distance)) }?.key
                                }
                                val target = settings.homeWidgets.indexOf(targetId)
                                if (target in settings.homeWidgets.indices && target != index) {
                                    onWidgetsChanged(settings.homeWidgets.moved(index, target))
                                }
                            }
                            IconButton(onClick = { onWidgetsChanged(settings.homeWidgets - id) }) { Icon(Icons.Outlined.Close, tr("移除", "Remove")) }
                        }
                    }
                    HomeWidget(
                        id = id,
                        settings = settings,
                        diaries = diaries,
                        thoughts = thoughts,
                        poem = poem,
                        onOpenDiary = onOpenDiary,
                        onOpenThoughts = onOpenThoughts,
                        onOpenWebsite = onOpenWebsite,
                        onQuickThought = onQuickThought,
                        onRefreshPoem = { viewModel.refreshPoem() },
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
            title = { Text(tr("添加小组件", "Add widget")) },
            text = {
                Column {
                    missing.forEach { widget ->
                        TextButton(
                            onClick = { onWidgetsChanged(settings.homeWidgets + widget.id); addDialog = false },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(tr(widget.chinese, widget.english)) }
                    }
                    if (missing.isEmpty()) Text(tr("所有组件都已添加", "All widgets are already added"))
                }
            },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text(tr("完成", "Done")) } },
        )
    }
}

@Composable
private fun HomeWidget(
    id: String,
    settings: AppSettings,
    diaries: List<DiaryIndexEntity>,
    thoughts: List<FlashThoughtEntity>,
    poem: DailyPoem,
    onOpenDiary: (String) -> Unit,
    onOpenThoughts: () -> Unit,
    onOpenWebsite: () -> Unit,
    onQuickThought: (String) -> Unit,
    onRefreshPoem: () -> Unit,
) {
    val today = LocalDate.now()
    val context = LocalContext.current
    val locale = if (settings.appLanguage == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
    when (id) {
        "calendar" -> WidgetCard(tr("日历", "Calendar")) { MonthCalendar(today) }
        "weather" -> WidgetCard(tr("天气", "Weather")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WbSunny, null)
                Spacer(Modifier.width(10.dp))
                Column { Text(tr("离线模式", "Offline")); Text(tr("暂无上次天气缓存", "No cached weather"), style = MaterialTheme.typography.bodySmall) }
            }
        }
        "poem" -> WidgetCard(tr("每日诗词", "Daily poem")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(poem.content, style = MaterialTheme.typography.titleMedium)
                    Text(poem.source, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.jinrishici.com"))) } }) {
                        Text(tr("由今日诗词 API 提供", "Powered by Jinrishici API"), style = MaterialTheme.typography.labelSmall)
                    }
                }
                IconButton(onClick = onRefreshPoem) { Icon(Icons.Outlined.Refresh, tr("换一句", "Refresh poem")) }
            }
        }
        "today" -> WidgetCard(tr("今天", "Today")) {
            val pattern = if (settings.appLanguage == AppLanguage.ENGLISH) "EEEE, MMMM d, yyyy" else "yyyy年M月d日 EEEE"
            Text(today.format(DateTimeFormatter.ofPattern(pattern, locale)), style = MaterialTheme.typography.headlineSmall)
        }
        "streak" -> WidgetCard(tr("连续记录", "Writing streak")) {
            Text(if (settings.appLanguage == AppLanguage.ENGLISH) "${streakDays(diaries, today)} days" else "${streakDays(diaries, today)} 天", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
        "month_diaries" -> WidgetCard(tr("本月日记", "Diaries this month")) {
            val prefix = today.toString().take(7)
            val count = diaries.count { it.dateIso.startsWith(prefix) }
            Text(if (settings.appLanguage == AppLanguage.ENGLISH) "$count entries" else "$count 篇", style = MaterialTheme.typography.headlineMedium)
        }
        "total_words" -> WidgetCard(tr("日记总字数", "Total diary words")) {
            Text("${diaries.sumOf { it.wordCount }}", style = MaterialTheme.typography.headlineMedium)
        }
        "recent_diary" -> WidgetCard(tr("最近日记", "Recent diary")) {
            diaries.take(3).forEach { item ->
                TextButton(onClick = { onOpenDiary(item.uri) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                            Text(item.name.removeSuffix(".md"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.dateIso, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (diaries.isEmpty()) Text(tr("还没有日记", "No diaries yet"))
        }
        "recent_thought" -> WidgetCard(tr("最近小巧思", "Recent thoughts")) {
            thoughts.take(3).forEach { Text(it.content, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = onOpenThoughts) { Text(tr("查看全部", "View all")) }
        }
        "quick_input" -> QuickInputWidget(onQuickThought)
        "random_diary" -> WidgetCard(tr("随机旧日记", "Random old diary")) {
            val item = remember(diaries) { diaries.takeIf { it.isNotEmpty() }?.get(Random.nextInt(diaries.size)) }
            if (item == null) Text(tr("还没有可回顾的日记", "No diary to revisit")) else TextButton(onClick = { onOpenDiary(item.uri) }) { Text(item.name.removeSuffix(".md")) }
        }
        "year_progress" -> WidgetCard(tr("年度进度", "Year progress")) {
            val total = if (today.isLeapYear) 366 else 365
            val progress = today.dayOfYear / total.toFloat()
            Text(if (settings.appLanguage == AppLanguage.ENGLISH) "${(progress * 100).toInt()}% · day ${today.dayOfYear} / $total" else "${(progress * 100).toInt()}% · 第 ${today.dayOfYear} / $total 天")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        "website" -> WidgetCard(tr("网站快捷入口", "Website shortcut")) {
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
    WidgetCard(tr("快速输入", "Quick input")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.weight(1f), placeholder = { Text(tr("记一条小巧思", "Write a thought")) })
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { onSubmit(value); value = "" },
                enabled = value.isNotBlank(),
            ) { Icon(Icons.Outlined.Send, tr("发送", "Send")) }
        }
    }
}

@Composable
private fun MonthCalendar(today: LocalDate) {
    val month = YearMonth.from(today)
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val cells = List(firstOffset) { 0 } + (1..month.lengthOfMonth()).toList()
    Text(if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == AppLanguage.ENGLISH) "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}" else "${month.year}年${month.monthValue}月", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth()) {
        val weekdays = if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == AppLanguage.ENGLISH) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("一", "二", "三", "四", "五", "六", "日")
        weekdays.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center) }
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
