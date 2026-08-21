package com.deskcubby.app.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.export.DiaryZipRestoreResult
import com.deskcubby.app.data.export.ExportSelection
import com.deskcubby.app.data.export.ZipExportResult
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr

private data class ExportOption(
    val key: String,
    val labelZh: String,
    val labelEn: String,
)

private val EXPORT_OPTIONS = listOf(
    ExportOption("diaries", "日记", "Diaries"),
    ExportOption("media", "媒体", "Media"),
    ExportOption("notes", "笔记", "Notes"),
    ExportOption("poems", "诗词", "Poems"),
    ExportOption("thoughts", "小巧思", "Thoughts"),
    ExportOption("favorites", "收藏书签", "Bookmarks"),
    ExportOption("dateRecords", "日期记录", "Date records"),
    ExportOption("readingProgress", "阅读进度", "Reading progress"),
    ExportOption("games", "游戏", "Games"),
    ExportOption("vault", "Vault 加密数据", "Vault (encrypted)"),
    ExportOption("usage", "使用统计", "Usage statistics"),
    ExportOption("agentChats", "Agent 对话", "Agent chats"),
    ExportOption("settings", "设置与订阅", "Settings & subscriptions"),
)

@Composable
fun ExportScreen(
    contentPadding: PaddingValues,
    zipExport: ZipExportState,
    onExport: (ExportSelection) -> Unit,
    onConsumeResult: () -> Unit,
    restoreViewModel: DiaryZipRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val language = LocalAppLanguage.current
    var selection by remember { mutableStateOf(ExportSelection()) }
    val english = language == AppLanguage.ENGLISH
    val zipRestore by restoreViewModel.state.collectAsStateWithLifecycle()
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(restoreViewModel::restore)
    }

    zipExport.result?.let { result ->
        ExportResultDialog(
            result = result,
            onConfirm = {
                onConsumeResult()
                context.startActivity(shareZipIntent(context, result))
            },
            onDismiss = onConsumeResult,
        )
    }
    zipRestore.result?.let { result ->
        RestoreResultDialog(
            result = result,
            warning = zipRestore.warning,
            onDismiss = restoreViewModel::consumeResult,
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                role = PanelRole.FEATURE,
                padding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        tr(
                            "勾选要打包进 zip 的内容，然后点击「导出」。未配置目录的类型会自动跳过。",
                            "Select the content to package into the zip, then tap Export. Types without a configured folder are skipped automatically.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        tr(
                            "文件名：<用户名>.zip，保存到下载文件夹后可直接分享。",
                            "Filename: <username>.zip, saved to the Downloads folder and ready to share.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                role = PanelRole.STANDARD,
                padding = PaddingValues(4.dp),
            ) {
                Column {
                    EXPORT_OPTIONS.forEachIndexed { index, option ->
                        ExportCheckboxRow(
                            label = if (english) option.labelEn else option.labelZh,
                            checked = selection.isSelected(option.key),
                            onCheckedChange = { checked ->
                                selection = selection.withSelected(option.key, checked)
                            },
                        )
                        if (index < EXPORT_OPTIONS.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }

        item {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                role = PanelRole.MEDIA,
                padding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = tr("压缩包内的目录结构", "Folder structure inside the zip"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = tr(
                            "README.md\n" +
                                "diaries/      日记 markdown + .deskcubby 工作区元数据\n" +
                                "media/        媒体文件\n" +
                                "notes/        笔记 markdown（保留子目录）\n" +
                                "data/         结构化数据 data.json（诗词、小巧思、收藏、日期记录、阅读进度、游戏、Vault 加密、使用统计、Agent 对话、设置与订阅）",
                            "README.md\n" +
                                "diaries/      Diary markdown + .deskcubby workspace metadata\n" +
                                "media/        Media files\n" +
                                "notes/        Note markdown (subfolders kept)\n" +
                                "data/         Structured data.json (poems, thoughts, bookmarks, date records, reading progress, games, encrypted Vault, usage stats, Agent chats, settings)",
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            GlassPanel(
                modifier = Modifier.fillMaxWidth(),
                role = PanelRole.STANDARD,
                padding = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("恢复日记 workspace", "Restore diary workspace"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        tr(
                            "从 DeskCubby 导出的 zip 恢复 diaries/ 下的 Markdown 与 .deskcubby 四个元数据文件。不会覆盖媒体、笔记或 data/data.json；请先在设置中选好日记目录。",
                            "Restore Markdown plus the four .deskcubby metadata files under diaries/ from a DeskCubby export zip. Media, notes, and data/data.json are not overwritten; choose a diary folder first.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !zipRestore.busy && !zipExport.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("选择 zip 并恢复", "Choose zip and restore"))
                    }
                    if (zipRestore.busy) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                            Text(tr("正在校验并恢复…", "Validating and restoring…"))
                        }
                    }
                    zipRestore.error?.let { error ->
                        Text(
                            tr("恢复失败：", "Restore failed: ") + error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            val busy = zipExport.busy
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onExport(selection) },
                    enabled = !busy && !zipRestore.busy && selection.anySelected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("导出为 zip", "Export as zip"))
                }
                if (busy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                        Text(tr("正在打包导出…", "Packaging…"))
                    }
                }
                zipExport.error?.let { error ->
                    Text(
                        tr("导出失败：", "Export failed: ") + error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExportResultDialog(
    result: ZipExportResult,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (result.success) {
                    tr("导出完成", "Export complete")
                } else {
                    tr("导出未完全成功", "Export incomplete")
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr(
                        "「${result.fileName}」已生成。",
                        "“${result.fileName}” has been created.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (result.downloadUri != null) {
                        tr(
                            "已保存到手机「下载 / Downloads / DeskCubby」文件夹。",
                            "Saved to your Downloads / DeskCubby folder.",
                        )
                    } else {
                        tr(
                            "已保存到应用缓存，可直接分享。",
                            "Saved to the app cache and ready to share.",
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!result.success) {
                    result.failedReason?.let { reason ->
                        Text(
                            tr("注意：", "Warning: ") + reason,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (result.failedFiles.isNotEmpty()) {
                        Text(
                            tr(
                                "以下 ${result.failedFiles.size} 个文件无法读取，已跳过，未计入成功数：",
                                "${result.failedFiles.size} file(s) could not be read and were skipped (not counted as exported):",
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            result.failedFiles.take(20).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (result.counts.isNotEmpty()) {
                    Text(
                        text = result.counts.entries
                            .joinToString("、") { (key, count) -> "$key: $count" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(tr("分享", "Share"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) }
        },
    )
}

@Composable
private fun RestoreResultDialog(
    result: DiaryZipRestoreResult,
    warning: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("恢复完成", "Restore complete")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr(
                        "已恢复 ${result.diaryMarkdownCount} 个 Markdown 与 ${result.workspaceMetadataCount} 个 workspace 元数据文件。",
                        "Restored ${result.diaryMarkdownCount} Markdown file(s) and ${result.workspaceMetadataCount} workspace metadata file(s).",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    tr(
                        "恢复后已重新扫描结构化记录索引。",
                        "The structured-record index was rescanned after restore.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                warning?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("完成", "Done")) } },
    )
}

private fun shareZipIntent(context: Context, result: ZipExportResult): Intent =
    Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, result.shareUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri(result.fileName, result.shareUri)
    }

private fun ExportSelection.isSelected(key: String): Boolean = when (key) {
    "diaries" -> diaries
    "media" -> media
    "notes" -> notes
    "poems" -> poems
    "thoughts" -> thoughts
    "favorites" -> favorites
    "dateRecords" -> dateRecords
    "readingProgress" -> readingProgress
    "games" -> games
    "vault" -> vault
    "usage" -> usage
    "agentChats" -> agentChats
    "settings" -> settings
    else -> false
}

private fun ExportSelection.withSelected(key: String, value: Boolean): ExportSelection = when (key) {
    "diaries" -> copy(diaries = value)
    "media" -> copy(media = value)
    "notes" -> copy(notes = value)
    "poems" -> copy(poems = value)
    "thoughts" -> copy(thoughts = value)
    "favorites" -> copy(favorites = value)
    "dateRecords" -> copy(dateRecords = value)
    "readingProgress" -> copy(readingProgress = value)
    "games" -> copy(games = value)
    "vault" -> copy(vault = value)
    "usage" -> copy(usage = value)
    "agentChats" -> copy(agentChats = value)
    "settings" -> copy(settings = value)
    else -> this
}
