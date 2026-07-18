package com.deskcubby.app.ui.thought

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ThoughtListState(
    val isLoaded: Boolean = false,
    val items: List<FlashThoughtEntity> = emptyList(),
    val pendingScrollItemId: Long? = null,
)

@HiltViewModel
class ThoughtViewModel @Inject constructor(
    private val repository: ThoughtRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableActiveState = MutableStateFlow(ThoughtListState())
    val activeState: StateFlow<ThoughtListState> = mutableActiveState.asStateFlow()
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

    init {
        viewModelScope.launch {
            repository.active.collect { items ->
                val currentIds = items.mapTo(hashSetOf<Long>()) { it.id }
                mutableActiveState.update { previous ->
                    val addedItem = if (previous.isLoaded) {
                        val previousIds = previous.items.mapTo(hashSetOf<Long>()) { it.id }
                        items.filterNot { it.id in previousIds }.maxByOrNull { it.updatedAt }
                    } else {
                        null
                    }
                    ThoughtListState(
                        isLoaded = true,
                        items = items,
                        pendingScrollItemId = addedItem?.id
                            ?: previous.pendingScrollItemId?.takeIf { it in currentIds },
                    )
                }
            }
        }
    }

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

    fun move(id: Long, targetIndex: Int) {
        viewModelScope.launch { repository.move(id, targetIndex) }
    }

    fun consumeScrollRequest(id: Long) {
        mutableActiveState.update { state ->
            if (state.pendingScrollItemId == id) state.copy(pendingScrollItemId = null) else state
        }
    }
}
