package com.deskcubby.app.data.sync

import android.content.Context
import com.deskcubby.app.data.backup.AppBackupRepository
import com.deskcubby.app.data.backup.BackupJsonCodec
import com.deskcubby.app.data.backup.BackupSummary
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.ReaderProgressRecord
import com.deskcubby.app.data.repository.ReaderRepository
import com.deskcubby.app.data.statistics.USAGE_DEVICE_REMOTE_PREFIX
import com.deskcubby.app.data.statistics.UsageDeviceJsonCodec
import com.deskcubby.app.data.statistics.UsageDeviceRecord
import com.deskcubby.app.data.statistics.UsageDeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AppCloudSyncStatus(
    val running: Boolean = false,
    val activeConfigId: String? = null,
    val progress: CloudSyncProgress? = null,
    val lastFinishedAt: Long? = null,
    val lastRuns: List<CloudSyncConfigRun> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val pendingJsonCount: Int = 0,
)

data class PendingCloudSyncJson(
    val fileName: String,
    val receivedAt: Long,
    val size: Long,
)

/** Application-facing cloud sync facade with encrypted credential hydration and JSON staging. */
@Singleton
class AppCloudSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    diaryRepository: DiaryFileRepository,
    private val settingsRepository: SettingsRepository,
    private val backupRepository: AppBackupRepository,
    private val secretStore: CloudSyncSecretStore,
    usageDeviceRepository: UsageDeviceRepository,
    readerRepository: ReaderRepository,
) {
    private val incomingDirectory = File(context.filesDir, INCOMING_DIRECTORY)
    private val runtimePreferences = context.getSharedPreferences(
        RUNTIME_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val coordinator = CloudSyncCoordinator(
        context = context,
        diaryRepository = diaryRepository,
        settingsProvider = { settingsRepository.settings.first() },
        jsonBridge = AppCloudSyncJsonBridge(
            incomingDirectory = incomingDirectory,
            backupRepository = backupRepository,
        ),
        usageBridge = AppCloudSyncUsageBridge(usageDeviceRepository),
        readerProgressBridge = AppCloudSyncReaderProgressBridge(readerRepository),
    )
    private val mutableStatus = MutableStateFlow(
        AppCloudSyncStatus(
            lastFinishedAt = runtimePreferences
                .getLong(KEY_LAST_FINISHED_AT, 0L)
                .takeIf { it > 0L },
            pendingJsonCount = pendingIncomingJson().size,
        ),
    )
    val status: StateFlow<AppCloudSyncStatus> = mutableStatus.asStateFlow()

    suspend fun syncEnabled(
        mode: CloudSyncRunMode = CloudSyncRunMode.NORMAL,
    ): List<CloudSyncConfigRun> {
        mutableStatus.value = mutableStatus.value.copy(
            running = true,
            activeConfigId = null,
            progress = null,
            message = null,
            error = null,
        )
        return try {
            val settings = settingsRepository.settings.first()
            if (!settings.cloudSyncEnabled) {
                throw CloudSyncConfigurationException("请先在设置中开启云端同步。")
            }
            val storedConfigs = settings.cloudSyncConfigs.filter(CloudSyncConfig::enabled)
            if (storedConfigs.isEmpty()) {
                throw CloudSyncConfigurationException("没有已启用的云端同步配置。")
            }
            requireSafeForceDownloadSourceCount(mode, storedConfigs.size)
            val configs = storedConfigs.map(secretStore::hydrate)
            val runs = coordinator.syncEnabled(configs, mode = mode) { configId, progress ->
                mutableStatus.update {
                    it.copy(activeConfigId = configId, progress = progress)
                }
            }
            val failed = runs.count { it.errorMessage != null }
            val finishedAt = runs.mapNotNull { it.result?.finishedAtMillis }
                .maxOrNull()
                ?: System.currentTimeMillis()
            persistLastFinishedAt(finishedAt)
            mutableStatus.value = mutableStatus.value.copy(
                running = false,
                activeConfigId = null,
                progress = null,
                lastFinishedAt = finishedAt,
                lastRuns = runs,
                message = if (failed == 0) {
                    when (mode) {
                        CloudSyncRunMode.NORMAL -> "云端同步完成 / Cloud sync completed"
                        CloudSyncRunMode.FORCE_UPLOAD ->
                            "强制上传完成 / Forced upload completed"
                        CloudSyncRunMode.FORCE_DOWNLOAD ->
                            "强制下载完成 / Forced download completed"
                    }
                } else {
                    "部分云端配置同步失败 / Some cloud sync services failed"
                },
                error = runs.mapNotNull(CloudSyncConfigRun::errorMessage)
                    .firstOrNull(),
                pendingJsonCount = pendingIncomingJson().size,
            )
            runs
        } catch (cancelled: CancellationException) {
            mutableStatus.update { it.copy(running = false, activeConfigId = null, progress = null) }
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.update {
                it.copy(
                    running = false,
                    activeConfigId = null,
                    progress = null,
                    error = formatCloudSyncError(error),
                    pendingJsonCount = pendingIncomingJson().size,
                )
            }
            throw error
        }
    }

    suspend fun syncConfig(configId: String): CloudSyncRunResult {
        val stored = settingsRepository.settings.first().cloudSyncConfigs
            .firstOrNull { it.id == configId }
            ?: throw CloudSyncConfigurationException("同步配置不存在。")
        val config = secretStore.hydrate(stored)
        mutableStatus.value = mutableStatus.value.copy(
            running = true,
            activeConfigId = configId,
            progress = null,
            message = null,
            error = null,
        )
        return try {
            coordinator.sync(config) { progress ->
                mutableStatus.update { it.copy(progress = progress) }
            }.also { result ->
                persistLastFinishedAt(result.finishedAtMillis)
                mutableStatus.update {
                    it.copy(
                        running = false,
                        activeConfigId = null,
                        progress = null,
                        lastFinishedAt = result.finishedAtMillis,
                        lastRuns = listOf(CloudSyncConfigRun(configId, result)),
                        message = "云端同步完成 / Cloud sync completed",
                        pendingJsonCount = pendingIncomingJson().size,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            mutableStatus.update { it.copy(running = false, activeConfigId = null, progress = null) }
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.update {
                it.copy(
                    running = false,
                    activeConfigId = null,
                    progress = null,
                    error = formatCloudSyncError(error),
                    pendingJsonCount = pendingIncomingJson().size,
                )
            }
            throw error
        }
    }

    fun pendingIncomingJson(): List<PendingCloudSyncJson> = runCatching {
        incomingDirectory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(INCOMING_PREFIX) && it.name.endsWith(".json") }
            .sortedByDescending(File::lastModified)
            .map { PendingCloudSyncJson(it.name, it.lastModified(), it.length()) }
    }.getOrDefault(emptyList())

    suspend fun restoreIncomingJson(fileName: String): BackupSummary {
        val file = resolveIncomingFile(fileName)
        val raw = withContext(Dispatchers.IO) {
            require(file.isFile && file.length() in 1..MAX_INCOMING_JSON_BYTES) {
                "待导入的云端 JSON 不存在或大小无效。"
            }
            file.readText(Charsets.UTF_8)
        }
        val summary = backupRepository.importStagedBackupJson(raw)
        // The canonical remote object remains a recovery source. Remove the local staging copy
        // after a successful, user-confirmed import so stale files cannot exhaust the staging cap.
        withContext(Dispatchers.IO) {
            runCatching { file.delete() }
        }
        mutableStatus.update {
            it.copy(pendingJsonCount = pendingIncomingJson().size)
        }
        return summary
    }

    private fun resolveIncomingFile(fileName: String): File {
        require(fileName == File(fileName).name && fileName.startsWith(INCOMING_PREFIX)) {
            "待导入文件名无效。"
        }
        val file = File(incomingDirectory, fileName)
        require(file.parentFile?.canonicalFile == incomingDirectory.canonicalFile) {
            "待导入文件位置无效。"
        }
        return file
    }

    private fun persistLastFinishedAt(value: Long) {
        if (value <= 0L) return
        runtimePreferences.edit().putLong(KEY_LAST_FINISHED_AT, value).apply()
    }

    private companion object {
        const val INCOMING_DIRECTORY = "cloud-sync-incoming"
        const val INCOMING_PREFIX = "DeskCubby-incoming-"
        const val MAX_INCOMING_JSON_BYTES = BackupJsonCodec.MAX_JSON_BYTES.toLong()
        const val RUNTIME_PREFERENCES = "cloud_sync_runtime"
        const val KEY_LAST_FINISHED_AT = "last_finished_at"
    }
}

internal fun requireSafeForceDownloadSourceCount(
    mode: CloudSyncRunMode,
    enabledSourceCount: Int,
) {
    require(enabledSourceCount >= 0)
    if (mode == CloudSyncRunMode.FORCE_DOWNLOAD && enabledSourceCount != 1) {
        throw CloudSyncConfigurationException(
            "强制下载只能使用一个已启用的云端来源；请先停用其他同步服务。 / " +
                "Force download requires exactly one enabled cloud source; disable the other " +
                "sync services first.",
            errorCode = "SYNC_FORCE_DOWNLOAD_SOURCE_COUNT",
        )
    }
}

private class AppCloudSyncJsonBridge(
    private val incomingDirectory: File,
    private val backupRepository: AppBackupRepository,
) : CloudSyncJsonBridge {
    override suspend fun snapshot(maxBytes: Long): CloudSyncJsonSnapshot =
        withContext(Dispatchers.IO) {
            val generated = backupRepository.encodeCurrentBackupForSync()
            // exportedAt is transport metadata, not application content. Canonicalizing it to
            // zero makes equal backups converge to identical hashes across devices and restarts.
            val bytes = canonicalizeCloudSyncBackupJson(generated)
            if (bytes.size.toLong() > maxBytes || bytes.size > MAX_JSON_BYTES) {
                throw CloudSyncLimitException("JSON 备份超过同步大小上限。")
            }
            CloudSyncJsonSnapshot(
                bytes = bytes,
                lastModifiedMillis = 0L,
            )
        }

    override suspend fun stageIncoming(
        bytes: ByteArray,
        sha256: String,
        sourceConfigId: String,
    ): StagedCloudSyncJson = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_JSON_BYTES || com.deskcubby.app.data.sync.sha256(bytes) != sha256) {
            throw CloudSyncConflictException("远端 JSON 校验失败，未保留待导入副本。")
        }
        BackupJsonCodec.decode(bytes.toString(Charsets.UTF_8))
        if (!incomingDirectory.exists() &&
            !incomingDirectory.mkdirs() && !incomingDirectory.isDirectory
        ) {
            throw CloudSyncException("无法创建云端 JSON 待导入目录。")
        }
        val sourceHash = com.deskcubby.app.data.sync.sha256(
            sourceConfigId.toByteArray(Charsets.UTF_8),
        ).take(8)
        val duplicatePrefix = "DeskCubby-incoming-$sourceHash-${sha256.take(8)}-"
        incomingDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter {
                it.isFile && it.name.startsWith(duplicatePrefix) && it.name.endsWith(".json") &&
                    it.length() == bytes.size.toLong()
            }
            .firstOrNull { candidate ->
                runCatching {
                    com.deskcubby.app.data.sync.sha256(candidate.readBytes()) == sha256
                }.getOrDefault(false)
            }
            ?.let { existing ->
                return@withContext StagedCloudSyncJson(
                    localId = existing.name,
                    lastModifiedMillis = existing.lastModified().coerceAtLeast(0L),
                )
            }
        if (incomingDirectory.listFiles().orEmpty().count(File::isFile) >= MAX_PENDING_FILES) {
            throw CloudSyncLimitException("待处理的云端 JSON 已达到上限，请先在同步设置中导入。")
        }
        val now = System.currentTimeMillis()
        val target = File(
            incomingDirectory,
            "$duplicatePrefix$now.json",
        )
        val pending = File(incomingDirectory, "${target.name}.pending")
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!pending.readBytes().contentEquals(bytes)) {
                throw CloudSyncException("云端 JSON 写入后校验失败。")
            }
            if (!pending.renameTo(target)) {
                FileOutputStream(target).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (!target.readBytes().contentEquals(bytes)) {
                    throw CloudSyncException("云端 JSON 提交后校验失败。")
                }
                pending.delete()
            }
            StagedCloudSyncJson(
                localId = target.name,
                lastModifiedMillis = target.lastModified().coerceAtLeast(now),
            )
        } catch (error: Exception) {
            pending.delete()
            if (target.exists() && !target.readBytes().contentEquals(bytes)) target.delete()
            throw error
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = BackupJsonCodec.MAX_JSON_BYTES
        const val MAX_PENDING_FILES = 100
    }
}

private class AppCloudSyncUsageBridge(
    private val repository: UsageDeviceRepository,
) : CloudSyncUsageBridge {
    override suspend fun snapshots(maxBytes: Long): List<CloudSyncUsageSnapshot> =
        repository.snapshotAll().map { record ->
            record.toSnapshot(maxBytes)
        }

    override suspend fun mergeIncoming(
        key: String,
        bytes: ByteArray,
        sha256: String,
    ): CloudSyncUsageSnapshot {
        if (
            bytes.isEmpty() ||
            bytes.size.toLong() > CloudSyncLimits().maxObjectBytes ||
            com.deskcubby.app.data.sync.sha256(bytes) != sha256
        ) {
            throw CloudSyncConflictException("远端使用时间校验失败。")
        }
        val record = try {
            UsageDeviceJsonCodec.decode(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw CloudSyncConflictException("远端使用时间格式无效。")
        }
        val expectedKey = "$USAGE_DEVICE_REMOTE_PREFIX${record.deviceId}.json"
        if (key != expectedKey) {
            throw CloudSyncConflictException("远端使用时间设备标识与路径不一致。")
        }
        return repository.mergeIncoming(record).toSnapshot(CloudSyncLimits().maxObjectBytes)
    }

    private fun UsageDeviceRecord.toSnapshot(maxBytes: Long): CloudSyncUsageSnapshot {
        val bytes = UsageDeviceJsonCodec.encode(this).toByteArray(Charsets.UTF_8)
        if (bytes.size.toLong() > maxBytes) {
            throw CloudSyncLimitException("使用时间设备历史超过单文件同步上限。")
        }
        return CloudSyncUsageSnapshot(
            key = "$USAGE_DEVICE_REMOTE_PREFIX$deviceId.json",
            bytes = bytes,
            lastModifiedMillis = updatedAtEpochMillis,
            localId = deviceId,
        )
    }
}

private class AppCloudSyncReaderProgressBridge(
    private val repository: ReaderRepository,
) : CloudSyncReaderProgressBridge {
    private val mutex = Mutex()

    override suspend fun snapshot(maxBytes: Long): CloudSyncReaderProgressSnapshot =
        mutex.withLock {
            repository.exportProgressRecords().toSnapshot(maxBytes)
        }

    override suspend fun mergeIncoming(
        bytes: ByteArray,
        sha256: String,
        maxBytes: Long,
    ): CloudSyncReaderProgressSnapshot = mutex.withLock {
        if (
            bytes.isEmpty() ||
            bytes.size > ReaderProgressJsonCodec.MAX_JSON_BYTES ||
            bytes.size.toLong() > maxBytes ||
            com.deskcubby.app.data.sync.sha256(bytes) != sha256
        ) {
            throw CloudSyncConflictException("远端阅读进度校验失败。")
        }
        val incoming = try {
            ReaderProgressJsonCodec.decode(bytes)
        } catch (_: Exception) {
            throw CloudSyncConflictException("远端阅读进度格式无效。")
        }
        val rollback = repository.exportProgressRecords()
        try {
            repository.importProgressRecords(incoming)
            repository.exportProgressRecords().toSnapshot(maxBytes)
        } catch (cancelled: CancellationException) {
            rollbackOrThrow(rollback, cancelled)
            throw cancelled
        } catch (error: Exception) {
            rollbackOrThrow(rollback, error)
            throw error
        }
    }

    private suspend fun rollbackOrThrow(
        records: List<ReaderProgressRecord>,
        original: Throwable,
    ) {
        try {
            withContext(NonCancellable) {
                repository.replaceProgressRecordsForRollback(records)
            }
        } catch (rollbackError: Exception) {
            original.addSuppressed(rollbackError)
            throw CloudSyncException(
                "阅读进度合并失败，且无法恢复同步前快照。",
                original,
            )
        }
    }

    private fun List<ReaderProgressRecord>.toSnapshot(
        maxBytes: Long,
    ): CloudSyncReaderProgressSnapshot {
        val bytes = ReaderProgressJsonCodec.encode(this)
        if (bytes.size.toLong() > maxBytes) {
            throw CloudSyncLimitException("阅读进度超过单文件同步上限。")
        }
        return CloudSyncReaderProgressSnapshot(
            bytes = bytes,
            lastModifiedMillis = maxOfOrNull(ReaderProgressRecord::updatedAt) ?: 0L,
        )
    }
}

internal fun canonicalizeCloudSyncBackupJson(generated: ByteArray): ByteArray {
    val decoded = BackupJsonCodec.decode(generated.toString(Charsets.UTF_8))
    return BackupJsonCodec.encode(decoded.copy(exportedAt = 0L))
        .toByteArray(Charsets.UTF_8)
}
