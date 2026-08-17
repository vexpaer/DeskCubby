package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncContent

data class LocalRecordRef(
    val localKey: String,
    val revision: Long,
    val updatedAt: Long,
)

data class SyncRecord(
    val id: String,
    val revision: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val payload: ByteArray,
) {
    val payloadSha256: String
        get() = sha256(payload)

    override fun equals(other: Any?): Boolean =
        other is SyncRecord &&
            id == other.id &&
            revision == other.revision &&
            updatedAt == other.updatedAt &&
            deleted == other.deleted &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + deleted.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "SyncRecord(id=$id, revision=$revision, updatedAt=$updatedAt, deleted=$deleted, " +
            "payload=<redacted:${payload.size}>)"
}

enum class RecordConflictPolicy {
    LWW,
    CONFLICT_COPY,
}

data class RecordApplyResult(
    val canonicalLocalKey: String,
    val conflictCopyLocalKey: String? = null,
)

interface RecordSyncAdapter {
    val contentType: CloudSyncContent
    val conflictPolicy: RecordConflictPolicy

    suspend fun listLocalRecords(): List<LocalRecordRef>

    suspend fun readLocalRecord(localKey: String): SyncRecord

    suspend fun applyRemoteRecord(
        record: SyncRecord,
        preserveLocalConflict: SyncRecord? = null,
        preserveLocalKey: String? = null,
    ): RecordApplyResult?

    suspend fun deleteLocalRecord(localKey: String)
}

data class RecordSyncEntry(
    val id: String,
    val revision: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val payloadSha256: String,
    val localKey: String? = null,
)

data class RecordSyncContentState(
    val contentType: CloudSyncContent,
    val scopeFingerprint: String,
    val manifestVersion: String?,
    val entries: Map<String, RecordSyncEntry>,
)

data class RemoteRecordManifestEntry(
    val id: String,
    val revision: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val payloadSha256: String,
)

data class RemoteRecordManifest(
    val version: String,
    val contentType: CloudSyncContent,
    val entries: List<RemoteRecordManifestEntry>,
) {
    val entriesById: Map<String, RemoteRecordManifestEntry> = entries.associateBy { it.id }
}
