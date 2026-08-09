package com.deskcubby.app.ui.diary

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AiModelType
import com.deskcubby.app.data.model.DiaryDocument
import com.deskcubby.app.data.model.DiaryEditorDocument
import com.deskcubby.app.data.model.DiaryTrashItem
import com.deskcubby.app.data.model.MealCategory
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.CalorieEstimationRepository
import com.deskcubby.app.data.repository.DiaryPreviewMedia
import com.deskcubby.app.data.repository.DiaryTextUtils
import com.deskcubby.app.data.repository.ExternalFileConflictException
import com.deskcubby.app.data.repository.MealCalorieModelUpdate
import com.deskcubby.app.data.repository.MealImageRecognition
import com.deskcubby.app.data.repository.MealCalendarDay
import com.deskcubby.app.data.repository.MealCalendarPhoto
import com.deskcubby.app.data.repository.MealDayDetails
import com.deskcubby.app.data.repository.MealEnergyEstimate
import com.deskcubby.app.data.repository.MAX_MEAL_ENERGY_KJ
import com.deskcubby.app.data.repository.MAX_MEAL_NOTE_CHARS
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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

private data class CalorieEstimationWorkPhoto(
    val photo: MealCalendarPhoto,
)

private data class RecognizedCaloriePhoto(
    val fileName: String,
    val recognition: MealImageRecognition,
)

private data class CalorieEstimationWork(
    val id: Long,
    val dateIso: String,
    val photos: List<CalorieEstimationWorkPhoto>,
    val dayPhotoCount: Int,
    val force: Boolean,
    val noteOverride: String?,
    val fallbackNote: String,
    val settings: AppSettings,
    val clearManualTotalOnSave: Boolean,
)

private data class MealCalendarSource(
    val diaryTreeUri: String?,
    val mediaTreeUri: String?,
    val contentRevision: Long,
)

private enum class CalorieEnqueueResult {
    ADDED,
    UPGRADED,
    DUPLICATE,
}

private const val MAX_PROGRESS_PHOTO_LABEL_CHARS = 160
private const val MAX_MODEL_TRACE_TEXT_CHARS = 32_000
private const val MAX_PARALLEL_MEAL_IMAGE_RECOGNITIONS = 3

internal suspend fun <T, R> mapConcurrentOrdered(
    items: List<T>,
    maxConcurrency: Int,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }
    val semaphore = Semaphore(minOf(maxConcurrency, items.size).coerceAtLeast(1))
    items.map { item ->
        async { semaphore.withPermit { transform(item) } }
    }.awaitAll()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repository: DiaryFileRepository,
    private val calorieRepository: CalorieEstimationRepository,
    private val settingsRepository: SettingsRepository,
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

    private val _mealCalendarExporting = MutableStateFlow(false)
    val mealCalendarExporting: StateFlow<Boolean> = _mealCalendarExporting.asStateFlow()

    private val _calorieEstimationQueueState = MutableStateFlow(CalorieEstimationQueueState())
    val calorieEstimationQueueState: StateFlow<CalorieEstimationQueueState> =
        _calorieEstimationQueueState.asStateFlow()

    private val _expandedMonth = MutableStateFlow<String?>(null)
    val expandedMonth: StateFlow<String?> = _expandedMonth.asStateFlow()

    private val _editorState = MutableStateFlow(EditorState())
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    private val _trash = MutableStateFlow<List<DiaryTrashItem>>(emptyList())
    val trash: StateFlow<List<DiaryTrashItem>> = _trash.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _defaultFolderSetupInProgress = MutableStateFlow(false)
    val defaultFolderSetupInProgress: StateFlow<Boolean> =
        _defaultFolderSetupInProgress.asStateFlow()

    private val saveRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private val saveMutex = Mutex()
    private val calorieEnqueueMutex = Mutex()
    private val calorieWorkQueue = mutableListOf<CalorieEstimationWork>()
    private var refreshJob: Job? = null
    private var mealCalendarRefreshJob: Job? = null
    private var loadedMealCalendarSource: MealCalendarSource? = null
    private var mealCalendarDirty: Boolean = true
    private var calorieQueueJob: Job? = null
    private var activeCalorieWork: CalorieEstimationWork? = null
    private var nextCalorieWorkId = 1L

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

    fun initializeDefaultFolders(selectedTreeUri: Uri) {
        if (_defaultFolderSetupInProgress.value) return
        _defaultFolderSetupInProgress.value = true
        viewModelScope.launch {
            try {
                val current = settings.value
                val initialized = repository.initializeDefaultFolders(
                    selectedTreeUri = selectedTreeUri,
                    savedTreeUris = listOf(current.diaryTreeUri, current.mediaTreeUri),
                )
                settingsRepository.setDefaultDiaryFolders(
                    grantTreeUri = initialized.grantTreeUri.toString(),
                    diaryTreeUri = current.diaryTreeUri ?: initialized.diaryTreeUri.toString(),
                    mediaTreeUri = current.mediaTreeUri ?: initialized.mediaTreeUri.toString(),
                )
                _message.value = localized(
                    "默认日记与媒体目录已设置",
                    "Default diary and media folders are ready",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = localized(
                    "无法设置默认目录。请在系统选择器中确认本机 Documents（文档）目录，并允许读写。",
                    "Could not set up the default folders. Confirm the local Documents folder in the system picker and allow read/write access.",
                )
            } finally {
                _defaultFolderSetupInProgress.value = false
            }
        }
    }

    fun loadMealCalendarIfNeeded() {
        val source = mealCalendarSource(settings.value)
        if (!mealCalendarDirty && loadedMealCalendarSource == source) return
        refreshMealCalendar(force = false, source = source)
    }

    fun forceRefreshMealCalendar() {
        refreshMealCalendar(force = true, source = mealCalendarSource(settings.value))
    }

    private fun refreshMealCalendar(force: Boolean, source: MealCalendarSource) {
        mealCalendarRefreshJob?.cancel()
        mealCalendarRefreshJob = viewModelScope.launch {
            _mealCalendarState.value = if (loadedMealCalendarSource == source) {
                _mealCalendarState.value.copy(loading = true, error = null)
            } else {
                MealCalendarState(loading = true)
            }
            try {
                val items = repository.scanMealCalendar(settings.value, forceRefresh = force)
                loadedMealCalendarSource = source
                mealCalendarDirty = mealCalendarSource(settings.value) != source
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

    private fun markMealCalendarDirty() {
        mealCalendarDirty = true
    }

    private fun acceptIncrementalMealCalendarMutation(
        previousSource: MealCalendarSource,
        cacheWasCurrent: Boolean,
    ) {
        val currentSource = mealCalendarSource(settings.value)
        val isOnlyExpectedMutation =
            currentSource.diaryTreeUri == previousSource.diaryTreeUri &&
                currentSource.mediaTreeUri == previousSource.mediaTreeUri &&
                currentSource.contentRevision == previousSource.contentRevision + 1
        if (cacheWasCurrent && isOnlyExpectedMutation) {
            loadedMealCalendarSource = currentSource
            mealCalendarDirty = false
        } else {
            markMealCalendarDirty()
        }
    }

    private fun mealCalendarSource(settings: AppSettings) = MealCalendarSource(
        diaryTreeUri = settings.diaryTreeUri,
        mediaTreeUri = settings.mediaTreeUri,
        contentRevision = repository.currentMealCalendarContentRevision(),
    )

    fun calculateUncalculatedCalories(
        dateIso: String? = null,
        force: Boolean = false,
        noteOverride: String? = null,
        photoFileName: String? = null,
    ) {
        if (!settings.value.calorieEstimationEnabled) return
        viewModelScope.launch {
            calorieEnqueueMutex.withLock {
                try {
                    val currentSettings = settings.value
                    if (!currentSettings.calorieEstimationEnabled) return@withLock
                    calorieConfigurationError(currentSettings)?.let { error ->
                        _message.value = error
                        return@withLock
                    }
                    val currentSource = mealCalendarSource(currentSettings)
                    val currentItems = _mealCalendarState.value.items.takeIf {
                        !mealCalendarDirty && loadedMealCalendarSource == currentSource
                    } ?: repository.scanMealCalendar(currentSettings).also { scanned ->
                        loadedMealCalendarSource = currentSource
                        mealCalendarDirty = mealCalendarSource(settings.value) != currentSource
                        _mealCalendarState.value = _mealCalendarState.value.copy(
                            loading = false,
                            items = scanned,
                            error = null,
                        )
                    }
                    val normalizedOverride = noteOverride
                        ?.trim()
                        ?.take(MAX_MEAL_NOTE_CHARS)
                    val seenPhotoKeys = if (dateIso == null) mutableSetOf<String>() else null
                    val prepared = currentItems
                        .filter { dateIso == null || it.dateIso == dateIso }
                        .mapNotNull { day ->
                            val seenInDay = seenPhotoKeys ?: mutableSetOf()
                            val selectedPhotos = day.photos.mapNotNull { photo ->
                                if (photoFileName != null &&
                                    !photo.fileName.equals(photoFileName, ignoreCase = true)
                                ) {
                                    return@mapNotNull null
                                }
                                if (!force && photo.energyKj != null) return@mapNotNull null
                                val key = photo.fileName
                                    .lowercase(Locale.ROOT)
                                    .ifBlank { photo.uri.toString() }
                                if (!seenInDay.add(key)) {
                                    return@mapNotNull null
                                }
                                CalorieEstimationWorkPhoto(
                                    photo = photo,
                                )
                            }
                            if (selectedPhotos.isEmpty()) return@mapNotNull null
                            CalorieEstimationWork(
                                id = nextCalorieWorkId++,
                                dateIso = day.dateIso,
                                photos = selectedPhotos,
                                dayPhotoCount = day.photos.size,
                                force = force,
                                noteOverride = if (day.dateIso == dateIso) {
                                    normalizedOverride
                                } else {
                                    null
                                },
                                fallbackNote = day.details.note,
                                settings = currentSettings,
                                clearManualTotalOnSave = force && photoFileName == null,
                            )
                        }

                    var changedCount = 0
                    prepared.forEach { work ->
                        when (enqueueCalorieWork(work)) {
                            CalorieEnqueueResult.ADDED,
                            CalorieEnqueueResult.UPGRADED -> changedCount += 1
                            CalorieEnqueueResult.DUPLICATE -> Unit
                        }
                    }
                    if (changedCount == 0) {
                        _message.value = if (prepared.isEmpty()) {
                            localized(
                                "没有需要计算的饮食图片",
                                "No meal photos need calculation",
                            )
                        } else {
                            localized(
                                "所选日期已在热量估算队列中",
                                "The selected date is already in the calorie queue",
                            )
                        }
                    } else {
                        _message.value = localized(
                            "已将 $changedCount 天加入热量估算队列",
                            "Added $changedCount day(s) to the calorie queue",
                        )
                        ensureCalorieQueueRunner()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _message.value = error.userMessage()
                }
            }
        }
    }

    fun clearFinishedCalorieEstimationProgress() {
        _calorieEstimationQueueState.update { current ->
            CalorieEstimationQueueState(
                items = current.items.filterNot { it.isTerminal },
            )
        }
    }

    private fun enqueueCalorieWork(work: CalorieEstimationWork): CalorieEnqueueResult {
        if (activeCalorieWork == null && calorieWorkQueue.isEmpty() &&
            !_calorieEstimationQueueState.value.isRunning
        ) {
            _calorieEstimationQueueState.value = CalorieEstimationQueueState()
        }
        val queuedIndex = calorieWorkQueue.indexOfFirst { it.dateIso == work.dateIso }
        if (queuedIndex >= 0) {
            val queued = calorieWorkQueue[queuedIndex]
            if (work.force && !queued.force) {
                val upgraded = work.copy(id = queued.id)
                calorieWorkQueue[queuedIndex] = upgraded
                replaceCalorieProgress(queued.id) { progressFor(upgraded) }
                return CalorieEnqueueResult.UPGRADED
            }
            return CalorieEnqueueResult.DUPLICATE
        }

        val active = activeCalorieWork
        if (active?.dateIso == work.dateIso) {
            return CalorieEnqueueResult.DUPLICATE
        }
        calorieWorkQueue += work
        _calorieEstimationQueueState.update { current ->
            CalorieEstimationQueueState(items = current.items + progressFor(work))
        }
        return CalorieEnqueueResult.ADDED
    }

    private fun progressFor(work: CalorieEstimationWork) = CalorieEstimationDayProgress(
        id = work.id,
        dateIso = work.dateIso,
        selectedPhotoCount = work.photos.size,
        dayPhotoCount = work.dayPhotoCount,
        forceRecalculation = work.force,
    )

    private fun ensureCalorieQueueRunner() {
        if (calorieQueueJob?.isActive == true) return
        calorieQueueJob = viewModelScope.launch {
            var processedDays = 0
            var failedDays = 0
            try {
                while (calorieWorkQueue.isNotEmpty()) {
                    val work = calorieWorkQueue.removeAt(0)
                    activeCalorieWork = work
                    processedDays += 1
                    if (!processCalorieWork(work)) failedDays += 1
                    activeCalorieWork = null
                }
                if (processedDays > 0) {
                    _message.value = if (failedDays == 0) {
                        localized(
                            "热量估算队列已完成，共 $processedDays 天",
                            "Calorie queue finished for $processedDays day(s)",
                        )
                    } else {
                        localized(
                            "热量估算队列已处理完毕：$failedDays 天失败，可在进度页查看",
                            "Calorie queue finished with $failedDays failed day(s); see progress for details",
                        )
                    }
                }
            } finally {
                activeCalorieWork = null
                calorieQueueJob = null
            }
        }
    }

    private suspend fun recognizeCaloriePhotos(
        work: CalorieEstimationWork,
        executionSettings: AppSettings,
    ): List<RecognizedCaloriePhoto> {
        val indexedPhotos = work.photos.mapIndexed { index, workPhoto -> index to workPhoto }
        return mapConcurrentOrdered(
            items = indexedPhotos,
            maxConcurrency = MAX_PARALLEL_MEAL_IMAGE_RECOGNITIONS,
        ) { (index, workPhoto) ->
            requireCalorieDirectoriesUnchanged(work.settings, settings.value)
            val photo = workPhoto.photo
            val fileName = photo.fileName.takeIf(String::isNotBlank)
                ?: error("无法确定图片文件名，热量未记录")
            val selectedPhotoIndex = index + 1
            val photoLabel = listOf(photo.caption.trim(), photo.fileName.trim())
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" · ")
                .ifBlank { localized("饮食图片", "Meal photo") }
                .take(MAX_PROGRESS_PHOTO_LABEL_CHARS)
            replaceCalorieProgress(work.id) {
                it.copy(activePhotoCount = it.activePhotoCount + 1)
            }
            try {
                val recognition = calorieRepository.recognizeImage(
                    imageUri = photo.uri.toString(),
                    settings = executionSettings,
                    onModelUpdate = { update ->
                        updateCalorieModelTrace(
                            workId = work.id,
                            selectedPhotoIndex = selectedPhotoIndex,
                            photoLabel = photoLabel,
                            update = update,
                        )
                    },
                )
                replaceCalorieProgress(work.id) {
                    it.copy(completedPhotoCount = it.completedPhotoCount + 1)
                }
                RecognizedCaloriePhoto(fileName, recognition)
            } finally {
                finishCalorieModelTrace(work.id, selectedPhotoIndex)
                replaceCalorieProgress(work.id) {
                    it.copy(activePhotoCount = (it.activePhotoCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    private suspend fun processCalorieWork(work: CalorieEstimationWork): Boolean {
        return try {
            val executionSettings = settings.value
            require(executionSettings.calorieEstimationEnabled) {
                localized("热量估算已在设置中关闭", "Calorie estimation was disabled in settings")
            }
            requireCalorieDirectoriesUnchanged(work.settings, executionSettings)
            calorieConfigurationError(executionSettings)?.let(::error)
            val calculationNote = work.noteOverride
                ?: _mealCalendarState.value.items
                    .firstOrNull { it.dateIso == work.dateIso }
                    ?.details
                    ?.note
                ?: work.fallbackNote
            replaceCalorieProgress(work.id) {
                it.copy(
                    status = CalorieEstimationQueueStatus.IMAGE_RECOGNITION,
                    completedPhotoCount = 0,
                    activePhotoCount = 0,
                    currentPhotoLabel = null,
                    error = null,
                )
            }
            val recognizedPhotos = recognizeCaloriePhotos(work, executionSettings)
            requireCalorieDirectoriesUnchanged(work.settings, settings.value)
            replaceCalorieProgress(work.id) {
                it.copy(
                    status = CalorieEstimationQueueStatus.TEXT_ESTIMATION,
                    completedPhotoCount = work.photos.size,
                    activePhotoCount = 0,
                    currentPhotoLabel = localized("全日统一计算", "Unified day calculation"),
                )
            }
            val estimateList = try {
                calorieRepository.estimateRecognizedDay(
                    recognitions = recognizedPhotos.map(RecognizedCaloriePhoto::recognition),
                    settings = executionSettings,
                    note = calculationNote,
                    onModelUpdate = { update ->
                        updateCalorieModelTrace(
                            workId = work.id,
                            selectedPhotoIndex = 0,
                            photoLabel = localized("全日统一计算", "Unified day calculation"),
                            update = update,
                        )
                    },
                )
            } finally {
                finishCalorieModelTrace(work.id, selectedPhotoIndex = 0)
            }
            val estimates = linkedMapOf<String, MealEnergyEstimate>().apply {
                recognizedPhotos.zip(estimateList).forEach { (recognized, estimate) ->
                    put(recognized.fileName, estimate)
                }
            }

            replaceCalorieProgress(work.id) {
                it.copy(
                    status = CalorieEstimationQueueStatus.SAVING,
                    completedPhotoCount = work.photos.size,
                    activePhotoCount = 0,
                    currentPhotoLabel = null,
                )
            }
            // Do not let an older calendar scan publish stale metadata after this day's commit.
            mealCalendarRefreshJob?.cancelAndJoin()
            mealCalendarRefreshJob = null
            _mealCalendarState.value = _mealCalendarState.value.copy(loading = false)

            val detailsByDate = if (work.force) {
                val latestDetails = _mealCalendarState.value.items
                    .firstOrNull { it.dateIso == work.dateIso }
                    ?.details
                    ?: MealDayDetails(note = work.fallbackNote)
                mapOf(
                    work.dateIso to latestDetails.copy(
                        totalEnergyKjOverride = if (work.clearManualTotalOnSave) {
                            null
                        } else {
                            latestDetails.totalEnergyKjOverride
                        },
                        note = work.noteOverride ?: latestDetails.note,
                    ),
                )
            } else {
                emptyMap()
            }
            requireCalorieDirectoriesUnchanged(work.settings, settings.value)
            val sourceBeforeWrite = mealCalendarSource(executionSettings)
            val cacheWasCurrent = !mealCalendarDirty && loadedMealCalendarSource == sourceBeforeWrite
            repository.setMealEnergyResults(estimates, detailsByDate, executionSettings)
            applyCompletedCalorieWork(work.dateIso, estimates, detailsByDate[work.dateIso])
            acceptIncrementalMealCalendarMutation(sourceBeforeWrite, cacheWasCurrent)
            replaceCalorieProgress(work.id) {
                it.copy(
                    status = CalorieEstimationQueueStatus.COMPLETED,
                    completedPhotoCount = work.photos.size,
                    activePhotoCount = 0,
                    currentPhotoLabel = null,
                    error = null,
                )
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finishCalorieModelTrace(work.id)
            replaceCalorieProgress(work.id) {
                it.copy(
                    status = CalorieEstimationQueueStatus.FAILED,
                    failedAtStatus = it.status.takeIf { status ->
                        status != CalorieEstimationQueueStatus.QUEUED &&
                            !status.isTerminal
                    },
                    activePhotoCount = 0,
                    error = error.userMessage(),
                )
            }
            false
        }
    }

    private fun applyCompletedCalorieWork(
        dateIso: String,
        estimates: Map<String, MealEnergyEstimate>,
        details: MealDayDetails?,
    ) {
        val estimatesByKey = estimates.mapKeys { (fileName, _) ->
            fileName.lowercase(Locale.ROOT)
        }
        val current = _mealCalendarState.value
        _mealCalendarState.value = current.copy(
            loading = false,
            items = current.items.map { day ->
                day.copy(
                    photos = day.photos.map { photo ->
                        estimatesByKey[photo.fileName.lowercase(Locale.ROOT)]?.let { estimate ->
                            photo.copy(
                                energyKj = estimate.energyKj,
                                foods = estimate.foods,
                            )
                        } ?: photo
                    },
                    details = if (day.dateIso == dateIso) details ?: day.details else day.details,
                )
            },
        )
    }

    private fun replaceCalorieProgress(
        id: Long,
        transform: (CalorieEstimationDayProgress) -> CalorieEstimationDayProgress,
    ) {
        _calorieEstimationQueueState.update { current ->
            CalorieEstimationQueueState(
                items = current.items.map { item -> if (item.id == id) transform(item) else item },
            )
        }
    }

    private fun updateCalorieModelTrace(
        workId: Long,
        selectedPhotoIndex: Int,
        photoLabel: String,
        update: MealCalorieModelUpdate,
    ) {
        val now = SystemClock.elapsedRealtime()
        replaceCalorieProgress(workId) { progress ->
            val traceIndex = progress.modelTraces.indexOfLast { trace ->
                trace.isRunning && trace.stage == update.stage &&
                    trace.selectedPhotoIndex == selectedPhotoIndex
            }
            val traces = if (traceIndex >= 0) {
                progress.modelTraces.toMutableList().apply {
                    val current = get(traceIndex)
                    set(
                        traceIndex,
                        current.copy(
                            modelName = update.modelName.take(MAX_PROGRESS_PHOTO_LABEL_CHARS),
                            reasoning = update.completion.reasoning.take(MAX_MODEL_TRACE_TEXT_CHARS),
                            response = update.completion.content.take(MAX_MODEL_TRACE_TEXT_CHARS),
                        ),
                    )
                }
            } else {
                progress.modelTraces + CalorieModelTrace(
                    stage = update.stage,
                    modelName = update.modelName.take(MAX_PROGRESS_PHOTO_LABEL_CHARS),
                    selectedPhotoIndex = selectedPhotoIndex,
                    photoLabel = photoLabel,
                    startedAtElapsedRealtime = now,
                    reasoning = update.completion.reasoning.take(MAX_MODEL_TRACE_TEXT_CHARS),
                    response = update.completion.content.take(MAX_MODEL_TRACE_TEXT_CHARS),
                )
            }
            progress.copy(modelTraces = traces)
        }
    }

    private fun finishCalorieModelTrace(workId: Long, selectedPhotoIndex: Int? = null) {
        val now = SystemClock.elapsedRealtime()
        replaceCalorieProgress(workId) { progress ->
            val hasMatchingRunningTrace = progress.modelTraces.any { trace ->
                trace.isRunning &&
                    (selectedPhotoIndex == null || trace.selectedPhotoIndex == selectedPhotoIndex)
            }
            if (!hasMatchingRunningTrace) {
                progress
            } else {
                progress.copy(
                    modelTraces = progress.modelTraces.map { trace ->
                        if (trace.isRunning &&
                            (selectedPhotoIndex == null || trace.selectedPhotoIndex == selectedPhotoIndex)
                        ) {
                            trace.copy(finishedAtElapsedRealtime = now)
                        } else {
                            trace
                        }
                    },
                )
            }
        }
    }

    fun saveMealDayDetails(
        dateIso: String,
        totalEnergyKjOverride: Int?,
        note: String,
    ) {
        val initialState = _mealCalendarState.value
        if (initialState.loading) return
        if (_calorieEstimationQueueState.value.items.any {
                it.dateIso == dateIso && !it.isTerminal
            }
        ) {
            _message.value = localized(
                "该日期正在估算或排队，完成后再修改热量详情",
                "This date is being estimated or queued; edit its details after it finishes",
            )
            return
        }
        if (totalEnergyKjOverride != null && totalEnergyKjOverride !in 0..MAX_MEAL_ENERGY_KJ) {
            _mealCalendarState.value = initialState.copy(
                error = localized("总热量必须在 0–$MAX_MEAL_ENERGY_KJ kJ 之间", "Total energy is out of range"),
            )
            return
        }
        val normalizedDetails = MealDayDetails(
            totalEnergyKjOverride = totalEnergyKjOverride,
            note = note.trim().take(MAX_MEAL_NOTE_CHARS),
        )
        _mealCalendarState.value = initialState.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val executionSettings = settings.value
                val sourceBeforeWrite = mealCalendarSource(executionSettings)
                val cacheWasCurrent = !mealCalendarDirty && loadedMealCalendarSource == sourceBeforeWrite
                repository.setMealDayDetails(dateIso, normalizedDetails, executionSettings)
                val current = _mealCalendarState.value
                _mealCalendarState.value = current.copy(
                    loading = false,
                    error = null,
                    items = current.items.map { day ->
                        if (day.dateIso == dateIso) day.copy(details = normalizedDetails) else day
                    },
                )
                acceptIncrementalMealCalendarMutation(sourceBeforeWrite, cacheWasCurrent)
                _message.value = localized("热量详情已保存", "Energy details saved")
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

    private fun calorieConfigurationError(settings: AppSettings): String? {
        val hasImage = settings.aiConfigs.any {
            it.id == settings.calorieImageConfigId && it.type == AiModelType.IMAGE
        }
        if (!hasImage) {
            return localized(
                "请先在日记设置中选择图片识别模型",
                "Choose an image-recognition model in diary settings first",
            )
        }
        val hasText = settings.aiConfigs.any {
            it.id == settings.calorieTextConfigId && it.type == AiModelType.TEXT
        }
        return if (!hasText) {
            localized(
                "请先在日记设置中选择文字模型",
                "Choose a text model in diary settings first",
            )
        } else {
            null
        }
    }

    private fun requireCalorieDirectoriesUnchanged(
        queuedSettings: AppSettings,
        currentSettings: AppSettings,
    ) {
        require(
            queuedSettings.diaryTreeUri == currentSettings.diaryTreeUri &&
                queuedSettings.mediaTreeUri == currentSettings.mediaTreeUri,
        ) {
            localized(
                "日记或媒体目录已更改；为避免写入旧目录，本日期未保存，请重新加入队列",
                "The diary or media folder changed. This date was not saved to the old folder; queue it again",
            )
        }
    }

    fun exportMealCalendar(
        destinationUri: Uri,
        startInclusive: LocalDate,
        endInclusive: LocalDate,
        categories: Set<MealCategory>,
    ) {
        if (_mealCalendarExporting.value) return
        _mealCalendarExporting.value = true
        val selectedCategories = categories.toSet()
        viewModelScope.launch {
            try {
                val result = repository.exportMealCalendarPng(
                    destinationUri = destinationUri,
                    settings = settings.value,
                    startInclusive = startInclusive,
                    endInclusive = endInclusive,
                    categories = selectedCategories,
                )
                _message.value = localized(
                    "已导出 ${result.dayCount} 天、${result.photoCount} 张照片",
                    "Exported ${result.photoCount} photos across ${result.dayCount} days",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.userMessage()
            } finally {
                _mealCalendarExporting.value = false
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

    fun appendDailyRecordToCurrent(text: String, onDone: (Boolean) -> Unit = {}) {
        val state = _editorState.value
        val lineEnding = DiaryTextUtils.preferredLineEnding(state.content)
        val block = DiaryTextUtils.normalizeTextBlock(text, lineEnding)
        if (block.isBlank() || state.document == null || state.loading || state.conflict != null) {
            onDone(false)
            return
        }
        val separator = when {
            state.content.isEmpty() || state.content.endsWith('\n') || state.content.endsWith('\r') -> ""
            else -> lineEnding
        }
        onContentChanged(state.content + separator + block)
        viewModelScope.launch { onDone(saveCurrent()) }
    }

    fun saveNow(force: Boolean = false) {
        viewModelScope.launch { saveCurrent(force) }
    }

    private suspend fun saveCurrent(force: Boolean = false): Boolean = saveMutex.withLock {
        val snapshot = _editorState.value
        val doc = snapshot.document ?: return@withLock false
        if (!snapshot.dirty && !force) {
            return@withLock snapshot.conflict == null && doc.content == snapshot.content
        }
        if (snapshot.conflict != null && !force) return@withLock false
        _editorState.value = snapshot.copy(saving = true, error = null)
        try {
            val saved = repository.save(doc.uri, snapshot.content, doc.sha256, force)
            val changedDuringSave = _editorState.value.content != snapshot.content
            _editorState.value = _editorState.value.copy(
                document = saved,
                saving = false,
                dirty = changedDuringSave,
                conflict = null,
            )
            if (changedDuringSave) saveRequests.tryEmit(Unit)
            markMealCalendarDirty()
            refresh()
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is ExternalFileConflictException) {
                _editorState.value = _editorState.value.copy(saving = false, conflict = error.diskDocument)
            } else {
                _editorState.value = _editorState.value.copy(saving = false, error = error.userMessage())
            }
            false
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
                    if (category != null && settings.value.calorieEstimationEnabled) {
                        runCatching { calorieRepository.estimate(media.documentUri, settings.value) }
                            .onSuccess { estimate ->
                                repository.setMealPhotoEstimate(media.fileName, estimate, settings.value)
                            }
                            .onFailure { _editorState.value = _editorState.value.copy(error = it.userMessage()) }
                    }
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

    fun deleteMedia(markdownTarget: String) {
        viewModelScope.launch {
            saveMutex.withLock {
                val snapshot = _editorState.value
                val document = snapshot.document ?: return@withLock
                if (snapshot.loading || snapshot.conflict != null) return@withLock
                _editorState.value = snapshot.copy(saving = true, error = null)
                try {
                    val result = repository.deleteMediaAndReferences(
                        diaryUri = document.uri,
                        editorContent = snapshot.content,
                        expectedSha256 = document.sha256,
                        markdownTarget = markdownTarget,
                        settings = settings.value,
                    )
                    val latest = _editorState.value
                    val latestWithoutReferences = DiaryTextUtils.removeMediaReferences(
                        latest.content,
                        markdownTarget,
                    )
                    val changedDuringDelete = latestWithoutReferences != result.document.content
                    undoStack.clear()
                    redoStack.clear()
                    _editorState.value = latest.copy(
                        document = result.document,
                        content = latestWithoutReferences,
                        saving = false,
                        dirty = changedDuringDelete,
                        conflict = null,
                        error = null,
                    )
                    if (changedDuringDelete) saveRequests.tryEmit(Unit)
                    _message.value = if (result.mediaFileDeleted) {
                        localized(
                            "媒体文件及日记引用已删除",
                            "Media file and diary references deleted",
                        )
                    } else {
                        localized(
                            "媒体文件已不存在，日记引用已删除",
                            "The media file was already missing; diary references deleted",
                        )
                    }
                    markMealCalendarDirty()
                    refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ExternalFileConflictException) {
                    _editorState.value = _editorState.value.copy(
                        saving = false,
                        conflict = error.diskDocument,
                    )
                } catch (error: Exception) {
                    _editorState.value = _editorState.value.copy(
                        saving = false,
                        error = error.userMessage(),
                    )
                }
            }
        }
    }

    suspend fun resolveMedia(target: String): Uri? = repository.resolveMedia(target, settings.value)

    suspend fun resolveDiaryPreviewMedia(
        targets: Collection<String>,
    ): Map<String, DiaryPreviewMedia> = repository.resolveDiaryPreviewMedia(targets, settings.value)

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
                    markMealCalendarDirty()
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
                    markMealCalendarDirty()
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
                    markMealCalendarDirty()
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
