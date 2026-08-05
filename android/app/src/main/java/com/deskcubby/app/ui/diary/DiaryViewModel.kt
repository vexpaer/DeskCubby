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
import com.deskcubby.app.data.repository.MealCalorieEstimationStage
import com.deskcubby.app.data.repository.MealCalorieModelUpdate
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
import kotlinx.coroutines.cancelAndJoin
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

private data class CalorieEstimationWorkPhoto(
    val photo: MealCalendarPhoto,
    val positionInDay: Int,
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

private enum class CalorieEnqueueResult {
    ADDED,
    UPGRADED,
    DUPLICATE,
}

private const val MAX_PROGRESS_PHOTO_LABEL_CHARS = 160
private const val MAX_MODEL_TRACE_TEXT_CHARS = 32_000

@OptIn(FlowPreview::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repository: DiaryFileRepository,
    private val calorieRepository: CalorieEstimationRepository,
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

    fun refreshMealCalendar() {
        refreshMealCalendar(force = false)
    }

    fun forceRefreshMealCalendar() {
        refreshMealCalendar(force = true)
    }

    private fun refreshMealCalendar(force: Boolean) {
        mealCalendarRefreshJob?.cancel()
        mealCalendarRefreshJob = viewModelScope.launch {
            _mealCalendarState.value = _mealCalendarState.value.copy(loading = true, error = null)
            try {
                val items = repository.scanMealCalendar(settings.value, forceRefresh = force)
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
                    val currentItems = _mealCalendarState.value.items.takeIf { it.isNotEmpty() }
                        ?: repository.scanMealCalendar(currentSettings).also { scanned ->
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
                            val selectedPhotos = day.photos.mapIndexedNotNull { index, photo ->
                                if (photoFileName != null &&
                                    !photo.fileName.equals(photoFileName, ignoreCase = true)
                                ) {
                                    return@mapIndexedNotNull null
                                }
                                if (!force && photo.energyKj != null) return@mapIndexedNotNull null
                                val key = photo.fileName
                                    .lowercase(Locale.ROOT)
                                    .ifBlank { photo.uri.toString() }
                                if (!seenInDay.add(key)) {
                                    return@mapIndexedNotNull null
                                }
                                CalorieEstimationWorkPhoto(
                                    photo = photo,
                                    positionInDay = index + 1,
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
        _calorieEstimationQueueState.value = CalorieEstimationQueueState(
            items = _calorieEstimationQueueState.value.items.filterNot { it.isTerminal },
        )
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
        _calorieEstimationQueueState.value = CalorieEstimationQueueState(
            items = _calorieEstimationQueueState.value.items + progressFor(work),
        )
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

    private suspend fun processCalorieWork(work: CalorieEstimationWork): Boolean {
        return try {
            val executionSettings = settings.value
            require(executionSettings.calorieEstimationEnabled) {
                localized("热量估算已在设置中关闭", "Calorie estimation was disabled in settings")
            }
            requireCalorieDirectoriesUnchanged(work.settings, executionSettings)
            calorieConfigurationError(executionSettings)?.let(::error)
            val estimates = linkedMapOf<String, MealEnergyEstimate>()
            val calculationNote = work.noteOverride
                ?: _mealCalendarState.value.items
                    .firstOrNull { it.dateIso == work.dateIso }
                    ?.details
                    ?.note
                ?: work.fallbackNote
            work.photos.forEachIndexed { index, workPhoto ->
                requireCalorieDirectoriesUnchanged(work.settings, settings.value)
                val photo = workPhoto.photo
                val photoLabel = listOf(photo.caption.trim(), photo.fileName.trim())
                    .filter(String::isNotBlank)
                    .distinct()
                    .joinToString(" · ")
                    .ifBlank { localized("饮食图片", "Meal photo") }
                    .take(MAX_PROGRESS_PHOTO_LABEL_CHARS)
                replaceCalorieProgress(work.id) {
                    it.copy(
                        status = CalorieEstimationQueueStatus.IMAGE_RECOGNITION,
                        completedPhotoCount = index,
                        currentSelectedPhotoIndex = index + 1,
                        currentDayPhotoIndex = workPhoto.positionInDay,
                        currentPhotoLabel = photoLabel,
                        error = null,
                    )
                }
                val fileName = photo.fileName.takeIf(String::isNotBlank)
                    ?: error("无法确定图片文件名，热量未记录")
                estimates[fileName] = calorieRepository.estimate(
                    imageUri = photo.uri.toString(),
                    settings = executionSettings,
                    note = calculationNote,
                    onStageChanged = { stage ->
                        replaceCalorieProgress(work.id) {
                            it.copy(
                                status = when (stage) {
                                    MealCalorieEstimationStage.IMAGE_RECOGNITION ->
                                        CalorieEstimationQueueStatus.IMAGE_RECOGNITION
                                    MealCalorieEstimationStage.TEXT_ESTIMATION ->
                                        CalorieEstimationQueueStatus.TEXT_ESTIMATION
                                },
                            )
                        }
                    },
                    onModelUpdate = { update ->
                        updateCalorieModelTrace(work.id, update)
                    },
                )
                finishCalorieModelTrace(work.id)
                replaceCalorieProgress(work.id) {
                    it.copy(completedPhotoCount = index + 1)
                }
            }

            replaceCalorieProgress(work.id) {
                it.copy(
                    status = CalorieEstimationQueueStatus.SAVING,
                    completedPhotoCount = work.photos.size,
                    currentSelectedPhotoIndex = null,
                    currentDayPhotoIndex = null,
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
            repository.setMealEnergyResults(estimates, detailsByDate, executionSettings)
            applyCompletedCalorieWork(work.dateIso, estimates, detailsByDate[work.dateIso])
            replaceCalorieProgress(work.id) {
                it.copy(
                    status = CalorieEstimationQueueStatus.COMPLETED,
                    completedPhotoCount = work.photos.size,
                    currentSelectedPhotoIndex = null,
                    currentDayPhotoIndex = null,
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
        val current = _calorieEstimationQueueState.value
        _calorieEstimationQueueState.value = CalorieEstimationQueueState(
            items = current.items.map { item -> if (item.id == id) transform(item) else item },
        )
    }

    private fun updateCalorieModelTrace(
        workId: Long,
        update: MealCalorieModelUpdate,
    ) {
        val now = SystemClock.elapsedRealtime()
        replaceCalorieProgress(workId) { progress ->
            val selectedPhotoIndex = progress.currentSelectedPhotoIndex
                ?: return@replaceCalorieProgress progress
            val current = progress.modelTraces.lastOrNull()
            val sameRequest = current?.isRunning == true &&
                current.stage == update.stage &&
                current.selectedPhotoIndex == selectedPhotoIndex
            val traces = if (sameRequest) {
                progress.modelTraces.dropLast(1) + current.copy(
                    modelName = update.modelName.take(MAX_PROGRESS_PHOTO_LABEL_CHARS),
                    reasoning = update.completion.reasoning.take(MAX_MODEL_TRACE_TEXT_CHARS),
                    response = update.completion.content.take(MAX_MODEL_TRACE_TEXT_CHARS),
                )
            } else {
                progress.modelTraces.map { trace ->
                    if (trace.isRunning) trace.copy(finishedAtElapsedRealtime = now) else trace
                } + CalorieModelTrace(
                    stage = update.stage,
                    modelName = update.modelName.take(MAX_PROGRESS_PHOTO_LABEL_CHARS),
                    selectedPhotoIndex = selectedPhotoIndex,
                    photoLabel = progress.currentPhotoLabel.orEmpty(),
                    startedAtElapsedRealtime = now,
                    reasoning = update.completion.reasoning.take(MAX_MODEL_TRACE_TEXT_CHARS),
                    response = update.completion.content.take(MAX_MODEL_TRACE_TEXT_CHARS),
                )
            }
            progress.copy(modelTraces = traces)
        }
    }

    private fun finishCalorieModelTrace(workId: Long) {
        val now = SystemClock.elapsedRealtime()
        replaceCalorieProgress(workId) { progress ->
            if (progress.modelTraces.none(CalorieModelTrace::isRunning)) {
                progress
            } else {
                progress.copy(
                    modelTraces = progress.modelTraces.map { trace ->
                        if (trace.isRunning) trace.copy(finishedAtElapsedRealtime = now) else trace
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
                repository.setMealDayDetails(dateIso, normalizedDetails, settings.value)
                val current = _mealCalendarState.value
                _mealCalendarState.value = current.copy(
                    loading = false,
                    error = null,
                    items = current.items.map { day ->
                        if (day.dateIso == dateIso) day.copy(details = normalizedDetails) else day
                    },
                )
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
