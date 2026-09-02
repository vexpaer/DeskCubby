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
import kotlinx.coroutines.flow.StateFlow

private const val LEGACY_MIGRATED_XX_FIELD_PREFIX = "f_migrated_word_"

/** True only when a workspace file has never been created; existing empty files are intentional. */
internal fun shouldInitializeStructuredWorkspaceFile(raw: String?): Boolean = raw == null

/** Starter templates are safe only when every field they reference exists. */
internal fun defaultTemplatesSupportedBy(
    fields: List<StructuredField>,
): List<StructuredRecordTemplate> {
    val fieldIds = fields.mapTo(HashSet(fields.size)) { it.id }
    return DefaultStructuredExamples.TEMPLATES.filter { template ->
        template.segments.filterIsInstance<StructuredRecordSegment.Field>()
            .all { it.fieldId in fieldIds }
    }
}

/**
 * Older structured-record releases converted every legacy daily-event `xx` into a generated WORD
 * field whose id starts with [LEGACY_MIGRATED_XX_FIELD_PREFIX]. Those fields were implementation
 * artifacts, not user-created structured fields. Restore only those bindings to literal `xx` at the
 * workspace boundary so existing installs immediately regain the original underline/tap-replace UI.
 *
 * This is intentionally a projection migration rather than a destructive history rewrite: old
 * Markdown markers and their Room index rows remain readable, while all future records from the
 * restored template write the replacement as ordinary Markdown text.
 */
internal fun restoreLegacyPlainXxTemplates(
    templates: List<StructuredRecordTemplate>,
): List<StructuredRecordTemplate> = templates.map { template ->
    var changed = false
    val restored = template.segments.map { segment ->
        if (
            segment is StructuredRecordSegment.Field &&
            segment.fieldId.startsWith(LEGACY_MIGRATED_XX_FIELD_PREFIX)
        ) {
            changed = true
            StructuredRecordSegment.Text("xx")
        } else {
            segment
        }
    }
    if (changed) template.copy(segments = restored) else template
}

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
    /** Includes app writes, seed writes and same-root cloud-sync replacements. */
    val workspaceChanges: StateFlow<Long>
        get() = diaryFileRepository.workspaceChanges

    /**
     * Loads (creating on first use) the workspace settings. Cloud Sync may replace the same diary
     * root's file without changing its URI, so this intentionally never caches file contents.
     */
    suspend fun loadSettings(appSettings: AppSettings): StructuredWorkspaceSettings {
        val localSwitchTime = todayDiarySwitchTimeStore.current()
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_SETTINGS)
        if (raw != null) {
            return StructuredRecordsCodec.decodeSettings(raw).copy(dayBoundary = localSwitchTime)
        }

        val seeded = StructuredWorkspaceSettings(schemaVersion = 1, markdownProtocolVersion = 1)
        var resolved = seeded
        diaryFileRepository.updateWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_SETTINGS) { current ->
            if (current == null) {
                StructuredRecordsCodec.encodeSettings(seeded)
            } else {
                resolved = StructuredRecordsCodec.decodeSettings(current)
                current
            }
        }
        return resolved.copy(dayBoundary = localSwitchTime)
    }

    /** Compatibility helper: structured records always use the natural local date. */
    suspend fun resolveJournalDay(
        appSettings: AppSettings,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate {
        @Suppress("UNUSED_VARIABLE")
        val ignored = appSettings
        return LocalDateTime.ofInstant(now, zone).toLocalDate()
    }

    /** The one switched-date resolver, used only by the explicit “进入今日日记” action. */
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

    suspend fun reloadSettings(appSettings: AppSettings): StructuredWorkspaceSettings = loadSettings(appSettings)

    suspend fun saveSettings(appSettings: AppSettings, value: StructuredWorkspaceSettings) {
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_SETTINGS,
            StructuredRecordsCodec.encodeSettings(value),
        )
    }

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
        return loadSettings(appSettings).copy(dayBoundary = normalized)
    }

    /** Missing fields.json initializes defaults; an existing empty array remains empty. */
    suspend fun loadFields(appSettings: AppSettings): List<StructuredField> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_FIELDS)
        if (!shouldInitializeStructuredWorkspaceFile(raw)) {
            return StructuredRecordsCodec.decodeFields(requireNotNull(raw)).sortedBy { it.sortOrder }
        }

        var resolved = DefaultStructuredExamples.FIELDS
        diaryFileRepository.updateWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_FIELDS) { current ->
            if (current == null) {
                StructuredRecordsCodec.encodeFields(DefaultStructuredExamples.FIELDS)
            } else {
                resolved = StructuredRecordsCodec.decodeFields(current).sortedBy { it.sortOrder }
                current
            }
        }
        return resolved
    }

    /** Read-only variant for surfaces (notably widgets) that must never initialize workspace data. */
    suspend fun loadFieldsReadOnly(appSettings: AppSettings): List<StructuredField> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_FIELDS)
            ?: return emptyList()
        return StructuredRecordsCodec.decodeFields(raw).sortedBy { it.sortOrder }
    }

    suspend fun saveFields(appSettings: AppSettings, fields: List<StructuredField>) {
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_FIELDS,
            StructuredRecordsCodec.encodeFields(fields),
        )
    }

    /** Applies a field transform to the latest on-disk value under the shared workspace mutex. */
    suspend fun mutateFields(
        appSettings: AppSettings,
        transform: (List<StructuredField>) -> List<StructuredField>,
    ): List<StructuredField> {
        var resolved: List<StructuredField>? = null
        diaryFileRepository.updateWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_FIELDS) { current ->
            // updateWorkspaceFile already reads the latest file under the workspace mutex. Calling
            // loadFields immediately beforehand doubled SAF I/O. A genuinely missing file has the
            // same initialization semantics here: start from the default field set.
            val existing = if (current == null) {
                DefaultStructuredExamples.FIELDS
            } else {
                StructuredRecordsCodec.decodeFields(current).sortedBy { it.sortOrder }
            }
            val updated = transform(existing)
            resolved = updated
            if (current != null && updated == existing) current else StructuredRecordsCodec.encodeFields(updated)
        }
        return requireNotNull(resolved)
    }

    /**
     * Loads record templates. Missing records.json migrates legacy events or seeds only starter
     * templates whose referenced fields exist. An existing explicit empty array is always valid.
     */
    suspend fun loadTemplates(appSettings: AppSettings): List<StructuredRecordTemplate> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_RECORDS)
        if (!shouldInitializeStructuredWorkspaceFile(raw)) {
            return restoreLegacyPlainXxTemplates(
                StructuredRecordsCodec.decodeTemplates(requireNotNull(raw)).sortedBy { it.sortOrder },
            )
        }

        val migrated = migrateLegacyDailyEvents(appSettings)
        val candidate = if (migrated.isNotEmpty()) {
            migrated
        } else {
            defaultTemplatesSupportedBy(loadFields(appSettings))
        }
        var resolved = candidate
        diaryFileRepository.updateWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_RECORDS) { current ->
            if (current == null) {
                StructuredRecordsCodec.encodeTemplates(candidate)
            } else {
                resolved = restoreLegacyPlainXxTemplates(
                    StructuredRecordsCodec.decodeTemplates(current).sortedBy { it.sortOrder },
                )
                current
            }
        }
        return resolved
    }

    /** Background read: missing records.json means no rows yet, never a seed/migration write. */
    suspend fun loadTemplatesReadOnly(appSettings: AppSettings): List<StructuredRecordTemplate> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_RECORDS)
            ?: return emptyList()
        return restoreLegacyPlainXxTemplates(
            StructuredRecordsCodec.decodeTemplates(raw).sortedBy { it.sortOrder },
        )
    }

    /**
     * Widget read that distinguishes a genuinely missing records.json from an intentional empty one.
     * Before first migration, render legacy rows with their original IDs so existing PendingIntents
     * still have an entry point that can perform the durable migration on tap.
     */
    suspend fun loadTemplatesForWidget(appSettings: AppSettings): List<StructuredRecordTemplate> {
        val raw = diaryFileRepository.readWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_RECORDS)
        if (raw != null) {
            return restoreLegacyPlainXxTemplates(
                StructuredRecordsCodec.decodeTemplates(raw).sortedBy { it.sortOrder },
            )
        }
        return appSettings.dailyEventTemplates.mapIndexedNotNull { index, event ->
            val text = event.text.trim().take(StructuredRecordsCodec.MAX_TEXT_CHARS)
            if (text.isBlank()) null else StructuredRecordTemplate(
                id = event.id,
                name = text.take(StructuredRecordsCodec.MAX_NAME_CHARS),
                segments = listOf(StructuredRecordSegment.Text(text)),
                sortOrder = index,
            )
        }
    }

    /** Accepts both current IDs and pre-migration DailyEventTemplate IDs from already-installed widgets. */
    suspend fun resolveTemplateForWidget(
        appSettings: AppSettings,
        templateId: String?,
    ): StructuredRecordTemplate? {
        if (templateId.isNullOrBlank()) return null
        val templates = loadTemplates(appSettings)
        templates.firstOrNull { it.id == templateId }?.let { current ->
            return current.takeUnless { it.archived }
        }
        if (templates.isEmpty()) return null
        val legacy = appSettings.dailyEventTemplates.firstOrNull { it.id == templateId } ?: return null
        val migratedId = "r_migrated_${stableId(legacy.id)}"
        templates.firstOrNull { it.id == migratedId }?.let { migrated ->
            return migrated.takeUnless { it.archived }
        }

        // records.json may already exist even though this particular old widget ID was never
        // migrated. Build the same deterministic legacy representation, then atomically merge only
        // the requested template into the latest records file.
        val migrated = migrateLegacyDailyEvents(appSettings).firstOrNull { it.id == migratedId }
            ?: return null
        val canonical = mutateTemplates(appSettings) { current ->
            if (current.any { it.id == migrated.id }) current
            else current + migrated.copy(sortOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1)
        }
        return canonical.firstOrNull { it.id == migratedId }?.takeUnless { it.archived }
    }

    suspend fun saveTemplates(appSettings: AppSettings, templates: List<StructuredRecordTemplate>) {
        diaryFileRepository.writeWorkspaceFile(
            appSettings,
            StructuredRecordsCodec.FILE_RECORDS,
            StructuredRecordsCodec.encodeTemplates(restoreLegacyPlainXxTemplates(templates)),
        )
    }

    /** Applies a template transform to the latest on-disk list atomically with cloud-sync writes. */
    suspend fun mutateTemplates(
        appSettings: AppSettings,
        transform: (List<StructuredRecordTemplate>) -> List<StructuredRecordTemplate>,
    ): List<StructuredRecordTemplate> {
        loadTemplates(appSettings)
        var resolved: List<StructuredRecordTemplate>? = null
        diaryFileRepository.updateWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_RECORDS) { current ->
            val existing = restoreLegacyPlainXxTemplates(
                current?.let(StructuredRecordsCodec::decodeTemplates).orEmpty().sortedBy { it.sortOrder },
            )
            val updated = transform(existing)
            resolved = updated
            if (current != null && updated == existing) current else StructuredRecordsCodec.encodeTemplates(updated)
        }
        return requireNotNull(resolved)
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

    /** Applies a statistics transform atomically with same-root cloud-sync workspace writes. */
    suspend fun mutateMetrics(
        appSettings: AppSettings,
        transform: (List<StructuredMetric>) -> List<StructuredMetric>,
    ): List<StructuredMetric> {
        var resolved: List<StructuredMetric>? = null
        diaryFileRepository.updateWorkspaceFile(appSettings, StructuredRecordsCodec.FILE_STATISTICS) { current ->
            val existing = current?.let(StructuredRecordsCodec::decodeMetrics).orEmpty().sortedBy { it.sortOrder }
            val updated = transform(existing)
            resolved = updated
            if (current != null && updated == existing) current else StructuredRecordsCodec.encodeMetrics(updated)
        }
        return requireNotNull(resolved)
    }

    /** Lazy initialization used by normal screens. Existing explicit empty arrays remain empty. */
    suspend fun initializeMissingFiles(appSettings: AppSettings) {
        loadFields(appSettings)
        loadTemplates(appSettings)
    }

    /** Explicit Settings action: merge starter examples back without replacing user definitions. */
    suspend fun seedExamples(appSettings: AppSettings) {
        val fields = mutateFields(appSettings) { current ->
            val existingIds = current.mapTo(HashSet(current.size)) { it.id }
            var nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1
            current + DefaultStructuredExamples.FIELDS
                .filter { it.id !in existingIds }
                .map { it.copy(sortOrder = nextOrder++) }
        }
        val supportedDefaults = defaultTemplatesSupportedBy(fields)
        mutateTemplates(appSettings) { current ->
            val existingIds = current.mapTo(HashSet(current.size)) { it.id }
            var nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1
            current + supportedDefaults
                .filter { it.id !in existingIds }
                .map { it.copy(sortOrder = nextOrder++) }
        }
    }

    /** Ensures the system-sourced sleep/wake fields exist without dropping concurrent field edits. */
    suspend fun ensureSystemFields(appSettings: AppSettings): List<StructuredField> = mutateFields(appSettings) { fields ->
        val existingIds = fields.mapTo(HashSet(fields.size)) { it.id }
        val additions = buildList {
            if (SYSTEM_FIELD_SLEEP_TIME !in existingIds) {
                add(
                    StructuredField(
                        id = SYSTEM_FIELD_SLEEP_TIME,
                        name = "睡觉时间",
                        type = StructuredFieldType.TIME,
                        source = StructuredFieldSource.SYSTEM,
                        collector = COLLECTOR_LAST_PHONE_LOCK,
                    ),
                )
            }
            if (SYSTEM_FIELD_WAKE_TIME !in existingIds) {
                add(
                    StructuredField(
                        id = SYSTEM_FIELD_WAKE_TIME,
                        name = "起床时间",
                        type = StructuredFieldType.TIME,
                        source = StructuredFieldSource.SYSTEM,
                        collector = COLLECTOR_FIRST_PHONE_UNLOCK,
                    ),
                )
            }
        }
        if (additions.isEmpty()) {
            fields
        } else {
            var nextOrder = (fields.maxOfOrNull { it.sortOrder } ?: -1) + 1
            fields + additions.map { it.copy(sortOrder = nextOrder++) }
        }
    }

    /**
     * Migrates legacy daily-event templates without rewriting historical Markdown. `xx` remains
     * literal ordinary text: the structured editor provides its underline/tap replacement affordance
     * as a UI-only placeholder, but it never creates a StructuredField or participates in indexing.
     */
    private suspend fun migrateLegacyDailyEvents(
        appSettings: AppSettings,
    ): List<StructuredRecordTemplate> {
        val legacy = appSettings.dailyEventTemplates
        if (legacy.isEmpty()) return emptyList()
        return legacy.mapIndexedNotNull { index, event ->
            val text = event.text.trim().take(StructuredRecordsCodec.MAX_TEXT_CHARS)
            if (text.isBlank()) {
                null
            } else {
                StructuredRecordTemplate(
                    id = "r_migrated_${stableId(event.id)}",
                    name = text.take(StructuredRecordsCodec.MAX_NAME_CHARS),
                    segments = listOf(StructuredRecordSegment.Text(text)),
                    sortOrder = index,
                )
            }
        }
    }

    private fun stableId(value: String): String {
        val hash = value.hashCode()
        var result: Long = hash.toLong() and 0xffffffffL
        result = result * 31 + value.length
        return result.toString(36).take(10)
    }
}
