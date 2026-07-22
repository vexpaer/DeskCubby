@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.ai

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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.repository.AiChatMessage
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.repository.AiChatRole
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val textConfigs = settings.aiConfigs.filter { it.type == AiModelType.TEXT }
    val selectedConfig = textConfigs.firstOrNull { it.id == settings.aiChatConfigId }
    val configured = selectedConfig != null || settings.aiModel.isNotBlank() && settings.aiEndpointUrl.isNotBlank()
    var showClearConfirmation by remember { mutableStateOf(false) }
    var configMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(uiState.messages.size, uiState.isSending) {
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
                title = { Text(tr("AI 聊天", "AI chat")) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, tr("AI 设置", "AI settings"))
                    }
                    IconButton(
                        enabled = uiState.messages.isNotEmpty() || uiState.isSending,
                        onClick = { showClearConfirmation = true },
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, tr("清空对话", "Clear conversation"))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ChatComposer(
                value = uiState.draft,
                isSending = uiState.isSending,
                configured = configured,
                onValueChange = viewModel::updateDraft,
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
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !uiState.isSending) { configMenuExpanded = true },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tr("当前文字模型", "Current text model"), style = MaterialTheme.typography.labelSmall)
                                Text(selectedConfig?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    DropdownMenu(expanded = configMenuExpanded, onDismissRequest = { configMenuExpanded = false }) {
                        textConfigs.forEach { config -> DropdownMenuItem(
                            text = { Text(config.name) },
                            onClick = { configMenuExpanded = false; viewModel.selectConfiguration(config.id) },
                        ) }
                    }
                }
            }
            if (!configured) {
                ConfigurationNotice(onOpenSettings = onOpenSettings)
            }

            if (uiState.messages.isEmpty() && !uiState.isSending) {
                AppEmptyState(
                    icon = Icons.Outlined.SmartToy,
                    title = if (configured) {
                        tr("开始一段对话", "Start a conversation")
                    } else {
                        tr("先完成 AI 配置", "Configure AI first")
                    },
                    description = if (configured) {
                        tr(
                            "消息只保留在本次应用运行期间。API 密钥会以明文随所选配置保存。",
                            "Messages stay in memory for this app session. The API key is stored as plain text with the selected configuration.",
                        )
                    } else {
                        tr(
                            "请填写接口地址和模型名称，然后回到这里开始聊天。API 密钥可以留空。",
                            "Set an endpoint and model, then return here to chat. The API key may be left empty.",
                        )
                    },
                    actionLabel = if (configured) null else tr("打开 AI 设置", "Open AI settings"),
                    onAction = if (configured) null else onOpenSettings,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(uiState.messages, key = AiChatMessage::id) { message ->
                        ChatMessageBubble(message)
                    }
                    if (uiState.isSending) {
                        item(key = "ai-typing") { TypingBubble() }
                    }
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(tr("清空当前对话？", "Clear this conversation?")) },
            text = {
                Text(
                    tr(
                        "本次会话中的全部消息将被移除，正在进行的请求也会取消。",
                        "All messages in this session will be removed, and any active request will be cancelled.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        viewModel.clearConversation()
                    },
                ) { Text(tr("清空", "Clear")) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(tr("取消", "Cancel"))
                }
            },
        )
    }
}

@Composable
private fun ConfigurationNotice(onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
            TextButton(onClick = onOpenSettings) {
                Text(tr("去设置", "Settings"))
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(message: AiChatMessage) {
    val isUser = message.role == AiChatRole.USER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth(0.9f),
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
                Spacer(Modifier.height(7.dp))
                SelectionContainer {
                    Text(
                        message.content,
                        style = MaterialTheme.typography.bodyLarge,
                    )
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
    isSending: Boolean,
    configured: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = configured && value.isNotBlank() && !isSending
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (configured) tr("输入消息", "Message")
                        else tr("请先完成 AI 配置", "Configure AI first"),
                    )
                },
                minLines = 1,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (canSend) onSend() },
                ),
                shape = MaterialTheme.shapes.large,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = canSend,
                onClick = onSend,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Icon(
                    Icons.Outlined.Send,
                    contentDescription = tr("发送", "Send"),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}
