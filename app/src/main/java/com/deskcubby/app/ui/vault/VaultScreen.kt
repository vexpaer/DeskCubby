@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.vault

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.repository.VaultItem
import com.deskcubby.app.data.repository.VaultLockState
import com.deskcubby.app.data.repository.isValidNewVaultPassword
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun VaultScreen(
    padding: PaddingValues,
    viewModel: VaultViewModel,
) {
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()

    when (lockState) {
        VaultLockState.NOT_SET -> VaultSetupContent(
            padding = padding,
            viewModel = viewModel,
        )

        VaultLockState.LOCKED -> VaultLockedContent(
            padding = padding,
            viewModel = viewModel,
        )

        VaultLockState.UNLOCKED -> VaultUnlockedContent(
            padding = padding,
            viewModel = viewModel,
        )
    }
}

// ---------------------------------------------------------------------------
// NOT_SET: first-time password setup
// ---------------------------------------------------------------------------

@Composable
private fun VaultSetupContent(
    padding: PaddingValues,
    viewModel: VaultViewModel,
) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val passwordIsValid = isValidNewVaultPassword(password)
    val tooShort = password.isNotEmpty() && !passwordIsValid
    val mismatch = confirm.isNotEmpty() && confirm != password
    val canSubmit = passwordIsValid && confirm == password

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassPanel(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            padding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    tr("设置收藏夹密码", "Set a vault password"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    tr(
                        "收藏夹中的内容会使用由密码派生的密钥在本机加密保存。" +
                            "请务必牢记密码：一旦丢失将无法找回，加密数据也无法解密。",
                        "Vault entries are encrypted on this device with a key derived from " +
                            "your password. Remember it carefully: a lost password cannot be " +
                            "recovered, and the encrypted data cannot be decrypted without it.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                VaultPasswordField(
                    value = password,
                    onValueChange = {
                        password = it
                        if (error != null) viewModel.consumeError()
                    },
                    label = tr("密码", "Password"),
                    showPassword = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    isError = tooShort,
                    supportingText = tr(
                        "至少 1 个 Unicode 码点，长度不限",
                        "At least 1 Unicode code point; no maximum",
                    ),
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(10.dp))
                VaultPasswordField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        if (error != null) viewModel.consumeError()
                    },
                    label = tr("确认密码", "Confirm password"),
                    showPassword = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        tr("两次输入不一致", "Passwords do not match")
                    } else {
                        null
                    },
                    imeAction = ImeAction.Done,
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = tr(
                            "密码设置失败，加密配置未启用，请重试",
                            "Password setup failed; encryption was not enabled. Please try again.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.setupPassword(password) },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("设置密码并启用", "Set password and enable"))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// LOCKED: unlock prompt
// ---------------------------------------------------------------------------

@Composable
private fun VaultLockedContent(
    padding: PaddingValues,
    viewModel: VaultViewModel,
) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlassPanel(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(),
            padding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    tr("收藏夹已锁定", "Vault is locked"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("输入密码以查看加密内容", "Enter your password to view encrypted entries"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                VaultPasswordField(
                    value = password,
                    onValueChange = {
                        password = it
                        if (error != null) viewModel.consumeError()
                    },
                    label = tr("密码", "Password"),
                    showPassword = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    isError = error == VaultUiError.WRONG_PASSWORD,
                    supportingText = when (error) {
                        VaultUiError.WRONG_PASSWORD -> tr("密码错误", "Wrong password")
                        VaultUiError.CORRUPTED_ITEMS -> tr(
                            "加密内容存在损坏",
                            "Some encrypted content is corrupted",
                        )
                        VaultUiError.OPERATION_FAILED -> tr("操作失败", "Operation failed")
                        null -> null
                    },
                    imeAction = ImeAction.Done,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.unlock(password) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("解锁", "Unlock"))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// UNLOCKED: entry list + editor dialogs
// ---------------------------------------------------------------------------

@Composable
private fun VaultUnlockedContent(
    padding: PaddingValues,
    viewModel: VaultViewModel,
) {
    val contentState by viewModel.contentState.collectAsStateWithLifecycle()
    val items = contentState.items
    val corruptedItemCount = contentState.corruptedItemCount
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val operationFailedLabel = tr("操作失败", "Operation failed")
    val copiedLabel = tr("已复制", "Copied")
    val copyFailedLabel = tr("复制失败", "Could not copy")
    val openLinkFailedLabel = tr("无法打开链接", "Could not open link")

    var showNewEditor by remember { mutableStateOf(false) }
    var editorItem by remember { mutableStateOf<VaultItem?>(null) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showNotes by rememberSaveable { mutableStateOf(false) }

    fun showFeedback(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun copyContent(content: String) {
        runCatching { clipboard.setText(AnnotatedString(content)) }
            .onSuccess { showFeedback(copiedLabel) }
            .onFailure { showFeedback(copyFailedLabel) }
    }

    fun openLink(url: String) {
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                },
            )
        }.isSuccess
        if (!opened) showFeedback(openLinkFailedLabel)
    }

    // While the change-password dialog is open, it renders errors itself.
    LaunchedEffect(error, showChangePassword) {
        if (error == VaultUiError.OPERATION_FAILED && !showChangePassword) {
            snackbarHostState.showSnackbar(operationFailedLabel)
            viewModel.consumeError()
        }
    }

    Scaffold(
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("收藏夹", "Vault")) },
                actions = {
                    val displayModeState = if (showNotes) {
                        tr("正在显示备注", "Showing notes")
                    } else {
                        tr("正在显示日期", "Showing dates")
                    }
                    IconToggleButton(
                        checked = showNotes,
                        onCheckedChange = { showNotes = it },
                        modifier = Modifier.semantics {
                            stateDescription = displayModeState
                        },
                    ) {
                        Icon(
                            imageVector = if (showNotes) Icons.Outlined.Notes else Icons.Outlined.CalendarMonth,
                            contentDescription = tr("切换备注或日期显示", "Toggle notes or dates"),
                        )
                    }
                    IconButton(onClick = { showChangePassword = true }) {
                        Icon(Icons.Outlined.Key, tr("修改密码", "Change password"))
                    }
                    IconButton(onClick = viewModel::lock) {
                        Icon(Icons.Outlined.Lock, tr("锁定", "Lock"))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewEditor = true }) {
                Icon(Icons.Outlined.Add, tr("新增条目", "Add entry"))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        if (items.isEmpty() && corruptedItemCount == 0) {
            AppEmptyState(
                icon = Icons.Outlined.Inventory2,
                title = tr("收藏夹还是空的", "Vault is empty"),
                description = tr(
                    "在这里保存的内容都会加密存储，只有解锁后才能查看",
                    "Everything saved here is stored encrypted and readable only after unlocking",
                ),
                actionLabel = tr("新增条目", "Add entry"),
                onAction = { showNewEditor = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (corruptedItemCount > 0) {
                    item {
                        VaultCorruptionNotice(corruptedItemCount)
                    }
                }
                items(items, key = { it.id }) { item ->
                    VaultItemCard(
                        item = item,
                        showNote = showNotes,
                        onCopy = { copyContent(item.content) },
                        onOpenLink = ::openLink,
                        onEdit = { editorItem = item },
                    )
                }
            }
        }
    }

    if (showNewEditor) {
        VaultItemEditorDialog(
            item = null,
            onDismiss = { showNewEditor = false },
            onSave = { content, note ->
                viewModel.addItem(content, note) { ok -> if (ok) showNewEditor = false }
            },
            onDelete = null,
        )
    }

    editorItem?.let { item ->
        VaultItemEditorDialog(
            item = item,
            onDismiss = { editorItem = null },
            onSave = { content, note ->
                viewModel.updateItem(item.id, content, note) { ok -> if (ok) editorItem = null }
            },
            onDelete = {
                viewModel.deleteItem(item.id)
                editorItem = null
            },
        )
    }

    if (showChangePassword) {
        VaultChangePasswordDialog(
            viewModel = viewModel,
            onDismiss = {
                viewModel.consumeError()
                showChangePassword = false
            },
        )
    }
}

@Composable
private fun VaultCorruptionNotice(corruptedItemCount: Int) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = tr(
                    "有 $corruptedItemCount 条加密内容无法读取；原始数据已保留，未显示任何内容片段。",
                    "$corruptedItemCount encrypted ${
                        if (corruptedItemCount == 1) "entry is" else "entries are"
                    } unreadable. The original data was kept and no content fragment is shown.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VaultItemCard(
    item: VaultItem,
    showNote: Boolean,
    onCopy: () -> Unit,
    onOpenLink: (String) -> Unit,
    onEdit: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val safeUrl = remember(item.content) { safeVaultHttpUrlOrNull(item.content) }
    val timeText = remember(item.updatedAt, language) {
        val formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(if (language == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
        Instant.ofEpochMilli(item.updatedAt).atZone(ZoneId.systemDefault()).format(formatter)
    }

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClickLabel = if (safeUrl != null) {
                    tr("在浏览器中打开链接", "Open link in browser")
                } else {
                    tr("复制内容", "Copy content")
                },
                onLongClickLabel = tr("编辑条目", "Edit entry"),
                onClick = {
                    if (safeUrl != null) onOpenLink(safeUrl) else onCopy()
                },
                onLongClick = onEdit,
            ),
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = item.content.ifBlank { tr("（空内容）", "(Empty content)") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (safeUrl != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (safeUrl != null) TextDecoration.Underline else TextDecoration.None,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = tr("复制内容", "Copy content"),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (showNote) item.note ?: tr("无备注", "No note") else timeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VaultItemEditorDialog(
    item: VaultItem?,
    onDismiss: () -> Unit,
    onSave: (content: String, note: String?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var content by remember(item?.id) { mutableStateOf(item?.content.orEmpty()) }
    var note by remember(item?.id) { mutableStateOf(item?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (item == null) tr("新增条目", "Add entry") else tr("编辑条目", "Edit entry"))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(tr("内容", "Content")) },
                    minLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(tr("备注（可选）", "Note (optional)")) },
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = { onSave(content, note.takeUnless(String::isBlank)) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text(tr("删除", "Delete"), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

@Composable
private fun VaultChangePasswordDialog(
    viewModel: VaultViewModel,
    onDismiss: () -> Unit,
) {
    val error by viewModel.error.collectAsStateWithLifecycle()
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val newPasswordIsValid = isValidNewVaultPassword(newPassword)
    val tooShort = newPassword.isNotEmpty() && !newPasswordIsValid
    val mismatch = confirm.isNotEmpty() && confirm != newPassword
    val canSubmit = newPasswordIsValid &&
        confirm == newPassword

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("修改密码", "Change password")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    tr(
                        "所有条目将使用新密码重新加密。新密码丢失后同样无法找回。",
                        "All entries will be re-encrypted with the new password. " +
                            "A lost password still cannot be recovered.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VaultPasswordField(
                    value = oldPassword,
                    onValueChange = {
                        oldPassword = it
                        if (error != null) viewModel.consumeError()
                    },
                    label = tr("旧密码", "Old password"),
                    showPassword = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    isError = error != null,
                    supportingText = when (error) {
                        VaultUiError.WRONG_PASSWORD -> tr("旧密码错误", "Wrong old password")
                        VaultUiError.CORRUPTED_ITEMS -> tr(
                            "存在无法读取的加密条目，密码未修改",
                            "Unreadable encrypted entries exist; the password was not changed",
                        )
                        VaultUiError.OPERATION_FAILED -> tr("操作失败", "Operation failed")
                        null -> null
                    },
                    imeAction = ImeAction.Next,
                )
                VaultPasswordField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = tr("新密码", "New password"),
                    showPassword = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    isError = tooShort,
                    supportingText = tr(
                        "至少 1 个 Unicode 码点，长度不限",
                        "At least 1 Unicode code point; no maximum",
                    ),
                    imeAction = ImeAction.Next,
                )
                VaultPasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = tr("确认新密码", "Confirm new password"),
                    showPassword = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        tr("两次输入不一致", "Passwords do not match")
                    } else {
                        null
                    },
                    imeAction = ImeAction.Done,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    viewModel.changePassword(oldPassword, newPassword) { ok ->
                        if (ok) onDismiss()
                    }
                },
            ) { Text(tr("确认修改", "Confirm")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) }
        },
    )
}

@Composable
private fun VaultPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showPassword: Boolean,
    onToggleVisibility: () -> Unit,
    isError: Boolean,
    supportingText: String?,
    imeAction: ImeAction,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (showPassword) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (showPassword) {
                        tr("隐藏密码", "Hide password")
                    } else {
                        tr("显示密码", "Show password")
                    },
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
