package com.deskcubby.app.plugin.adapter

import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.ContentVersion
import com.deskcubby.plugin.api.core.api.DiaryDocument
import com.deskcubby.plugin.api.core.api.DiaryAPI
import com.deskcubby.plugin.api.core.api.FileAPI
import com.deskcubby.plugin.api.core.api.FileDocument
import com.deskcubby.plugin.api.core.api.FileEntry
import com.deskcubby.plugin.api.core.api.FileMutationOperation
import com.deskcubby.plugin.api.core.api.FileMutationPlan
import com.deskcubby.plugin.api.core.api.FileMutationRequest
import com.deskcubby.plugin.api.core.api.FileMutationResult
import com.deskcubby.plugin.api.core.api.FilePage
import com.deskcubby.plugin.api.core.api.FileQuery
import com.deskcubby.plugin.api.core.api.FileRoot
import com.deskcubby.plugin.api.core.api.FileSearchQuery
import com.deskcubby.plugin.api.core.api.VaultDocument
import com.deskcubby.plugin.api.core.api.VaultAPI
import com.deskcubby.plugin.api.core.api.VaultEntry
import com.deskcubby.plugin.api.core.api.VaultEntryKind
import com.deskcubby.plugin.api.core.api.VaultFolder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class FileApiAdapter @Inject constructor(
    private val diaryApi: DiaryAPI,
    private val vaultApi: VaultAPI,
    private val settingsRepository: SettingsRepository,
) : FileAPI {
    override suspend fun roots(): List<FileRoot> {
        val settings = settingsRepository.settings.first()
        return listOf(
            FileRoot(DIARY_ROOT, "日记", "Diary", settings.diaryTreeUri != null),
            FileRoot(NOTES_ROOT, "笔记", "Notes", settings.notesTreeUri != null),
        )
    }

    override suspend fun list(query: FileQuery): FilePage {
        val (offset, limit) = page(query.offset, query.limit)
        val entries = when (query.rootId) {
            DIARY_ROOT -> {
                require(query.folderId == null) { "Diary does not expose arbitrary subfolders" }
                diaryApi.list().map { item ->
                    FileEntry(
                        rootId = DIARY_ROOT,
                        fileId = item.documentId,
                        name = item.name,
                        isDirectory = false,
                        size = item.size,
                        lastModifiedMillis = item.lastModifiedMillis,
                    )
                }
            }

            NOTES_ROOT -> {
                val folder = query.folderId?.let { findFolder(it) }
                vaultApi.browse(folder).entries.map(VaultEntry::toFileEntry)
            }

            else -> throw invalidRoot(query.rootId)
        }
        return entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
            .toPage(offset, limit)
    }

    override suspend fun search(query: FileSearchQuery): FilePage {
        val text = query.text.trim().take(MAX_QUERY_CHARS)
        require(text.isNotEmpty()) { "File search text must not be blank" }
        val (offset, limit) = page(query.offset, query.limit)
        val entries: List<FileEntry> = when (query.rootId) {
            DIARY_ROOT -> buildList<FileEntry> {
                var scanned = 0
                for (item in diaryApi.list()) {
                    if (scanned++ >= MAX_SEARCHED_FILES) break
                    val matches = item.name.contains(text, ignoreCase = true) ||
                        item.title.contains(text, ignoreCase = true) ||
                        tolerateSearchFailure {
                            diaryApi.load(item.documentId).markdown.contains(text, true)
                        }
                    if (matches) {
                        add(
                            FileEntry(
                                rootId = DIARY_ROOT,
                                fileId = item.documentId,
                                name = item.name,
                                isDirectory = false,
                                size = item.size,
                                lastModifiedMillis = item.lastModifiedMillis,
                            ),
                        )
                    }
                }
            }

            NOTES_ROOT -> buildList<FileEntry> {
                for (entry in allVaultEntries()) {
                    if (size >= MAX_SEARCHED_FILES) break
                    val matches = entry.name.contains(text, ignoreCase = true) ||
                        entry.kind == VaultEntryKind.MARKDOWN && tolerateSearchFailure {
                            vaultApi.load(entry).markdown.contains(text, ignoreCase = true)
                        }
                    if (matches) add(entry.toFileEntry())
                }
            }

            else -> throw invalidRoot(query.rootId)
        }
        return entries.toPage(offset, limit)
    }

    override suspend fun read(rootId: String, fileId: String): FileDocument = when (rootId) {
        DIARY_ROOT -> diaryApi.load(fileId).toFileDocument()
        NOTES_ROOT -> {
            val entry = findEntry(fileId)
            require(entry.kind == VaultEntryKind.MARKDOWN) { "Only Markdown files can be read" }
            vaultApi.load(entry).toFileDocument()
        }
        else -> throw invalidRoot(rootId)
    }.also { document ->
        require(document.content.length <= MAX_FILE_CONTENT_CHARS) { "File is too large for Agent tools" }
    }

    override suspend fun prepareMutation(request: FileMutationRequest): FileMutationPlan {
        require(request.content.length <= MAX_FILE_CONTENT_CHARS) { "File content is too large" }
        return when (request.operation) {
            FileMutationOperation.CREATE -> prepareCreate(request)
            FileMutationOperation.UPDATE -> prepareUpdate(request)
        }
    }

    override suspend fun commitMutation(planToken: String): FileMutationResult {
        val plan = decodeToken(planToken, PLAN_SCHEMA)
        return when (FileMutationOperation.valueOf(plan.getString("operation"))) {
            FileMutationOperation.CREATE -> commitCreate(plan)
            FileMutationOperation.UPDATE -> commitUpdate(plan)
        }
    }

    override suspend fun undoMutation(undoToken: String): FileMutationResult {
        val token = decodeToken(undoToken, UNDO_SCHEMA)
        return when (FileMutationOperation.valueOf(token.getString("operation"))) {
            FileMutationOperation.CREATE -> undoCreate(token)
            FileMutationOperation.UPDATE -> undoUpdate(token)
        }
    }

    private suspend fun prepareCreate(request: FileMutationRequest): FileMutationPlan {
        require(request.rootId == DIARY_ROOT || request.rootId == NOTES_ROOT) { "Invalid file root" }
        val name = requireFileName(request.name)
        val folder = if (request.rootId == NOTES_ROOT) {
            request.folderId?.let { findFolder(it) }
        } else {
            require(request.folderId == null) { "Diary files can only be created at the diary root" }
            null
        }
        val token = JSONObject()
            .put("schema", PLAN_SCHEMA)
            .put("operation", FileMutationOperation.CREATE.name)
            .put("rootId", request.rootId)
            .put("folderId", folder?.folderId ?: JSONObject.NULL)
            .put("folderName", folder?.name ?: "")
            .put("folderPath", folder?.relativePath ?: "")
            .put("name", name)
            .put("content", request.content)
            .toString()
        val plannedEntry = FileEntry(
            rootId = request.rootId,
            fileId = "",
            parentId = folder?.folderId,
            parentRelativePath = folder?.relativePath.orEmpty(),
            name = name,
            isDirectory = false,
            size = request.content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            lastModifiedMillis = 0,
        )
        return FileMutationPlan(
            planToken = token,
            operation = FileMutationOperation.CREATE,
            target = "${request.rootId}/$name",
            summary = "Create $name in ${request.rootId}",
            after = FileDocument(plannedEntry, request.content),
        )
    }

    private suspend fun prepareUpdate(request: FileMutationRequest): FileMutationPlan {
        val fileId = request.fileId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("fileId is required for update")
        val before = read(request.rootId, fileId)
        val token = JSONObject()
            .put("schema", PLAN_SCHEMA)
            .put("operation", FileMutationOperation.UPDATE.name)
            .put("rootId", request.rootId)
            .put("fileId", fileId)
            .put("expectedSha256", before.entry.version?.sha256.orEmpty())
            .put("beforeContent", before.content)
            .put("content", request.content)
            .toString()
        return FileMutationPlan(
            planToken = token,
            operation = FileMutationOperation.UPDATE,
            target = "${request.rootId}/${before.entry.name}",
            summary = "Update ${before.entry.name}",
            before = before,
            after = FileDocument(before.entry.copy(version = null), request.content),
        )
    }

    private suspend fun commitCreate(token: JSONObject): FileMutationResult {
        val rootId = token.getString("rootId")
        val name = token.getString("name")
        val content = token.getString("content")
        val after = when (rootId) {
            DIARY_ROOT -> {
                val created = diaryApi.create(name.substringBeforeLast('.', name))
                try {
                    diaryApi.save(created.documentId, content, created.version).toFileDocument()
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        runCatching { diaryApi.moveToTrashDocument(created.documentId) }
                    }
                    throw error
                }
            }
            NOTES_ROOT -> {
                val folder = token.optString("folderId").takeIf(String::isNotBlank)?.let {
                    VaultFolder(it, token.optString("folderName"), token.optString("folderPath"))
                }
                val created = vaultApi.createMarkdown(folder, name)
                try {
                    vaultApi.save(created, content).toFileDocument()
                } catch (error: Throwable) {
                    withContext(NonCancellable) {
                        runCatching { vaultApi.delete(created.toVaultEntry()) }
                    }
                    throw error
                }
            }
            else -> throw invalidRoot(rootId)
        }
        val undo = JSONObject()
            .put("schema", UNDO_SCHEMA)
            .put("operation", FileMutationOperation.CREATE.name)
            .put("rootId", rootId)
            .put("fileId", after.entry.fileId)
            .put("afterSha256", after.entry.version?.sha256.orEmpty())
            .put("name", after.entry.name)
            .toString()
        return FileMutationResult(
            FileMutationOperation.CREATE,
            "$rootId/${after.entry.name}",
            "Created ${after.entry.name}",
            after = after,
            undoToken = undo,
        )
    }

    private suspend fun commitUpdate(token: JSONObject): FileMutationResult {
        val rootId = token.getString("rootId")
        val fileId = token.getString("fileId")
        val before = read(rootId, fileId)
        require(before.entry.version?.sha256 == token.getString("expectedSha256")) {
            "File changed after the Agent prepared this edit"
        }
        val after = when (rootId) {
            DIARY_ROOT -> diaryApi.save(
                fileId,
                token.getString("content"),
                requireNotNull(before.entry.version),
            ).toFileDocument()
            NOTES_ROOT -> vaultApi.save(
                before.toVaultDocument(),
                token.getString("content"),
            ).toFileDocument()
            else -> throw invalidRoot(rootId)
        }
        val undo = JSONObject()
            .put("schema", UNDO_SCHEMA)
            .put("operation", FileMutationOperation.UPDATE.name)
            .put("rootId", rootId)
            .put("fileId", after.entry.fileId)
            .put("afterSha256", after.entry.version?.sha256.orEmpty())
            .put("beforeContent", before.content)
            .toString()
        return FileMutationResult(
            FileMutationOperation.UPDATE,
            "$rootId/${after.entry.name}",
            "Updated ${after.entry.name}",
            before,
            after,
            undo,
        )
    }

    private suspend fun undoCreate(token: JSONObject): FileMutationResult {
        val rootId = token.getString("rootId")
        val current = read(rootId, token.getString("fileId"))
        require(current.entry.version?.sha256 == token.getString("afterSha256")) {
            "File changed after the Agent created it; Undo stopped"
        }
        when (rootId) {
            DIARY_ROOT -> diaryApi.moveToTrashDocument(current.entry.fileId)
            NOTES_ROOT -> vaultApi.delete(current.toVaultEntry())
            else -> throw invalidRoot(rootId)
        }
        return FileMutationResult(
            FileMutationOperation.CREATE,
            "$rootId/${current.entry.name}",
            "Removed the file created by Agent",
            before = current,
            after = null,
        )
    }

    private suspend fun undoUpdate(token: JSONObject): FileMutationResult {
        val rootId = token.getString("rootId")
        val current = read(rootId, token.getString("fileId"))
        require(current.entry.version?.sha256 == token.getString("afterSha256")) {
            "File changed after the Agent edit; Undo stopped"
        }
        val restoredContent = token.getString("beforeContent")
        val restored = when (rootId) {
            DIARY_ROOT -> diaryApi.save(
                current.entry.fileId,
                restoredContent,
                requireNotNull(current.entry.version),
            ).toFileDocument()
            NOTES_ROOT -> vaultApi.save(current.toVaultDocument(), restoredContent).toFileDocument()
            else -> throw invalidRoot(rootId)
        }
        return FileMutationResult(
            FileMutationOperation.UPDATE,
            "$rootId/${restored.entry.name}",
            "Restored the previous file contents",
            before = current,
            after = restored,
        )
    }

    private suspend fun allVaultEntries(): List<VaultEntry> {
        val results = mutableListOf<VaultEntry>()
        val queue = ArrayDeque<VaultFolder?>()
        queue.add(null)
        while (queue.isNotEmpty() && results.size < MAX_SCANNED_FILES) {
            val snapshot = vaultApi.browse(queue.removeFirst())
            snapshot.entries.forEach { entry ->
                if (results.size >= MAX_SCANNED_FILES) return@forEach
                results += entry
                if (entry.kind == VaultEntryKind.FOLDER) {
                    queue.add(
                        VaultFolder(
                            entry.entryId,
                            entry.name,
                            listOf(entry.parentRelativePath, entry.name)
                                .filter(String::isNotBlank)
                                .joinToString("/"),
                        ),
                    )
                }
            }
        }
        return results
    }

    private suspend fun findFolder(folderId: String): VaultFolder {
        val root = vaultApi.browse().folder
        if (root.folderId == folderId) return root
        val entry = allVaultEntries().firstOrNull {
            it.entryId == folderId && it.kind == VaultEntryKind.FOLDER
        } ?: throw PluginApiException("FILE_NOT_FOUND", "The requested notes folder was not found.")
        return VaultFolder(
            entry.entryId,
            entry.name,
            listOf(entry.parentRelativePath, entry.name).filter(String::isNotBlank).joinToString("/"),
        )
    }

    private suspend fun findEntry(fileId: String): VaultEntry = allVaultEntries().firstOrNull {
        it.entryId == fileId
    } ?: throw PluginApiException("FILE_NOT_FOUND", "The requested note was not found.")

    private fun page(offset: Int, limit: Int): Pair<Int, Int> =
        offset.coerceAtLeast(0) to limit.coerceIn(1, MAX_PAGE_SIZE)

    private fun List<FileEntry>.toPage(offset: Int, limit: Int): FilePage = FilePage(
        entries = drop(offset).take(limit),
        offset = offset,
        limit = limit,
        hasMore = size > offset + limit,
    )

    private fun requireFileName(value: String?): String = value.orEmpty().trim().also { name ->
        require(name.isNotEmpty() && name.length <= MAX_FILE_NAME_CHARS) { "Invalid file name" }
        require('/' !in name && '\\' !in name && name != "." && name != "..") { "Invalid file name" }
    }

    private fun decodeToken(raw: String, schema: String): JSONObject {
        require(raw.length <= MAX_TOKEN_CHARS) { "Mutation token is too large" }
        return JSONObject(raw).also { token ->
            require(token.getString("schema") == schema) { "Invalid mutation token" }
        }
    }

    private fun invalidRoot(rootId: String) = PluginApiException(
        "INVALID_FILE_ROOT",
        "Unknown or unauthorized file root: $rootId",
    )

    private companion object {
        const val DIARY_ROOT = "diary"
        const val NOTES_ROOT = "notes"
        const val PLAN_SCHEMA = "deskcubby.agent-file-plan.v1"
        const val UNDO_SCHEMA = "deskcubby.agent-file-undo.v1"
        const val MAX_PAGE_SIZE = 100
        const val MAX_QUERY_CHARS = 500
        const val MAX_FILE_NAME_CHARS = 240
        const val MAX_FILE_CONTENT_CHARS = 256 * 1024
        const val MAX_SCANNED_FILES = 1_000
        const val MAX_SEARCHED_FILES = 1_000
        const val MAX_TOKEN_CHARS = 512 * 1024
    }
}

private suspend inline fun tolerateSearchFailure(block: () -> Boolean): Boolean = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    false
}

private fun DiaryDocument.toFileDocument() = FileDocument(
    entry = FileEntry(
        rootId = "diary",
        fileId = documentId,
        name = name,
        isDirectory = false,
        size = version.size,
        lastModifiedMillis = version.lastModifiedMillis,
        version = version,
    ),
    content = markdown,
)

private fun VaultDocument.toFileDocument() = FileDocument(
    entry = FileEntry(
        rootId = "notes",
        fileId = documentId,
        parentId = parentId,
        parentRelativePath = parentRelativePath,
        name = name,
        isDirectory = false,
        size = version.size,
        lastModifiedMillis = version.lastModifiedMillis,
        version = version,
    ),
    content = markdown,
)

private fun VaultEntry.toFileEntry() = FileEntry(
    rootId = "notes",
    fileId = entryId,
    parentId = parentId,
    parentRelativePath = parentRelativePath,
    name = name,
    isDirectory = kind == VaultEntryKind.FOLDER,
    size = size,
    lastModifiedMillis = lastModifiedMillis,
)

private fun FileDocument.toVaultDocument() = VaultDocument(
    documentId = entry.fileId,
    parentId = requireNotNull(entry.parentId),
    parentRelativePath = entry.parentRelativePath,
    name = entry.name,
    markdown = content,
    version = requireNotNull(entry.version),
)

private fun FileDocument.toVaultEntry() = VaultEntry(
    entryId = entry.fileId,
    parentId = requireNotNull(entry.parentId),
    parentRelativePath = entry.parentRelativePath,
    name = entry.name,
    kind = VaultEntryKind.MARKDOWN,
    size = entry.size,
    lastModifiedMillis = entry.lastModifiedMillis,
)

private fun VaultDocument.toVaultEntry() = VaultEntry(
    entryId = documentId,
    parentId = parentId,
    parentRelativePath = parentRelativePath,
    name = name,
    kind = VaultEntryKind.MARKDOWN,
    size = version.size,
    lastModifiedMillis = version.lastModifiedMillis,
)
