package com.deskcubby.app.ui.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.repository.ReaderBook
import com.deskcubby.app.data.repository.ReaderContent
import com.deskcubby.app.data.repository.ReaderPreferences
import com.deskcubby.app.data.repository.ReaderRepository
import com.deskcubby.app.data.statistics.EngagementKind
import com.deskcubby.app.data.statistics.EngagementTimeRepository
import com.deskcubby.app.data.statistics.EngagementTimeSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ReaderContentState {
    data object Idle : ReaderContentState
    data object Loading : ReaderContentState
    data class Ready(val book: ReaderBook, val content: ReaderContent) : ReaderContentState
    data class Failed(val book: ReaderBook) : ReaderContentState
}

enum class ReaderMessage { IMPORT_FAILED, REMOVE_FAILED, SETTINGS_FAILED }

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: ReaderRepository,
    private val engagementTimeRepository: EngagementTimeRepository,
) : ViewModel() {
    val library = repository.state
    val storageIssue = repository.storageIssue
    val engagementTimes: StateFlow<EngagementTimeSnapshot> = engagementTimeRepository.snapshot

    private val _content = MutableStateFlow<ReaderContentState>(ReaderContentState.Idle)
    val content: StateFlow<ReaderContentState> = _content.asStateFlow()
    private val _message = MutableStateFlow<ReaderMessage?>(null)
    val message: StateFlow<ReaderMessage?> = _message.asStateFlow()
    private var openJob: Job? = null

    init {
        // ReaderRepository deliberately avoids constructor-time disk I/O. Loading here keeps the
        // first frame responsive while every later mutation is serialized behind the same mutex.
        viewModelScope.launch { repository.initialize() }
    }

    fun import(uri: Uri) = viewModelScope.launch {
        try {
            val book = repository.import(uri)
            open(book)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _message.value = ReaderMessage.IMPORT_FAILED
        }
    }

    fun open(book: ReaderBook) {
        openJob?.cancel()
        openJob = viewModelScope.launch {
            _content.value = ReaderContentState.Loading
            try {
                repository.markOpened(book.id)
                val current = repository.state.value.books.firstOrNull { it.id == book.id } ?: book
                _content.value = ReaderContentState.Ready(current, repository.load(current))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _content.value = ReaderContentState.Failed(book)
            }
        }
    }

    fun close() {
        openJob?.cancel()
        openJob = null
        activeBookId()?.let(::endReading)
        _content.value = ReaderContentState.Idle
    }

    fun remove(bookId: String) = viewModelScope.launch {
        if (activeBookId() == bookId) close()
        try {
            repository.remove(bookId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _message.value = ReaderMessage.REMOVE_FAILED
        }
    }

    fun updatePreferences(value: ReaderPreferences) = viewModelScope.launch {
        try {
            repository.updatePreferences(value)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _message.value = ReaderMessage.SETTINGS_FAILED
        }
    }

    fun saveTextProgress(bookId: String, pageIndex: Int, paragraphIndex: Int) =
        viewModelScope.launch {
        runCatching { repository.saveTextProgress(bookId, pageIndex, paragraphIndex) }
    }

    fun savePdfProgress(bookId: String, pageIndex: Int) = viewModelScope.launch {
        runCatching { repository.savePdfProgress(bookId, pageIndex) }
    }

    suspend fun renderPdfPage(book: ReaderBook, pageIndex: Int, widthPx: Int): Bitmap =
        repository.renderPdfPage(book, pageIndex, widthPx)

    fun beginReading(bookId: String) {
        engagementTimeRepository.begin(EngagementKind.READING, bookId)
    }

    fun checkpointReading(bookId: String) = viewModelScope.launch {
        persistEngagementTime {
            engagementTimeRepository.checkpoint(EngagementKind.READING, bookId)
        }
    }

    fun endReading(bookId: String) {
        // Detach synchronously so an immediate configuration-change resume cannot let a delayed
        // end remove the newly-started session. Repository-owned persistence also survives this
        // ViewModel being cleared while the reader is leaving the Activity.
        try {
            engagementTimeRepository.endAndCommit(EngagementKind.READING, bookId)
        } catch (_: Exception) {
            // Timing failure must not make an Activity lifecycle callback crash the reader.
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun activeBookId(): String? = when (val current = _content.value) {
        is ReaderContentState.Ready -> current.book.id
        is ReaderContentState.Failed -> current.book.id
        ReaderContentState.Idle,
        ReaderContentState.Loading,
        -> null
    }

    private suspend fun persistEngagementTime(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Reading remains usable even if the private timing file cannot be updated.
        }
    }
}
