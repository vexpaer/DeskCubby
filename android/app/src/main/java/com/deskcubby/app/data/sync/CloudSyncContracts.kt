package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import java.io.IOException

data class CloudSyncLimits(
    val connectTimeoutMillis: Int = 15_000,
    val readTimeoutMillis: Int = 30_000,
    val overallTimeoutMillis: Long = 10 * 60_000L,
    val maxObjectBytes: Long = 64L * 1024 * 1024,
    val maxTransferredBytes: Long = 512L * 1024 * 1024,
    val maxObjects: Int = 10_000,
) {
    init {
        require(connectTimeoutMillis in 1_000..120_000)
        require(readTimeoutMillis in 1_000..300_000)
        require(overallTimeoutMillis in 1_000L..3_600_000L)
        require(maxObjectBytes in 1L..512L * 1024 * 1024)
        require(maxTransferredBytes >= maxObjectBytes)
        require(maxTransferredBytes <= 4L * 1024 * 1024 * 1024)
        require(maxObjects in 1..100_000)
    }
}

/** Shared per-run network-body budget used by every remote request. */
class TransferBudget(
    private val maximum: Long,
) {
    private val lock = Any()
    private var usedBytes = 0L

    init {
        require(maximum >= 0L)
    }

    val used: Long
        get() = synchronized(lock) { usedBytes }

    val remaining: Long
        get() = synchronized(lock) { maximum - usedBytes }

    fun reserve(bytes: Long) {
        synchronized(lock) {
            if (bytes < 0L || bytes > maximum - usedBytes) {
                throw CloudSyncLimitException(
                    "本次同步的网络传输量超过上限。 / " +
                        "This sync run exceeded its network transfer limit.",
                )
            }
            usedBytes += bytes
        }
    }
}

data class LocalSyncObject(
    val key: String,
    val content: CloudSyncContent,
    val size: Long,
    val lastModifiedMillis: Long,
    val sha256: String,
    /**
     * Store-private identifier, normally a content URI. It must never be converted to a file path.
     */
    val localId: String,
)

data class RemoteSyncObject(
    val key: String,
    val size: Long,
    val lastModifiedMillis: Long,
    val sha256: String,
    /** Opaque conditional-write version. */
    val version: String,
    internal val storageName: String,
)

sealed interface LocalWriteResult {
    data class Applied(val objectInfo: LocalSyncObject) : LocalWriteResult
    data class ConflictCopy(
        val existing: LocalSyncObject,
        val copy: LocalSyncObject,
    ) : LocalWriteResult
}

/**
 * All SAF access is implemented by DiaryFileRepository. The sync engine depends only on this
 * bounded interface so it can be tested without Android document providers.
 */
interface CloudSyncLocalStore {
    suspend fun list(
        selectedContents: Set<CloudSyncContent>,
        limits: CloudSyncLimits,
    ): List<LocalSyncObject>

    suspend fun read(
        objectInfo: LocalSyncObject,
        maxBytes: Long,
    ): ByteArray

    /**
     * Applies downloaded bytes only when [expectedLocalSha256] still matches. If the local object
     * changed after the scan, the implementation keeps it and writes a deterministic conflict copy.
     */
    suspend fun writeRemote(
        key: String,
        bytes: ByteArray,
        contentSha256: String,
        lastModifiedMillis: Long,
        expectedLocalSha256: String?,
        limits: CloudSyncLimits,
    ): LocalWriteResult
}

interface CloudSyncRemoteStore {
    suspend fun list(prefixes: Set<String>): List<RemoteSyncObject>

    suspend fun read(
        objectInfo: RemoteSyncObject,
        maxBytes: Long,
    ): ByteArray

    /**
     * Publishes [bytes] only if the manifest still contains [expectedRemoteVersion]. A null value
     * means the key must not exist. Implementations must fail closed when conditions are unsupported.
     */
    suspend fun write(
        key: String,
        bytes: ByteArray,
        contentSha256: String,
        lastModifiedMillis: Long,
        expectedRemoteVersion: String?,
    ): RemoteSyncObject
}

fun interface CloudSyncRemoteStoreFactory {
    fun create(
        config: CloudSyncConfig,
        limits: CloudSyncLimits,
        transferBudget: TransferBudget,
    ): CloudSyncRemoteStore
}

open class CloudSyncException(
    message: String,
    cause: Throwable? = null,
    val errorCode: String = "SYNC_FAILED",
) : IOException(message, cause)

class CloudSyncConflictException(
    message: String = "云端内容在同步期间发生变化，请重新同步。 / " +
        "Remote content changed during sync; please sync again.",
) : CloudSyncException(message, errorCode = "SYNC_CONFLICT")

class CloudSyncLimitException(
    message: String,
) : CloudSyncException(message, errorCode = "SYNC_LIMIT")

class CloudSyncConfigurationException(
    message: String,
) : CloudSyncException(message, errorCode = "SYNC_CONFIG")

internal fun formatCloudSyncError(error: Throwable): String {
    val syncError = generateSequence(error) { it.cause }
        .filterIsInstance<CloudSyncException>()
        .firstOrNull()
    val code = syncError?.errorCode ?: "SYNC_UNEXPECTED"
    val message = syncError?.message
        ?.takeIf(String::isNotBlank)
        ?: "云端同步失败，请检查服务配置。"
    return "[$code] $message"
}
