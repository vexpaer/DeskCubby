package com.deskcubby.app.data.statistics

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

data class UsageDeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val platform: String = USAGE_DEVICE_PLATFORM_ANDROID,
    val updatedAtEpochMillis: Long,
)

data class UsageDeviceRecord(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val updatedAtEpochMillis: Long,
    val history: UsageStatisticsHistory,
)

object UsageDeviceJsonCodec {
    fun encode(record: UsageDeviceRecord): String {
        validateUsageDeviceRecord(record)
        val root = JSONObject()
            .put(KEY_SCHEMA_VERSION, USAGE_DEVICE_SCHEMA_VERSION)
            .put(KEY_DEVICE_ID, record.deviceId)
            .put(KEY_DEVICE_NAME, record.deviceName)
            .put(KEY_PLATFORM, record.platform)
            .put(KEY_UPDATED_AT, record.updatedAtEpochMillis)
            .put(KEY_HISTORY, JSONObject(UsageStatisticsJsonCodec.encode(record.history)))
        val encoded = root.toString()
        requireUsageDeviceSize(encoded)
        return encoded
    }

    fun decode(json: String): UsageDeviceRecord {
        requireUsageDeviceSize(json)
        return try {
            val tokener = JSONTokener(json)
            val root = tokener.nextValue()
            require(root is JSONObject) { "Usage device root must be an object." }
            require(tokener.nextClean() == '\u0000') {
                "Unexpected content after usage device JSON."
            }
            val keys = root.keys().asSequence().toSet()
            require(
                keys == setOf(
                    KEY_SCHEMA_VERSION,
                    KEY_DEVICE_ID,
                    KEY_DEVICE_NAME,
                    KEY_PLATFORM,
                    KEY_UPDATED_AT,
                    KEY_HISTORY,
                ),
            ) { "Usage device JSON contains missing or unknown fields." }
            require(root.requiredIntegralLong(KEY_SCHEMA_VERSION) == USAGE_DEVICE_SCHEMA_VERSION.toLong()) {
                "Unsupported usage device schema."
            }
            UsageDeviceRecord(
                deviceId = normalizeUsageDeviceId(root.getString(KEY_DEVICE_ID)),
                deviceName = normalizeUsageDeviceName(root.getString(KEY_DEVICE_NAME)),
                platform = normalizeUsageDevicePlatform(root.getString(KEY_PLATFORM)),
                updatedAtEpochMillis = root.requiredIntegralLong(KEY_UPDATED_AT).also {
                    require(it >= 0L) { "Usage device timestamp must not be negative." }
                },
                history = UsageStatisticsJsonCodec.decode(
                    root.getJSONObject(KEY_HISTORY).toString(),
                ),
            ).also(::validateUsageDeviceRecord)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: JSONException) {
            throw StatisticsJsonException("Malformed usage device JSON.", error)
        } catch (error: RuntimeException) {
            throw StatisticsJsonException("Malformed usage device JSON.", error)
        }
    }
}

@Singleton
class UsageDeviceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageStore: UsageStatisticsStore,
) {
    private val dataStore = context.usageDeviceIdentityDataStore
    private val cacheDirectory = File(context.filesDir, USAGE_DEVICE_CACHE_DIRECTORY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identityMutex = Mutex()
    private val cacheMutex = Mutex()
    private val cacheLoaded = CompletableDeferred<Unit>()
    private val mutableIdentity = MutableStateFlow<UsageDeviceIdentity?>(null)
    private val mutableForeignRecords = MutableStateFlow<Map<String, UsageDeviceRecord>>(emptyMap())

    val identity: Flow<UsageDeviceIdentity> = mutableIdentity.filterNotNull()

    val records: Flow<List<UsageDeviceRecord>> = combine(
        identity,
        usageStore.history,
        mutableForeignRecords,
    ) { identity, ownHistory, foreign ->
        buildList {
            add(identity.toRecord(ownHistory))
            addAll(
                foreign.values
                    .asSequence()
                    .filterNot { it.deviceId == identity.deviceId }
                    .sortedWith(
                        compareBy<UsageDeviceRecord> { it.deviceName.lowercase() }
                            .thenBy(UsageDeviceRecord::deviceId),
                    ),
            )
        }
    }.distinctUntilChanged()

    init {
        scope.launch {
            try {
                cacheMutex.withLock {
                    mutableForeignRecords.value = readCachedRecords()
                }
            } finally {
                cacheLoaded.complete(Unit)
            }
        }
        scope.launch {
            ensureIdentity()
            dataStore.data
                .map(::decodeIdentity)
                .filterNotNull()
                .distinctUntilChanged()
                .collect { mutableIdentity.value = it }
        }
    }

    suspend fun currentIdentity(): UsageDeviceIdentity {
        ensureIdentity()
        return checkNotNull(mutableIdentity.value ?: decodeIdentity(dataStore.data.first()))
    }

    suspend fun renameCurrentDevice(rawName: String) {
        val name = normalizeUsageDeviceName(rawName)
        identityMutex.withLock {
            val current = currentIdentityUnlocked()
            val newestHistoryTimestamp = usageStore.history.first()
                .days
                .maxOfOrNull(UsageStatisticsDay::collectedAtEpochMillis)
                ?: 0L
            val updated = current.copy(
                deviceName = name,
                updatedAtEpochMillis = nextUsageDeviceUpdatedAt(
                    now = System.currentTimeMillis(),
                    current = current.updatedAtEpochMillis,
                    newestHistory = newestHistoryTimestamp,
                ),
            )
            dataStore.edit { prefs ->
                prefs[UsageDeviceIdentityKeys.deviceId] = updated.deviceId
                prefs[UsageDeviceIdentityKeys.deviceName] = updated.deviceName
                prefs[UsageDeviceIdentityKeys.updatedAt] = updated.updatedAtEpochMillis
            }
            mutableIdentity.value = updated
        }
    }

    suspend fun snapshotAll(): List<UsageDeviceRecord> {
        cacheLoaded.await()
        val identity = currentIdentity()
        val ownSnapshot = usageStore.canonicalSnapshot().history
        return cacheMutex.withLock {
            buildList {
                add(identity.toRecord(ownSnapshot))
                addAll(
                    mutableForeignRecords.value.values
                        .filterNot { it.deviceId == identity.deviceId },
                )
            }.sortedBy(UsageDeviceRecord::deviceId)
        }
    }

    suspend fun mergeIncoming(record: UsageDeviceRecord): UsageDeviceRecord {
        val verified = UsageDeviceJsonCodec.decode(UsageDeviceJsonCodec.encode(record))
        cacheLoaded.await()
        val identity = currentIdentity()
        return if (verified.deviceId == identity.deviceId) {
            val merged = usageStore.update { current ->
                mergeUsageDeviceHistory(current, verified.history)
            }
            identity.toRecord(merged)
        } else {
            cacheMutex.withLock {
                val current = mutableForeignRecords.value[verified.deviceId]
                require(current != null || mutableForeignRecords.value.size < MAX_USAGE_DEVICES - 1) {
                    "Too many usage devices."
                }
                val merged = current?.mergeWith(verified) ?: verified
                writeCachedRecord(merged)
                mutableForeignRecords.value = mutableForeignRecords.value + (
                    merged.deviceId to merged
                    )
                merged
            }
        }
    }

    suspend fun mergeBackup(records: List<UsageDeviceRecord>) {
        require(records.size <= MAX_USAGE_DEVICES) { "Too many usage devices." }
        val verified = records.map { record ->
            UsageDeviceJsonCodec.decode(UsageDeviceJsonCodec.encode(record))
        }
        val before = snapshotAll()
        try {
            verified.forEach { mergeIncoming(it) }
        } catch (error: Throwable) {
            runCatching { replaceAllForRollback(before) }
            throw error
        }
    }

    internal suspend fun replaceAllForRollback(records: List<UsageDeviceRecord>) {
        cacheLoaded.await()
        val identity = currentIdentity()
        val own = records.firstOrNull { it.deviceId == identity.deviceId }
            ?: identity.toRecord(UsageStatisticsHistory())
        usageStore.update { own.history }
        cacheMutex.withLock {
            val foreign = records
                .asSequence()
                .filterNot { it.deviceId == identity.deviceId }
                .associateBy(UsageDeviceRecord::deviceId)
            foreign.values.forEach(::writeCachedRecord)
            cachedRecordFiles().forEach { file ->
                val id = file.name.removeSuffix(USAGE_DEVICE_FILE_SUFFIX)
                if (id !in foreign && file.parentFile?.canonicalFile == cacheDirectory.canonicalFile) {
                    file.delete()
                }
            }
            mutableForeignRecords.value = foreign
        }
    }

    private suspend fun ensureIdentity() {
        identityMutex.withLock {
            val current = decodeIdentity(dataStore.data.first())
            if (current != null) {
                mutableIdentity.value = current
                return
            }
            val now = System.currentTimeMillis().coerceAtLeast(0L)
            val created = UsageDeviceIdentity(
                deviceId = UUID.randomUUID().toString(),
                deviceName = defaultUsageDeviceName(),
                updatedAtEpochMillis = now,
            )
            dataStore.edit { prefs ->
                prefs[UsageDeviceIdentityKeys.deviceId] = created.deviceId
                prefs[UsageDeviceIdentityKeys.deviceName] = created.deviceName
                prefs[UsageDeviceIdentityKeys.updatedAt] = created.updatedAtEpochMillis
            }
            mutableIdentity.value = created
        }
    }

    private suspend fun currentIdentityUnlocked(): UsageDeviceIdentity {
        val current = mutableIdentity.value ?: decodeIdentity(dataStore.data.first())
        if (current != null) return current
        val now = System.currentTimeMillis().coerceAtLeast(0L)
        return UsageDeviceIdentity(
            deviceId = UUID.randomUUID().toString(),
            deviceName = defaultUsageDeviceName(),
            updatedAtEpochMillis = now,
        )
    }

    private fun decodeIdentity(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): UsageDeviceIdentity? {
        val id = prefs[UsageDeviceIdentityKeys.deviceId]
            ?.let { runCatching { normalizeUsageDeviceId(it) }.getOrNull() }
            ?: return null
        val name = prefs[UsageDeviceIdentityKeys.deviceName]
            ?.let { runCatching { normalizeUsageDeviceName(it) }.getOrNull() }
            ?: return null
        val updatedAt = prefs[UsageDeviceIdentityKeys.updatedAt]
            ?.takeIf { it >= 0L }
            ?: return null
        return UsageDeviceIdentity(
            deviceId = id,
            deviceName = name,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun readCachedRecords(): Map<String, UsageDeviceRecord> {
        if (!cacheDirectory.exists()) return emptyMap()
        val records = LinkedHashMap<String, UsageDeviceRecord>()
        cachedRecordFiles()
            .take(MAX_USAGE_DEVICES - 1)
            .forEach { file ->
                val record = runCatching {
                    val bytes = readBounded(file)
                    UsageDeviceJsonCodec.decode(bytes.toString(StandardCharsets.UTF_8))
                }.getOrNull() ?: return@forEach
                if (file.name == "${record.deviceId}$USAGE_DEVICE_FILE_SUFFIX") {
                    records[record.deviceId] = record
                }
            }
        return records
    }

    private fun writeCachedRecord(record: UsageDeviceRecord) {
        ensureCacheDirectory()
        val json = UsageDeviceJsonCodec.encode(record)
        val file = File(cacheDirectory, "${record.deviceId}$USAGE_DEVICE_FILE_SUFFIX")
        require(file.parentFile?.canonicalFile == cacheDirectory.canonicalFile) {
            "Usage device cache path is invalid."
        }
        val atomicFile = AtomicFile(file)
        var output = atomicFile.startWrite()
        try {
            output.write(json.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
        val verified = UsageDeviceJsonCodec.decode(
            readBounded(file).toString(StandardCharsets.UTF_8),
        )
        require(verified == record) { "Usage device cache verification failed." }
    }

    private fun ensureCacheDirectory() {
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create usage device cache.")
        }
        require(cacheDirectory.isDirectory) { "Usage device cache is not a directory." }
    }

    private fun cachedRecordFiles(): List<File> = cacheDirectory.listFiles()
        .orEmpty()
        .filter { file ->
            file.isFile &&
                file.name.endsWith(USAGE_DEVICE_FILE_SUFFIX) &&
                runCatching {
                    normalizeUsageDeviceId(file.name.removeSuffix(USAGE_DEVICE_FILE_SUFFIX))
                }.isSuccess
        }
        .sortedBy(File::getName)

    private fun readBounded(file: File): ByteArray {
        if (file.length() !in 1..MAX_USAGE_DEVICE_JSON_BYTES.toLong()) {
            throw StatisticsJsonException("Usage device JSON has an invalid size.")
        }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_USAGE_DEVICE_JSON_BYTES) {
                    throw StatisticsJsonException("Usage device JSON is too large.")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }
}

fun mergeUsageDeviceHistory(
    current: UsageStatisticsHistory,
    incoming: UsageStatisticsHistory,
): UsageStatisticsHistory {
    val days = (current.days + incoming.days)
        .groupBy(UsageStatisticsDay::date)
        .mapValues { (_, candidates) ->
            candidates.maxWithOrNull(
                compareBy<UsageStatisticsDay> {
                    if (it.state == StatisticsDayState.FINAL) 1 else 0
                }.thenBy(UsageStatisticsDay::collectedAtEpochMillis),
            ) ?: error("A usage date group cannot be empty.")
        }
        .values
        .sortedBy(UsageStatisticsDay::date)
    return UsageStatisticsHistory(
        trackingStartedOn = listOfNotNull(
            current.trackingStartedOn,
            incoming.trackingStartedOn,
            days.firstOrNull()?.date,
        ).minOrNull(),
        days = days,
        backfillCompletedThrough = listOfNotNull(
            current.backfillCompletedThrough,
            incoming.backfillCompletedThrough,
        ).maxOrNull(),
    )
}

fun combineUsageDeviceHistories(records: List<UsageDeviceRecord>): UsageStatisticsHistory {
    if (records.isEmpty()) return UsageStatisticsHistory()
    val days = records
        .flatMap { it.history.days }
        .groupBy(UsageStatisticsDay::date)
        .map { (date, sourceDays) ->
            val apps = sourceDays
                .flatMap(UsageStatisticsDay::apps)
                .groupBy(UsageAppDuration::packageName)
                .map { (packageName, durations) ->
                    UsageAppDuration(
                        packageName = packageName,
                        foregroundMillis = durations.fold(0L) { total, duration ->
                            saturatedAdd(total, duration.foregroundMillis)
                        },
                    )
                }
                .sortedBy(UsageAppDuration::packageName)
            val newest = sourceDays.maxBy(UsageStatisticsDay::collectedAtEpochMillis)
            UsageStatisticsDay(
                date = date,
                zoneId = newest.zoneId,
                state = if (sourceDays.all { it.state == StatisticsDayState.FINAL }) {
                    StatisticsDayState.FINAL
                } else {
                    StatisticsDayState.OPEN
                },
                collectedAtEpochMillis = newest.collectedAtEpochMillis,
                apps = apps,
            )
        }
        .sortedBy(UsageStatisticsDay::date)
    return UsageStatisticsHistory(
        trackingStartedOn = records.mapNotNull { it.history.trackingStartedOn }.minOrNull(),
        days = days,
        // This projection is presentation-only. A date is known complete across all selected
        // devices only through the least advanced device watermark.
        backfillCompletedThrough = records
            .map { it.history.backfillCompletedThrough }
            .takeIf { values -> values.all { it != null } }
            ?.filterNotNull()
            ?.minOrNull(),
    )
}

private fun UsageDeviceIdentity.toRecord(history: UsageStatisticsHistory): UsageDeviceRecord =
    UsageDeviceRecord(
        deviceId = deviceId,
        deviceName = deviceName,
        platform = platform,
        updatedAtEpochMillis = maxOf(
            updatedAtEpochMillis,
            history.days.maxOfOrNull(UsageStatisticsDay::collectedAtEpochMillis) ?: 0L,
        ),
        history = canonicalUsageStatisticsHistory(history),
    )

private fun UsageDeviceRecord.mergeWith(incoming: UsageDeviceRecord): UsageDeviceRecord {
    require(deviceId == incoming.deviceId)
    val newestMetadata = if (incoming.updatedAtEpochMillis >= updatedAtEpochMillis) {
        incoming
    } else {
        this
    }
    return newestMetadata.copy(
        updatedAtEpochMillis = maxOf(updatedAtEpochMillis, incoming.updatedAtEpochMillis),
        history = mergeUsageDeviceHistory(history, incoming.history),
    )
}

private fun validateUsageDeviceRecord(record: UsageDeviceRecord) {
    require(normalizeUsageDeviceId(record.deviceId) == record.deviceId)
    require(normalizeUsageDeviceName(record.deviceName) == record.deviceName)
    require(normalizeUsageDevicePlatform(record.platform) == record.platform)
    require(record.updatedAtEpochMillis >= 0L)
    require(
        record.updatedAtEpochMillis >=
            (record.history.days.maxOfOrNull(UsageStatisticsDay::collectedAtEpochMillis) ?: 0L),
    ) { "Usage device timestamp precedes its newest history row." }
    // The canonical codec performs all day/app/zone/count validation.
    UsageStatisticsJsonCodec.encode(record.history)
}

internal fun normalizeUsageDeviceId(raw: String): String {
    val normalized = UUID.fromString(raw.trim()).toString()
    require(normalized.length == 36) { "Usage device id is invalid." }
    return normalized
}

internal fun normalizeUsageDeviceName(raw: String): String {
    val normalized = raw.trim()
    require(normalized.isNotEmpty()) { "Device name must not be empty." }
    require(normalized.codePointCount(0, normalized.length) <= MAX_USAGE_DEVICE_NAME_CODE_POINTS) {
        "Device name is too long."
    }
    require(normalized.none(Character::isISOControl)) {
        "Device name contains control characters."
    }
    return normalized
}

private fun normalizeUsageDevicePlatform(raw: String): String {
    val normalized = raw.trim().lowercase()
    require(USAGE_DEVICE_PLATFORM_REGEX.matches(normalized)) {
        "Usage device platform is invalid."
    }
    return normalized
}

private fun defaultUsageDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    val candidate = listOf(manufacturer, model)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
        .joinToString(" ")
        .takeIf(String::isNotBlank)
        ?: "Android device"
    return limitUsageDeviceNameInput(candidate)
}

internal fun limitUsageDeviceNameInput(raw: String): String {
    val filtered = raw.filterNot(Character::isISOControl)
    val codePoints = filtered.codePointCount(0, filtered.length)
    if (codePoints <= MAX_USAGE_DEVICE_NAME_CODE_POINTS) return filtered
    return filtered.substring(
        0,
        filtered.offsetByCodePoints(0, MAX_USAGE_DEVICE_NAME_CODE_POINTS),
    )
}

internal fun nextUsageDeviceUpdatedAt(
    now: Long,
    current: Long,
    newestHistory: Long,
): Long {
    val floor = maxOf(now.coerceAtLeast(0L), current, newestHistory)
    return if (floor == Long.MAX_VALUE) floor else floor + 1L
}

private fun requireUsageDeviceSize(json: String) {
    require(
        json.length <= MAX_USAGE_DEVICE_JSON_BYTES &&
            json.toByteArray(StandardCharsets.UTF_8).size <= MAX_USAGE_DEVICE_JSON_BYTES,
    ) { "Usage device JSON is too large." }
}

private fun JSONObject.requiredIntegralLong(key: String): Long {
    val value = get(key)
    return when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw StatisticsJsonException("$key must be an integer.")
    }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

private val Context.usageDeviceIdentityDataStore by preferencesDataStore(
    name = "usage_device_identity",
)

private object UsageDeviceIdentityKeys {
    val deviceId = stringPreferencesKey("device_id")
    val deviceName = stringPreferencesKey("device_name")
    val updatedAt = longPreferencesKey("updated_at")
}

const val USAGE_DEVICE_SCHEMA_VERSION = 1
const val USAGE_DEVICE_PLATFORM_ANDROID = "android"
const val MAX_USAGE_DEVICES = 64
const val MAX_USAGE_DEVICE_NAME_CODE_POINTS = 80
internal const val MAX_USAGE_DEVICE_JSON_BYTES = MAX_STATISTICS_JSON_BYTES + 64 * 1024
internal const val USAGE_DEVICE_REMOTE_PREFIX = "usage/v1/"
private const val USAGE_DEVICE_CACHE_DIRECTORY = "usage-device-histories"
private const val USAGE_DEVICE_FILE_SUFFIX = ".json"
private const val KEY_SCHEMA_VERSION = "schemaVersion"
private const val KEY_DEVICE_ID = "deviceId"
private const val KEY_DEVICE_NAME = "deviceName"
private const val KEY_PLATFORM = "platform"
private const val KEY_UPDATED_AT = "updatedAtEpochMillis"
private const val KEY_HISTORY = "history"
private val USAGE_DEVICE_PLATFORM_REGEX = Regex("[a-z][a-z0-9_-]{0,31}")
