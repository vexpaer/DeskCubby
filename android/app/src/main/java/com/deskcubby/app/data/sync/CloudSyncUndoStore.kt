package com.deskcubby.app.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One-shot undo snapshot for the most recent sync run.
 *
 * Every new sync run starts by discarding the previous snapshot. While the run applies remote
 * diary files, [captureBeforeOverwrite] stores the exact local bytes that are about to be
 * replaced, and [captureCreated] remembers files the run creates. "撤回一次" (undo once) then
 * restores overwritten files and removes created ones. The snapshot lives in the app-private
 * directory, is bounded, and never enters backups or the cloud.
 */
@Singleton
class CloudSyncUndoStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.filesDir, "cloud-sync-undo")
    private val manifestFile = File(directory, "manifest.json")
    private val mutex = Mutex()

    data class UndoEntry(
        val key: String,
        val uri: String,
        val action: String,
        val backupName: String?,
        val sha256: String?,
    ) {
        val isOverwrite: Boolean get() = action == ACTION_OVERWRITE
        val isCreate: Boolean get() = action == ACTION_CREATE
    }

    /** Clears any previous run's snapshot; called once at the start of every sync run. */
    suspend fun beginRun() = mutex.withLock {
        withContext(Dispatchers.IO) { deleteTree(directory) }
    }

    /** Stores the local bytes that are about to be overwritten by a downloaded diary file. */
    suspend fun captureBeforeOverwrite(
        key: String,
        uri: String,
        bytes: ByteArray,
        sha256: String,
    ) = mutex.withLock {
        if (bytes.isEmpty() || bytes.size > MAX_UNDO_BACKUP_BYTES) return@withLock
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val backupName = sha256.take(MAX_BACKUP_NAME_CHARS) + ".bin"
            val backup = File(directory, backupName)
            FileOutputStream(backup).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!backup.readBytes().contentEquals(bytes)) {
                backup.delete()
                return@withContext
            }
            appendEntry(
                UndoEntry(
                    key = key,
                    uri = uri,
                    action = ACTION_OVERWRITE,
                    backupName = backupName,
                    sha256 = sha256,
                ),
            )
        }
    }

    /** Records a file the sync run created locally (undo removes it again). */
    suspend fun captureCreated(key: String, uri: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            appendEntry(
                UndoEntry(
                    key = key,
                    uri = uri,
                    action = ACTION_CREATE,
                    backupName = null,
                    sha256 = null,
                ),
            )
        }
    }

    suspend fun entries(): List<UndoEntry> = mutex.withLock {
        withContext(Dispatchers.IO) { readEntries() }
    }

    suspend fun readBackup(name: String): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = File(directory, name)
            if (!file.isFile || file.length() > MAX_UNDO_BACKUP_BYTES) null else file.readBytes()
        }
    }

    /** Drops one successfully restored/removed entry. */
    suspend fun removeEntry(entry: UndoEntry) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val remaining = readEntries().filterNot { it.key == entry.key && it.uri == entry.uri }
            entry.backupName?.let { File(directory, it).delete() }
            writeManifest(remaining)
        }
    }

    /** True when a snapshot exists and can be undone. */
    fun hasUndo(): Boolean = readEntriesSync().isNotEmpty()

    private fun readEntriesSync(): List<UndoEntry> = runCatching {
        if (!manifestFile.isFile) return emptyList()
        val root = JSONObject(manifestFile.readText(Charsets.UTF_8))
        val array = root.optJSONArray("entries") ?: JSONArray()
        if (array.length() > MAX_UNDO_ENTRIES) return emptyList()
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val key = item.optString("key").takeIf(String::isNotBlank) ?: continue
                val uri = item.optString("uri").takeIf(String::isNotBlank) ?: continue
                val action = item.optString("action")
                if (action != ACTION_OVERWRITE && action != ACTION_CREATE) continue
                add(
                    UndoEntry(
                        key = key,
                        uri = uri,
                        action = action,
                        backupName = item.optString("backup").takeIf(String::isNotBlank),
                        sha256 = item.optString("sha").takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private suspend fun readEntries(): List<UndoEntry> = readEntriesSync()

    private suspend fun appendEntry(entry: UndoEntry) {
        withContext(Dispatchers.IO) {
            val current = readEntriesSync()
            val updated = (current + entry).takeLast(MAX_UNDO_ENTRIES)
            writeManifest(updated)
        }
    }

    private fun writeManifest(entries: List<UndoEntry>) {
        directory.mkdirs()
        val root = JSONObject().put(
            "entries",
            JSONArray().apply {
                entries.forEach { entry ->
                    put(
                        JSONObject()
                            .put("key", entry.key)
                            .put("uri", entry.uri)
                            .put("action", entry.action)
                            .put("backup", entry.backupName ?: JSONObject.NULL)
                            .put("sha", entry.sha256 ?: JSONObject.NULL),
                    )
                }
            },
        )
        FileOutputStream(manifestFile).use { output ->
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach(::deleteTree)
        file.delete()
    }

    companion object {
        const val ACTION_OVERWRITE = "overwrite"
        const val ACTION_CREATE = "create"
        private const val MAX_UNDO_ENTRIES = 2_000
        private const val MAX_UNDO_BACKUP_BYTES = 64L * 1024 * 1024
        private const val MAX_BACKUP_NAME_CHARS = 16
    }
}
