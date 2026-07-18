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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.runtime.key
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
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.DarkMode
import com.deskcubby.app.data.model.NavItemConfig
import com.deskcubby.app.data.model.NavItemId
import com.deskcubby.app.data.model.VisualStyle
import com.deskcubby.app.ui.iconFor
import com.deskcubby.app.ui.components.FourDotDragHandle
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr

private enum class SettingsPage {
    MAIN,
    APPEARANCE,
    DIARY,
    BLOG,
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
                description = tr("默认主页", "Default home page"),
                icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                onClick = { onOpen(SettingsPage.BLOG) },
            )
        }
        item {
            SettingsMenuItem(
                title = tr("底部导航", "Bottom navigation"),
                description = tr("默认页、排序、显隐、名称与图标", "Default page, order, visibility, labels and icons"),
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
    val navCenters = remember { mutableStateMapOf<NavItemId, Float>() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
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
private fun SaveButton(enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(tr("保存", "Save")) }
}

@Composable
private fun FolderButton(title: String, uri: String?, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(uri?.substringAfterLast('%')?.take(42) ?: tr("尚未选择", "Not selected"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
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
    val icons = listOf("home", "book", "language", "bolt", "settings", "calendar", "star", "write", "sparkle", "day")

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
    SettingsPage.DIARY -> tr("日记与媒体", "Diary & media")
    SettingsPage.BLOG -> tr("浏览器", "Browser")
    SettingsPage.NAVIGATION -> tr("底部导航", "Bottom navigation")
}

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
