package com.deskcubby.app.plugin.adapter

import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.NoteDocument
import com.deskcubby.app.data.repository.NoteEntry
import com.deskcubby.app.data.repository.NoteExternalConflictException
import com.deskcubby.app.data.repository.NoteFileVersion
import com.deskcubby.app.data.repository.NoteFolderLocation
import com.deskcubby.app.data.repository.NoteFolderSnapshot
import com.deskcubby.app.data.repository.NotesRepository
import com.deskcubby.plugin.api.core.PluginCapabilityUnavailableException
import com.deskcubby.plugin.api.core.api.ContentVersion
import com.deskcubby.plugin.api.core.api.VaultAPI
import com.deskcubby.plugin.api.core.api.VaultConflictException
import com.deskcubby.plugin.api.core.api.VaultDocument
import com.deskcubby.plugin.api.core.api.VaultEntry
import com.deskcubby.plugin.api.core.api.VaultEntryKind
import com.deskcubby.plugin.api.core.api.VaultFolder
import com.deskcubby.plugin.api.core.api.VaultFolderSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class VaultApiAdapter @Inject constructor(
    private val repository: NotesRepository,
    private val settingsRepository: SettingsRepository,
) : VaultAPI {
    override suspend fun browse(folder: VaultFolder?): VaultFolderSnapshot {
        val root = rootUri()
        val snapshot = if (folder == null) {
            repository.scanRoot(root)
        } else {
            repository.scanFolder(root, folder.toNoteFolder())
        }
        return snapshot.toPluginSnapshot()
    }

    override suspend fun createFolder(parent: VaultFolder?, name: String): VaultEntry {
        val root = rootUri()
        val resolvedParent = parent?.toNoteFolder() ?: repository.scanRoot(root).location
        return repository.createFolder(root, resolvedParent, name)
            .toPluginEntry(resolvedParent.relativePath)
    }

    override suspend fun createMarkdown(parent: VaultFolder?, name: String): VaultDocument {
        val root = rootUri()
        val resolvedParent = parent?.toNoteFolder() ?: repository.scanRoot(root).location
        return repository.createNote(root, resolvedParent, name).toPluginDocument()
    }

    override suspend fun load(entry: VaultEntry): VaultDocument =
        repository.load(
            rootUri = rootUri(),
            entry = entry.toNoteEntry(),
            folderRelativePath = entry.parentRelativePath,
        ).toPluginDocument()

    override suspend fun save(
        document: VaultDocument,
        markdown: String,
        force: Boolean,
    ): VaultDocument = try {
        repository.save(
            rootUri = rootUri(),
            document = document.toNoteDocument(),
            content = markdown,
            force = force,
        ).toPluginDocument()
    } catch (conflict: NoteExternalConflictException) {
        throw VaultConflictException(conflict.diskDocument.toPluginDocument(), conflict)
    }

    override suspend fun saveConflictCopy(
        document: VaultDocument,
        markdown: String,
    ): VaultDocument = repository.saveConflictCopy(
        rootUri = rootUri(),
        document = document.toNoteDocument(),
        content = markdown,
    ).toPluginDocument()

    override suspend fun rename(entry: VaultEntry, newName: String): VaultEntry =
        repository.renameEntry(rootUri(), entry.toNoteEntry(), newName)
            .toPluginEntry(entry.parentRelativePath)

    override suspend fun delete(entry: VaultEntry) {
        repository.deleteEntry(rootUri(), entry.toNoteEntry())
    }

    private suspend fun rootUri(): String =
        settingsRepository.settings.first().notesTreeUri
            ?: throw PluginCapabilityUnavailableException("vault")
}

private fun NoteFolderSnapshot.toPluginSnapshot(): VaultFolderSnapshot = VaultFolderSnapshot(
    folder = location.toPluginFolder(),
    entries = entries.map { it.toPluginEntry(location.relativePath) },
)

private fun NoteFolderLocation.toPluginFolder(): VaultFolder = VaultFolder(
    folderId = uri,
    name = name,
    relativePath = relativePath,
)

private fun VaultFolder.toNoteFolder(): NoteFolderLocation = NoteFolderLocation(
    uri = folderId,
    name = name,
    relativePath = relativePath,
)

private fun NoteEntry.toPluginEntry(parentRelativePath: String): VaultEntry = VaultEntry(
    entryId = uri,
    parentId = parentUri,
    parentRelativePath = parentRelativePath,
    name = name,
    kind = if (isFolder) VaultEntryKind.FOLDER else VaultEntryKind.MARKDOWN,
    size = size,
    lastModifiedMillis = lastModified,
)

private fun VaultEntry.toNoteEntry(): NoteEntry = NoteEntry(
    uri = entryId,
    parentUri = parentId,
    name = name,
    isFolder = kind == VaultEntryKind.FOLDER,
    size = size,
    lastModified = lastModifiedMillis,
)

private fun NoteDocument.toPluginDocument(): VaultDocument = VaultDocument(
    documentId = uri,
    parentId = parentUri,
    parentRelativePath = folderRelativePath,
    name = name,
    markdown = content,
    version = ContentVersion(
        sha256 = version.sha256,
        size = version.size,
        lastModifiedMillis = version.lastModified,
    ),
)

private fun VaultDocument.toNoteDocument(): NoteDocument = NoteDocument(
    uri = documentId,
    parentUri = parentId,
    folderRelativePath = parentRelativePath,
    name = name,
    content = markdown,
    version = NoteFileVersion(
        sha256 = version.sha256,
        size = version.size,
        lastModified = version.lastModifiedMillis,
    ),
)
