package com.deskcubby.app.data.sync

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class BlobMetadata(
    val version: String,
    val size: Long,
    val lastModifiedMillis: Long,
)

internal class BlobRead(
    val metadata: BlobMetadata,
    val bytes: ByteArray,
) {
    override fun toString(): String =
        "BlobRead(version=<opaque>, size=${bytes.size})"
}

internal sealed interface BlobWriteCondition {
    data object MustNotExist : BlobWriteCondition
    data class MustMatch(val version: String) : BlobWriteCondition
}

internal interface ConditionalBlobTransport {
    suspend fun get(
        storageName: String,
        maxBytes: Long,
        expectedVersion: String? = null,
    ): BlobRead?

    suspend fun put(
        storageName: String,
        bytes: ByteArray,
        sha256: String,
        condition: BlobWriteCondition,
    ): BlobMetadata
}

/**
 * Remote inventory shared by WebDAV and S3.
 *
 * Payloads are immutable, content-addressed blobs. The small manifest is the only mutable object
 * and is always written with If-Match/If-None-Match. A cancelled or racing upload can therefore
 * leave an unreferenced blob, but cannot overwrite published user content.
 */
internal class ManifestRemoteStore(
    private val transport: ConditionalBlobTransport,
    private val limits: CloudSyncLimits,
) : CloudSyncRemoteStore {
    private val mutex = Mutex()
    private var loadedManifest: LoadedManifest? = null

    override suspend fun list(prefixes: Set<String>): List<RemoteSyncObject> = mutex.withLock {
        val normalizedPrefixes = prefixes.map { prefix ->
            requireValidSyncKey(prefix.removeSuffix("/")) + "/"
        }.toSet()
        val loaded = loadManifest()
        loadedManifest = loaded
        loaded.entries.values
            .asSequence()
            .filter { entry -> normalizedPrefixes.any(entry.key::startsWith) }
            .map(ManifestEntry::toRemoteObject)
            .sortedBy(RemoteSyncObject::key)
            .toList()
    }

    override suspend fun read(
        objectInfo: RemoteSyncObject,
        maxBytes: Long,
    ): ByteArray {
        if (objectInfo.size > maxBytes) {
            throw CloudSyncLimitException("云端文件超过单文件同步上限。")
        }
        val decodedVersion = decodeVersionToken(objectInfo.version)
        if (decodedVersion.sha256 != objectInfo.sha256 ||
            decodedVersion.storageName != objectInfo.storageName
        ) {
            throw CloudSyncException("云端同步版本与清单不一致。")
        }
        val blob = transport.get(
            storageName = objectInfo.storageName,
            maxBytes = maxBytes,
            expectedVersion = decodedVersion.blobVersion,
        ) ?: throw CloudSyncConflictException("云端文件已被移动或删除，请重新同步。")
        val actualHash = sha256(blob.bytes)
        if (blob.bytes.size.toLong() != objectInfo.size || actualHash != objectInfo.sha256) {
            throw CloudSyncConflictException("云端文件内容与同步清单不一致，未写入本地。")
        }
        return blob.bytes
    }

    override suspend fun write(
        key: String,
        bytes: ByteArray,
        contentSha256: String,
        lastModifiedMillis: Long,
        expectedRemoteVersion: String?,
    ): RemoteSyncObject = mutex.withLock {
        val validKey = requireValidSyncKey(key)
        if (bytes.size.toLong() > limits.maxObjectBytes) {
            throw CloudSyncLimitException("本地文件超过单文件同步上限。")
        }
        if (sha256(bytes) != contentSha256) {
            throw CloudSyncException("本地文件在同步读取期间发生变化。")
        }
        val loaded = loadedManifest ?: loadManifest().also { loadedManifest = it }
        val existing = loaded.entries[validKey]
        if (existing?.versionToken != expectedRemoteVersion) {
            throw CloudSyncConflictException()
        }
        if (existing?.sha256 == contentSha256) {
            ensureManifestStillCurrent(loaded)
            return@withLock existing.toRemoteObject()
        }
        if (existing == null && loaded.entries.size >= limits.maxObjects) {
            throw CloudSyncLimitException("云端同步清单的文件数量超过上限。")
        }

        val storageName = objectStorageName(validKey, contentSha256)
        val blobMetadata = try {
            transport.put(
                storageName = storageName,
                bytes = bytes,
                sha256 = contentSha256,
                condition = BlobWriteCondition.MustNotExist,
            )
        } catch (conflict: CloudSyncConflictException) {
            // Retrying an interrupted publish is safe because blob names include the content hash.
            val prior = transport.get(storageName, limits.maxObjectBytes)
                ?: throw conflict
            if (prior.bytes.size != bytes.size || sha256(prior.bytes) != contentSha256) {
                throw conflict
            }
            prior.metadata
        }
        val replacement = ManifestEntry(
            key = validKey,
            sha256 = contentSha256,
            size = bytes.size.toLong(),
            lastModifiedMillis = lastModifiedMillis.coerceAtLeast(0L),
            storageName = storageName,
            blobVersion = blobMetadata.version,
        )
        val updatedEntries = loaded.entries + (validKey to replacement)
        val manifestBytes = RemoteManifestCodec.encode(updatedEntries.values)
        if (manifestBytes.size.toLong() > MAX_MANIFEST_BYTES) {
            throw CloudSyncLimitException("云端同步清单超过大小上限。")
        }
        val manifestHash = sha256(manifestBytes)
        val manifestMetadata = try {
            transport.put(
                storageName = MANIFEST_STORAGE_NAME,
                bytes = manifestBytes,
                sha256 = manifestHash,
                condition = loaded.remoteVersion?.let(BlobWriteCondition::MustMatch)
                    ?: BlobWriteCondition.MustNotExist,
            )
        } catch (error: CancellationException) {
            loadedManifest = null
            throw error
        } catch (error: Exception) {
            loadedManifest = null
            throw error
        }
        loadedManifest = LoadedManifest(
            remoteVersion = manifestMetadata.version,
            entries = updatedEntries,
        )
        replacement.toRemoteObject()
    }

    private suspend fun loadManifest(): LoadedManifest {
        val blob = transport.get(
            storageName = MANIFEST_STORAGE_NAME,
            maxBytes = MAX_MANIFEST_BYTES,
        ) ?: return LoadedManifest(remoteVersion = null, entries = emptyMap())
        if (blob.metadata.version.isBlank()) {
            throw CloudSyncException("云端服务未提供 ETag，无法执行安全的条件同步。")
        }
        val entries = RemoteManifestCodec.decode(blob.bytes, limits.maxObjects)
        return LoadedManifest(
            remoteVersion = blob.metadata.version,
            entries = entries.associateBy(ManifestEntry::key),
        )
    }

    private suspend fun ensureManifestStillCurrent(expected: LoadedManifest) {
        val version = expected.remoteVersion ?: throw CloudSyncConflictException()
        val blob = transport.get(
            storageName = MANIFEST_STORAGE_NAME,
            maxBytes = MAX_MANIFEST_BYTES,
            expectedVersion = version,
        ) ?: throw CloudSyncConflictException()
        val current = RemoteManifestCodec.decode(blob.bytes, limits.maxObjects)
            .associateBy(ManifestEntry::key)
        if (current != expected.entries) throw CloudSyncConflictException()
    }

    private data class LoadedManifest(
        val remoteVersion: String?,
        val entries: Map<String, ManifestEntry>,
    )

    private companion object {
        const val MANIFEST_STORAGE_NAME = ".deskcubby-sync-v1.manifest"
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    }
}

private data class ManifestEntry(
    val key: String,
    val sha256: String,
    val size: Long,
    val lastModifiedMillis: Long,
    val storageName: String,
    val blobVersion: String,
) {
    val versionToken: String
        get() = encodeVersionToken(sha256, blobVersion, storageName)

    fun toRemoteObject(): RemoteSyncObject = RemoteSyncObject(
        key = key,
        size = size,
        lastModifiedMillis = lastModifiedMillis,
        sha256 = sha256,
        version = versionToken,
        storageName = storageName,
    )
}

private object RemoteManifestCodec {
    private const val HEADER = "DeskCubby-Sync\t1"
    private val HASH = Regex("[0-9a-f]{64}")
    private val STORAGE_NAME = Regex("[.A-Za-z0-9_-]{1,200}")

    fun encode(entries: Collection<ManifestEntry>): ByteArray = buildString {
        append(HEADER)
        append('\n')
        entries.sortedBy(ManifestEntry::key).forEach { entry ->
            append(encodeField(entry.key))
            append('\t')
            append(entry.sha256)
            append('\t')
            append(entry.size)
            append('\t')
            append(entry.lastModifiedMillis)
            append('\t')
            append(entry.storageName)
            append('\t')
            append(encodeField(entry.blobVersion))
            append('\n')
        }
    }.toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray, maxObjects: Int): List<ManifestEntry> {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw CloudSyncException("云端同步清单不是有效的 UTF-8 文本。", error)
        }
        val lines = text.lineSequence().toList()
        if (lines.firstOrNull() != HEADER) {
            throw CloudSyncException("云端同步清单格式无效或版本不受支持。")
        }
        val entryLines = lines.drop(1).filter(String::isNotEmpty)
        if (entryLines.size > maxObjects) {
            throw CloudSyncLimitException("云端同步清单的文件数量超过上限。")
        }
        val seen = hashSetOf<String>()
        return entryLines.map { line ->
            val fields = line.split('\t')
            if (fields.size != 6) throw CloudSyncException("云端同步清单格式无效。")
            val key = requireValidSyncKey(decodeField(fields[0]))
            val hash = fields[1]
            val size = fields[2].toLongOrNull()
            val lastModified = fields[3].toLongOrNull()
            val storageName = fields[4]
            val blobVersion = decodeField(fields[5])
            if (!seen.add(key) || !HASH.matches(hash) || size == null || size < 0L ||
                lastModified == null || lastModified < 0L ||
                !STORAGE_NAME.matches(storageName) || blobVersion.isBlank() ||
                blobVersion.length > 4_096
            ) {
                throw CloudSyncException("云端同步清单包含无效条目。")
            }
            ManifestEntry(key, hash, size, lastModified, storageName, blobVersion)
        }
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value)))
            .toString()
    } catch (error: Exception) {
        throw CloudSyncException("云端同步清单包含无效编码。", error)
    }
}

private data class DecodedVersionToken(
    val sha256: String,
    val blobVersion: String,
    val storageName: String,
)

private fun encodeVersionToken(
    hash: String,
    blobVersion: String,
    storageName: String,
): String = listOf(hash, blobVersion, storageName).joinToString("\n") { value ->
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun decodeVersionToken(token: String): DecodedVersionToken {
    val parts = token.split('\n')
    if (parts.size != 3) throw CloudSyncException("云端同步版本无效。")
    return try {
        DecodedVersionToken(
            sha256 = String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
            blobVersion = String(
                Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8,
            ),
            storageName = String(
                Base64.getUrlDecoder().decode(parts[2]),
                StandardCharsets.UTF_8,
            ),
        )
    } catch (error: IllegalArgumentException) {
        throw CloudSyncException("云端同步版本无效。", error)
    }
}

private fun objectStorageName(key: String, contentHash: String): String =
    ".deskcubby-object-${sha256(key.toByteArray(StandardCharsets.UTF_8)).take(32)}-$contentHash"
