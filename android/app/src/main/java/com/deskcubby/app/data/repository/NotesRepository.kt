package com.deskcubby.app.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class NoteFolderLocation(
    val uri: String,
    val name: String,
    /** Slash-separated, display-name path relative to the selected vault root. */
    val relativePath: String,
)

data class NoteEntry(
    val uri: String,
    val parentUri: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val lastModified: Long,
)

data class NoteFolderSnapshot(
    val location: NoteFolderLocation,
    val entries: List<NoteEntry>,
)

data class NoteFileVersion(
    val sha256: String,
    val size: Long,
    val lastModified: Long,
)

data class NoteDocument(
    val uri: String,
    val parentUri: String,
    val folderRelativePath: String,
    val name: String,
    val content: String,
    val version: NoteFileVersion,
)

data class ImportedNoteMedia(
    val uri: Uri,
    val fileName: String,
    val markdownTarget: String,
)

class NoteExternalConflictException(val diskDocument: NoteDocument) : Exception()

/**
 * SAF-only file boundary for the Obsidian-compatible Notes page.
 *
 * The selected tree remains the authority. No content URI is converted to a filesystem path;
 * every child URI is required to carry the same tree grant before it is read or mutated.
 */
@Singleton
class NotesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val writeMutex = Mutex()

    fun persistTreePermission(uri: Uri) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)) {
            "只能选择系统文件选择器中的目录"
        }
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    suspend fun scanRoot(rootUri: String): NoteFolderSnapshot = withContext(Dispatchers.IO) {
        val root = tree(rootUri)
        val location = NoteFolderLocation(
            uri = root.uri.toString(),
            name = root.name?.take(MAX_NOTE_NAME_CHARS).orEmpty().ifBlank { "Notes" },
            relativePath = "",
        )
        scanFolderUnlocked(rootUri, location)
    }

    suspend fun scanFolder(
        rootUri: String,
        location: NoteFolderLocation,
    ): NoteFolderSnapshot = withContext(Dispatchers.IO) {
        scanFolderUnlocked(rootUri, location)
    }

    suspend fun createFolder(
        rootUri: String,
        parent: NoteFolderLocation,
        requestedName: String,
    ): NoteEntry = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val name = normalizeNoteName(requestedName, markdownFile = false)
            val directory = directory(rootUri, parent.uri)
            requireNoSibling(directory, name)
            val created = directory.createDirectory(name) ?: error("存储服务无法创建文件夹")
            check(created.isDirectory && created.name == name) { "文件夹创建后校验失败" }
            created.toEntry(directory.uri)
        }
    }

    suspend fun createNote(
        rootUri: String,
        parent: NoteFolderLocation,
        requestedName: String,
    ): NoteDocument = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val name = normalizeNoteName(requestedName, markdownFile = true)
            val directory = directory(rootUri, parent.uri)
            requireNoSibling(directory, name)
            val created = directory.createFile("text/markdown", name)
                ?: error("存储服务无法创建笔记")
            val title = name.removeSuffix(".md")
            val initial = "# $title\n\n"
            try {
                writeTextVerified(created.uri, initial)
                loadUnlocked(rootUri, created.uri.toString(), parent.uri, parent.relativePath)
            } catch (error: Exception) {
                val committed = runCatching { readText(created.uri) == initial }.getOrDefault(false)
                if (!committed) {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { created.delete() } }
                }
                if (committed && error !is CancellationException) {
                    loadUnlocked(rootUri, created.uri.toString(), parent.uri, parent.relativePath)
                } else {
                    throw error
                }
            }
        }
    }

    suspend fun renameEntry(
        rootUri: String,
        entry: NoteEntry,
        requestedName: String,
    ): NoteEntry = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            requireInsideTree(rootUri, Uri.parse(entry.uri))
            val parent = directory(rootUri, entry.parentUri)
            val targetName = normalizeNoteName(requestedName, markdownFile = !entry.isFolder)
            if (targetName == entry.name) return@withContext entry
            requireNoSibling(parent, targetName, ignoredUri = entry.uri)
            val source = DocumentFile.fromSingleUri(context, Uri.parse(entry.uri))
                ?.takeIf(DocumentFile::exists)
                ?: error("找不到要重命名的项目")
            val renamedUri = runCatching {
                DocumentsContract.renameDocument(resolver, source.uri, targetName)
            }.getOrNull() ?: run {
                check(source.renameTo(targetName)) { "存储服务拒绝了新名称" }
                source.uri
            }
            val renamed = DocumentFile.fromSingleUri(context, renamedUri)
                ?.takeIf { it.exists() && it.name == targetName }
                ?: parent.listFiles().firstOrNull {
                    it.name.equals(targetName, ignoreCase = true)
                }
                ?: error("项目可能已重命名，但无法重新定位")
            renamed.toEntry(parent.uri)
        }
    }

    suspend fun deleteEntry(rootUri: String, entry: NoteEntry) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            requireInsideTree(rootUri, Uri.parse(entry.uri))
            val source = DocumentFile.fromSingleUri(context, Uri.parse(entry.uri))
                ?.takeIf(DocumentFile::exists)
                ?: return@withContext
            check(source.delete()) { "存储服务拒绝删除该项目" }
            val parent = directory(rootUri, entry.parentUri)
            check(parent.listFiles().none { it.uri.toString() == entry.uri }) {
                "删除后校验失败"
            }
        }
    }

    suspend fun load(
        rootUri: String,
        entry: NoteEntry,
        folderRelativePath: String,
    ): NoteDocument = withContext(Dispatchers.IO) {
        require(!entry.isFolder) { "文件夹不能作为 Markdown 打开" }
        loadUnlocked(rootUri, entry.uri, entry.parentUri, folderRelativePath)
    }

    suspend fun save(
        rootUri: String,
        document: NoteDocument,
        content: String,
        force: Boolean = false,
    ): NoteDocument = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            require(content.toByteArray(StandardCharsets.UTF_8).size <= MAX_NOTE_BYTES) {
                "笔记超过 ${MAX_NOTE_BYTES / 1024 / 1024} MiB 上限"
            }
            val disk = loadUnlocked(
                rootUri,
                document.uri,
                document.parentUri,
                document.folderRelativePath,
            )
            if (!force && disk.version.sha256 != document.version.sha256) {
                throw NoteExternalConflictException(disk)
            }
            try {
                writeTextVerified(Uri.parse(document.uri), content)
            } catch (error: Exception) {
                val committed = runCatching { readText(Uri.parse(document.uri)) == content }
                    .getOrDefault(false)
                if (!committed) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { writeTextVerified(Uri.parse(document.uri), disk.content) }
                            .exceptionOrNull()
                            ?.let(error::addSuppressed)
                    }
                    throw error
                }
            }
            loadUnlocked(
                rootUri,
                document.uri,
                document.parentUri,
                document.folderRelativePath,
            )
        }
    }

    suspend fun saveConflictCopy(
        rootUri: String,
        document: NoteDocument,
        content: String,
    ): NoteDocument = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val parent = directory(rootUri, document.parentUri)
            val stem = document.name.removeSuffix(".md").take(100)
            var sequence = 1
            var name: String
            do {
                val suffix = if (sequence == 1) "DeskCubby conflict" else "DeskCubby conflict $sequence"
                name = "$stem ($suffix).md"
                sequence += 1
            } while (parent.listFiles().any { it.name.equals(name, ignoreCase = true) })
            val created = parent.createFile("text/markdown", name)
                ?: error("无法创建冲突副本")
            try {
                writeTextVerified(created.uri, content)
                loadUnlocked(
                    rootUri,
                    created.uri.toString(),
                    document.parentUri,
                    document.folderRelativePath,
                )
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { created.delete() } }
                throw error
            }
        }
    }

    /** Copies one image into a user-picked folder inside the current vault and verifies it. */
    suspend fun importMedia(
        rootUri: String,
        sourceUri: Uri,
        destinationTreeUri: Uri,
        note: NoteDocument,
    ): ImportedNoteMedia = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            require(sourceUri.scheme == ContentResolver.SCHEME_CONTENT) {
                "只能导入系统选择器中的媒体"
            }
            requireTreeWithinRoot(rootUri, destinationTreeUri)
            persistTreePermission(destinationTreeUri)
            val mime = resolver.getType(sourceUri)
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.startsWith("image/") }
                ?: error("所选文件不是受支持的图片")
            val sourceName = queryDisplayName(sourceUri)
            val requestedName = normalizeMediaName(sourceName, mime)
            val destination = DocumentFile.fromTreeUri(context, destinationTreeUri)
                ?.takeIf { it.exists() && it.isDirectory && it.canWrite() }
                ?: error("媒体存储位置不可写")
            val actualName = uniqueSiblingName(destination, requestedName)
            val created = destination.createFile(mime, actualName)
                ?: error("无法在所选位置创建媒体")
            try {
                val written = copyBoundedAndDigest(sourceUri, created.uri)
                val verified = digestAndSize(created.uri, MAX_NOTE_MEDIA_BYTES)
                check(written == verified) { "媒体写入后的回读校验失败" }
                val providerName = created.name?.takeIf(String::isNotBlank) ?: actualName
                val target = relativeDocumentTarget(
                    fromFolderUri = Uri.parse(note.parentUri),
                    targetUri = created.uri,
                ) ?: providerName
                ImportedNoteMedia(
                    uri = created.uri,
                    fileName = providerName,
                    markdownTarget = target.replace('\\', '/'),
                )
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { created.delete() } }
                throw error
            }
        }
    }

    suspend fun resolveMediaTargets(
        rootUri: String,
        folderRelativePath: String,
        targets: Collection<String>,
    ): Map<String, Uri> = withContext(Dispatchers.IO) {
        if (targets.isEmpty()) return@withContext emptyMap()
        val root = tree(rootUri)
        buildMap {
            targets.distinct().take(MAX_NOTE_PREVIEW_TARGETS).forEach { original ->
                currentCoroutineContext().ensureActive()
                val cleaned = normalizeMarkdownTarget(original) ?: return@forEach
                val path = normalizedRelativeTarget(folderRelativePath, cleaned)
                val direct = path?.let { resolveRelativeFile(root, it) }
                val fallback = if (direct == null && '/' !in cleaned) {
                    findFileByNameBounded(root, cleaned)
                } else {
                    null
                }
                (direct ?: fallback)?.let { put(original, it.uri) }
            }
        }
    }

    private fun scanFolderUnlocked(
        rootUri: String,
        location: NoteFolderLocation,
    ): NoteFolderSnapshot {
        val folder = directory(rootUri, location.uri)
        val children = folder.listFiles()
        require(children.size <= MAX_NOTE_FOLDER_ENTRIES) { "文件夹项目过多，已停止扫描" }
        val entries = children.mapNotNull { child ->
            val name = child.name?.take(MAX_NOTE_NAME_CHARS)?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            when {
                child.isDirectory -> child.toEntry(folder.uri)
                child.isFile && name.endsWith(".md", ignoreCase = true) -> child.toEntry(folder.uri)
                else -> null
            }
        }.sortedWith(
            compareByDescending<NoteEntry> { it.isFolder }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name },
        )
        return NoteFolderSnapshot(location, entries)
    }

    private fun loadUnlocked(
        rootUri: String,
        uri: String,
        parentUri: String,
        folderRelativePath: String,
    ): NoteDocument {
        val parsed = Uri.parse(uri)
        requireInsideTree(rootUri, parsed)
        val file = DocumentFile.fromSingleUri(context, parsed)
            ?.takeIf { it.exists() && it.isFile }
            ?: error("笔记已被移动或删除")
        val name = file.name?.takeIf { it.endsWith(".md", ignoreCase = true) }
            ?: error("所选文件不是 Markdown 笔记")
        val content = readText(parsed)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        return NoteDocument(
            uri = parsed.toString(),
            parentUri = parentUri,
            folderRelativePath = folderRelativePath,
            name = name,
            content = content,
            version = NoteFileVersion(
                sha256 = sha256(bytes),
                size = bytes.size.toLong(),
                lastModified = file.lastModified().coerceAtLeast(0L),
            ),
        )
    }

    private fun tree(raw: String): DocumentFile {
        val uri = Uri.parse(raw)
        require(uri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(uri)) {
            "笔记目录授权无效"
        }
        return DocumentFile.fromTreeUri(context, uri)
            ?.takeIf { it.exists() && it.isDirectory }
            ?: error("笔记目录授权已失效，请重新选择")
    }

    private fun directory(rootUri: String, raw: String): DocumentFile {
        val uri = Uri.parse(raw)
        requireInsideTree(rootUri, uri)
        return DocumentFile.fromSingleUri(context, uri)
            ?.takeIf { it.exists() && it.isDirectory }
            ?: DocumentFile.fromTreeUri(context, uri)
                ?.takeIf { it.exists() && it.isDirectory }
            ?: error("文件夹已被移动或删除")
    }

    private fun requireInsideTree(rootRaw: String, candidate: Uri) {
        val root = Uri.parse(rootRaw)
        require(root.authority == candidate.authority) { "项目不在所选笔记目录中" }
        val rootTreeId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull()
            ?: error("笔记目录授权无效")
        val candidateTreeId = runCatching { DocumentsContract.getTreeDocumentId(candidate) }
            .getOrNull()
        require(candidate == root || candidateTreeId == rootTreeId) {
            "项目不在所选笔记目录中"
        }
    }

    private fun requireTreeWithinRoot(rootRaw: String, candidateTree: Uri) {
        val root = Uri.parse(rootRaw)
        require(root.authority == candidateTree.authority) { "媒体目录必须位于当前笔记库内" }
        val rootId = DocumentsContract.getTreeDocumentId(root)
        val candidateId = DocumentsContract.getTreeDocumentId(candidateTree)
        if (rootId == candidateId) return
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(root, rootId)
        val candidateDocument = DocumentsContract.buildDocumentUriUsingTree(
            candidateTree,
            candidateId,
        )
        val providerConfirms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            DocumentsContract.isChildDocument(resolver, rootDocument, candidateDocument)
        }.getOrDefault(false)
        val pathConfirms = documentIdPath(candidateId)?.let { candidatePath ->
            documentIdPath(rootId)?.let { rootPath ->
                candidatePath.first == rootPath.first &&
                    (candidatePath.second == rootPath.second ||
                        candidatePath.second.startsWith(rootPath.second.trimEnd('/') + "/"))
            }
        } == true
        require(providerConfirms || pathConfirms) { "媒体目录必须位于当前笔记库内" }
    }

    private fun DocumentFile.toEntry(parent: Uri): NoteEntry = NoteEntry(
        uri = uri.toString(),
        parentUri = parent.toString(),
        name = name?.take(MAX_NOTE_NAME_CHARS).orEmpty(),
        isFolder = isDirectory,
        size = if (isFile) length().coerceAtLeast(0L) else 0L,
        lastModified = lastModified().coerceAtLeast(0L),
    )

    private fun requireNoSibling(
        parent: DocumentFile,
        name: String,
        ignoredUri: String? = null,
    ) {
        require(parent.listFiles().none {
            it.uri.toString() != ignoredUri && it.name.equals(name, ignoreCase = true)
        }) { "当前文件夹已存在同名项目" }
    }

    private fun readText(uri: Uri): String {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_NOTE_BYTES) {
                    "笔记超过 ${MAX_NOTE_BYTES / 1024 / 1024} MiB 上限"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取笔记")
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun writeTextVerified(uri: Uri, content: String) {
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_NOTE_BYTES)
        val output = resolver.openOutputStream(uri, "rwt")
            ?: resolver.openOutputStream(uri, "wt")
            ?: error("无法写入笔记")
        output.use { stream ->
            stream.write(bytes)
            stream.flush()
        }
        check(readText(uri) == content) { "笔记写入后的回读校验失败" }
    }

    private suspend fun copyBoundedAndDigest(source: Uri, destination: Uri): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = resolver.openInputStream(source) ?: error("无法读取所选图片")
        val output = resolver.openOutputStream(destination, "rwt")
            ?: resolver.openOutputStream(destination, "wt")
            ?: error("无法写入媒体")
        input.use { sourceStream ->
            output.use { targetStream ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = sourceStream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_NOTE_MEDIA_BYTES) { "媒体超过 64 MiB 上限" }
                    targetStream.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                targetStream.flush()
            }
        }
        require(total > 0) { "所选图片为空" }
        return total to digest.digest().toHex()
    }

    private fun digestAndSize(uri: Uri, maximum: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maximum)
                digest.update(buffer, 0, count)
            }
        } ?: error("无法回读媒体")
        return total to digest.digest().toHex()
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.trim()?.take(MAX_NOTE_NAME_CHARS)?.takeIf(String::isNotBlank)
        ?: "image"

    private fun uniqueSiblingName(parent: DocumentFile, requested: String): String {
        val existing = parent.listFiles().mapNotNull { it.name?.lowercase(Locale.ROOT) }.toSet()
        if (requested.lowercase(Locale.ROOT) !in existing) return requested
        val extension = requested.substringAfterLast('.', "")
        val stem = requested.removeSuffix(if (extension.isEmpty()) "" else ".$extension")
        var sequence = 2
        while (true) {
            val candidate = "$stem ($sequence)${if (extension.isEmpty()) "" else ".$extension"}"
            if (candidate.lowercase(Locale.ROOT) !in existing) return candidate
            sequence += 1
        }
    }

    private fun relativeDocumentTarget(fromFolderUri: Uri, targetUri: Uri): String? {
        val from = documentPath(fromFolderUri) ?: return null
        val target = documentPath(targetUri) ?: return null
        if (from.first != target.first) return null
        val fromSegments = from.second.split('/').filter(String::isNotBlank)
        val targetSegments = target.second.split('/').filter(String::isNotBlank)
        var common = 0
        while (
            common < fromSegments.size && common < targetSegments.size &&
            fromSegments[common] == targetSegments[common]
        ) common += 1
        return buildList {
            repeat(fromSegments.size - common) { add("..") }
            addAll(targetSegments.drop(common))
        }.joinToString("/").takeIf(String::isNotBlank)
    }

    private fun documentPath(uri: Uri): Pair<String, String>? {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        return documentIdPath(id)
    }

    private fun documentIdPath(id: String): Pair<String, String>? {
        val separator = id.indexOf(':')
        if (separator <= 0) return null
        return id.substring(0, separator) to id.substring(separator + 1).trim('/')
    }

    private fun normalizeMarkdownTarget(raw: String): String? {
        val value = Uri.decode(raw.trim().trim('<', '>'))
            .substringBefore('#')
            .replace('\\', '/')
            .trim()
        if (value.isBlank() || value.startsWith('/') || Uri.parse(value).scheme != null) return null
        return value
    }

    private fun normalizedRelativeTarget(folderPath: String, target: String): List<String>? {
        val result = folderPath.split('/').filter(String::isNotBlank).toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (result.isEmpty()) return null else result.removeAt(result.lastIndex)
                else -> result += segment
            }
        }
        return result
    }

    private fun resolveRelativeFile(root: DocumentFile, path: List<String>): DocumentFile? {
        if (path.isEmpty()) return null
        var current = root
        path.forEachIndexed { index, segment ->
            val child = current.listFiles().firstOrNull {
                it.name.equals(segment, ignoreCase = true)
            } ?: return null
            if (index < path.lastIndex && !child.isDirectory) return null
            current = child
        }
        return current.takeIf(DocumentFile::isFile)
    }

    private fun findFileByNameBounded(root: DocumentFile, name: String): DocumentFile? {
        val queue = ArrayDeque<Pair<DocumentFile, Int>>()
        queue += root to 0
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NOTE_MEDIA_SEARCH_ENTRIES) {
            val (directory, depth) = queue.removeFirst()
            directory.listFiles().forEach { child ->
                visited += 1
                if (child.isFile && child.name.equals(name, ignoreCase = true)) return child
                if (child.isDirectory && depth < MAX_NOTE_MEDIA_SEARCH_DEPTH) {
                    queue += child to depth + 1
                }
            }
        }
        return null
    }

    private companion object {
        const val MAX_NOTE_NAME_CHARS = 240
        const val MAX_NOTE_BYTES = 4 * 1024 * 1024
        const val MAX_NOTE_MEDIA_BYTES = 64L * 1024L * 1024L
        const val MAX_NOTE_FOLDER_ENTRIES = 5_000
        const val MAX_NOTE_PREVIEW_TARGETS = 2_000
        const val MAX_NOTE_MEDIA_SEARCH_ENTRIES = 20_000
        const val MAX_NOTE_MEDIA_SEARCH_DEPTH = 16
    }
}

internal fun normalizeNoteName(raw: String, markdownFile: Boolean): String {
    var value = raw.trim().replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_")
        .trimEnd(' ', '.')
        .take(220)
    if (markdownFile && !value.endsWith(".md", ignoreCase = true)) value += ".md"
    require(value.isNotBlank() && value != "." && value != "..") { "名称不能为空" }
    val stem = value.substringBeforeLast('.').uppercase(Locale.ROOT)
    require(stem !in WINDOWS_RESERVED_NAMES) { "该名称不兼容 Windows/Obsidian" }
    return value
}

private fun normalizeMediaName(raw: String, mime: String): String {
    val extension = raw.substringAfterLast('.', "").lowercase(Locale.ROOT)
        .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        ?: when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
    val stem = raw.substringBeforeLast('.').ifBlank { "image" }
    return normalizeNoteName("$stem.$extension", markdownFile = false)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

private val WINDOWS_RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL"))
    (1..9).forEach { index ->
        add("COM$index")
        add("LPT$index")
    }
}
