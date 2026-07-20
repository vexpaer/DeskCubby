package com.deskcubby.app.ui.blog

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.BrowserTheme
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.BrowserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val MAX_BROWSER_TABS = 8
const val BROWSER_BLANK_URL = "about:blank"

data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

data class BrowserTabState(
    val id: Long,
    val addressDraft: String,
    val addressDirty: Boolean = false,
    val url: String,
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val renderProcessGone: Boolean = false,
) {
    fun toBrowserUiState() = BrowserUiState(
        url = url,
        title = title,
        progress = progress,
        loading = loading,
        canGoBack = canGoBack,
        canGoForward = canGoForward,
    )
}

data class BrowserTabsState(
    val ready: Boolean = false,
    val tabs: List<BrowserTabState> = emptyList(),
    val currentTabId: Long? = null,
) {
    val currentTab: BrowserTabState?
        get() = tabs.firstOrNull { it.id == currentTabId } ?: tabs.firstOrNull()
}

@HiltViewModel
class BlogViewModel @Inject constructor(
    private val repository: BrowserRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _settings = MutableStateFlow<AppSettings?>(null)
    val settings: StateFlow<AppSettings?> = _settings.asStateFlow()

    val history: StateFlow<List<BrowserRecordEntity>> = repository.history.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val favorites: StateFlow<List<BrowserRecordEntity>> = repository.favorites.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState = _uiState.asStateFlow()

    private val _tabsState = MutableStateFlow(BrowserTabsState())
    val tabsState: StateFlow<BrowserTabsState> = _tabsState.asStateFlow()
    private var nextTabId = 1L

    init {
        viewModelScope.launch {
            val initialSettings = settingsRepository.settings.first()
            _settings.value = initialSettings
            val initialUrl = SettingsRepository.normalizeUrl(
                initialSettings.lastBrowserUrl
                    ?.takeUnless { it.isBlank() || it.equals(BROWSER_BLANK_URL, ignoreCase = true) }
                    ?: initialSettings.browserHomeUrl,
            )
            val initialIsBlank = initialUrl.equals(BROWSER_BLANK_URL, ignoreCase = true)
            val initialTab = BrowserTabState(
                id = 0L,
                addressDraft = if (initialIsBlank) "" else initialUrl,
                url = initialUrl,
                loading = !initialIsBlank,
            )
            _tabsState.value = BrowserTabsState(
                ready = true,
                tabs = listOf(initialTab),
                currentTabId = initialTab.id,
            )
            _uiState.value = initialTab.toBrowserUiState()

            settingsRepository.settings.collect { _settings.value = it }
        }
    }

    fun selectTab(tabId: Long) {
        val state = _tabsState.value
        val tab = state.tabs.firstOrNull { it.id == tabId } ?: return
        if (state.currentTabId == tabId) return
        _tabsState.value = state.copy(currentTabId = tabId)
        _uiState.value = tab.toBrowserUiState()
    }

    fun addTab(): Boolean {
        val state = _tabsState.value
        if (!state.ready || state.tabs.size >= MAX_BROWSER_TABS) return false
        val tab = BrowserTabState(
            id = nextTabId++,
            addressDraft = "",
            url = BROWSER_BLANK_URL,
            loading = false,
        )
        _tabsState.value = state.copy(
            tabs = state.tabs + tab,
            currentTabId = tab.id,
        )
        _uiState.value = tab.toBrowserUiState()
        return true
    }

    fun closeTab(tabId: Long): Boolean {
        val state = _tabsState.value
        val closingIndex = state.tabs.indexOfFirst { it.id == tabId }
        if (closingIndex < 0) return false

        if (state.tabs.size == 1) {
            val blankTab = BrowserTabState(
                id = nextTabId++,
                addressDraft = "",
                url = BROWSER_BLANK_URL,
            )
            _tabsState.value = state.copy(
                tabs = listOf(blankTab),
                currentTabId = blankTab.id,
            )
            _uiState.value = blankTab.toBrowserUiState()
            return true
        }

        val remaining = state.tabs.filterNot { it.id == tabId }
        val nextCurrentId = if (state.currentTabId == tabId) {
            remaining[closingIndex.coerceAtMost(remaining.lastIndex)].id
        } else {
            state.currentTabId?.takeIf { id -> remaining.any { it.id == id } } ?: remaining.first().id
        }
        val nextCurrent = remaining.first { it.id == nextCurrentId }
        _tabsState.value = state.copy(tabs = remaining, currentTabId = nextCurrentId)
        _uiState.value = nextCurrent.toBrowserUiState()
        return true
    }

    fun updateAddressDraft(tabId: Long, value: String) {
        updateTab(tabId) { it.copy(addressDraft = value, addressDirty = true) }
    }

    fun commitAddress(tabId: Long, rawAddress: String): String {
        val normalized = SettingsRepository.normalizeUrl(rawAddress)
        val isBlankPage = normalized.equals(BROWSER_BLANK_URL, ignoreCase = true)
        updateTab(tabId) {
            val wasRecovering = it.renderProcessGone
            it.copy(
                addressDraft = if (isBlankPage) "" else normalized,
                addressDirty = false,
                url = normalized,
                progress = 0,
                loading = !isBlankPage,
                title = if (isBlankPage) "" else it.title,
                canGoBack = if (isBlankPage || wasRecovering) false else it.canGoBack,
                canGoForward = if (isBlankPage || wasRecovering) false else it.canGoForward,
                renderProcessGone = false,
            )
        }
        return normalized
    }

    fun updateTabBrowserState(
        tabId: Long,
        url: String? = null,
        title: String? = null,
        progress: Int? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null,
    ) {
        updateTab(tabId) { old ->
            val committedUrl = url?.takeIf(String::isNotBlank) ?: old.url
            val isBlankPage = committedUrl.equals(BROWSER_BLANK_URL, ignoreCase = true)
            old.copy(
                addressDraft = if (old.addressDirty) {
                    old.addressDraft
                } else if (isBlankPage) {
                    ""
                } else {
                    committedUrl
                },
                url = committedUrl,
                title = title ?: old.title,
                progress = progress ?: old.progress,
                loading = !isBlankPage && (progress ?: old.progress) < 100,
                canGoBack = if (isBlankPage) false else canGoBack ?: old.canGoBack,
                canGoForward = if (isBlankPage) false else canGoForward ?: old.canGoForward,
            )
        }
    }

    fun pageFinished(
        tabId: Long,
        url: String,
        title: String,
        canGoBack: Boolean,
        canGoForward: Boolean,
    ) {
        updateTabBrowserState(
            tabId = tabId,
            url = url,
            title = title,
            progress = 100,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
        )
        if (url.isBlank() || url.equals(BROWSER_BLANK_URL, ignoreCase = true)) return
        val isActive = _tabsState.value.currentTabId == tabId
        launchPersistenceOperation("record browser visit") {
            repository.recordVisit(url, title)
        }
        if (isActive) {
            launchPersistenceOperation("save last browser URL") {
                settingsRepository.setLastBrowserUrl(url)
            }
        }
    }

    fun markRenderProcessGone(tabId: Long, didCrash: Boolean) {
        Log.w(BLOG_VIEW_MODEL_TAG, "WebView renderer ${if (didCrash) "crashed" else "was killed"} for tab $tabId")
        updateTab(tabId) {
            it.copy(
                progress = 0,
                loading = false,
                canGoBack = false,
                canGoForward = false,
                renderProcessGone = true,
            )
        }
    }

    fun reloadAfterRenderProcessGone(tabId: Long) {
        updateTab(tabId) { tab ->
            val isBlankPage = tab.url.equals(BROWSER_BLANK_URL, ignoreCase = true)
            tab.copy(
                progress = 0,
                loading = !isBlankPage,
                canGoBack = false,
                canGoForward = false,
                renderProcessGone = false,
            )
        }
    }

    fun toggleFavorite(
        url: String = _uiState.value.url,
        title: String = _uiState.value.title,
    ) {
        if (url.isBlank() || url.equals(BROWSER_BLANK_URL, ignoreCase = true)) return
        val favorite = favorites.value.any { it.url == url }
        launchPersistenceOperation("update browser favorite") {
            repository.setFavorite(url, title, !favorite)
        }
    }

    fun clearHistory() {
        launchPersistenceOperation("clear browser history") { repository.clearHistory() }
    }

    fun setBrowserTheme(value: BrowserTheme) {
        launchPersistenceOperation("save browser theme") { settingsRepository.setBrowserTheme(value) }
    }

    fun setDesktopMode(enabled: Boolean) {
        launchPersistenceOperation("save browser desktop mode") {
            settingsRepository.setBrowserDesktopMode(enabled)
        }
    }

    private fun launchPersistenceOperation(
        operation: String,
        block: suspend () -> Unit,
    ) = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(BLOG_VIEW_MODEL_TAG, "Failed to $operation", error)
        }
    }

    private fun updateTab(tabId: Long, transform: (BrowserTabState) -> BrowserTabState) {
        val state = _tabsState.value
        val index = state.tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val updatedTab = transform(state.tabs[index])
        val updatedTabs = state.tabs.toMutableList().apply { this[index] = updatedTab }
        _tabsState.value = state.copy(tabs = updatedTabs)
        if (state.currentTabId == tabId) _uiState.value = updatedTab.toBrowserUiState()
    }
}

private const val BLOG_VIEW_MODEL_TAG = "BlogViewModel"
