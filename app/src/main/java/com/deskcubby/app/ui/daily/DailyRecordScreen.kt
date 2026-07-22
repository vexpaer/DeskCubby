@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.daily

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr

@Composable
fun DailyRecordScreen(
    padding: PaddingValues,
    viewModel: DailyRecordViewModel,
    onBack: (() -> Unit)? = null,
    onRecordToCurrentDiary: ((String, (Boolean) -> Unit) -> Unit)? = null,
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val sendingTemplateIds by viewModel.sendingTemplateIds.collectAsStateWithLifecycle()
    val templateOperationInProgress by viewModel.templateOperationInProgress.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var editorTemplate by remember { mutableStateOf<DailyEventTemplate?>(null) }
    var showNewEditor by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DailyEventTemplate?>(null) }
    var localFeedback by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var locallyRecordedTemplateId by remember { mutableStateOf<String?>(null) }
    var locallySendingTemplateIds by remember { mutableStateOf(emptySet<String>()) }
    var localFeedbackKey by remember { mutableStateOf(0L) }
    val currentDiarySuccessPrefix = tr("已添加到当前日记：", "Added to the current diary: ")
    val currentDiaryUnavailable = tr(
        "未能写入当前日记，请返回编辑器查看保存状态后重试。",
        "Could not save to the current diary. Return to the editor, check its save status, and retry.",
    )

    LaunchedEffect(feedback?.key) {
        feedback?.let { current ->
            snackbarHostState.showSnackbar(current.message)
            viewModel.consumeFeedback(current.key)
        }
    }
    LaunchedEffect(localFeedback?.first) {
        localFeedback?.let { current ->
            snackbarHostState.showSnackbar(current.second)
            if (localFeedback == current) localFeedback = null
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
                title = { Text(tr("日常记录", "Daily records")) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (templates.isEmpty()) {
                item(key = "empty") {
                    EmptyDailyEvents()
                }
            }

            items(templates, key = DailyEventTemplate::id) { template ->
                DailyEventRecorder(
                    template = template,
                    isSending = template.id in sendingTemplateIds || template.id in locallySendingTemplateIds,
                    clearInputsKey = feedback
                        ?.takeIf { !it.isError && it.recordedTemplateId == template.id }
                        ?.key ?: localFeedbackKey.takeIf { locallyRecordedTemplateId == template.id },
                    recordContentDescription = onRecordToCurrentDiary?.let {
                        tr(
                            "添加 ${template.text} 到当前日记",
                            "Add ${template.text} to the current diary",
                        )
                    },
                    onRecord = { entry ->
                        if (onRecordToCurrentDiary == null) {
                            viewModel.record(template, entry)
                        } else if (template.id !in locallySendingTemplateIds) {
                            locallySendingTemplateIds += template.id
                            onRecordToCurrentDiary(entry) { success ->
                                locallySendingTemplateIds -= template.id
                                localFeedbackKey += 1
                                locallyRecordedTemplateId = template.id.takeIf { success }
                                localFeedback = localFeedbackKey to if (success) {
                                    currentDiarySuccessPrefix + entry
                                } else {
                                    currentDiaryUnavailable
                                }
                            }
                        }
                    },
                    onEdit = { editorTemplate = template },
                    onDelete = { pendingDelete = template },
                )
            }

            item(key = "add") {
                Button(
                    onClick = { showNewEditor = true },
                    enabled = !templateOperationInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("新增日常事件", "Add daily event"))
                }
            }
        }
    }

    if (showNewEditor) {
        DailyEventEditorDialog(
            template = null,
            onDismiss = { showNewEditor = false },
            onConfirm = { text ->
                viewModel.addTemplate(text, "", "")
                showNewEditor = false
            },
        )
    }

    editorTemplate?.let { template ->
        DailyEventEditorDialog(
            template = template,
            onDismiss = { editorTemplate = null },
            onConfirm = { text ->
                viewModel.updateTemplate(template.id, text, "", "")
                editorTemplate = null
            },
        )
    }

    pendingDelete?.let { template ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("删除日常事件？", "Delete daily event?")) },
            text = {
                Text(
                    tr(
                        "将删除“${template.text}”，此操作无法撤销。",
                        "\u201c${template.text}\u201d will be deleted. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !templateOperationInProgress,
                    onClick = {
                        viewModel.removeTemplate(template.id)
                        pendingDelete = null
                    },
                ) {
                    Text(tr("删除", "Delete"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun EmptyDailyEvents() {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.EventNote,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                tr("还没有日常事件", "No daily events yet"),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                tr(
                    "添加一句常用记录；需要替换的位置可以写成 xx。",
                    "Add a reusable sentence; write xx wherever a value should be replaced.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A reusable event recorder card. Home can render this component without the management actions by
 * leaving [onEdit] and [onDelete] null.
 */
@Composable
fun DailyEventRecorder(
    template: DailyEventTemplate,
    onRecord: (sentence: String) -> Unit,
    modifier: Modifier = Modifier,
    isSending: Boolean = false,
    clearInputsKey: Any? = null,
    recordContentDescription: String? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var sentence by rememberSaveable(template.id, template.text, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(template.text))
    }
    val placeholderColor = MaterialTheme.colorScheme.primary
    val xxTransformation = remember(placeholderColor) {
        XxUnderlineVisualTransformation(placeholderColor)
    }

    LaunchedEffect(clearInputsKey) {
        if (clearInputsKey != null) {
            sentence = TextFieldValue(template.text)
        }
    }
    fun submit() {
        val completeSentence = sentence.text.trim()
        if (!isSending && completeSentence.isNotEmpty()) onRecord(completeSentence)
    }

    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sentence,
                    onValueChange = { candidate ->
                        if (candidate.text.length <= MAX_DAILY_EVENT_TEXT_LENGTH) {
                            sentence = selectTappedPlaceholder(previous = sentence, candidate = candidate)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isSending,
                    visualTransformation = xxTransformation,
                    minLines = 1,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }, onDone = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = ::submit,
                    enabled = !isSending && sentence.text.isNotBlank(),
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Send,
                            recordContentDescription ?: tr(
                                "添加 ${template.text} 到今日日记",
                                "Add ${template.text} to today's diary",
                            ),
                        )
                    }
                }
            }
            if (onEdit != null || onDelete != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    onEdit?.let { edit -> IconButton(onClick = edit, enabled = !isSending) {
                        Icon(Icons.Outlined.Edit, tr("编辑 ${template.text}", "Edit ${template.text}"))
                    } }
                    onDelete?.let { delete -> IconButton(onClick = delete, enabled = !isSending) {
                        Icon(Icons.Outlined.Delete, tr("删除 ${template.text}", "Delete ${template.text}"))
                    } }
                }
            }
        }
    }
}

internal fun selectTappedPlaceholder(previous: TextFieldValue, candidate: TextFieldValue): TextFieldValue {
    if (candidate.text != previous.text || !candidate.selection.collapsed) return candidate
    val cursor = candidate.selection.start
    val match = DAILY_XX_PATTERN.findAll(candidate.text).firstOrNull {
        cursor in it.range.first..(it.range.last + 1)
    } ?: return candidate
    return candidate.copy(selection = TextRange(match.range.first, match.range.last + 1))
}

private class XxUnderlineVisualTransformation(private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        DAILY_XX_PATTERN.findAll(text.text).forEach { match ->
            builder.addStyle(
                SpanStyle(color = color, textDecoration = TextDecoration.Underline),
                match.range.first,
                match.range.last + 1,
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

private val DAILY_XX_PATTERN = Regex("xx", RegexOption.IGNORE_CASE)

@Composable
private fun DailyEventEditorDialog(
    template: DailyEventTemplate?,
    onDismiss: () -> Unit,
    onConfirm: (text: String) -> Unit,
) {
    var text by rememberSaveable(template?.id) { mutableStateOf(template?.text.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (template == null) tr("新增日常事件", "Add daily event")
                else tr("编辑日常事件", "Edit daily event"),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_DAILY_EVENT_TEXT_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("事件文字", "Event text")) },
                    placeholder = { Text(tr("例如：喝水 xx 杯", "e.g. Drink xx glasses")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Text(
                    tr(
                        "提示：xx 是可替换符号，记录时点击下划线处即可直接改写。",
                        "Tip: xx is replaceable. Tap its underline when recording to edit it directly.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text) },
            ) {
                Text(tr("保存", "Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("取消", "Cancel"))
            }
        },
    )
}
