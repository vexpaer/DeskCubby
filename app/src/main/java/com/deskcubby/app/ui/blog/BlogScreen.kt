@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.blog

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.deskcubby.app.R
import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.ui.theme.tr
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

@Composable
fun BlogScreen(
    padding: PaddingValues,
    viewModel: BlogViewModel,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val settingsOrNull by viewModel.settings.collectAsStateWithLifecycle()
    val tabsState by viewModel.tabsState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val webViews = remember { mutableStateMapOf<Long, WebView>() }
    var findDialog by remember { mutableStateOf(false) }
    var recordsDialog by remember { mutableStateOf<RecordMode?>(null) }
    var toolbarMenu by remember { mutableStateOf(false) }
    var tabsMenu by remember { mutableStateOf(false) }
    var pendingFileRequest by remember { mutableStateOf<PendingFileRequest?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = pendingFileRequest
        pendingFileRequest = null
        request?.let {
            val selectedFiles = runCatching {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }.getOrNull()
            runCatching { it.callback.onReceiveValue(selectedFiles) }
        }
    }

    val settings = settingsOrNull
    val currentTab = tabsState.currentTab
    if (!tabsState.ready || settings == null || currentTab == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    val tabs = tabsState.tabs
    val tabIds = tabs.map { it.id }
    val systemUsesDarkTheme =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val browserUsesDarkTheme = when (settings.browserTheme) {
        BrowserTheme.SYSTEM -> systemUsesDarkTheme
        BrowserTheme.LIGHT -> false
        BrowserTheme.DARK -> true
    }
    val initialPage = tabs.indexOfFirst { it.id == currentTab.id }.coerceAtLeast(0)
    val latestPageCount by rememberUpdatedState(tabIds.size)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { latestPageCount })
    val currentWebView = webViews[currentTab.id]
    val isBlankPage = currentTab.url.equals(BROWSER_BLANK_URL, ignoreCase = true)
    val isFavorite = !isBlankPage && favorites.any { it.url == currentTab.url }

    fun cancelFileRequest(tabId: Long) {
        pendingFileRequest?.takeIf { it.tabId == tabId }?.let {
            pendingFileRequest = null
            runCatching { it.callback.onReceiveValue(null) }
        }
    }

    fun loadInTab(tabId: Long, rawAddress: String) {
        val normalized = viewModel.commitAddress(tabId, rawAddress)
        if (!normalized.equals(BROWSER_BLANK_URL, ignoreCase = true)) {
            webViews[tabId]?.loadUrl(normalized)
        }
    }

    fun addTab() {
        viewModel.addTab()
        tabsMenu = false
    }

    fun closeTab(tabId: Long) {
        cancelFileRequest(tabId)
        viewModel.closeTab(tabId)
    }

    // First apply view-model selections after the dynamic page count has caught up,
    // then listen for real user swipes. Keeping both directions in this single effect
    // prevents a stale settledPage from selecting the old tab while a new page is added.
    LaunchedEffect(pagerState, tabIds, tabsState.currentTabId) {
        val target = tabIds.indexOf(tabsState.currentTabId)
        if (target >= 0) {
            snapshotFlow { pagerState.pageCount }.first { count -> count > target }
            if (pagerState.currentPage != target) {
                pagerState.scrollToPage(target)
            }
        }
        toolbarMenu = false
        tabsMenu = false

        var scrollStarted = false
        snapshotFlow { pagerState.isScrollInProgress to pagerState.settledPage }
            .collect { (scrolling, settledPage) ->
                if (scrolling) {
                    scrollStarted = true
                } else if (scrollStarted) {
                    scrollStarted = false
                    tabIds.getOrNull(settledPage)?.let { settledTabId ->
                        if (settledTabId != tabsState.currentTabId) {
                            viewModel.selectTab(settledTabId)
                        }
                    }
                }
            }
    }

    BackHandler(enabled = currentTab.canGoBack) { currentWebView?.goBack() }

    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    HorizontalPager(
                        state = pagerState,
                        key = { page -> tabIds.getOrNull(page) ?: -(page + 1L) },
                        modifier = Modifier.weight(1f),
                    ) { page ->
                        val tab = tabs.getOrNull(page) ?: return@HorizontalPager
                        BrowserAddressField(
                            tabId = tab.id,
                            address = tab.addressDraft,
                            modifier = Modifier.fillMaxWidth(),
                            onAddressChanged = { value -> viewModel.updateAddressDraft(tab.id, value) },
                            onGo = { value -> loadInTab(tab.id, value) },
                        )
                    }
                    Box {
                        TextButton(onClick = { tabsMenu = true }, modifier = Modifier.size(48.dp)) {
                            Text(tabs.size.toString())
                        }
                        DropdownMenu(expanded = tabsMenu, onDismissRequest = { tabsMenu = false }) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (tabs.size >= MAX_BROWSER_TABS) {
                                            tr("最多打开 8 个网页", "Maximum 8 tabs")
                                        } else {
                                            tr("新建网页", "New tab")
                                        },
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Add, null) },
                                enabled = tabs.size < MAX_BROWSER_TABS,
                                onClick = ::addTab,
                            )
                            tabs.forEachIndexed { index, tab ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(tab.title.ifBlank { tr("网页 ${index + 1}", "Tab ${index + 1}") }, maxLines = 1)
                                            Text(tab.url.ifBlank { tab.addressDraft }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                        }
                                    },
                                    leadingIcon = { Text((index + 1).toString()) },
                                    trailingIcon = {
                                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            if (tab.id == currentTab.id) {
                                                Icon(Icons.Outlined.Check, tr("当前网页", "Current tab"))
                                            }
                                            IconButton(
                                                onClick = { closeTab(tab.id) },
                                                modifier = Modifier.size(40.dp),
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Close,
                                                    tr("关闭网页 ${index + 1}", "Close tab ${index + 1}"),
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        tabsMenu = false
                                        viewModel.selectTab(tab.id)
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { toolbarMenu = true }) {
                            Icon(Icons.Outlined.Menu, tr("浏览器菜单", "Browser menu"))
                        }
                        DropdownMenu(expanded = toolbarMenu, onDismissRequest = { toolbarMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("打开输入的网址", "Open entered URL")) },
                                leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) },
                                onClick = { toolbarMenu = false; loadInTab(currentTab.id, currentTab.addressDraft) },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("主页", "Home")) },
                                leadingIcon = { Icon(Icons.Outlined.Home, null) },
                                onClick = { toolbarMenu = false; loadInTab(currentTab.id, settings.browserHomeUrl) },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("后退", "Back")) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) },
                                enabled = currentTab.canGoBack,
                                onClick = { toolbarMenu = false; currentWebView?.goBack() },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("前进", "Forward")) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, null) },
                                enabled = currentTab.canGoForward,
                                onClick = { toolbarMenu = false; currentWebView?.goForward() },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("刷新", "Refresh")) },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                enabled = currentWebView != null,
                                onClick = { toolbarMenu = false; currentWebView?.reload() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (isFavorite) tr("取消收藏", "Remove favorite") else tr("收藏当前网页", "Favorite this page")) },
                                leadingIcon = { Icon(if (isFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null) },
                                enabled = !isBlankPage,
                                onClick = {
                                    toolbarMenu = false
                                    viewModel.toggleFavorite(currentTab.url, currentTab.title)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("页内查找", "Find in page")) },
                                leadingIcon = { Icon(Icons.Outlined.FindInPage, null) },
                                enabled = currentWebView != null,
                                onClick = { toolbarMenu = false; findDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("浏览历史", "History")) },
                                leadingIcon = { Icon(Icons.Outlined.History, null) },
                                onClick = { toolbarMenu = false; recordsDialog = RecordMode.HISTORY },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("收藏夹", "Favorites")) },
                                leadingIcon = { Icon(Icons.Outlined.Bookmark, null) },
                                onClick = { toolbarMenu = false; recordsDialog = RecordMode.FAVORITES },
                            )
                            DropdownMenuItem(
                                text = {
                                    val label = when (settings.browserTheme) {
                                        BrowserTheme.SYSTEM -> tr("跟随系统", "System")
                                        BrowserTheme.LIGHT -> tr("亮色", "Light")
                                        BrowserTheme.DARK -> tr("暗色", "Dark")
                                    }
                                    Text(tr("网页主题：", "Page theme: ") + label)
                                },
                                leadingIcon = { Icon(Icons.Outlined.DarkMode, null) },
                                onClick = {
                                    toolbarMenu = false
                                    viewModel.setBrowserTheme(
                                        when (settings.browserTheme) {
                                            BrowserTheme.SYSTEM -> BrowserTheme.LIGHT
                                            BrowserTheme.LIGHT -> BrowserTheme.DARK
                                            BrowserTheme.DARK -> BrowserTheme.SYSTEM
                                        },
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (settings.browserDesktopMode) {
                                            tr("电脑模式：已开启", "Desktop mode: on")
                                        } else {
                                            tr("电脑模式：已关闭", "Desktop mode: off")
                                        },
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.DesktopWindows, null) },
                                trailingIcon = if (settings.browserDesktopMode) {
                                    { Icon(Icons.Outlined.Check, null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    toolbarMenu = false
                                    viewModel.setDesktopMode(!settings.browserDesktopMode)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(tr("用外部浏览器打开", "Open externally")) },
                                leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) },
                                enabled = !isBlankPage,
                                onClick = { toolbarMenu = false; openExternal(context, currentTab.url) },
                            )
                        }
                    }
                }
                if (currentTab.loading) {
                    LinearProgressIndicator(
                        progress = { currentTab.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            tabs.forEach { tab ->
                key(tab.id) {
                    val pageModifier = if (tab.id == currentTab.id) {
                        Modifier.fillMaxSize().zIndex(1f)
                    } else {
                        Modifier.size(0.dp).graphicsLayer(alpha = 0f).zIndex(0f)
                    }
                    if (tab.renderProcessGone) {
                        BrowserRendererGonePage(
                            dark = browserUsesDarkTheme,
                            modifier = pageModifier,
                            onReload = { viewModel.reloadAfterRenderProcessGone(tab.id) },
                        )
                    } else if (tab.url.equals(BROWSER_BLANK_URL, ignoreCase = true)) {
                        BrowserStartPage(
                            favorites = favorites,
                            dark = browserUsesDarkTheme,
                            modifier = pageModifier,
                            onOpen = { url -> loadInTab(tab.id, url) },
                        )
                    } else {
                        BrowserWebPage(
                            tabId = tab.id,
                            initialUrl = tab.url,
                            active = tab.id == currentTab.id,
                            dark = browserUsesDarkTheme,
                            desktopMode = settings.browserDesktopMode,
                            modifier = pageModifier,
                            onWebViewCreated = { id, view -> webViews[id] = view },
                            onWebViewDisposed = { id, view ->
                                if (webViews[id] === view) {
                                    cancelFileRequest(id)
                                    webViews.remove(id)
                                }
                            },
                            onRenderProcessGone = viewModel::markRenderProcessGone,
                            onStateChanged = { id, url, title, progress, canGoBack, canGoForward ->
                                viewModel.updateTabBrowserState(
                                    tabId = id,
                                    url = url,
                                    title = title,
                                    progress = progress,
                                    canGoBack = canGoBack,
                                    canGoForward = canGoForward,
                                )
                            },
                            onPageFinished = { id, url, title, canGoBack, canGoForward ->
                                viewModel.pageFinished(
                                    tabId = id,
                                    url = url,
                                    title = title,
                                    canGoBack = canGoBack,
                                    canGoForward = canGoForward,
                                )
                            },
                            onChooseFile = { id, callback, params ->
                                pendingFileRequest?.callback?.let { previous ->
                                    runCatching { previous.onReceiveValue(null) }
                                }
                                pendingFileRequest = PendingFileRequest(id, callback)
                                runCatching {
                                    filePicker.launch(params.createIntent())
                                    true
                                }.getOrElse {
                                    pendingFileRequest = null
                                    runCatching { callback.onReceiveValue(null) }
                                    false
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingFileRequest?.callback?.let { callback ->
                runCatching { callback.onReceiveValue(null) }
            }
            pendingFileRequest = null
        }
    }

    if (findDialog) {
        FindDialog(
            onDismiss = { currentWebView?.clearMatches(); findDialog = false },
            onFind = { currentWebView?.findAllAsync(it) },
            onNext = { currentWebView?.findNext(true) },
            onPrevious = { currentWebView?.findNext(false) },
        )
    }
    recordsDialog?.let { mode ->
        BrowserRecordsDialog(
            title = if (mode == RecordMode.HISTORY) tr("浏览历史", "History") else tr("收藏夹", "Favorites"),
            records = if (mode == RecordMode.HISTORY) history else favorites,
            onDismiss = { recordsDialog = null },
            onOpen = { loadInTab(currentTab.id, it); recordsDialog = null },
            onClear = if (mode == RecordMode.HISTORY) ({ viewModel.clearHistory() }) else null,
        )
    }
}

@Composable
private fun BrowserAddressField(
    tabId: Long,
    address: String,
    modifier: Modifier = Modifier,
    onAddressChanged: (String) -> Unit,
    onGo: (String) -> Unit,
) {
    var fieldValue by remember(tabId) {
        mutableStateOf(TextFieldValue(address, selection = TextRange(address.length)))
    }
    var wasFocused by remember(tabId) { mutableStateOf(false) }

    LaunchedEffect(address) {
        if (address != fieldValue.text) {
            fieldValue = TextFieldValue(address, selection = TextRange(address.length))
        }
    }

    OutlinedTextField(
        value = fieldValue,
        onValueChange = { value ->
            fieldValue = value
            onAddressChanged(value.text)
        },
        singleLine = true,
        modifier = modifier.onFocusChanged { focusState ->
            if (focusState.isFocused && !wasFocused) {
                fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
            }
            wasFocused = focusState.isFocused
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo(fieldValue.text) }),
    )
}

@Composable
private fun BrowserStartPage(
    favorites: List<BrowserRecordEntity>,
    dark: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
) {
    val background = if (dark) Color(0xFF121212) else Color(0xFFF8FAF8)
    val foreground = if (dark) Color(0xFFEAEAEA) else Color(0xFF202320)
    val secondary = if (dark) Color(0xFFB8B8B8) else Color(0xFF5D625D)

    Box(modifier.background(background)) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = null,
            alpha = if (dark) 0.12f else 0.08f,
            modifier = Modifier
                .size(260.dp)
                .align(androidx.compose.ui.Alignment.Center),
        )
        if (favorites.isEmpty()) {
            Text(
                text = tr("暂无书签", "No bookmarks yet"),
                color = secondary,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                item {
                    Text(
                        text = tr("书签", "Bookmarks"),
                        color = foreground,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                items(favorites, key = { it.url }) { favorite ->
                    TextButton(
                        onClick = { onOpen(favorite.url) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                text = favorite.title.ifBlank { favorite.url },
                                color = foreground,
                                maxLines = 1,
                            )
                            Text(
                                text = favorite.url,
                                color = secondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRendererGonePage(
    dark: Boolean,
    modifier: Modifier = Modifier,
    onReload: () -> Unit,
) {
    val background = if (dark) Color(0xFF121212) else Color(0xFFF8FAF8)
    val foreground = if (dark) Color(0xFFEAEAEA) else Color(0xFF202320)
    val secondary = if (dark) Color(0xFFB8B8B8) else Color(0xFF5D625D)

    Box(
        modifier = modifier.background(background),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(
                text = tr("网页渲染进程已停止", "The page renderer stopped"),
                color = foreground,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = tr("应用仍可继续使用，点击后再重新加载此网页。", "The app can continue. Reload this page when ready."),
                color = secondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(
                onClick = onReload,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Icon(Icons.Outlined.Refresh, null)
                Text(tr("重新加载", "Reload"))
            }
        }
    }
}

private data class PendingFileRequest(
    val tabId: Long,
    val callback: ValueCallback<Array<Uri>>,
)

@Composable
private fun BrowserWebPage(
    tabId: Long,
    initialUrl: String,
    active: Boolean,
    dark: Boolean,
    desktopMode: Boolean,
    modifier: Modifier,
    onWebViewCreated: (Long, WebView) -> Unit,
    onWebViewDisposed: (Long, WebView) -> Unit,
    onRenderProcessGone: (Long, Boolean) -> Unit,
    onStateChanged: (Long, String, String, Int, Boolean, Boolean) -> Unit,
    onPageFinished: (Long, String, String, Boolean, Boolean) -> Unit,
    onChooseFile: (Long, ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
) {
    val baseContext = LocalContext.current
    val latestOnCreated by rememberUpdatedState(onWebViewCreated)
    val latestOnDisposed by rememberUpdatedState(onWebViewDisposed)
    val latestOnRenderProcessGone by rememberUpdatedState(onRenderProcessGone)
    val latestOnStateChanged by rememberUpdatedState(onStateChanged)
    val latestOnPageFinished by rememberUpdatedState(onPageFinished)
    val latestOnChooseFile by rememberUpdatedState(onChooseFile)
    val renderConfig = BrowserRenderingConfig(dark = dark, desktopMode = desktopMode)
    val releaseGuard = remember(tabId) { WebViewReleaseGuard() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            releaseGuard.released = false
            WebView(MutableContextWrapper(browserThemedContext(ctx, dark))).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = true
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                applyBrowserRenderingConfig(this, renderConfig)
                tag = renderConfig
                latestOnCreated(tabId, this)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    WebView.startSafeBrowsing(ctx, null)
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        return if (uri.scheme == "http" || uri.scheme == "https") {
                            false
                        } else {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            true
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        latestOnPageFinished(
                            tabId,
                            url,
                            view.title.orEmpty(),
                            view.canGoBack(),
                            view.canGoForward(),
                        )
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        handler.cancel()
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        latestOnDisposed(tabId, view)
                        releaseWebView(view, releaseGuard, rendererProcessGone = true)
                        latestOnRenderProcessGone(tabId, detail.didCrash())
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        latestOnStateChanged(
                            tabId,
                            view.url.orEmpty(),
                            view.title.orEmpty(),
                            newProgress,
                            view.canGoBack(),
                            view.canGoForward(),
                        )
                    }

                    override fun onShowFileChooser(
                        webView: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams,
                    ): Boolean = latestOnChooseFile(tabId, filePathCallback, fileChooserParams)
                }
                setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                    enqueueDownload(ctx, url, userAgent, contentDisposition, mimeType)
                })
                loadUrl(initialUrl)
            }
        },
        update = { view ->
            val previousConfig = view.tag as? BrowserRenderingConfig
            if (previousConfig != renderConfig) {
                val shouldReload = !view.url.isNullOrBlank()
                if (shouldReload) view.stopLoading()
                if (previousConfig?.dark != renderConfig.dark) {
                    (view.context as? MutableContextWrapper)?.baseContext =
                        browserThemedContext(baseContext, renderConfig.dark)
                }
                applyBrowserRenderingConfig(view, renderConfig)
                view.tag = renderConfig
                if (shouldReload) view.reload()
            }
            if (active) view.onResume() else view.onPause()
        },
        onRelease = { view ->
            latestOnDisposed(tabId, view)
            releaseWebView(view, releaseGuard)
        },
    )
}

private class WebViewReleaseGuard(var released: Boolean = false)

private fun releaseWebView(
    view: WebView,
    guard: WebViewReleaseGuard,
    rendererProcessGone: Boolean = false,
) {
    if (guard.released) return
    guard.released = true

    (view.parent as? ViewGroup)?.removeView(view)
    if (!rendererProcessGone) {
        view.stopLoading()
        view.onPause()
        view.setDownloadListener(null)
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.clearHistory()
        view.removeAllViews()
    }
    view.destroy()
}

private data class BrowserRenderingConfig(
    val dark: Boolean,
    val desktopMode: Boolean,
)

private fun browserThemedContext(base: Context, dark: Boolean): Context {
    val overrideConfiguration = Configuration(base.resources.configuration).apply {
        val nightMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    }
    val configuredContext = base.createConfigurationContext(overrideConfiguration)
    val theme = if (dark) {
        android.R.style.Theme_Material_NoActionBar
    } else {
        android.R.style.Theme_Material_Light_NoActionBar
    }
    return ContextThemeWrapper(configuredContext, theme)
}

@Suppress("DEPRECATION")
private fun applyBrowserRenderingConfig(view: WebView, config: BrowserRenderingConfig) {
    val webSettings = view.settings
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(webSettings, config.dark)
    } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
        WebSettingsCompat.setForceDark(
            webSettings,
            if (config.dark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF,
        )
    }

    val desiredUserAgent = if (config.desktopMode) {
        desktopUserAgent(view.context)
    } else {
        WebSettings.getDefaultUserAgent(view.context)
    }
    if (webSettings.userAgentString != desiredUserAgent) {
        webSettings.userAgentString = desiredUserAgent
    }
    webSettings.useWideViewPort = config.desktopMode
    webSettings.loadWithOverviewMode = config.desktopMode
    view.setBackgroundColor(if (config.dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())
}

private fun desktopUserAgent(context: Context): String = WebSettings.getDefaultUserAgent(context)
    .replace(Regex("\\(Linux; Android[^)]*\\)"), "(X11; Linux x86_64)")
    .replace("; wv", "")
    .replace(" Version/4.0", "")
    .replace(" Mobile ", " ")

private enum class RecordMode { HISTORY, FAVORITES }

@Composable
private fun FindDialog(onDismiss: () -> Unit, onFind: (String) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("网页内查找", "Find in page")) },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it; onFind(it) }, singleLine = true)
                Row {
                    TextButton(onClick = onPrevious) { Text(tr("上一个", "Previous")) }
                    TextButton(onClick = onNext) { Text(tr("下一个", "Next")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("完成", "Done")) } },
    )
}

@Composable
private fun BrowserRecordsDialog(
    title: String,
    records: List<BrowserRecordEntity>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (records.isEmpty()) Text(tr("暂无记录", "No records")) else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(records, key = { it.url }) { item ->
                    TextButton(onClick = { onOpen(item.url) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(item.title, maxLines = 1)
                            Text(item.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("关闭", "Close")) } },
        dismissButton = onClear?.let { clear ->
            @Composable { TextButton(onClick = clear) { Icon(Icons.Outlined.DeleteSweep, null); Text(tr("清空", "Clear")) } }
        },
    )
}

private fun openExternal(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "没有可用的外部浏览器", Toast.LENGTH_SHORT).show() }
}

private fun enqueueDownload(context: Context, url: String, userAgent: String?, disposition: String?, mimeType: String?) {
    runCatching {
        val safeName = URLUtil.guessFileName(url, disposition, mimeType).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .setTitle(safeName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
        userAgent?.takeIf(String::isNotBlank)?.let { request.addRequestHeader("User-Agent", it) }
        CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)?.let { request.addRequestHeader("Cookie", it) }
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(context, "已加入下载队列", Toast.LENGTH_SHORT).show()
    }.onFailure { Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_SHORT).show() }
}
