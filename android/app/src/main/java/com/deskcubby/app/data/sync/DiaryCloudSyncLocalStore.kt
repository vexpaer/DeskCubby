package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.repository.DiaryCloudSyncArea
import com.deskcubby.app.data.repository.DiaryCloudSyncFile
import com.deskcubby.app.data.repository.DiaryCloudSyncWriteResult
import com.deskcubby.app.data.repository.DiaryFileRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CloudSyncJsonSnapshot(
    val bytes: ByteArray,
    val lastModifiedMillis: Long,
    val localId: String = "current-backup",
) {
    override fun toString(): String =
        "CloudSyncJsonSnapshot(bytes=<redacted:${bytes.size}>, lastModifiedMillis=$lastModifiedMillis)"
}

data class StagedCloudSyncJson(
    val localId: String,
    val lastModifiedMillis: Long,
)

data class CloudSyncUsageSnapshot(
    val key: String,
    val bytes: ByteArray,
    val lastModifiedMillis: Long,
    val localId: String,
) {
    override fun toString(): String =
        "CloudSyncUsageSnapshot(key=$key, bytes=<redacted:${bytes.size}>)"
}

/**
 * JSON downloads are validated and staged for explicit user restore. They are never imported into
 * Room/DataStore merely because a background sync ran.
 */
interface CloudSyncJsonBridge {
    suspend fun snapshot(maxBytes: Long): CloudSyncJsonSnapshot

    suspend fun stageIncoming(
        bytes: ByteArray,
        sha256: String,
        sourceConfigId: String,
    ): StagedCloudSyncJson
}

/** Per-device usage objects are encrypted only by transport and merge automatically by date. */
interface CloudSyncUsageBridge {
    suspend fun snapshots(maxBytes: Long): List<CloudSyncUsageSnapshot>

    suspend fun mergeIncoming(
        key: String,
        bytes: ByteArray,
        sha256: String,
    ): CloudSyncUsageSnapshot
}

class DiaryCloudSyncLocalStore(
    private val diaryRepository: DiaryFileRepository,
    private val settingsProvider: suspend () -> AppSettings,
    private val configId: String,
    private val jsonBridge: CloudSyncJsonBridge? = null,
    private val usageBridge: CloudSyncUsageBridge? = null,
) : CloudSyncLocalStore {
    private val mutex = Mutex()
    private var jsonSnapshot: CloudSyncJsonSnapshot? = null
    private var usageSnapshots: Map<String, CloudSyncUsageSnapshot> = emptyMap()

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
        val result = files.map { it.toLocalObject() }.toMutableList()
        if (CloudSyncContent.JSON_BACKUP in selectedContents) {
            val bridge = jsonBridge ?: throw CloudSyncConfigurationException(
                "JSON 同步尚未连接到应用备份服务。",
            )
            val snapshot = bridge.snapshot(limits.maxObjectBytes)
            if (snapshot.bytes.size.toLong() > limits.maxObjectBytes) {
                throw CloudSyncLimitException("JSON 备份超过单文件同步上限。")
            }
            jsonSnapshot = snapshot
            result += LocalSyncObject(
                key = JSON_SYNC_KEY,
                content = CloudSyncContent.JSON_BACKUP,
                size = snapshot.bytes.size.toLong(),
                lastModifiedMillis = snapshot.lastModifiedMillis.coerceAtLeast(0L),
                sha256 = sha256(snapshot.bytes),
                localId = snapshot.localId,
            )
        } else {
            jsonSnapshot = null
        }
        if (CloudSyncContent.USAGE_STATISTICS in selectedContents) {
            val bridge = usageBridge ?: throw CloudSyncConfigurationException(
                "使用时间同步尚未连接到设备历史服务。",
            )
            val snapshots = bridge.snapshots(limits.maxObjectBytes)
            usageSnapshots = snapshots.associateBy(CloudSyncUsageSnapshot::key)
            result += snapshots.map { snapshot ->
                LocalSyncObject(
                    key = snapshot.key,
                    content = CloudSyncContent.USAGE_STATISTICS,
                    size = snapshot.bytes.size.toLong(),
                    lastModifiedMillis = snapshot.lastModifiedMillis.coerceAtLeast(0L),
                    sha256 = sha256(snapshot.bytes),
                    localId = snapshot.localId,
                )
            }
        } else {
            usageSnapshots = emptyMap()
        }
        if (result.size > limits.maxObjects) {
            throw CloudSyncLimitException("同步文件数量超过上限。")
        }
        result
    }

    override suspend fun read(
        objectInfo: LocalSyncObject,
        maxBytes: Long,
    ): ByteArray = mutex.withLock {
        if (objectInfo.content == CloudSyncContent.JSON_BACKUP) {
            val snapshot = jsonSnapshot
                ?: throw CloudSyncConflictException("JSON 备份快照已失效，请重新同步。")
            if (snapshot.localId != objectInfo.localId ||
                snapshot.bytes.size.toLong() != objectInfo.size ||
                sha256(snapshot.bytes) != objectInfo.sha256
            ) {
                throw CloudSyncConflictException("JSON 备份在同步读取期间发生变化。")
            }
            return@withLock snapshot.bytes.copyOf()
        }
        if (objectInfo.content == CloudSyncContent.USAGE_STATISTICS) {
            val snapshot = usageSnapshots[objectInfo.key]
                ?: throw CloudSyncConflictException("使用时间快照已失效，请重新同步。")
            if (
                snapshot.localId != objectInfo.localId ||
                snapshot.bytes.size.toLong() != objectInfo.size ||
                sha256(snapshot.bytes) != objectInfo.sha256
            ) {
                throw CloudSyncConflictException("使用时间在同步读取期间发生变化。")
            }
            return@withLock snapshot.bytes.copyOf()
        }
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
        if (key.startsWith(USAGE_SYNC_PREFIX)) {
            val bridge = usageBridge ?: throw CloudSyncConfigurationException(
                "使用时间同步尚未连接到设备历史服务。",
            )
            val merged = bridge.mergeIncoming(key, bytes, contentSha256)
            if (merged.bytes.size.toLong() > limits.maxObjectBytes) {
                throw CloudSyncLimitException("合并后的使用时间超过单文件同步上限。")
            }
            usageSnapshots = usageSnapshots + (merged.key to merged)
            return@withLock LocalWriteResult.Applied(
                LocalSyncObject(
                    key = merged.key,
                    content = CloudSyncContent.USAGE_STATISTICS,
                    size = merged.bytes.size.toLong(),
                    lastModifiedMillis = merged.lastModifiedMillis.coerceAtLeast(0L),
                    sha256 = sha256(merged.bytes),
                    localId = merged.localId,
                ),
            )
        }
        if (key == JSON_SYNC_KEY || key == LEGACY_JSON_SYNC_KEY) {
            val bridge = jsonBridge ?: throw CloudSyncConfigurationException(
                "JSON 同步尚未连接到应用备份服务。",
            )
            val current = jsonSnapshot ?: bridge.snapshot(limits.maxObjectBytes).also {
                jsonSnapshot = it
            }
            val currentObject = LocalSyncObject(
                key = JSON_SYNC_KEY,
                content = CloudSyncContent.JSON_BACKUP,
                size = current.bytes.size.toLong(),
                lastModifiedMillis = current.lastModifiedMillis,
                sha256 = sha256(current.bytes),
                localId = current.localId,
            )
            val staged = bridge.stageIncoming(bytes, contentSha256, configId)
            val copyKey = "json/dc.remote-conflict-${contentSha256.take(8)}.json"
            return@withLock LocalWriteResult.ConflictCopy(
                existing = currentObject,
                copy = LocalSyncObject(
                    key = copyKey,
                    content = CloudSyncContent.JSON_BACKUP,
                    size = bytes.size.toLong(),
                    lastModifiedMillis = staged.lastModifiedMillis,
                    sha256 = contentSha256,
                    localId = staged.localId,
                ),
            )
        }

        val (content, area, fileName) = parseDiaryOrMediaKey(key)
        val result = diaryRepository.writeFromCloudSync(
            settings = settingsProvider(),
            area = area,
            name = fileName,
            bytes = bytes,
            expectedSha256 = contentSha256,
            expectedLocalSha256 = expectedLocalSha256,
            maxObjectBytes = limits.maxObjectBytes,
        )
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
            CloudSyncContent.JSON_BACKUP ->
                throw CloudSyncException("JSON 备份不能作为日记文件读取。")
            CloudSyncContent.USAGE_STATISTICS ->
                throw CloudSyncException("使用时间不能作为日记文件读取。")
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
        const val JSON_SYNC_KEY = "json/dc.json"
        const val LEGACY_JSON_SYNC_KEY = "json/DeskCubby.json"
        const val USAGE_SYNC_PREFIX = "usage/v1/"
        const val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
