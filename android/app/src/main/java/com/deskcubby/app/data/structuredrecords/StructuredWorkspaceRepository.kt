package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.repository.DiaryFileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

/**
 * Loads and persists the `.deskcubby` workspace files inside the diary root. These files are the
 * "how to interpret the Markdown" semantics and must travel with the diary folder across devices.
 * The first launch seeds the five default examples only when the workspace is empty; existing user
 * templates are never duplicated.
 */
@Singleton
class StructuredWorkspaceRepository @Inject constructor(
    private val diaryFileRepository: DiaryFileRepository,
) {
    private val settingsCache = SettingsCache()

    /** Loads (creating on first use) the workspace [StructuredWorkspaceSettings]. */
    suspend fun loadSettings(appSettings: AppSettings): StructuredWorkspaceSettings {
        settingsCache.current?.let { return it }
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_SETTINGS)
        val decoded = if (raw == null) {
            seedSettings(appSettings)
        } else {
            StructuredRecordsCodec.decodeSettings(raw)
        }
        settingsCache.current = decoded
        return decoded
    }

    /**
     * The unified "what is today" resolver. Every page that needs the current day for journaling
     * must call this instead of comparing calendar midnights.
     */
    suspend fun resolveJournalDay(appSettings: AppSettings, now: Instant = Instant.now()): LocalDate {
        val workspace = loadSettings(appSettings)
        return JournalDayEngine.resolveJournalDay(
            now,
            JournalDayEngine.parseBoundary(workspace.dayBoundary),
        )
    }

    /** Effective `HH:mm` boundary for [journalDay] (honors boundary history). */
    suspend fun effectiveDayBoundary(appSettings: AppSettings, journalDay: LocalDate): String =
        loadSettings(appSettings).effectiveDayBoundary(journalDay)

    /** Re-reads settings from disk, ignoring the in-memory cache (e.g. after a rebuild). */
    suspend fun reloadSettings(appSettings: AppSettings): StructuredWorkspaceSettings {
        settingsCache.current = null
        return loadSettings(appSettings)
    }

    suspend fun saveSettings(appSettings: AppSettings, value: StructuredWorkspaceSettings) {
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_SETTINGS,
            StructuredRecordsCodec.encodeSettings(value),
        )
        settingsCache.current = value
    }

    /**
     * Applies a new day boundary. Boundary changes take effect from the next Journal Day (the
     * boundary itself changes, but the change is recorded in the history so old records keep their
     * original effective boundary when restoring real date-times).
     */
    suspend fun setDayBoundary(
        appSettings: AppSettings,
        newBoundary: String,
        journalDay: LocalDate,
    ): StructuredWorkspaceSettings {
        val current = loadSettings(appSettings)
        val normalized = JournalDayEngine.parseBoundary(newBoundary)
            ?.let(JournalDayEngine::formatBoundary)
            ?: JournalDayEngine.DEFAULT_DAY_BOUNDARY
        // The current journal day is already in progress; record the change as effective from the
        // next one so today's earlier instants are not retroactively reinterpreted under the new
        // boundary (the settings copy promises "从下一个日记日开始生效").
        val effectiveFrom = journalDay.plusDays(1)
        val history = current.dayBoundaryHistory
            .filterNot { it.effectiveFromJournalDay == effectiveFrom.toString() } +
            DayBoundaryRecord(effectiveFromJournalDay = effectiveFrom.toString(), value = normalized)
        val updated = current.copy(
            dayBoundary = normalized,
            dayBoundaryHistory = history.sortedBy { it.effectiveFromJournalDay },
        )
        saveSettings(appSettings, updated)
        return updated
    }

    /** Loads the field definitions, seeding the five defaults when none exist yet. */
    suspend fun loadFields(appSettings: AppSettings): List<StructuredField> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_FIELDS)
        val decoded = if (raw == null) emptyList() else StructuredRecordsCodec.decodeFields(raw)
        return if (decoded.isEmpty()) {
            seedFields(appSettings)
        } else {
            decoded.sortedBy { it.sortOrder }
        }
    }

    suspend fun saveFields(appSettings: AppSettings, fields: List<StructuredField>) {
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_FIELDS,
            StructuredRecordsCodec.encodeFields(fields),
        )
    }

    /** Loads record templates, seeding defaults or migrating legacy daily events when none exist. */
    suspend fun loadTemplates(appSettings: AppSettings): List<StructuredRecordTemplate> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_RECORDS)
        val decoded = if (raw == null) emptyList() else StructuredRecordsCodec.decodeTemplates(raw)
        return if (decoded.isEmpty()) {
            val migrated = migrateLegacyDailyEvents(appSettings)
            if (migrated.isEmpty()) seedTemplates(appSettings) else {
                saveTemplates(appSettings, migrated)
                migrated
            }
        } else {
            decoded.sortedBy { it.sortOrder }
        }
    }

    suspend fun saveTemplates(appSettings: AppSettings, templates: List<StructuredRecordTemplate>) {
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_RECORDS,
            StructuredRecordsCodec.encodeTemplates(templates),
        )
    }

    suspend fun loadMetrics(appSettings: AppSettings): List<StructuredMetric> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_STATISTICS)
        val decoded = if (raw == null) emptyList() else StructuredRecordsCodec.decodeMetrics(raw)
        return decoded.sortedBy { it.sortOrder }
    }

    suspend fun saveMetrics(appSettings: AppSettings, metrics: List<StructuredMetric>) {
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_STATISTICS,
            StructuredRecordsCodec.encodeMetrics(metrics),
        )
    }

    /** Adds the default example fields, only when the workspace has no fields at all. */
    suspend fun seedExamples(appSettings: AppSettings) {
        if (loadFields(appSettings).isEmpty()) {
            seedFields(appSettings)
        }
        if (loadTemplates(appSettings).isEmpty()) {
            seedTemplates(appSettings)
        }
    }

    /**
     * Ensures the system-sourced sleep/wake fields exist (used when the auto recorder is enabled).
     * Never duplicates; leaves user-defined fields untouched.
     */
    suspend fun ensureSystemFields(appSettings: AppSettings): List<StructuredField> {
        val fields = loadFields(appSettings)
        val existingIds = fields.mapTo(HashSet(fields.size)) { it.id }
        val systemFields = listOf(
            StructuredField(
                id = SYSTEM_FIELD_SLEEP_TIME,
                name = "睡觉时间",
                type = StructuredFieldType.TIME,
                source = StructuredFieldSource.SYSTEM,
                collector = COLLECTOR_LAST_PHONE_LOCK,
                sortOrder = fields.size,
            ),
            StructuredField(
                id = SYSTEM_FIELD_WAKE_TIME,
                name = "起床时间",
                type = StructuredFieldType.TIME,
                source = StructuredFieldSource.SYSTEM,
                collector = COLLECTOR_FIRST_PHONE_UNLOCK,
                sortOrder = fields.size + 1,
            ),
        )
        val toAdd = systemFields.filter { it.id !in existingIds }
        if (toAdd.isEmpty()) return fields
        val merged = (fields + toAdd).sortedBy { it.sortOrder }
        saveFields(appSettings, merged)
        return merged
    }

    private suspend fun seedSettings(appSettings: AppSettings): StructuredWorkspaceSettings {
        val today = LocalDate.now()
        val seeded = StructuredWorkspaceSettings(
            schemaVersion = 1,
            markdownProtocolVersion = 1,
            dayBoundary = JournalDayEngine.DEFAULT_DAY_BOUNDARY,
            dayBoundaryHistory = listOf(
                DayBoundaryRecord(
                    effectiveFromJournalDay = today.toString(),
                    value = JournalDayEngine.DEFAULT_DAY_BOUNDARY,
                ),
            ),
        )
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_SETTINGS,
            StructuredRecordsCodec.encodeSettings(seeded),
        )
        return seeded
    }

    private suspend fun seedFields(appSettings: AppSettings): List<StructuredField> {
        val fields = DefaultStructuredExamples.FIELDS
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_FIELDS,
            StructuredRecordsCodec.encodeFields(fields),
        )
        return fields
    }

    private suspend fun seedTemplates(appSettings: AppSettings): List<StructuredRecordTemplate> {
        val templates = DefaultStructuredExamples.TEMPLATES
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_RECORDS,
            StructuredRecordsCodec.encodeTemplates(templates),
        )
        return templates
    }

    /**
     * Migrates the legacy "日常事件" templates (with `xx` placeholders) into structured record
     * templates. Each `xx` becomes a `word` field segment; templates without any placeholder stay
     * plain text. Old Markdown history is never aggressively rewritten — only the reusable
     * templates are upgraded, and the migrated fields are freely renameable afterwards.
     */
    private suspend fun migrateLegacyDailyEvents(
        appSettings: AppSettings,
    ): List<StructuredRecordTemplate> {
        val legacy = appSettings.dailyEventTemplates
        if (legacy.isEmpty()) return emptyList()
        val fields = loadFields(appSettings).toMutableList()
        val existingFieldIds = fields.mapTo(HashSet(fields.size)) { it.id }
        val templates = ArrayList<StructuredRecordTemplate>(legacy.size)
        var wordIndex = 0
        val xxPattern = Regex("xx", RegexOption.IGNORE_CASE)
        for (event in legacy) {
            val text = event.text.trim().take(StructuredRecordsCodec.MAX_TEXT_CHARS)
            if (text.isBlank()) continue
            val templateId = "r_migrated_${stableId(event.id)}"
            val segments = ArrayList<StructuredRecordSegment>()
            if (!xxPattern.containsMatchIn(text)) {
                segments += StructuredRecordSegment.Text(text)
            } else {
                val fieldId = "f_migrated_word_${wordIndex++}_${stableId(event.id)}"
                if (fieldId !in existingFieldIds) {
                    fields += StructuredField(
                        id = fieldId,
                        name = "文字",
                        type = StructuredFieldType.WORD,
                        source = StructuredFieldSource.MANUAL,
                        sortOrder = fields.size,
                    )
                    existingFieldIds += fieldId
                }
                val parts = xxPattern.split(text)
                for (index in parts.indices) {
                    if (parts[index].isNotEmpty()) {
                        segments += StructuredRecordSegment.Text(parts[index])
                    }
                    if (index < parts.size - 1) {
                        segments += StructuredRecordSegment.Field(fieldId)
                    }
                }
            }
            if (segments.isNotEmpty()) {
                templates += StructuredRecordTemplate(
                    id = templateId,
                    name = text.take(StructuredRecordsCodec.MAX_NAME_CHARS),
                    segments = segments,
                    sortOrder = templates.size,
                )
            }
        }
        if (templates.isEmpty()) return emptyList()
        saveFields(appSettings, fields)
        return templates
    }

    private fun stableId(value: String): String {
        val hash = value.hashCode()
        var result: Long = (hash.toLong() and 0xffffffffL)
        result = result * 31 + value.length
        return result.toString(36).take(10)
    }

    /** Small in-memory cache so stats queries do not re-read the workspace file every frame. */
    private class SettingsCache {
        var current: StructuredWorkspaceSettings? = null
    }
}
