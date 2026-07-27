package com.deskcubby.app.ui.poetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.repository.PoemEditContentStatus
import com.deskcubby.app.data.repository.PoetryBookRepository
import com.deskcubby.app.data.repository.PoetryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val mutableError = MutableStateFlow<PoetryOperationFailure?>(null)
    val error: StateFlow<PoetryOperationFailure?> = mutableError.asStateFlow()

    private val mutableEditorState = MutableStateFlow<PoetryEditorState>(PoetryEditorState.Idle)
    val editorState: StateFlow<PoetryEditorState> = mutableEditorState.asStateFlow()
    private var editorLoadJob: Job? = null

    fun create(content: String, source: String, onDone: (Boolean) -> Unit = {}) {
        launchOperation(PoetryOperationFailure.CREATE, onDone) {
            repository.create(content, source)
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

    fun saveEditor(content: String, source: String) {
        val current = mutableEditorState.value as? PoetryEditorState.Ready ?: return
        if (current.saving) return
        mutableEditorState.value = current.copy(saving = true)
        viewModelScope.launch {
            try {
                repository.update(current.draft.poem.id, content, source)
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
