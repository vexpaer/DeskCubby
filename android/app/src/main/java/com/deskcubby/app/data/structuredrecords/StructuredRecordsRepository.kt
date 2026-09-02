package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.local.StructuredRecordDao
import com.deskcubby.app.data.local.StructuredRecordFileEntity
import com.deskcubby.app.data.local.StructuredRecordOccurrenceEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DiaryEditorDocument
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
    /** Calendar date of the Markdown file that was actually written. */
    val journalDay: LocalDate? = null,
    val messageEnglish: String? = null,
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

private fun normalizationErrorEnglish(reason: String?): String = when (reason) {
    "内容包含保留标记 <!-- 与 -->" -> "reserved marker tokens are not allowed"
    "数值无效" -> "invalid number"
    "时间格式应为 HH:mm" -> "time must use HH:mm format"
    "时间无效" -> "invalid time"
    "时长分钟数无效" -> "invalid duration minutes"
    "时长格式无效" -> "invalid duration format"
    else -> "invalid format"
}

/**
 * Markdown-first structured-record storage. The caller owns date semantics: a home quick-add passes
 * today, while a diary-local action passes the opened diary's date. The repository only writes the
 * requested file and never silently replaces that target with the wall-clock date.
 *
 * Normal writes never rescan the diary directory. The exact durable document that changed is used
 * to update the derived Room index. Full directory reconciliation is reserved for startup/manual
 * rebuilds and external-file recovery.
 */
@Singleton
class StructuredRecordsRepository @Inject constructor(
    private val diaryFileRepository: DiaryFileRepository,
    private val structuredRecordDao: StructuredRecordDao,
    private val workspaceRepository: StructuredWorkspaceRepository,
    private val phoneInteractionEstimator: PhoneInteractionEstimator,
) {
    private val indexMutex = Mutex()

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
            val wakeResult = upsertSystemFieldValue(
                settings,
                wakeField,
                wakeDate,
                JournalDayEngine.formatTime(session.wakeLocalTime()),
            )
            if (wakeResult.success) written += 1
            val sleepResult = upsertSystemFieldValue(
                settings,
                sleepField,
                wakeDate,
                JournalDayEngine.formatTime(session.sleepLocalTime()),
            )
            if (sleepResult.success) written += 1
            session.durationSeconds
        }
        return written
    }

    /** Compatibility helper for callers that intentionally mean the real local date. */
    suspend fun currentJournalDay(settings: AppSettings, now: Instant = Instant.now()): LocalDate {
        @Suppress("UNUSED_VARIABLE")
        val ignored = settings
        return LocalDateTime.ofInstant(now, ZoneId.systemDefault()).toLocalDate()
    }

    suspend fun loadAllOccurrences(): List<StructuredRecordOccurrenceEntity> = structuredRecordDao.getAll()

    suspend fun occurrencesForField(fieldId: String, startIso: String, endIso: String) =
        structuredRecordDao.occurrencesForField(fieldId, startIso, endIso)

    /** Inserts one template-driven record into the explicitly requested date. */
    suspend fun insertRecordFromTemplate(
        settings: AppSettings,
        template: StructuredRecordTemplate,
        values: List<String>,
        targetDate: LocalDate? = null,
        now: Instant = Instant.now(),
        /** Optional caller-owned snapshot that avoids another SAF fields.json read on hot paths. */
        knownFieldsById: Map<String, StructuredField>? = null,
    ): StructuredRecordWriteResult {
        val fields = knownFieldsById ?: try {
            workspaceRepository.loadFields(settings).associateBy { it.id }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return StructuredRecordWriteResult(
                success = false,
                message = "无法读取结构化字段",
                messageEnglish = "Could not load structured fields",
            )
        }
        val fieldSegments = template.segments.filterIsInstance<StructuredRecordSegment.Field>()
        if (fieldSegments.size != values.size) {
            return StructuredRecordWriteResult(
                success = false,
                message = "字段与填写值数量不一致",
                messageEnglish = "The number of field values does not match the template",
            )
        }
        val normalizedTexts = ArrayList<String>(values.size)
        for (index in fieldSegments.indices) {
            val field = fields[fieldSegments[index].fieldId]
                ?: return StructuredRecordWriteResult(
                    success = false,
                    message = "字段不存在",
                    messageEnglish = "A referenced field no longer exists",
                )
            val normalized = StructuredFieldNormalizer.normalize(field.type, values[index])
            if (normalized.isError) {
                val chineseReason = normalized.error ?: "格式无效"
                val englishReason = normalizationErrorEnglish(normalized.error)
                return StructuredRecordWriteResult(
                    success = false,
                    message = "“${field.name}”的填写值无效：$chineseReason",
                    messageEnglish = "The value for “${field.name}” is invalid: $englishReason",
                )
            }
            if (normalized.value == null) {
                return StructuredRecordWriteResult(
                    success = false,
                    message = "“${field.name}”不能为空",
                    messageEnglish = "“${field.name}” cannot be empty",
                )
            }
            normalizedTexts += normalized.value.displayText
        }

        val block = StructuredMarkdownProtocol.buildRecordText(template.segments, normalizedTexts)
        val writeDate = targetDate ?: LocalDateTime.ofInstant(now, ZoneId.systemDefault()).toLocalDate()
        val document = try {
            diaryFileRepository.transformDiaryForDate(settings, writeDate) { content ->
                val lineEnding = DiaryTextUtils.preferredLineEnding(content)
                val normalizedBlock = DiaryTextUtils.normalizeTextBlock(block, lineEnding)
                val sep = when {
                    content.isEmpty() || content.endsWith('\n') || content.endsWith('\r') -> ""
                    else -> lineEnding
                }
                content + sep + normalizedBlock
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return StructuredRecordWriteResult(
                success = false,
                message = "写入日记失败",
                journalDay = writeDate,
                messageEnglish = "Could not write to the diary",
            )
        }
        return indexWrittenDocument(settings, document, writeDate)
    }

    /** Writes or updates one automatic system field in the supplied natural-date file. */
    suspend fun upsertSystemFieldValue(
        settings: AppSettings,
        field: StructuredField,
        journalDay: LocalDate,
        rawValue: String,
    ): StructuredRecordWriteResult {
        val normalized = StructuredFieldNormalizer.normalize(field.type, rawValue)
        if (normalized.isError || normalized.value == null) {
            return StructuredRecordWriteResult(
                success = false,
                message = "系统字段值无效",
                journalDay = journalDay,
                messageEnglish = "The system field value is invalid",
            )
        }
        val display = normalized.value.displayText
        val document = try {
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return StructuredRecordWriteResult(
                success = false,
                message = "写入日记失败",
                journalDay = journalDay,
                messageEnglish = "Could not write to the diary",
            )
        }
        return indexWrittenDocument(settings, document, journalDay)
    }

    /**
     * Re-indexes one already-saved diary document. Diary editor autosave, media edits and rename use
     * this instead of waiting for startup/full-directory reconciliation.
     */
    suspend fun indexDocument(settings: AppSettings, document: DiaryEditorDocument) {
        val journalDay = diaryFileRepository.extractDate(
            document.name,
            document.lastModified,
            settings.fileNamePattern,
        )
        refreshDocumentIndexes(settings, document, journalDay)?.let { throw it }
    }

    private suspend fun indexWrittenDocument(
        settings: AppSettings,
        document: DiaryEditorDocument,
        journalDay: LocalDate,
    ): StructuredRecordWriteResult {
        val indexFailure = refreshDocumentIndexes(settings, document, journalDay)
        return if (indexFailure == null) {
            StructuredRecordWriteResult(success = true, journalDay = journalDay)
        } else {
            StructuredRecordWriteResult(
                success = true,
                message = "记录已写入，但索引刷新失败",
                journalDay = journalDay,
                messageEnglish = "The record was saved, but the index refresh failed",
            )
        }
    }

    /** Attempts both rebuildable projections even if either Room update fails. */
    private suspend fun refreshDocumentIndexes(
        settings: AppSettings,
        document: DiaryEditorDocument,
        journalDay: LocalDate,
    ): Exception? {
        var failure: Exception? = null
        try {
            diaryFileRepository.indexDocument(settings, document)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
        }
        try {
            indexMutex.withLock { parseAndStoreDocument(document, journalDay) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        return failure
    }

    suspend fun rebuildIndex(settings: AppSettings) = indexMutex.withLock {
        refreshIndex(settings, forceAll = true)
    }

    suspend fun refreshIncremental(settings: AppSettings): IncrementalIndexResult = indexMutex.withLock {
        refreshIndex(settings, forceAll = false)
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
                val document = loadDocumentOrNull(file.uri)
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
        val document = loadDocumentOrNull(file.uri) ?: return
        val journalDay = diaryFileRepository.extractDate(
            file.name,
            file.lastModified,
            settings.fileNamePattern,
        )
        parseAndStoreDocument(document, journalDay)
    }

    private suspend fun loadDocumentOrNull(uri: String): DiaryEditorDocument? = try {
        diaryFileRepository.load(uri)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun parseAndStoreDocument(
        document: DiaryEditorDocument,
        journalDay: LocalDate,
    ) {
        val parsedAt = Instant.now().toEpochMilli()
        val occurrences = StructuredMarkdownProtocol.parse(document.content).mapIndexed { order, occurrence ->
            StructuredRecordOccurrenceEntity(
                journalDay = journalDay.toString(),
                sourceFile = document.uri,
                sourceFileModifiedAt = document.lastModified,
                fieldId = occurrence.fieldId,
                rawValue = occurrence.rawValue,
                normalizedValue = occurrence.rawValue,
                valueType = "raw",
                orderInFile = order,
                parsedAt = parsedAt,
            )
        }
        structuredRecordDao.replaceFileParse(
            StructuredRecordFileEntity(
                sourceFile = document.uri,
                modifiedAt = document.lastModified,
                fileSize = document.size,
                sha256 = document.sha256,
                parsedAt = parsedAt,
            ),
            occurrences,
        )
    }

    suspend fun removeOccurrencesForFile(uri: String) {
        indexMutex.withLock { structuredRecordDao.removeFileParse(uri) }
    }
}
