package com.deskcubby.app.data.statistics

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class EngagementKind { GAME, READING }

data class EngagementTimeSnapshot(
    val gameTotalsMillis: Map<String, Long> = emptyMap(),
    val readingTotalsMillis: Map<String, Long> = emptyMap(),
    /** Last known local title for every book with persisted reading time. */
    val readingTitles: Map<String, String> = emptyMap(),
) {
    fun total(kind: EngagementKind, id: String): Long = when (kind) {
        EngagementKind.GAME -> gameTotalsMillis[id]
        EngagementKind.READING -> readingTotalsMillis[id]
    } ?: 0L
}

/** A synchronously detached session interval waiting to be committed to the JSON store. */
@ConsistentCopyVisibility
data class PendingEngagementDuration internal constructor(
    internal val kind: EngagementKind,
    internal val id: String,
    internal val elapsedMillis: Long,
    internal val readingTitle: String?,
)

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
    private data class ActiveSession(
        val startedAtElapsedMillis: Long,
        val readingTitle: String?,
    )

    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val file = File(directory, FILE_NAME)
    private val mutex = Mutex()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionsLock = Any()
    private val sessions = mutableMapOf<SessionKey, ActiveSession>()
    private val _snapshot = MutableStateFlow(readSnapshotOrEmpty())
    val snapshot: StateFlow<EngagementTimeSnapshot> = _snapshot.asStateFlow()

    fun begin(kind: EngagementKind, id: String, readingTitle: String? = null) {
        val validId = requireValidId(id)
        val validTitle = if (kind == EngagementKind.READING) {
            normalizeReadingTitle(readingTitle)
        } else {
            null
        }
        synchronized(sessionsLock) {
            val key = SessionKey(kind, validId)
            val existing = sessions[key]
            if (existing == null) {
                sessions[key] = ActiveSession(SystemClock.elapsedRealtime(), validTitle)
            } else if (validTitle != null && existing.readingTitle != validTitle) {
                sessions[key] = existing.copy(readingTitle = validTitle)
            }
        }
    }

    suspend fun checkpoint(kind: EngagementKind, id: String) {
        val validId = requireValidId(id)
        val key = SessionKey(kind, validId)
        val now = SystemClock.elapsedRealtime()
        val interval = synchronized(sessionsLock) {
            val active = sessions[key] ?: return
            sessions[key] = active.copy(startedAtElapsedMillis = now)
            PendingEngagementDuration(
                kind = kind,
                id = validId,
                elapsedMillis = (now - active.startedAtElapsedMillis)
                    .coerceIn(0L, MAX_SINGLE_CHECKPOINT_MILLIS),
                readingTitle = active.readingTitle,
            )
        }
        if (interval.elapsedMillis > 0L) addDuration(interval)
    }

    /**
     * Removes the active session synchronously, before any coroutine can be delayed by I/O.
     * Callers that may immediately begin the same id use this to prevent a late end from deleting
     * the new session after a pause/resume or configuration change.
     */
    fun endNow(kind: EngagementKind, id: String): PendingEngagementDuration? {
        val validId = requireValidId(id)
        val key = SessionKey(kind, validId)
        val now = SystemClock.elapsedRealtime()
        val interval = synchronized(sessionsLock) {
            val active = sessions.remove(key) ?: return null
            PendingEngagementDuration(
                kind = kind,
                id = validId,
                elapsedMillis = (now - active.startedAtElapsedMillis)
                    .coerceIn(0L, MAX_SINGLE_CHECKPOINT_MILLIS),
                readingTitle = active.readingTitle,
            )
        }
        return interval.takeIf { it.elapsedMillis > 0L }
    }

    suspend fun commit(pending: PendingEngagementDuration) {
        addDuration(pending)
    }

    /**
     * Persists titles for already-timed books before a shelf entry can be removed. This also
     * upgrades v1 files while the old shelf still has enough information to recover the title.
     */
    suspend fun rememberReadingTitles(titlesById: Map<String, String>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(titlesById.size <= MAX_ITEMS_PER_KIND)
            val normalized = titlesById.entries.mapNotNull { (id, title) ->
                val validId = requireValidId(id)
                normalizeReadingTitle(title)?.let { validId to it }
            }.toMap()
            val current = readSnapshotOrEmpty()
            val updatedTitles = current.readingTitles.toMutableMap()
            current.readingTotalsMillis.forEach { (id, totalMillis) ->
                if (totalMillis > 0L) {
                    normalized[id]?.let { updatedTitles[id] = it }
                }
            }
            val updated = current.copy(readingTitles = updatedTitles.toSortedMap())
            if (updated == current) return@withContext
            writeVerified(updated)
            _snapshot.value = updated
        }
    }

    /** Commits outside feature ViewModel scopes so an Activity finish cannot cancel final time. */
    fun endAndCommit(kind: EngagementKind, id: String) {
        val pending = endNow(kind, id) ?: return
        persistenceScope.launch {
            runCatching { commit(pending) }
        }
    }

    suspend fun end(kind: EngagementKind, id: String) {
        endNow(kind, id)?.let { commit(it) }
    }

    private suspend fun addDuration(pending: PendingEngagementDuration) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = readSnapshotOrEmpty()
            val updated = when (pending.kind) {
                EngagementKind.GAME -> current.copy(
                    gameTotalsMillis = current.gameTotalsMillis.withAddedDuration(
                        pending.id,
                        pending.elapsedMillis,
                    ),
                )
                EngagementKind.READING -> current.copy(
                    readingTotalsMillis = current.readingTotalsMillis.withAddedDuration(
                        pending.id,
                        pending.elapsedMillis,
                    ),
                    readingTitles = current.readingTitles.withReadingTitle(
                        pending.id,
                        pending.readingTitle,
                    ),
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

    private fun Map<String, String>.withReadingTitle(
        id: String,
        title: String?,
    ): Map<String, String> {
        val validTitle = normalizeReadingTitle(title) ?: return this
        return toMutableMap().apply { this[id] = validTitle }.toSortedMap()
    }

    private fun readSnapshotOrEmpty(): EngagementTimeSnapshot {
        val candidates = listOf(
            File(directory, "$FILE_NAME.pending"),
            file,
            File(directory, "$FILE_NAME.bak"),
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                if (!candidate.isFile || candidate.length() !in 1..MAX_JSON_BYTES) {
                    return@runCatching null
                }
                decode(candidate.readText(Charsets.UTF_8))
            }.getOrNull()
        } ?: EngagementTimeSnapshot()
    }

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
        val backup = File(directory, "$FILE_NAME.bak")
        val committed = runCatching {
            if (file.isFile && file.length() in 1..MAX_JSON_BYTES) {
                decode(file.readText(Charsets.UTF_8))
            } else {
                null
            }
        }.getOrNull()
        if (committed != null) {
            FileOutputStream(backup).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            check(decode(backup.readText(Charsets.UTF_8)) == committed) {
                "Engagement time backup verification failed"
            }
        }
        try {
            Files.move(
                pending.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                pending.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        check(decode(file.readText(Charsets.UTF_8)) == value) {
            "Engagement time commit verification failed"
        }
    }

    private fun encode(value: EngagementTimeSnapshot): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("gameTotalsMillis", value.gameTotalsMillis.toJsonObject())
        .put("readingTotalsMillis", value.readingTotalsMillis.toJsonObject())
        .put("readingTitles", value.readingTitles.toReadingTitlesJsonObject())
        .toString()

    private fun decode(raw: String): EngagementTimeSnapshot {
        val root = JSONObject(raw)
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion in 1..SCHEMA_VERSION)
        val readingTotals = root.getJSONObject("readingTotalsMillis").decodeTotals()
        val readingTitles = if (schemaVersion >= 2) {
            root.getJSONObject("readingTitles").decodeReadingTitles().also { titles ->
                require(titles.keys.all(readingTotals::containsKey))
            }
        } else {
            emptyMap()
        }
        return EngagementTimeSnapshot(
            gameTotalsMillis = root.getJSONObject("gameTotalsMillis").decodeTotals(),
            readingTotalsMillis = readingTotals,
            readingTitles = readingTitles,
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

    private fun Map<String, String>.toReadingTitlesJsonObject(): JSONObject = JSONObject().apply {
        entries.sortedBy(Map.Entry<String, String>::key).forEach { (id, title) ->
            put(id, requireNotNull(normalizeReadingTitle(title)))
        }
    }

    private fun JSONObject.decodeReadingTitles(): Map<String, String> {
        require(length() <= MAX_ITEMS_PER_KIND)
        return keys().asSequence().associateWith { id ->
            requireValidId(id)
            getString(id).also { title ->
                require(title.isNotBlank() && title.length <= MAX_READING_TITLE_CHARS)
            }
        }.toSortedMap()
    }

    private fun normalizeReadingTitle(title: String?): String? = title
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_READING_TITLE_CHARS)

    private fun requireValidId(id: String): String = id.also {
        require(ID_PATTERN.matches(it)) { "Invalid engagement identifier" }
    }

    companion object {
        const val DIRECTORY_NAME = "engagement"
        const val FILE_NAME = "engagement-times-v1.json"
        private const val SCHEMA_VERSION = 2
        private const val MAX_JSON_BYTES = 2 * 1024 * 1024
        private const val MAX_ITEMS_PER_KIND = 2_000
        private const val MAX_READING_TITLE_CHARS = 240
        private const val MAX_SINGLE_CHECKPOINT_MILLIS = 6L * 60 * 60 * 1_000
        private const val MAX_TOTAL_MILLIS = 100L * 365 * 24 * 60 * 60 * 1_000
        private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,256}")
    }
}
