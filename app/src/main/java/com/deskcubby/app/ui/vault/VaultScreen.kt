@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.repository.VaultItem
import com.deskcubby.app.data.repository.VaultLockState
import com.deskcubby.app.data.repository.MIN_VAULT_PASSWORD_CODE_POINTS
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

@Composable
fun VaultScreen(
    padding: PaddingValues,
    viewModel: VaultViewModel,
) {
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()

    when (lockState) {
        VaultLockState.NOT_SET -> VaultSetupContent(
            padding = padding,
            onSetup = viewModel::setupPassword,
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
    onSetup: (String) -> Unit,
) {
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
                    onValueChange = { password = it },
                    label = tr("密码", "Password"),
                    showPassword = showPassword,
                    onToggleVisibility = { showPassword = !showPassword },
                    isError = tooShort,
                    supportingText = if (tooShort) {
                        tr(
                            "至少 $MIN_VAULT_PASSWORD_CODE_POINTS 个 Unicode 字符，长度不限",
                            "At least $MIN_VAULT_PASSWORD_CODE_POINTS Unicode characters; no maximum",
                        )
                    } else {
                        null
                    },
                    imeAction = ImeAction.Next,
                )
                Spacer(Modifier.height(10.dp))
                VaultPasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
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
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { onSetup(password) },
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
                        VaultUiError.OPERATION_FAILED -> tr("操作失败", "Operation failed")
                        null -> null
                    },
                    imeAction = ImeAction.Done,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.unlock(password) },
                    enabled = password.isNotEmpty(),
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
    val items by viewModel.items.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val operationFailedLabel = tr("操作失败", "Operation failed")

    var showNewEditor by remember { mutableStateOf(false) }
    var editorItem by remember { mutableStateOf<VaultItem?>(null) }
    var showChangePassword by remember { mutableStateOf(false) }

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
        if (items.isEmpty()) {
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
                items(items, key = { it.id }) { item ->
                    VaultItemCard(item = item, onClick = { editorItem = item })
                }
            }
        }
    }

    if (showNewEditor) {
        VaultItemEditorDialog(
            item = null,
            onDismiss = { showNewEditor = false },
            onSave = { title, content ->
                viewModel.addItem(title, content) { ok -> if (ok) showNewEditor = false }
            },
            onDelete = null,
        )
    }

    editorItem?.let { item ->
        VaultItemEditorDialog(
            item = item,
            onDismiss = { editorItem = null },
            onSave = { title, content ->
                viewModel.updateItem(item.id, title, content) { ok -> if (ok) editorItem = null }
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
private fun VaultItemCard(
    item: VaultItem,
    onClick: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val timeText = remember(item.updatedAt, language) {
        val formatter = DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(if (language == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE)
        Instant.ofEpochMilli(item.updatedAt).atZone(ZoneId.systemDefault()).format(formatter)
    }

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 22.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                item.title.ifBlank { tr("（无标题）", "(Untitled)") },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                timeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun VaultItemEditorDialog(
    item: VaultItem?,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var content by remember(item?.id) { mutableStateOf(item?.content.orEmpty()) }

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
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tr("标题", "Title")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(tr("内容", "Content")) },
                    minLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() || content.isNotBlank(),
                onClick = { onSave(title, content) },
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
    val canSubmit = oldPassword.isNotEmpty() &&
        newPasswordIsValid &&
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
                    isError = error == VaultUiError.WRONG_PASSWORD,
                    supportingText = when (error) {
                        VaultUiError.WRONG_PASSWORD -> tr("旧密码错误", "Wrong old password")
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
                    supportingText = if (tooShort) {
                        tr(
                            "至少 $MIN_VAULT_PASSWORD_CODE_POINTS 个 Unicode 字符，长度不限",
                            "At least $MIN_VAULT_PASSWORD_CODE_POINTS Unicode characters; no maximum",
                        )
                    } else {
                        null
                    },
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
