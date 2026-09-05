@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.deskcubby.app.agent.AgentApprovalRequest
import com.deskcubby.app.agent.AgentExecutionStatus
import com.deskcubby.app.agent.AgentExecutionUpdate
import com.deskcubby.app.agent.AgentRunUsage
import com.deskcubby.app.data.model.AgentDataSource
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.model.LayoutMode
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.repository.AiAttachmentKind
import com.deskcubby.app.data.repository.AiChatAttachment
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.app.data.repository.AiConversation
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.DcCard
import com.deskcubby.app.ui.components.DcDivider
import com.deskcubby.app.ui.components.DcListRow
import com.deskcubby.app.ui.components.DcSectionHeader
import com.deskcubby.app.ui.components.currentGutter
import com.deskcubby.app.ui.theme.DeskCubbyColors.hairline
import com.deskcubby.app.ui.theme.DeskCubbyRadius
import com.deskcubby.app.ui.theme.DeskCubbySpacing
import com.deskcubby.app.ui.theme.DeskCubbyType
import com.deskcubby.app.ui.components.ContextPanel
import com.deskcubby.app.ui.components.LocalLayoutMode
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.components.MarkdownText
import com.deskcubby.app.ui.theme.tr
import java.text.NumberFormat

@Composable
fun AiChatScreen(
    padding: PaddingValues,
    viewModel: AiChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenReview: () -> Unit,
    initialPrompt: String? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val approval by viewModel.pendingApproval.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val textConfigs = settings.aiConfigs.filter { it.type == AiModelType.TEXT && it.enabled }
    val selected = textConfigs.firstOrNull { it.id == settings.aiChatConfigId }
    val configured = selected?.supportsToolCalling == true &&
        selected.endpointUrl.isNotBlank() && selected.model.isNotBlank()
    var historyVisible by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AiConversation?>(null) }
    var deleteTarget by remember { mutableStateOf<AiConversation?>(null) }
    var configMenu by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.attachFiles(uris.map(Uri::toString))
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    // A Desk-provided initial prompt is sent once as the user's first message; the ViewModel's
    // consumed flag prevents rotation/recomposition from re-sending it.
    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            viewModel.submitInitialPrompt(initialPrompt)
        }
    }
    LaunchedEffect(state.transientMessage) {
        state.transientMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeTransientMessage()
        }
    }
    LaunchedEffect(state.messages.lastOrNull()?.id, state.executionUpdates.size, state.isSending) {
        val count = state.messages.size +
            (if (state.executionUpdates.isNotEmpty()) 1 else 0) +
            (if (state.isSending) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.activeConversationTitle.ifBlank { tr("DeskCubby Agent", "DeskCubby Agent") },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (state.activeConversationId == null) {
                                tr("按需调用工具，不预加载全文", "Tools on demand; no bulk content preload")
                            } else {
                                tr("会话自动保存，可选择云同步", "Auto-saved; optional cloud sync")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenReview) {
                        Icon(Icons.AutoMirrored.Outlined.FactCheck, tr("Agent Review", "Agent Review"))
                    }
                    IconButton(onClick = { historyVisible = true }) {
                        Icon(Icons.Outlined.History, tr("会话历史", "Conversation history"))
                    }
                    IconButton(
                        enabled = !state.isSending && !state.isPreparingAttachments,
                        onClick = viewModel::startNewConversation,
                    ) {
                        Icon(Icons.Outlined.AddComment, tr("新会话", "New conversation"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, tr("AI 设置", "AI settings"))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AgentComposer(
                value = state.draft,
                attachments = state.pendingAttachments,
                isRunning = state.isSending,
                isPreparing = state.isPreparingAttachments,
                configured = configured,
                fontSizeSp = settings.aiPageFontSizeSp,
                authorizedSourceCount = settings.agentEnabledSources.size,
                permissionModeLabel = agentPermissionModeLabel(settings.agentPermissionMode),
                onValueChange = viewModel::updateDraft,
                onPickFiles = {
                    picker.launch(
                        arrayOf(
                            "image/*",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "text/*",
                            "application/json",
                            "application/xml",
                        ),
                    )
                },
                onManageContext = viewModel::showContextManager,
                onPermissionMode = viewModel::showPermissionMode,
                onRemoveAttachment = viewModel::removePendingAttachment,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopAgent,
            )
        },
    ) { inner ->
        val layoutMode = LocalLayoutMode.current
        Box(Modifier.fillMaxSize().padding(inner)) {
        Column(Modifier.fillMaxSize()) {
            ModelSelector(
                configs = textConfigs,
                selectedId = settings.aiChatConfigId,
                expanded = configMenu,
                enabled = !state.isSending,
                onExpanded = { configMenu = it },
                onSelect = viewModel::selectConfiguration,
            )
            if (selected != null && !selected.supportsToolCalling) {
                CapabilityNotice(onOpenSettings)
            } else if (selected == null) {
                ConfigurationNotice(onOpenSettings)
            }
            if (state.messages.isEmpty() && !state.isSending) {
                AppEmptyState(
                    icon = Icons.Outlined.Psychology,
                    title = if (configured) tr("交给 Agent 一项任务", "Give the Agent a task")
                    else tr("先配置 Agent 模型", "Configure an Agent model first"),
                    description = if (configured) {
                        tr(
                            "Agent 会先检索再读取，只能访问“管理上下文”中授权的数据源；任何写入都会遵守审批模式并留下可撤回 Review。",
                            "The Agent searches before reading, can access only authorized sources, and records every mutation in Review with approval and Undo.",
                        )
                    } else {
                        tr(
                            "请选择支持 OpenAI-compatible 原生 tool calling 的文字模型配置。不会解析普通回复来偷偷执行工具。",
                            "Choose a text model with native OpenAI-compatible tool calling. Ordinary prose is never parsed to execute tools.",
                        )
                    },
                    actionLabel = if (configured) null else tr("打开 AI 设置", "Open AI settings"),
                    onAction = if (configured) null else onOpenSettings,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(
                        horizontal = currentGutter(),
                        vertical = DeskCubbySpacing.base,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DeskCubbySpacing.lg),
                ) {
                    items(state.messages, key = AiChatMessage::id) {
                        AgentMessageBubble(
                            message = it,
                            fontSizeSp = settings.aiPageFontSizeSp,
                            replyBoxWidthDp = settings.aiReplyBoxWidthDp,
                            headingSizesSp = settings.markdownHeadingSizesSp,
                        )
                    }
                    if (state.executionUpdates.isNotEmpty()) {
                        item("agent-execution") {
                            AgentExecutionPanel(
                                updates = state.executionUpdates,
                                usage = state.lastRunUsage,
                                running = state.isSending,
                            )
                        }
                    }
                    if (state.isSending && state.executionUpdates.isEmpty()) {
                        item("agent-thinking") { AgentThinkingBubble() }
                    }
                }
            }
        }
        if (layoutMode == LayoutMode.EXPANDED) {
            AiContextPanel(
                enabledSources = settings.agentEnabledSources,
                permissionMode = settings.agentPermissionMode,
                executionUpdates = state.executionUpdates,
                running = state.isSending,
                onOpenReview = onOpenReview,
                onOpenContext = viewModel::showContextManager,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        }
    }

    if (historyVisible) {
        ConversationHistoryDialog(
            conversations,
            state.activeConversationId,
            onDismiss = { historyVisible = false },
            onNew = { historyVisible = false; viewModel.startNewConversation() },
            onOpen = { historyVisible = false; viewModel.openConversation(it.id) },
            onRename = { historyVisible = false; renameTarget = it },
            onDelete = { historyVisible = false; deleteTarget = it },
        )
    }
    renameTarget?.let { conversation ->
        RenameConversationDialog(
            conversation,
            onDismiss = { renameTarget = null },
            onConfirm = { title -> renameTarget = null; viewModel.renameConversation(conversation.id, title) },
        )
    }
    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("删除这段会话？", "Delete this conversation?")) },
            text = {
                Text(
                    tr(
                        "“${conversation.title}”会从本机隐藏；启用 Agent 会话同步后，删除状态也会同步到其他设备。",
                        "“${conversation.title}” will be hidden locally. If Agent chat sync is enabled, the deletion also syncs to other devices.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; viewModel.deleteConversation(conversation.id) }) {
                    Text(tr("删除", "Delete"))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
    if (state.isContextManagerVisible) {
        AgentContextDialog(
            selected = settings.agentEnabledSources,
            enabled = !state.isSending,
            onToggle = viewModel::setSourceEnabled,
            onDismiss = viewModel::hideContextManager,
        )
    }
    if (state.isPermissionModeVisible) {
        AgentPermissionDialog(
            selected = settings.agentPermissionMode,
            onSelect = viewModel::setPermissionMode,
            onDismiss = viewModel::hidePermissionMode,
        )
    }
    approval?.let { request ->
        AgentApprovalDialog(
            request,
            onApprove = { viewModel.approveMutation(request.requestId) },
            onReject = { viewModel.rejectMutation(request.requestId) },
        )
    }
}

@Composable
private fun ModelSelector(
    configs: List<com.deskcubby.app.data.model.AiModelConfig>,
    selectedId: String?,
    expanded: Boolean,
    enabled: Boolean,
    onExpanded: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    if (configs.isEmpty()) return
    val selected = configs.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable(enabled) { onExpanded(true) },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("当前 Agent 模型", "Current Agent model"), style = MaterialTheme.typography.labelSmall)
                    Text(selected?.name ?: tr("请选择", "Select one"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selected?.supportsToolCalling == true) {
                    Icon(Icons.Outlined.CheckCircle, tr("支持工具调用", "Tool calling supported"), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        DropdownMenu(expanded, onDismissRequest = { onExpanded(false) }) {
            configs.forEach { config ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(config.name)
                            Text(
                                if (config.supportsToolCalling) tr("原生工具调用", "Native tool calling")
                                else tr("不支持 Agent 工具", "Agent tools unsupported"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onExpanded(false); onSelect(config.id) },
                )
            }
        }
    }
}

@Composable
private fun AgentComposer(
    value: String,
    attachments: List<AiChatAttachment>,
    isRunning: Boolean,
    isPreparing: Boolean,
    configured: Boolean,
    fontSizeSp: Float,
    authorizedSourceCount: Int,
    permissionModeLabel: String,
    onValueChange: (String) -> Unit,
    onPickFiles: () -> Unit,
    onManageContext: () -> Unit,
    onPermissionMode: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    val canSend = configured && (value.isNotBlank() || attachments.isNotEmpty()) && !isRunning && !isPreparing
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = scheme.hairline)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = DeskCubbySpacing.md,
                        vertical = DeskCubbySpacing.md,
                    ),
            ) {
            attachments.forEach { attachment ->
                AttachmentRow(attachment, enabled = !isRunning, onRemove = { onRemoveAttachment(attachment.uri) })
                Spacer(Modifier.height(DeskCubbySpacing.sm))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(
                    enabled = !isRunning && !isPreparing,
                    onClick = { menu = true },
                    modifier = Modifier.padding(bottom = DeskCubbySpacing.xxs),
                ) {
                    if (isPreparing) AppLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Apps, tr("Agent 工具与上下文", "Agent tools and context"))
                }
                Spacer(Modifier.width(DeskCubbySpacing.sm))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
                    placeholder = {
                        Text(
                            if (configured) tr("描述任务，Agent 会按需调用工具", "Describe a task; the Agent will use tools as needed")
                            else tr("请先配置支持工具调用的模型", "Configure a tool-capable model first"),
                        )
                    },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    shape = DeskCubbyRadius.field,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = scheme.hairline,
                        unfocusedContainerColor = scheme.surfaceContainer,
                        focusedContainerColor = scheme.surfaceContainer,
                    ),
                    trailingIcon = {
                        if (isRunning) {
                            IconButton(onClick = onStop) {
                                Icon(Icons.Outlined.StopCircle, tr("中止 Agent", "Stop Agent"), tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(enabled = canSend, onClick = onSend) {
                                Icon(Icons.AutoMirrored.Outlined.Send, tr("运行 Agent", "Run Agent"))
                            }
                        }
                    },
                )
            }
            }
        }
    }

    // The four-square entry is the Agent's control surface, so it earns a sheet with room for
    // descriptions and current state instead of three anonymous dropdown rows.
    if (menu) {
        ModalBottomSheet(
            onDismissRequest = { menu = false },
            containerColor = scheme.surfaceContainerLow,
            shape = DeskCubbyRadius.sheet,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(Modifier.padding(bottom = DeskCubbySpacing.xl)) {
                DcSectionHeader(
                    title = tr("Agent 工具与上下文", "Agent tools and context"),
                    modifier = Modifier.padding(horizontal = DeskCubbySpacing.base),
                )
                DcListRow(
                    title = tr("插入图片 / 文档", "Insert image / document"),
                    subtitle = tr("图片、PDF、Word、纯文本", "Images, PDF, Word, plain text"),
                    leading = {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        menu = false
                        onPickFiles()
                    },
                )
                DcListRow(
                    title = tr("管理上下文", "Manage context"),
                    subtitle = tr(
                        "已授权 $authorizedSourceCount 个数据源",
                        "$authorizedSourceCount source(s) authorized",
                    ),
                    leading = {
                        Icon(
                            Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        menu = false
                        onManageContext()
                    },
                )
                DcListRow(
                    title = tr("AI 权限模式", "AI permission mode"),
                    subtitle = permissionModeLabel,
                    leading = {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        menu = false
                        onPermissionMode()
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(attachment: AiChatAttachment, enabled: Boolean, onRemove: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (attachment.kind == AiAttachmentKind.IMAGE && attachment.uri.isNotBlank()) {
                AsyncImage(
                    model = Uri.parse(attachment.uri),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    if (attachment.kind == AiAttachmentKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.Description,
                    null,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(attachment.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (attachment.kind == AiAttachmentKind.DOCUMENT) {
                        tr("文档文字将作为不可信数据发送", "Document text is sent as untrusted data")
                    } else {
                        tr("图片将发送给当前模型", "Image is sent to the current model")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(enabled = enabled, onClick = onRemove) {
                Icon(Icons.Outlined.Close, tr("移除附件", "Remove attachment"))
            }
        }
    }
}

@Composable
private fun AgentMessageBubble(
    message: AiChatMessage,
    fontSizeSp: Float,
    replyBoxWidthDp: Float,
    headingSizesSp: List<Float>,
) {
    if (message.role == AiChatRole.CONTEXT) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Text(
                tr("旧版冻结上下文（只读兼容）", "Legacy frozen context (read-only compatibility)"),
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        return
    }
    val user = message.role == AiChatRole.USER
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        // Your own words are a compact filled bubble pushed to the reading edge; the Agent's
        // reply is plain typeset text on the canvas. Wrapping long Markdown in a tinted box
        // fights the content, so the container is only used where it carries meaning.
        Column(
            modifier = Modifier
                .widthIn(max = replyBoxWidthDp.dp)
                .fillMaxWidth(if (user) 0.88f else 1f),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        ) {
            if (!user) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(DeskCubbySpacing.sm))
                    Text(
                        text = tr("Agent", "Agent"),
                        style = DeskCubbyType.eyebrow,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(DeskCubbySpacing.sm))
            }
            Surface(
                modifier = if (user) Modifier else Modifier.fillMaxWidth(),
                shape = if (user) MaterialTheme.shapes.large else DeskCubbyRadius.control,
                color = if (user) scheme.primaryContainer else Color.Transparent,
                contentColor = if (user) scheme.onPrimaryContainer else scheme.onSurface,
            ) {
            Column(
                Modifier.padding(
                    horizontal = if (user) DeskCubbySpacing.base else 0.dp,
                    vertical = if (user) DeskCubbySpacing.md else 0.dp,
                ),
            ) {
                message.attachments.forEach { attachment ->
                    if (user) Spacer(Modifier.height(8.dp))
                    if (attachment.kind == AiAttachmentKind.IMAGE && attachment.uri.isNotBlank()) {
                        AsyncImage(
                            model = Uri.parse(attachment.uri),
                            contentDescription = attachment.displayName,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 340.dp).clip(MaterialTheme.shapes.medium),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (attachment.kind == AiAttachmentKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.Description,
                                null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(attachment.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (message.content.isNotBlank()) {
                    if (user) Spacer(Modifier.height(7.dp))
                    if (user) {
                        SelectionContainer {
                            Text(
                                message.content,
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
                            )
                        }
                    } else {
                        // Agent replies render Markdown (headings, lists, code, quotes, links).
                        MarkdownText(
                            markdown = message.content,
                            headingSizesSp = headingSizesSp,
                            baseTextSizeSp = fontSizeSp,
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun AgentExecutionPanel(
    updates: List<AgentExecutionUpdate>,
    usage: AgentRunUsage?,
    running: Boolean,
) {
    DcCard(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        contentPadding = PaddingValues(DeskCubbySpacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DeskCubbySpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    AppLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(DeskCubbySpacing.sm))
                Text(
                    text = if (running) tr("Agent 执行中", "Agent running")
                    else tr("执行记录", "Execution log"),
                    style = DeskCubbyType.eyebrow,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            updates.forEach { AgentExecutionRow(it) }
            usage?.let {
                DcDivider()
                AgentUsageRow(it)
            }
        }
    }
}

@Composable
private fun AgentExecutionRow(update: AgentExecutionUpdate) {
    var expanded by rememberSaveable(update.toolCallId) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(DeskCubbyRadius.md)
            .background(scheme.surfaceContainer)
            .clickable { expanded = !expanded }
            .padding(
                horizontal = DeskCubbySpacing.md,
                vertical = DeskCubbySpacing.md,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = statusIcon(update.status),
                contentDescription = null,
                tint = statusTint(update.status, scheme),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(DeskCubbySpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = update.title,
                    style = DeskCubbyType.listTitle,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (update.target.isNotBlank()) {
                    Text(
                        text = update.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) tr("收起", "Collapse") else tr("展开", "Expand"),
                tint = scheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(DeskCubbySpacing.md))
            DcDivider()
            Spacer(Modifier.height(DeskCubbySpacing.md))
            Text(
                text = update.toolName,
                style = DeskCubbyType.mono,
                color = scheme.onSurfaceVariant,
            )
            if (update.argumentsSummary.isNotBlank()) {
                Spacer(Modifier.height(DeskCubbySpacing.xxs))
                Text(
                    text = update.argumentsSummary,
                    style = DeskCubbyType.mono,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (update.resultSummary.isNotBlank()) {
                Spacer(Modifier.height(DeskCubbySpacing.sm))
                Text(
                    text = update.resultSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusIcon(status: AgentExecutionStatus) = when (status) {
    AgentExecutionStatus.PREPARING -> Icons.Outlined.HourglassEmpty
    AgentExecutionStatus.RUNNING -> Icons.Outlined.Autorenew
    AgentExecutionStatus.WAITING_APPROVAL -> Icons.Outlined.HelpOutline
    AgentExecutionStatus.APPROVED -> Icons.Outlined.Check
    AgentExecutionStatus.REJECTED -> Icons.Outlined.Close
    AgentExecutionStatus.SUCCEEDED -> Icons.Outlined.Check
    AgentExecutionStatus.FAILED -> Icons.Outlined.ErrorOutline
    AgentExecutionStatus.CANCELED -> Icons.Outlined.Cancel
}

private fun statusTint(status: AgentExecutionStatus, scheme: ColorScheme) = when (status) {
    AgentExecutionStatus.PREPARING,
    AgentExecutionStatus.RUNNING,
    AgentExecutionStatus.CANCELED,
    -> scheme.onSurfaceVariant
    AgentExecutionStatus.WAITING_APPROVAL -> scheme.tertiary
    AgentExecutionStatus.REJECTED,
    AgentExecutionStatus.FAILED,
    -> scheme.error
    AgentExecutionStatus.APPROVED,
    AgentExecutionStatus.SUCCEEDED,
    -> scheme.primary
}

@Composable
private fun AgentUsageRow(usage: AgentRunUsage) {
    val formatter = NumberFormat.getIntegerInstance()
    Text(
        if (usage.reportedCallCount == 0) {
            tr(
                "${usage.modelCallCount} 次模型调用 · Provider 未报告 Token",
                "${usage.modelCallCount} model calls · tokens not reported by provider",
            )
        } else {
            val total = if (usage.totalTokensReported) formatter.format(usage.totalTokens) else "—"
            val rate = usage.cacheRate?.let { String.format("%.1f%%", it * 100) } ?: "—"
            tr(
                "$total Token · 缓存率 $rate",
                "$total tokens · cache rate $rate",
            )
        },
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun AgentThinkingBubble() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
        Spacer(Modifier.width(DeskCubbySpacing.md))
        Text(
            text = tr("Agent 正在规划下一步…", "Agent is planning the next step…"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentContextDialog(
    selected: Set<AgentDataSource>,
    enabled: Boolean,
    onToggle: (AgentDataSource, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("管理上下文", "Manage context")) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                item {
                    Text(
                        tr(
                            "勾选表示授予检索和按需读取权限，不会把全部正文塞入 Prompt。取消授权后，Agent 工具立即无法访问该数据源。",
                            "A check grants search and on-demand read access; it does not inject all content into the prompt. Removing access blocks the source immediately.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(AgentDataSource.entries, key = AgentDataSource::name) { source ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled) { onToggle(source, source !in selected) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = source in selected,
                            onCheckedChange = if (enabled) ({ onToggle(source, it) }) else null,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(sourceLabel(source), fontWeight = FontWeight.Medium)
                            Text(sourceDescription(source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("完成", "Done")) } },
    )
}

@Composable
private fun sourceLabel(source: AgentDataSource): String = when (source) {
    AgentDataSource.DIARY -> tr("日记", "Diary")
    AgentDataSource.THOUGHTS -> tr("小巧思", "Thoughts")
    AgentDataSource.DATE_RECORDS -> tr("日期", "Dates")
    AgentDataSource.DAILY_EVENTS -> tr("结构化记录", "Structured records")
    AgentDataSource.NOTES -> tr("笔记", "Notes")
    AgentDataSource.POEMS -> tr("诗词本", "Poetry book")
    AgentDataSource.USAGE -> tr("手机使用时间", "Phone usage")
    AgentDataSource.STATISTICS -> tr("统计数据", "Statistics")
    AgentDataSource.APP_GUIDE -> tr("应用指南", "App guide")
}

@Composable
private fun sourceDescription(source: AgentDataSource): String = when (source) {
    AgentDataSource.DIARY -> tr("按日期检索与读取；允许创建、编辑、删除", "Search/read by date; create, edit, delete")
    AgentDataSource.THOUGHTS -> tr("正文、分类与时间", "Text, categories, and timestamps")
    AgentDataSource.DATE_RECORDS -> tr("重要日期名称与日期", "Important dates and names")
    AgentDataSource.DAILY_EVENTS -> tr("日常记录模板", "Daily-record templates")
    AgentDataSource.NOTES -> tr("已授权 SAF 笔记目录", "Authorized SAF notes tree")
    AgentDataSource.POEMS -> tr("收藏诗词与分类", "Saved poems and categories")
    AgentDataSource.USAGE -> tr("只读的按日/应用使用数据", "Read-only daily/app usage")
    AgentDataSource.STATISTICS -> tr("只读聚合统计", "Read-only aggregate statistics")
    AgentDataSource.APP_GUIDE -> tr("应用使用教学按章节索引；只读", "Read-only how-to guide indexed by section")
}

@Composable
private fun AgentPermissionDialog(
    selected: AgentPermissionMode,
    onSelect: (AgentPermissionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("AI 权限模式", "AI permission mode")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PermissionModeCard(
                    selected == AgentPermissionMode.REQUIRE_APPROVAL,
                    tr("需要批准", "Require approval"),
                    tr("读取无需确认；每一个创建、编辑、删除或设置修改都先显示预览。", "Reads run directly; every create, edit, delete, or setting change shows a preview first."),
                ) { onSelect(AgentPermissionMode.REQUIRE_APPROVAL) }
                PermissionModeCard(
                    selected == AgentPermissionMode.FULL_AUTO,
                    tr("全自动", "Full auto"),
                    tr("修改直接执行，但仍逐项写入 Review，并可在安全时 Undo。", "Mutations run directly but remain individually recorded in Review with Undo where safe."),
                ) { onSelect(AgentPermissionMode.FULL_AUTO) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) } },
    )
}

@Composable
private fun PermissionModeCard(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    DcCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        selected = selected,
        contentPadding = PaddingValues(DeskCubbySpacing.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Column(Modifier.padding(start = DeskCubbySpacing.sm)) {
                Text(
                    text = title,
                    style = DeskCubbyType.listTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(DeskCubbySpacing.xxxs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AgentApprovalDialog(
    request: AgentApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Outlined.Lock, null) },
        title = { Text(tr("Agent 请求修改", "Agent requests a change")) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                item { ReviewField(tr("工具", "Tool"), request.toolName) }
                item { ReviewField(tr("目标", "Target"), request.target.ifBlank { "—" }) }
                item { ReviewField(tr("计划", "Plan"), request.summary) }
                if (request.before.isNotBlank()) item { ReviewCodeBlock(tr("修改前", "Before"), request.before) }
                if (request.after.isNotBlank()) item { ReviewCodeBlock(tr("修改后", "After"), request.after) }
            }
        },
        confirmButton = { TextButton(onClick = onApprove) { Text(tr("批准", "Approve")) } },
        dismissButton = { TextButton(onClick = onReject) { Text(tr("拒绝", "Reject")) } },
    )
}

@Composable
private fun ReviewField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ReviewCodeBlock(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = DeskCubbyRadius.md, color = MaterialTheme.colorScheme.surfaceContainer) {
        SelectionContainer {
            Text(
                value.take(256 * 1024),
                modifier = Modifier.fillMaxWidth().padding(DeskCubbySpacing.md),
                style = DeskCubbyType.mono,
            )
        }
    }
}

@Composable
private fun ConversationHistoryDialog(
    conversations: List<AiConversation>,
    activeId: Long?,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (AiConversation) -> Unit,
    onRename: (AiConversation) -> Unit,
    onDelete: (AiConversation) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("会话历史", "Conversation history")) },
        text = {
            if (conversations.isEmpty()) Text(tr("还没有保存的会话。", "No saved conversations yet."))
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversations, key = AiConversation::id) { conversation ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(conversation) },
                        color = if (conversation.id == activeId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (conversation.id == activeId) tr("当前会话", "Current") else tr("点击继续", "Tap to continue"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            IconButton(onClick = { onRename(conversation) }) { Icon(Icons.Outlined.Edit, tr("重命名", "Rename")) }
                            IconButton(onClick = { onDelete(conversation) }) { Icon(Icons.Outlined.Delete, tr("删除", "Delete")) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text(tr("新会话", "New conversation")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) } },
    )
}

@Composable
private fun RenameConversationDialog(
    conversation: AiConversation,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by rememberSaveable(conversation.id) { mutableStateOf(conversation.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("重命名会话", "Rename conversation")) },
        text = { OutlinedTextField(title, { title = it.take(80) }, singleLine = true) },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title) }) { Text(tr("保存", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun ConfigurationNotice(onOpenSettings: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.errorContainer)
            .padding(
                horizontal = currentGutter(),
                vertical = DeskCubbySpacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = scheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(DeskCubbySpacing.md))
        Text(
            text = tr("请选择可用的文字模型", "Select an available text model"),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSettings) { Text(tr("设置", "Settings")) }
    }
}

@Composable
private fun AiContextPanel(
    enabledSources: Set<AgentDataSource>,
    permissionMode: AgentPermissionMode,
    executionUpdates: List<AgentExecutionUpdate>,
    running: Boolean,
    onOpenReview: () -> Unit,
    onOpenContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContextPanel(
        modifier = modifier.fillMaxHeight(),
        content = {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(tr("上下文", "Context"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                enabledSources.forEach { source ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(DeskCubbySpacing.md))
                        Text(
                            text = agentDataSourceLabel(source),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenContext, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("管理数据源", "Manage sources"))
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(tr("Agent 活动", "Agent activity"), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (executionUpdates.isEmpty() && !running) {
                    Text(
                        tr("暂无活动", "No activity"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    executionUpdates.takeLast(6).forEach { update ->
                        Text(
                            update.toolName + " · " + agentExecutionStatusLabel(update.status),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    tr("审批模式", "Approval") + " · " + agentPermissionModeLabel(permissionMode),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenReview, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("打开 Review", "Open Review"))
                }
                Spacer(Modifier.height(12.dp))
            }
        },
    )
}

@Composable
private fun agentDataSourceLabel(source: AgentDataSource): String = when (source) {
    AgentDataSource.DIARY -> tr("日记", "Diary")
    AgentDataSource.THOUGHTS -> tr("小巧思", "Thoughts")
    AgentDataSource.DATE_RECORDS -> tr("日期记录", "Date records")
    AgentDataSource.DAILY_EVENTS -> tr("结构化记录", "Structured records")
    AgentDataSource.NOTES -> tr("笔记", "Notes")
    AgentDataSource.POEMS -> tr("诗词", "Poems")
    AgentDataSource.USAGE -> tr("使用时间", "Screen time")
    AgentDataSource.STATISTICS -> tr("统计", "Statistics")
    AgentDataSource.APP_GUIDE -> tr("应用指南", "App guide")
}

@Composable
private fun agentPermissionModeLabel(mode: AgentPermissionMode): String = when (mode) {
    AgentPermissionMode.REQUIRE_APPROVAL -> tr("需要批准", "Requires approval")
    AgentPermissionMode.FULL_AUTO -> tr("全自动", "Fully automatic")
}

@Composable
private fun agentExecutionStatusLabel(status: AgentExecutionStatus): String = when (status) {
    AgentExecutionStatus.PREPARING -> tr("准备中", "Preparing")
    AgentExecutionStatus.RUNNING -> tr("执行中", "Running")
    AgentExecutionStatus.WAITING_APPROVAL -> tr("待批准", "Needs approval")
    AgentExecutionStatus.APPROVED -> tr("已批准", "Approved")
    AgentExecutionStatus.REJECTED -> tr("已拒绝", "Rejected")
    AgentExecutionStatus.SUCCEEDED -> tr("成功", "Done")
    AgentExecutionStatus.FAILED -> tr("失败", "Failed")
    AgentExecutionStatus.CANCELED -> tr("已取消", "Canceled")
}


@Composable
private fun CapabilityNotice(onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null)
            Spacer(Modifier.width(10.dp))
            Text(
                tr("该 Provider 未启用原生工具调用，Agent 不会运行。", "Native tool calling is disabled for this provider; Agent will not run."),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text(tr("检查", "Review")) }
        }
    }
}
