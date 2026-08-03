package com.deskcubby.app.data.statistics

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class EngagementKind { GAME, READING }

data class EngagementTimeSnapshot(
    val gameTotalsMillis: Map<String, Long> = emptyMap(),
    val readingTotalsMillis: Map<String, Long> = emptyMap(),
) {
    fun total(kind: EngagementKind, id: String): Long = when (kind) {
        EngagementKind.GAME -> gameTotalsMillis[id]
        EngagementKind.READING -> readingTotalsMillis[id]
    } ?: 0L
}

/**
 * Stores foreground reading/game durations in one bounded application-private JSON file.
 *
 * Sessions use elapsed realtime so wall-clock changes cannot inflate totals. Every checkpoint is
 * written to a sibling file, decoded again, fsynced, and only then replaces the committed file.
 */
@Singleton
class EngagementTimeRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private data class SessionKey(val kind: EngagementKind, val id: String)

    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val file = File(directory, FILE_NAME)
    private val mutex = Mutex()
    private val sessionsLock = Any()
    private val sessions = mutableMapOf<SessionKey, Long>()
    private val _snapshot = MutableStateFlow(readSnapshotOrEmpty())
    val snapshot: StateFlow<EngagementTimeSnapshot> = _snapshot.asStateFlow()

    fun begin(kind: EngagementKind, id: String) {
        val validId = requireValidId(id)
        synchronized(sessionsLock) {
            sessions.putIfAbsent(SessionKey(kind, validId), SystemClock.elapsedRealtime())
        }
    }

    suspend fun checkpoint(kind: EngagementKind, id: String) {
        val validId = requireValidId(id)
        val key = SessionKey(kind, validId)
        val now = SystemClock.elapsedRealtime()
        val elapsed = synchronized(sessionsLock) {
            val started = sessions[key] ?: return
            sessions[key] = now
            (now - started).coerceIn(0L, MAX_SINGLE_CHECKPOINT_MILLIS)
        }
        if (elapsed > 0L) addDuration(key, elapsed)
    }

    suspend fun end(kind: EngagementKind, id: String) {
        val validId = requireValidId(id)
        val key = SessionKey(kind, validId)
        val now = SystemClock.elapsedRealtime()
        val elapsed = synchronized(sessionsLock) {
            val started = sessions.remove(key) ?: return
            (now - started).coerceIn(0L, MAX_SINGLE_CHECKPOINT_MILLIS)
        }
        if (elapsed > 0L) addDuration(key, elapsed)
    }

    private suspend fun addDuration(key: SessionKey, elapsed: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = readSnapshotOrEmpty()
            val updated = when (key.kind) {
                EngagementKind.GAME -> current.copy(
                    gameTotalsMillis = current.gameTotalsMillis.withAddedDuration(key.id, elapsed),
                )
                EngagementKind.READING -> current.copy(
                    readingTotalsMillis = current.readingTotalsMillis.withAddedDuration(key.id, elapsed),
                )
            }
            writeVerified(updated)
            _snapshot.value = updated
        }
    }

    private fun Map<String, Long>.withAddedDuration(id: String, elapsed: Long): Map<String, Long> =
        toMutableMap().apply {
            this[id] = ((this[id] ?: 0L) + elapsed).coerceAtMost(MAX_TOTAL_MILLIS)
        }.toSortedMap()

    private fun readSnapshotOrEmpty(): EngagementTimeSnapshot = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_JSON_BYTES) return@runCatching EngagementTimeSnapshot()
        decode(file.readText(Charsets.UTF_8))
    }.getOrDefault(EngagementTimeSnapshot())

    private fun writeVerified(value: EngagementTimeSnapshot) {
        directory.mkdirs()
        val encoded = encode(value)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
        val pending = File(directory, "$FILE_NAME.pending")
        FileOutputStream(pending).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(decode(pending.readText(Charsets.UTF_8)) == value) {
            "Engagement time verification failed"
        }
        if (!pending.renameTo(file)) {
            FileOutputStream(file).use { output ->
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            check(decode(file.readText(Charsets.UTF_8)) == value) {
                "Engagement time commit verification failed"
            }
            pending.delete()
        }
    }

    private fun encode(value: EngagementTimeSnapshot): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("gameTotalsMillis", value.gameTotalsMillis.toJsonObject())
        .put("readingTotalsMillis", value.readingTotalsMillis.toJsonObject())
        .toString()

    private fun decode(raw: String): EngagementTimeSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION)
        return EngagementTimeSnapshot(
            gameTotalsMillis = root.getJSONObject("gameTotalsMillis").decodeTotals(),
            readingTotalsMillis = root.getJSONObject("readingTotalsMillis").decodeTotals(),
        )
    }

    private fun Map<String, Long>.toJsonObject(): JSONObject = JSONObject().apply {
        entries.sortedBy(Map.Entry<String, Long>::key).forEach { (id, duration) ->
            put(id, duration.coerceIn(0L, MAX_TOTAL_MILLIS))
        }
    }

    private fun JSONObject.decodeTotals(): Map<String, Long> {
        require(length() <= MAX_ITEMS_PER_KIND)
        return keys().asSequence().associateWith { id ->
            requireValidId(id)
            getLong(id).also { require(it in 0L..MAX_TOTAL_MILLIS) }
        }.toSortedMap()
    }

    private fun requireValidId(id: String): String = id.also {
        require(ID_PATTERN.matches(it)) { "Invalid engagement identifier" }
    }

    companion object {
        const val DIRECTORY_NAME = "engagement"
        const val FILE_NAME = "engagement-times-v1.json"
        private const val SCHEMA_VERSION = 1
        private const val MAX_JSON_BYTES = 512 * 1024
        private const val MAX_ITEMS_PER_KIND = 2_000
        private const val MAX_SINGLE_CHECKPOINT_MILLIS = 6L * 60 * 60 * 1_000
        private const val MAX_TOTAL_MILLIS = 100L * 365 * 24 * 60 * 60 * 1_000
        private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,256}")
    }
}
