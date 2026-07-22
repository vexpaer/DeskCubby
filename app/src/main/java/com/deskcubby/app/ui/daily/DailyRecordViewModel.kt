package com.deskcubby.app.ui.daily

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A one-shot message emitted by [DailyRecordViewModel]. */
data class DailyRecordFeedback(
    val key: Long,
    val message: String,
    val isError: Boolean,
    val recordedTemplateId: String? = null,
)

@HiltViewModel
class DailyRecordViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val diaryFileRepository: DiaryFileRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    val templates: StateFlow<List<DailyEventTemplate>> = settings
        .map { currentSettings -> currentSettings.dailyEventTemplates }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings().dailyEventTemplates,
        )

    private val mutableSendingTemplateIds = MutableStateFlow<Set<String>>(emptySet())
    val sendingTemplateIds: StateFlow<Set<String>> = mutableSendingTemplateIds.asStateFlow()

    private val mutableTemplateOperationInProgress = MutableStateFlow(false)
    val templateOperationInProgress: StateFlow<Boolean> =
        mutableTemplateOperationInProgress.asStateFlow()

    private val mutableFeedback = MutableStateFlow<DailyRecordFeedback?>(null)
    val feedback: StateFlow<DailyRecordFeedback?> = mutableFeedback.asStateFlow()

    private var feedbackKey = 0L

    fun addTemplate(text: String, firstUnit: String, secondUnit: String) {
        val normalized = normalizedTemplate(
            id = UUID.randomUUID().toString(),
            text = text,
            firstUnit = firstUnit,
            secondUnit = secondUnit,
        ) ?: return showValidationError()

        runTemplateOperation(
            successChinese = "已添加日常事件",
            successEnglish = "Daily event added",
        ) {
            settingsRepository.addDailyEventTemplate(normalized)
        }
    }

    fun updateTemplate(id: String, text: String, firstUnit: String, secondUnit: String) {
        val normalized = normalizedTemplate(id, text, firstUnit, secondUnit)
            ?: return showValidationError()

        runTemplateOperation(
            successChinese = "已保存日常事件",
            successEnglish = "Daily event saved",
        ) {
            settingsRepository.updateDailyEventTemplate(normalized)
        }
    }

    fun removeTemplate(id: String) {
        runTemplateOperation(
            successChinese = "已删除日常事件",
            successEnglish = "Daily event deleted",
        ) {
            settingsRepository.removeDailyEventTemplate(id)
        }
    }

    /**
     * Appends a formatted event to today's diary. Duplicate taps for the same template are ignored
     * until the current durable write finishes.
     */
    fun record(template: DailyEventTemplate, sentence: String) {
        if (template.id in mutableSendingTemplateIds.value) return
        val entry = sentence.trim()
        if (entry.isBlank()) return showValidationError()

        mutableSendingTemplateIds.value += template.id
        viewModelScope.launch {
            try {
                val settingsSnapshot = settings.value
                diaryFileRepository.appendTextToToday(entry, settingsSnapshot)
                try {
                    diaryFileRepository.scan(settingsSnapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The Markdown write is already durable; a later scan can refresh the index.
                }
                showFeedback(
                    chinese = "已添加到今日日记：$entry",
                    english = "Added to today's diary: $entry",
                    isError = false,
                    recordedTemplateId = template.id,
                    language = settingsSnapshot.appLanguage,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFeedback(
                    chinese = error.message ?: "无法写入今日日记",
                    english = error.message ?: "Could not write to today's diary",
                    isError = true,
                )
            } finally {
                mutableSendingTemplateIds.value -= template.id
            }
        }
    }

    fun consumeFeedback(key: Long) {
        if (mutableFeedback.value?.key == key) mutableFeedback.value = null
    }

    private fun runTemplateOperation(
        successChinese: String,
        successEnglish: String,
        operation: suspend () -> Unit,
    ) {
        if (mutableTemplateOperationInProgress.value) return
        mutableTemplateOperationInProgress.value = true
        viewModelScope.launch {
            try {
                operation()
                showFeedback(successChinese, successEnglish, isError = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showFeedback(
                    chinese = error.message ?: "操作失败",
                    english = error.message ?: "Operation failed",
                    isError = true,
                )
            } finally {
                mutableTemplateOperationInProgress.value = false
            }
        }
    }

    private fun showValidationError() {
        showFeedback(
            chinese = "请输入日常事件名称",
            english = "Enter a daily event name",
            isError = true,
        )
    }

    private fun showFeedback(
        chinese: String,
        english: String,
        isError: Boolean,
        recordedTemplateId: String? = null,
        language: AppLanguage = settings.value.appLanguage,
    ) {
        feedbackKey += 1
        mutableFeedback.value = DailyRecordFeedback(
            key = feedbackKey,
            message = if (language == AppLanguage.ENGLISH) english else chinese,
            isError = isError,
            recordedTemplateId = recordedTemplateId,
        )
    }
}

private fun normalizedTemplate(
    id: String,
    text: String,
    firstUnit: String,
    secondUnit: String,
): DailyEventTemplate? {
    val normalizedText = text.trim().take(MAX_DAILY_EVENT_TEXT_LENGTH)
    if (normalizedText.isBlank()) return null
    val migratedPattern = buildString {
        append(normalizedText)
        firstUnit.trim().takeIf(String::isNotEmpty)?.let { append(" xx ").append(it) }
        secondUnit.trim().takeIf(String::isNotEmpty)?.let { append(" xx ").append(it) }
    }
    return DailyEventTemplate(id = id, text = migratedPattern)
}

const val MAX_DAILY_EVENT_TEXT_LENGTH = 100
