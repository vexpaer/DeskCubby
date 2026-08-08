package com.deskcubby.plugin.api.core.api

/** Obsidian-compatible Markdown/file vault API. This is separate from the encrypted Favorites UI. */
interface VaultAPI {
    suspend fun browse(folder: VaultFolder? = null): VaultFolderSnapshot

    suspend fun createFolder(parent: VaultFolder? = null, name: String): VaultEntry

    suspend fun createMarkdown(parent: VaultFolder? = null, name: String): VaultDocument

    suspend fun load(entry: VaultEntry): VaultDocument

    suspend fun save(
        document: VaultDocument,
        markdown: String,
        force: Boolean = false,
    ): VaultDocument

    suspend fun saveConflictCopy(document: VaultDocument, markdown: String): VaultDocument

    suspend fun rename(entry: VaultEntry, newName: String): VaultEntry

    suspend fun delete(entry: VaultEntry)
}

data class VaultFolder(
    val folderId: String,
    val name: String,
    val relativePath: String,
)

enum class VaultEntryKind {
    FOLDER,
    MARKDOWN,
}

data class VaultEntry(
    val entryId: String,
    val parentId: String,
    val parentRelativePath: String,
    val name: String,
    val kind: VaultEntryKind,
    val size: Long,
    val lastModifiedMillis: Long,
)

data class VaultFolderSnapshot(
    val folder: VaultFolder,
    val entries: List<VaultEntry>,
)

data class VaultDocument(
    val documentId: String,
    val parentId: String,
    val parentRelativePath: String,
    val name: String,
    val markdown: String,
    val version: ContentVersion,
)

class VaultConflictException(
    val currentDocument: VaultDocument,
    cause: Throwable? = null,
) : Exception("The Markdown file changed outside DeskCubby.", cause)
