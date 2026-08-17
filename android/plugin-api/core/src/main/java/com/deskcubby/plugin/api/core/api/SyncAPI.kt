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
    NOTES,
    MEDIA,
    THOUGHTS,
    THOUGHT_CATEGORIES,
    DATE_RECORDS,
    POEMS,
    POETRY_CATEGORIES,
    FAVORITES,
    RSS_SUBSCRIPTIONS,
    GAME_STATES,
    GAME_STATISTICS,
    USAGE_STATISTICS,
    READING_PROGRESS,
    READER_PREFERENCES,
    AGENT_CHATS,
    VAULT,
    GLOBAL_SETTINGS,
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
