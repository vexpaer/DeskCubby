@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.deskcubby.app.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.repository.ReaderBackground
import com.deskcubby.app.data.repository.ReaderBook
import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.repository.ReaderContent
import com.deskcubby.app.data.repository.ReaderOrientation
import com.deskcubby.app.data.repository.ReaderPreferences
import com.deskcubby.app.data.statistics.EngagementKind
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

@Composable
fun ReaderScreen(
    padding: PaddingValues,
    viewModel: ReaderViewModel,
    onReadingChanged: (Boolean) -> Unit = {},
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val times by viewModel.engagementTimes.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val localizedMessage = message?.let { readerMessageText(it) }
    val snackbar = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    val reading = content !is ReaderContentState.Idle

    LaunchedEffect(reading) { onReadingChanged(reading) }
    DisposableEffect(Unit) { onDispose { onReadingChanged(false) } }
    LaunchedEffect(localizedMessage) {
        localizedMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    when (val current = content) {
        ReaderContentState.Idle -> ReaderLibrary(
            padding = padding,
            books = library.books,
            totals = { id -> times.total(EngagementKind.READING, id) },
            onImport = { importLauncher.launch(arrayOf("text/plain", "application/pdf")) },
            onOpen = viewModel::open,
            onRemove = viewModel::remove,
            snackbar = snackbar,
        )
        ReaderContentState.Loading -> ReaderLoadingPage(onBack = viewModel::close)
        is ReaderContentState.Failed -> ReaderFailurePage(
            book = current.book,
            message = tr(
                "无法读取文件，请重新授权或重新导入。",
                "The file could not be read. Grant access again or re-import it.",
            ),
            onBack = viewModel::close,
            onRetry = { viewModel.open(current.book) },
        )
        is ReaderContentState.Ready -> ReaderBookPage(
            book = current.book,
            content = current.content,
            preferences = library.preferences,
            totalMillis = times.total(EngagementKind.READING, current.book.id),
            viewModel = viewModel,
            onBack = viewModel::close,
        )
    }
}

@Composable
private fun ReaderLibrary(
    padding: PaddingValues,
    books: List<ReaderBook>,
    totals: (String) -> Long,
    onImport: () -> Unit,
    onOpen: (ReaderBook) -> Unit,
    onRemove: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    var pendingDelete by remember { mutableStateOf<ReaderBook?>(null) }
    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = { TopAppBar(title = { Text(tr("阅读", "Reader")) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onImport) {
                Icon(Icons.Outlined.Add, tr("导入 TXT 或 PDF", "Import TXT or PDF"))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        if (books.isEmpty()) {
            AppEmptyState(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = tr("书架还是空的", "Your library is empty"),
                description = tr(
                    "导入 TXT 或 PDF 小说；文件保留在原位置，DeskCubby 只保存读取授权、进度与设置。",
                    "Import a TXT or PDF book. The file stays in place; DeskCubby stores only access, progress, and settings.",
                ),
                actionLabel = tr("导入小说", "Import a book"),
                onAction = onImport,
                modifier = Modifier.fillMaxSize().padding(inner),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(books.size, key = { books[it].id }) { index ->
                    val book = books[index]
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(book) },
                        cornerRadius = 20.dp,
                        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (book.type == ReaderBookType.PDF) Icons.Outlined.PictureAsPdf else Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(
                                    tr("累计阅读 ", "Total reading ") + formatDuration(totals(book.id)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { pendingDelete = book }) {
                                Icon(Icons.Outlined.Delete, tr("从书架移除", "Remove from library"))
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(tr("移除书籍？", "Remove book?")) },
            text = { Text(tr("只移除书架记录，不会删除原文件。", "This removes only the library entry, not the original file.")) },
            confirmButton = {
                TextButton(onClick = { onRemove(book.id); pendingDelete = null }) {
                    Text(tr("移除", "Remove"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun ReaderLoadingPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("正在打开", "Opening")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回书架", "Back to library"))
                    }
                },
            )
        },
    ) { inner ->
        Box(
            Modifier.fillMaxSize().padding(inner),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }
}

@Composable
private fun ReaderFailurePage(book: ReaderBook, message: String, onBack: () -> Unit, onRetry: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回书架", "Back to library"))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(tr("重试", "Retry")) }
        }
    }
}

@Composable
private fun ReaderBookPage(
    book: ReaderBook,
    content: ReaderContent,
    preferences: ReaderPreferences,
    totalMillis: Long,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
) {
    val (background, foreground) = readerColors(preferences.background)
    var showSettings by remember { mutableStateOf(false) }
    val activity = LocalContext.current.findActivity()
    BackHandler(onBack = onBack)
    ReaderOrientationEffect(activity, preferences.orientation)
    ReaderTimingEffect(book.id, viewModel)

    Scaffold(
        containerColor = background,
        contentColor = foreground,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回书架", "Back to library"))
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Outlined.Settings, tr("阅读设置", "Reading settings"))
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    titleContentColor = foreground,
                    navigationIconContentColor = foreground,
                    actionIconContentColor = foreground,
                ),
            )
        },
    ) { inner ->
        when (content) {
            is ReaderContent.TextBook -> TextReader(
                book = book,
                paragraphs = content.paragraphs,
                preferences = preferences,
                foreground = foreground,
                contentPadding = inner,
                onProgress = { viewModel.saveTextProgress(book.id, it) },
            )
            is ReaderContent.PdfBook -> PdfReader(
                book = book,
                pageCount = content.pageCount,
                background = background,
                foreground = foreground,
                contentPadding = inner,
                viewModel = viewModel,
            )
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            initial = preferences,
            totalMillis = totalMillis,
            onDismiss = { showSettings = false },
            onSave = {
                viewModel.updatePreferences(it)
                showSettings = false
            },
        )
    }
}

@Composable
private fun TextReader(
    book: ReaderBook,
    paragraphs: List<String>,
    preferences: ReaderPreferences,
    foreground: Color,
    contentPadding: PaddingValues,
    onProgress: (Int) -> Unit,
) {
    val initial = book.textParagraphIndex.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
    val currentOnProgress by rememberUpdatedState(onProgress)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { index ->
                delay(600L)
                currentOnProgress(index)
            }
    }
    DisposableEffect(listState) {
        onDispose { currentOnProgress(listState.firstVisibleItemIndex) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
    ) {
        items(paragraphs.size, key = { it }) { index ->
            Text(
                text = paragraphs[index],
                color = foreground,
                fontSize = preferences.fontSizeSp.sp,
                lineHeight = (preferences.fontSizeSp * preferences.lineHeightMultiplier).sp,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.padding(bottom = preferences.paragraphSpacingDp.dp),
            )
        }
    }
}

@Composable
private fun PdfReader(
    book: ReaderBook,
    pageCount: Int,
    background: Color,
    foreground: Color,
    contentPadding: PaddingValues,
    viewModel: ReaderViewModel,
) {
    val pagerState = rememberPagerState(
        initialPage = book.pdfPageIndex.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { viewModel.savePdfProgress(book.id, it) }
    }
    DisposableEffect(pagerState) {
        onDispose { viewModel.savePdfProgress(book.id, pagerState.currentPage) }
    }
    Box(Modifier.fillMaxSize().padding(contentPadding).background(background)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            PdfPage(book, page, viewModel)
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            shape = RoundedCornerShape(50),
            color = background.copy(alpha = 0.9f),
            contentColor = foreground,
        ) {
            Text("${pagerState.currentPage + 1} / $pageCount", Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun PdfPage(book: ReaderBook, page: Int, viewModel: ReaderViewModel) {
    val rendered by produceState<Result<Bitmap>?>(null, book.id, page) {
        value = runCatching { viewModel.renderPdfPage(book, page, 1_100) }
    }
    val bitmap = rendered?.getOrNull()
    DisposableEffect(bitmap) {
        onDispose { if (bitmap != null && !bitmap.isRecycled) bitmap.recycle() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            rendered == null -> CircularProgressIndicator()
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = tr("PDF 第 ${page + 1} 页", "PDF page ${page + 1}"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            else -> Text(tr("这一页无法显示", "This page could not be rendered"), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    initial: ReaderPreferences,
    totalMillis: Long,
    onDismiss: () -> Unit,
    onSave: (ReaderPreferences) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("阅读设置", "Reading settings")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(tr("累计阅读：", "Total reading: ") + formatDuration(totalMillis))
                }
                item {
                    Text(tr("背景颜色", "Background"), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderBackground.entries.forEach { background ->
                            val colors = readerColors(background)
                            Surface(
                                modifier = Modifier.size(42.dp).clickable { draft = draft.copy(background = background) },
                                shape = CircleShape,
                                color = colors.first,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (draft.background == background) 3.dp else 1.dp,
                                    if (draft.background == background) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                            ) {}
                        }
                    }
                }
                item {
                    SettingSlider(
                        label = tr("字体大小", "Font size"),
                        valueText = "${draft.fontSizeSp.toInt()} sp",
                        value = draft.fontSizeSp,
                        range = 12f..38f,
                        steps = 25,
                    ) { draft = draft.copy(fontSizeSp = it) }
                }
                item {
                    SettingSlider(
                        label = tr("行间距", "Line spacing"),
                        valueText = String.format(Locale.ROOT, "%.1f×", draft.lineHeightMultiplier),
                        value = draft.lineHeightMultiplier,
                        range = 1f..2.4f,
                        steps = 13,
                    ) { draft = draft.copy(lineHeightMultiplier = (it * 10).toInt() / 10f) }
                }
                item {
                    SettingSlider(
                        label = tr("段间距", "Paragraph spacing"),
                        valueText = "${draft.paragraphSpacingDp.toInt()} dp",
                        value = draft.paragraphSpacingDp,
                        range = 0f..36f,
                        steps = 17,
                    ) { draft = draft.copy(paragraphSpacingDp = it) }
                }
                item {
                    Text(tr("屏幕方向", "Screen orientation"), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderOrientation.entries.forEach { orientation ->
                            FilterChip(
                                selected = draft.orientation == orientation,
                                onClick = { draft = draft.copy(orientation = orientation) },
                                label = {
                                    Text(when (orientation) {
                                        ReaderOrientation.FOLLOW_SYSTEM -> tr("跟随系统", "System")
                                        ReaderOrientation.PORTRAIT -> tr("竖屏", "Portrait")
                                        ReaderOrientation.LANDSCAPE -> tr("横屏", "Landscape")
                                    })
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(tr("保存", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun SettingSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueText, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun ReaderTimingEffect(bookId: String, viewModel: ReaderViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bookId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.beginReading(bookId)
                Lifecycle.Event.ON_PAUSE -> viewModel.endReading(bookId)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.beginReading(bookId)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.endReading(bookId)
        }
    }
    LaunchedEffect(bookId) {
        while (isActive) {
            delay(30_000L)
            viewModel.checkpointReading(bookId)
        }
    }
}

@Composable
private fun ReaderOrientationEffect(activity: Activity?, orientation: ReaderOrientation) {
    DisposableEffect(activity, orientation) {
        if (activity != null) {
            activity.requestedOrientation = when (orientation) {
                ReaderOrientation.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        onDispose {
            if (activity != null && !activity.isFinishing) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun readerColors(background: ReaderBackground): Pair<Color, Color> = when (background) {
    ReaderBackground.WHITE -> Color(0xFFFFFFFF) to Color(0xFF202124)
    ReaderBackground.PAPER -> Color(0xFFF4F0E6) to Color(0xFF332E28)
    ReaderBackground.SEPIA -> Color(0xFFE8D6B0) to Color(0xFF3B2C1E)
    ReaderBackground.GREEN -> Color(0xFFDDE8D7) to Color(0xFF203126)
    ReaderBackground.NIGHT -> Color(0xFF171A1C) to Color(0xFFE2E0DA)
}

@Composable
private fun readerMessageText(message: ReaderMessage): String = when (message) {
    ReaderMessage.IMPORT_FAILED -> tr(
        "导入失败。请选择可读取的 TXT（不超过 32 MiB）或 PDF 文件。",
        "Import failed. Choose a readable TXT (up to 32 MiB) or PDF file.",
    )
    ReaderMessage.REMOVE_FAILED -> tr("无法从书架移除书籍", "Could not remove the book from the library")
    ReaderMessage.SETTINGS_FAILED -> tr("无法保存阅读设置", "Could not save reading settings")
}

@Composable
private fun formatDuration(millis: Long): String {
    val minutes = (millis.coerceAtLeast(0L) / 60_000L)
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours > 0 -> tr("${hours}小时${remaining}分钟", "${hours}h ${remaining}m")
        minutes > 0 -> tr("${minutes}分钟", "${minutes}m")
        else -> tr("不足 1 分钟", "< 1m")
    }
}
