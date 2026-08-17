package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncContent
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class RemoteRecordPayload(
    val key: String,
    val version: String,
    val size: Long,
    val sha256: String,
)

/**
 * Record sync transport on top of the existing WebDAV/S3 object stores.
 *
 * One small manifest object per content type carries id/revision/hash/tombstone metadata, so a
 * normal sync only downloads payloads for records whose manifest entry actually changed.
 */
class RecordSyncRemoteStore(
    private val remote: CloudSyncRemoteStore,
    private val limits: CloudSyncLimits,
) {
    suspend fun loadManifest(content: CloudSyncContent): RemoteRecordManifest? {
        val prefix = manifestPrefix(content)
        val candidates = remote.list(setOf(prefix))
        if (candidates.isEmpty()) return null
        val target = candidates.singleOrNull { it.key == manifestKey(content) }
            ?: throw CloudSyncException("记录同步清单路径无效。")
        requireObjectWithinLimit(target)
        val bytes = remote.read(target, limits.maxObjectBytes)
        require(bytes.size.toLong() == target.size && sha256(bytes) == target.sha256) {
            "记录同步清单在读取期间发生变化。"
        }
        return RecordManifestCodec.decode(bytes, content)
            .copy(version = target.version)
    }

    suspend fun saveManifest(
        content: CloudSyncContent,
        entries: List<RemoteRecordManifestEntry>,
        expectedRemoteVersion: String?,
    ): RemoteRecordManifest {
        val bytes = RecordManifestCodec.encode(content, entries)
        val written = remote.write(
            key = manifestKey(content),
            bytes = bytes,
            contentSha256 = sha256(bytes),
            lastModifiedMillis = System.currentTimeMillis(),
            expectedRemoteVersion = expectedRemoteVersion,
        )
        return RecordManifestCodec.decode(bytes, content).copy(version = written.version)
    }

    suspend fun payloadObject(
        content: CloudSyncContent,
        id: String,
    ): RemoteRecordPayload? {
        val key = payloadKey(content, id)
        val candidate = remote.list(setOf(key)).singleOrNull { it.key == key }
            ?: return null
        requireObjectWithinLimit(candidate)
        return RemoteRecordPayload(key, candidate.version, candidate.size, candidate.sha256)
    }

    suspend fun readPayload(
        content: CloudSyncContent,
        id: String,
        expectedSha256: String,
    ): ByteArray {
        val objectInfo = payloadObject(content, id)
            ?: throw CloudSyncConflictException("云端记录 payload 不存在。")
        require(objectInfo.sha256 == expectedSha256) {
            "云端记录 payload 与清单不一致，请重新同步。"
        }
        val bytes = remote.read(
            RemoteSyncObject(
                key = objectInfo.key,
                size = objectInfo.size,
                lastModifiedMillis = 0L,
                sha256 = objectInfo.sha256,
                version = objectInfo.version,
                storageName = "records/$id",
            ),
            limits.maxObjectBytes,
        )
        require(sha256(bytes) == expectedSha256) { "云端记录 payload 校验失败。" }
        return bytes
    }

    suspend fun writePayload(
        content: CloudSyncContent,
        id: String,
        bytes: ByteArray,
        expectedSha256: String,
    ) {
        require(bytes.size.toLong() <= limits.maxObjectBytes) { "记录超过单文件同步上限。" }
        require(sha256(bytes) == expectedSha256) { "记录 payload 校验失败。" }
        val existing = payloadObject(content, id)
        val expectedVersion = if (existing?.sha256 == expectedSha256) {
            return
        } else {
            existing?.version
        }
        remote.write(
            key = payloadKey(content, id),
            bytes = bytes,
            contentSha256 = expectedSha256,
            lastModifiedMillis = System.currentTimeMillis(),
            expectedRemoteVersion = expectedVersion,
        )
    }

    private fun requireObjectWithinLimit(objectInfo: RemoteSyncObject) {
        if (objectInfo.size !in 0..limits.maxObjectBytes || !SHA256_REGEX.matches(objectInfo.sha256)) {
            throw CloudSyncLimitException("记录同步对象大小或摘要无效。")
        }
    }

    private fun manifestPrefix(content: CloudSyncContent): String =
        "sync-meta/${content.remoteDirectory}/manifest.json"

    private fun manifestKey(content: CloudSyncContent): String = manifestPrefix(content)

    private fun payloadKey(content: CloudSyncContent, id: String): String {
        requireValidSyncKey("records/${content.remoteDirectory.substringAfter('/')}/$id")
        val safe = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id.toByteArray(StandardCharsets.UTF_8))
        return "${content.remoteDirectory}/${safe}.json"
    }

    private companion object {
        val SHA256_REGEX = Regex("[0-9a-f]{64}", RegexOption.IGNORE_CASE)
    }
}

internal object RecordManifestCodec {
    const val FORMAT = "deskcubby-record-manifest"
    const val VERSION = 1

    fun encode(
        content: CloudSyncContent,
        entries: List<RemoteRecordManifestEntry>,
    ): ByteArray {
        val normalized = entries.distinctBy(RemoteRecordManifestEntry::id)
            .sortedBy(RemoteRecordManifestEntry::id)
        require(normalized.size == entries.size) { "记录同步清单包含重复 ID。" }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("contentType", content.name)
            .put(
                "records",
                JSONArray().apply {
                    normalized.forEach { entry ->
                        put(
                            JSONObject()
                                .put("id", entry.id)
                                .put("revision", entry.revision)
                                .put("updatedAt", entry.updatedAt)
                                .put("deleted", entry.deleted)
                                .put("sha256", entry.payloadSha256),
                        )
                    }
                },
            )
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(
        bytes: ByteArray,
        expectedContent: CloudSyncContent,
    ): RemoteRecordManifest {
        require(bytes.isNotEmpty() && bytes.size <= 8L * 1024 * 1024)
        val root = JSONTokener(bytes.toString(StandardCharsets.UTF_8)).nextValue() as? JSONObject
            ?: throw IllegalArgumentException("记录同步清单格式无效。")
        require(root.optString("format") == FORMAT && root.optInt("version") == VERSION)
        require(root.optString("contentType") == expectedContent.name)
        val records = root.optJSONArray("records") ?: JSONArray()
        val entries = buildList(records.length()) {
            for (index in 0 until records.length()) {
                val item = records.getJSONObject(index)
                val id = item.getString("id")
                requireValidSyncKey("records/${expectedContent.remoteDirectory.substringAfter('/')}/$id")
                add(
                    RemoteRecordManifestEntry(
                        id = id,
                        revision = item.getLong("revision"),
                        updatedAt = item.getLong("updatedAt"),
                        deleted = item.getBoolean("deleted"),
                        payloadSha256 = item.getString("sha256"),
                    ),
                )
            }
        }
        require(entries.map(RemoteRecordManifestEntry::id).distinct().size == entries.size)
        return RemoteRecordManifest("", expectedContent, entries)
    }
}
