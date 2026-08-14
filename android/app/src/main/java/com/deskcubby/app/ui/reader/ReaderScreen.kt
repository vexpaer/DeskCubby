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
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.repository.ReaderBackground
import com.deskcubby.app.data.repository.ReaderBook
import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.repository.ReaderLibraryLayout
import com.deskcubby.app.data.repository.ReaderContent
import com.deskcubby.app.data.repository.ReaderChapter
import com.deskcubby.app.data.repository.ReaderChapterDetectionMode
import com.deskcubby.app.data.repository.ReaderTextPage
import com.deskcubby.app.data.repository.ReaderOrientation
import com.deskcubby.app.data.repository.ReaderPreferences
import com.deskcubby.app.data.repository.ReaderStorageIssue
import com.deskcubby.app.data.repository.MAX_READER_CHAPTER_TITLE_CHARS
import com.deskcubby.app.data.repository.MAX_READER_SEARCH_QUERY_CHARS
import com.deskcubby.app.data.repository.MIN_READER_CHAPTER_HEADING_CHARS
import com.deskcubby.app.data.repository.MAX_READER_CUSTOM_REGEX_CHARS
import com.deskcubby.app.data.repository.MAX_READER_PDF_ZOOM_PERCENT
import com.deskcubby.app.data.repository.MIN_READER_PDF_ZOOM_PERCENT
import com.deskcubby.app.data.repository.isValidReaderChapterRegex
import com.deskcubby.app.data.repository.findReaderTextMatches
import com.deskcubby.app.data.statistics.EngagementKind
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.ColorPickerDialog
import com.deskcubby.app.ui.components.PageTutorialTarget
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.tr
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    padding: PaddingValues,
    viewModel: ReaderViewModel,
    onReadingChanged: (Boolean) -> Unit = {},
    onTutorialTargetChanged: (PageTutorialTarget?) -> Unit = {},
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val times by viewModel.engagementTimes.collectAsStateWithLifecycle()
    val storageIssue by viewModel.storageIssue.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val localizedMessage = message?.let { readerMessageText(it) }
    val snackbar = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    val reading = content !is ReaderContentState.Idle
    val tutorialTarget = readerTutorialTarget(content)

    LaunchedEffect(reading) { onReadingChanged(reading) }
    LaunchedEffect(tutorialTarget) { onTutorialTargetChanged(tutorialTarget) }
    DisposableEffect(Unit) {
        onDispose {
            onReadingChanged(false)
            onTutorialTargetChanged(null)
        }
    }
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
            preferences = library.preferences,
            storageIssue = storageIssue,
            totals = { id -> times.total(EngagementKind.READING, id) },
            onImport = { importLauncher.launch(arrayOf("text/plain", "application/pdf")) },
            onOpen = viewModel::open,
            onRemove = viewModel::remove,
            onUpdatePreferences = viewModel::updatePreferences,
            onSetCustomCover = viewModel::setCustomCover,
            loadCover = viewModel::loadCover,
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
    preferences: ReaderPreferences,
    storageIssue: ReaderStorageIssue?,
    totals: (String) -> Long,
    onImport: () -> Unit,
    onOpen: (ReaderBook) -> Unit,
    onRemove: (String) -> Unit,
    onUpdatePreferences: (ReaderPreferences) -> Unit,
    onSetCustomCover: (String, android.net.Uri?) -> Unit,
    loadCover: suspend (ReaderBook, Int) -> Bitmap?,
    snackbar: SnackbarHostState,
) {
    var pendingDelete by remember { mutableStateOf<ReaderBook?>(null) }
    var coverBook by remember { mutableStateOf<ReaderBook?>(null) }
    var showShelfSettings by remember { mutableStateOf(false) }
    val coverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selectedBook = coverBook
        if (selectedBook != null && uri != null) {
            onSetCustomCover(selectedBook.id, uri)
        }
        coverBook = null
    }
    Scaffold(
        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(tr("阅读", "Reader")) },
                actions = {
                    IconButton(onClick = { showShelfSettings = true }) {
                        Icon(Icons.Outlined.Settings, tr("书架设置", "Shelf settings"))
                    }
                },
            )
        },
        floatingActionButton = {
            if (storageIssue == null) {
                FloatingActionButton(onClick = onImport) {
                    Icon(Icons.Outlined.Add, tr("导入 TXT 或 PDF", "Import TXT or PDF"))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        if (storageIssue != null) {
            AppEmptyState(
                icon = Icons.Outlined.WarningAmber,
                title = tr("书架状态需要修复", "Library state needs repair"),
                description = tr(
                    "书架 JSON 损坏、超限或无法安全提交。为保护已有记录，应用已停止修改该文件；TXT/PDF 原文件没有被更改。",
                    "The library JSON is damaged, too large, or could not be committed safely. Changes are blocked to preserve it; the original TXT/PDF files were not modified.",
                ),
                modifier = Modifier.fillMaxSize().padding(inner),
            )
        } else if (books.isEmpty()) {
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
        } else if (preferences.libraryLayout == ReaderLibraryLayout.LIST) {
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
                                if (book.type == ReaderBookType.PDF) {
                                    Icons.Outlined.PictureAsPdf
                                } else {
                                    Icons.AutoMirrored.Outlined.MenuBook
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text(
                                    buildString {
                                        append(tr("累计阅读 ", "Total reading "))
                                        append(formatDuration(totals(book.id)))
                                        if (preferences.showProgressPercentage) {
                                            append(tr(" · 进度 ", " · Progress "))
                                            append(readerBookProgressPercent(book))
                                            append('%')
                                        }
                                    },
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
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = 12.dp,
                    bottom = 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                gridItems(books, key = ReaderBook::id) { book ->
                    GlassPanel(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(book) },
                        cornerRadius = 20.dp,
                        padding = PaddingValues(10.dp),
                    ) {
                        Column {
                            Box {
                                ReaderCover(
                                    book = book,
                                    loadCover = loadCover,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                                )
                                Surface(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                ) {
                                    Row {
                                        IconButton(
                                            onClick = { coverBook = book },
                                            modifier = Modifier.size(38.dp),
                                        ) {
                                            Icon(Icons.Outlined.Image, tr("修改封面", "Change cover"))
                                        }
                                        IconButton(
                                            onClick = { pendingDelete = book },
                                            modifier = Modifier.size(38.dp),
                                        ) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                tr("从书架移除", "Remove from library"),
                                            )
                                        }
                                    }
                                }
                            }
                            if (preferences.showGridBookTitles ||
                                preferences.showProgressPercentage
                            ) {
                                Spacer(Modifier.height(8.dp))
                            }
                            if (preferences.showGridBookTitles) {
                                Text(
                                    book.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (preferences.showProgressPercentage) {
                                Text(
                                    tr("进度 ", "Progress ") +
                                        readerBookProgressPercent(book) + "%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    coverBook?.let { book ->
        AlertDialog(
            onDismissRequest = { coverBook = null },
            title = { Text(tr("修改封面", "Change cover")) },
            text = {
                Text(
                    if (book.type == ReaderBookType.PDF) {
                        tr(
                            "默认优先使用系统文档服务提供的安全缩略图；也可以选择一张图片覆盖。",
                            "A safe thumbnail from the system document service is preferred by default, or you can override it with an image.",
                        )
                    } else {
                        tr(
                            "可以选择一张图片作为 TXT 书籍封面。",
                            "Choose an image to use as this TXT book's cover.",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { coverLauncher.launch(arrayOf("image/*")) }) {
                    Text(tr("选择图片", "Choose image"))
                }
            },
            dismissButton = {
                Row {
                    if (book.coverUri != null) {
                        TextButton(onClick = {
                            onSetCustomCover(book.id, null)
                            coverBook = null
                        }) {
                            Text(
                                if (book.type == ReaderBookType.PDF) {
                                    tr("恢复自动封面", "Use automatic cover")
                                } else {
                                    tr("移除封面", "Remove cover")
                                },
                            )
                        }
                    }
                    TextButton(onClick = { coverBook = null }) { Text(tr("取消", "Cancel")) }
                }
            },
        )
    }

    if (showShelfSettings) {
        ReaderShelfSettingsDialog(
            initial = preferences,
            onDismiss = { showShelfSettings = false },
            onSave = {
                onUpdatePreferences(it)
                showShelfSettings = false
            },
        )
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
    val (background, foreground) = readerColors(
        preferences.background,
        preferences.customBackgroundArgb,
        preferences.customForegroundArgb,
    )
    var showSettings by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showSearch by rememberSaveable(book.id) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(book.id) { mutableStateOf("") }
    var selectedSearchIndex by rememberSaveable(book.id) { mutableIntStateOf(0) }
    var pdfSearchResultCount by remember(book.id) { mutableIntStateOf(0) }
    var pdfChapters by remember(book.id) { mutableStateOf<List<ReaderChapter>>(emptyList()) }
    var pdfChapterScanRunning by remember(book.id) { mutableStateOf(false) }
    // A transient sandbox/service timeout belongs only to this reading attempt. Saving this flag
    // made one cold-start failure permanently force compatibility mode for the rest of the
    // Activity instance, so users had no way to try the enhanced reader again.
    var enhancedPdfReaderUnavailable by remember(book.id) { mutableStateOf(false) }
    var pdfFallbackNoticeCount by remember(book.id) { mutableIntStateOf(0) }
    var requestedPage by remember(book.id) { mutableStateOf<Int?>(null) }
    var controlsVisible by rememberSaveable(book.id) {
        mutableStateOf(!preferences.immersiveMode)
    }
    val showReaderControls = !preferences.immersiveMode || controlsVisible
    val centerControlDescription = if (showReaderControls) {
        tr("阅读组件已显示", "Reading controls are visible")
    } else {
        tr("阅读组件已隐藏", "Reading controls are hidden")
    }
    val centerControlActionLabel = if (showReaderControls) {
        tr("隐藏阅读组件", "Hide reading controls")
    } else {
        tr("显示阅读组件", "Show reading controls")
    }
    val textContent = content as? ReaderContent.TextBook
    var currentPage by rememberSaveable(book.id) {
        mutableIntStateOf(
            when (content) {
                is ReaderContent.TextBook -> book.textPageIndex.coerceAtLeast(0)
                is ReaderContent.PdfBook -> book.pdfPageIndex.coerceAtLeast(0)
            },
        )
    }
    val totalPages = when (content) {
        is ReaderContent.TextBook -> content.pages.size
        is ReaderContent.PdfBook -> content.pageCount
    }.coerceAtLeast(1)
    val textSearchMatches = remember(textContent, searchQuery) {
        textContent?.let { findReaderTextMatches(it.pages, searchQuery) }.orEmpty()
    }
    val searchResultCount = if (textContent != null) {
        textSearchMatches.size
    } else {
        pdfSearchResultCount
    }
    val pdfRendererMode = selectReaderPdfRendererMode(
        enhancedReaderUnavailable = enhancedPdfReaderUnavailable,
    )
    val textLayerAvailable = content !is ReaderContent.PdfBook ||
        pdfRendererMode == ReaderPdfRendererMode.ENHANCED
    val chapters = textContent?.chapters ?: pdfChapters
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val bookSnackbar = remember { SnackbarHostState() }
    val pdfFallbackMessage = tr(
        "增强 PDF 视图未能加载，已自动切换到兼容视图。",
        "The enhanced PDF view could not load. Switched to the compatibility view.",
    )
    val pdfRetryLabel = tr("重试增强视图", "Retry enhanced view")
    val activity = LocalContext.current.findActivity()
    BackHandler {
        if (drawerState.isOpen) scope.launch { drawerState.close() } else onBack()
    }
    ReaderOrientationEffect(activity, preferences.orientation)
    ReaderSystemBarsEffect(activity, hideSystemBars = preferences.immersiveMode && !controlsVisible)
    ReaderTimingEffect(book.id, book.title, viewModel)

    LaunchedEffect(preferences.immersiveMode) {
        controlsVisible = !preferences.immersiveMode
    }

    LaunchedEffect(searchQuery, textSearchMatches) {
        selectedSearchIndex = 0
        if (searchQuery.isNotBlank() && textSearchMatches.isNotEmpty()) {
            requestedPage = textSearchMatches.first().pageIndex
        }
    }
    LaunchedEffect(textLayerAvailable) {
        if (!textLayerAvailable) {
            showSearch = false
            searchQuery = ""
        }
    }
    LaunchedEffect(pdfFallbackNoticeCount) {
        if (pdfFallbackNoticeCount > 0) {
            val result = bookSnackbar.showSnackbar(
                message = pdfFallbackMessage,
                actionLabel = pdfRetryLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                enhancedPdfReaderUnavailable = false
            }
        }
    }

    fun selectSearchResult(requestedIndex: Int) {
        if (searchResultCount <= 0) return
        selectedSearchIndex = ((requestedIndex % searchResultCount) + searchResultCount) %
            searchResultCount
        textSearchMatches.getOrNull(selectedSearchIndex)?.let { match ->
            requestedPage = match.pageIndex
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showReaderControls,
        drawerContent = {
            ReaderChapterDrawer(
                chapters = chapters,
                currentPage = currentPage,
                totalPages = totalPages,
                scanning = pdfChapterScanRunning,
                isPdf = content is ReaderContent.PdfBook,
                pdfTextLayerAvailable = textLayerAvailable,
                foreground = foreground,
                background = background,
                onChapterSelected = { chapter ->
                    requestedPage = chapter.pageIndex
                    scope.launch { drawerState.close() }
                },
                onJump = {
                    scope.launch { drawerState.close() }
                    showJump = true
                },
            )
        },
    ) {
        ReaderOverlayScaffold(
            background = background,
            foreground = foreground,
            showReaderControls = showReaderControls,
            controlsOverlayContent = preferences.immersiveMode,
            snackbarHostState = bookSnackbar,
            topBar = {
                Column(Modifier.background(background)) {
                        TopAppBar(
                            title = {
                                Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        tr("返回书架", "Back to library"),
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.MenuBook,
                                        tr("打开目录", "Open contents"),
                                    )
                                }
                                if (textLayerAvailable) {
                                    IconButton(onClick = {
                                        showSearch = !showSearch
                                        if (!showSearch) searchQuery = ""
                                    }) {
                                        Icon(Icons.Outlined.Search, tr("搜索正文", "Search text"))
                                    }
                                }
                                IconButton(onClick = { showJump = true }) {
                                    Icon(
                                        Icons.Outlined.FormatListNumbered,
                                        tr("跳转页数或进度", "Jump to page or progress"),
                                    )
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        tr("阅读设置", "Reading settings"),
                                    )
                                }
                            },
                            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                                containerColor = background,
                                titleContentColor = foreground,
                                navigationIconContentColor = foreground,
                                actionIconContentColor = foreground,
                            ),
                        )
                        if (showSearch) {
                            ReaderSearchBar(
                                query = searchQuery,
                                resultCount = searchResultCount,
                                selectedIndex = selectedSearchIndex,
                                foreground = foreground,
                                onQueryChanged = { searchQuery = it.take(MAX_READER_SEARCH_QUERY_CHARS) },
                                onPrevious = { selectSearchResult(selectedSearchIndex - 1) },
                                onNext = { selectSearchResult(selectedSearchIndex + 1) },
                                onClose = {
                                    showSearch = false
                                    searchQuery = ""
                                },
                            )
                        }
                }
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(preferences.immersiveMode) {
                        if (!preferences.immersiveMode) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val halfHotspot = READER_CENTER_HOTSPOT_SIZE_DP.dp.toPx() / 2f
                            if (abs(down.position.x - size.width / 2f) > halfHotspot ||
                                abs(down.position.y - size.height / 2f) > halfHotspot
                            ) {
                                return@awaitEachGesture
                            }
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.changes.size > 1) moved = true
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    moved = true
                                    break
                                }
                                if ((change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    moved = true
                                }
                                if (!change.pressed) break
                            }
                            // Observe without consuming: TXT scrolling, PDF pan/zoom and text
                            // selection continue to receive the complete pointer stream.
                            if (!moved) controlsVisible = !controlsVisible
                        }
                    },
            ) {
                when (content) {
                    is ReaderContent.TextBook -> TextReader(
                        book = book,
                        pages = content.pages,
                        preferences = preferences,
                        background = background,
                        foreground = foreground,
                        searchQuery = searchQuery,
                        showPageIndicator = showReaderControls,
                        requestedPage = requestedPage,
                        onRequestedPageConsumed = { requestedPage = null },
                        onCurrentPageChanged = { currentPage = it },
                        onProgress = { pageIndex, paragraphIndex, pageOffsetPercent ->
                            viewModel.saveTextProgress(
                                bookId = book.id,
                                pageIndex = pageIndex,
                                paragraphIndex = paragraphIndex,
                                pageOffsetPercent = pageOffsetPercent,
                            )
                        },
                    )
                    is ReaderContent.PdfBook -> PdfReader(
                        book = book,
                        pageCount = content.pageCount,
                        preferences = preferences,
                        useEnhancedRenderer = pdfRendererMode == ReaderPdfRendererMode.ENHANCED,
                        background = background,
                        foreground = foreground,
                        showPageIndicator = showReaderControls,
                        requestedPage = requestedPage,
                        onRequestedPageConsumed = { requestedPage = null },
                        onCurrentPageChanged = { currentPage = it },
                        searchQuery = searchQuery,
                        selectedSearchIndex = selectedSearchIndex,
                        onSearchResultCountChanged = { pdfSearchResultCount = it },
                        onChaptersChanged = { pdfChapters = it },
                        onChapterScanRunningChanged = { pdfChapterScanRunning = it },
                        onEnhancedReaderUnavailable = {
                            if (!enhancedPdfReaderUnavailable) {
                                enhancedPdfReaderUnavailable = true
                                pdfFallbackNoticeCount += 1
                            }
                        },
                        onPdfZoomPercentChanged = { zoom ->
                            viewModel.updatePreferences(preferences.copy(pdfZoomPercent = zoom))
                        },
                        viewModel = viewModel,
                    )
                }
                if (preferences.immersiveMode) {
                    Box(
                        Modifier
                            .size(READER_CENTER_HOTSPOT_SIZE_DP.dp)
                            .align(Alignment.Center)
                            .semantics {
                                contentDescription = centerControlDescription
                                role = Role.Button
                                onClick(label = centerControlActionLabel) {
                                    controlsVisible = !controlsVisible
                                    true
                                }
                            },
                    )
                }
            }
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            initial = preferences,
            isPdf = content is ReaderContent.PdfBook,
            totalMillis = totalMillis,
            onDismiss = { showSettings = false },
            onSave = {
                viewModel.updatePreferences(it)
                showSettings = false
            },
        )
    }
    if (showJump) {
        ReaderJumpDialog(
            currentPage = currentPage,
            totalPages = totalPages,
            isText = textContent != null,
            onDismiss = { showJump = false },
            onJump = { page ->
                requestedPage = page
                showJump = false
            },
        )
    }
}

/**
 * Keeps the reader's content plane fixed while transient chrome is composed above it.
 * Scaffold reports top-bar padding to its body, but the reader intentionally ignores that value:
 * the toolbar/search row occludes the top of the page instead of moving its center.
 */
@Composable
internal fun ReaderOverlayScaffold(
    background: Color,
    foreground: Color,
    showReaderControls: Boolean,
    controlsOverlayContent: Boolean,
    snackbarHostState: SnackbarHostState,
    topBar: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = background,
        contentColor = foreground,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (showReaderControls) topBar()
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (controlsOverlayContent) Modifier else Modifier.padding(inner),
                )
                .testTag(READER_CONTENT_PLANE_TEST_TAG),
        ) {
            content()
        }
    }
}

internal const val READER_CONTENT_PLANE_TEST_TAG = "reader_content_plane"

@Composable
private fun ReaderCover(
    book: ReaderBook,
    loadCover: suspend (ReaderBook, Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    var measuredWidthPx by remember(book.id) { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = book.id,
        key2 = book.coverUri,
        key3 = "${book.fingerprint}:${book.totalPages}:$measuredWidthPx",
    ) {
        if (measuredWidthPx <= 0) return@produceState
        value = try {
            loadCover(book, measuredWidthPx)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }
    Surface(
        modifier = modifier.onSizeChanged { size ->
            if (size.width > 0 && size.width != measuredWidthPx) measuredWidthPx = size.width
        },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = tr("《${book.title}》封面", "Cover for ${book.title}"),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (book.type == ReaderBookType.TXT) {
            Box(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = book.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun ReaderShelfSettingsDialog(
    initial: ReaderPreferences,
    onDismiss: () -> Unit,
    onSave: (ReaderPreferences) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("书架设置", "Shelf settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(tr("显示方式", "Layout"), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.libraryLayout == ReaderLibraryLayout.LIST,
                        onClick = { draft = draft.copy(libraryLayout = ReaderLibraryLayout.LIST) },
                        leadingIcon = {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null)
                        },
                        label = { Text(tr("列表", "List")) },
                    )
                    FilterChip(
                        selected = draft.libraryLayout == ReaderLibraryLayout.GRID,
                        onClick = { draft = draft.copy(libraryLayout = ReaderLibraryLayout.GRID) },
                        leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                        label = { Text(tr("两列封面", "Two-column covers")) },
                    )
                }
                if (draft.libraryLayout == ReaderLibraryLayout.GRID) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("显示封面下方书名", "Show titles below covers"))
                            Text(
                                tr(
                                    "关闭后隐藏两列封面下方的书名；无图片的 TXT 默认封面仍会显示书名。",
                                    "Hide titles below the two-column covers. TXT books without an image still show their title on the default cover.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.showGridBookTitles,
                            onCheckedChange = { draft = draft.copy(showGridBookTitles = it) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("显示阅读进度百分比", "Show reading progress"))
                        Text(
                            tr(
                                "进度按稳定逻辑页或 PDF 实际页数计算。",
                                "Progress uses stable logical TXT pages or actual PDF pages.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draft.showProgressPercentage,
                        onCheckedChange = { draft = draft.copy(showProgressPercentage = it) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

private fun readerBookProgressPercent(book: ReaderBook): Int {
    if (book.totalPages <= 1) return 0
    val page = if (book.type == ReaderBookType.PDF) book.pdfPageIndex else book.textPageIndex
    val precisePage = page.coerceIn(0, book.totalPages - 1) +
        com.deskcubby.app.data.repository.normalizePageOffsetPercent(book.pageOffsetPercent) / 100f
    return (precisePage.coerceIn(0f, (book.totalPages - 1).toFloat()) /
        (book.totalPages - 1).toFloat() * 100f).roundToInt()
}

@Composable
private fun TextReader(
    book: ReaderBook,
    pages: List<ReaderTextPage>,
    preferences: ReaderPreferences,
    background: Color,
    foreground: Color,
    searchQuery: String,
    showPageIndicator: Boolean,
    requestedPage: Int?,
    onRequestedPageConsumed: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onProgress: (Int, Int, Int) -> Unit,
) {
    val initial = if (book.textPageIndex >= 0) {
        book.textPageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
    } else {
        com.deskcubby.app.data.repository.textPageForParagraph(
            pages,
            book.textParagraphIndex,
        )
    }
    val initialPosition = remember(book.id) {
        ReaderPagePosition(
            pageIndex = initial,
            pageOffsetPercent = book.pageOffsetPercent,
        )
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPosition.pageIndex)
    var initialPositionRestored by rememberSaveable(book.id) { mutableStateOf(false) }
    val visiblePosition by remember(listState, pages.size) {
        derivedStateOf { listState.currentReaderPagePosition(pages.size) }
    }
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnPageChanged by rememberUpdatedState(onCurrentPageChanged)
    val currentPages by rememberUpdatedState(pages)
    val currentInitialPositionRestored by rememberUpdatedState(initialPositionRestored)
    LaunchedEffect(book.id, listState, pages.size) {
        if (!initialPositionRestored) {
            listState.restoreReaderPagePosition(initialPosition, pages.size)
            initialPositionRestored = true
        }
    }
    LaunchedEffect(requestedPage) {
        requestedPage?.let { page ->
            listState.scrollToItem(page.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
            onRequestedPageConsumed()
        }
    }
    LaunchedEffect(listState, initialPositionRestored, pages) {
        if (!initialPositionRestored) return@LaunchedEffect
        snapshotFlow { listState.currentReaderPagePosition(pages.size) }
            .distinctUntilChanged()
            .collectLatest { position ->
                currentOnPageChanged(position.pageIndex)
                delay(600L)
                currentOnProgress(
                    position.pageIndex,
                    pages[position.pageIndex].firstParagraphIndex,
                    position.pageOffsetPercent,
                )
            }
    }
    DisposableEffect(listState) {
        onDispose {
            if (currentPages.isEmpty()) return@onDispose
            val position = if (currentInitialPositionRestored) {
                listState.currentReaderPagePosition(currentPages.size)
            } else {
                initialPosition
            }
            currentOnProgress(
                position.pageIndex,
                currentPages[position.pageIndex].firstParagraphIndex,
                position.pageOffsetPercent,
            )
        }
    }
    Box(Modifier.fillMaxSize().background(background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 58.dp),
        ) {
            items(pages.size, key = { it }) { index ->
                SelectionContainer {
                    Column {
                        val pageParagraphs = remember(pages[index].text) {
                            pages[index].text.split("\n\n")
                        }
                        pageParagraphs.forEach { paragraph ->
                            Text(
                                text = readerSearchAnnotatedString(
                                    text = paragraph,
                                    query = searchQuery,
                                    highlightColor = MaterialTheme.colorScheme.primary.copy(
                                        alpha = 0.32f,
                                    ),
                                ),
                                color = foreground,
                                fontSize = preferences.fontSizeSp.sp,
                                lineHeight = (preferences.fontSizeSp *
                                    preferences.lineHeightMultiplier).sp,
                                fontFamily = FontFamily.Serif,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = preferences.paragraphSpacingDp.dp),
                            )
                        }
                        Text(
                            tr("— 第 ${index + 1} 页 —", "— Page ${index + 1} —"),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            color = foreground.copy(alpha = 0.55f),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
        if (showPageIndicator) {
            ReaderPageIndicator(
                currentPage = visiblePosition.pageIndex,
                totalPages = pages.size,
                background = background,
                foreground = foreground,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun ReaderSearchBar(
    query: String,
    resultCount: Int,
    selectedIndex: Int,
    foreground: Color,
    onQueryChanged: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChanged("") }) {
                        Icon(Icons.Outlined.Close, tr("清除搜索", "Clear search"))
                    }
                }
            },
            placeholder = { Text(tr("搜索整本小说", "Search this book")) },
        )
        Text(
            if (query.isBlank()) {
                "—"
            } else if (resultCount == 0) {
                tr("0 项", "0 results")
            } else {
                "${selectedIndex.coerceIn(0, resultCount - 1) + 1}/$resultCount"
            },
            color = foreground.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelMedium,
        )
        IconButton(onClick = onPrevious, enabled = resultCount > 0) {
            Icon(Icons.Outlined.KeyboardArrowUp, tr("上一个结果", "Previous result"))
        }
        IconButton(onClick = onNext, enabled = resultCount > 0) {
            Icon(Icons.Outlined.KeyboardArrowDown, tr("下一个结果", "Next result"))
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, tr("关闭搜索", "Close search"))
        }
    }
}

private fun readerSearchAnnotatedString(
    text: String,
    query: String,
    highlightColor: Color,
): AnnotatedString {
    val normalizedQuery = query.trim().take(MAX_READER_SEARCH_QUERY_CHARS)
    if (normalizedQuery.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var fromIndex = 0
        while (fromIndex < text.length) {
            val match = text.indexOf(normalizedQuery, fromIndex, ignoreCase = true)
            if (match < 0) break
            addStyle(
                SpanStyle(background = highlightColor),
                start = match,
                end = match + normalizedQuery.length,
            )
            fromIndex = match + normalizedQuery.length.coerceAtLeast(1)
        }
    }
}

@Composable
private fun ReaderChapterDrawer(
    chapters: List<ReaderChapter>,
    currentPage: Int,
    totalPages: Int,
    scanning: Boolean,
    isPdf: Boolean,
    pdfTextLayerAvailable: Boolean,
    foreground: Color,
    background: Color,
    onChapterSelected: (ReaderChapter) -> Unit,
    onJump: () -> Unit,
) {
    val currentChapter = chapters.lastOrNull { it.pageIndex <= currentPage }
    ModalDrawerSheet(
        drawerContainerColor = background,
        drawerContentColor = foreground,
    ) {
        Text(
            tr("目录", "Contents"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        )
        Text(
            tr(
                "当前位置 ${currentPage + 1} / $totalPages 页",
                "Current page ${currentPage + 1} / $totalPages",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = foreground.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        TextButton(onClick = onJump, modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Outlined.FormatListNumbered, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(tr("按页数或进度跳转", "Jump by page or progress"))
        }
        if (scanning) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    tr("正在扫描整本 PDF 的目录…", "Scanning the full PDF for chapters…"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (chapters.isEmpty()) {
            Text(
                if (isPdf && !pdfTextLayerAvailable) {
                    tr(
                        "当前 PDF 兼容视图或系统 PDF 扩展不提供文本目录；仍可按页数跳转。",
                        "The current compatibility view or system PDF extension has no text contents; page jumping remains available.",
                    )
                } else if (isPdf) {
                    tr(
                        "暂未从 PDF 文本层识别到章节；扫描版 PDF 没有可搜索文字时仍可按页数跳转。",
                        "No chapters have been found in the PDF text layer yet. Image-only PDFs can still be navigated by page.",
                    )
                } else {
                    tr(
                        "当前智能/自定义规则没有识别到章节标题；仍可使用逻辑页跳转，并可在阅读设置中调整规则。",
                        "The current smart/custom rules found no chapter headings. Logical-page jumping remains available, and the rules can be changed in Reading settings.",
                    )
                },
                modifier = Modifier.padding(20.dp),
                color = foreground.copy(alpha = 0.72f),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(chapters, key = { "${it.pageIndex}:${it.paragraphIndex}:${it.title}" }) { chapter ->
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    tr("第 ${chapter.pageIndex + 1} 页", "Page ${chapter.pageIndex + 1}"),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        selected = currentChapter == chapter,
                        onClick = { onChapterSelected(chapter) },
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }

}

@Composable
private fun PdfReader(
    book: ReaderBook,
    pageCount: Int,
    preferences: ReaderPreferences,
    useEnhancedRenderer: Boolean,
    background: Color,
    foreground: Color,
    showPageIndicator: Boolean,
    requestedPage: Int?,
    onRequestedPageConsumed: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    searchQuery: String,
    selectedSearchIndex: Int,
    onSearchResultCountChanged: (Int) -> Unit,
    onChaptersChanged: (List<ReaderChapter>) -> Unit,
    onChapterScanRunningChanged: (Boolean) -> Unit,
    onEnhancedReaderUnavailable: () -> Unit,
    onPdfZoomPercentChanged: (Int) -> Unit,
    viewModel: ReaderViewModel,
) {
    var currentPdfPage by rememberSaveable(book.id) {
        mutableIntStateOf(book.pdfPageIndex.coerceIn(0, pageCount - 1))
    }
    var currentPdfPageOffsetPercent by rememberSaveable(book.id) {
        mutableIntStateOf(book.pageOffsetPercent)
    }
    val currentPosition = ReaderPagePosition(
        pageIndex = currentPdfPage.coerceIn(0, pageCount - 1),
        pageOffsetPercent = com.deskcubby.app.data.repository.normalizePageOffsetPercent(
            currentPdfPageOffsetPercent,
        ),
    )
    val reportCurrentPosition: (ReaderPagePosition) -> Unit = { position ->
        val safePosition = ReaderPagePosition(
            pageIndex = position.pageIndex.coerceIn(0, pageCount - 1),
            pageOffsetPercent =
                com.deskcubby.app.data.repository.normalizePageOffsetPercent(
                    position.pageOffsetPercent,
                ),
        )
        currentPdfPage = safePosition.pageIndex
        currentPdfPageOffsetPercent = safePosition.pageOffsetPercent
        onCurrentPageChanged(safePosition.pageIndex)
    }
    LaunchedEffect(book.id, currentPosition) {
        delay(600L)
        viewModel.savePdfProgress(
            bookId = book.id,
            pageIndex = currentPosition.pageIndex,
            pageOffsetPercent = currentPosition.pageOffsetPercent,
        )
    }
    val currentPositionOnDispose by rememberUpdatedState(currentPosition)
    DisposableEffect(book.id) {
        onDispose {
            viewModel.savePdfProgress(
                bookId = book.id,
                pageIndex = currentPositionOnDispose.pageIndex,
                pageOffsetPercent = currentPositionOnDispose.pageOffsetPercent,
            )
        }
    }
    Box(Modifier.fillMaxSize().background(background)) {
        if (useEnhancedRenderer) {
            PdfiumPdfReader(
                uri = android.net.Uri.parse(book.uri),
                initialPosition = currentPosition,
                preferences = preferences,
                background = background,
                foreground = foreground,
                modifier = Modifier.fillMaxSize(),
                requestedPage = requestedPage,
                searchQuery = searchQuery,
                selectedSearchIndex = selectedSearchIndex,
                onRequestedPageConsumed = onRequestedPageConsumed,
                onCurrentPositionChanged = reportCurrentPosition,
                onSearchResultCountChanged = onSearchResultCountChanged,
                onChaptersChanged = onChaptersChanged,
                onChapterScanRunningChanged = onChapterScanRunningChanged,
                onEnhancedReaderUnavailable = onEnhancedReaderUnavailable,
                onZoomPercentChanged = onPdfZoomPercentChanged,
            )
        } else {
            LaunchedEffect(Unit) {
                onSearchResultCountChanged(0)
                onChaptersChanged(emptyList())
                onChapterScanRunningChanged(false)
            }
            LegacyContinuousPdfReader(
                book = book,
                initialPosition = currentPosition,
                pageCount = pageCount,
                zoomPercent = preferences.pdfZoomPercent,
                background = background,
                foreground = foreground,
                requestedPage = requestedPage,
                onRequestedPageConsumed = onRequestedPageConsumed,
                onCurrentPositionChanged = reportCurrentPosition,
                viewModel = viewModel,
            )
        }
        if (showPageIndicator) {
            ReaderPageIndicator(
                currentPage = currentPosition.pageIndex,
                totalPages = pageCount,
                background = background,
                foreground = foreground,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun LegacyContinuousPdfReader(
    book: ReaderBook,
    initialPosition: ReaderPagePosition,
    pageCount: Int,
    zoomPercent: Int,
    background: Color,
    foreground: Color,
    requestedPage: Int?,
    onRequestedPageConsumed: () -> Unit,
    onCurrentPositionChanged: (ReaderPagePosition) -> Unit,
    viewModel: ReaderViewModel,
) {
    val restoredPosition = remember(book.id) {
        ReaderPagePosition(
            pageIndex = initialPosition.pageIndex.coerceIn(0, pageCount - 1),
            pageOffsetPercent = initialPosition.pageOffsetPercent,
        )
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restoredPosition.pageIndex,
    )
    var initialPositionRestored by rememberSaveable(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id, listState, pageCount) {
        if (!initialPositionRestored) {
            listState.restoreReaderPagePosition(restoredPosition, pageCount)
            initialPositionRestored = true
        }
    }
    LaunchedEffect(requestedPage) {
        requestedPage?.let { page ->
            listState.scrollToItem(page.coerceIn(0, pageCount - 1))
            onRequestedPageConsumed()
        }
    }
    LaunchedEffect(listState, initialPositionRestored, pageCount) {
        if (!initialPositionRestored) return@LaunchedEffect
        snapshotFlow { listState.currentReaderPagePosition(pageCount) }
            .distinctUntilChanged()
            .collect(onCurrentPositionChanged)
    }
    // Same free 2D pan as the enhanced view: the horizontal component of every drag is forwarded
    // here while the vertical component keeps going through the LazyColumn scrollable.
    var horizontalOffsetPx by remember(book.id) { mutableFloatStateOf(0f) }
    var maxHorizontalPx by remember(book.id) { mutableIntStateOf(0) }
    val twoDimensionalPan = remember(book.id) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): Offset {
                if (available.x == 0f || maxHorizontalPx <= 0) return Offset.Zero
                val previous = horizontalOffsetPx
                horizontalOffsetPx = (previous - available.x)
                    .coerceIn(0f, maxHorizontalPx.toFloat())
                return Offset(previous - horizontalOffsetPx, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (available.x == 0f || maxHorizontalPx <= 0) return Velocity.Zero
                val start = horizontalOffsetPx
                val end = (start - available.x * 0.12f)
                    .coerceIn(0f, maxHorizontalPx.toFloat())
                if (kotlin.math.abs(end - start) < 1f) return Velocity.Zero
                val animatable = androidx.compose.animation.core.Animatable(start)
                animatable.animateTo(end, animationSpec = androidx.compose.animation.core.tween(220)) {
                    horizontalOffsetPx = value
                }
                return Velocity(available.x, 0f)
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pageViewportWidth = (maxWidth - 24.dp).coerceAtLeast(1.dp)
        val pageWidth = (pageViewportWidth * zoomPercent / 100f).coerceAtLeast(160.dp)
        val targetWidthPx = with(density) { pageWidth.roundToPx() }
        val pageViewportWidthPx = with(density) { pageViewportWidth.roundToPx() }
        val calculatedMaxHorizontalPx = readerPdfMaxHorizontalOffset(
            viewportWidthPx = pageViewportWidthPx,
            contentWidthPx = targetWidthPx,
        )
        LaunchedEffect(book.id, calculatedMaxHorizontalPx) {
            maxHorizontalPx = calculatedMaxHorizontalPx
            horizontalOffsetPx = horizontalOffsetPx.coerceIn(
                0f,
                calculatedMaxHorizontalPx.toFloat(),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(twoDimensionalPan),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pageCount, key = { it }) { page ->
                LegacyPdfPage(
                    book = book,
                    page = page,
                    displayWidth = pageWidth,
                    targetWidthPx = targetWidthPx,
                    horizontalOffsetPx = horizontalOffsetPx,
                    background = background,
                    foreground = foreground,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun ReaderPageIndicator(
    currentPage: Int,
    totalPages: Int,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(50),
        color = background.copy(alpha = 0.9f),
        contentColor = foreground,
    ) {
        Text(
            "${currentPage.coerceAtLeast(0) + 1} / ${totalPages.coerceAtLeast(1)}",
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ReaderJumpDialog(
    currentPage: Int,
    totalPages: Int,
    isText: Boolean,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    val safeTotal = totalPages.coerceAtLeast(1)
    var selectedPage by rememberSaveable(currentPage, safeTotal) {
        mutableIntStateOf(currentPage.coerceIn(0, safeTotal - 1))
    }
    var pageText by rememberSaveable(currentPage, safeTotal) {
        mutableStateOf((selectedPage + 1).toString())
    }
    val parsedPage = pageText.toIntOrNull()?.minus(1)?.takeIf { it in 0 until safeTotal }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("跳转页数 / 进度", "Jump to page / progress")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { changed ->
                        pageText = changed.filter(Char::isDigit).take(8)
                        pageText.toIntOrNull()?.minus(1)?.takeIf { it in 0 until safeTotal }
                            ?.let { selectedPage = it }
                    },
                    label = { Text(tr("页码（1–$safeTotal）", "Page (1–$safeTotal)")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = pageText.isNotEmpty() && parsedPage == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(tr("阅读进度", "Progress"))
                    Text(
                        "${(((selectedPage + 1).toFloat() / safeTotal) * 100).roundToInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = selectedPage.toFloat(),
                    onValueChange = {
                        selectedPage = it.roundToInt().coerceIn(0, safeTotal - 1)
                        pageText = (selectedPage + 1).toString()
                    },
                    valueRange = 0f..(safeTotal - 1).coerceAtLeast(1).toFloat(),
                    steps = (safeTotal - 2).coerceIn(0, 200),
                    enabled = safeTotal > 1,
                )
                if (isText) {
                    Text(
                        tr(
                            "TXT 每约 1,800 个字符划为一个逻辑页，页数不受设备尺寸或字体变化影响。",
                            "TXT uses one stable logical page per roughly 1,800 characters, independent of screen size or font changes.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedPage != null,
                onClick = { parsedPage?.let(onJump) },
            ) { Text(tr("跳转", "Jump")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
}

@Composable
private fun LegacyPdfPage(
    book: ReaderBook,
    page: Int,
    displayWidth: androidx.compose.ui.unit.Dp,
    targetWidthPx: Int,
    horizontalOffsetPx: Float,
    background: Color,
    foreground: Color,
    viewModel: ReaderViewModel,
) {
    val rendered by produceState<Result<Bitmap>?>(null, book.id, page, targetWidthPx) {
        value = runCatching { viewModel.renderPdfPage(book, page, targetWidthPx) }
    }
    val bitmap = rendered?.getOrNull()
    when {
        rendered == null -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxWidth().height(360.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
        bitmap != null -> ReaderPdfPageViewport(
            displayWidth = displayWidth,
            imageWidthPx = bitmap.width,
            imageHeightPx = bitmap.height,
            horizontalOffsetPx = horizontalOffsetPx,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = tr("PDF 第 ${page + 1} 页", "PDF page ${page + 1}"),
                contentScale = ContentScale.FillBounds,
                colorFilter = readerPdfColorFilter(background, foreground),
            )
        }
        else -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tr("这一页无法显示", "This page could not be rendered"),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    initial: ReaderPreferences,
    isPdf: Boolean,
    totalMillis: Long,
    onDismiss: () -> Unit,
    onSave: (ReaderPreferences) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var showCustomColorPicker by remember { mutableStateOf(false) }
    var showForegroundColorPicker by remember { mutableStateOf(false) }
    val customRegexValid = isValidReaderChapterRegex(draft.customChapterRegex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("阅读设置", "Reading settings")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(tr("累计阅读：", "Total reading: ") + formatDuration(totalMillis))
                }
                if (isPdf) {
                    item {
                        SettingSlider(
                            label = tr("PDF 缩放比例", "PDF zoom"),
                            valueText = "${draft.pdfZoomPercent}%",
                            value = draft.pdfZoomPercent.toFloat(),
                            range = MIN_READER_PDF_ZOOM_PERCENT.toFloat()..
                                MAX_READER_PDF_ZOOM_PERCENT.toFloat(),
                            steps = 249,
                        ) {
                            draft = draft.copy(
                                pdfZoomPercent = it.roundToInt().coerceIn(
                                    MIN_READER_PDF_ZOOM_PERCENT,
                                    MAX_READER_PDF_ZOOM_PERCENT,
                                ),
                            )
                        }
                        Text(
                            tr(
                                "双指缩放可临时调整；此比例用于重新打开和设置保存后的基准缩放。",
                                "Pinch to zoom temporarily; this percentage is the saved baseline used after reopening.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Text(tr("背景颜色", "Background"), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderBackground.entries.forEach { background ->
                            val colors = readerColors(
                                background,
                                draft.customBackgroundArgb,
                                draft.customForegroundArgb,
                            )
                            Surface(
                                modifier = Modifier.size(42.dp).clickable {
                                    if (background == ReaderBackground.CUSTOM) {
                                        showCustomColorPicker = true
                                    } else {
                                        draft = draft.copy(background = background)
                                    }
                                },
                                shape = CircleShape,
                                color = colors.first,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (draft.background == background) 3.dp else 1.dp,
                                    if (draft.background == background) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                ),
                            ) {
                                if (background == ReaderBackground.CUSTOM) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Palette,
                                            contentDescription = tr("自定义背景颜色", "Custom background color"),
                                            tint = colors.second,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    val foregroundPreview = Color(
                        draft.customForegroundArgb ?: readerColors(
                            draft.background,
                            draft.customBackgroundArgb,
                            null,
                        ).second.toArgb(),
                    )
                    Text(tr("字体 / PDF 前景色", "Text / PDF foreground"), fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterChip(
                            selected = draft.customForegroundArgb == null,
                            onClick = { draft = draft.copy(customForegroundArgb = null) },
                            label = { Text(tr("自动", "Automatic")) },
                        )
                        Surface(
                            modifier = Modifier.size(42.dp).clickable {
                                showForegroundColorPicker = true
                            },
                            shape = CircleShape,
                            color = foregroundPreview,
                            border = androidx.compose.foundation.BorderStroke(
                                if (draft.customForegroundArgb != null) 3.dp else 1.dp,
                                if (draft.customForegroundArgb != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Palette,
                                    contentDescription = tr("自定义字体颜色", "Custom text color"),
                                    tint = if (foregroundPreview.luminance() > 0.5f) {
                                        Color.Black
                                    } else {
                                        Color.White
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    if (isPdf) {
                        Text(
                            tr(
                                "PDF 会把页面映射为当前背景与前景双色；选择夜间背景和浅色前景即可获得黑底白字效果，原文件不会修改。",
                                "PDF pages are mapped to the selected background and foreground. Use a dark background with a light foreground for dark mode; the original file is unchanged.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("全屏纯净模式", "Immersive reading"), fontWeight = FontWeight.SemiBold)
                            Text(
                                tr(
                                    "隐藏书名、工具栏、页码和系统栏；点屏幕中央可显示或再次隐藏。",
                                    "Hide the title, toolbar, page indicator, and system bars. Tap the center to show or hide them.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.immersiveMode,
                            onCheckedChange = { draft = draft.copy(immersiveMode = it) },
                        )
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
                    Text(tr("智能章节", "Smart chapters"), fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderChapterDetectionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = draft.chapterDetectionMode == mode,
                                onClick = { draft = draft.copy(chapterDetectionMode = mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ReaderChapterDetectionMode.SMART ->
                                                tr("仅智能", "Smart only")
                                            ReaderChapterDetectionMode.CUSTOM ->
                                                tr("仅自定义", "Custom only")
                                            ReaderChapterDetectionMode.SMART_AND_CUSTOM ->
                                                tr("智能 + 自定义", "Smart + custom")
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Text(
                        tr(
                            "智能规则支持中文章节/卷/回/幕、英文 Chapter/Part/Book/Section/Episode、Markdown 标题、序章/尾声及多种编号格式。",
                            "Smart rules cover Chinese chapters/volumes, Chapter/Part/Book/Section/Episode, Markdown headings, prologues/epilogues, and several numbering styles.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (draft.chapterDetectionMode != ReaderChapterDetectionMode.SMART) {
                    item {
                        OutlinedTextField(
                            value = draft.customChapterRegex,
                            onValueChange = {
                                draft = draft.copy(
                                    customChapterRegex = it.take(MAX_READER_CUSTOM_REGEX_CHARS),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(tr("自定义整行正则", "Custom full-line regex")) },
                            placeholder = { Text("^(第.+章|Chapter\\s+.+)$") },
                            isError = !customRegexValid,
                            supportingText = {
                                Text(
                                    if (customRegexValid) {
                                        tr(
                                            "规则匹配整行；留空等于不追加自定义规则。",
                                            "The rule matches a whole line; leave blank to add no custom rule.",
                                        )
                                    } else {
                                        tr("正则格式无效", "Invalid regular expression")
                                    },
                                )
                            },
                            minLines = 2,
                            maxLines = 5,
                        )
                    }
                }
                item {
                    SettingSlider(
                        label = tr("章节标题最长字符数", "Maximum heading length"),
                        valueText = draft.chapterHeadingMaxChars.toString(),
                        value = draft.chapterHeadingMaxChars.toFloat(),
                        range = MIN_READER_CHAPTER_HEADING_CHARS.toFloat()..
                            MAX_READER_CHAPTER_TITLE_CHARS.toFloat(),
                        steps = MAX_READER_CHAPTER_TITLE_CHARS -
                            MIN_READER_CHAPTER_HEADING_CHARS - 1,
                    ) {
                        draft = draft.copy(chapterHeadingMaxChars = it.roundToInt())
                    }
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
        confirmButton = {
            TextButton(
                enabled = customRegexValid,
                onClick = { onSave(draft) },
            ) { Text(tr("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消", "Cancel")) } },
    )
    if (showCustomColorPicker) {
        ColorPickerDialog(
            initialColorArgb = draft.customBackgroundArgb,
            title = tr("自定义阅读背景", "Custom reading background"),
            onDismiss = { showCustomColorPicker = false },
            onConfirm = { color ->
                draft = draft.copy(
                    background = ReaderBackground.CUSTOM,
                    customBackgroundArgb = color,
                )
                showCustomColorPicker = false
            },
        )
    }
    if (showForegroundColorPicker) {
        ColorPickerDialog(
            initialColorArgb = draft.customForegroundArgb ?: readerColors(
                draft.background,
                draft.customBackgroundArgb,
                null,
            ).second.toArgb(),
            title = tr("自定义字体颜色", "Custom text color"),
            onDismiss = { showForegroundColorPicker = false },
            onConfirm = { color ->
                draft = draft.copy(customForegroundArgb = color)
                showForegroundColorPicker = false
            },
        )
    }
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
private fun ReaderTimingEffect(bookId: String, title: String, viewModel: ReaderViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bookId, title) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.beginReading(bookId, title)
                Lifecycle.Event.ON_PAUSE -> viewModel.endReading(bookId)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.beginReading(bookId, title)
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
            val exitOrientation = activity?.let {
                readerExitOrientation(
                    isFinishing = activity.isFinishing,
                    isChangingConfigurations = activity.isChangingConfigurations,
                )
            }
            if (exitOrientation != null) {
                // MainActivity's product baseline is system-controlled. Do not restore the new
                // Activity's inherited SENSOR_* value after a reader-triggered configuration
                // change, or the whole app would remain locked after leaving the reader.
                activity.requestedOrientation = exitOrientation
            }
        }
    }
}

@Composable
private fun ReaderSystemBarsEffect(activity: Activity?, hideSystemBars: Boolean) {
    DisposableEffect(activity, hideSystemBars) {
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
        }
        if (hideSystemBars) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (hideSystemBars) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

internal fun readerExitOrientation(
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
): Int? = if (!isFinishing && !isChangingConfigurations) {
    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
} else {
    null
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun readerColors(
    background: ReaderBackground,
    customBackgroundArgb: Int,
    customForegroundArgb: Int?,
): Pair<Color, Color> = when (background) {
    ReaderBackground.WHITE -> Color(0xFFFFFFFF) to Color(0xFF202124)
    ReaderBackground.PAPER -> Color(0xFFF4F0E6) to Color(0xFF332E28)
    ReaderBackground.SEPIA -> Color(0xFFE8D6B0) to Color(0xFF3B2C1E)
    ReaderBackground.GREEN -> Color(0xFFDDE8D7) to Color(0xFF203126)
    ReaderBackground.NIGHT -> Color(0xFF171A1C) to Color(0xFFE2E0DA)
    ReaderBackground.CUSTOM -> Color(customBackgroundArgb).let { custom ->
        custom to if (custom.luminance() > 0.5f) Color(0xFF181818) else Color(0xFFF4F4F4)
    }
}.let { (resolvedBackground, automaticForeground) ->
    resolvedBackground to customForegroundArgb
        ?.let { Color(it or 0xFF000000.toInt()) }
        .let { it ?: automaticForeground }
}

/**
 * Maps rendered PDF luminance to the configured foreground/background pair. This changes only the
 * displayed bitmap; the source document remains untouched. Black becomes [foreground], white
 * becomes [background], and intermediate shades remain readable between those endpoints.
 */
private fun readerPdfColorFilter(background: Color, foreground: Color): ColorFilter? {
    val backgroundArgb = background.toArgb()
    val foregroundArgb = foreground.toArgb()
    if (!readerPdfColorTransformRequired(backgroundArgb, foregroundArgb)) return null
    return ColorFilter.colorMatrix(
        ColorMatrix(readerPdfColorMatrixValues(backgroundArgb, foregroundArgb)),
    )
}

private const val READER_CENTER_HOTSPOT_SIZE_DP = 132

@Composable
private fun readerTutorialTarget(content: ReaderContentState): PageTutorialTarget? = when (content) {
    ReaderContentState.Idle -> PageTutorialTarget(
        pageId = "reader/library",
        title = tr("阅读书架", "Reading library"),
        description = tr(
            "右下角可导入 TXT 或 PDF；书籍正文仍保存在你选择的原文件中。",
            "Import TXT or PDF from the lower-right button; the original selected file remains the source of the book.",
        ),
        hints = listOf(
            tr("点书籍继续上次进度，长按或使用移除按钮可从书架移除。", "Open a book to resume; use remove to take it off this library."),
            tr("阅读时长与进度会在本机自动保存。", "Reading time and progress are saved locally."),
        ),
    )
    is ReaderContentState.Ready -> when (content.content) {
        is ReaderContent.TextBook -> PageTutorialTarget(
            pageId = "reader/txt",
            title = tr("TXT 阅读", "TXT reader"),
            description = tr(
                "TXT 会自动识别“第…章”、Chapter 等标题，并按约 1,800 字符生成稳定的逻辑页。",
                "TXT detects headings such as 第…章 and Chapter, then creates stable logical pages of roughly 1,800 characters.",
            ),
            hints = listOf(
                tr("顶部目录按钮可打开章节侧栏并直接跳转。", "Use the contents button to open the chapter drawer and jump directly."),
                tr("放大镜会搜索整本小说；长按正文可选择并复制。", "Search scans the whole novel; long-press text to select and copy it."),
                tr("页码按钮可输入页数，或拖动进度滑块跳转。", "Use the page button to enter a page or drag the progress slider."),
                tr("阅读设置可调字号、间距、方向和自定义背景颜色。", "Reading settings include type size, spacing, orientation, and a custom background color."),
            ),
        )
        is ReaderContent.PdfBook -> PageTutorialTarget(
            pageId = "reader/pdf",
            title = tr("PDF 阅读", "PDF reader"),
            description = tr(
                "PDFium 增强视图以连续页面阅读；加载超时或不可用时会自动切换到系统兼容视图。",
                "The PDFium enhanced view forms one continuous reader; if it times out or is unavailable, the app switches to the system compatibility view.",
            ),
            hints = listOf(
                tr("增强视图支持双指即时缩放；齿轮中可保存 50%–300% 的基准比例。", "The enhanced view supports pinch zoom; save a 50%–300% baseline from the gear."),
                tr("增强视图可搜索内嵌文字并自动派生目录；纯图片扫描件没有可搜索文字，DeskCubby 不进行 OCR。", "The enhanced view can search embedded text and derive contents automatically. Image-only scans have no searchable text; DeskCubby does not perform OCR."),
                tr("兼容视图不提供搜索或自动目录，但仍可连续阅读并按真实页码保存进度。", "The compatibility view has no search or automatic contents, but still reads continuously and saves the physical page."),
                tr("离开书籍时会保存当前位置。", "Your current position is saved when you leave the book."),
            ),
        )
    }
    ReaderContentState.Loading -> null
    is ReaderContentState.Failed -> PageTutorialTarget(
        pageId = "reader/error",
        title = tr("书籍无法打开", "Book could not be opened"),
        description = tr("可先重试；如果原文件被移动、删除或权限失效，请回到书架重新导入。", "Retry first. If the source was moved, deleted, or its permission expired, return to the library and import it again."),
        hints = emptyList(),
    )
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
