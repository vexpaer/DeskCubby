package com.deskcubby.app.ui.diary

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.DiaryEditorDocument
import com.deskcubby.app.data.model.DiaryTrashItem
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.DiaryTextUtils
import com.deskcubby.app.data.repository.ExternalFileConflictException
import com.deskcubby.app.data.repository.MealCalendarDay
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

data class MealCalendarState(
    val loading: Boolean = false,
    val items: List<MealCalendarDay> = emptyList(),
    val error: String? = null,
)

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

    private val _mealCalendarState = MutableStateFlow(MealCalendarState())
    val mealCalendarState: StateFlow<MealCalendarState> = _mealCalendarState.asStateFlow()

    private val _expandedMonth = MutableStateFlow<String?>(null)
    val expandedMonth: StateFlow<String?> = _expandedMonth.asStateFlow()

    private val _editorState = MutableStateFlow(EditorState())
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    private val _trash = MutableStateFlow<List<DiaryTrashItem>>(emptyList())
    val trash: StateFlow<List<DiaryTrashItem>> = _trash.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val saveRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private val saveMutex = Mutex()
    private var refreshJob: Job? = null
    private var mealCalendarRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            saveRequests.debounce(1_200).collect { saveNow() }
        }
        viewModelScope.launch {
            settings.map { it.diaryTreeUri }.distinctUntilChanged().collect {
                _expandedMonth.value = null
                if (it != null) refresh() else _listState.value = DiaryListState()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _listState.value = _listState.value.copy(loading = true, error = null)
            runCatching { repository.scan(settings.value) }
                .onSuccess { items ->
                    _listState.value = DiaryListState(items = items)
                    if (_expandedMonth.value !in items.map(DiaryDocument::monthKey)) {
                        _expandedMonth.value = null
                    }
                }
                .onFailure { _listState.value = DiaryListState(error = it.userMessage()) }
        }
    }

    fun refreshMealCalendar() {
        mealCalendarRefreshJob?.cancel()
        mealCalendarRefreshJob = viewModelScope.launch {
            _mealCalendarState.value = _mealCalendarState.value.copy(loading = true, error = null)
            try {
                val items = repository.scanMealCalendar(settings.value)
                _mealCalendarState.value = MealCalendarState(items = items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _mealCalendarState.value = _mealCalendarState.value.copy(
                    loading = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun toggleExpandedMonth(month: String) {
        _expandedMonth.value = if (_expandedMonth.value == month) null else month
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
        _mealCalendarState.value = _mealCalendarState.value.copy(error = null)
    }

    fun importImage(uri: Uri, category: String?) {
        viewModelScope.launch {
            runCatching { repository.importImage(uri, category, settings.value) }
                .onSuccess { media ->
                    val state = _editorState.value
                    val lineBreak = if (state.content.isEmpty() || state.content.endsWith('\n') || state.content.endsWith('\r')) {
                        ""
                    } else {
                        DiaryTextUtils.preferredLineEnding(state.content)
                    }
                    onContentChanged(state.content + lineBreak + media.markdown)
                }
                .onFailure { _editorState.value = _editorState.value.copy(error = it.userMessage()) }
        }
    }

    fun updateImageCaption(fullMarkdown: String, newCaption: String) {
        val state = _editorState.value
        val replacement = fullMarkdown.replaceFirst(Regex("!\\[[^]]*]"), "![${newCaption.replace("]", "") }]")
        onContentChanged(state.content.replaceFirst(fullMarkdown, replacement))
    }

    fun moveSourceLine(fromIndex: Int, toIndex: Int) {
        val source = _editorState.value.content
        onContentChanged(DiaryTextUtils.moveSourceLine(source, fromIndex, toIndex))
    }

    suspend fun resolveMedia(target: String): Uri? = repository.resolveMedia(target, settings.value)

    fun rename(uri: String, fileName: String) {
        viewModelScope.launch {
            runCatching { repository.rename(uri, fileName, settings.value) }
                .onSuccess { renamed ->
                    val editor = _editorState.value
                    if (editor.document?.uri == uri) {
                        _editorState.value = editor.copy(
                            document = renamed.copy(content = editor.content),
                        )
                    }
                    _message.value = localized(
                        "已重命名为 ${renamed.name}",
                        "Renamed to ${renamed.name}",
                    )
                    refresh()
                }
                .onFailure { _message.value = it.userMessage() }
        }
    }

    fun delete(uri: String) {
        viewModelScope.launch {
            runCatching {
                require(repository.delete(uri, settings.value)) { localized("无法移入回收站", "Could not move diary to trash") }
            }
                .onSuccess {
                    _message.value = localized("已移入日记回收站", "Moved to diary trash")
                    refresh()
                    refreshTrash()
                }
                .onFailure { _message.value = it.userMessage() }
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
            runCatching {
                require(repository.restore(uri, settings.value)) { localized("无法恢复日记", "Could not restore diary") }
            }
                .onSuccess {
                    _message.value = localized("日记已恢复", "Diary restored")
                    refresh()
                    refreshTrash()
                }
                .onFailure { _message.value = it.userMessage() }
        }
    }

    fun permanentlyDeleteTrash(uri: String) {
        viewModelScope.launch {
            runCatching {
                require(repository.permanentlyDelete(uri)) { localized("无法永久删除", "Could not permanently delete diary") }
            }
                .onSuccess {
                    _message.value = localized("已永久删除", "Permanently deleted")
                    refreshTrash()
                }
                .onFailure { _message.value = it.userMessage() }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun localized(chinese: String, english: String): String =
        if (settings.value.appLanguage == AppLanguage.ENGLISH) english else chinese

    private fun Throwable.userMessage(): String = message ?: "操作失败，请检查目录授权"
}
