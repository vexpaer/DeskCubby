package com.deskcubby.app.data.sync

import android.content.Context
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.repository.DiaryCloudSyncArea
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.NotesRepository
import com.deskcubby.app.data.repository.ReaderRepository
import com.deskcubby.app.data.repository.VaultRepository
import com.deskcubby.app.data.statistics.UsageDeviceRepository
import com.deskcubby.app.widget.DeskCubbyWidgetProvider
import com.deskcubby.app.widget.requestIndependentCloudWidgetUpdates
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val lastUploadedCount: Int? = null,
    val lastDownloadedCount: Int? = null,
    val lastConflictCount: Int? = null,
    val message: String? = null,
    val error: String? = null,
)

internal data class CloudSyncTransferTotals(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
)

internal fun cloudSyncTransferTotals(runs: List<CloudSyncConfigRun>): CloudSyncTransferTotals =
    CloudSyncTransferTotals(
        uploaded = runs.sumOf { it.result?.uploadedCount ?: 0 },
        downloaded = runs.sumOf { it.result?.downloadedCount ?: 0 },
        conflicts = runs.sumOf { it.result?.conflictCount ?: 0 },
    )

/**
 * Application-facing cloud sync facade.
 *
 * Manual backup JSON never enters this service. Files use SHA-256 three-way reconciliation;
 * structured data is synchronized by the unified record engine. Credentials are hydrated from
 * the device-local Android Keystore store just before a request.
 */
@Singleton
class AppCloudSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryRepository: DiaryFileRepository,
    private val notesRepository: NotesRepository,
    private val settingsRepository: SettingsRepository,
    private val secretStore: CloudSyncSecretStore,
    private val cloudSyncUndoStore: CloudSyncUndoStore,
    usageDeviceRepository: UsageDeviceRepository,
    readerRepository: ReaderRepository,
    agentChatSyncRepository: AgentChatSyncRepository,
    vaultRepository: VaultRepository,
    roomAdapters: RoomRecordSyncAdapters,
    repositoryAdapters: RepositoryRecordSyncAdapters,
) {
    private val runtimePreferences = context.getSharedPreferences(
        RUNTIME_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val recordAdapters = roomAdapters.all() + repositoryAdapters.all()
    private val coordinator = CloudSyncCoordinator(
        context = context,
        diaryRepository = diaryRepository,
        notesRepository = notesRepository,
        settingsProvider = { settingsRepository.settings.first() },
        recordSyncAdapters = recordAdapters,
        cloudSyncUndoStore = cloudSyncUndoStore,
    )
    private val mutableStatus = MutableStateFlow(
        AppCloudSyncStatus(
            lastFinishedAt = runtimePreferences.getLong(KEY_LAST_FINISHED_AT, 0L).takeIf { it > 0L },
            lastUploadedCount = runtimePreferences.getInt(KEY_LAST_UPLOADED_COUNT, -1).takeIf { it >= 0 },
            lastDownloadedCount = runtimePreferences.getInt(KEY_LAST_DOWNLOADED_COUNT, -1).takeIf { it >= 0 },
            lastConflictCount = runtimePreferences.getInt(KEY_LAST_CONFLICT_COUNT, -1).takeIf { it >= 0 },
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
        requestGeneralWidgetUpdate()
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
                mutableStatus.update { it.copy(activeConfigId = configId, progress = progress) }
                requestGeneralWidgetUpdate()
            }
            val failed = runs.count { it.errorMessage != null }
            val finishedAt = runs.mapNotNull { it.result?.finishedAtMillis }
                .maxOrNull()
                ?: System.currentTimeMillis()
            val totals = cloudSyncTransferTotals(runs)
            persistLastRun(finishedAt, totals)
            mutableStatus.value = mutableStatus.value.copy(
                running = false,
                activeConfigId = null,
                progress = null,
                lastFinishedAt = finishedAt,
                lastRuns = runs,
                lastUploadedCount = totals.uploaded,
                lastDownloadedCount = totals.downloaded,
                lastConflictCount = totals.conflicts,
                message = if (failed == 0) {
                    when (mode) {
                        CloudSyncRunMode.NORMAL -> "云端同步完成 / Cloud sync completed"
                        CloudSyncRunMode.FORCE_UPLOAD -> "强制上传完成 / Forced upload completed"
                        CloudSyncRunMode.FORCE_DOWNLOAD -> "强制下载完成 / Forced download completed"
                    }
                } else {
                    "部分云端配置同步失败 / Some cloud sync services failed"
                },
                error = runs.mapNotNull(CloudSyncConfigRun::errorMessage).firstOrNull(),
            )
            requestGeneralWidgetUpdate()
            runs
        } catch (cancelled: CancellationException) {
            mutableStatus.update { it.copy(running = false, activeConfigId = null, progress = null) }
            requestGeneralWidgetUpdate()
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.update {
                it.copy(running = false, activeConfigId = null, progress = null, error = formatCloudSyncError(error))
            }
            requestGeneralWidgetUpdate()
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
        requestGeneralWidgetUpdate()
        return try {
            coordinator.sync(config) { progress ->
                mutableStatus.update { it.copy(progress = progress) }
                requestGeneralWidgetUpdate()
            }.also { result ->
                val totals = cloudSyncTransferTotals(listOf(CloudSyncConfigRun(configId, result)))
                persistLastRun(result.finishedAtMillis, totals)
                mutableStatus.update {
                    it.copy(
                        running = false,
                        activeConfigId = null,
                        progress = null,
                        lastFinishedAt = result.finishedAtMillis,
                        lastRuns = listOf(CloudSyncConfigRun(configId, result)),
                        lastUploadedCount = totals.uploaded,
                        lastDownloadedCount = totals.downloaded,
                        lastConflictCount = totals.conflicts,
                        message = "云端同步完成 / Cloud sync completed",
                    )
                }
                requestGeneralWidgetUpdate()
            }
        } catch (cancelled: CancellationException) {
            mutableStatus.update { it.copy(running = false, activeConfigId = null, progress = null) }
            requestGeneralWidgetUpdate()
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.update {
                it.copy(running = false, activeConfigId = null, progress = null, error = formatCloudSyncError(error))
            }
            requestGeneralWidgetUpdate()
            throw error
        }
    }

    /** Undoes the most recent run's local diary-file changes only. */
    suspend fun undoLastSync(): Int {
        val settings = settingsRepository.settings.first()
        val entries = cloudSyncUndoStore.entries()
        if (entries.isEmpty()) return 0
        var restored = 0
        for (entry in entries) {
            try {
                when {
                    entry.isOverwrite -> {
                        val backupName = entry.backupName ?: continue
                        val bytes = cloudSyncUndoStore.readBackup(backupName) ?: continue
                        val directory = entry.key.substringBefore('/')
                        val name = entry.key.substringAfter('/', "")
                        if (directory != CloudSyncContent.DIARIES.remoteDirectory || name.isBlank() || '/' in name) continue
                        val expectedSha = entry.sha256 ?: sha256(bytes)
                        val currentBytes = readUndoCurrentBytes(entry.uri)
                        diaryRepository.writeFromCloudSync(
                            settings = settings,
                            area = DiaryCloudSyncArea.DIARY,
                            name = name,
                            bytes = bytes,
                            expectedSha256 = expectedSha,
                            expectedLocalSha256 = currentBytes?.let { sha256(it) },
                            maxObjectBytes = CloudSyncLimits().maxObjectBytes,
                        )
                        restored++
                    }
                    entry.isCreate -> {
                        val deleted = diaryRepository.delete(entry.uri, settings)
                        if (deleted) restored++
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One failing entry must not stop the rest of the undo.
            }
            cloudSyncUndoStore.removeEntry(entry)
        }
        requestGeneralWidgetUpdate()
        return restored
    }

    private suspend fun readUndoCurrentBytes(uri: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = android.net.Uri.parse(uri)
                val fd = context.contentResolver.openAssetFileDescriptor(parsed, "r") ?: return@runCatching null
                fd.use {
                    if (it.length > CloudSyncLimits().maxObjectBytes) return@runCatching null
                    it.createInputStream().use { input -> input.readBytes() }
                }
            }.getOrNull()
        }

    private fun requestGeneralWidgetUpdate() {
        DeskCubbyWidgetProvider.requestUpdate(context)
        requestIndependentCloudWidgetUpdates(context)
    }

    private fun persistLastRun(value: Long, totals: CloudSyncTransferTotals) {
        if (value <= 0L) return
        runtimePreferences.edit()
            .putLong(KEY_LAST_FINISHED_AT, value)
            .putInt(KEY_LAST_UPLOADED_COUNT, totals.uploaded.coerceAtLeast(0))
            .putInt(KEY_LAST_DOWNLOADED_COUNT, totals.downloaded.coerceAtLeast(0))
            .putInt(KEY_LAST_CONFLICT_COUNT, totals.conflicts.coerceAtLeast(0))
            .apply()
    }

    private companion object {
        const val RUNTIME_PREFERENCES = "cloud_sync_runtime"
        const val KEY_LAST_FINISHED_AT = "last_finished_at"
        const val KEY_LAST_UPLOADED_COUNT = "last_uploaded_count"
        const val KEY_LAST_DOWNLOADED_COUNT = "last_downloaded_count"
        const val KEY_LAST_CONFLICT_COUNT = "last_conflict_count"
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
                "Force download requires exactly one enabled cloud source; disable the other sync services first.",
            errorCode = "SYNC_FORCE_DOWNLOAD_SOURCE_COUNT",
        )
    }
}
