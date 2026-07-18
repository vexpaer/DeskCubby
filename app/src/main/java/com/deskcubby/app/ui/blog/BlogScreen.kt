@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.blog

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.data.preferences.SettingsRepository

@Composable
fun BlogScreen(
    padding: PaddingValues,
    viewModel: BlogViewModel,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf("") }
    var findDialog by remember { mutableStateOf(false) }
    var recordsDialog by remember { mutableStateOf<RecordMode?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    val isFavorite = favorites.any { it.url == state.url }
    LaunchedEffect(state.url) { if (state.url.isNotBlank()) address = state.url }
    BackHandler(enabled = state.canGoBack) { webView?.goBack() }

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
                    IconButton(onClick = { webView?.goBack() }, enabled = state.canGoBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "后退") }
                    IconButton(onClick = { webView?.goForward() }, enabled = state.canGoForward) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "前进") }
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            IconButton(onClick = { webView?.loadUrl(SettingsRepository.normalizeUrl(address)) }) {
                                Icon(Icons.Outlined.OpenInBrowser, "打开")
                            }
                        },
                    )
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(if (isFavorite) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, "收藏")
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { webView?.loadUrl(settings.browserHomeUrl) }) { Icon(Icons.Outlined.Home, "主页") }
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, "刷新") }
                    IconButton(onClick = { findDialog = true }) { Icon(Icons.Outlined.FindInPage, "页内查找") }
                    IconButton(onClick = { recordsDialog = RecordMode.HISTORY }) { Icon(Icons.Outlined.History, "历史") }
                    IconButton(onClick = { recordsDialog = RecordMode.FAVORITES }) { Icon(Icons.Outlined.Bookmark, "收藏夹") }
                    IconButton(onClick = { openExternal(context, state.url) }) { Icon(Icons.Outlined.OpenInBrowser, "外部浏览器") }
                }
                if (state.loading) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        },
    ) { inner ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(inner),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    this.settings.databaseEnabled = true
                    this.settings.allowFileAccess = false
                    this.settings.allowContentAccess = true
                    this.settings.allowFileAccessFromFileURLs = false
                    this.settings.allowUniversalAccessFromFileURLs = false
                    this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    this.settings.setSupportZoom(true)
                    this.settings.builtInZoomControls = true
                    this.settings.displayZoomControls = false
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
                            viewModel.pageFinished(url, view.title.orEmpty())
                            viewModel.updateBrowserState(canGoBack = view.canGoBack(), canGoForward = view.canGoForward())
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            Toast.makeText(ctx, "证书验证失败，已停止加载", Toast.LENGTH_SHORT).show()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            viewModel.updateBrowserState(
                                url = view.url.orEmpty(),
                                title = view.title.orEmpty(),
                                progress = newProgress,
                                canGoBack = view.canGoBack(),
                                canGoForward = view.canGoForward(),
                            )
                        }

                        override fun onShowFileChooser(
                            webView: WebView,
                            filePathCallback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams,
                        ): Boolean {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = filePathCallback
                            return runCatching { filePicker.launch(fileChooserParams.createIntent()); true }
                                .getOrElse { fileCallback = null; filePathCallback.onReceiveValue(null); false }
                        }
                    }
                    setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                        enqueueDownload(ctx, url, userAgent, contentDisposition, mimeType)
                    })
                    val initial = settings.lastBrowserUrl ?: settings.browserHomeUrl
                    loadUrl(initial)
                }
            },
            update = {},
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            webView?.apply { stopLoading(); webChromeClient = null; clearHistory(); destroy() }
            webView = null
        }
    }

    if (findDialog) {
        FindDialog(
            onDismiss = { webView?.clearMatches(); findDialog = false },
            onFind = { webView?.findAllAsync(it) },
            onNext = { webView?.findNext(true) },
            onPrevious = { webView?.findNext(false) },
        )
    }
    recordsDialog?.let { mode ->
        BrowserRecordsDialog(
            title = if (mode == RecordMode.HISTORY) "浏览历史" else "收藏夹",
            records = if (mode == RecordMode.HISTORY) history else favorites,
            onDismiss = { recordsDialog = null },
            onOpen = { webView?.loadUrl(it); recordsDialog = null },
            onClear = if (mode == RecordMode.HISTORY) ({ viewModel.clearHistory() }) else null,
        )
    }
}

private enum class RecordMode { HISTORY, FAVORITES }

@Composable
private fun FindDialog(onDismiss: () -> Unit, onFind: (String) -> Unit, onNext: () -> Unit, onPrevious: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网页内查找") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it; onFind(it) }, singleLine = true)
                Row {
                    TextButton(onClick = onPrevious) { Text("上一个") }
                    TextButton(onClick = onNext) { Text("下一个") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
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
            if (records.isEmpty()) Text("暂无记录") else LazyColumn(Modifier.heightIn(max = 420.dp)) {
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = onClear?.let { clear ->
            @Composable { TextButton(onClick = clear) { Icon(Icons.Outlined.DeleteSweep, null); Text("清空") } }
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
