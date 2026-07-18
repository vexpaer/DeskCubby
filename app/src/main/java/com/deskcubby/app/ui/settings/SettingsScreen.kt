package com.deskcubby.app.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ViewWeek
import androidx.compose.material3.Button
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.theme.GlassPanel

private enum class SettingsPage(val title: String) {
    MAIN("设置"),
    APPEARANCE("外观"),
    DIARY("日记与媒体"),
    BLOG("博客"),
    NAVIGATION("底部导航"),
}

private data class DiarySettingsDraft(
    val diaryTreeUri: String?,
    val mediaTreeUri: String?,
    val mediaPrefix: String,
    val filePattern: String,
    val titlePattern: String,
    val datePattern: String,
    val template: String,
    val imagePattern: String,
    val imageWidth: Int?,
    val imageHeight: Int?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    padding: PaddingValues,
    viewModel: SettingsViewModel,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(SettingsPage.MAIN) }

    BackHandler(enabled = page != SettingsPage.MAIN) { page = SettingsPage.MAIN }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    if (page != SettingsPage.MAIN) {
                        IconButton(onClick = { page = SettingsPage.MAIN }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回设置")
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
                onSave = { visualStyle, darkMode ->
                    viewModel.setVisualStyle(visualStyle)
                    viewModel.setDarkMode(darkMode)
                    page = SettingsPage.MAIN
                },
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
                    viewModel.setMediaPrefix(draft.mediaPrefix)
                    viewModel.setFileNamePattern(draft.filePattern)
                    viewModel.setTitlePattern(draft.titlePattern)
                    viewModel.setDatePattern(draft.datePattern)
                    viewModel.setTemplate(draft.template)
                    viewModel.setImageNamePattern(draft.imagePattern)
                    draft.imageWidth?.let(viewModel::setImageMaxWidth)
                    draft.imageHeight?.let(viewModel::setImageMaxHeight)
                    page = SettingsPage.MAIN
                },
            )

            SettingsPage.BLOG -> BlogSettingsPage(
                currentHome = settings.browserHomeUrl,
                contentPadding = inner,
                onSave = { browserHome ->
                    viewModel.setBrowserHome(browserHome)
                    page = SettingsPage.MAIN
                },
            )

            SettingsPage.NAVIGATION -> NavigationSettingsPage(
                settings = settings,
                contentPadding = inner,
                onSave = { defaultPage, navItems ->
                    viewModel.setDefaultPage(defaultPage)
                    viewModel.setNavItems(navItems)
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
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsMenuItem(
                title = "外观",
                description = "界面风格与明暗模式",
                icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                onClick = { onOpen(SettingsPage.APPEARANCE) },
            )
        }
        item {
            SettingsMenuItem(
                title = "日记与媒体",
                description = if (settings.diaryTreeUri == null) "目录、文件格式与图片规则" else "日记目录已配置",
                icon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
                onClick = { onOpen(SettingsPage.DIARY) },
            )
        }
        item {
            SettingsMenuItem(
                title = "博客",
                description = "默认主页",
                icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                onClick = { onOpen(SettingsPage.BLOG) },
            )
        }
        item {
            SettingsMenuItem(
                title = "底部导航",
                description = "默认页、排序、显隐、名称与图标",
                icon = { Icon(Icons.Outlined.ViewWeek, contentDescription = null) },
                onClick = { onOpen(SettingsPage.NAVIGATION) },
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
                Icon(Icons.Outlined.ChevronRight, contentDescription = "进入$title")
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun AppearanceSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onSave: (VisualStyle, DarkMode) -> Unit,
) {
    var visualStyle by remember(settings.visualStyle) { mutableStateOf(settings.visualStyle) }
    var darkMode by remember(settings.darkMode) { mutableStateOf(settings.darkMode) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection("界面风格") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    VisualStyle.entries.forEachIndexed { index, style ->
                        SegmentedButton(
                            selected = visualStyle == style,
                            onClick = { visualStyle = style },
                            shape = SegmentedButtonDefaults.itemShape(index, VisualStyle.entries.size),
                        ) { Text(if (style == VisualStyle.MATERIAL) "安卓原生" else "Liquid Glass") }
                    }
                }
            }
        }
        item {
            SettingsSection("明暗模式") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    DarkMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = darkMode == mode,
                            onClick = { darkMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, DarkMode.entries.size),
                        ) {
                            Text(
                                when (mode) {
                                    DarkMode.SYSTEM -> "跟随"
                                    DarkMode.LIGHT -> "浅色"
                                    DarkMode.DARK -> "深色"
                                },
                            )
                        }
                    }
                }
            }
        }
        item { SaveButton { onSave(visualStyle, darkMode) } }
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
    var mediaPrefix by remember(settings.mediaMarkdownPrefix) { mutableStateOf(settings.mediaMarkdownPrefix) }
    var filePattern by remember(settings.fileNamePattern) { mutableStateOf(settings.fileNamePattern) }
    var titlePattern by remember(settings.titlePattern) { mutableStateOf(settings.titlePattern) }
    var datePattern by remember(settings.datePattern) { mutableStateOf(settings.datePattern) }
    var template by remember(settings.markdownTemplate) { mutableStateOf(settings.markdownTemplate) }
    var imagePattern by remember(settings.imageNamePattern) { mutableStateOf(settings.imageNamePattern) }
    var imageWidth by remember(settings.imageMaxWidthDp) { mutableStateOf(settings.imageMaxWidthDp.toString()) }
    var imageHeight by remember(settings.imageMaxHeightDp) { mutableStateOf(settings.imageMaxHeightDp.toString()) }

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
            SettingsSection("本地文件") {
                FolderButton(
                    title = "日记目录",
                    uri = diaryTreeUri,
                    onClick = { diaryFolderPicker.launch(diaryTreeUri?.let(Uri::parse)) },
                )
                Spacer(Modifier.height(8.dp))
                FolderButton(
                    title = "媒体目录",
                    uri = mediaTreeUri,
                    onClick = { mediaFolderPicker.launch(mediaTreeUri?.let(Uri::parse)) },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = mediaPrefix,
                    onValueChange = { mediaPrefix = it },
                    label = { Text("Markdown 媒体路径前缀") },
                    supportingText = { Text("例如 ../Attachments；只影响写入的链接，不转换 content:// URI") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            SettingsSection("日记格式") {
                SettingField(filePattern, { filePattern = it }, "文件名日期格式", "yyyy-MM-dd '日记'")
                SettingField(titlePattern, { titlePattern = it }, "标题日期格式", "yyyy年M月d日 EEEE")
                SettingField(datePattern, { datePattern = it }, "通用日期格式", "yyyy-MM-dd")
                SettingField(imagePattern, { imagePattern = it }, "图片命名格式", "{date}_{category}_{seq}")
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("默认 Markdown 模板") },
                    supportingText = { Text("支持 {title} 与 {date}") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = imageWidth,
                        onValueChange = { imageWidth = it.filter(Char::isDigit) },
                        label = { Text("图片最大宽度 dp") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = imageHeight,
                        onValueChange = { imageHeight = it.filter(Char::isDigit) },
                        label = { Text("图片最大高度 dp") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            SaveButton {
                onSave(
                    DiarySettingsDraft(
                        diaryTreeUri = diaryTreeUri,
                        mediaTreeUri = mediaTreeUri,
                        mediaPrefix = mediaPrefix,
                        filePattern = filePattern,
                        titlePattern = titlePattern,
                        datePattern = datePattern,
                        template = template,
                        imagePattern = imagePattern,
                        imageWidth = imageWidth.toIntOrNull(),
                        imageHeight = imageHeight.toIntOrNull(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun BlogSettingsPage(
    currentHome: String,
    contentPadding: PaddingValues,
    onSave: (String) -> Unit,
) {
    var browserHome by remember(currentHome) { mutableStateOf(currentHome) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection("默认主页") {
                OutlinedTextField(
                    value = browserHome,
                    onValueChange = { browserHome = it },
                    label = { Text("网址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { SaveButton { onSave(browserHome) } }
    }
}

@Composable
private fun NavigationSettingsPage(
    settings: AppSettings,
    contentPadding: PaddingValues,
    onSave: (NavItemId, List<NavItemConfig>) -> Unit,
) {
    var defaultPage by remember(settings.defaultPage) { mutableStateOf(settings.defaultPage) }
    var navItems by remember(settings.navItems) { mutableStateOf(settings.navItems.map { it.copy() }) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection("默认启动页面") {
                DefaultPagePicker(defaultPage, navItems) { defaultPage = it }
            }
        }
        item {
            SettingsSection("导航项目") {
                Text("设置入口始终保留；其余页面可隐藏、改名、换图标和排序。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                navItems.forEachIndexed { index, item ->
                    NavConfigRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < navItems.lastIndex,
                        onChange = { changed ->
                            val changedItems = navItems.toMutableList().apply { set(index, changed) }
                            navItems = changedItems
                            if (defaultPage == changed.id && !changed.visible && changed.id != NavItemId.SETTINGS) {
                                defaultPage = changedItems.firstOrNull { it.visible || it.id == NavItemId.SETTINGS }?.id
                                    ?: NavItemId.SETTINGS
                            }
                        },
                        onMove = { offset ->
                            val target = index + offset
                            if (target in navItems.indices) {
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
        item { SaveButton { onSave(defaultPage, navItems) } }
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
private fun SaveButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("保存") }
}

@Composable
private fun FolderButton(title: String, uri: String?, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(uri?.substringAfterLast('%')?.take(42) ?: "尚未选择", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

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
            value = visible.firstOrNull { it.id == current }?.label ?: current.defaultLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("默认启动页面") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            visible.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label) },
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
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (NavItemConfig) -> Unit,
    onMove: (Int) -> Unit,
) {
    var iconMenu by remember { mutableStateOf(false) }
    val icons = listOf("home", "book", "language", "bolt", "settings", "calendar", "star", "write", "sparkle", "day")

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { iconMenu = true }) { Icon(iconFor(item.iconKey), "选择图标") }
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
                label = { Text(item.id.defaultLabel) },
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = item.visible || item.id == NavItemId.SETTINGS,
                enabled = item.id != NavItemId.SETTINGS,
                onCheckedChange = { onChange(item.copy(visible = it)) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                Icon(Icons.Outlined.ArrowUpward, "上移")
            }
            IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                Icon(Icons.Outlined.ArrowDownward, "下移")
            }
        }
    }
}
