package com.deskcubby.app.ui.poetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.PoetryCategoryEntity
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.repository.PoemEditContentStatus
import com.deskcubby.app.data.repository.PoetryBookRepository
import com.deskcubby.app.data.repository.PoetryPresetCategorySummary
import com.deskcubby.app.data.repository.PoetryPresetImportResult
import com.deskcubby.app.data.repository.PoetryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PoetryEditDraft(
    val poem: SavedPoemEntity,
    val contentStatus: PoemEditContentStatus,
)

sealed interface PoetryEditorState {
    data object Idle : PoetryEditorState
    data class Loading(val poemId: Long) : PoetryEditorState
    data class Ready(
        val draft: PoetryEditDraft,
        val saving: Boolean = false,
    ) : PoetryEditorState
}

enum class PoetryOperationFailure {
    LOAD_FOR_EDIT,
    CREATE,
    UPDATE,
    DELETE,
    CATEGORY,
    PRESET_IMPORT,
}

sealed interface PoetryCategoryFilter {
    data object All : PoetryCategoryFilter
    data object Uncategorized : PoetryCategoryFilter
    data class Category(val id: Long) : PoetryCategoryFilter
}

@HiltViewModel
class PoetryBookViewModel @Inject constructor(
    private val repository: PoetryBookRepository,
    private val dailyPoetryRepository: PoetryRepository,
) : ViewModel() {
    val poems: StateFlow<List<SavedPoemEntity>> = repository.poems.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val categories: StateFlow<List<PoetryCategoryEntity>> = repository.categories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val mutableSelectedCategory =
        MutableStateFlow<PoetryCategoryFilter>(PoetryCategoryFilter.All)
    val selectedCategory: StateFlow<PoetryCategoryFilter> = mutableSelectedCategory.asStateFlow()
    private val mutablePresetCategories =
        MutableStateFlow<List<PoetryPresetCategorySummary>>(emptyList())
    val presetCategories: StateFlow<List<PoetryPresetCategorySummary>> =
        mutablePresetCategories.asStateFlow()
    private val mutableImportingPresetId = MutableStateFlow<String?>(null)
    val importingPresetId: StateFlow<String?> = mutableImportingPresetId.asStateFlow()
    private val mutablePresetImportResult = MutableStateFlow<PoetryPresetImportResult?>(null)
    val presetImportResult: StateFlow<PoetryPresetImportResult?> =
        mutablePresetImportResult.asStateFlow()

    private val mutableError = MutableStateFlow<PoetryOperationFailure?>(null)
    val error: StateFlow<PoetryOperationFailure?> = mutableError.asStateFlow()

    private val mutableEditorState = MutableStateFlow<PoetryEditorState>(PoetryEditorState.Idle)
    val editorState: StateFlow<PoetryEditorState> = mutableEditorState.asStateFlow()
    private var editorLoadJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                mutablePresetCategories.value = repository.presetCategorySummaries()
            } catch (_: Exception) {
                mutableError.value = PoetryOperationFailure.PRESET_IMPORT
            }
        }
        viewModelScope.launch {
            categories.collectLatest { available ->
                val selected = mutableSelectedCategory.value
                if (
                    selected is PoetryCategoryFilter.Category &&
                    available.none { it.id == selected.id }
                ) {
                    mutableSelectedCategory.value = PoetryCategoryFilter.All
                }
            }
        }
    }

    fun create(
        content: String,
        source: String,
        categoryId: Long?,
        onDone: (Boolean) -> Unit = {},
    ) {
        launchOperation(PoetryOperationFailure.CREATE, onDone) {
            repository.create(content, source, categoryId)
        }
    }

    fun beginEdit(id: Long) {
        val current = mutableEditorState.value
        if (current is PoetryEditorState.Ready && current.saving) return
        editorLoadJob?.cancel()
        editorLoadJob = viewModelScope.launch {
            mutableEditorState.value = PoetryEditorState.Loading(id)
            try {
                // Re-query the canonical row instead of trusting any list-card representation.
                val stored = repository.loadForEdit(id)
                val resolution = dailyPoetryRepository.resolveSavedContentForEdit(
                    storedContent = stored.content,
                    storedSource = stored.source,
                )
                currentCoroutineContext().ensureActive()
                if ((mutableEditorState.value as? PoetryEditorState.Loading)?.poemId == id) {
                    mutableEditorState.value = PoetryEditorState.Ready(
                        PoetryEditDraft(
                            poem = stored.copy(content = resolution.content),
                            contentStatus = resolution.status,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if ((mutableEditorState.value as? PoetryEditorState.Loading)?.poemId == id) {
                    mutableEditorState.value = PoetryEditorState.Idle
                    mutableError.value = PoetryOperationFailure.LOAD_FOR_EDIT
                }
            }
        }
    }

    fun dismissEditor() {
        when (val current = mutableEditorState.value) {
            PoetryEditorState.Idle -> Unit
            is PoetryEditorState.Loading -> {
                editorLoadJob?.cancel()
                editorLoadJob = null
                mutableEditorState.value = PoetryEditorState.Idle
            }
            is PoetryEditorState.Ready -> {
                if (!current.saving) mutableEditorState.value = PoetryEditorState.Idle
            }
        }
    }

    fun saveEditor(content: String, source: String, categoryId: Long?) {
        val current = mutableEditorState.value as? PoetryEditorState.Ready ?: return
        if (current.saving) return
        mutableEditorState.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                repository.update(current.draft.poem.id, content, source, categoryId)
                mutableEditorState.value = PoetryEditorState.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val latest = mutableEditorState.value as? PoetryEditorState.Ready
                if (latest?.draft?.poem?.id == current.draft.poem.id) {
                    mutableEditorState.value = latest.copy(saving = false)
                }
                mutableError.value = PoetryOperationFailure.UPDATE
            }
        }
    }

    fun delete(id: Long) {
        launchOperation(PoetryOperationFailure.DELETE) { repository.delete(id) }
    }

    fun selectCategory(category: PoetryCategoryFilter) {
        mutableSelectedCategory.value = category
    }

    fun setCategory(id: Long, categoryId: Long?) {
        launchOperation(PoetryOperationFailure.CATEGORY) {
            repository.setCategory(id, categoryId)
        }
    }

    fun createCategory(name: String, colorArgb: Int, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try {
                onDone(repository.createCategory(name, colorArgb) != null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = PoetryOperationFailure.CATEGORY
                onDone(false)
            }
        }
    }

    fun updateCategory(
        id: Long,
        name: String,
        colorArgb: Int,
        onDone: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                onDone(repository.updateCategory(id, name, colorArgb))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = PoetryOperationFailure.CATEGORY
                onDone(false)
            }
        }
    }

    fun deleteCategory(id: Long) {
        launchOperation(PoetryOperationFailure.CATEGORY) {
            repository.deleteCategory(id)
        }
    }

    fun importPresetCategory(presetId: String) {
        if (mutableImportingPresetId.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableImportingPresetId.value = presetId
            try {
                val result = repository.importPresetCategory(presetId)
                mutablePresetImportResult.value = result
                mutableSelectedCategory.value = PoetryCategoryFilter.Category(result.categoryId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableError.value = PoetryOperationFailure.PRESET_IMPORT
            } finally {
                mutableImportingPresetId.value = null
            }
        }
    }

    fun consumePresetImportResult() {
        mutablePresetImportResult.value = null
    }

    fun consumeError() {
        mutableError.value = null
    }

    private fun launchOperation(
        failure: PoetryOperationFailure,
        onDone: (Boolean) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
                onDone(true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableError.value = failure
                onDone(false)
            }
        }
    }
}
