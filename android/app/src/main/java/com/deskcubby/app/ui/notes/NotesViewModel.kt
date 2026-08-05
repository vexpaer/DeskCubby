package com.deskcubby.app.ui.notes

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.NoteDocument
import com.deskcubby.app.data.repository.NoteEntry
import com.deskcubby.app.data.repository.NoteExternalConflictException
import com.deskcubby.app.data.repository.NoteFolderLocation
import com.deskcubby.app.data.repository.NoteFolderSnapshot
import com.deskcubby.app.data.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotesBrowserState(
    val loading: Boolean = false,
    val snapshot: NoteFolderSnapshot? = null,
    val breadcrumbs: List<NoteFolderLocation> = emptyList(),
    val error: String? = null,
    val mutating: Boolean = false,
)

data class NoteEditorState(
    val loading: Boolean = false,
    val document: NoteDocument? = null,
    val content: String = "",
    val preview: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val conflict: NoteDocument? = null,
    val error: String? = null,
)

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    private val _browserState = MutableStateFlow(NotesBrowserState())
    val browserState: StateFlow<NotesBrowserState> = _browserState.asStateFlow()
    private val _editorState = MutableStateFlow(NoteEditorState())
    val editorState: StateFlow<NoteEditorState> = _editorState.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val saveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val saveMutex = Mutex()
    private var rootLoadJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings
                .map { it.notesTreeUri }
                .distinctUntilChanged()
                .collectLatest { rootUri ->
                    if (rootUri == null) {
                        _browserState.value = NotesBrowserState()
                    } else {
                        loadRoot(rootUri)
                    }
                }
        }
        @Suppress("OPT_IN_USAGE")
        viewModelScope.launch {
            saveRequests.debounce(900L).collectLatest { saveCurrent() }
        }
    }

    fun selectRoot(uri: Uri) {
        rootLoadJob?.cancel()
        rootLoadJob = viewModelScope.launch {
            try {
                repository.persistTreePermission(uri)
                settingsRepository.setNotesTreeUri(uri.toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _browserState.value = _browserState.value.copy(error = error.userMessage())
            }
        }
    }

    fun refresh() {
        val root = settings.value.notesTreeUri ?: return
        val current = _browserState.value
        val location = current.snapshot?.location
        viewModelScope.launch {
            loadLocation(root, location, current.breadcrumbs)
        }
    }

    fun openFolder(entry: NoteEntry) {
        if (!entry.isFolder) return
        val root = settings.value.notesTreeUri ?: return
        val current = _browserState.value.snapshot?.location ?: return
        val relative = listOf(current.relativePath, entry.name)
            .filter(String::isNotBlank)
            .joinToString("/")
        val location = NoteFolderLocation(entry.uri, entry.name, relative)
        val breadcrumbs = _browserState.value.breadcrumbs + location
        viewModelScope.launch { loadLocation(root, location, breadcrumbs) }
    }

    fun openBreadcrumb(index: Int) {
        val root = settings.value.notesTreeUri ?: return
        val breadcrumbs = _browserState.value.breadcrumbs
        val location = breadcrumbs.getOrNull(index) ?: return
        viewModelScope.launch {
            loadLocation(root, location, breadcrumbs.take(index + 1))
        }
    }

    fun createFolder(name: String) = mutateBrowser {
        val root = requireNotNull(settings.value.notesTreeUri)
        val location = requireNotNull(_browserState.value.snapshot?.location)
        repository.createFolder(root, location, name)
        localized("文件夹已创建", "Folder created")
    }

    fun createNote(name: String, onOpened: () -> Unit) {
        val root = settings.value.notesTreeUri ?: return
        val location = _browserState.value.snapshot?.location ?: return
        if (_browserState.value.mutating) return
        _browserState.value = _browserState.value.copy(mutating = true, error = null)
        viewModelScope.launch {
            try {
                val document = repository.createNote(root, location, name)
                _editorState.value = NoteEditorState(document = document, content = document.content)
                reloadCurrentFolder(root)
                onOpened()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _browserState.value = _browserState.value.copy(error = error.userMessage())
            } finally {
                _browserState.value = _browserState.value.copy(mutating = false)
            }
        }
    }

    fun rename(entry: NoteEntry, name: String) = mutateBrowser {
        val root = requireNotNull(settings.value.notesTreeUri)
        repository.renameEntry(root, entry, name)
        localized("已重命名", "Renamed")
    }

    fun delete(entry: NoteEntry) = mutateBrowser {
        val root = requireNotNull(settings.value.notesTreeUri)
        repository.deleteEntry(root, entry)
        localized("已删除", "Deleted")
    }

    fun openNote(entry: NoteEntry, onOpened: () -> Unit) {
        val root = settings.value.notesTreeUri ?: return
        val folder = _browserState.value.snapshot?.location ?: return
        viewModelScope.launch {
            _editorState.value = NoteEditorState(loading = true)
            try {
                val document = repository.load(root, entry, folder.relativePath)
                _editorState.value = NoteEditorState(document = document, content = document.content)
                onOpened()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _editorState.value = NoteEditorState(error = error.userMessage())
            }
        }
    }

    fun onContentChanged(value: String) {
        if (_editorState.value.content == value) return
        _editorState.value = _editorState.value.copy(
            content = value,
            dirty = true,
            error = null,
        )
        saveRequests.tryEmit(Unit)
    }

    fun togglePreview() {
        _editorState.value = _editorState.value.copy(preview = !_editorState.value.preview)
    }

    fun saveNow(force: Boolean = false) {
        viewModelScope.launch { saveCurrent(force) }
    }

    fun reloadConflict() {
        val disk = _editorState.value.conflict ?: return
        _editorState.value = NoteEditorState(document = disk, content = disk.content)
    }

    fun saveConflictCopy() {
        val root = settings.value.notesTreeUri ?: return
        val state = _editorState.value
        val document = state.document ?: return
        viewModelScope.launch {
            _editorState.value = state.copy(saving = true, error = null)
            try {
                val copy = repository.saveConflictCopy(root, document, state.content)
                _editorState.value = NoteEditorState(document = copy, content = copy.content)
                _message.value = localized("已另存冲突副本", "Conflict copy saved")
                reloadCurrentFolder(root)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _editorState.value = _editorState.value.copy(
                    saving = false,
                    error = error.userMessage(),
                )
            }
        }
    }

    fun importMedia(sourceUri: Uri, destinationTreeUri: Uri) {
        val root = settings.value.notesTreeUri ?: return
        val snapshot = _editorState.value
        val document = snapshot.document ?: return
        viewModelScope.launch {
            _editorState.value = _editorState.value.copy(saving = true, error = null)
            try {
                val media = repository.importMedia(root, sourceUri, destinationTreeUri, document)
                val current = _editorState.value
                check(current.document?.uri == document.uri) {
                    localized(
                        "媒体已复制，但当前笔记已经切换，未插入链接",
                        "Media was copied, but the active note changed, so no link was inserted",
                    )
                }
                val currentContent = current.content
                val lineEnding = if (currentContent.contains("\r\n")) "\r\n" else "\n"
                val separator = if (
                    currentContent.isEmpty() || currentContent.endsWith('\n') ||
                    currentContent.endsWith('\r')
                ) "" else lineEnding
                val caption = media.fileName.substringBeforeLast('.').replace(']', '_')
                val target = media.markdownTarget.replace(">", "%3E")
                onContentChanged(
                    currentContent + separator + "![$caption](<$target>)" + lineEnding,
                )
                _message.value = localized(
                    "媒体已复制到所选笔记库位置",
                    "Media copied to the selected vault location",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _editorState.value = _editorState.value.copy(error = error.userMessage())
            } finally {
                _editorState.value = _editorState.value.copy(saving = false)
            }
        }
    }

    suspend fun resolvePreviewMedia(targets: Collection<String>): Map<String, Uri> {
        val root = settings.value.notesTreeUri ?: return emptyMap()
        val folderPath = _editorState.value.document?.folderRelativePath.orEmpty()
        return repository.resolveMediaTargets(root, folderPath, targets)
    }

    fun dismissError() {
        _browserState.value = _browserState.value.copy(error = null)
        _editorState.value = _editorState.value.copy(error = null)
    }

    fun consumeMessage() {
        _message.value = null
    }

    private suspend fun saveCurrent(force: Boolean = false): Boolean = saveMutex.withLock {
        val root = settings.value.notesTreeUri ?: return@withLock false
        val snapshot = _editorState.value
        val document = snapshot.document ?: return@withLock false
        if (!snapshot.dirty && !force) return@withLock snapshot.conflict == null
        if (snapshot.conflict != null && !force) return@withLock false
        _editorState.value = snapshot.copy(saving = true, error = null)
        return@withLock try {
            val saved = repository.save(root, document, snapshot.content, force)
            val changedDuringSave = _editorState.value.content != snapshot.content
            _editorState.value = _editorState.value.copy(
                document = saved,
                saving = false,
                dirty = changedDuringSave,
                conflict = null,
            )
            if (changedDuringSave) saveRequests.tryEmit(Unit)
            reloadCurrentFolder(root)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _editorState.value = if (error is NoteExternalConflictException) {
                _editorState.value.copy(saving = false, conflict = error.diskDocument)
            } else {
                _editorState.value.copy(saving = false, error = error.userMessage())
            }
            false
        }
    }

    private fun mutateBrowser(block: suspend () -> String) {
        if (_browserState.value.mutating) return
        _browserState.value = _browserState.value.copy(mutating = true, error = null)
        viewModelScope.launch {
            try {
                val message = block()
                settings.value.notesTreeUri?.let { reloadCurrentFolder(it) }
                _message.value = message
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _browserState.value = _browserState.value.copy(error = error.userMessage())
            } finally {
                _browserState.value = _browserState.value.copy(mutating = false)
            }
        }
    }

    private suspend fun loadRoot(root: String) {
        _browserState.value = NotesBrowserState(loading = true)
        try {
            val snapshot = repository.scanRoot(root)
            _browserState.value = NotesBrowserState(
                snapshot = snapshot,
                breadcrumbs = listOf(snapshot.location),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _browserState.value = NotesBrowserState(error = error.userMessage())
        }
    }

    private suspend fun loadLocation(
        root: String,
        location: NoteFolderLocation?,
        breadcrumbs: List<NoteFolderLocation>,
    ) {
        if (location == null) return loadRoot(root)
        _browserState.value = _browserState.value.copy(loading = true, error = null)
        try {
            val snapshot = repository.scanFolder(root, location)
            _browserState.value = NotesBrowserState(
                snapshot = snapshot,
                breadcrumbs = breadcrumbs,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _browserState.value = _browserState.value.copy(
                loading = false,
                error = error.userMessage(),
            )
        }
    }

    private suspend fun reloadCurrentFolder(root: String) {
        val state = _browserState.value
        loadLocation(root, state.snapshot?.location, state.breadcrumbs)
    }

    private fun localized(chinese: String, english: String): String =
        if (settings.value.appLanguage.name == "ENGLISH") english else chinese

    private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank)
        ?: localized("操作失败", "Operation failed")
}
