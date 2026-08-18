@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.structuredrecords

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.structuredrecords.JournalDayEngine
import com.deskcubby.app.data.structuredrecords.StructuredField
import com.deskcubby.app.data.structuredrecords.StructuredFieldSource
import com.deskcubby.app.data.structuredrecords.StructuredFieldType
import com.deskcubby.app.data.structuredrecords.StructuredRecordSegment
import com.deskcubby.app.data.structuredrecords.StructuredRecordTemplate
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr

@Composable
fun StructuredRecordsScreen(
    padding: PaddingValues,
    viewModel: StructuredRecordsViewModel,
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val fields by viewModel.fields.collectAsStateWithLifecycle()
    val sendingIds by viewModel.sendingTemplateIds.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val systemSnapshot by viewModel.systemSnapshot.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingTemplate by remember { mutableStateOf<StructuredRecordTemplate?>(null) }
    var showNewEditor by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<StructuredRecordTemplate?>(null) }
    val fieldsById = remember(fields) { fields.associateBy { it.id } }

    LaunchedEffect(Unit) { viewModel.touchNow() }
    LaunchedEffect(feedback?.key) {
        feedback?.let { current ->
            snackbarHostState.showSnackbar(current.message)
            viewModel.consumeFeedback(current.key)
        }
    }

    Scaffold(
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding()
            .navigationBarsPadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("结构化记录", "Structured records")) },
                navigationIcon = {
                    onBack?.let { goBack ->
                        IconButton(onClick = goBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (systemSnapshot != null && systemSnapshot!!.autoRecording) {
                item(key = "system") {
                    SystemSleepWakeCard(snapshot = systemSnapshot!!, journalDay = viewModel.journalDay.collectAsStateWithLifecycle().value)
                }
            }
            if (templates.isEmpty()) {
                item(key = "empty") { EmptyStructuredRecords(onAdd = { showNewEditor = true }) }
            }
            items(templates.filterNot { it.archived }, key = StructuredRecordTemplate::id) { template ->
                StructuredRecordRecorder(
                    template = template,
                    fieldsById = fieldsById,
                    now = now,
                    isSending = template.id in sendingIds,
                    clearInputsKey = feedback
                        ?.takeIf { !it.isError && it.recordedTemplateId == template.id }
                        ?.key,
                    onRecord = { values -> viewModel.record(template, values) },
                    onEdit = { editingTemplate = template },
                    onDelete = { pendingDelete = template },
                )
            }
            item(key = "add") {
                Button(
                    onClick = { showNewEditor = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("新建结构化记录", "New structured record"))
                }
            }
        }
    }

    if (showNewEditor) {
        NewRecordEditorDialog(
            fields = fields,
            onDismiss = { showNewEditor = false },
            onConfirm = { name, fieldId, prefix ->
                val field = fieldsById[fieldId]
                viewModel.addTemplate(name, field, prefix)
                showNewEditor = false
            },
        )
    }

    editingTemplate?.let { template ->
        val fieldSegments = template.segments.filterIsInstance<StructuredRecordSegment.Field>()
        if (fieldSegments.isNotEmpty() && fieldSegments.all { fieldsById.containsKey(it.fieldId) }) {
            TemplateFieldEditorDialog(
                template = template,
                fieldsById = fieldsById,
                onDismiss = { editingTemplate = null },
                onSave = { updated ->
                    viewModel.updateTemplate(updated)
                    editingTemplate = null
                },
            )
        } else {
            // Template has no editable fields (pure text) — rename only.
            PureTextTemplateEditorDialog(
                template = template,
                onDismiss = { editingTemplate = null },
                onSave = { updated ->
                    viewModel.updateTemplate(updated)
                    editingTemplate = null
                },
            )
        }
    }

    pendingDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("删除结构化记录？", "Delete structured record?")) },
            text = { Text(tr("将删除“${template.name}”，此操作无法撤销。", "“${template.name}” will be deleted. This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeTemplate(template.id)
                    pendingDelete = null
                }) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun EmptyStructuredRecords(onAdd: () -> Unit) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.EventNote, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(tr("还没有结构化记录", "No structured records yet"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                tr("添加一句带类型字段的记录，例如「做了 [数字] 个俯卧撑」。", "Add a sentence with a typed field, e.g. “Did [number] push-ups.”"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAdd) { Text(tr("添加示例记录", "Add example records")) }
        }
    }
}

@Composable
private fun SystemSleepWakeCard(snapshot: com.deskcubby.app.data.structuredrecords.SystemFieldSnapshot, journalDay: java.time.LocalDate?) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(tr("自动估算的作息", "Auto-estimated sleep & wake"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                tr(
                    "根据手机首次/最后一次使用与解锁/锁屏时间估算，不使用 Health Connect；最终值会在当天结算后写入日记。",
                    "Estimated from first/last phone use and unlock/lock events, not Health Connect. Final values are written to the diary after the day settles.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (journalDay != null) {
                Text(
                    tr("当前日记日：$journalDay", "Journal day: $journalDay"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("起床：${snapshot.wakeTime?.let(JournalDayEngine::formatTime) ?: "—"}", "Wake: ${snapshot.wakeTime?.let(JournalDayEngine::formatTime) ?: "—"}"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("睡觉：${snapshot.sleepTime?.let(JournalDayEngine::formatTime) ?: "—"}", "Sleep: ${snapshot.sleepTime?.let(JournalDayEngine::formatTime) ?: "—"}"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StructuredRecordRecorder(
    template: StructuredRecordTemplate,
    fieldsById: Map<String, StructuredField>,
    now: java.time.LocalTime,
    isSending: Boolean,
    clearInputsKey: Long? = null,
    onRecord: (List<String>) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    val fieldSegments = template.segments.filterIsInstance<StructuredRecordSegment.Field>()
    val state = remember(template.id) { mutableStateOf(emptyList<String>()) }
    val values = state.value
    LaunchedEffect(clearInputsKey) {
        if (clearInputsKey != null) state.value = emptyList()
    }
    val ready = fieldSegments.indices.all { index ->
        values.getOrNull(index)?.isNotBlank() == true
    }
    // Show the sentence as a preview; field positions render as their current values or the field name.
    val preview = remember(template.segments, values, fieldsById) { previewText(template.segments, values, fieldsById) }

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                preview,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (fieldSegments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fieldSegments.forEachIndexed { index, segment ->
                        val field = fieldsById[segment.fieldId]
                        if (field != null) {
                            TypedFieldInput(
                                field = field,
                                now = now,
                                value = values.getOrNull(index).orEmpty(),
                                onValueChange = { newValue ->
                                    val updated = values.toMutableList()
                                    while (updated.size <= index) updated.add("")
                                    updated[index] = newValue
                                    state.value = updated
                                },
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f)) {
                    onEdit?.let { edit -> IconButton(onClick = edit, enabled = !isSending) {
                        Icon(Icons.Outlined.Edit, tr("编辑 ${template.name}", "Edit ${template.name}"))
                    } }
                    onDelete?.let { delete -> IconButton(onClick = delete, enabled = !isSending) {
                        Icon(Icons.Outlined.Delete, tr("删除 ${template.name}", "Delete ${template.name}"))
                    } }
                }
                FilledIconButton(
                    onClick = { onRecord(values) },
                    enabled = !isSending && ready,
                ) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(tr("记录", "Record"), modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
    }
}

private fun previewText(
    segments: List<StructuredRecordSegment>,
    values: List<String>,
    fieldsById: Map<String, StructuredField>,
): String {
    val sb = StringBuilder()
    var valueIndex = 0
    for (segment in segments) {
        when (segment) {
            is StructuredRecordSegment.Text -> sb.append(segment.value)
            is StructuredRecordSegment.Field -> {
                val value = values.getOrNull(valueIndex)
                if (!value.isNullOrBlank()) {
                    sb.append(value)
                } else {
                    val field = fieldsById[segment.fieldId]
                    sb.append("[").append(field?.name ?: segment.fieldId).append("]")
                }
                valueIndex++
            }
        }
    }
    return sb.toString().trim()
}

@Composable
private fun TypedFieldInput(
    field: StructuredField,
    now: java.time.LocalTime,
    value: String,
    onValueChange: (String) -> Unit,
) {
    when (field.type) {
        StructuredFieldType.WORD -> OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(field.name) },
            minLines = 1,
            maxLines = 4,
        )
        StructuredFieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = { candidate -> onValueChange(candidate.take(24)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (field.unit.isNullOrBlank()) field.name else "${field.name}（${field.unit}）") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            singleLine = true,
        )
        StructuredFieldType.TIME -> OutlinedTextField(
            value = value,
            onValueChange = { candidate -> onValueChange(candidate.take(5)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(field.name) },
            placeholder = { Text(JournalDayEngine.formatTime(now)) },
            singleLine = true,
        )
        StructuredFieldType.DURATION -> OutlinedTextField(
            value = value,
            onValueChange = { candidate -> onValueChange(candidate.take(12)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(field.name) },
            placeholder = { Text(tr("00:42", "00:42")) },
            singleLine = true,
        )
        StructuredFieldType.TYPE -> TypeOptionInput(field = field, value = value, onValueChange = onValueChange)
    }
}

@Composable
private fun TypeOptionInput(field: StructuredField, value: String, onValueChange: (String) -> Unit) {
    val options = field.options
    if (options.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                val selected = option == value
                TextButton(
                    onClick = { onValueChange(option) },
                    modifier = Modifier,
                ) {
                    Text(option, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (field.allowCustomOption || value !in options) {
                OutlinedTextField(
                    value = if (value in options) "" else value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("自定义", "Custom")) },
                    singleLine = true,
                )
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(field.name) },
            singleLine = true,
        )
    }
}

@Composable
private fun NewRecordEditorDialog(
    fields: List<StructuredField>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, fieldId: String, prefix: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var prefix by rememberSaveable { mutableStateOf("") }
    var selectedFieldId by rememberSaveable { mutableStateOf(fields.firstOrNull()?.id.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("新建结构化记录", "New structured record")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("记录名称", "Record name")) },
                    placeholder = { Text(tr("例如：俯卧撑次数", "e.g. Push-ups")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("前导文字（可选）", "Prefix text (optional)")) },
                    placeholder = { Text(tr("例如：做了 ", "e.g. Did ")) },
                    singleLine = true,
                )
                FieldPicker(
                    fields = fields,
                    selectedFieldId = selectedFieldId,
                    onSelect = { selectedFieldId = it },
                )
                Text(
                    tr("选择一个已存在的字段；也可以稍后在字段管理中添加新字段。", "Choose an existing field; you can add new fields later in field management."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedFieldId.isNotEmpty() && name.isNotBlank(),
                onClick = { onConfirm(name.trim(), selectedFieldId, prefix.trim()) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldPicker(fields: List<StructuredField>, selectedFieldId: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = fields.firstOrNull { it.id == selectedFieldId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { "${it.name}（${fieldTypeLabel(it.type)}）" } ?: tr("选择字段", "Select a field"),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fields.forEach { field ->
                DropdownMenuItem(
                    text = { Text("${field.name}（${fieldTypeLabel(field.type)}）") },
                    onClick = {
                        onSelect(field.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun fieldTypeLabel(type: StructuredFieldType): String = when (type) {
    StructuredFieldType.WORD -> tr("文字", "Text")
    StructuredFieldType.NUMBER -> tr("数字", "Number")
    StructuredFieldType.TYPE -> tr("分类", "Category")
    StructuredFieldType.TIME -> tr("时间", "Time")
    StructuredFieldType.DURATION -> tr("时长", "Duration")
}

@Composable
private fun TemplateFieldEditorDialog(
    template: StructuredRecordTemplate,
    fieldsById: Map<String, StructuredField>,
    onDismiss: () -> Unit,
    onSave: (StructuredRecordTemplate) -> Unit,
) {
    var name by rememberSaveable(template.id) { mutableStateOf(template.name) }
    var prefix by rememberSaveable(template.id) {
        mutableStateOf(template.segments.firstOrNull { it is StructuredRecordSegment.Text }?.let { (it as StructuredRecordSegment.Text).value }.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("编辑结构化记录", "Edit structured record")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("记录名称", "Record name")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("前导文字", "Prefix text")) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val segments = template.segments.toMutableList()
                    val leadingTextIndex = segments.indexOfFirst { it is StructuredRecordSegment.Text }
                    if (prefix.isBlank()) {
                        if (leadingTextIndex >= 0) segments.removeAt(leadingTextIndex)
                    } else if (leadingTextIndex >= 0) {
                        segments[leadingTextIndex] = StructuredRecordSegment.Text(prefix)
                    } else {
                        segments.add(0, StructuredRecordSegment.Text(prefix))
                    }
                    val updated = template.copy(name = name.trim(), segments = segments)
                    onSave(updated)
                },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun PureTextTemplateEditorDialog(
    template: StructuredRecordTemplate,
    onDismiss: () -> Unit,
    onSave: (StructuredRecordTemplate) -> Unit,
) {
    var name by rememberSaveable(template.id) { mutableStateOf(template.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("编辑结构化记录", "Edit structured record")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(tr("记录名称", "Record name")) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(template.copy(name = name.trim())) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}
