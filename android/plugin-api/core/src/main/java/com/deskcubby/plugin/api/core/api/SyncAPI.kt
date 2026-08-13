package com.deskcubby.plugin.api.core.api

interface SyncAPI {
    suspend fun configurations(): List<SyncConfiguration>

    fun currentStatus(): SyncStatus

    suspend fun syncEnabled(): List<SyncBatchItem>

    suspend fun sync(configurationId: String): SyncRun
}

enum class SyncServiceType {
    WEBDAV,
    S3_COMPATIBLE,
}

enum class SyncDirection {
    UPLOAD_ONLY,
    TWO_WAY,
}

enum class SyncContent {
    DIARIES,
    MEDIA,
    JSON_BACKUP,
    USAGE_STATISTICS,
    READING_PROGRESS,
    AGENT_CHATS,
}

data class SyncConfiguration(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val serviceType: SyncServiceType,
    val direction: SyncDirection,
    val selectedContents: Set<SyncContent>,
)

data class SyncStatus(
    val running: Boolean,
    val activeConfigurationId: String?,
    val completedObjects: Int,
    val totalObjects: Int,
    val transferredBytes: Long,
    val lastFinishedAtMillis: Long?,
    val pendingJsonCount: Int,
)

data class SyncRun(
    val configurationId: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val uploadedCount: Int,
    val downloadedCount: Int,
    val conflictCount: Int,
    val transferredBytes: Long,
)

data class SyncBatchItem(
    val configurationId: String,
    val run: SyncRun? = null,
    val errorMessage: String? = null,
)
