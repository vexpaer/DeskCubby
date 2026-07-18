package com.deskcubby.app.ui.blog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.BrowserRecordEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.BrowserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val loading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

@HiltViewModel
class BlogViewModel @Inject constructor(
    private val repository: BrowserRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )
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

    fun updateBrowserState(
        url: String? = null,
        title: String? = null,
        progress: Int? = null,
        canGoBack: Boolean? = null,
        canGoForward: Boolean? = null,
    ) {
        _uiState.value = _uiState.value.copy(
            url = url ?: _uiState.value.url,
            title = title ?: _uiState.value.title,
            progress = progress ?: _uiState.value.progress,
            loading = (progress ?: _uiState.value.progress) < 100,
            canGoBack = canGoBack ?: _uiState.value.canGoBack,
            canGoForward = canGoForward ?: _uiState.value.canGoForward,
        )
    }

    fun pageFinished(url: String, title: String) {
        updateBrowserState(url = url, title = title, progress = 100)
        viewModelScope.launch {
            repository.recordVisit(url, title)
            settingsRepository.setLastBrowserUrl(url)
        }
    }

    fun toggleFavorite() {
        val state = _uiState.value
        if (state.url.isBlank()) return
        val favorite = favorites.value.any { it.url == state.url }
        viewModelScope.launch { repository.setFavorite(state.url, state.title, !favorite) }
    }

    fun clearHistory() = viewModelScope.launch { repository.clearHistory() }
}
