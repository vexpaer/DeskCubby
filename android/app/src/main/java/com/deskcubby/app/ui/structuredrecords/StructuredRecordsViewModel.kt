package com.deskcubby.app.ui.structuredrecords

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.structuredrecords.JournalDayEngine
import com.deskcubby.app.data.structuredrecords.PhoneInteractionEstimator
import com.deskcubby.app.data.structuredrecords.StructuredField
import com.deskcubby.app.data.structuredrecords.StructuredRecordSegment
import com.deskcubby.app.data.structuredrecords.StructuredRecordTemplate
import com.deskcubby.app.data.structuredrecords.StructuredRecordsRepository
import com.deskcubby.app.data.structuredrecords.SystemFieldSnapshot
import com.deskcubby.app.data.structuredrecords.StructuredWorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    private val mutableFields = MutableStateFlow<List<StructuredField>>(emptyList())
    val fields: StateFlow<List<StructuredField>> = mutableFields.asStateFlow()

    private val mutableTemplates = MutableStateFlow<List<StructuredRecordTemplate>>(emptyList())
    val templates: StateFlow<List<StructuredRecordTemplate>> = mutableTemplates.asStateFlow()

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
            settings.map { it.diaryTreeUri }.distinctUntilChanged().collect { uri ->
                if (uri != null) {
                    refreshWorkspace()
                    refreshSystemSnapshot()
                } else {
                    mutableFields.value = emptyList()
                    mutableTemplates.value = emptyList()
                    mutableSystemSnapshot.value = null
                    mutableJournalDay.value = null
                }
            }
        }
    }

    private suspend fun refreshWorkspace() {
        val appSettings = settings.value
        if (appSettings.diaryTreeUri == null) return
        workspaceRepository.seedExamples(appSettings)
        workspaceRepository.ensureSystemFields(appSettings)
        mutableFields.value = workspaceRepository.loadFields(appSettings)
        mutableTemplates.value = workspaceRepository.loadTemplates(appSettings)
    }

    /** Re-reads workspace fields/templates after a change elsewhere (settings screen etc.). */
    fun refreshWorkspaceFromUi() {
        viewModelScope.launch { refreshWorkspace() }
    }

    fun refreshSystemSnapshot() {
        viewModelScope.launch { refreshSystemSnapshot(settings.value) }
    }

    private suspend fun refreshSystemSnapshot(appSettings: AppSettings) {
        mutableNow.value = LocalTime.now()
        if (appSettings.diaryTreeUri == null) {
            mutableSystemSnapshot.value = null
            return
        }
        val workspace = workspaceRepository.loadSettings(appSettings)
        val boundary = JournalDayEngine.parseBoundary(workspace.dayBoundary)
        val today = JournalDayEngine.resolveJournalDay(Instant.now(), boundary)
        mutableJournalDay.value = today
        val estimate = if (appSettings.structuredAutoRecordSleepWake) {
            // queryEvents replays the day's interaction history synchronously; keep it off Main.
            withContext(Dispatchers.IO) { phoneInteractionEstimator.estimateForJournalDay(today, boundary) }
        } else null
        mutableSystemSnapshot.value = SystemFieldSnapshot(
            autoRecording = appSettings.structuredAutoRecordSleepWake,
            usageAccessGranted = phoneInteractionEstimator.hasUsageAccess(),
            wakeTime = estimate?.wakeTime,
            sleepTime = estimate?.sleepTime,
        )
    }

    fun touchNow() {
        mutableNow.value = LocalTime.now()
    }

    /** Records one template with typed values (in field-segment order). */
    fun record(template: StructuredRecordTemplate, values: List<String>) {
        if (template.id in mutableSending.value) return
        mutableSending.value += template.id
        viewModelScope.launch {
            val appSettings = settings.value
            if (appSettings.diaryTreeUri == null) {
                showFeedback(
                    "请先在设置中选择日记目录",
                    "Choose a diary folder in settings first",
                    isError = true,
                )
            } else {
                val result = recordsRepository.insertRecordFromTemplate(appSettings, template, values)
                if (result.success) {
                    showFeedback(
                        "已记录到 ${result.journalDay}",
                        "Recorded on ${result.journalDay}",
                        isError = false,
                        recordedTemplateId = template.id,
                    )
                    refreshSystemSnapshot(appSettings)
                } else {
                    showFeedback(result.message ?: "记录失败", result.message ?: "Failed", isError = true)
                }
            }
            mutableSending.value -= template.id
        }
    }

    /** Creates a template from a plain text, or text + one field. */
    fun addTemplate(name: String, field: StructuredField?, prefix: String?) {
        viewModelScope.launch {
            val appSettings = settings.value
            if (appSettings.diaryTreeUri == null) {
                showFeedback("请先选择日记目录", "Choose a diary folder first", isError = true)
                return@launch
            }
            val currentFields = workspaceRepository.loadFields(appSettings)
            val segments = buildList {
                prefix?.takeIf(String::isNotBlank)?.let { add(StructuredRecordSegment.Text(it)) }
                field?.let {
                    if (currentFields.none { f -> f.id == it.id }) {
                        workspaceRepository.saveFields(appSettings, currentFields + it)
                    }
                    add(StructuredRecordSegment.Field(it.id))
                }
            }
            if (segments.isEmpty()) {
                showFeedback("请添加字段", "Add a field first", isError = true)
                return@launch
            }
            val templates = workspaceRepository.loadTemplates(appSettings)
            val template = StructuredRecordTemplate(
                id = "r_" + UUID.randomUUID().toString().take(8),
                name = name.ifBlank { field?.name ?: "记录" },
                segments = segments,
                sortOrder = templates.size,
            )
            workspaceRepository.saveTemplates(appSettings, templates + template)
            refreshWorkspace()
        }
    }

    fun updateTemplate(template: StructuredRecordTemplate) {
        viewModelScope.launch {
            val appSettings = settings.value
            val templates = workspaceRepository.loadTemplates(appSettings)
            workspaceRepository.saveTemplates(
                appSettings,
                templates.map { if (it.id == template.id) template else it },
            )
            refreshWorkspace()
        }
    }

    fun removeTemplate(id: String) {
        viewModelScope.launch {
            val appSettings = settings.value
            val templates = workspaceRepository.loadTemplates(appSettings)
            workspaceRepository.saveTemplates(appSettings, templates.filterNot { it.id == id })
            refreshWorkspace()
        }
    }

    fun rebuildIndex(onDone: (Boolean, String) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val appSettings = settings.value
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
            } catch (error: Exception) {
                onDone(false, error.message ?: "重建失败")
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
