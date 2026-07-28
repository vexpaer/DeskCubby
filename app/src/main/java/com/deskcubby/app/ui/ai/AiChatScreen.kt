@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.ai

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.repository.AiChatImage
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.repository.AiChatRole
import com.deskcubby.app.data.repository.AiConversation
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.theme.tr

@Composable
fun AiChatScreen(
    padding: PaddingValues,
    viewModel: AiChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val textConfigs = settings.aiConfigs.filter { it.type == AiModelType.TEXT }
    val selectedConfig = textConfigs.firstOrNull { it.id == settings.aiChatConfigId }
    val configured = selectedConfig != null ||
        settings.aiModel.isNotBlank() && settings.aiEndpointUrl.isNotBlank()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AiConversation?>(null) }
    var deleteTarget by remember { mutableStateOf<AiConversation?>(null) }
    var configMenuExpanded by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.attachImage(it.toString()) }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(uiState.messages.lastOrNull()?.id, uiState.isSending) {
        val itemCount = uiState.messages.size + if (uiState.isSending) 1 else 0
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Scaffold(
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            uiState.activeConversationTitle.ifBlank { tr("AI 聊天", "AI chat") },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (uiState.activeConversationId != null) {
                            Text(
                                tr("对话会自动保存", "Conversation saved automatically"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Outlined.History, tr("对话历史", "Conversation history"))
                    }
                    IconButton(
                        enabled = !uiState.isSending && !uiState.isPreparingImage,
                        onClick = viewModel::startNewConversation,
                    ) {
                        Icon(Icons.Outlined.AddComment, tr("新对话", "New conversation"))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, tr("AI 设置", "AI settings"))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatComposer(
                value = uiState.draft,
                image = uiState.pendingImage,
                contextCount = uiState.pendingContextKeys.size,
                isSending = uiState.isSending,
                isPreparingImage = uiState.isPreparingImage,
                configured = configured,
                onValueChange = viewModel::updateDraft,
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                onPickDiaryContext = viewModel::openDiaryContextPicker,
                onPickThoughtContext = viewModel::openThoughtContextPicker,
                onRemoveImage = viewModel::removePendingImage,
                onClearContext = viewModel::clearPendingContexts,
                onSend = viewModel::sendMessage,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (textConfigs.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !uiState.isSending) { configMenuExpanded = true },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tr("当前文字模型", "Current text model"), style = MaterialTheme.typography.labelSmall)
                                Text(selectedConfig?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = configMenuExpanded,
                        onDismissRequest = { configMenuExpanded = false },
                    ) {
                        textConfigs.forEach { config ->
                            DropdownMenuItem(
                                text = { Text(config.name) },
                                onClick = {
                                    configMenuExpanded = false
                                    viewModel.selectConfiguration(config.id)
                                },
                            )
                        }
                    }
                }
            }
            if (!configured) ConfigurationNotice(onOpenSettings)

            if (uiState.messages.isEmpty() && !uiState.isSending) {
                AppEmptyState(
                    icon = Icons.Outlined.Psychology,
                    title = if (configured) {
                        tr("开始一段对话", "Start a conversation")
                    } else {
                        tr("先完成 AI 配置", "Configure AI first")
                    },
                    description = if (configured) {
                        tr(
                            "可导入日记等本机记录作为上下文；文字、图片、上下文快照和 AI 回答会随对话保存在本机。",
                            "You can import local records such as diaries as context. Messages, images, frozen context, and AI replies are saved locally with the conversation.",
                        )
                    } else {
                        tr(
                            "请填写接口地址和模型名称，然后回到这里开始聊天。API 密钥可以留空。",
                            "Set an endpoint and model, then return here to chat. The API key may be left empty.",
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
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.messages, key = AiChatMessage::id) { message ->
                        ChatMessageBubble(message)
                    }
                    if (uiState.isSending) item(key = "ai-typing") { TypingBubble() }
                }
            }
        }
    }

    if (showHistory) {
        ConversationHistoryDialog(
            conversations = conversations,
            activeConversationId = uiState.activeConversationId,
            onDismiss = { showHistory = false },
            onNew = {
                showHistory = false
                viewModel.startNewConversation()
            },
            onOpen = { conversation ->
                showHistory = false
                viewModel.openConversation(conversation.id)
            },
            onRename = {
                showHistory = false
                renameTarget = it
            },
            onDelete = {
                showHistory = false
                deleteTarget = it
            },
        )
    }
    renameTarget?.let { conversation ->
        RenameConversationDialog(
            conversation = conversation,
            onDismiss = { renameTarget = null },
            onConfirm = { title ->
                renameTarget = null
                viewModel.renameConversation(conversation.id, title)
            },
        )
    }
    deleteTarget?.let { conversation ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(tr("删除这段对话？", "Delete this conversation?")) },
            text = {
                Text(
                    tr(
                        "“${conversation.title}”及其中的全部消息将从本机永久删除。",
                        "\"${conversation.title}\" and all its messages will be permanently deleted from this device.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        viewModel.deleteConversation(conversation.id)
                    },
                ) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }
    val contextPickerSource = uiState.contextPickerSource
    if (uiState.isContextPickerVisible && contextPickerSource != null) {
        AiContextPickerDialog(
            source = contextPickerSource,
            candidates = uiState.contextCandidates,
            selectedKeys = uiState.pendingContextKeys,
            errorMessage = uiState.contextPickerErrorMessage,
            isLoading = uiState.isLoadingContextCandidates,
            isLoadingPreview = uiState.isLoadingContextPreview,
            preview = uiState.contextPreview,
            onDismiss = viewModel::closeContextPicker,
            onRefresh = viewModel::refreshContextCandidates,
            onToggle = viewModel::toggleContextCandidate,
            onToggleGroup = viewModel::toggleContextGroup,
            onPreview = viewModel::previewContextCandidate,
            onDismissPreview = viewModel::dismissContextPreview,
        )
    }
}

@Composable
private fun ConversationHistoryDialog(
    conversations: List<AiConversation>,
    activeConversationId: Long?,
    onDismiss: () -> Unit,
    onNew: () -> Unit,
    onOpen: (AiConversation) -> Unit,
    onRename: (AiConversation) -> Unit,
    onDelete: (AiConversation) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("对话历史", "Conversation history")) },
        text = {
            if (conversations.isEmpty()) {
                Text(tr("还没有保存的对话。", "No saved conversations yet."))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(conversations, key = AiConversation::id) { conversation ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(conversation) },
                            color = if (conversation.id == activeConversationId) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        conversation.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (conversation.id == activeConversationId) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    )
                                    Text(
                                        if (conversation.id == activeConversationId) {
                                            tr("当前对话", "Current conversation")
                                        } else {
                                            tr("点击继续", "Tap to continue")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onRename(conversation) }) {
                                    Icon(Icons.Outlined.Edit, tr("重命名", "Rename"))
                                }
                                IconButton(onClick = { onDelete(conversation) }) {
                                    Icon(Icons.Outlined.Delete, tr("删除", "Delete"))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNew) { Text(tr("新对话", "New conversation")) } },
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
        title = { Text(tr("重命名对话", "Rename conversation")) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(80) },
                singleLine = true,
                label = { Text(tr("标题", "Title")) },
            )
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title) }) {
                Text(tr("保存", "Save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun ConfigurationNotice(onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                tr("请先填写接口地址和模型名称", "Set an endpoint and model first"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onOpenSettings) { Text(tr("去设置", "Settings")) }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: AiChatMessage) {
    if (message.role == AiChatRole.CONTEXT) {
        ContextMessageCard(message.id, message.content)
        return
    }
    val isUser = message.role == AiChatRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(0.9f),
            shape = MaterialTheme.shapes.large,
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            contentColor = if (isUser) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            tonalElevation = if (isUser) 1.dp else 2.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isUser) Icons.Outlined.Person else Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isUser) tr("我", "You") else tr("AI", "AI"),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                message.image?.let { image ->
                    Spacer(Modifier.height(9.dp))
                    AsyncImage(
                        model = Uri.parse(image.uri),
                        contentDescription = tr("已上传图片", "Uploaded image"),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 360.dp)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (message.reasoning.isNotBlank()) {
                    Spacer(Modifier.height(9.dp))
                    ReasoningPanel(message.id, message.reasoning)
                }
                Spacer(Modifier.height(7.dp))
                SelectionContainer {
                    Text(
                        message.content.ifBlank {
                            tr("模型未返回最终回答。", "The model did not return a final answer.")
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningPanel(messageId: Long, reasoning: String) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Psychology, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    tr("模型返回的思考过程", "Model-provided reasoning"),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) tr("收起", "Collapse") else tr("展开", "Expand"),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(reasoning, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TypingBubble() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppLoadingIndicator(size = 22.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("正在思考…", "Thinking…"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatComposer(
    value: String,
    image: AiChatImage?,
    contextCount: Int,
    isSending: Boolean,
    isPreparingImage: Boolean,
    configured: Boolean,
    onValueChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onPickDiaryContext: () -> Unit,
    onPickThoughtContext: () -> Unit,
    onRemoveImage: () -> Unit,
    onClearContext: () -> Unit,
    onSend: () -> Unit,
) {
    val canSend = configured && (value.isNotBlank() || image != null) &&
        !isSending && !isPreparingImage
    var addMenuExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isSending, isPreparingImage) {
        if (isSending || isPreparingImage) addMenuExpanded = false
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (contextCount > 0) {
                PendingContextSummary(
                    count = contextCount,
                    onClear = onClearContext,
                )
                Spacer(Modifier.height(8.dp))
            }
            image?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = Uri.parse(it.uri),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Outlined.Image, null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            tr(
                                "图片将发送给当前模型",
                                "Image will be sent to the current model",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(enabled = !isSending, onClick = onRemoveImage) {
                            Icon(Icons.Outlined.Close, tr("移除图片", "Remove image"))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(
                        enabled = !isSending && !isPreparingImage,
                        onClick = { addMenuExpanded = true },
                    ) {
                        if (isPreparingImage) {
                            AppLoadingIndicator(size = 22.dp, strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Outlined.Add,
                                tr("添加媒体或上下文", "Add media or context"),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = addMenuExpanded,
                        onDismissRequest = { addMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(tr("选择图片", "Choose image")) },
                            leadingIcon = { Icon(Icons.Outlined.Image, null) },
                            onClick = {
                                addMenuExpanded = false
                                onPickImage()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("导入日记上下文", "Import diary context")) },
                            leadingIcon = { Icon(Icons.Outlined.Article, null) },
                            onClick = {
                                addMenuExpanded = false
                                onPickDiaryContext()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("导入小巧思上下文", "Import thought context")) },
                            leadingIcon = { Icon(Icons.Outlined.Lightbulb, null) },
                            onClick = {
                                addMenuExpanded = false
                                onPickThoughtContext()
                            },
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (configured) {
                                tr(
                                    "输入消息，可附带上下文或一张图片",
                                    "Message, with optional context or one image",
                                )
                            } else {
                                tr("请先完成 AI 配置", "Configure AI first")
                            },
                        )
                    },
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    shape = MaterialTheme.shapes.large,
                    trailingIcon = {
                        IconButton(
                            enabled = canSend,
                            onClick = onSend,
                        ) {
                            Icon(
                                Icons.Outlined.Send,
                                contentDescription = tr("发送", "Send"),
                            )
                        }
                    },
                )
            }
        }
    }
}
