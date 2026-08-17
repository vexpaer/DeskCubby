package com.deskcubby.app.data.sync

import android.content.Context
import com.deskcubby.app.data.model.CloudSyncContent
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface RecordSyncStateStore {
    suspend fun load(configId: String, contentType: CloudSyncContent): RecordSyncContentState?
    suspend fun save(configId: String, state: RecordSyncContentState)
}

/**
 * V2 sync state for record sync. V1 file-sync hashes are intentionally untouched and remain the
 * FileSyncEngine ancestry; record state carries stable-id -> local-key mappings and tombstones.
 */
class FileRecordSyncStateStore(
    context: Context,
) : RecordSyncStateStore {
    private val directory = File(context.filesDir, "cloud-sync-record-state")

    override suspend fun load(
        configId: String,
        contentType: CloudSyncContent,
    ): RecordSyncContentState? = withContext(Dispatchers.IO) {
        val file = stateFile(configId, contentType)
        if (!file.isFile || file.length() > MAX_STATE_BYTES) return@withContext null
        try {
            decode(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun save(
        configId: String,
        state: RecordSyncContentState,
    ) = withContext(Dispatchers.IO) {
        val bytes = encode(state)
        if (bytes.size > MAX_STATE_BYTES) {
            throw CloudSyncLimitException("本地记录同步状态超过大小上限。")
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw CloudSyncException("无法创建本地记录同步状态目录。")
        }
        val target = stateFile(configId, state.contentType)
        val pending = File(directory, "${target.name}.pending")
        FileOutputStream(pending).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!pending.renameTo(target)) {
            FileOutputStream(target).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            pending.delete()
        }
    }

    private fun stateFile(configId: String, contentType: CloudSyncContent): File {
        val raw = "$configId\u0000${contentType.name}"
        val suffix = sha256(raw.toByteArray(StandardCharsets.UTF_8)).take(32)
        return File(directory, "$suffix.state")
    }

    private fun encode(state: RecordSyncContentState): ByteArray {
        val root = JSONObject()
            .put("format", "DeskCubby-Record-Sync-State-2")
            .put("scopeFingerprint", state.scopeFingerprint)
            .putNullable("manifestVersion", state.manifestVersion)
            .put("contentType", state.contentType.name)
            .put(
                "entries",
                JSONArray().apply {
                    state.entries.values.sortedBy(RecordSyncEntry::id).forEach { entry ->
                        put(
                            JSONObject()
                                .put("id", entry.id)
                                .put("revision", entry.revision)
                                .put("updatedAt", entry.updatedAt)
                                .put("deleted", entry.deleted)
                                .put("payloadSha256", entry.payloadSha256)
                                .putNullable("localKey", entry.localKey),
                        )
                    }
                },
            )
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): RecordSyncContentState {
        val root = JSONObject(bytes.toString(StandardCharsets.UTF_8))
        require(root.optString("format") == "DeskCubby-Record-Sync-State-2")
        require(root.has("scopeFingerprint") && root.getString("scopeFingerprint").length == 64)
        val contentType = CloudSyncContent.valueOf(root.getString("contentType"))
        val entries = linkedMapOf<String, RecordSyncEntry>()
        val jsonEntries = root.optJSONArray("entries") ?: JSONArray()
        for (index in 0 until jsonEntries.length()) {
            val item = jsonEntries.getJSONObject(index)
            val id = item.getString("id")
            requireValidSyncKey("records/$id")
            val entry = RecordSyncEntry(
                id = id,
                revision = item.getLong("revision"),
                updatedAt = item.getLong("updatedAt"),
                deleted = item.getBoolean("deleted"),
                payloadSha256 = item.getString("payloadSha256"),
                localKey = if (item.isNull("localKey")) null else item.getString("localKey"),
            )
            require(entries.put(id, entry) == null)
        }
        return RecordSyncContentState(
            contentType = contentType,
            scopeFingerprint = root.getString("scopeFingerprint"),
            manifestVersion = if (root.isNull("manifestVersion")) null else root.getString(
                "manifestVersion",
            ),
            entries = entries,
        )
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private companion object {
        const val MAX_STATE_BYTES = 8L * 1024 * 1024
    }
}
