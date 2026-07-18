package com.deskcubby.app.ui.diary

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.DiaryEditorDocument
import com.deskcubby.app.data.model.DiaryTrashItem
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.ExternalFileConflictException
import com.deskcubby.app.data.repository.DiaryTextUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DiaryListState(
    val loading: Boolean = false,
    val items: List<DiaryDocument> = emptyList(),
    val error: String? = null,
) {
    val byMonth: Map<String, List<DiaryDocument>> get() = items.groupBy { it.monthKey }
}

data class EditorState(
    val document: DiaryEditorDocument? = null,
    val content: String = "",
    val loading: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val preview: Boolean = false,
    val error: String? = null,
    val conflict: DiaryEditorDocument? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repository: DiaryFileRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    private val _listState = MutableStateFlow(DiaryListState())
    val listState: StateFlow<DiaryListState> = _listState.asStateFlow()

    private val _editorState = MutableStateFlow(EditorState())
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    private val _trash = MutableStateFlow<List<DiaryTrashItem>>(emptyList())
    val trash: StateFlow<List<DiaryTrashItem>> = _trash.asStateFlow()

    private val saveRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private val saveMutex = Mutex()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            saveRequests.debounce(1_200).collect { saveNow() }
        }
        viewModelScope.launch {
            settings.map { it.diaryTreeUri }.distinctUntilChanged().collect {
                if (it != null) refresh() else _listState.value = DiaryListState()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _listState.value = _listState.value.copy(loading = true, error = null)
            runCatching { repository.scan(settings.value) }
                .onSuccess { _listState.value = DiaryListState(items = it) }
                .onFailure { _listState.value = DiaryListState(error = it.userMessage()) }
        }
    }

    fun open(uri: String) {
        viewModelScope.launch {
            _editorState.value = EditorState(loading = true)
            runCatching { repository.load(uri) }
                .onSuccess { doc ->
                    undoStack.clear()
                    redoStack.clear()
                    _editorState.value = EditorState(document = doc, content = doc.content)
                }
                .onFailure { _editorState.value = EditorState(error = it.userMessage()) }
        }
    }

    fun enterToday(onOpened: () -> Unit) {
        viewModelScope.launch {
            _editorState.value = EditorState(loading = true)
            runCatching { repository.enterToday(settings.value) }
                .onSuccess { doc ->
                    undoStack.clear()
                    redoStack.clear()
                    _editorState.value = EditorState(document = doc, content = doc.content)
                    refresh()
                    onOpened()
                }
                .onFailure { _editorState.value = EditorState(error = it.userMessage()) }
        }
    }

    fun create(title: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.create(settings.value, title) }
                .onSuccess { doc ->
                    _editorState.value = EditorState(document = doc, content = doc.content)
                    refresh()
                    onOpened()
                }
                .onFailure { _listState.value = _listState.value.copy(error = it.userMessage()) }
        }
    }

    fun onContentChanged(value: String, recordUndo: Boolean = true) {
        val old = _editorState.value.content
        if (old == value) return
        if (recordUndo) {
            undoStack.addLast(old)
            while (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear()
        }
        _editorState.value = _editorState.value.copy(content = value, dirty = true, error = null)
        saveRequests.tryEmit(Unit)
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_editorState.value.content)
        _editorState.value = _editorState.value.copy(content = previous, dirty = true)
        saveRequests.tryEmit(Unit)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_editorState.value.content)
        _editorState.value = _editorState.value.copy(content = next, dirty = true)
        saveRequests.tryEmit(Unit)
    }

    fun togglePreview() {
        _editorState.value = _editorState.value.copy(preview = !_editorState.value.preview)
    }

    fun saveNow(force: Boolean = false) {
        viewModelScope.launch {
            saveMutex.withLock {
                val snapshot = _editorState.value
                val doc = snapshot.document ?: return@withLock
                if (!snapshot.dirty && !force) return@withLock
                if (snapshot.conflict != null && !force) return@withLock
                _editorState.value = snapshot.copy(saving = true, error = null)
                runCatching { repository.save(doc.uri, snapshot.content, doc.sha256, force) }
                    .onSuccess { saved ->
                        val changedDuringSave = _editorState.value.content != snapshot.content
                        _editorState.value = _editorState.value.copy(
                            document = saved,
                            saving = false,
                            dirty = changedDuringSave,
                            conflict = null,
                        )
                        if (changedDuringSave) saveRequests.tryEmit(Unit)
                        refresh()
                    }
                    .onFailure { error ->
                        if (error is ExternalFileConflictException) {
                            _editorState.value = _editorState.value.copy(saving = false, conflict = error.diskDocument)
                        } else {
                            _editorState.value = _editorState.value.copy(saving = false, error = error.userMessage())
                        }
                    }
            }
        }
    }

    fun reloadConflict() {
        val disk = _editorState.value.conflict ?: return
        undoStack.addLast(_editorState.value.content)
        _editorState.value = EditorState(document = disk, content = disk.content)
    }

    fun dismissError() {
        _editorState.value = _editorState.value.copy(error = null)
        _listState.value = _listState.value.copy(error = null)
    }

    fun importImage(uri: Uri, category: String?, cursor: Int) {
        viewModelScope.launch {
            runCatching { repository.importImage(uri, category, settings.value) }
                .onSuccess { media ->
                    val state = _editorState.value
                    val index = cursor.coerceIn(0, state.content.length)
                    val beforeBreak = if (index > 0 && state.content[index - 1] != '\n') "\n" else ""
                    val afterBreak = if (index < state.content.length && state.content[index] != '\n') "\n" else ""
                    val inserted = state.content.substring(0, index) + beforeBreak + media.markdown + afterBreak + state.content.substring(index)
                    onContentChanged(inserted)
                }
                .onFailure { _editorState.value = _editorState.value.copy(error = it.userMessage()) }
        }
    }

    fun updateImageCaption(fullMarkdown: String, newCaption: String) {
        val state = _editorState.value
        val replacement = fullMarkdown.replaceFirst(Regex("!\\[[^]]*]"), "![${newCaption.replace("]", "") }]")
        onContentChanged(state.content.replaceFirst(fullMarkdown, replacement))
    }

    fun moveImageBlock(fullMarkdown: String, direction: Int) {
        val moved = DiaryTextUtils.moveStandaloneImage(_editorState.value.content, fullMarkdown, direction)
        if (moved != _editorState.value.content) onContentChanged(moved)
    }

    suspend fun resolveMedia(target: String): Uri? = repository.resolveMedia(target, settings.value)

    fun rename(uri: String, title: String) {
        viewModelScope.launch {
            runCatching { repository.rename(uri, title) }
                .onSuccess { refresh() }
                .onFailure { _listState.value = _listState.value.copy(error = it.userMessage()) }
        }
    }

    fun delete(uri: String) {
        viewModelScope.launch {
            runCatching { repository.delete(uri) }
                .onSuccess { refresh(); refreshTrash() }
                .onFailure { _listState.value = _listState.value.copy(error = it.userMessage()) }
        }
    }

    fun refreshTrash() {
        viewModelScope.launch {
            runCatching { repository.scanTrash(settings.value) }
                .onSuccess { _trash.value = it }
                .onFailure { _listState.value = _listState.value.copy(error = it.userMessage()) }
        }
    }

    fun restoreTrash(uri: String) {
        viewModelScope.launch {
            runCatching { repository.restore(uri, settings.value) }
                .onSuccess { refresh(); refreshTrash() }
                .onFailure { _listState.value = _listState.value.copy(error = it.userMessage()) }
        }
    }

    fun permanentlyDeleteTrash(uri: String) {
        viewModelScope.launch {
            runCatching { repository.permanentlyDelete(uri) }
                .onSuccess { refreshTrash() }
                .onFailure { _listState.value = _listState.value.copy(error = it.userMessage()) }
        }
    }

    private fun Throwable.userMessage(): String = message ?: "操作失败，请检查目录授权"
}
