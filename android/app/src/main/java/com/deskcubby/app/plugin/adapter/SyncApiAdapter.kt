package com.deskcubby.app.plugin.adapter

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent as AppSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection as AppSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType as AppSyncServiceType
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.sync.AppCloudSyncService
import com.deskcubby.app.data.sync.CloudSyncRunResult
import com.deskcubby.plugin.api.core.api.SyncAPI
import com.deskcubby.plugin.api.core.api.SyncBatchItem
import com.deskcubby.plugin.api.core.api.SyncConfiguration
import com.deskcubby.plugin.api.core.api.SyncContent
import com.deskcubby.plugin.api.core.api.SyncDirection
import com.deskcubby.plugin.api.core.api.SyncRun
import com.deskcubby.plugin.api.core.api.SyncServiceType
import com.deskcubby.plugin.api.core.api.SyncStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class SyncApiAdapter @Inject constructor(
    private val service: AppCloudSyncService,
    private val settingsRepository: SettingsRepository,
) : SyncAPI {
    override suspend fun configurations(): List<SyncConfiguration> =
        settingsRepository.settings.first().cloudSyncConfigs.map(CloudSyncConfig::toPluginConfig)

    override fun currentStatus(): SyncStatus = service.status.value.let { status ->
        SyncStatus(
            running = status.running,
            activeConfigurationId = status.activeConfigId,
            completedObjects = status.progress?.completedObjects ?: 0,
            totalObjects = status.progress?.totalObjects ?: 0,
            transferredBytes = status.progress?.transferredBytes ?: 0L,
            lastFinishedAtMillis = status.lastFinishedAt,
            pendingJsonCount = status.pendingJsonCount,
        )
    }

    override suspend fun syncEnabled(): List<SyncBatchItem> = service.syncEnabled().map { item ->
        SyncBatchItem(
            configurationId = item.configId,
            run = item.result?.toPluginRun(),
            errorMessage = item.errorMessage,
        )
    }

    override suspend fun sync(configurationId: String): SyncRun =
        service.syncConfig(configurationId).toPluginRun()
}

private fun CloudSyncConfig.toPluginConfig(): SyncConfiguration = SyncConfiguration(
    id = id,
    name = name,
    enabled = enabled,
    serviceType = when (serviceType) {
        AppSyncServiceType.WEBDAV -> SyncServiceType.WEBDAV
        AppSyncServiceType.S3_COMPATIBLE -> SyncServiceType.S3_COMPATIBLE
    },
    direction = when (direction) {
        AppSyncDirection.UPLOAD_ONLY -> SyncDirection.UPLOAD_ONLY
        AppSyncDirection.TWO_WAY -> SyncDirection.TWO_WAY
    },
    selectedContents = selectedContents.mapTo(linkedSetOf()) { content ->
        when (content) {
            AppSyncContent.DIARIES -> SyncContent.DIARIES
            AppSyncContent.MEDIA -> SyncContent.MEDIA
            AppSyncContent.JSON_BACKUP -> SyncContent.JSON_BACKUP
            AppSyncContent.USAGE_STATISTICS -> SyncContent.USAGE_STATISTICS
            AppSyncContent.READING_PROGRESS -> SyncContent.READING_PROGRESS
            AppSyncContent.AGENT_CHATS -> SyncContent.AGENT_CHATS
        }
    },
)

private fun CloudSyncRunResult.toPluginRun(): SyncRun = SyncRun(
    configurationId = configId,
    startedAtMillis = startedAtMillis,
    finishedAtMillis = finishedAtMillis,
    uploadedCount = uploadedCount,
    downloadedCount = downloadedCount,
    conflictCount = conflictCount,
    transferredBytes = transferredBytes,
)
