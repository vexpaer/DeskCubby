package com.deskcubby.app.ui.thought

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
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

sealed interface ThoughtCategoryFilter {
    data object All : ThoughtCategoryFilter
    data object Uncategorized : ThoughtCategoryFilter
    data class Category(val id: Long) : ThoughtCategoryFilter
}

@HiltViewModel
class ThoughtViewModel @Inject constructor(
    private val repository: ThoughtRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableActiveState = MutableStateFlow(ThoughtListState())
    val activeState: StateFlow<ThoughtListState> = mutableActiveState.asStateFlow()
    private val mutableSelectedCategory = MutableStateFlow<ThoughtCategoryFilter>(ThoughtCategoryFilter.All)
    val selectedCategory: StateFlow<ThoughtCategoryFilter> = mutableSelectedCategory.asStateFlow()
    val categories: StateFlow<List<ThoughtCategoryEntity>> = repository.categories.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
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
        viewModelScope.launch {
            categories.collect { available ->
                val selected = mutableSelectedCategory.value
                if (selected is ThoughtCategoryFilter.Category && available.none { it.id == selected.id }) {
                    mutableSelectedCategory.value = ThoughtCategoryFilter.Uncategorized
                }
            }
        }
    }

    fun submit(
        selectedId: Long?,
        content: String,
        category: ThoughtCategoryFilter? = null,
        onDone: () -> Unit,
    ) {
        if (content.isBlank()) return
        viewModelScope.launch {
            if (selectedId == null) {
                val destination = category ?: mutableSelectedCategory.value
                repository.create(content, destination.categoryIdOrNull())
            } else {
                repository.update(selectedId, content)
                if (category != null) repository.setCategory(selectedId, category.categoryIdOrNull())
            }
            onDone()
        }
    }

    fun togglePinned(id: Long) = viewModelScope.launch { repository.togglePinned(id) }
    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }
    fun restore(id: Long) = viewModelScope.launch { repository.restore(id) }
    fun permanentlyDelete(id: Long) = viewModelScope.launch { repository.permanentlyDelete(id) }
    fun selectCategory(category: ThoughtCategoryFilter) {
        mutableSelectedCategory.value = category
    }

    fun setCategory(id: Long, category: ThoughtCategoryFilter) = viewModelScope.launch {
        repository.setCategory(id, category.categoryIdOrNull())
    }

    fun createCategory(name: String, colorArgb: Int, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.createCategory(name, colorArgb) != null) }
    }

    fun updateCategory(id: Long, name: String, colorArgb: Int, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.updateCategory(id, name, colorArgb)) }
    }

    fun deleteCategory(id: Long, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onDone(repository.deleteCategory(id)) }
    }

    fun setSplitRatio(value: Float) = viewModelScope.launch { settingsRepository.setThoughtSplitRatio(value) }

    fun move(id: Long, targetIndex: Int) {
        val selected = mutableSelectedCategory.value
        viewModelScope.launch {
            when (selected) {
                ThoughtCategoryFilter.All -> repository.move(id, targetIndex)
                ThoughtCategoryFilter.Uncategorized -> repository.moveInCategory(id, targetIndex, null)
                is ThoughtCategoryFilter.Category -> repository.moveInCategory(id, targetIndex, selected.id)
            }
        }
    }

    fun consumeScrollRequest(id: Long) {
        mutableActiveState.update { state ->
            if (state.pendingScrollItemId == id) state.copy(pendingScrollItemId = null) else state
        }
    }
}

private fun ThoughtCategoryFilter.categoryIdOrNull(): Long? = when (this) {
    ThoughtCategoryFilter.All,
    ThoughtCategoryFilter.Uncategorized,
    -> null
    is ThoughtCategoryFilter.Category -> id
}
