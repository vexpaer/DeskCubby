package com.deskcubby.app.ui.home

import android.net.Uri
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.repository.DailyPoem
import com.deskcubby.app.data.repository.DateRecordRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.CalorieEstimationRepository
import com.deskcubby.app.data.repository.PoetryRepository
import com.deskcubby.app.data.repository.PoetryBookRepository
import com.deskcubby.app.data.repository.ThoughtRepository
import com.deskcubby.app.data.sync.AppCloudSyncStatus
import com.deskcubby.app.data.sync.CloudSyncRunMode
import com.deskcubby.app.data.sync.CloudSyncManualQueueState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    diaryIndexDao: DiaryIndexDao,
    private val thoughtRepository: ThoughtRepository,
    dateRecordRepository: DateRecordRepository,
    private val poetryRepository: PoetryRepository,
    private val poetryBookRepository: PoetryBookRepository,
    private val diaryFileRepository: DiaryFileRepository,
    private val calorieRepository: CalorieEstimationRepository,
) : ViewModel() {
    val diaries: StateFlow<List<DiaryIndexEntity>> = diaryIndexDao.observeAll().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val thoughts: StateFlow<List<FlashThoughtEntity>> = thoughtRepository.recent.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val thoughtCategories: StateFlow<List<ThoughtCategoryEntity>> = thoughtRepository.categories.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val dateRecords: StateFlow<List<DateRecordEntity>> = dateRecordRepository.records.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val poem: StateFlow<DailyPoem> = poetryRepository.poem.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        PoetryRepository.FALLBACK,
    )
    private val _poemRefreshing = MutableStateFlow(false)
    val poemRefreshing: StateFlow<Boolean> = _poemRefreshing.asStateFlow()
    private val _mealUploadInProgress = MutableStateFlow(false)
    val mealUploadInProgress: StateFlow<Boolean> = _mealUploadInProgress.asStateFlow()
    private val mealUploadMutex = Mutex()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _dailyRecordInProgress = MutableStateFlow<Set<String>>(emptySet())
    val dailyRecordInProgress: StateFlow<Set<String>> = _dailyRecordInProgress.asStateFlow()
    private val _cloudSyncActionState = MutableStateFlow(
        HomeCloudSyncActionState(queuedMode = CloudSyncManualQueueState.queuedMode(context)),
    )
    val cloudSyncActionState: StateFlow<HomeCloudSyncActionState> =
        _cloudSyncActionState.asStateFlow()

    init {
        viewModelScope.launch {
            CloudSyncManualQueueState.reconcile(context)
            CloudSyncManualQueueState.queuedModeFlow.collect { queuedMode ->
                _cloudSyncActionState.value = applyPersistentHomeCloudQueue(
                    _cloudSyncActionState.value,
                    queuedMode,
                )
            }
        }
    }

    fun refreshPoem(language: AppLanguage, force: Boolean = true) {
        if (_poemRefreshing.value) return
        _poemRefreshing.value = true
        viewModelScope.launch {
            try {
                poetryRepository.refresh(force)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _message.value = if (language == AppLanguage.ENGLISH) {
                    "Could not refresh the poem; the current poem was kept"
                } else {
                    "诗词刷新失败，已保留当前内容"
                }
            } finally {
                _poemRefreshing.value = false
            }
        }
    }

    fun savePoem(poem: DailyPoem, language: AppLanguage) {
        viewModelScope.launch {
            try {
                // Prefer the whole origin poem when the API provided it.
                poetryBookRepository.create(poem.fullContent.ifBlank { poem.content }, poem.source)
                _message.value = if (language == AppLanguage.ENGLISH) {
                    "Added to poetry book"
                } else {
                    "已加入诗词本"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.message ?: if (language == AppLanguage.ENGLISH) {
                    "Could not save the poem"
                } else {
                    "诗词保存失败"
                }
            }
        }
    }

    fun addThought(content: String, categoryId: Long?, onDone: (Boolean) -> Unit = {}) {
        val snapshot = content.trim()
        if (snapshot.isBlank()) {
            onDone(false)
            return
        }
        viewModelScope.launch {
            try {
                thoughtRepository.create(snapshot, categoryId)
                onDone(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDone(false)
            }
        }
    }

    fun addMealPhoto(
        uri: Uri,
        category: String,
        settings: AppSettings,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch {
            var sourceReleased = false
            fun releaseSource() {
                if (!sourceReleased) {
                    sourceReleased = true
                    runCatching(onDone)
                }
            }
            try {
                mealUploadMutex.withLock {
                    try {
                        _mealUploadInProgress.value = true
                        val media = diaryFileRepository.appendImageToToday(uri, category, settings)
                        // The camera source can be removed as soon as both durable writes finish;
                        // a later index scan must not keep the temporary photo alive.
                        releaseSource()
                        _message.value = if (settings.appLanguage == AppLanguage.ENGLISH) {
                            "$category photo added to today's diary"
                        } else {
                            "$category 图片已加入今日日记"
                        }
                        if (settings.calorieEstimationEnabled) {
                            try {
                                val estimate = calorieRepository.estimate(media.documentUri, settings)
                                diaryFileRepository.setMealPhotoEstimate(
                                    media.fileName,
                                    estimate,
                                    settings,
                                )
                                _message.value = "${_message.value} · ${estimate.energyKj}kJ"
                            } catch (error: Exception) {
                                _message.value = "${_message.value} · 热量估算失败：${error.message.orEmpty()}"
                            }
                        }
                        // The image and Markdown are already durable at this point. Index refresh
                        // is best-effort so a scan failure never encourages a duplicate retry.
                        try {
                            diaryFileRepository.scan(settings)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The next normal scan will refresh the index.
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _message.value = error.message ?: if (settings.appLanguage == AppLanguage.ENGLISH) {
                            "Could not add the photo"
                        } else {
                            "图片添加失败"
                        }
                    } finally {
                        _mealUploadInProgress.value = false
                    }
                }
            } finally {
                releaseSource()
            }
        }
    }

    fun addDailyRecordToToday(
        templateId: String,
        entry: String,
        settings: AppSettings,
        onDone: (Boolean) -> Unit = {},
    ) {
        if (templateId in _dailyRecordInProgress.value || entry.isBlank()) return
        _dailyRecordInProgress.value += templateId
        viewModelScope.launch {
            var success = false
            try {
                diaryFileRepository.appendTextToToday(entry, settings)
                success = true
                _message.value = if (settings.appLanguage == AppLanguage.ENGLISH) {
                    "Added to today's diary: $entry"
                } else {
                    "已添加到今日日记：$entry"
                }
                try {
                    diaryFileRepository.scan(settings)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The durable Markdown write succeeded; a later scan can refresh the index.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = error.message ?: if (settings.appLanguage == AppLanguage.ENGLISH) {
                    "Could not add the daily record"
                } else {
                    "日常记录添加失败"
                }
            } finally {
                _dailyRecordInProgress.value -= templateId
                onDone(success)
            }
        }
    }

    fun showMessage(message: String) {
        _message.value = message
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun recordCloudSyncEnqueue(mode: CloudSyncRunMode, accepted: Boolean) {
        _cloudSyncActionState.value = applyPersistentHomeCloudQueue(
            reduceHomeCloudSyncEnqueue(mode, accepted),
            CloudSyncManualQueueState.queuedModeFlow.value,
        )
    }

    fun reconcileCloudSyncStatus(status: AppCloudSyncStatus) {
        _cloudSyncActionState.value = reconcileHomeCloudSyncActionState(
            _cloudSyncActionState.value,
            status,
        )
    }
}

data class HomeCloudSyncActionState(
    val queuedMode: CloudSyncRunMode? = null,
    val enqueueFailed: Boolean = false,
)

internal fun reduceHomeCloudSyncEnqueue(
    mode: CloudSyncRunMode,
    accepted: Boolean,
): HomeCloudSyncActionState = if (accepted) {
    HomeCloudSyncActionState(queuedMode = mode)
} else {
    HomeCloudSyncActionState(enqueueFailed = true)
}

internal fun reconcileHomeCloudSyncActionState(
    current: HomeCloudSyncActionState,
    status: AppCloudSyncStatus,
): HomeCloudSyncActionState = if (
    status.running || status.message != null || status.error != null
) {
    current.copy(enqueueFailed = false)
} else {
    current
}

internal fun applyPersistentHomeCloudQueue(
    current: HomeCloudSyncActionState,
    queuedMode: CloudSyncRunMode?,
): HomeCloudSyncActionState = current.copy(
    queuedMode = queuedMode,
    enqueueFailed = if (queuedMode != null) false else current.enqueueFailed,
)
