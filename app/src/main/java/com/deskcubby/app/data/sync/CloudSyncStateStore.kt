package com.deskcubby.app.data.sync

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CloudSyncBaseState(
    val scopeFingerprint: String,
    val hashesByKey: Map<String, String>,
)

interface CloudSyncStateStore {
    suspend fun load(configId: String): CloudSyncBaseState?
    suspend fun save(configId: String, state: CloudSyncBaseState)
}

/**
 * Rebuildable sync ancestry only; credentials and content are never written here.
 */
class FileCloudSyncStateStore(
    context: Context,
) : CloudSyncStateStore {
    private val directory = File(context.filesDir, "cloud-sync-state")

    override suspend fun load(configId: String): CloudSyncBaseState? =
        withContext(Dispatchers.IO) {
            val file = stateFile(configId)
            if (!file.isFile || file.length() > MAX_STATE_BYTES) return@withContext null
            try {
                decode(file.readBytes())
            } catch (_: Exception) {
                // The state is only a conflict-detection cache. Fail closed to first-sync
                // semantics instead of preventing access to local content.
                null
            }
        }

    override suspend fun save(configId: String, state: CloudSyncBaseState) =
        withContext(Dispatchers.IO) {
            val bytes = encode(state)
            if (bytes.size > MAX_STATE_BYTES) {
                throw CloudSyncLimitException("本地同步状态超过大小上限。")
            }
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                throw CloudSyncException("无法创建本地同步状态目录。")
            }
            val target = stateFile(configId)
            val pending = File(directory, "${target.name}.pending")
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!pending.renameTo(target)) {
                // State is rebuildable, so direct replacement is acceptable only inside this
                // narrow app-private directory after a complete pending write.
                FileOutputStream(target).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                pending.delete()
            }
        }

    private fun stateFile(configId: String): File =
        File(directory, "${sha256(configId.toByteArray(StandardCharsets.UTF_8))}.state")

    private fun encode(state: CloudSyncBaseState): ByteArray = buildString {
        append(HEADER)
        append('\t')
        append(state.scopeFingerprint)
        append('\n')
        state.hashesByKey.toSortedMap().forEach { (key, hash) ->
            requireValidSyncKey(key)
            require(HASH.matches(hash))
            append(
                Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(key.toByteArray(StandardCharsets.UTF_8)),
            )
            append('\t')
            append(hash)
            append('\n')
        }
    }.toByteArray(StandardCharsets.UTF_8)

    private fun decode(bytes: ByteArray): CloudSyncBaseState {
        val lines = String(bytes, StandardCharsets.UTF_8).lineSequence().toList()
        val header = lines.firstOrNull()?.split('\t')
        require(header?.size == 2 && header[0] == HEADER && HASH.matches(header[1]))
        val hashes = linkedMapOf<String, String>()
        lines.drop(1).filter(String::isNotBlank).forEach { line ->
            val fields = line.split('\t')
            require(fields.size == 2 && HASH.matches(fields[1]))
            val key = requireValidSyncKey(
                String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8),
            )
            require(hashes.put(key, fields[1]) == null)
        }
        return CloudSyncBaseState(header[1], hashes)
    }

    private companion object {
        const val HEADER = "DeskCubby-Sync-State-1"
        const val MAX_STATE_BYTES = 4L * 1024 * 1024
        val HASH = Regex("[0-9a-f]{64}")
    }
}
