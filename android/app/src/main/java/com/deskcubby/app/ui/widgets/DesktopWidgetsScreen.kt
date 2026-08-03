@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.widgets

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.DESKTOP_WIDGET_HOME_MODULE_IDS
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.ui.components.ColorPickerDialog
import com.deskcubby.app.ui.theme.tr
import java.util.UUID

@Composable
fun DesktopWidgetsScreen(
    padding: PaddingValues,
    viewModel: DesktopWidgetsViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apps by viewModel.launchableApps.collectAsStateWithLifecycle()
    val loadingApps by viewModel.loadingApps.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val english = settings.appLanguage == AppLanguage.ENGLISH
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var draft by remember { mutableStateOf<DesktopWidgetConfig?>(null) }
    var original by remember { mutableStateOf<DesktopWidgetConfig?>(null) }
    var discardConfirmation by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<DesktopWidgetConfig?>(null) }
    var colorTarget by remember { mutableStateOf<WidgetColorTarget?>(null) }
    var modulePickerVisible by remember { mutableStateOf(false) }
    var appPickerVisible by remember { mutableStateOf(false) }

    val backgroundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess || context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (persisted) {
            draft = draft?.copy(backgroundImageUri = uri.toString())
        }
    }

    fun closeEditor() {
        draft = null
        original = null
        colorTarget = null
        modulePickerVisible = false
        appPickerVisible = false
    }

    fun requestCloseEditor() {
        if (draft != original) discardConfirmation = true else closeEditor()
    }

    BackHandler(enabled = draft != null) { requestCloseEditor() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (draft == null) tr("桌面小卡片", "Desktop widgets")
                        else tr("编辑小卡片", "Edit widget card"),
                    )
                },
                navigationIcon = if (draft != null) {
                    {
                        IconButton(onClick = ::requestCloseEditor) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回", "Back"))
                        }
                    }
                } else {
                    {}
                },
                actions = {
                    draft?.let { current ->
                        IconButton(
                            enabled = current.name.isNotBlank() &&
                                (current.contentType != DesktopWidgetContentType.APP_SHORTCUT ||
                                    current.appPackageName != null),
                            onClick = {
                                viewModel.save(current) { saved ->
                                    if (saved) closeEditor()
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Save, tr("保存", "Save"))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (draft == null) {
                FloatingActionButton(
                    onClick = {
                        val created = DesktopWidgetConfig(
                            id = UUID.randomUUID().toString(),
                            name = if (english) "New card" else "新小卡片",
                            backgroundColorArgb = settings.themeColorArgb,
                        )
                        original = null
                        draft = created
                    },
                ) {
                    Icon(Icons.Outlined.Add, tr("新建小卡片", "New widget card"))
                }
            }
        },
    ) { inner ->
        val editing = draft
        if (editing == null) {
            WidgetCardList(
                configs = settings.desktopWidgetConfigs,
                contentPadding = inner,
                english = english,
                onEdit = {
                    original = it
                    draft = it
                },
                onDelete = { deleteCandidate = it },
                onPin = { viewModel.requestPin(it, english) },
            )
        } else {
            WidgetCardEditor(
                draft = editing,
                contentPadding = inner,
                english = english,
                onChange = { draft = it },
                onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
                onPickBackgroundColor = { colorTarget = WidgetColorTarget.BACKGROUND },
                onPickTextColor = { colorTarget = WidgetColorTarget.TEXT },
                onPickModule = { modulePickerVisible = true },
                onPickApp = {
                    viewModel.loadLaunchableApps()
                    appPickerVisible = true
                },
            )
        }
    }

    if (discardConfirmation) {
        AlertDialog(
            onDismissRequest = { discardConfirmation = false },
            title = { Text(tr("放弃未保存的更改？", "Discard unsaved changes?")) },
            text = { Text(tr("当前小卡片草稿尚未保存。", "This widget-card draft has not been saved.")) },
            confirmButton = {
                TextButton(onClick = {
                    discardConfirmation = false
                    closeEditor()
                }) { Text(tr("放弃", "Discard")) }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmation = false }) {
                    Text(tr("继续编辑", "Keep editing"))
                }
            },
        )
    }
    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(tr("删除小卡片？", "Delete widget card?")) },
            text = {
                Text(
                    tr(
                        "已放到桌面的实例会显示默认卡片或配置提示，不会从桌面被强制移除。",
                        "Existing launcher instances will show the default card or a setup message; they are not forcibly removed.",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(candidate.id)
                    deleteCandidate = null
                }) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(tr("取消", "Cancel")) }
            },
        )
    }
    colorTarget?.let { target ->
        val current = draft ?: return@let
        ColorPickerDialog(
            initialColorArgb = if (target == WidgetColorTarget.BACKGROUND) {
                current.backgroundColorArgb
            } else {
                current.textColorArgb
            },
            title = if (target == WidgetColorTarget.BACKGROUND) {
                tr("选择背景色", "Pick background color")
            } else {
                tr("选择文字色", "Pick text color")
            },
            onDismiss = { colorTarget = null },
            onConfirm = { color ->
                draft = if (target == WidgetColorTarget.BACKGROUND) {
                    current.copy(backgroundColorArgb = color)
                } else {
                    current.copy(textColorArgb = color)
                }
                colorTarget = null
            },
        )
    }
    if (modulePickerVisible) {
        HomeModulePicker(
            current = draft?.homeModuleId.orEmpty(),
            english = english,
            onDismiss = { modulePickerVisible = false },
            onSelected = { module ->
                draft = draft?.copy(homeModuleId = module)
                modulePickerVisible = false
            },
        )
    }
    if (appPickerVisible) {
        LaunchableAppPicker(
            apps = apps,
            loading = loadingApps,
            onDismiss = { appPickerVisible = false },
            onSelected = { app ->
                draft = draft?.copy(
                    appPackageName = app.packageName,
                    appLabel = app.label,
                )
                appPickerVisible = false
            },
        )
    }
}

@Composable
private fun WidgetCardList(
    configs: List<DesktopWidgetConfig>,
    contentPadding: PaddingValues,
    english: Boolean,
    onEdit: (DesktopWidgetConfig) -> Unit,
    onDelete: (DesktopWidgetConfig) -> Unit,
    onPin: (DesktopWidgetConfig) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    if (english) {
                        "Design reusable cards here, then add them to the home screen. The saved size controls the layout preview; Android launchers make the final grid decision and allow further resizing."
                    } else {
                        "先在这里设计可复用卡片，再添加到桌面。保存的尺寸用于布局预览；最终占用格数由 Android 桌面决定，添加后仍可继续缩放。"
                    },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (configs.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Widgets, null, modifier = Modifier.size(56.dp))
                    Text(if (english) "No cards yet" else "还没有小卡片")
                }
            }
        }
        items(configs, key = DesktopWidgetConfig::id) { config ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WidgetPreview(config, english)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(config.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${config.widthCells} × ${config.heightCells} · " +
                                    widgetContentSummary(config, english),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { onEdit(config) }) {
                            Icon(Icons.Outlined.Edit, if (english) "Edit" else "编辑")
                        }
                        IconButton(onClick = { onDelete(config) }) {
                            Icon(Icons.Outlined.Delete, if (english) "Delete" else "删除")
                        }
                    }
                    Button(onClick = { onPin(config) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (english) "Add to home screen" else "添加到桌面")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun WidgetCardEditor(
    draft: DesktopWidgetConfig,
    contentPadding: PaddingValues,
    english: Boolean,
    onChange: (DesktopWidgetConfig) -> Unit,
    onPickBackground: () -> Unit,
    onPickBackgroundColor: () -> Unit,
    onPickTextColor: () -> Unit,
    onPickModule: () -> Unit,
    onPickApp: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { WidgetPreview(draft, english) }
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it.take(80))) },
                label = { Text(if (english) "Card name" else "卡片名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            EditorSection(if (english) "Size" else "大小") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    COMMON_WIDGET_SIZES.forEach { (width, height) ->
                        FilterChip(
                            selected = draft.widthCells == width && draft.heightCells == height,
                            onClick = { onChange(draft.copy(widthCells = width, heightCells = height)) },
                            label = { Text("$width × $height") },
                        )
                    }
                }
                CellStepper(
                    label = if (english) "Width" else "宽度",
                    value = draft.widthCells,
                    onValueChange = { onChange(draft.copy(widthCells = it)) },
                )
                CellStepper(
                    label = if (english) "Height" else "高度",
                    value = draft.heightCells,
                    onValueChange = { onChange(draft.copy(heightCells = it)) },
                )
                Text(
                    if (english) "Custom range: 1–6 cells in each direction." else "宽高均可在 1–6 格之间自定义。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            EditorSection(if (english) "Appearance" else "外观") {
                OutlinedButton(onClick = onPickBackgroundColor, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ColorLens, null, tint = Color(draft.backgroundColorArgb))
                    Spacer(Modifier.width(8.dp))
                    Text(if (english) "Background color" else "背景颜色")
                }
                OutlinedButton(onClick = onPickTextColor, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ColorLens, null, tint = Color(draft.textColorArgb))
                    Spacer(Modifier.width(8.dp))
                    Text(if (english) "Text color" else "文字颜色")
                }
                OutlinedButton(onClick = onPickBackground, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (draft.backgroundImageUri == null) {
                            if (english) "Choose background image" else "选择背景图片"
                        } else if (english) {
                            "Replace background image"
                        } else {
                            "更换背景图片"
                        },
                    )
                }
                if (draft.backgroundImageUri != null) {
                    TextButton(
                        onClick = { onChange(draft.copy(backgroundImageUri = null)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (english) "Remove background image" else "移除背景图片") }
                }
            }
        }
        item {
            EditorSection(if (english) "Content" else "显示内容") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.contentType == DesktopWidgetContentType.HOME_MODULE,
                        onClick = {
                            onChange(draft.copy(contentType = DesktopWidgetContentType.HOME_MODULE))
                        },
                        label = { Text(if (english) "Home module" else "主页模块") },
                        leadingIcon = { Icon(Icons.Outlined.Home, null) },
                    )
                    FilterChip(
                        selected = draft.contentType == DesktopWidgetContentType.APP_SHORTCUT,
                        onClick = {
                            onChange(draft.copy(contentType = DesktopWidgetContentType.APP_SHORTCUT))
                        },
                        label = { Text(if (english) "App button" else "应用启动按钮") },
                        leadingIcon = { Icon(Icons.Outlined.Apps, null) },
                    )
                }
                if (draft.contentType == DesktopWidgetContentType.HOME_MODULE) {
                    OutlinedButton(onClick = onPickModule, modifier = Modifier.fillMaxWidth()) {
                        Text(homeModuleLabel(draft.homeModuleId, english))
                    }
                } else {
                    OutlinedButton(onClick = onPickApp, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            draft.appLabel ?: draft.appPackageName
                                ?: if (english) "Choose an app" else "选择应用",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun WidgetPreview(config: DesktopWidgetConfig, english: Boolean) {
    val previewHeight = (132f * config.heightCells / config.widthCells)
        .coerceIn(78f, 230f)
        .dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(previewHeight)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(config.backgroundColorArgb)),
    ) {
        config.backgroundImageUri?.let { uri ->
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)))
        }
        Column(
            modifier = Modifier.align(Alignment.CenterStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                config.name,
                color = Color(config.textColorArgb),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                widgetContentSummary(config, english),
                color = Color(config.textColorArgb).copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${config.widthCells} × ${config.heightCells}",
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            color = Color(config.textColorArgb).copy(alpha = 0.82f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EditorSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CellStepper(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        IconButton(
            enabled = value > MIN_DESKTOP_WIDGET_CELLS,
            onClick = { onValueChange(value - 1) },
        ) { Icon(Icons.Outlined.Remove, null) }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        IconButton(
            enabled = value < MAX_DESKTOP_WIDGET_CELLS,
            onClick = { onValueChange(value + 1) },
        ) { Icon(Icons.Outlined.Add, null) }
    }
}

@Composable
private fun HomeModulePicker(
    current: String,
    english: Boolean,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("选择主页模块", "Choose home module")) },
        text = {
            LazyColumn(modifier = Modifier.height(420.dp)) {
                items(DESKTOP_WIDGET_HOME_MODULE_IDS, key = { it }) { module ->
                    TextButton(onClick = { onSelected(module) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            homeModuleLabel(module, english) + if (module == current) "  ✓" else "",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun LaunchableAppPicker(
    apps: List<LaunchableApp>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSelected: (LaunchableApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val needle = query.trim()
        if (needle.isEmpty()) apps else apps.filter {
            it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("选择要启动的应用", "Choose an app to launch")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("搜索", "Search")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (loading) {
                    Text(tr("正在读取应用列表…", "Loading apps…"))
                } else {
                    LazyColumn(modifier = Modifier.height(380.dp)) {
                        items(filtered, key = LaunchableApp::packageName) { app ->
                            TextButton(
                                onClick = { onSelected(app) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

private fun widgetContentSummary(config: DesktopWidgetConfig, english: Boolean): String =
    if (config.contentType == DesktopWidgetContentType.APP_SHORTCUT) {
        config.appLabel ?: config.appPackageName ?: if (english) "App button" else "应用启动按钮"
    } else {
        homeModuleLabel(config.homeModuleId, english)
    }

private fun homeModuleLabel(id: String, english: Boolean): String {
    val labels = when (id) {
        "calendar" -> "日历" to "Calendar"
        "weather" -> "天气缓存" to "Weather cache"
        "poem" -> "每日诗词" to "Daily poem"
        "today" -> "今天日期" to "Today"
        "date_records" -> "日期记录" to "Date records"
        "streak" -> "连续记录天数" to "Writing streak"
        "month_diaries" -> "本月日记数量" to "Diaries this month"
        "total_words" -> "日记总字数" to "Total diary words"
        "recent_diary" -> "最近日记" to "Recent diary"
        "recent_thought" -> "最近小巧思" to "Recent thought"
        "quick_input" -> "快速输入" to "Quick input"
        "daily_records" -> "日常记录" to "Daily records"
        "meal_photos" -> "饮食图片" to "Meal photos"
        "random_diary" -> "随机旧日记" to "Random old diary"
        "year_progress" -> "年度进度" to "Year progress"
        "website" -> "网站快捷入口" to "Website shortcut"
        else -> "主页模块" to "Home module"
    }
    return if (english) labels.second else labels.first
}

private enum class WidgetColorTarget { BACKGROUND, TEXT }

private val COMMON_WIDGET_SIZES = listOf(
    1 to 1,
    1 to 2,
    2 to 1,
    2 to 2,
    1 to 4,
    4 to 1,
    2 to 4,
    4 to 2,
    4 to 4,
)
