package com.deskcubby.app.ui.structuredrecords

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.structuredrecords.PhoneInteractionEstimator
import com.deskcubby.app.data.structuredrecords.StructuredField
import com.deskcubby.app.data.structuredrecords.StructuredRecordDraft
import com.deskcubby.app.data.structuredrecords.StructuredRecordTemplate
import com.deskcubby.app.data.structuredrecords.StructuredRecordsRepository
import com.deskcubby.app.data.structuredrecords.StructuredWorkspaceRepository
import com.deskcubby.app.data.structuredrecords.SystemFieldSnapshot
import com.deskcubby.app.data.structuredrecords.structuredDraftToSegments
import com.deskcubby.app.data.structuredrecords.structuredDraftValues
import com.deskcubby.app.widget.DeskCubbyWidgetProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val UI_WORKSPACE_REFRESH_COALESCE_MS = 1_500L

data class StructuredRecordsFeedback(
    val key: Long,
    val message: String,
    val isError: Boolean,
    val recordedTemplateId: String? = null,
)

@HiltViewModel
class StructuredRecordsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: StructuredWorkspaceRepository,
    private val recordsRepository: StructuredRecordsRepository,
    private val phoneInteractionEstimator: PhoneInteractionEstimator,
    private val uiStore: StructuredRecordsUiStore,
    @ApplicationContext private val applicationContext: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    val fields: StateFlow<List<StructuredField>> = uiStore.fields.asStateFlow()
    val templates: StateFlow<List<StructuredRecordTemplate>> = uiStore.templates.asStateFlow()

    private val mutableSending = MutableStateFlow<Set<String>>(emptySet())
    val sendingTemplateIds: StateFlow<Set<String>> = mutableSending.asStateFlow()

    private val mutableFeedback = MutableStateFlow<StructuredRecordsFeedback?>(null)
    val feedback: StateFlow<StructuredRecordsFeedback?> = mutableFeedback.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableNow = MutableStateFlow(LocalTime.now())
    val now: StateFlow<LocalTime> = mutableNow.asStateFlow()

    private val mutableSystemSnapshot = MutableStateFlow<SystemFieldSnapshot?>(null)
    val systemSnapshot: StateFlow<SystemFieldSnapshot?> = mutableSystemSnapshot.asStateFlow()

    private val mutableJournalDay = MutableStateFlow<LocalDate?>(null)
    val journalDay: StateFlow<LocalDate?> = mutableJournalDay.asStateFlow()

    private var feedbackKey = 0L

    init {
        viewModelScope.launch {
            settingsRepository.settings.map { it.diaryTreeUri }.distinctUntilChanged().collect { uri ->
                val rootChanged = uiStore.mutationMutex.withLock {
                    val changed = !uiStore.rootInitialized || uiStore.rootUri != uri
                    if (changed) {
                        uiStore.rootInitialized = true
                        uiStore.rootUri = uri
                        uiStore.fields.value = emptyList()
                        uiStore.templates.value = emptyList()
                        uiStore.lastWorkspaceRefreshAtMillis = 0L
                        uiStore.lastAppliedWorkspaceRevision = -1L
                    }
                    changed
                }
                if (rootChanged) mutableSending.value = emptySet()
                mutableSystemSnapshot.value = null
                mutableJournalDay.value = null
                if (uri != null) {
                    val appSettings = settingsRepository.settings.first()
                    try {
                        uiStore.mutationMutex.withLock { refreshWorkspace(appSettings) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep the collector alive; a later workspace invalidation retries.
                    }
                    refreshSystemSnapshot(appSettings)
                }
            }
        }
        viewModelScope.launch {
            // StateFlow immediately replays its current revision. The diary-root collector above
            // already performs the initial load, so consuming that replay used to do the same SAF
            // workspace read twice on every ViewModel creation.
            workspaceRepository.workspaceChanges.drop(1).collect { revision ->
                val appSettings = settingsRepository.settings.first()
                if (appSettings.diaryTreeUri != null) {
                    try {
                        uiStore.mutationMutex.withLock {
                            // App-side template mutations update uiStore optimistically and then
                            // publish this same revision. Do not re-read the files we just wrote.
                            if (revision != uiStore.lastAppliedWorkspaceRevision) {
                                refreshWorkspace(appSettings)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // One transient provider failure must not cancel future invalidations.
                    }
                }
            }
        }
    }

    private suspend fun refreshWorkspace(appSettings: AppSettings) {
        val rootUri = appSettings.diaryTreeUri ?: return
        // ensureSystemFields already returns the canonical field list; loading fields.json again was
        // a redundant SAF round-trip on every page visit.
        val loadedFields = workspaceRepository.ensureSystemFields(appSettings)
        val loadedTemplates = workspaceRepository.loadTemplates(appSettings)
        if (uiStore.rootInitialized && uiStore.rootUri == rootUri) {
            uiStore.fields.value = loadedFields
            uiStore.templates.value = loadedTemplates
            uiStore.lastWorkspaceRefreshAtMillis = System.currentTimeMillis()
            uiStore.lastAppliedWorkspaceRevision = workspaceRepository.workspaceChanges.value
        }
        // Refreshing launcher widgets is best effort and should never extend page loading.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                DeskCubbyWidgetProvider.requestUpdate(applicationContext)
            } catch (_: Exception) {
                // Workspace persistence must not depend on launcher availability.
            }
        }
    }

    fun refreshWorkspaceFromUi() {
        viewModelScope.launch {
            val appSettings = settingsRepository.settings.first()
            try {
                uiStore.mutationMutex.withLock {
                    val rootUri = appSettings.diaryTreeUri ?: return@withLock
                    val elapsed = System.currentTimeMillis() - uiStore.lastWorkspaceRefreshAtMillis
                    val recentlyLoaded = uiStore.rootInitialized &&
                        uiStore.rootUri == rootUri &&
                        uiStore.lastWorkspaceRefreshAtMillis > 0L &&
                        elapsed in 0..UI_WORKSPACE_REFRESH_COALESCE_MS
                    if (!recentlyLoaded) refreshWorkspace(appSettings)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFeedback("无法读取结构化记录", "Could not load structured records", isError = true)
            }
        }
    }

    fun refreshSystemSnapshot() {
        viewModelScope.launch { refreshSystemSnapshot(settings.value) }
    }

    private suspend fun refreshSystemSnapshot(appSettings: AppSettings) {
        mutableNow.value = LocalTime.now()
        if (appSettings.diaryTreeUri == null) {
            mutableSystemSnapshot.value = null
            mutableJournalDay.value = null
            return
        }
        val today = LocalDate.now()
        mutableJournalDay.value = today
        val session = if (appSettings.structuredAutoRecordSleepWake) {
            withContext(Dispatchers.IO) { phoneInteractionEstimator.estimateForWakeDate(today) }
        } else null
        mutableSystemSnapshot.value = SystemFieldSnapshot(
            autoRecording = appSettings.structuredAutoRecordSleepWake,
            usageAccessGranted = phoneInteractionEstimator.hasUsageAccess(),
            wakeTime = session?.wakeLocalTime(),
            sleepTime = session?.sleepLocalTime(),
        )
    }

    fun touchNow() {
        mutableNow.value = LocalTime.now()
    }

    /**
     * Records to [targetDate] when launched from a diary. A null target intentionally means the
     * real local date, which is what Home/desktop quick-add uses.
     */
    fun record(
        template: StructuredRecordTemplate,
        draft: StructuredRecordDraft,
        targetDate: LocalDate? = null,
    ) {
        if (template.id in mutableSending.value) return
        mutableSending.value += template.id
        viewModelScope.launch {
            var refreshWidgetAfterWrite = false
            try {
                val appSettings = settingsRepository.settings.first()
                if (appSettings.diaryTreeUri == null) {
                    showFeedback(
                        "请先在设置中选择日记目录",
                        "Choose a diary folder in settings first",
                        isError = true,
                    )
                    return@launch
                }
                val values = structuredDraftValues(draft)
                if (values == null) {
                    showFeedback("请填写所有下划线字段", "Fill every underlined field", isError = true)
                    return@launch
                }
                val editedTemplate = template.copy(segments = structuredDraftToSegments(draft))
                val result = recordsRepository.insertRecordFromTemplate(
                    settings = appSettings,
                    template = editedTemplate,
                    values = values,
                    targetDate = targetDate,
                    // The screen already loaded exactly these field definitions from `.deskcubby`.
                    // Re-reading fields.json through SAF for every tap added visible spinner time.
                    knownFieldsById = uiStore.fields.value.associateBy { it.id },
                )
                if (result.success) {
                    showFeedback(
                        result.message ?: "已记录到 ${result.journalDay}",
                        result.messageEnglish ?: "Recorded on ${result.journalDay}",
                        isError = false,
                        recordedTemplateId = template.id,
                    )
                    refreshWidgetAfterWrite = true
                } else {
                    showFeedback(
                        result.message ?: "记录失败",
                        result.messageEnglish ?: "Could not record",
                        isError = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFeedback("记录失败", "Could not record", isError = true)
            } finally {
                mutableSending.value -= template.id
            }

            // Launcher/widget refresh is best effort and must not extend the send-button spinner.
            if (refreshWidgetAfterWrite) {
                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        DeskCubbyWidgetProvider.requestUpdate(applicationContext)
                    } catch (_: Exception) {
                        // The record and its derived index are already durable.
                    }
                }
            }
        }
    }

    fun addTemplate(name: String, draft: StructuredRecordDraft) {
        val segments = structuredDraftToSegments(draft)
        if (segments.isEmpty()) {
            showFeedback("请输入模板正文", "Enter template text", isError = true)
            return
        }
        val id = "r_" + UUID.randomUUID().toString().take(8)
        mutateTemplates { templates ->
            templates + StructuredRecordTemplate(
                id = id,
                name = name.ifBlank { "记录" },
                segments = segments,
                sortOrder = (templates.maxOfOrNull { it.sortOrder } ?: -1) + 1,
            )
        }
    }

    fun updateTemplate(template: StructuredRecordTemplate) {
        mutateTemplates { templates ->
            templates.map { current -> if (current.id == template.id) template else current }
        }
    }

    fun removeTemplate(id: String) {
        mutateTemplates { templates -> templates.filterNot { it.id == id } }
    }

    private fun mutateTemplates(
        transform: (List<StructuredRecordTemplate>) -> List<StructuredRecordTemplate>,
    ) {
        viewModelScope.launch {
            var persisted = false
            uiStore.mutationMutex.withLock {
                val appSettings = settingsRepository.settings.first()
                val rootUri = appSettings.diaryTreeUri
                if (rootUri == null || !uiStore.rootInitialized || uiStore.rootUri != rootUri) {
                    showFeedback("请先选择日记目录", "Choose a diary folder first", isError = true)
                    return@withLock
                }
                val before = uiStore.templates.value
                val optimistic = transform(before)
                if (optimistic == before) return@withLock
                uiStore.templates.value = optimistic

                try {
                    val canonical = workspaceRepository.mutateTemplates(appSettings, transform)
                    if (uiStore.rootUri == rootUri) {
                        uiStore.templates.value = canonical
                        uiStore.lastWorkspaceRefreshAtMillis = System.currentTimeMillis()
                        uiStore.lastAppliedWorkspaceRevision = workspaceRepository.workspaceChanges.value
                    }
                    persisted = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (uiStore.rootUri == rootUri) {
                        uiStore.templates.value = try {
                            workspaceRepository.loadTemplatesReadOnly(appSettings)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            before
                        }
                    }
                    showFeedback(
                        "结构化记录保存失败",
                        "Could not save structured records",
                        isError = true,
                    )
                }
            }
            if (persisted) {
                try {
                    DeskCubbyWidgetProvider.requestUpdate(applicationContext)
                } catch (_: Exception) {
                    // The durable workspace write already succeeded; widget refresh is best effort.
                }
            }
        }
    }

    fun rebuildIndex(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val appSettings = settingsRepository.settings.first()
            if (appSettings.diaryTreeUri == null) {
                onDone(false, "请先选择日记目录")
                return@launch
            }
            mutableBusy.value = true
            try {
                recordsRepository.rebuildIndex(appSettings)
                onDone(true, "重建完成")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onDone(false, "重建失败")
            } finally {
                mutableBusy.value = false
            }
        }
    }

    fun consumeFeedback(key: Long) {
        if (mutableFeedback.value?.key == key) mutableFeedback.value = null
    }

    private fun showFeedback(
        chinese: String,
        english: String,
        isError: Boolean,
        recordedTemplateId: String? = null,
    ) {
        feedbackKey += 1
        val language = settings.value.appLanguage
        mutableFeedback.value = StructuredRecordsFeedback(
            key = feedbackKey,
            message = if (language == AppLanguage.ENGLISH) english else chinese,
            isError = isError,
            recordedTemplateId = recordedTemplateId,
        )
    }
}
