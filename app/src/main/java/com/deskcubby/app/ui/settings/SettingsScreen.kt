package com.deskcubby.app.ui.settings

import android.net.Uri
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.takeCodePoints
import com.deskcubby.app.data.backup.AutoBackupStatus
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsPage {
    MAIN,
    APPEARANCE,
    HOME,
    BACKUP,
    DIARY,
    BLOG,
    THOUGHT,
    NAVIGATION,
}

private data class DiarySettingsDraft(
    val diaryTreeUri: String?,
    val mediaTreeUri: String?,
    val filePattern: String,
    val template: String,
    val imagePattern: String,
    val imageWidth: Int?,
    val imageHeight: Int?,
    val mealImageCompressionEnabled: Boolean,
    val mealImageCompressionQuality: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    padding: PaddingValues,
    viewModel: SettingsViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backupOperation by viewModel.backupOperation.collectAsStateWithLifecycle()
    val autoBackupStatus by viewModel.autoBackupStatus.collectAsStateWithLifecycle()
    val settingsError by viewModel.settingsError.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }

    LaunchedEffect(settingsError) {
        settingsError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSettingsError()
        }
    }

    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(pageTitle(page)) },
                navigationIcon = {
                    if (page != SettingsPage.MAIN) {
                        IconButton(onClick = { page = SettingsPage.MAIN }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回设置", "Back to settings"))
                        }
                    }
                },
            )
        },
        modifier = Modifier
            .padding(bottom = padding.calculateBottomPadding())
            .imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        when (page) {
            SettingsPage.MAIN -> SettingsMainPage(
                settings = settings,
                contentPadding = inner,
                onOpen = { page = it },
            )

            SettingsPage.APPEARANCE -> AppearanceSettingsPage(
                settings = settings,
                contentPadding = inner,
                onSave = { visualStyle, darkMode, language, themeColor ->
                    viewModel.setVisualStyle(visualStyle)
                    viewModel.setDarkMode(darkMode)
                    viewModel.setAppLanguage(language)
                    viewModel.setThemeColor(themeColor)
                    page = SettingsPage.MAIN
                },
            )

            SettingsPage.HOME -> HomeSettingsPage(
                settings = settings,
                contentPadding = inner,
                onSave = { userName, widgetBordersEnabled ->
                    viewModel.setUserName(userName)
                    viewModel.setHomeWidgetBordersEnabled(widgetBordersEnabled)
                    page = SettingsPage.MAIN
                },
            )

            SettingsPage.BACKUP -> BackupSettingsPage(
                backupTreeUri = settings.backupTreeUri,
                operation = backupOperation,
                autoBackupStatus = autoBackupStatus,
                contentPadding = inner,
                onSelectFolder = viewModel::selectBackupFolder,
                onImportExistingBackup = viewModel::importExistingBackup,
                onOverwriteExistingBackup = viewModel::overwriteExistingBackup,
                onCancelFolderConflict = viewModel::cancelBackupFolderConflict,
                onSaveNow = viewModel::saveBackupNow,
                onDisableAutoBackup = viewModel::disableAutoBackup,
                onExport = viewModel::exportBackup,
                onImport = viewModel::importBackup,
            )

            SettingsPage.DIARY -> DiarySettingsPage(
                settings = settings,
                contentPadding = inner,
                onSave = { draft ->
                    if (draft.diaryTreeUri != null && draft.diaryTreeUri != settings.diaryTreeUri) {
                        viewModel.persistFolder(Uri.parse(draft.diaryTreeUri), diary = true)
                    }
                    if (draft.mediaTreeUri != null && draft.mediaTreeUri != settings.mediaTreeUri) {
                        viewModel.persistFolder(Uri.parse(draft.mediaTreeUri), diary = false)
                    }
                    viewModel.setFileNamePattern(draft.filePattern)
                    viewModel.setTemplate(draft.template)
                    viewModel.setImageNamePattern(draft.imagePattern)
                    draft.imageWidth?.let(viewModel::setImageMaxWidth)
                    draft.imageHeight?.let(viewModel::setImageMaxHeight)
                    viewModel.setMealImageCompressionEnabled(draft.mealImageCompressionEnabled)
                    viewModel.setMealImageCompressionQuality(draft.mealImageCompressionQuality)
                    page = SettingsPage.MAIN
                },
            )

            SettingsPage.BLOG -> BlogSettingsPage(
                settings = settings,
                contentPadding = inner,
                onSave = { browserHome, browserTheme, browserDesktopMode ->
                    viewModel.setBrowserHome(browserHome)
                    viewModel.setBrowserTheme(browserTheme)
                    viewModel.setBrowserDesktopMode(browserDesktopMode)
                    page = SettingsPage.MAIN
                },
            )

            SettingsPage.THOUGHT -> ThoughtSettingsPage(
                currentRowHeight = settings.thoughtRowHeightDp,
                contentPadding = inner,
                onSave = { rowHeight ->
                    viewModel.setThoughtRowHeight(rowHeight)
                    page = SettingsPage.MAIN
                },
            )

            SettingsPage.NAVIGATION -> NavigationSettingsPage(
                settings = settings,
                contentPadding = inner,
                onSave = { defaultPage, navItems, showLabels ->
                    viewModel.setDefaultPage(defaultPage)
                    viewModel.setNavItems(navItems)
                    viewModel.setBottomNavShowLabels(showLabels)
                    page = SettingsPage.MAIN
                },
            )
        }
    }
}

@Composable
private fun SettingsMainPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onOpen: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsMenuItem(
                title = tr("外观与语言", "Appearance & language"),
                description = tr("界面风格、主题色、明暗模式和语言", "Style, color, dark mode and language"),
                icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                onClick = { onOpen(SettingsPage.APPEARANCE) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("主页", "Home"),
                description = tr("用户名、问候语和模块边框", "User name, greeting and widget borders"),
                icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                onClick = { onOpen(SettingsPage.HOME) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("应用数据与备份", "App data & backup"),
                description = if (settings.backupTreeUri == null) {
                    tr("自动保存文件夹、导入与导出 JSON", "Auto-save folder and JSON import/export")
                } else {
                    tr("应用内容会在更改后自动保存", "App data is saved automatically after changes")
                },
                icon = { Icon(Icons.Outlined.Backup, contentDescription = null) },
                onClick = { onOpen(SettingsPage.BACKUP) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("日记与媒体", "Diary & media"),
                description = if (settings.diaryTreeUri == null) tr("目录、文件名与图片规则", "Folders, file names and image rules")
                else tr("日记目录已配置", "Diary folder configured"),
                icon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
                onClick = { onOpen(SettingsPage.DIARY) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("浏览器", "Browser"),
                description = tr("默认主页、主题和电脑模式", "Home page, theme and desktop mode"),
                icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                onClick = { onOpen(SettingsPage.BLOG) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("小巧思", "Thoughts"),
                description = tr("调节每一行的显示高度", "Adjust the height of each row"),
                icon = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
                onClick = { onOpen(SettingsPage.THOUGHT) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("底部导航", "Bottom navigation"),
                description = tr("显示方式、默认页、排序、名称与图标", "Display, default page, order, labels and icons"),
                icon = { Icon(Icons.Outlined.ViewWeek, contentDescription = null) },
                onClick = { onOpen(SettingsPage.NAVIGATION) },
            )
        }
        item {
            SettingsMenuItem(
                title = "About",
                description = tr("查看 DeskCubby GitHub 仓库", "Open the DeskCubby GitHub repository"),
                icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                onClick = { openUrl(context, GITHUB_URL) },
            )
        }
    }
}

@Composable
private fun SettingsMenuItem(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        padding = PaddingValues(0.dp),
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            leadingContent = icon,
            trailingContent = {
                Icon(Icons.Outlined.ChevronRight, contentDescription = tr("进入$title", "Open $title"))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun BackupSettingsPage(
    backupTreeUri: String?,
    operation: BackupOperationState,
    autoBackupStatus: AutoBackupStatus,
    contentPadding: PaddingValues,
    onSelectFolder: (Uri) -> Unit,
    onImportExistingBackup: () -> Unit,
    onOverwriteExistingBackup: () -> Unit,
    onCancelFolderConflict: () -> Unit,
    onSaveNow: () -> Unit,
    onDisableAutoBackup: () -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
) {
    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onSelectFolder)
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(onExport)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImportUri = uri?.toString()
    }
    val busy = operation.busy || autoBackupStatus.isSaving

    operation.folderConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = onCancelFolderConflict,
            title = { Text(tr("发现已有备份", "Existing backup found")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr(
                            "所选文件夹中已有 DeskCubby 备份。请选择导入它，或明确使用当前数据覆盖。",
                            "The selected folder already contains a DeskCubby backup. Import it or explicitly replace it with current data.",
                        ),
                    )
                    Text(
                        tr("导出时间：", "Exported: ") + formatBackupTime(conflict.summary.exportedAt),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        tr(
                            "${conflict.summary.thoughtCount} 条小巧思，${conflict.summary.categoryCount} 个分类，${conflict.summary.favoriteCount} 个浏览器收藏，${conflict.summary.dateRecordCount} 个日期记录，${conflict.summary.poemCount} 首诗词",
                            "${conflict.summary.thoughtCount} thoughts, ${conflict.summary.categoryCount} categories, ${conflict.summary.favoriteCount} browser bookmarks, ${conflict.summary.dateRecordCount} date records, ${conflict.summary.poemCount} poems",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(
                        onClick = onImportExistingBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("导入已有备份", "Import existing backup")) }
                    OutlinedButton(
                        onClick = onOverwriteExistingBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("用当前数据覆盖", "Replace with current data")) }
                    TextButton(
                        onClick = onCancelFolderConflict,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text(tr("取消", "Cancel")) }
                }
            },
        )
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(tr("导入应用数据？", "Import app data?")) },
            text = {
                Text(
                    tr(
                        "导入会替换当前设置、小巧思及其分类、浏览器收藏夹、日期记录和诗词本。日记正文和媒体文件不会被修改。确定继续吗？",
                        "Importing replaces the current settings, thoughts and categories, browser bookmarks, date records, and poetry book. Diary entries and media files are not changed. Continue?",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri = null
                        onImport(Uri.parse(uri))
                    },
                ) { Text(tr("导入并替换", "Import and replace")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("自动保存文件夹", "Auto-save folder")) {
                Text(
                    tr(
                        "选择一个独立文件夹后，每次应用内容发生更改都会自动保存到 DeskCubby.json。",
                        "Choose a dedicated folder to save automatically to DeskCubby.json whenever app data changes.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                FolderButton(
                    title = if (backupTreeUri == null) {
                        tr("选择文件夹", "Choose folder")
                    } else {
                        tr("更换文件夹", "Change folder")
                    },
                    uri = backupTreeUri,
                    enabled = !busy,
                    onClick = { folderPicker.launch(backupTreeUri?.let(Uri::parse)) },
                )
                if (backupTreeUri != null) {
                    Button(
                        onClick = onSaveNow,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("立即保存", "Save now"))
                    }
                    OutlinedButton(
                        onClick = onDisableAutoBackup,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("停止自动保存", "Stop auto-save")) }
                }
            }
        }
        item {
            SettingsSection(tr("备份内容", "Backup contents")) {
                Text(
                    tr(
                        "包含应用设置、小巧思及其分类、浏览器收藏夹、日期记录、诗词本，以及日记和媒体目录路径；不包含日记正文或媒体文件。",
                        "Includes app settings, thoughts and categories, browser bookmarks, date records, the poetry book, and diary/media folder paths; diary entries and media files are not included.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    tr(
                        "跨设备导入后需重新选择日记和媒体目录；没有本机授权的目录路径不会覆盖当前目录。",
                        "After importing on another device, reselect diary and media folders; paths without local access do not replace current folders.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SettingsSection(tr("JSON 导入与导出", "JSON import & export")) {
                Text(
                    tr(
                        "可将应用内容保存为单个 JSON 文件，或从之前的备份恢复。",
                        "Save app data as one JSON file or restore it from an earlier backup.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    tr(
                        "JSON 未加密，包含小巧思及其分类、收藏网址、日期记录和目录信息，请勿存入公开或共享目录。",
                        "JSON is not encrypted and contains thoughts and categories, bookmarked URLs, date records and folder information. Do not store it in a public or shared folder.",
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { exportPicker.launch(defaultBackupFileName()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("导出 JSON", "Export JSON")) }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("导入 JSON", "Import JSON")) }
            }
        }
        item {
            SettingsSection(tr("保存状态", "Save status")) {
                if (busy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(tr("正在处理…", "Working…"))
                    }
                }
                autoBackupStatus.lastSavedAt?.let { savedAt ->
                    Text(
                        tr("上次自动保存：", "Last auto-save: ") + formatBackupTime(savedAt),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                autoBackupStatus.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        tr("自动保存错误：", "Auto-save error: ") + error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                operation.message?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
                operation.error?.let { error ->
                    Text(
                        tr("操作失败：", "Operation failed: ") + error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (!busy && autoBackupStatus.lastSavedAt == null && autoBackupStatus.error == null &&
                    operation.message == null && operation.error == null
                ) {
                    Text(
                        if (backupTreeUri == null) tr("尚未开启自动保存", "Auto-save is off")
                        else tr("等待首次自动保存", "Waiting for the first auto-save"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onSave: (VisualStyle, DarkMode, AppLanguage, Int) -> Unit,
) {
    var visualStyle by remember(settings.visualStyle) { mutableStateOf(settings.visualStyle) }
    var darkMode by remember(settings.darkMode) { mutableStateOf(settings.darkMode) }
    var language by remember(settings.appLanguage) { mutableStateOf(settings.appLanguage) }
    var themeHex by remember(settings.themeColorArgb) { mutableStateOf(colorToHex(settings.themeColorArgb)) }
    val parsedThemeColor = parseThemeColor(themeHex)
    val presets = listOf(0xFF42664D, 0xFF4C63A6, 0xFFC44B75, 0xFFE57C23, 0xFF7B5EA7, 0xFF00897B)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("界面风格", "Visual style")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    VisualStyle.entries.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = visualStyle == style,
                            onClick = { visualStyle = style },
                            shape = SegmentedButtonDefaults.itemShape(index, VisualStyle.entries.size),
                        ) { Text(if (style == VisualStyle.MATERIAL) tr("安卓原生", "Material") else "Liquid Glass") }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("明暗模式", "Dark mode")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DarkMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = darkMode == mode,
                            onClick = { darkMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                        ) {
                            Text(
                                when (mode) {
                                    DarkMode.SYSTEM -> tr("跟随", "System")
                                    DarkMode.LIGHT -> tr("浅色", "Light")
                                    DarkMode.DARK -> tr("深色", "Dark")
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("软件语言", "App language")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AppLanguage.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            selected = language == item,
                            onClick = { language = item },
                            shape = SegmentedButtonDefaults.itemShape(index, AppLanguage.entries.size),
                        ) { Text(if (item == AppLanguage.CHINESE) "中文" else "English") }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("主题色", "Theme color")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    presets.forEach { value ->
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(value))
                                .clickable { themeHex = colorToHex(value.toInt()) },
                        )
                    }
                }
                OutlinedTextField(
                    value = themeHex,
                    onValueChange = { themeHex = it.take(7) },
                    label = { Text(tr("自定义颜色", "Custom color")) },
                    supportingText = { Text(tr("输入 #RRGGBB，例如 #42664D", "Enter #RRGGBB, for example #42664D")) },
                    isError = parsedThemeColor == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SaveButton(enabled = parsedThemeColor != null) {
                onSave(visualStyle, darkMode, language, requireNotNull(parsedThemeColor))
            }
        }
    }
}

@Composable
private fun HomeSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onSave: (String, Boolean) -> Unit,
) {
    var userName by rememberSaveable(settings.userName) { mutableStateOf(settings.userName) }
    var widgetBordersEnabled by rememberSaveable(settings.homeWidgetBordersEnabled) {
        mutableStateOf(settings.homeWidgetBordersEnabled)
    }
    val trimmedName = userName.trim()
    val greetingPreview = if (trimmedName.isBlank()) {
        tr("你好！", "Hello!")
    } else {
        tr("你好，$trimmedName！", "Hello, $trimmedName!")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("主页问候", "Home greeting")) {
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it.takeCodePoints(32) },
                    label = { Text(tr("用户名", "User name")) },
                    supportingText = { Text(tr("主页将显示：$greetingPreview", "Home will show: $greetingPreview")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsSection(tr("模块样式", "Widget style")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示模块边框", "Show widget borders"))
                        Text(
                            tr(
                                "关闭后主页模块会更自然地连成一体",
                                "Turn off for a more continuous home layout",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = widgetBordersEnabled,
                        onCheckedChange = { widgetBordersEnabled = it },
                    )
                }
            }
        }
        item {
            SaveButton { onSave(trimmedName, widgetBordersEnabled) }
        }
    }
}

@Composable
private fun DiarySettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onSave: (DiarySettingsDraft) -> Unit,
) {
    var diaryTreeUri by remember(settings.diaryTreeUri) { mutableStateOf(settings.diaryTreeUri) }
    var mediaTreeUri by remember(settings.mediaTreeUri) { mutableStateOf(settings.mediaTreeUri) }
    var filePattern by remember(settings.fileNamePattern) { mutableStateOf(settings.fileNamePattern) }
    var template by remember(settings.markdownTemplate) { mutableStateOf(settings.markdownTemplate) }
    var imagePattern by remember(settings.imageNamePattern) { mutableStateOf(settings.imageNamePattern) }
    var imageWidth by remember(settings.imageMaxWidthDp) { mutableStateOf(settings.imageMaxWidthDp.toString()) }
    var imageHeight by remember(settings.imageMaxHeightDp) { mutableStateOf(settings.imageMaxHeightDp.toString()) }
    var mealImageCompressionEnabled by rememberSaveable(settings.mealImageCompressionEnabled) {
        mutableStateOf(settings.mealImageCompressionEnabled)
    }
    var mealImageCompressionQuality by rememberSaveable(settings.mealImageCompressionQuality) {
        mutableIntStateOf(settings.mealImageCompressionQuality)
    }

    val diaryFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { diaryTreeUri = it.toString() }
    }
    val mediaFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { mediaTreeUri = it.toString() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("本地文件", "Local files")) {
                FolderButton(
                    title = tr("日记目录", "Diary folder"),
                    uri = diaryTreeUri,
                    onClick = { diaryFolderPicker.launch(diaryTreeUri?.let(Uri::parse)) },
                )
                Spacer(Modifier.height(8.dp))
                FolderButton(
                    title = tr("媒体目录", "Media folder"),
                    uri = mediaTreeUri,
                    onClick = { mediaFolderPicker.launch(mediaTreeUri?.let(Uri::parse)) },
                )
            }
        }
        item {
            SettingsSection(tr("日记与图片格式", "Diary and image format")) {
                SettingField(filePattern, { filePattern = it }, tr("今日日记文件名格式", "Today's diary filename format"), "yyyy-MM-dd")
                SettingField(imagePattern, { imagePattern = it }, tr("图片命名格式", "Image filename format"), "{date}_{category}_{seq}")
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text(tr("默认 Markdown 模板", "Default Markdown template")) },
                    supportingText = { Text(tr("支持 {title} 与 {date}", "Supports {title} and {date}")) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = imageWidth,
                        onValueChange = { imageWidth = it.filter(Char::isDigit) },
                        label = { Text(tr("图片最大宽度 dp", "Max image width (dp)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = imageHeight,
                        onValueChange = { imageHeight = it.filter(Char::isDigit) },
                        label = { Text(tr("图片最大高度 dp", "Max image height (dp)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            SettingsSection(tr("饮食图片压缩", "Meal image compression")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("自动压缩饮食图片", "Compress meal images automatically"))
                        Text(
                            tr(
                                "适用于主页拍照、选图和日记中的餐别图片",
                                "Applies to home camera/gallery photos and categorized meal images in diaries",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = mealImageCompressionEnabled,
                        onCheckedChange = { mealImageCompressionEnabled = it },
                    )
                }
                Text(
                    tr(
                        "压缩质量：$mealImageCompressionQuality%",
                        "Compression quality: $mealImageCompressionQuality%",
                    ),
                    color = if (mealImageCompressionEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Slider(
                    value = mealImageCompressionQuality.toFloat(),
                    onValueChange = {
                        mealImageCompressionQuality = (it / 5f).roundToInt().times(5).coerceIn(30, 95)
                    },
                    enabled = mealImageCompressionEnabled,
                    valueRange = 30f..95f,
                    steps = 12,
                )
                Text(
                    tr(
                        "数值越低文件越小；压缩时最长边同时限制为 2560 像素。",
                        "Lower values create smaller files; the longest edge is also limited to 2560 px.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SaveButton {
                onSave(
                    DiarySettingsDraft(
                        diaryTreeUri = diaryTreeUri,
                        mediaTreeUri = mediaTreeUri,
                        filePattern = filePattern,
                        template = template,
                        imagePattern = imagePattern,
                        imageWidth = imageWidth.toIntOrNull(),
                        imageHeight = imageHeight.toIntOrNull(),
                        mealImageCompressionEnabled = mealImageCompressionEnabled,
                        mealImageCompressionQuality = mealImageCompressionQuality,
                    ),
                )
            }
        }
    }
}

@Composable
private fun BlogSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onSave: (String, BrowserTheme, Boolean) -> Unit,
) {
    var browserHome by remember(settings.browserHomeUrl) { mutableStateOf(settings.browserHomeUrl) }
    var browserTheme by remember(settings.browserTheme) { mutableStateOf(settings.browserTheme) }
    var browserDesktopMode by remember(settings.browserDesktopMode) { mutableStateOf(settings.browserDesktopMode) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("默认主页", "Default home page")) {
                OutlinedTextField(
                    value = browserHome,
                    onValueChange = { browserHome = it },
                    label = { Text(tr("网址", "URL")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsSection(tr("浏览器主题", "Browser theme")) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BrowserTheme.entries.forEachIndexed { index, theme ->
                        SegmentedButton(
                            selected = browserTheme == theme,
                            onClick = { browserTheme = theme },
                            shape = SegmentedButtonDefaults.itemShape(index, BrowserTheme.entries.size),
                        ) {
                            Text(
                                when (theme) {
                                    BrowserTheme.SYSTEM -> tr("跟随", "System")
                                    BrowserTheme.LIGHT -> tr("浅色", "Light")
                                    BrowserTheme.DARK -> tr("深色", "Dark")
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SettingsSection(tr("网页模式", "Web page mode")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("电脑模式", "Desktop mode"))
                        Text(
                            tr("优先请求网页的桌面版布局", "Prefer the desktop layout of websites"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = browserDesktopMode,
                        onCheckedChange = { browserDesktopMode = it },
                    )
                }
            }
        }
        item { SaveButton { onSave(browserHome, browserTheme, browserDesktopMode) } }
    }
}

@Composable
private fun ThoughtSettingsPage(
    currentRowHeight: Int,
    contentPadding: PaddingValues,
    onSave: (Int) -> Unit,
) {
    var rowHeight by remember(currentRowHeight) {
        mutableIntStateOf(currentRowHeight.coerceIn(48, 120))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("每行高度", "Row height")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("小巧思列表", "Thoughts list"))
                    Text("$rowHeight dp", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = rowHeight.toFloat(),
                    onValueChange = { rowHeight = it.roundToInt().coerceIn(48, 120) },
                    valueRange = 48f..120f,
                    steps = 71,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("紧凑 48 dp", "Compact 48 dp"), style = MaterialTheme.typography.bodySmall)
                    Text(tr("宽松 120 dp", "Spacious 120 dp"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SaveButton { onSave(rowHeight) } }
    }
}

@Composable
private fun NavigationSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onSave: (NavItemId, List<NavItemConfig>, Boolean) -> Unit,
) {
    var defaultPage by remember(settings.defaultPage) { mutableStateOf(settings.defaultPage) }
    var navItems by remember(settings.navItems) { mutableStateOf(settings.navItems.map { it.copy() }) }
    var showLabels by remember(settings.bottomNavShowLabels) { mutableStateOf(settings.bottomNavShowLabels) }
    val navCenters = remember { mutableStateMapOf<NavItemId, Float>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection(tr("导航栏样式", "Navigation bar style")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示文字", "Show labels"))
                        Text(
                            tr("关闭后仅显示图标，导航栏占用高度更低", "Turn off to show icons only and use a shorter navigation bar"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = showLabels, onCheckedChange = { showLabels = it })
                }
            }
        }
        item {
            SettingsSection(tr("默认启动页面", "Default start page")) {
                DefaultPagePicker(defaultPage, navItems) { defaultPage = it }
            }
        }
        item {
            SettingsSection(tr("导航项目", "Navigation items")) {
                Text(
                    tr("设置入口始终保留；其余页面可隐藏、改名、换图标和排序。", "Settings is always available; other items can be hidden, renamed, reordered or given new icons."),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                navItems.forEachIndexed { index, item ->
                    key(item.id) {
                        NavConfigRow(
                            item = item,
                            onChange = { changed ->
                                val changedItems = navItems.toMutableList().apply { set(index, changed) }
                                navItems = changedItems
                                if (defaultPage == changed.id && !changed.visible && changed.id != NavItemId.SETTINGS) {
                                    defaultPage = changedItems.firstOrNull { it.visible || it.id == NavItemId.SETTINGS }?.id
                                        ?: NavItemId.SETTINGS
                                }
                            },
                            onCenterChanged = { navCenters[item.id] = it },
                            onMove = { distance ->
                                val start = navCenters[item.id]
                                val targetId = start?.let { origin ->
                                    navCenters.minByOrNull { (_, center) -> kotlin.math.abs(center - (origin + distance)) }?.key
                                }
                                val target = navItems.indexOfFirst { it.id == targetId }
                                if (target in navItems.indices && target != index) {
                                    navItems = navItems.toMutableList().apply {
                                        val moved = removeAt(index)
                                        add(target, moved)
                                    }
                                }
                            },
                        )
                        if (index != navItems.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
        item { SaveButton { onSave(defaultPage, navItems, showLabels) } }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SaveButton(enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(tr("保存", "Save")) }
}

@Composable
private fun FolderButton(title: String, uri: String?, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                uri?.let(::displayFolderName) ?: tr("尚未选择", "Not selected"),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
    }
}

private fun displayFolderName(rawUri: String): String = runCatching {
    val documentId = Uri.decode(Uri.parse(rawUri).lastPathSegment ?: rawUri)
    documentId.substringAfter(':', documentId).takeLast(42)
}.getOrDefault(rawUri.takeLast(42))

@Composable
private fun SettingField(value: String, onValueChange: (String) -> Unit, label: String, hint: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DefaultPagePicker(current: NavItemId, items: List<NavItemConfig>, onSelected: (NavItemId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val visible = items.filter { it.visible || it.id == NavItemId.SETTINGS }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = visible.firstOrNull { it.id == current }?.let { localizedNavLabel(it) }
                ?: tr(current.defaultLabel, current.englishLabel),
            onValueChange = {},
            readOnly = true,
            label = { Text(tr("默认启动页面", "Default start page")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            visible.forEach { item ->
                DropdownMenuItem(
                    text = { Text(localizedNavLabel(item)) },
                    leadingIcon = { Icon(iconFor(item.iconKey), contentDescription = null) },
                    onClick = {
                        onSelected(item.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NavConfigRow(
    item: NavItemConfig,
    onChange: (NavItemConfig) -> Unit,
    onCenterChanged: (Float) -> Unit,
    onMove: (Float) -> Unit,
) {
    var iconMenu by remember { mutableStateOf(false) }
    val icons = listOf("home", "book", "poetry", "language", "bolt", "settings", "calendar", "star", "write", "sparkle", "day")

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onCenterChanged(it.boundsInRoot().center.y) }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { iconMenu = true }) { Icon(iconFor(item.iconKey), tr("选择图标", "Choose icon")) }
                DropdownMenu(expanded = iconMenu, onDismissRequest = { iconMenu = false }) {
                    icons.chunked(5).forEach { row ->
                        Row {
                            row.forEach { key ->
                                IconButton(
                                    onClick = {
                                        onChange(item.copy(iconKey = key))
                                        iconMenu = false
                                    },
                                ) { Icon(iconFor(key), key) }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = item.label,
                onValueChange = { onChange(item.copy(label = it.take(8))) },
                singleLine = true,
                label = { Text(tr(item.id.defaultLabel, item.id.englishLabel)) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("显示", "Visible"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = item.visible || item.id == NavItemId.SETTINGS,
                enabled = item.id != NavItemId.SETTINGS,
                onCheckedChange = { onChange(item.copy(visible = it)) },
            )
            FourDotDragHandle(onDragFinished = onMove)
        }
    }
}

@Composable
private fun pageTitle(page: SettingsPage): String = when (page) {
    SettingsPage.MAIN -> tr("设置", "Settings")
    SettingsPage.APPEARANCE -> tr("外观与语言", "Appearance & language")
    SettingsPage.HOME -> tr("主页", "Home")
    SettingsPage.BACKUP -> tr("应用数据与备份", "App data & backup")
    SettingsPage.DIARY -> tr("日记与媒体", "Diary & media")
    SettingsPage.BLOG -> tr("浏览器", "Browser")
    SettingsPage.THOUGHT -> tr("小巧思", "Thoughts")
    SettingsPage.NAVIGATION -> tr("底部导航", "Bottom navigation")
}

private fun defaultBackupFileName(): String =
    "DeskCubby-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.json"

private fun formatBackupTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private fun parseThemeColor(raw: String): Int? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' }) return null
    return (0xFF000000L or hex.toLong(16)).toInt()
}

private fun colorToHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private const val GITHUB_URL = "https://github.com/vexpaer/DeskCubby"

@Composable
private fun localizedNavLabel(item: NavItemConfig): String =
    if (com.deskcubby.app.ui.theme.LocalAppLanguage.current == AppLanguage.ENGLISH && item.label.isDefaultLabelFor(item.id)) {
        item.id.englishLabel
    } else {
        item.label
    }

private fun String.isDefaultLabelFor(id: NavItemId): Boolean =
    this == id.defaultLabel || (id == NavItemId.BLOG && this == "博客") || (id == NavItemId.THOUGHT && this == "闪思")
