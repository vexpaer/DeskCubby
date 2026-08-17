package com.deskcubby.app.data.sync

import com.deskcubby.app.data.backup.BackupJsonCodec

/**
 * Compatibility-only definitions retained so existing instrumentation tests and old staging
 * clients still compile. New runtime code never uses these types or [canonicalizeCloudSyncBackupJson];
 * manual backup JSON and record sync are completely separate.
 */
data class CloudSyncJsonSnapshot(
    val bytes: ByteArray,
    val lastModifiedMillis: Long,
    val localId: String = "current-backup",
)

data class StagedCloudSyncJson(
    val localId: String,
    val lastModifiedMillis: Long,
)

interface CloudSyncJsonBridge {
    suspend fun snapshot(maxBytes: Long): CloudSyncJsonSnapshot
    suspend fun stageIncoming(bytes: ByteArray, sha256: String, sourceConfigId: String): StagedCloudSyncJson
}

data class CloudSyncUsageSnapshot(
    val key: String,
    val bytes: ByteArray,
    val lastModifiedMillis: Long,
    val localId: String,
)

interface CloudSyncUsageBridge {
    suspend fun snapshots(maxBytes: Long): List<CloudSyncUsageSnapshot>
    suspend fun mergeIncoming(key: String, bytes: ByteArray, sha256: String): CloudSyncUsageSnapshot
}

data class CloudSyncReaderProgressSnapshot(
    val bytes: ByteArray,
    val lastModifiedMillis: Long,
    val localId: String = "reader-progress",
)

interface CloudSyncReaderProgressBridge {
    suspend fun snapshot(maxBytes: Long): CloudSyncReaderProgressSnapshot
    suspend fun mergeIncoming(bytes: ByteArray, sha256: String, maxBytes: Long): CloudSyncReaderProgressSnapshot
}

data class CloudSyncAgentChatSnapshot(
    val bytes: ByteArray,
    val lastModifiedMillis: Long,
    val localId: String = "agent-chats",
)

interface CloudSyncAgentChatBridge {
    suspend fun snapshot(maxBytes: Long): CloudSyncAgentChatSnapshot
    suspend fun mergeIncoming(bytes: ByteArray, sha256: String, maxBytes: Long): CloudSyncAgentChatSnapshot
}

/** Legacy canonicalization helper; kept only for old instrumentation-test compilation. */
internal fun canonicalizeCloudSyncBackupJson(generated: ByteArray): ByteArray {
    val decoded = BackupJsonCodec.decode(generated.toString(Charsets.UTF_8))
    return BackupJsonCodec.encode(decoded.copy(exportedAt = 0L)).toByteArray(Charsets.UTF_8)
}
