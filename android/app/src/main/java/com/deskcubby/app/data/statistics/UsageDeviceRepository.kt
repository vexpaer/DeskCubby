package com.deskcubby.app.data.statistics

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.LegacyStatisticsMigrationDao
import com.deskcubby.app.data.local.LegacyStatisticsMigrationEntity
import com.deskcubby.app.data.local.UsageDeviceEntity
import com.deskcubby.app.data.local.UsageDeviceHistoryRoomRow
import com.deskcubby.app.data.local.UsageHistoryRoomRow
import com.deskcubby.app.data.local.UsageStatisticsDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val database: AppDatabase,
    private val dao: UsageStatisticsDao,
    private val migrationDao: LegacyStatisticsMigrationDao,
) {
    private val dataStore = context.usageDeviceIdentityDataStore
    private val cacheDirectory = File(context.filesDir, USAGE_DEVICE_CACHE_DIRECTORY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identityMutex = Mutex()
    private val writerMutex = Mutex()
    private val mutableIdentity = MutableStateFlow<UsageDeviceIdentity?>(null)
    private val mutableForeignRecords = MutableStateFlow<Map<String, UsageDeviceRecord>>(emptyMap())
    private val initialized = scope.async(start = CoroutineStart.LAZY) {
        usageStore.awaitReady()
        val identity = currentIdentity()
        migrateLegacyCacheIfNeeded(identity)
        readForeignRecords().also { mutableForeignRecords.value = it }
    }

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
    }.combine(
        flow {
            initialized.await()
            emit(Unit)
        },
    ) { migratedRecords, _ -> migratedRecords }
        .distinctUntilChanged()

    init {
        scope.launch {
            initialized.await()
            dao.observeForeignHistoryRows().collect { rows ->
                mutableForeignRecords.value = usageDeviceRecordsFromRoomRows(rows)
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
            val newestHistoryTimestamp = usageStore.current()
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
        initialized.await()
        val identity = currentIdentity()
        val ownSnapshot = usageStore.canonicalSnapshot().history
        val foreign = readForeignRecords()
        return buildList {
            add(identity.toRecord(ownSnapshot))
            addAll(foreign.values.filterNot { it.deviceId == identity.deviceId })
        }.sortedBy(UsageDeviceRecord::deviceId)
    }

    suspend fun mergeIncoming(record: UsageDeviceRecord): UsageDeviceRecord {
        val verified = UsageDeviceJsonCodec.decode(UsageDeviceJsonCodec.encode(record))
        initialized.await()
        val identity = currentIdentity()
        return if (verified.deviceId == identity.deviceId) {
            val merged = usageStore.update { current ->
                mergeUsageDeviceHistory(current, verified.history)
            }
            identity.toRecord(merged)
        } else {
            writerMutex.withLock {
                database.withTransaction {
                    val current = usageDeviceRecordFromRoomRows(
                        dao.getForeignHistoryRows(verified.deviceId),
                    )
                    require(current != null || dao.foreignDeviceCount() < MAX_USAGE_DEVICES - 1) {
                        "Too many usage devices."
                    }
                    (current?.mergeWith(verified) ?: verified).also { merged ->
                        replaceUsageDeviceRecordInRoom(dao, merged)
                    }
                }
            }
        }
    }

    suspend fun mergeBackup(records: List<UsageDeviceRecord>) {
        require(records.size <= MAX_USAGE_DEVICES) { "Too many usage devices." }
        val verified = records.map { record ->
            UsageDeviceJsonCodec.decode(UsageDeviceJsonCodec.encode(record))
        }
        initialized.await()
        val identity = currentIdentity()
        writerMutex.withLock {
            database.withTransaction {
                var ownHistory = usageHistoryFromRoomRows(
                    dao.getHistoryRows(LOCAL_USAGE_HISTORY_OWNER_ID),
                )
                val foreign = usageDeviceRecordsFromRoomRows(
                    dao.getAllForeignHistoryRows(),
                ).toMutableMap()
                verified.forEach { record ->
                    if (record.deviceId == identity.deviceId) {
                        ownHistory = mergeUsageDeviceHistory(ownHistory, record.history)
                    } else {
                        foreign[record.deviceId] = foreign[record.deviceId]
                            ?.mergeWith(record)
                            ?: record
                    }
                }
                require(foreign.size <= MAX_USAGE_DEVICES - 1) { "Too many usage devices." }
                replaceUsageHistoryInRoom(dao, LOCAL_USAGE_HISTORY_OWNER_ID, ownHistory)
                replaceAllForeignRecordsInRoom(dao, foreign.values)
            }
        }
        usageStore.reload()
    }

    internal suspend fun replaceAllForRollback(records: List<UsageDeviceRecord>) {
        require(records.size <= MAX_USAGE_DEVICES) { "Too many usage devices." }
        val verified = records.map { record ->
            UsageDeviceJsonCodec.decode(UsageDeviceJsonCodec.encode(record))
        }
        initialized.await()
        val identity = currentIdentity()
        val own = verified.firstOrNull { it.deviceId == identity.deviceId }
            ?: identity.toRecord(UsageStatisticsHistory())
        val foreign = verified.filterNot { it.deviceId == identity.deviceId }
        require(foreign.map(UsageDeviceRecord::deviceId).distinct().size == foreign.size) {
            "Duplicate usage device."
        }
        writerMutex.withLock {
            database.withTransaction {
                replaceUsageHistoryInRoom(dao, LOCAL_USAGE_HISTORY_OWNER_ID, own.history)
                replaceAllForeignRecordsInRoom(dao, foreign)
            }
        }
        usageStore.reload()
    }

    internal fun cancelForTest() {
        scope.cancel()
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

    private suspend fun readForeignRecords(): Map<String, UsageDeviceRecord> =
        database.withTransaction {
            usageDeviceRecordsFromRoomRows(dao.getAllForeignHistoryRows())
        }

    private suspend fun migrateLegacyCacheIfNeeded(identity: UsageDeviceIdentity) {
        if (migrationDao.isComplete(LEGACY_USAGE_DEVICE_CACHE_MIGRATION_ID)) return
        val files = withContext(Dispatchers.IO) { cachedRecordFiles() }
        // Scan one extra candidate beyond the maximum foreign-device count. A malformed or
        // accidentally cached local-device file must not consume a foreign slot and hide the
        // final valid legacy record. The persisted repository still enforces 63 foreign rows.
        var everyFileMigrated = files.size <= MAX_USAGE_DEVICES
        var importedAny = false
        files.take(MAX_USAGE_DEVICES).forEach { file ->
            val fileMarkerId = legacyUsageDeviceFileMigrationId(file)
            if (migrationDao.isComplete(fileMarkerId)) return@forEach
            val record = runCatching {
                withContext(Dispatchers.IO) {
                    UsageDeviceJsonCodec.decode(
                        readBoundedStatisticsFile(
                            file = file,
                            maximumBytes = MAX_USAGE_DEVICE_JSON_BYTES,
                        ).toString(StandardCharsets.UTF_8),
                    ).also { decoded ->
                        require(file.name == "${decoded.deviceId}$USAGE_DEVICE_FILE_SUFFIX") {
                            "Usage device cache name does not match its record."
                        }
                    }
                }
            }.getOrElse {
                rethrowStatisticsMigrationCancellation(it)
                // A bad cache must not hide other devices. Leave it and its marker untouched,
                // import the remaining valid files, and retry this one on a later process start.
                everyFileMigrated = false
                return@forEach
            }
            database.withTransaction {
                if (migrationDao.isComplete(fileMarkerId)) return@withTransaction
                if (record.deviceId == identity.deviceId) {
                    val current = usageHistoryFromRoomRows(
                        dao.getHistoryRows(LOCAL_USAGE_HISTORY_OWNER_ID),
                    )
                    replaceUsageHistoryInRoom(
                        dao,
                        LOCAL_USAGE_HISTORY_OWNER_ID,
                        mergeUsageDeviceHistory(current, record.history),
                    )
                } else {
                    val current = usageDeviceRecordFromRoomRows(
                        dao.getForeignHistoryRows(record.deviceId),
                    )
                    require(current != null || dao.foreignDeviceCount() < MAX_USAGE_DEVICES - 1) {
                        "Too many usage devices."
                    }
                    replaceUsageDeviceRecordInRoom(dao, current?.mergeWith(record) ?: record)
                }
                migrationDao.markComplete(
                    LegacyStatisticsMigrationEntity(
                        migrationId = fileMarkerId,
                        importedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                    ),
                )
                importedAny = true
            }
        }
        if (everyFileMigrated) {
            database.withTransaction {
                migrationDao.markComplete(
                    LegacyStatisticsMigrationEntity(
                        migrationId = LEGACY_USAGE_DEVICE_CACHE_MIGRATION_ID,
                        importedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                    ),
                )
            }
        }
        if (importedAny) usageStore.reload()
    }

    private fun legacyUsageDeviceFileMigrationId(file: File): String =
        "$LEGACY_USAGE_DEVICE_CACHE_FILE_MIGRATION_PREFIX${file.name}"

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

}

internal suspend fun replaceUsageDeviceRecordInRoom(
    dao: UsageStatisticsDao,
    record: UsageDeviceRecord,
) {
    validateUsageDeviceRecord(record)
    replaceUsageHistoryInRoom(dao, record.deviceId, record.history)
    dao.upsertDevice(
        UsageDeviceEntity(
            deviceId = record.deviceId,
            deviceName = record.deviceName,
            platform = record.platform,
            updatedAtEpochMillis = record.updatedAtEpochMillis,
        ),
    )
}

internal suspend fun replaceAllForeignRecordsInRoom(
    dao: UsageStatisticsDao,
    records: Collection<UsageDeviceRecord>,
) {
    require(records.size <= MAX_USAGE_DEVICES - 1) { "Too many usage devices." }
    require(records.map(UsageDeviceRecord::deviceId).distinct().size == records.size) {
        "Duplicate usage device."
    }
    dao.deleteAllForeignHistories(LOCAL_USAGE_HISTORY_OWNER_ID)
    records.sortedBy(UsageDeviceRecord::deviceId).forEach { record ->
        replaceUsageDeviceRecordInRoom(dao, record)
    }
}

internal fun usageDeviceRecordFromRoomRows(
    rows: List<UsageDeviceHistoryRoomRow>,
): UsageDeviceRecord? {
    if (rows.isEmpty()) return null
    val first = rows.first()
    val historyRows = rows.map { row ->
        UsageHistoryRoomRow(
            ownerId = row.deviceId,
            trackingStartedOn = row.trackingStartedOn,
            backfillCompletedThrough = row.backfillCompletedThrough,
            dayDateIso = row.dayDateIso,
            dayZoneId = row.dayZoneId,
            dayState = row.dayState,
            dayCollectedAtEpochMillis = row.dayCollectedAtEpochMillis,
            packageName = row.packageName,
            foregroundMillis = row.foregroundMillis,
        )
    }
    return UsageDeviceRecord(
        deviceId = first.deviceId,
        deviceName = first.deviceName,
        platform = first.platform,
        updatedAtEpochMillis = first.updatedAtEpochMillis,
        history = usageHistoryFromRoomRows(historyRows),
    ).also(::validateUsageDeviceRecord)
}

internal fun usageDeviceRecordsFromRoomRows(
    rows: List<UsageDeviceHistoryRoomRow>,
): Map<String, UsageDeviceRecord> = rows
    .groupBy(UsageDeviceHistoryRoomRow::deviceId)
    .mapValues { (_, deviceRows) -> checkNotNull(usageDeviceRecordFromRoomRows(deviceRows)) }

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
