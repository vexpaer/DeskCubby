package com.deskcubby.plugin.api.core.api

/** Text-file operations restricted to user-authorized DeskCubby SAF roots. */
interface FileAPI {
    suspend fun roots(): List<FileRoot>

    suspend fun list(query: FileQuery): FilePage

    suspend fun search(query: FileSearchQuery): FilePage

    suspend fun read(rootId: String, fileId: String): FileDocument

    suspend fun prepareMutation(request: FileMutationRequest): FileMutationPlan

    suspend fun commitMutation(planToken: String): FileMutationResult

    suspend fun undoMutation(undoToken: String): FileMutationResult
}

data class FileRoot(
    val id: String,
    val labelChinese: String,
    val labelEnglish: String,
    val configured: Boolean,
)

data class FileQuery(
    val rootId: String,
    val folderId: String? = null,
    val offset: Int = 0,
    val limit: Int = 20,
)

data class FileSearchQuery(
    val rootId: String,
    val text: String,
    val offset: Int = 0,
    val limit: Int = 20,
)

data class FilePage(
    val entries: List<FileEntry>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

data class FileEntry(
    val rootId: String,
    val fileId: String,
    val parentId: String? = null,
    val parentRelativePath: String = "",
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMillis: Long,
    val version: ContentVersion? = null,
)

data class FileDocument(
    val entry: FileEntry,
    val content: String,
)

enum class FileMutationOperation {
    CREATE,
    UPDATE,
}

data class FileMutationRequest(
    val operation: FileMutationOperation,
    val rootId: String,
    val fileId: String? = null,
    val folderId: String? = null,
    val name: String? = null,
    val content: String,
)

data class FileMutationPlan(
    val planToken: String,
    val operation: FileMutationOperation,
    val target: String,
    val summary: String,
    val before: FileDocument? = null,
    val after: FileDocument? = null,
)

data class FileMutationResult(
    val operation: FileMutationOperation,
    val target: String,
    val summary: String,
    val before: FileDocument? = null,
    val after: FileDocument? = null,
    val undoToken: String? = null,
)
