package com.deskcubby.plugin.api.core.api

interface DiaryAPI {
    suspend fun list(): List<DiaryEntry>

    suspend fun load(documentId: String): DiaryDocument

    suspend fun create(title: String, dateIso: String? = null): DiaryDocument

    suspend fun openToday(dateIso: String? = null): DiaryDocument

    suspend fun save(
        documentId: String,
        markdown: String,
        expectedVersion: ContentVersion,
        force: Boolean = false,
    ): DiaryDocument

    suspend fun rename(documentId: String, newFileName: String): DiaryDocument

    suspend fun moveToTrash(documentId: String): Boolean

    suspend fun moveToTrashDocument(documentId: String): DiaryTrashDocument

    suspend fun restoreFromTrash(trashDocumentId: String): DiaryDocument

    suspend fun appendToToday(markdown: String, dateIso: String? = null): DiaryDocument
}

data class DiaryEntry(
    val documentId: String,
    val name: String,
    val title: String,
    val dateIso: String,
    val monthKey: String,
    val lastModifiedMillis: Long,
    val size: Long,
    val wordCount: Int,
)

data class DiaryDocument(
    val documentId: String,
    val name: String,
    val markdown: String,
    val version: ContentVersion,
)

data class DiaryTrashDocument(
    val documentId: String,
    val originalName: String,
    val deletedAtMillis: Long,
)

class DiaryConflictException(
    val currentDocument: DiaryDocument,
    cause: Throwable? = null,
) : Exception("The diary changed outside DeskCubby.", cause)
