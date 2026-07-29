package com.deskcubby.app.ui.rss

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.RssSubscription
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.RssArticle
import com.deskcubby.app.data.repository.RssRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RssUiState(
    val loadingSettings: Boolean = true,
    val refreshing: Boolean = false,
    val savingSubscription: Boolean = false,
    val subscriptions: List<RssSubscription> = emptyList(),
    val articles: List<RssArticle> = emptyList(),
    val maxItemsPerFeed: Int = 20,
    val showSummaries: Boolean = true,
    val lastUpdatedAtMillis: Long? = null,
    val error: String? = null,
)

@HiltViewModel
class RssViewModel @Inject constructor(
    private val repository: RssRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(RssUiState())
    val uiState: StateFlow<RssUiState> = mutableUiState.asStateFlow()

    private var startedInitialRefresh = false
    private var refreshPending = false

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableUiState.update { state ->
                    state.copy(
                        loadingSettings = false,
                        subscriptions = settings.rssSubscriptions,
                        maxItemsPerFeed = settings.rssMaxItemsPerFeed,
                        showSummaries = settings.rssShowSummaries,
                    )
                }
                if (!startedInitialRefresh) {
                    startedInitialRefresh = true
                    if (settings.rssSubscriptions.any(RssSubscription::enabled)) refresh()
                }
            }
        }
    }

    fun saveSubscription(
        subscriptionId: String?,
        title: String,
        url: String,
        onDone: (Boolean) -> Unit = {},
    ) {
        if (mutableUiState.value.savingSubscription) return
        val normalizedUrl = try {
            repository.normalizeFeedUrl(url)
        } catch (error: Exception) {
            report(error)
            onDone(false)
            return
        }
        val current = mutableUiState.value.subscriptions
        if (current.any { it.id != subscriptionId && it.url.equals(normalizedUrl, ignoreCase = true) }) {
            mutableUiState.update { it.copy(error = "这个 RSS 地址已经添加过了。") }
            onDone(false)
            return
        }
        val normalizedTitle = title.trim().ifBlank {
            runCatching { URI(normalizedUrl).host.orEmpty() }.getOrDefault("").ifBlank { "RSS" }
        }
        val updated = if (subscriptionId == null) {
            current + RssSubscription(
                id = UUID.randomUUID().toString(),
                title = normalizedTitle,
                url = normalizedUrl,
            )
        } else {
            var found = false
            current.map { subscription ->
                if (subscription.id == subscriptionId) {
                    found = true
                    subscription.copy(title = normalizedTitle, url = normalizedUrl)
                } else {
                    subscription
                }
            }.let { items ->
                if (found) items else items + RssSubscription(
                    id = subscriptionId,
                    title = normalizedTitle,
                    url = normalizedUrl,
                )
            }
        }
        persistSubscriptions(updated, onDone)
    }

    fun deleteSubscription(subscriptionId: String) {
        val updated = mutableUiState.value.subscriptions.filterNot { it.id == subscriptionId }
        persistSubscriptions(updated) { success ->
            if (success) {
                mutableUiState.update { state ->
                    state.copy(articles = state.articles.filterNot { it.feedId == subscriptionId })
                }
            }
        }
    }

    fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean) {
        val updated = mutableUiState.value.subscriptions.map { subscription ->
            if (subscription.id == subscriptionId) subscription.copy(enabled = enabled) else subscription
        }
        persistSubscriptions(updated) { success ->
            if (success && !enabled) {
                mutableUiState.update { state ->
                    state.copy(articles = state.articles.filterNot { it.feedId == subscriptionId })
                }
            }
            if (success && enabled) refresh()
        }
    }

    fun refresh() {
        val snapshot = mutableUiState.value
        if (snapshot.refreshing) {
            refreshPending = true
            return
        }
        if (snapshot.loadingSettings) return
        val enabled = snapshot.subscriptions.filter(RssSubscription::enabled)
        if (enabled.isEmpty()) {
            mutableUiState.update {
                it.copy(
                    articles = emptyList(),
                    error = if (it.subscriptions.isEmpty()) "请先添加一个 RSS 订阅。" else "没有已启用的 RSS 订阅。",
                )
            }
            return
        }
        mutableUiState.update { it.copy(refreshing = true, error = null) }
        val requestedUrls = enabled.associate { it.id to it.url }
        viewModelScope.launch {
            try {
                val result = repository.refresh(enabled, snapshot.maxItemsPerFeed)
                if (refreshPending) {
                    // A subscription changed while this request was running. The queued refresh
                    // below owns the visible result, so do not flash stale articles first.
                    mutableUiState.update { it.copy(refreshing = false) }
                    return@launch
                }
                mutableUiState.update { current ->
                    val activeFeedIds = current.subscriptions
                        .filter { subscription ->
                            subscription.enabled && requestedUrls[subscription.id] == subscription.url
                        }
                        .mapTo(hashSetOf(), RssSubscription::id)
                    val retainedFromFailedFeeds = current.articles.filter { article ->
                        article.feedId in result.errors && article.feedId in activeFeedIds
                    }
                    val freshForActiveFeeds = result.articles.filter { it.feedId in activeFeedIds }
                    val merged = (freshForActiveFeeds + retainedFromFailedFeeds)
                        .distinctBy(RssArticle::id)
                        .sortedWith(
                            compareByDescending<RssArticle> { it.publishedAtMillis ?: Long.MIN_VALUE }
                                .thenBy { it.feedTitle }
                                .thenBy { it.title },
                        )
                    current.copy(
                        refreshing = false,
                        articles = merged,
                        lastUpdatedAtMillis = System.currentTimeMillis(),
                        error = formatRefreshErrors(result.errors, current.subscriptions),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update { current ->
                    if (refreshPending) current.copy(refreshing = false) else current.copy(
                        refreshing = false,
                        error = error.message?.takeIf(String::isNotBlank) ?: "RSS 刷新失败。",
                    )
                }
            } finally {
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }
        }
    }

    fun consumeError() {
        mutableUiState.update { it.copy(error = null) }
    }

    private fun persistSubscriptions(
        subscriptions: List<RssSubscription>,
        onDone: (Boolean) -> Unit = {},
    ) {
        if (mutableUiState.value.savingSubscription) return
        mutableUiState.update { it.copy(savingSubscription = true, error = null) }
        viewModelScope.launch {
            try {
                settingsRepository.setRssSubscriptions(subscriptions)
                mutableUiState.update {
                    it.copy(
                        savingSubscription = false,
                        subscriptions = subscriptions,
                    )
                }
                onDone(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableUiState.update {
                    it.copy(
                        savingSubscription = false,
                        error = error.message?.takeIf(String::isNotBlank) ?: "保存 RSS 订阅失败。",
                    )
                }
                onDone(false)
            }
        }
    }

    private fun report(error: Exception) {
        mutableUiState.update {
            it.copy(error = error.message?.takeIf(String::isNotBlank) ?: "RSS 地址无效。")
        }
    }
}

private fun formatRefreshErrors(
    errors: Map<String, String>,
    subscriptions: List<RssSubscription>,
): String? {
    if (errors.isEmpty()) return null
    val titles = subscriptions.associate { it.id to it.title.ifBlank { it.url } }
    return errors.entries.joinToString(separator = "\n") { (feedId, message) ->
        "${titles[feedId] ?: "RSS"}：$message"
    }
}
