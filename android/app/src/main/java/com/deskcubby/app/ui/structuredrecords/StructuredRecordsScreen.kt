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
import androidx.compose.material.icons.automirrored.outlined.Send
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.structuredrecords.JournalDayEngine
import com.deskcubby.app.data.structuredrecords.StructuredField
import com.deskcubby.app.data.structuredrecords.StructuredFieldType
import com.deskcubby.app.data.structuredrecords.StructuredRecordDraft
import com.deskcubby.app.data.structuredrecords.StructuredRecordTemplate
import com.deskcubby.app.data.structuredrecords.applyStructuredDraftEdit
import com.deskcubby.app.data.structuredrecords.createStructuredRecordDraft
import com.deskcubby.app.data.structuredrecords.createStructuredTemplateDraft
import com.deskcubby.app.data.structuredrecords.insertStructuredTemplateField
import com.deskcubby.app.data.structuredrecords.isStructuredDraftReady
import com.deskcubby.app.data.structuredrecords.replaceStructuredDraftField
import com.deskcubby.app.data.structuredrecords.structuredDraftToSegments
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
    val journalDay by viewModel.journalDay.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingTemplate by remember { mutableStateOf<StructuredRecordTemplate?>(null) }
    var showNewEditor by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<StructuredRecordTemplate?>(null) }
    val fieldsById = remember(fields) { fields.associateBy { it.id } }

    LaunchedEffect(Unit) {
        viewModel.touchNow()
        viewModel.refreshWorkspaceFromUi()
    }
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
            if (systemSnapshot?.autoRecording == true) {
                item(key = "system") {
                    SystemSleepWakeCard(snapshot = systemSnapshot!!, journalDay = journalDay)
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
                    onRecord = { draft -> viewModel.record(template, draft) },
                    onEdit = { editingTemplate = template },
                    onDelete = { pendingDelete = template },
                )
            }
            item(key = "add") {
                Button(onClick = { showNewEditor = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("新建结构化记录", "New structured record"))
                }
            }
        }
    }

    if (showNewEditor) {
        TemplateEditorDialog(
            initialTemplate = null,
            fields = fields.filterNot { it.archived },
            fieldsById = fieldsById,
            onDismiss = { showNewEditor = false },
            onSaveNew = { name, draft ->
                viewModel.addTemplate(name, draft)
                showNewEditor = false
            },
            onSaveExisting = { _, _ -> },
        )
    }

    editingTemplate?.let { template ->
        TemplateEditorDialog(
            initialTemplate = template,
            fields = fields.filterNot { it.archived },
            fieldsById = fieldsById,
            onDismiss = { editingTemplate = null },
            onSaveNew = { _, _ -> },
            onSaveExisting = { name, draft ->
                viewModel.updateTemplate(
                    template.copy(name = name, segments = structuredDraftToSegments(draft)),
                )
                editingTemplate = null
            },
        )
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
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(tr("取消", "Cancel")) } },
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
                tr("模板正文里可以放任意多个类型字段；记录时所有字段都在同一个输入框中原地填写。", "A template can contain any number of typed fields; fill them inline in one editor."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAdd) { Text(tr("新建记录", "Create record")) }
        }
    }
}

@Composable
private fun SystemSleepWakeCard(
    snapshot: com.deskcubby.app.data.structuredrecords.SystemFieldSnapshot,
    journalDay: java.time.LocalDate?,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(tr("自动估算的作息", "Auto-estimated sleep & wake"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                tr(
                    "根据手机首次/最后一次使用与解锁/锁屏时间估算，不使用 Health Connect；归属起床时间的自然日期。",
                    "Estimated from first/last phone use and unlock/lock events, not Health Connect; assigned to the wake timestamp's calendar date.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (journalDay != null) {
                Text(tr("自然日期：$journalDay", "Calendar date: $journalDay"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WbSunny, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("起床：${snapshot.wakeTime?.let(JournalDayEngine::formatTime) ?: "—"}", "Wake: ${snapshot.wakeTime?.let(JournalDayEngine::formatTime) ?: "—"}"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("睡觉：${snapshot.sleepTime?.let(JournalDayEngine::formatTime) ?: "—"}", "Sleep: ${snapshot.sleepTime?.let(JournalDayEngine::formatTime) ?: "—"}"))
                }
            }
        }
    }
}

@Composable
internal fun StructuredRecordRecorder(
    template: StructuredRecordTemplate,
    fieldsById: Map<String, StructuredField>,
    now: java.time.LocalTime,
    isSending: Boolean,
    clearInputsKey: Long?,
    onRecord: (StructuredRecordDraft) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    fun freshDraft() = createStructuredRecordDraft(template, fieldsById, now)
    var draft by remember(template.id, template.segments, fieldsById) { mutableStateOf(freshDraft()) }
    var editor by remember(template.id, template.segments, fieldsById) {
        mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length)))
    }
    LaunchedEffect(clearInputsKey) {
        if (clearInputsKey != null) {
            draft = freshDraft()
            editor = TextFieldValue(draft.text, TextRange(draft.text.length))
        }
    }
    val activeSpan = activeDraftField(draft, editor.selection)
    val activeField = activeSpan?.let { fieldsById[it.fieldId] }
    val ready = isStructuredDraftReady(draft)

    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(template.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = editor,
                onValueChange = { candidate ->
                    if (candidate.text == editor.text) {
                        editor = selectWholeFieldWhenTapped(candidate, draft)
                    } else {
                        val updated = applyStructuredDraftEdit(draft, candidate.text)
                        if (updated != null) {
                            draft = updated
                            editor = candidate.copy(selection = candidate.selection.coerceTo(updated.text.length))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 10,
                visualTransformation = draftVisualTransformation(draft),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (activeField?.type == StructuredFieldType.NUMBER) KeyboardType.Decimal else KeyboardType.Text,
                ),
            )
            if (activeSpan != null && activeField != null) {
                val span = activeSpan
                InlineFieldAssist(
                    field = activeField,
                    now = now,
                    value = span.value.orEmpty(),
                    onValue = { replacement ->
                        draft = replaceStructuredDraftField(draft, span.occurrenceIndex, replacement)
                        val changed = draft.fields.first { it.occurrenceIndex == span.occurrenceIndex }
                        editor = TextFieldValue(draft.text, TextRange(changed.start, changed.endExclusive))
                    },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f)) {
                    onEdit?.let { edit -> IconButton(onClick = edit, enabled = !isSending) { Icon(Icons.Outlined.Edit, tr("编辑 ${template.name}", "Edit ${template.name}")) } }
                    onDelete?.let { delete -> IconButton(onClick = delete, enabled = !isSending) { Icon(Icons.Outlined.Delete, tr("删除 ${template.name}", "Delete ${template.name}")) } }
                }
                FilledIconButton(onClick = { onRecord(draft) }, enabled = !isSending && ready) {
                    if (isSending) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.Send,
                            contentDescription = tr("记录 ${template.name}", "Record ${template.name}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineFieldAssist(
    field: StructuredField,
    now: java.time.LocalTime,
    value: String,
    onValue: (String) -> Unit,
) {
    when (field.type) {
        StructuredFieldType.TYPE -> {
            if (field.options.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    field.options.forEach { option ->
                        TextButton(onClick = { onValue(option) }) {
                            Text(option, color = if (option == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        StructuredFieldType.TIME -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onValue(JournalDayEngine.formatTime(now)) }) { Text(tr("当前时间", "Now")) }
        }
        StructuredFieldType.DURATION -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onValue("00:15") }) { Text("15m") }
            TextButton(onClick = { onValue("00:30") }) { Text("30m") }
            TextButton(onClick = { onValue("01:00") }) { Text("1h") }
        }
        StructuredFieldType.WORD,
        StructuredFieldType.NUMBER,
        -> Unit
    }
}

@Composable
private fun TemplateEditorDialog(
    initialTemplate: StructuredRecordTemplate?,
    fields: List<StructuredField>,
    fieldsById: Map<String, StructuredField>,
    onDismiss: () -> Unit,
    onSaveNew: (String, StructuredRecordDraft) -> Unit,
    onSaveExisting: (String, StructuredRecordDraft) -> Unit,
) {
    var name by rememberSaveable(initialTemplate?.id) { mutableStateOf(initialTemplate?.name.orEmpty()) }
    val initialDraft = remember(initialTemplate?.id, initialTemplate?.segments, fieldsById) {
        initialTemplate?.let { createStructuredTemplateDraft(it, fieldsById) }
            ?: StructuredRecordDraft("", emptyList())
    }
    var draft by remember(initialTemplate?.id) { mutableStateOf(initialDraft) }
    var editor by remember(initialTemplate?.id) { mutableStateOf(TextFieldValue(initialDraft.text, TextRange(initialDraft.text.length))) }
    var selectedFieldId by rememberSaveable(initialTemplate?.id) { mutableStateOf(fields.firstOrNull()?.id.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialTemplate == null) tr("新建结构化记录", "New structured record") else tr("编辑结构化记录", "Edit structured record")) },
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
                    value = editor,
                    onValueChange = { candidate ->
                        if (candidate.text == editor.text) {
                            editor = selectWholeFieldWhenTapped(candidate, draft)
                        } else {
                            applyStructuredDraftEdit(draft, candidate.text)?.let { updated ->
                                draft = updated
                                editor = candidate.copy(selection = candidate.selection.coerceTo(updated.text.length))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("模板正文", "Template body")) },
                    placeholder = { Text(tr("例如：今天做了  个俯卧撑，午饭吃了 。", "e.g. Today I did  push-ups and ate  for lunch.")) },
                    minLines = 4,
                    maxLines = 10,
                    visualTransformation = draftVisualTransformation(draft),
                )
                if (fields.isNotEmpty()) {
                    FieldPicker(fields, selectedFieldId) { selectedFieldId = it }
                    TextButton(
                        enabled = selectedFieldId.isNotBlank(),
                        onClick = {
                            val field = fieldsById[selectedFieldId] ?: return@TextButton
                            val inserted = insertStructuredTemplateField(draft, field, editor.selection.start)
                            draft = inserted
                            val span = inserted.fields.lastOrNull { it.fieldId == field.id }
                            editor = TextFieldValue(
                                inserted.text,
                                span?.let { TextRange(it.start, it.endExclusive) } ?: TextRange(inserted.text.length),
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("在光标处插入字段", "Insert field at cursor"))
                    }
                }
                Text(
                    tr("字段在正文中以下划线显示；可以插入多个字段、重复字段和多行文字。", "Fields stay underlined inline; multiple, repeated and multiline fields are supported."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && draft.text.isNotBlank(),
                onClick = {
                    if (initialTemplate == null) onSaveNew(name.trim(), draft)
                    else onSaveExisting(name.trim(), draft)
                },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

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

private fun draftVisualTransformation(draft: StructuredRecordDraft): VisualTransformation =
    VisualTransformation { input ->
        val builder = AnnotatedString.Builder(input)
        draft.fields.forEach { field ->
            if (field.start >= 0 && field.endExclusive <= input.length && field.start < field.endExclusive) {
                builder.addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline),
                    field.start,
                    field.endExclusive,
                )
            }
        }
        TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

private fun activeDraftField(draft: StructuredRecordDraft, selection: TextRange) =
    draft.fields.firstOrNull { field ->
        selection.start >= field.start && selection.end <= field.endExclusive &&
            (selection.start < field.endExclusive || selection.end > field.start)
    }

private fun selectWholeFieldWhenTapped(value: TextFieldValue, draft: StructuredRecordDraft): TextFieldValue {
    if (!value.selection.collapsed) return value
    val cursor = value.selection.start
    val field = draft.fields.firstOrNull { cursor >= it.start && cursor <= it.endExclusive }
        ?: return value
    return value.copy(selection = TextRange(field.start, field.endExclusive))
}

private fun TextRange.coerceTo(length: Int): TextRange = TextRange(
    start.coerceIn(0, length),
    end.coerceIn(0, length),
)
