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
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StructuredRecordWriteResult(
    val success: Boolean,
    val message: String? = null,
    /** Legacy property name kept for API/index compatibility; value is the natural Markdown date. */
    val journalDay: LocalDate? = null,
)

data class IncrementalIndexResult(
    val parsedFiles: Int,
    val totalOccurrences: Int,
)

internal const val STRUCTURED_HASH_AUDIT_INTERVAL_MS = 6L * 60L * 60L * 1000L

internal fun shouldVerifyStructuredFile(
    lastModified: Long,
    lastVerifiedAt: Long,
    nowMillis: Long,
): Boolean = lastModified <= 0L ||
    nowMillis - lastVerifiedAt >= STRUCTURED_HASH_AUDIT_INTERVAL_MS

/**
 * Markdown-first structured-record orchestration. All new records belong to the natural local
 * calendar date. The “今日日记切换时间” is intentionally unavailable to this repository.
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
     * Writes completed stop -> next-start sleep sessions. A session is summarized on its natural
     * wake date, using the real local sleep/wake clock times. No Journal Day or diary switch time is
     * read. A short bounded backfill window heals days missed while the app was closed.
     */
    suspend fun settleAutomaticSleepWake(settings: AppSettings, now: Instant = Instant.now()): Int {
        if (!settings.structuredAutoRecordSleepWake) return 0
        workspaceRepository.ensureSystemFields(settings)
        val today = LocalDateTime.ofInstant(now, ZoneId.systemDefault()).toLocalDate()
        val fields = workspaceRepository.loadFields(settings).associateBy { it.id }
        val wakeField = fields[SYSTEM_FIELD_WAKE_TIME]
        val sleepField = fields[SYSTEM_FIELD_SLEEP_TIME]
        if (wakeField == null || sleepField == null) return 0

        var written = 0
        for (offset in 0..3) {
            val wakeDate = today.minusDays(offset.toLong())
            val session = phoneInteractionEstimator.estimateForWakeDate(wakeDate, now = now) ?: continue
            val wake = session.wakeLocalTime()
            val sleep = session.sleepLocalTime()
            val wakeResult = upsertSystemFieldValue(
                settings,
                wakeField,
                wakeDate,
                JournalDayEngine.formatTime(wake),
            )
            if (wakeResult.success) written += 1
            val sleepResult = upsertSystemFieldValue(
                settings,
                sleepField,
                wakeDate,
                JournalDayEngine.formatTime(sleep),
            )
            if (sleepResult.success) written += 1
            session.durationSeconds
        }
        return written
    }

    /** Legacy API name; returns the natural local date and never reads workspace settings. */
    suspend fun currentJournalDay(settings: AppSettings, now: Instant = Instant.now()): LocalDate {
        @Suppress("UNUSED_VARIABLE")
        val ignored = settings
        return LocalDateTime.ofInstant(now, ZoneId.systemDefault()).toLocalDate()
    }

    suspend fun loadAllOccurrences(): List<StructuredRecordOccurrenceEntity> = structuredRecordDao.getAll()

    suspend fun occurrencesForField(fieldId: String, startIso: String, endIso: String) =
        structuredRecordDao.occurrencesForField(fieldId, startIso, endIso)

    /** Inserts one template-driven record into the natural-date Markdown file. */
    suspend fun insertRecordFromTemplate(
        settings: AppSettings,
        template: StructuredRecordTemplate,
        values: List<String>,
        now: Instant = Instant.now(),
    ): StructuredRecordWriteResult {
        val fields = workspaceRepository.loadFields(settings).associateBy { it.id }
        val fieldSegments = template.segments.filterIsInstance<StructuredRecordSegment.Field>()
        if (fieldSegments.size != values.size) {
            return StructuredRecordWriteResult(false, "字段与填写值数量不一致")
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
        val naturalDate = LocalDateTime.ofInstant(now, ZoneId.systemDefault()).toLocalDate()
        return try {
            diaryFileRepository.transformDiaryForDate(settings, naturalDate) { content ->
                val lineEnding = DiaryTextUtils.preferredLineEnding(content)
                val normalizedBlock = DiaryTextUtils.normalizeTextBlock(block, lineEnding)
                val sep = when {
                    content.isEmpty() || content.endsWith('\n') || content.endsWith('\r') -> ""
                    else -> lineEnding
                }
                content + sep + normalizedBlock
            }
            refreshFileIndex(settings, naturalDate)
            StructuredRecordWriteResult(true, null, naturalDate)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            StructuredRecordWriteResult(false, error.message ?: "写入日记失败", naturalDate)
        }
    }

    /**
     * Writes or updates one system field in the supplied natural-date file. The parameter keeps its
     * old name only to avoid a Room/API migration; it is never resolved through a day boundary.
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

    suspend fun rebuildIndex(settings: AppSettings) = indexMutex.withLock {
        refreshIndex(settings, forceAll = true)
    }

    suspend fun refreshIncremental(settings: AppSettings): IncrementalIndexResult = indexMutex.withLock {
        refreshIndex(settings, forceAll = false)
    }

    private suspend fun refreshFileIndex(settings: AppSettings, journalDay: LocalDate) {
        indexMutex.withLock {
            val files = diaryFileRepository.listDiaryFileMeta(settings)
            val target = files.firstOrNull { file ->
                diaryFileRepository.extractDate(file.name, file.lastModified, settings.fileNamePattern) == journalDay
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
        val previousStates = if (forceAll) null
        else structuredRecordDao.allFileStates().associateBy { it.sourceFile }
        val activeUris = files.map { it.uri }
        var parsed = 0
        for (file in files) {
            val previous = previousStates?.get(file.uri)
            val metadataUnchanged = previous != null &&
                previous.modifiedAt == file.lastModified &&
                previous.fileSize == file.size
            val nowMillis = Instant.now().toEpochMilli()
            val requiresHashAudit = previous != null && shouldVerifyStructuredFile(
                lastModified = file.lastModified,
                lastVerifiedAt = previous.parsedAt,
                nowMillis = nowMillis,
            )
            if (metadataUnchanged && !requiresHashAudit) continue
            if (metadataUnchanged && previous != null) {
                val document = runCatching { diaryFileRepository.load(file.uri) }.getOrNull()
                if (document != null) {
                    val currentHash = DiaryTextUtils.sha256(document.content.toByteArray())
                    if (currentHash == previous.sha256) {
                        structuredRecordDao.upsertFileState(previous.copy(parsedAt = nowMillis))
                        continue
                    }
                }
            }
            parseAndStoreFile(settings, file)
            parsed += 1
        }
        structuredRecordDao.deleteOccurrencesForMissingFiles(activeUris)
        structuredRecordDao.deleteFileStatesForMissingFiles(activeUris)
        return IncrementalIndexResult(parsedFiles = parsed, totalOccurrences = structuredRecordDao.getAll().size)
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
        if (occurrences.isNotEmpty()) structuredRecordDao.insertOccurrences(occurrences)
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

    suspend fun removeOccurrencesForFile(uri: String) {
        structuredRecordDao.deleteOccurrencesForFile(uri)
    }
}