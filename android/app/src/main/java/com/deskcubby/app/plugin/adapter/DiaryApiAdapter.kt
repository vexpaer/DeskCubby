package com.deskcubby.app.plugin.adapter

import com.deskcubby.app.data.model.DiaryDocument as AppDiaryEntry
import com.deskcubby.app.data.model.DiaryEditorDocument
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.ExternalFileConflictException
import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.ContentVersion
import com.deskcubby.plugin.api.core.api.DiaryAPI
import com.deskcubby.plugin.api.core.api.DiaryConflictException
import com.deskcubby.plugin.api.core.api.DiaryDocument
import com.deskcubby.plugin.api.core.api.DiaryEntry
import com.deskcubby.plugin.api.core.api.DiaryTrashDocument
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class DiaryApiAdapter @Inject constructor(
    private val repository: DiaryFileRepository,
    private val settingsRepository: SettingsRepository,
) : DiaryAPI {
    override suspend fun list(): List<DiaryEntry> =
        repository.scan(settingsRepository.settings.first()).map(AppDiaryEntry::toPluginEntry)

    override suspend fun load(documentId: String): DiaryDocument =
        repository.load(documentId).toPluginDocument()

    override suspend fun create(title: String, dateIso: String?): DiaryDocument =
        repository.create(
            settings = settingsRepository.settings.first(),
            title = title,
            date = parsePluginDate(dateIso),
        ).toPluginDocument()

    override suspend fun openToday(dateIso: String?): DiaryDocument =
        repository.enterToday(
            settings = settingsRepository.settings.first(),
            today = parsePluginDate(dateIso),
        ).toPluginDocument()

    override suspend fun save(
        documentId: String,
        markdown: String,
        expectedVersion: ContentVersion,
        force: Boolean,
    ): DiaryDocument = try {
        repository.save(
            uri = documentId,
            content = markdown,
            expectedSha256 = expectedVersion.sha256,
            force = force,
        ).toPluginDocument()
    } catch (conflict: ExternalFileConflictException) {
        throw DiaryConflictException(conflict.diskDocument.toPluginDocument(), conflict)
    }

    override suspend fun rename(documentId: String, newFileName: String): DiaryDocument =
        repository.rename(
            uri = documentId,
            newFileName = newFileName,
            settings = settingsRepository.settings.first(),
        ).toPluginDocument()

    override suspend fun moveToTrash(documentId: String): Boolean =
        repository.delete(documentId, settingsRepository.settings.first())

    override suspend fun moveToTrashDocument(documentId: String): DiaryTrashDocument {
        val settings = settingsRepository.settings.first()
        val before = repository.scanTrash(settings).mapTo(hashSetOf()) { it.uri }
        check(repository.delete(documentId, settings)) { "Diary could not be moved to trash" }
        val trashed = repository.scanTrash(settings).firstOrNull { it.uri !in before }
            ?: error("Diary trash entry could not be identified")
        return DiaryTrashDocument(
            documentId = trashed.uri,
            originalName = trashed.originalName,
            deletedAtMillis = trashed.deletedAt,
        )
    }

    override suspend fun restoreFromTrash(trashDocumentId: String): DiaryDocument {
        val settings = settingsRepository.settings.first()
        val before = repository.scan(settings).mapTo(hashSetOf()) { it.uri }
        check(repository.restore(trashDocumentId, settings)) { "Diary could not be restored" }
        val restored = repository.scan(settings).firstOrNull { it.uri !in before }
            ?: error("Restored diary could not be identified")
        return repository.load(restored.uri).toPluginDocument()
    }

    override suspend fun appendToToday(markdown: String, dateIso: String?): DiaryDocument =
        repository.appendTextToToday(
            text = markdown,
            settings = settingsRepository.settings.first(),
            date = parsePluginDate(dateIso),
        ).toPluginDocument()
}

internal fun DiaryEditorDocument.toPluginDocument(): DiaryDocument = DiaryDocument(
    documentId = uri,
    name = name,
    markdown = content,
    version = ContentVersion(
        sha256 = sha256,
        size = size,
        lastModifiedMillis = lastModified,
    ),
)

private fun AppDiaryEntry.toPluginEntry(): DiaryEntry = DiaryEntry(
    documentId = uri,
    name = name,
    title = title,
    dateIso = dateIso,
    monthKey = monthKey,
    lastModifiedMillis = lastModified,
    size = size,
    wordCount = wordCount,
)

internal fun parsePluginDate(value: String?): LocalDate {
    if (value == null) return LocalDate.now()
    return try {
        LocalDate.parse(value)
    } catch (error: Exception) {
        throw PluginApiException(
            code = "INVALID_DATE",
            message = "Expected an ISO-8601 calendar date.",
            cause = error,
        )
    }
}
