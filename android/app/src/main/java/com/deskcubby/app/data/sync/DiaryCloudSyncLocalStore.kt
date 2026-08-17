package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.repository.DiaryTextUtils
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.repository.DiaryCloudSyncArea
import com.deskcubby.app.data.repository.DiaryCloudSyncFile
import com.deskcubby.app.data.repository.DiaryCloudSyncWriteResult
import com.deskcubby.app.data.repository.DiaryFileRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DiaryCloudSyncLocalStore(
    private val diaryRepository: DiaryFileRepository,
    private val settingsProvider: suspend () -> AppSettings,
    private val cloudSyncUndoStore: CloudSyncUndoStore? = null,
) : CloudSyncLocalStore {
    /** Legacy test/constructor compatibility; JSON bridges are intentionally ignored. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        diaryRepository: DiaryFileRepository,
        settingsProvider: suspend () -> AppSettings,
        configId: String = "",
        jsonBridge: CloudSyncJsonBridge? = null,
        usageBridge: CloudSyncUsageBridge? = null,
        readerProgressBridge: CloudSyncReaderProgressBridge? = null,
        agentChatBridge: CloudSyncAgentChatBridge? = null,
        cloudSyncUndoStore: CloudSyncUndoStore? = null,
    ) : this(diaryRepository, settingsProvider, cloudSyncUndoStore)
    private val mutex = Mutex()
    // Key -> scanned file, kept so the undo store can snapshot the exact local bytes that a
    // downloaded diary file is about to replace.
    private var lastListedDiaryFiles: Map<String, DiaryCloudSyncFile> = emptyMap()

    override suspend fun list(
        selectedContents: Set<CloudSyncContent>,
        limits: CloudSyncLimits,
    ): List<LocalSyncObject> = mutex.withLock {
        val settings = settingsProvider()
        val areas = buildSet {
            if (CloudSyncContent.DIARIES in selectedContents) add(DiaryCloudSyncArea.DIARY)
            if (CloudSyncContent.MEDIA in selectedContents) add(DiaryCloudSyncArea.MEDIA)
        }
        val files = diaryRepository.snapshotForCloudSync(
            settings = settings,
            areas = areas,
            maxObjectBytes = limits.maxObjectBytes,
            maxObjects = limits.maxObjects,
        )
        lastListedDiaryFiles = files.associateBy { file -> file.toLocalObject().key }
        val result = files.map { it.toLocalObject() }.toMutableList()
        if (result.size > limits.maxObjects) {
            throw CloudSyncLimitException("同步文件数量超过上限。")
        }
        result
    }

    override suspend fun read(
        objectInfo: LocalSyncObject,
        maxBytes: Long,
    ): ByteArray = mutex.withLock {
        diaryRepository.readForCloudSync(

            file = objectInfo.toDiaryFile(),
            maxObjectBytes = maxBytes,
        )
    }

    override suspend fun writeRemote(
        key: String,
        bytes: ByteArray,
        contentSha256: String,
        lastModifiedMillis: Long,
        expectedLocalSha256: String?,
        limits: CloudSyncLimits,
    ): LocalWriteResult = mutex.withLock {
        requireValidSyncKey(key)
        if (bytes.size.toLong() > limits.maxObjectBytes || sha256(bytes) != contentSha256) {
            throw CloudSyncConflictException("远端文件校验失败，未写入本地。")
        }
        val (content, area, fileName) = parseDiaryOrMediaKey(key)
        // One-shot undo snapshot: remember the exact local bytes a downloaded diary file is about
        // to replace (and files the run creates) so "撤回一次" can restore them afterwards.
        val undoStore = cloudSyncUndoStore
        val scannedBefore = if (undoStore != null && area == DiaryCloudSyncArea.DIARY) {
            lastListedDiaryFiles[key]
        } else {
            null
        }
        val previousBytes: ByteArray? = if (undoStore != null && scannedBefore != null) {
            runCatching {
                diaryRepository.readForCloudSync(scannedBefore, limits.maxObjectBytes)
            }.getOrNull()
        } else {
            null
        }
        val result = diaryRepository.writeFromCloudSync(
            settings = settingsProvider(),
            area = area,
            name = fileName,
            bytes = bytes,
            expectedSha256 = contentSha256,
            expectedLocalSha256 = expectedLocalSha256,
            maxObjectBytes = limits.maxObjectBytes,
        )
        if (undoStore != null && area == DiaryCloudSyncArea.DIARY) {
            when (result) {
                is DiaryCloudSyncWriteResult.Applied -> {
                    if (previousBytes != null && scannedBefore != null) {
                        runCatching {
                            undoStore.captureBeforeOverwrite(
                                key = key,
                                uri = scannedBefore.uri,
                                bytes = previousBytes,
                                sha256 = DiaryTextUtils.sha256(previousBytes),
                            )
                        }
                    } else {
                        runCatching { undoStore.captureCreated(key, result.file.uri) }
                    }
                }
                is DiaryCloudSyncWriteResult.ConflictCopy -> Unit
            }
        }
        if (area == DiaryCloudSyncArea.DIARY) {
            // A durable file write is the source of truth; rebuild Room only afterwards.
            diaryRepository.scan(settingsProvider())
        }
        when (result) {
            is DiaryCloudSyncWriteResult.Applied ->
                LocalWriteResult.Applied(result.file.toLocalObject())
            is DiaryCloudSyncWriteResult.ConflictCopy -> {
                val existing = result.existing?.toLocalObject()
                    ?: LocalSyncObject(
                        key = key,
                        content = content,
                        size = 0L,
                        lastModifiedMillis = 0L,
                        sha256 = EMPTY_SHA256,
                        localId = "deleted-during-sync",
                    )
                LocalWriteResult.ConflictCopy(existing, result.copy.toLocalObject())
            }
        }
    }

    private fun DiaryCloudSyncFile.toLocalObject(): LocalSyncObject {
        val content = when (area) {
            DiaryCloudSyncArea.DIARY -> CloudSyncContent.DIARIES
            DiaryCloudSyncArea.MEDIA -> CloudSyncContent.MEDIA
        }
        return LocalSyncObject(
            key = "${content.remoteDirectory}/$name",
            content = content,
            size = size,
            lastModifiedMillis = lastModifiedMillis,
            sha256 = sha256,
            localId = uri,
        )
    }

    private fun LocalSyncObject.toDiaryFile(): DiaryCloudSyncFile {
        val area = when (content) {
            CloudSyncContent.DIARIES -> DiaryCloudSyncArea.DIARY
            CloudSyncContent.MEDIA -> DiaryCloudSyncArea.MEDIA
            else -> throw CloudSyncException("该内容不能作为日记/媒体文件读取。")
        }
        return DiaryCloudSyncFile(
            area = area,
            name = key.substringAfter('/'),
            uri = localId,
            mimeType = "",
            size = size,
            lastModifiedMillis = lastModifiedMillis,
            sha256 = sha256,
        )
    }

    private fun parseDiaryOrMediaKey(
        key: String,
    ): Triple<CloudSyncContent, DiaryCloudSyncArea, String> {
        val directory = key.substringBefore('/')
        val name = key.substringAfter('/', "")
        if (name.isBlank() || '/' in name) throw CloudSyncException("同步文件路径无效。")
        return when (directory) {
            CloudSyncContent.DIARIES.remoteDirectory -> Triple(
                CloudSyncContent.DIARIES,
                DiaryCloudSyncArea.DIARY,
                name,
            )
            CloudSyncContent.MEDIA.remoteDirectory -> Triple(
                CloudSyncContent.MEDIA,
                DiaryCloudSyncArea.MEDIA,
                name,
            )
            else -> throw CloudSyncException("同步文件类别无效。")
        }
    }

    private companion object {
        val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
