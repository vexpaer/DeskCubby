package com.deskcubby.app.ui.poetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.SavedPoemEntity
import com.deskcubby.app.data.repository.PoetryBookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PoetryBookViewModel @Inject constructor(
    private val repository: PoetryBookRepository,
) : ViewModel() {
    val poems: StateFlow<List<SavedPoemEntity>> = repository.poems.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()

    fun create(content: String, source: String, onDone: (Boolean) -> Unit = {}) {
        launchOperation(onDone) { repository.create(content, source) }
    }

    fun update(id: Long, content: String, source: String, onDone: (Boolean) -> Unit = {}) {
        launchOperation(onDone) { repository.update(id, content, source) }
    }

    fun delete(id: Long) {
        launchOperation { repository.delete(id) }
    }

    fun consumeError() {
        mutableError.value = null
    }

    private fun launchOperation(
        onDone: (Boolean) -> Unit = {},
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
                onDone(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableError.value = error.message ?: "Unknown error"
                onDone(false)
            }
        }
    }
}
