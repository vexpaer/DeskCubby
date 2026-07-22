@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.deskcubby.app.ui.rss

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.repository.RssArticle
import com.deskcubby.app.ui.components.AppEmptyState
import com.deskcubby.app.ui.components.AppLoadingIndicator
import com.deskcubby.app.ui.theme.GlassPanel
import com.deskcubby.app.ui.theme.LocalAppLanguage
import com.deskcubby.app.ui.theme.PanelRole
import com.deskcubby.app.ui.theme.tr
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun RssScreen(
    padding: PaddingValues,
    viewModel: RssViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNewSubscription by remember { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<RssSubscription?>(null) }
    var deletingSubscription by remember { mutableStateOf<RssSubscription?>(null) }
    val openFailedMessage = tr("无法打开这篇文章", "Unable to open this article")

    LaunchedEffect(state.error) {
        state.error?.let { error ->
            snackbarHostState.showSnackbar(error)
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
                title = { Text("RSS") },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.refreshing && !state.loadingSettings,
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, tr("刷新订阅", "Refresh feeds"))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewSubscription = true }) {
                Icon(Icons.Outlined.Add, tr("添加 RSS 订阅", "Add RSS feed"))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.loadingSettings -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    AppLoadingIndicator()
                }
            }
            state.subscriptions.isEmpty() -> {
                AppEmptyState(
                    icon = Icons.Outlined.RssFeed,
                    title = tr("还没有 RSS 订阅", "No RSS feeds yet"),
                    description = tr(
                        "添加一个 HTTPS 订阅地址，在一个页面阅读更新。",
                        "Add an HTTPS feed URL and read its updates in one place.",
                    ),
                    actionLabel = tr("添加订阅", "Add feed"),
                    onAction = { showNewSubscription = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "subscription_heading") {
                        SectionHeading(
                            title = tr("订阅源", "Feeds"),
                            detail = tr(
                                "每个订阅最多 ${state.maxItemsPerFeed} 篇",
                                "Up to ${state.maxItemsPerFeed} items per feed",
                            ),
                        )
                    }
                    item(key = "subscriptions") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(state.subscriptions, key = RssSubscription::id) { subscription ->
                                SubscriptionCard(
                                    subscription = subscription,
                                    enabled = !state.savingSubscription,
                                    onEnabledChange = { enabled ->
                                        viewModel.setSubscriptionEnabled(subscription.id, enabled)
                                    },
                                    onEdit = { editingSubscription = subscription },
                                    onDelete = { deletingSubscription = subscription },
                                )
                            }
                        }
                    }
                    item(key = "article_heading") {
                        SectionHeading(
                            title = tr("最新文章", "Latest articles"),
                            detail = tr(
                                "${state.articles.size} 篇",
                                "${state.articles.size} items",
                            ),
                        )
                    }
                    if (state.articles.isEmpty()) {
                        item(key = "empty_articles") {
                            AppEmptyState(
                                icon = Icons.Outlined.RssFeed,
                                title = tr("暂无文章", "No articles yet"),
                                description = tr(
                                    "点击右上角刷新；也可以检查订阅是否已启用。",
                                    "Refresh from the top right, or check that a feed is enabled.",
                                ),
                                actionLabel = tr("刷新", "Refresh"),
                                onAction = viewModel::refresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 300.dp),
                            )
                        }
                    } else {
                        items(state.articles, key = RssArticle::id) { article ->
                            ArticleCard(
                                article = article,
                                showSummary = state.showSummaries,
                                onOpen = {
                                    val opened = openArticle(context, article.url)
                                    if (!opened) {
                                        scope.launch { snackbarHostState.showSnackbar(openFailedMessage) }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showNewSubscription) {
        SubscriptionEditorDialog(
            subscription = null,
            saving = state.savingSubscription,
            onDismiss = { if (!state.savingSubscription) showNewSubscription = false },
            onConfirm = { title, url ->
                viewModel.saveSubscription(null, title, url) { saved ->
                    if (saved) {
                        showNewSubscription = false
                        viewModel.refresh()
                    }
                }
            },
        )
    }

    editingSubscription?.let { subscription ->
        SubscriptionEditorDialog(
            subscription = subscription,
            saving = state.savingSubscription,
            onDismiss = { if (!state.savingSubscription) editingSubscription = null },
            onConfirm = { title, url ->
                viewModel.saveSubscription(subscription.id, title, url) { saved ->
                    if (saved) {
                        editingSubscription = null
                        viewModel.refresh()
                    }
                }
            },
        )
    }

    deletingSubscription?.let { subscription ->
        AlertDialog(
            onDismissRequest = { if (!state.savingSubscription) deletingSubscription = null },
            title = { Text(tr("删除 RSS 订阅？", "Delete RSS feed?")) },
            text = {
                Text(
                    tr(
                        "将删除“${subscription.title.ifBlank { subscription.url }}”及当前加载的文章。",
                        "This removes “${subscription.title.ifBlank { subscription.url }}” and its loaded articles.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSubscription(subscription.id)
                        deletingSubscription = null
                    },
                    enabled = !state.savingSubscription,
                ) { Text(tr("删除", "Delete")) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletingSubscription = null },
                    enabled = !state.savingSubscription,
                ) { Text(tr("取消", "Cancel")) }
            },
        )
    }
}

@Composable
private fun SectionHeading(title: String, detail: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubscriptionCard(
    subscription: RssSubscription,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassPanel(
        modifier = Modifier.widthIn(min = 250.dp, max = 300.dp),
        role = PanelRole.STANDARD,
        cornerRadius = 18.dp,
        padding = PaddingValues(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = subscription.enabled,
                onCheckedChange = onEnabledChange,
                enabled = enabled,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = subscription.title.ifBlank { subscription.url },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subscription.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Outlined.Edit, tr("编辑订阅", "Edit feed"))
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Outlined.Delete, tr("删除订阅", "Delete feed"))
            }
        }
    }
}

@Composable
private fun ArticleCard(
    article: RssArticle,
    showSummary: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = article.url.isNotBlank(), onClick = onOpen),
        cornerRadius = 20.dp,
        padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = article.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildArticleMeta(article, language),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (article.url.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (showSummary && article.summary.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = article.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SubscriptionEditorDialog(
    subscription: RssSubscription?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember(subscription?.id) { mutableStateOf(subscription?.title.orEmpty()) }
    var url by remember(subscription?.id) { mutableStateOf(subscription?.url.orEmpty()) }
    val canSave = url.isNotBlank() && !saving

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (subscription == null) tr("添加 RSS 订阅", "Add RSS feed")
                else tr("编辑 RSS 订阅", "Edit RSS feed"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(tr("名称（可选）", "Name (optional)")) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(tr("HTTPS 订阅地址", "HTTPS feed URL")) },
                    supportingText = {
                        Text(tr("省略协议时会自动使用 https://", "https:// is added when omitted"))
                    },
                    singleLine = true,
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (canSave) onConfirm(title, url) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, url) },
                enabled = canSave,
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(tr("保存", "Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(tr("取消", "Cancel"))
            }
        },
    )
}

private fun openArticle(context: android.content.Context, url: String): Boolean {
    if (url.isBlank()) return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
}

private fun buildArticleMeta(article: RssArticle, language: AppLanguage): String {
    val date = article.publishedAtMillis?.let { timestamp ->
        val locale = if (language == AppLanguage.ENGLISH) Locale.ENGLISH else Locale.SIMPLIFIED_CHINESE
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(timestamp))
    }
    return listOfNotNull(article.feedTitle.takeIf(String::isNotBlank), date).joinToString(" · ")
}
