package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.repository.NotesRepository

class NotesCloudSyncLocalStore(
    private val notesRepository: NotesRepository,
    private val notesTreeUriProvider: suspend () -> String?,
) : CloudSyncLocalStore {
    override suspend fun list(
        selectedContents: Set<CloudSyncContent>,
        limits: CloudSyncLimits,
    ): List<LocalSyncObject> {
        if (CloudSyncContent.NOTES !in selectedContents) return emptyList()
        val root = notesTreeUriProvider()
            ?: throw CloudSyncConfigurationException("同步笔记前请先选择笔记目录。")
        return notesRepository.snapshotForCloudSync(root, limits.maxObjectBytes, limits.maxObjects)
            .map { it.toLocalObject() }
    }

    override suspend fun read(
        objectInfo: LocalSyncObject,
        maxBytes: Long,
    ): ByteArray {
        check(objectInfo.content == CloudSyncContent.NOTES) { "同步内容类别无效。" }
        return notesRepository.readForCloudSync(objectInfo.toNoteFile(), maxBytes)
    }

    override suspend fun writeRemote(
        key: String,
        bytes: ByteArray,
        contentSha256: String,
        lastModifiedMillis: Long,
        expectedLocalSha256: String?,
        limits: CloudSyncLimits,
    ): LocalWriteResult {
        requireValidSyncKey(key)
        val name = key.substringAfter('/')
        require(name.isNotBlank() && '/' !in name && key.startsWith("${CloudSyncContent.NOTES.remoteDirectory}/"))
        val root = notesTreeUriProvider()
            ?: throw CloudSyncConfigurationException("同步笔记前请先选择笔记目录。")
        return when (
            val result = notesRepository.writeFromCloudSync(
                rootUri = root,
                name = name,
                bytes = bytes,
                expectedSha256 = contentSha256,
                expectedLocalSha256 = expectedLocalSha256,
                maxObjectBytes = limits.maxObjectBytes,
            )
        ) {
            is NotesRepository.NoteCloudSyncWriteResult.Applied ->
                LocalWriteResult.Applied(result.file.toLocalObject())
            is NotesRepository.NoteCloudSyncWriteResult.ConflictCopy ->
                LocalWriteResult.ConflictCopy(
                    existing = result.existing?.toLocalObject() ?: LocalSyncObject(key, CloudSyncContent.NOTES, 0L, 0L, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", "deleted"),
                    copy = result.copy.toLocalObject(),
                )
        }
    }

    private fun NotesRepository.NoteCloudSyncFile.toLocalObject() = LocalSyncObject(
        key = "${CloudSyncContent.NOTES.remoteDirectory}/$name",
        content = CloudSyncContent.NOTES,
        size = size,
        lastModifiedMillis = lastModifiedMillis,
        sha256 = sha256,
        localId = uri,
    )

    private fun LocalSyncObject.toNoteFile() = NotesRepository.NoteCloudSyncFile(
        name = key.substringAfter('/'),
        uri = localId,
        size = size,
        lastModifiedMillis = lastModifiedMillis,
        sha256 = sha256,
    )
}

class CompositeCloudSyncLocalStore(
    delegates: Map<CloudSyncContent, CloudSyncLocalStore>,
) : CloudSyncLocalStore {
    private val delegatesById = delegates

    override suspend fun list(
        selectedContents: Set<CloudSyncContent>,
        limits: CloudSyncLimits,
    ): List<LocalSyncObject> = delegatesById.flatMap { (content, store) ->
        if (content in selectedContents) store.list(setOf(content), limits) else emptyList()
    }

    override suspend fun read(
        objectInfo: LocalSyncObject,
        maxBytes: Long,
    ): ByteArray = delegateFor(objectInfo.content).read(objectInfo, maxBytes)

    override suspend fun writeRemote(
        key: String,
        bytes: ByteArray,
        contentSha256: String,
        lastModifiedMillis: Long,
        expectedLocalSha256: String?,
        limits: CloudSyncLimits,
    ): LocalWriteResult {
        val content = CloudSyncContent.entries.firstOrNull { key.startsWith("${it.remoteDirectory}/") }
            ?: throw CloudSyncException("同步文件类别无效。")
        return delegateFor(content).writeRemote(
            key, bytes, contentSha256, lastModifiedMillis, expectedLocalSha256, limits,
        )
    }

    private fun delegateFor(content: CloudSyncContent): CloudSyncLocalStore =
        delegatesById[content]
            ?: throw CloudSyncConfigurationException("文件同步适配器缺失：${content.name}")
}
