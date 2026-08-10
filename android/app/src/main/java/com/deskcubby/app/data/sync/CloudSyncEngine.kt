package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class CloudSyncItemOutcome {
    UNCHANGED,
    UPLOADED,
    DOWNLOADED,
    CONFLICT_COPY_SAVED,
    REMOTE_CHANGE_SKIPPED,
}

/**
 * Explicit reconciliation policy for a user-initiated sync run.
 *
 * Forced runs intentionally choose one side for objects that exist on both sides, but they never
 * propagate deletions. Local snapshot checks remain strict; remote transports send provider
 * conditions when supported, while S3 compatibility still verifies manifest/payload content.
 */
enum class CloudSyncRunMode {
    NORMAL,
    FORCE_UPLOAD,
    FORCE_DOWNLOAD,
}

data class CloudSyncItemReport(
    val key: String,
    val outcome: CloudSyncItemOutcome,
)

data class CloudSyncProgress(
    val completedObjects: Int,
    val totalObjects: Int,
    val transferredBytes: Long,
    val currentKey: String?,
)

data class CloudSyncRunResult(
    val configId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val reports: List<CloudSyncItemReport>,
    val transferredBytes: Long,
) {
    val uploadedCount: Int
        get() = reports.count { it.outcome == CloudSyncItemOutcome.UPLOADED }
    val downloadedCount: Int
        get() = reports.count { it.outcome == CloudSyncItemOutcome.DOWNLOADED }
    val conflictCount: Int
        get() = reports.count { it.outcome == CloudSyncItemOutcome.CONFLICT_COPY_SAVED }
}

class CloudSyncEngine(
    private val localStore: CloudSyncLocalStore,
    private val remoteStoreFactory: CloudSyncRemoteStoreFactory,
    private val stateStore: CloudSyncStateStore,
) {
    private val runMutex = Mutex()

    suspend fun sync(
        config: CloudSyncConfig,
        limits: CloudSyncLimits = CloudSyncLimits(),
        mode: CloudSyncRunMode = CloudSyncRunMode.NORMAL,
        onProgress: (CloudSyncProgress) -> Unit = {},
    ): CloudSyncRunResult = runMutex.withLock {
        val startedAt = System.currentTimeMillis()
        val validated = config.validateForSync()
        try {
            withTimeout(limits.overallTimeoutMillis) {
                syncWithinTimeout(validated, limits, startedAt, mode, onProgress)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw CloudSyncException("同步超时，未完成的文件不会被提交。", timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun syncWithinTimeout(
        validated: ValidatedCloudSyncConfig,
        limits: CloudSyncLimits,
        startedAt: Long,
        mode: CloudSyncRunMode,
        onProgress: (CloudSyncProgress) -> Unit,
    ): CloudSyncRunResult {
        val config = validated.source
        val budget = TransferBudget(limits.maxTransferredBytes)
        val remoteStore = remoteStoreFactory.create(config, limits, budget)
        val localObjects = localStore.list(config.selectedContents, limits)
        val prefixes = config.selectedContents.map { "${it.remoteDirectory}/" }.toSet()
        val remoteObjects = remoteStore.list(prefixes)
        validateInventory(localObjects, remoteObjects, prefixes, limits)

        val localByKey = localObjects.associateBy(LocalSyncObject::key)
        val remoteByKey = remoteObjects.associateBy(RemoteSyncObject::key)
        val allKeys = (localByKey.keys + remoteByKey.keys).sorted()
        val oldState = stateStore.load(config.id)
            ?.takeIf { it.scopeFingerprint == validated.scopeFingerprint }
        val updatedBases = oldState?.hashesByKey.orEmpty().toMutableMap()
        updatedBases.keys.removeAll { key ->
            prefixes.any(key::startsWith) && key !in allKeys
        }
        val reports = ArrayList<CloudSyncItemReport>(allKeys.size)
        onProgress(CloudSyncProgress(0, allKeys.size, budget.used, null))
        allKeys.forEachIndexed { index, key ->
            val local = localByKey[key]
            val remote = remoteByKey[key]
            val baseHash = oldState?.hashesByKey?.get(key)
            val report = reconcileOne(
                config = config,
                local = local,
                remote = remote,
                baseHash = baseHash,
                remoteStore = remoteStore,
                limits = limits,
                mode = mode,
            )
            reports += report
            when (report.outcome) {
                CloudSyncItemOutcome.UNCHANGED,
                CloudSyncItemOutcome.UPLOADED,
                CloudSyncItemOutcome.DOWNLOADED,
                -> updatedBases[key] = when (report.outcome) {
                    CloudSyncItemOutcome.DOWNLOADED -> remote?.sha256
                    else -> local?.sha256 ?: remote?.sha256
                } ?: error("Successful sync item has no hash")

                CloudSyncItemOutcome.CONFLICT_COPY_SAVED,
                CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED,
                -> Unit
            }
            onProgress(
                CloudSyncProgress(
                    completedObjects = index + 1,
                    totalObjects = allKeys.size,
                    transferredBytes = budget.used,
                    currentKey = key,
                ),
            )
        }
        stateStore.save(
            config.id,
            CloudSyncBaseState(
                scopeFingerprint = validated.scopeFingerprint,
                hashesByKey = updatedBases,
            ),
        )
        return CloudSyncRunResult(
            configId = config.id,
            startedAtMillis = startedAt,
            finishedAtMillis = System.currentTimeMillis(),
            reports = reports,
            transferredBytes = budget.used,
        )
    }

    private suspend fun reconcileOne(
        config: CloudSyncConfig,
        local: LocalSyncObject?,
        remote: RemoteSyncObject?,
        baseHash: String?,
        remoteStore: CloudSyncRemoteStore,
        limits: CloudSyncLimits,
        mode: CloudSyncRunMode,
    ): CloudSyncItemReport {
        val key = local?.key ?: checkNotNull(remote).key
        if (local == null) {
            if (
                mode == CloudSyncRunMode.FORCE_UPLOAD ||
                (mode == CloudSyncRunMode.NORMAL &&
                    config.direction == CloudSyncDirection.UPLOAD_ONLY)
            ) {
                return CloudSyncItemReport(key, CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
            }
            val remoteObject = checkNotNull(remote)
            val bytes = readRemote(remoteStore, remoteObject, limits)
            return when (
                localStore.writeRemote(
                    key = key,
                    bytes = bytes,
                    contentSha256 = remoteObject.sha256,
                    lastModifiedMillis = remoteObject.lastModifiedMillis,
                    expectedLocalSha256 = null,
                    limits = limits,
                )
            ) {
                is LocalWriteResult.Applied ->
                    CloudSyncItemReport(key, CloudSyncItemOutcome.DOWNLOADED)
                is LocalWriteResult.ConflictCopy ->
                    CloudSyncItemReport(key, CloudSyncItemOutcome.CONFLICT_COPY_SAVED)
            }
        }
        if (remote == null) {
            if (mode == CloudSyncRunMode.FORCE_DOWNLOAD) {
                return CloudSyncItemReport(key, CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
            }
            val bytes = readLocal(local, limits)
            remoteStore.write(
                key,
                bytes,
                local.sha256,
                local.lastModifiedMillis,
                expectedRemoteVersion = null,
            )
            return CloudSyncItemReport(key, CloudSyncItemOutcome.UPLOADED)
        }
        if (local.sha256 == remote.sha256) {
            return CloudSyncItemReport(key, CloudSyncItemOutcome.UNCHANGED)
        }

        if (mode == CloudSyncRunMode.FORCE_UPLOAD) {
            val bytes = readLocal(local, limits)
            remoteStore.write(
                key,
                bytes,
                local.sha256,
                local.lastModifiedMillis,
                expectedRemoteVersion = remote.version,
            )
            return CloudSyncItemReport(key, CloudSyncItemOutcome.UPLOADED)
        }
        if (mode == CloudSyncRunMode.FORCE_DOWNLOAD) {
            val bytes = readRemote(remoteStore, remote, limits)
            return when (
                localStore.writeRemote(
                    key = key,
                    bytes = bytes,
                    contentSha256 = remote.sha256,
                    lastModifiedMillis = remote.lastModifiedMillis,
                    expectedLocalSha256 = local.sha256,
                    limits = limits,
                )
            ) {
                is LocalWriteResult.Applied ->
                    CloudSyncItemReport(key, CloudSyncItemOutcome.DOWNLOADED)
                is LocalWriteResult.ConflictCopy ->
                    CloudSyncItemReport(key, CloudSyncItemOutcome.CONFLICT_COPY_SAVED)
            }
        }

        val localChanged = baseHash == null || local.sha256 != baseHash
        val remoteChanged = baseHash == null || remote.sha256 != baseHash
        if (localChanged && !remoteChanged) {
            val bytes = readLocal(local, limits)
            remoteStore.write(
                key,
                bytes,
                local.sha256,
                local.lastModifiedMillis,
                expectedRemoteVersion = remote.version,
            )
            return CloudSyncItemReport(key, CloudSyncItemOutcome.UPLOADED)
        }
        if (!localChanged && remoteChanged) {
            if (config.direction == CloudSyncDirection.UPLOAD_ONLY) {
                return CloudSyncItemReport(key, CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
            }
            val bytes = readRemote(remoteStore, remote, limits)
            return when (
                localStore.writeRemote(
                    key,
                    bytes,
                    remote.sha256,
                    remote.lastModifiedMillis,
                    expectedLocalSha256 = local.sha256,
                    limits = limits,
                )
            ) {
                is LocalWriteResult.Applied ->
                    CloudSyncItemReport(key, CloudSyncItemOutcome.DOWNLOADED)
                is LocalWriteResult.ConflictCopy ->
                    CloudSyncItemReport(key, CloudSyncItemOutcome.CONFLICT_COPY_SAVED)
            }
        }

        if (config.direction == CloudSyncDirection.UPLOAD_ONLY) {
            return CloudSyncItemReport(key, CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED)
        }
        val bytes = readRemote(remoteStore, remote, limits)
        return when (
            localStore.writeRemote(
                key,
                bytes,
                remote.sha256,
                remote.lastModifiedMillis,
                // Null means "must be absent"; because local exists this deliberately preserves
                // it and asks the SAF store for a deterministic remote-conflict copy.
                expectedLocalSha256 = null,
                limits = limits,
            )
        ) {
            is LocalWriteResult.Applied ->
                CloudSyncItemReport(key, CloudSyncItemOutcome.DOWNLOADED)
            is LocalWriteResult.ConflictCopy ->
                CloudSyncItemReport(key, CloudSyncItemOutcome.CONFLICT_COPY_SAVED)
        }
    }

    private suspend fun readLocal(
        objectInfo: LocalSyncObject,
        limits: CloudSyncLimits,
    ): ByteArray {
        requireObjectWithinLimit(objectInfo.size, limits)
        return localStore.read(objectInfo, limits.maxObjectBytes).also { bytes ->
            if (bytes.size.toLong() != objectInfo.size || sha256(bytes) != objectInfo.sha256) {
                throw CloudSyncConflictException("本地文件在同步读取期间发生变化，请重新同步。")
            }
        }
    }

    private suspend fun readRemote(
        store: CloudSyncRemoteStore,
        objectInfo: RemoteSyncObject,
        limits: CloudSyncLimits,
    ): ByteArray {
        requireObjectWithinLimit(objectInfo.size, limits)
        return store.read(objectInfo, limits.maxObjectBytes).also { bytes ->
            if (bytes.size.toLong() != objectInfo.size || sha256(bytes) != objectInfo.sha256) {
                throw CloudSyncConflictException("云端文件在同步读取期间发生变化，请重新同步。")
            }
        }
    }

    private fun validateInventory(
        local: List<LocalSyncObject>,
        remote: List<RemoteSyncObject>,
        prefixes: Set<String>,
        limits: CloudSyncLimits,
    ) {
        if (local.size > limits.maxObjects || remote.size > limits.maxObjects ||
            local.map(LocalSyncObject::key).distinct().size != local.size ||
            remote.map(RemoteSyncObject::key).distinct().size != remote.size
        ) {
            throw CloudSyncLimitException("同步文件数量超过上限或包含重复路径。")
        }
        local.forEach { item ->
            requireInventoryItem(item.key, item.size, item.sha256, prefixes, limits)
        }
        remote.forEach { item ->
            requireInventoryItem(item.key, item.size, item.sha256, prefixes, limits)
            if (item.version.isBlank()) throw CloudSyncException("云端同步版本无效。")
        }
        (local.asSequence().map(LocalSyncObject::key) +
            remote.asSequence().map(RemoteSyncObject::key))
            .filter { it.startsWith("${CloudSyncContent.READING_PROGRESS.remoteDirectory}/") }
            .forEach { key ->
                if (key != READING_PROGRESS_SYNC_KEY) {
                    throw CloudSyncException("阅读进度同步清单包含无效路径。")
                }
            }
    }

    private fun requireInventoryItem(
        key: String,
        size: Long,
        hash: String,
        prefixes: Set<String>,
        limits: CloudSyncLimits,
    ) {
        requireValidSyncKey(key)
        if (prefixes.none(key::startsWith) || !SHA256_REGEX.matches(hash)) {
            throw CloudSyncException("同步清单包含未选择或无效的文件。")
        }
        requireObjectWithinLimit(size, limits)
    }

    private fun requireObjectWithinLimit(size: Long, limits: CloudSyncLimits) {
        if (size !in 0..limits.maxObjectBytes) {
            throw CloudSyncLimitException("文件超过单文件同步上限。")
        }
    }

    private companion object {
        val SHA256_REGEX = Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE)
        const val READING_PROGRESS_SYNC_KEY = "reading/v1/progress.json"
    }
}
