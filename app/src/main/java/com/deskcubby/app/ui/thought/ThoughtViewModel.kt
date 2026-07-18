package com.deskcubby.app.ui.thought

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ThoughtViewModel @Inject constructor(
    private val repository: ThoughtRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val active: StateFlow<List<FlashThoughtEntity>> = repository.active.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val trash: StateFlow<List<FlashThoughtEntity>> = repository.trash.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    fun submit(selectedId: Long?, content: String, onDone: () -> Unit) {
        if (content.isBlank()) return
        viewModelScope.launch {
            if (selectedId == null) repository.create(content) else repository.update(selectedId, content)
            onDone()
        }
    }

    fun togglePinned(id: Long) = viewModelScope.launch { repository.togglePinned(id) }
    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }
    fun restore(id: Long) = viewModelScope.launch { repository.restore(id) }
    fun permanentlyDelete(id: Long) = viewModelScope.launch { repository.permanentlyDelete(id) }
    fun setSplitRatio(value: Float) = viewModelScope.launch { settingsRepository.setThoughtSplitRatio(value) }
}
