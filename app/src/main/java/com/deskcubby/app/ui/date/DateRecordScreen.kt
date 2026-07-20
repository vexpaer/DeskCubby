@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.date

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.tr
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.time.temporal.ChronoUnit

@Composable
fun DateRecordScreen(
    padding: PaddingValues,
    viewModel: DateRecordViewModel,
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val operationFailedLabel = tr("操作失败", "Operation failed")
    var editorRecord by remember { mutableStateOf<DateRecordEntity?>(null) }
    var showNewEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DateRecordEntity?>(null) }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar("$operationFailedLabel: $it")
            viewModel.consumeError()
        }
    }

    Scaffold(
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(title = { Text(tr("日期记录", "Dates")) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewEditor = true }) {
                Icon(Icons.Outlined.Add, tr("添加日期", "Add date"))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        if (records.isEmpty()) {
            EmptyDates(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                onAdd = { showNewEditor = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    DateRecordCard(
                        record = record,
                        onEdit = { editorRecord = record },
                        onDelete = { pendingDelete = record },
                    )
                }
            }
        }
    }

    if (showNewEditor) {
        DateRecordEditorDialog(
            record = null,
            onDismiss = { showNewEditor = false },
            onConfirm = { name, icon, dateIso ->
                viewModel.create(name, icon, dateIso)
                showNewEditor = false
            },
        )
    }

    editorRecord?.let { record ->
        DateRecordEditorDialog(
            record = record,
            onDismiss = { editorRecord = null },
            onConfirm = { name, icon, dateIso ->
                viewModel.update(record.id, name, icon, dateIso)
                editorRecord = null
            },
        )
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("删除日期？", "Delete date?")) },
            text = {
                Text(
                    tr(
                        "将删除“${record.name}”，此操作无法撤销。",
                        "\u201c${record.name}\u201d will be deleted. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(record.id)
                        pendingDelete = null
                    },
                ) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun EmptyDates(modifier: Modifier, onAdd: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(tr("还没有日期记录", "No dates yet"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("添加纪念日、目标日或其他重要日期", "Add an anniversary, goal, or important date"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            Button(onClick = onAdd) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("添加日期", "Add date"))
            }
        }
    }
}

@Composable
private fun DateRecordCard(
    record: DateRecordEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val target = remember(record.dateIso) { runCatching { LocalDate.parse(record.dateIso) }.getOrNull() }
    val today = LocalDate.now()
    val language = LocalAppLanguage.current
    val formatter = remember(language) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(
            if (language == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE,
        )
    }

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(record.icon.ifBlank { DEFAULT_DATE_ICON }, fontSize = 25.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    target?.format(formatter) ?: record.dateIso,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    dateDistanceText(record.name, target, today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, tr("编辑", "Edit")) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, tr("删除", "Delete")) }
        }
    }
}

@Composable
private fun dateDistanceText(name: String, target: LocalDate?, today: LocalDate): String {
    if (target == null) return tr("日期格式无效", "Invalid date")
    val days = ChronoUnit.DAYS.between(today, target)
    return when {
        days < 0 -> tr("距离 $name 已经过去 ${-days} 天", "${-days} days since $name")
        days > 0 -> tr("还有 $days 天到 $name", "$days days until $name")
        else -> tr("今天就是 $name", "$name is today")
    }
}

@Composable
private fun DateRecordEditorDialog(
    record: DateRecordEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, icon: String, dateIso: String) -> Unit,
) {
    val today = LocalDate.now()
    val initialDate = remember(record?.id, record?.dateIso) {
        record?.dateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
    }
    var name by remember(record?.id) { mutableStateOf(record?.name.orEmpty()) }
    var icon by remember(record?.id) { mutableStateOf(record?.icon?.ifBlank { DEFAULT_DATE_ICON } ?: DEFAULT_DATE_ICON) }
    var selectedDate by remember(record?.id) { mutableStateOf(initialDate) }
    var showDatePicker by remember(record?.id) { mutableStateOf(false) }
    val language = LocalAppLanguage.current
    val dateFormatter = remember(language) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(
            if (language == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE,
        )
    }
    val previewName = if (name.isBlank()) tr("这个日期", "this date") else name

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (record == null) tr("添加日期", "Add date") else tr("编辑日期", "Edit date")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_DATE_RECORD_NAME_CHARS) },
                    label = { Text(tr("名称", "Name")) },
                    placeholder = { Text(tr("例如：旅行出发", "e.g. Start of trip")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = icon,
                    onValueChange = { value -> if (value.codePointCount(0, value.length) <= 4) icon = value },
                    label = { Text(tr("图标（可直接输入 Emoji）", "Icon (enter an emoji)")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(COMMON_DATE_ICONS) { candidate ->
                        Surface(
                            modifier = Modifier
                                .size(42.dp)
                                .clickable { icon = candidate },
                            shape = MaterialTheme.shapes.medium,
                            color = if (icon == candidate) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) { Text(candidate, fontSize = 21.sp) }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CalendarMonth, null)
                    Spacer(Modifier.width(8.dp))
                    Text(selectedDate.format(dateFormatter))
                }
                Text(
                    dateDistanceText(previewName, selectedDate, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name, icon, selectedDate.toString()) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )

    if (showDatePicker) {
        DateSelectionDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                selectedDate = it
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun DateSelectionDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = pickerState.selectedDateMillis
                        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?: initialDate
                    onConfirm(selected)
                },
            ) { Text(tr("确定", "OK")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    ) {
        DatePicker(state = pickerState, showModeToggle = true)
    }
}

private val COMMON_DATE_ICONS = listOf("🎯", "🎂", "❤️", "✈️", "🎓", "🏠", "💼", "🎉", "⭐", "📅")

private const val MAX_DATE_RECORD_NAME_CHARS = 256
