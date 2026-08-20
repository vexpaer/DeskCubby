package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.TodayDiarySwitchTimeStore
import com.deskcubby.app.data.repository.DiaryFileRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads and persists the `.deskcubby` workspace files inside the diary root. These files contain the
 * structured-record protocol, fields, templates and metrics. Calendar ownership is always the
 * natural local date; the device-local “今日日记切换时间” never changes structured data ownership.
 */
@Singleton
class StructuredWorkspaceRepository @Inject constructor(
    private val diaryFileRepository: DiaryFileRepository,
    private val todayDiarySwitchTimeStore: TodayDiarySwitchTimeStore,
) {
    private val settingsCache = SettingsCache()

    /** Loads (creating on first use) the workspace [StructuredWorkspaceSettings]. */
    suspend fun loadSettings(appSettings: AppSettings): StructuredWorkspaceSettings {
        val localSwitchTime = todayDiarySwitchTimeStore.current()
        settingsCache.current?.let { cached ->
            return cached.copy(dayBoundary = localSwitchTime)
        }
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_SETTINGS)
        val decoded = if (raw == null) {
            seedSettings(appSettings)
        } else {
            StructuredRecordsCodec.decodeSettings(raw)
        }.copy(dayBoundary = localSwitchTime)
        settingsCache.current = decoded
        return decoded
    }

    /**
     * Compatibility helper for old callers that only need the current date. It deliberately ignores
     * the diary switch time and returns the natural device-local calendar date.
     */
    suspend fun resolveJournalDay(
        appSettings: AppSettings,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate {
        @Suppress("UNUSED_VARIABLE")
        val ignored = appSettings
        return LocalDateTime.ofInstant(now, zone).toLocalDate()
    }

    /**
     * The one and only switched-date resolver. Call this only from the explicit “进入今日日记” action.
     */
    suspend fun resolveTodayDiaryDate(
        appSettings: AppSettings,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate {
        @Suppress("UNUSED_VARIABLE")
        val ignored = appSettings
        val switchMinutes = JournalDayEngine.parseBoundary(todayDiarySwitchTimeStore.current())
        return JournalDayEngine.resolveTodayDiaryDate(LocalDateTime.ofInstant(now, zone), switchMinutes)
    }

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
        settingsCache.current = value.copy(dayBoundary = todayDiarySwitchTimeStore.current())
    }

    /**
     * Compatibility entry point used by the existing settings UI. The value is now device-local and
     * takes effect immediately only for “进入今日日记”; no history is created and no workspace JSON
     * field is changed.
     */
    suspend fun setDayBoundary(
        appSettings: AppSettings,
        newBoundary: String,
        journalDay: LocalDate,
    ): StructuredWorkspaceSettings {
        @Suppress("UNUSED_VARIABLE")
        val ignoredDay = journalDay
        val normalized = JournalDayEngine.parseBoundary(newBoundary)
            ?.let(JournalDayEngine::formatBoundary)
            ?: JournalDayEngine.DEFAULT_DAY_BOUNDARY
        todayDiarySwitchTimeStore.set(normalized)
        val current = loadSettings(appSettings).copy(dayBoundary = normalized)
        settingsCache.current = current
        return current
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
        if (loadFields(appSettings).isEmpty()) seedFields(appSettings)
        if (loadTemplates(appSettings).isEmpty()) seedTemplates(appSettings)
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
        val seeded = StructuredWorkspaceSettings(schemaVersion = 1, markdownProtocolVersion = 1)
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

    /** Migrates legacy daily-event templates without rewriting historical Markdown. */
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
                    if (parts[index].isNotEmpty()) segments += StructuredRecordSegment.Text(parts[index])
                    if (index < parts.size - 1) segments += StructuredRecordSegment.Field(fieldId)
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
        var result: Long = hash.toLong() and 0xffffffffL
        result = result * 31 + value.length
        return result.toString(36).take(10)
    }

    private class SettingsCache {
        var current: StructuredWorkspaceSettings? = null
    }
}
