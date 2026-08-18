package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.local.StructuredRecordDao
import com.deskcubby.app.data.local.StructuredRecordFileEntity
import com.deskcubby.app.data.local.StructuredRecordOccurrenceEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.repository.DiaryFileMeta
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.DiaryTextUtils
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of writing one structured record / system value into Markdown. */
data class StructuredRecordWriteResult(
    val success: Boolean,
    val message: String? = null,
    val journalDay: LocalDate? = null,
)

/** Outcome of an incremental index refresh. */
data class IncrementalIndexResult(
    val parsedFiles: Int,
    val totalOccurrences: Int,
)

/**
 * The structured-records orchestration layer: it owns the Markdown-first write rules and the
 * derived local index.
 *
 * Write order is always:
 *  1. compute Journal Day
 *  2. normalize values by field type
 *  3. write normal Markdown + dc field markers (through [DiaryFileRepository], which owns SAF I/O)
 *  4. update the local index
 *
 * Markdown is the source of truth. If an index update fails after a durable Markdown write, the
 * Markdown survives and the file stays marked "changed" so a later refresh re-parses it.
 */
@Singleton
class StructuredRecordsRepository @Inject constructor(
    private val diaryFileRepository: DiaryFileRepository,
    private val structuredRecordDao: StructuredRecordDao,
    private val workspaceRepository: StructuredWorkspaceRepository,
    private val phoneInteractionEstimator: PhoneInteractionEstimator,
) {
    private val indexMutex = Mutex()

    /**
     * Settles the automatic sleep/wake estimates for the previous journal day(s) into Markdown.
     * Only the final first-use/last-use of a complete journal day is written — individual unlock or
     * lock events are never appended to the diary. Idempotent: updating an existing marker to the
     * same value produces no file change.
     */
    suspend fun settleAutomaticSleepWake(settings: AppSettings, now: Instant = Instant.now()): Int {
        if (!settings.structuredAutoRecordSleepWake) return 0
        workspaceRepository.ensureSystemFields(settings)
        val workspace = workspaceRepository.loadSettings(settings)
        val boundaryMinutes = JournalDayEngine.parseBoundary(workspace.dayBoundary)
        val today = JournalDayEngine.resolveJournalDay(now, boundaryMinutes)
        val fields = workspaceRepository.loadFields(settings).associateBy { it.id }
        val wakeField = fields[SYSTEM_FIELD_WAKE_TIME]
        val sleepField = fields[SYSTEM_FIELD_SLEEP_TIME]
        if (wakeField == null || sleepField == null) return 0

        var written = 0
        // Settle a small bounded window of past days so a missed day heals on the next open.
        for (offset in 1..3) {
            val day = today.minusDays(offset.toLong())
            val estimate = phoneInteractionEstimator.estimateForJournalDay(day, boundaryMinutes)
                ?: continue
            estimate.wakeTime?.let { wake ->
                val result = upsertSystemFieldValue(settings, wakeField, day, JournalDayEngine.formatTime(wake))
                if (result.success) written += 1
            }
            estimate.sleepTime?.let { sleep ->
                val result = upsertSystemFieldValue(settings, sleepField, day, JournalDayEngine.formatTime(sleep))
                if (result.success) written += 1
            }
        }
        return written
    }

    suspend fun currentJournalDay(settings: AppSettings, now: Instant = Instant.now()): LocalDate {
        val workspace = workspaceRepository.loadSettings(settings)
        return JournalDayEngine.resolveJournalDay(
            now,
            JournalDayEngine.parseBoundary(workspace.dayBoundary),
        )
    }

    suspend fun loadAllOccurrences(): List<StructuredRecordOccurrenceEntity> =
        structuredRecordDao.getAll()

    /** Queries the index for one field in a range (inclusive, ISO journal days). */
    suspend fun occurrencesForField(fieldId: String, startIso: String, endIso: String) =
        structuredRecordDao.occurrencesForField(fieldId, startIso, endIso)

    /**
     * Inserts one template-driven record into the journal file for the resolved Journal Day and
     * re-parses that file into the index. Values are in template field-segment order and are
     * validated/normalized by their field type before any Markdown is written.
     */
    suspend fun insertRecordFromTemplate(
        settings: AppSettings,
        template: StructuredRecordTemplate,
        values: List<String>,
        now: Instant = Instant.now(),
    ): StructuredRecordWriteResult {
        val fields = workspaceRepository.loadFields(settings).associateBy { it.id }
        val fieldSegments = template.segments.filterIsInstance<StructuredRecordSegment.Field>()
        if (fieldSegments.size != values.size) {
            return StructuredRecordWriteResult(
                false,
                "字段与填写值数量不一致",
            )
        }
        val normalizedTexts = ArrayList<String>(values.size)
        for (index in fieldSegments.indices) {
            val field = fields[fieldSegments[index].fieldId]
                ?: return StructuredRecordWriteResult(false, "字段不存在")
            val normalized = StructuredFieldNormalizer.normalize(field.type, values[index])
            if (normalized.isError) {
                return StructuredRecordWriteResult(false, "“${field.name}”无效：${normalized.error}")
            }
            if (normalized.value == null) {
                return StructuredRecordWriteResult(false, "“${field.name}”不能为空")
            }
            normalizedTexts += normalized.value.displayText
        }
        val block = StructuredMarkdownProtocol.buildRecordText(template.segments, normalizedTexts)
        val workspace = workspaceRepository.loadSettings(settings)
        val journalDay = JournalDayEngine.resolveJournalDay(
            now,
            JournalDayEngine.parseBoundary(workspace.dayBoundary),
        )
        val separator = "\n"
        return try {
            diaryFileRepository.transformDiaryForDate(settings, journalDay) { content ->
                val lineEnding = DiaryTextUtils.preferredLineEnding(content)
                val normalizedBlock = DiaryTextUtils.normalizeTextBlock(block, lineEnding)
                val sep = when {
                    content.isEmpty() || content.endsWith('\n') || content.endsWith('\r') -> ""
                    else -> lineEnding
                }
                content + sep + normalizedBlock
            }
            refreshFileIndex(settings, journalDay)
            StructuredRecordWriteResult(true, null, journalDay)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            StructuredRecordWriteResult(
                false,
                error.message ?: "写入日记失败",
                journalDay,
            )
        }
    }

    /**
     * Writes (or updates in place) one system-sourced field value on a Journal Day. When the field
     * marker already exists in that day's file the visible value is replaced without touching any
     * surrounding Markdown; otherwise a readable `名称：value` line is appended.
     */
    suspend fun upsertSystemFieldValue(
        settings: AppSettings,
        field: StructuredField,
        journalDay: LocalDate,
        rawValue: String,
    ): StructuredRecordWriteResult {
        val normalized = StructuredFieldNormalizer.normalize(field.type, rawValue)
        if (normalized.isError || normalized.value == null) {
            return StructuredRecordWriteResult(false, "系统字段值无效")
        }
        val display = normalized.value.displayText
        return try {
            diaryFileRepository.transformDiaryForDate(settings, journalDay) { content ->
                val existing = StructuredMarkdownProtocol.parse(content)
                    .firstOrNull { it.fieldId == field.id }
                if (existing != null) {
                    StructuredMarkdownProtocol.replaceValue(content, existing, display)
                } else {
                    val preferred = DiaryTextUtils.preferredLineEnding(content)
                    val block = "${field.name}：${StructuredMarkdownProtocol.openMarker(field.id)}$display${StructuredMarkdownProtocol.closeMarker(field.id)}"
                    val normalizedBlock = DiaryTextUtils.normalizeTextBlock(block, preferred)
                    val sep = when {
                        content.isEmpty() || content.endsWith('\n') || content.endsWith('\r') -> ""
                        else -> preferred
                    }
                    content + sep + normalizedBlock
                }
            }
            refreshFileIndex(settings, journalDay)
            StructuredRecordWriteResult(true, null, journalDay)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            StructuredRecordWriteResult(false, error.message ?: "写入日记失败", journalDay)
        }
    }

    /**
     * Rebuilds the entire structured-records index from Markdown. Safe to call at any time; the
     * result must equal an incrementally maintained index.
     */
    suspend fun rebuildIndex(settings: AppSettings) = indexMutex.withLock {
        refreshIndex(settings, forceAll = true)
    }

    /**
     * Incremental refresh: only files whose mtime/size changed (or that are new) are re-parsed.
     * Statistics and Agent queries read the index; nothing here full-scans Markdown on every call.
     */
    suspend fun refreshIncremental(settings: AppSettings): IncrementalIndexResult = indexMutex.withLock {
        val result = refreshIndex(settings, forceAll = false)
        result
    }

    /** Re-parses one journal file after an internal write so the index stays current immediately. */
    private suspend fun refreshFileIndex(settings: AppSettings, journalDay: LocalDate) {
        indexMutex.withLock {
            val files = diaryFileRepository.listDiaryFileMeta(settings)
            val target = files.firstOrNull { file ->
                diaryFileRepository.extractDate(file.name, file.lastModified, settings.fileNamePattern) ==
                    journalDay
            }
            if (target != null) parseAndStoreFile(settings, target)
        }
    }

    private suspend fun refreshIndex(
        settings: AppSettings,
        forceAll: Boolean,
    ): IncrementalIndexResult {
        val files = diaryFileRepository.listDiaryFileMeta(settings)
        if (files.isEmpty()) {
            structuredRecordDao.clear()
            return IncrementalIndexResult(parsedFiles = 0, totalOccurrences = 0)
        }
        val previousStates = if (forceAll) {
            null
        } else {
            structuredRecordDao.allFileStates().associateBy { it.sourceFile }
        }
        val activeUris = files.map { it.uri }
        var parsed = 0
        for (file in files) {
            val previous = previousStates?.get(file.uri)
            val unchanged = previous != null &&
                previous.modifiedAt == file.lastModified &&
                previous.fileSize == file.size
            if (unchanged) continue
            parseAndStoreFile(settings, file)
            parsed += 1
        }
        structuredRecordDao.deleteOccurrencesForMissingFiles(activeUris)
        structuredRecordDao.deleteFileStatesForMissingFiles(activeUris)
        val total = structuredRecordDao.getAll().size
        return IncrementalIndexResult(parsedFiles = parsed, totalOccurrences = total)
    }

    private suspend fun parseAndStoreFile(settings: AppSettings, file: DiaryFileMeta) {
        val document = runCatching { diaryFileRepository.load(file.uri) }.getOrNull() ?: return
        val content = document.content
        val sha256 = DiaryTextUtils.sha256(content.toByteArray())
        val journalDay = diaryFileRepository.extractDate(
            file.name,
            file.lastModified,
            settings.fileNamePattern,
        ).toString()
        val occurrences = StructuredMarkdownProtocol.parse(content).mapIndexed { order, occurrence ->
            StructuredRecordOccurrenceEntity(
                journalDay = journalDay,
                sourceFile = file.uri,
                sourceFileModifiedAt = document.lastModified,
                fieldId = occurrence.fieldId,
                rawValue = occurrence.rawValue,
                normalizedValue = occurrence.rawValue,
                valueType = "raw",
                orderInFile = order,
                parsedAt = Instant.now().toEpochMilli(),
            )
        }
        structuredRecordDao.deleteOccurrencesForFile(file.uri)
        if (occurrences.isNotEmpty()) {
            structuredRecordDao.insertOccurrences(occurrences)
        }
        structuredRecordDao.upsertFileState(
            StructuredRecordFileEntity(
                sourceFile = file.uri,
                modifiedAt = file.lastModified,
                fileSize = file.size,
                sha256 = sha256,
                parsedAt = Instant.now().toEpochMilli(),
            ),
        )
    }

    /**
     * Deletes all occurrences belonging to the journal file at [uri]. Used by the agent edit/delete
     * path and by file deletion so the index never holds "ghost" values for removed Markdown.
     */
    suspend fun removeOccurrencesForFile(uri: String) {
        structuredRecordDao.deleteOccurrencesForFile(uri)
    }
}
