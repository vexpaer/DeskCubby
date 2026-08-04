package com.deskcubby.app.data.statistics

import android.content.Context
import androidx.room.withTransaction
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.LegacyStatisticsMigrationDao
import com.deskcubby.app.data.local.LegacyStatisticsMigrationEntity
import com.deskcubby.app.data.local.StepDayEntity
import com.deskcubby.app.data.local.StepHistoryEntity
import com.deskcubby.app.data.local.StepHistoryRoomRow
import com.deskcubby.app.data.local.StepStatisticsDao
import com.deskcubby.app.data.local.UsageAppDurationEntity
import com.deskcubby.app.data.local.UsageDayEntity
import com.deskcubby.app.data.local.UsageHistoryEntity
import com.deskcubby.app.data.local.UsageHistoryRoomRow
import com.deskcubby.app.data.local.UsageStatisticsDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Room-backed source of truth for this device's usage history. */
@Singleton
class UsageStatisticsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val dao: UsageStatisticsDao,
    private val migrationDao: LegacyStatisticsMigrationDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerMutex = Mutex()
    private val mutableHistory = MutableStateFlow(UsageStatisticsHistory())
    private val initialized = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        migrateLegacyFileIfNeeded()
        readCurrent().also { mutableHistory.value = it }
    }

    val history: StateFlow<UsageStatisticsHistory> = mutableHistory.asStateFlow()

    init {
        scope.launch {
            initialized.await()
            dao.observeHistoryRows(LOCAL_USAGE_HISTORY_OWNER_ID).collect { rows ->
                mutableHistory.value = usageHistoryFromRoomRows(rows)
            }
        }
    }

    suspend fun current(): UsageStatisticsHistory {
        initialized.await()
        return readCurrent().also { mutableHistory.value = it }
    }

    internal suspend fun awaitReady() {
        initialized.await()
    }

    internal fun cancelForTest() {
        scope.cancel()
    }

    suspend fun update(
        transform: (UsageStatisticsHistory) -> UsageStatisticsHistory,
    ): UsageStatisticsHistory = writerMutex.withLock {
        initialized.await()
        database.withTransaction {
            val current = usageHistoryFromRoomRows(
                dao.getHistoryRows(LOCAL_USAGE_HISTORY_OWNER_ID),
            )
            canonicalUsageStatisticsHistory(transform(current)).also { next ->
                validateUsageStatisticsHistory(next)
                replaceUsageHistoryInRoom(dao, LOCAL_USAGE_HISTORY_OWNER_ID, next)
            }
        }.also { mutableHistory.value = it }
    }

    suspend fun reload(): UsageStatisticsHistory = current()

    /** Encodes Room state only at the explicit Android-v4 export boundary. */
    suspend fun canonicalSnapshot(): UsageStatisticsSnapshot {
        val canonical = canonicalUsageStatisticsHistory(current())
        val (encoded, verified) = encodeAndVerifyStatisticsValue(
            value = canonical,
            encode = UsageStatisticsJsonCodec::encode,
            decode = UsageStatisticsJsonCodec::decode,
        )
        return UsageStatisticsSnapshot(
            bytes = encoded.toByteArray(StandardCharsets.UTF_8),
            history = verified,
        )
    }

    private suspend fun readCurrent(): UsageStatisticsHistory = database.withTransaction {
        usageHistoryFromRoomRows(dao.getHistoryRows(LOCAL_USAGE_HISTORY_OWNER_ID))
    }

    private suspend fun migrateLegacyFileIfNeeded() {
        if (migrationDao.isComplete(LEGACY_USAGE_FILE_MIGRATION_ID)) return
        val incoming = runCatching {
            readLegacyStatisticsFile(
                filesDir = context.filesDir,
                fileName = USAGE_STATISTICS_FILE_NAME,
                decode = UsageStatisticsJsonCodec::decode,
            )
        }.getOrElse {
            rethrowStatisticsMigrationCancellation(it)
            // The original bytes remain untouched and the marker stays absent, so a later
            // process can retry after recovery. Room remains the only runtime authority.
            return
        }
        database.withTransaction {
            if (migrationDao.isComplete(LEGACY_USAGE_FILE_MIGRATION_ID)) return@withTransaction
            val current = usageHistoryFromRoomRows(
                dao.getHistoryRows(LOCAL_USAGE_HISTORY_OWNER_ID),
            )
            val merged = incoming?.let { mergeUsageDeviceHistory(current, it) } ?: current
            replaceUsageHistoryInRoom(dao, LOCAL_USAGE_HISTORY_OWNER_ID, merged)
            migrationDao.markComplete(
                LegacyStatisticsMigrationEntity(
                    migrationId = LEGACY_USAGE_FILE_MIGRATION_ID,
                    importedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                ),
            )
        }
    }
}

data class UsageStatisticsSnapshot internal constructor(
    val bytes: ByteArray,
    val history: UsageStatisticsHistory,
)

/** Room-backed source of truth for Health Connect daily aggregates. */
@Singleton
class StepStatisticsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val dao: StepStatisticsDao,
    private val migrationDao: LegacyStatisticsMigrationDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writerMutex = Mutex()
    private val mutableHistory = MutableStateFlow(StepStatisticsHistory())
    private val initialized = scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        migrateLegacyFileIfNeeded()
        readCurrent().also { mutableHistory.value = it }
    }

    val history: StateFlow<StepStatisticsHistory> = mutableHistory.asStateFlow()

    init {
        scope.launch {
            initialized.await()
            dao.observeHistoryRows(STEP_HISTORY_ID).collect { rows ->
                mutableHistory.value = stepHistoryFromRoomRows(rows)
            }
        }
    }

    suspend fun current(): StepStatisticsHistory {
        initialized.await()
        return readCurrent().also { mutableHistory.value = it }
    }

    suspend fun update(
        transform: (StepStatisticsHistory) -> StepStatisticsHistory,
    ): StepStatisticsHistory = writerMutex.withLock {
        initialized.await()
        database.withTransaction {
            val current = stepHistoryFromRoomRows(dao.getHistoryRows(STEP_HISTORY_ID))
            canonicalStepStatisticsHistory(transform(current)).also { next ->
                validateStepStatisticsHistory(next)
                replaceStepHistoryInRoom(dao, next)
            }
        }.also { mutableHistory.value = it }
    }

    suspend fun reload(): StepStatisticsHistory = current()

    internal fun cancelForTest() {
        scope.cancel()
    }

    private suspend fun readCurrent(): StepStatisticsHistory = database.withTransaction {
        stepHistoryFromRoomRows(dao.getHistoryRows(STEP_HISTORY_ID))
    }

    private suspend fun migrateLegacyFileIfNeeded() {
        if (migrationDao.isComplete(LEGACY_STEP_FILE_MIGRATION_ID)) return
        val incoming = runCatching {
            readLegacyStatisticsFile(
                filesDir = context.filesDir,
                fileName = STEP_STATISTICS_FILE_NAME,
                decode = StepStatisticsJsonCodec::decode,
            )
        }.getOrElse {
            rethrowStatisticsMigrationCancellation(it)
            return
        }
        database.withTransaction {
            if (migrationDao.isComplete(LEGACY_STEP_FILE_MIGRATION_ID)) return@withTransaction
            val current = stepHistoryFromRoomRows(dao.getHistoryRows(STEP_HISTORY_ID))
            val merged = incoming?.let { mergeStepStatisticsHistory(current, it) } ?: current
            replaceStepHistoryInRoom(dao, merged)
            migrationDao.markComplete(
                LegacyStatisticsMigrationEntity(
                    migrationId = LEGACY_STEP_FILE_MIGRATION_ID,
                    importedAtEpochMillis = System.currentTimeMillis().coerceAtLeast(0L),
                ),
            )
        }
    }
}

internal suspend fun replaceUsageHistoryInRoom(
    dao: UsageStatisticsDao,
    ownerId: String,
    history: UsageStatisticsHistory,
) {
    val canonical = canonicalUsageStatisticsHistory(history)
    validateUsageStatisticsHistory(canonical)
    dao.upsertHistory(
        UsageHistoryEntity(
            ownerId = ownerId,
            trackingStartedOn = canonical.trackingStartedOn?.toString(),
            backfillCompletedThrough = canonical.backfillCompletedThrough?.toString(),
        ),
    )
    dao.deleteDays(ownerId)
    if (canonical.days.isNotEmpty()) {
        dao.insertDays(
            canonical.days.map { day ->
                UsageDayEntity(
                    ownerId = ownerId,
                    dateIso = day.date.toString(),
                    zoneId = day.zoneId,
                    state = day.state.name,
                    collectedAtEpochMillis = day.collectedAtEpochMillis,
                )
            },
        )
        val apps = canonical.days.flatMap { day ->
            day.apps.map { app ->
                UsageAppDurationEntity(
                    ownerId = ownerId,
                    dateIso = day.date.toString(),
                    packageName = app.packageName,
                    foregroundMillis = app.foregroundMillis,
                )
            }
        }
        if (apps.isNotEmpty()) dao.insertAppDurations(apps)
    }
}

internal fun usageHistoryFromRoomRows(
    rows: List<UsageHistoryRoomRow>,
): UsageStatisticsHistory {
    if (rows.isEmpty()) return UsageStatisticsHistory()
    val first = rows.first()
    val days = rows
        .filter { it.dayDateIso != null }
        .groupBy { checkNotNull(it.dayDateIso) }
        .map { (dateIso, dayRows) ->
            val day = dayRows.first()
            UsageStatisticsDay(
                date = LocalDate.parse(dateIso),
                zoneId = checkNotNull(day.dayZoneId),
                state = StatisticsDayState.valueOf(checkNotNull(day.dayState)),
                collectedAtEpochMillis = checkNotNull(day.dayCollectedAtEpochMillis),
                apps = dayRows.mapNotNull { row ->
                    row.packageName?.let { packageName ->
                        UsageAppDuration(
                            packageName = packageName,
                            foregroundMillis = checkNotNull(row.foregroundMillis),
                        )
                    }
                },
            )
        }
        .sortedBy(UsageStatisticsDay::date)
    return UsageStatisticsHistory(
        trackingStartedOn = first.trackingStartedOn?.let(LocalDate::parse),
        days = days,
        backfillCompletedThrough = first.backfillCompletedThrough?.let(LocalDate::parse),
    ).also(::validateUsageStatisticsHistory)
}

internal suspend fun replaceStepHistoryInRoom(
    dao: StepStatisticsDao,
    history: StepStatisticsHistory,
) {
    val canonical = canonicalStepStatisticsHistory(history)
    validateStepStatisticsHistory(canonical)
    val baseline = canonical.deviceSensorBaseline
    dao.upsertHistory(
        StepHistoryEntity(
            id = STEP_HISTORY_ID,
            trackingStartedOn = canonical.trackingStartedOn?.toString(),
            baselineDateIso = baseline?.date?.toString(),
            baselineCumulativeSteps = baseline?.cumulativeSteps,
            baselineCapturedAtEpochMillis = baseline?.capturedAtEpochMillis,
        ),
    )
    dao.deleteDays(STEP_HISTORY_ID)
    if (canonical.days.isNotEmpty()) {
        dao.insertDays(
            canonical.days.map { day ->
                StepDayEntity(
                    historyId = STEP_HISTORY_ID,
                    dateIso = day.date.toString(),
                    zoneId = day.zoneId,
                    state = day.state.name,
                    collectedAtEpochMillis = day.collectedAtEpochMillis,
                    steps = day.steps,
                    distanceMeters = day.distanceMeters,
                    activeCaloriesKilocalories = day.activeCaloriesKilocalories,
                )
            },
        )
    }
}

internal fun stepHistoryFromRoomRows(rows: List<StepHistoryRoomRow>): StepStatisticsHistory {
    if (rows.isEmpty()) return StepStatisticsHistory()
    val first = rows.first()
    val baseline = first.baselineDateIso?.let { dateIso ->
        DeviceStepSensorBaseline(
            date = LocalDate.parse(dateIso),
            cumulativeSteps = checkNotNull(first.baselineCumulativeSteps),
            capturedAtEpochMillis = checkNotNull(first.baselineCapturedAtEpochMillis),
        )
    }
    return StepStatisticsHistory(
        trackingStartedOn = first.trackingStartedOn?.let(LocalDate::parse),
        days = rows.mapNotNull { row ->
            row.dayDateIso?.let { dateIso ->
                StepStatisticsDay(
                    date = LocalDate.parse(dateIso),
                    zoneId = checkNotNull(row.dayZoneId),
                    state = StatisticsDayState.valueOf(checkNotNull(row.dayState)),
                    collectedAtEpochMillis = checkNotNull(row.dayCollectedAtEpochMillis),
                    steps = row.steps,
                    distanceMeters = row.distanceMeters,
                    activeCaloriesKilocalories = row.activeCaloriesKilocalories,
                )
            }
        }.sortedBy(StepStatisticsDay::date),
        deviceSensorBaseline = baseline,
    ).also(::validateStepStatisticsHistory)
}

internal fun canonicalStepStatisticsHistory(
    history: StepStatisticsHistory,
): StepStatisticsHistory = history.copy(days = history.days.sortedBy(StepStatisticsDay::date))

internal fun mergeStepStatisticsHistory(
    current: StepStatisticsHistory,
    incoming: StepStatisticsHistory,
): StepStatisticsHistory {
    val days = (current.days + incoming.days)
        .groupBy(StepStatisticsDay::date)
        .mapValues { (_, candidates) ->
            candidates.maxWithOrNull(
                compareBy<StepStatisticsDay> {
                    if (it.state == StatisticsDayState.FINAL) 1 else 0
                }.thenBy(StepStatisticsDay::collectedAtEpochMillis),
            ) ?: error("A step date group cannot be empty.")
        }
        .values
        .sortedBy(StepStatisticsDay::date)
    val baseline = listOfNotNull(current.deviceSensorBaseline, incoming.deviceSensorBaseline)
        .maxByOrNull(DeviceStepSensorBaseline::capturedAtEpochMillis)
    return StepStatisticsHistory(
        trackingStartedOn = listOfNotNull(
            current.trackingStartedOn,
            incoming.trackingStartedOn,
            days.firstOrNull()?.date,
        ).minOrNull(),
        days = days,
        deviceSensorBaseline = baseline,
    )
}

private suspend fun <T> readLegacyStatisticsFile(
    filesDir: File,
    fileName: String,
    decode: (String) -> T,
): T? = withContext(Dispatchers.IO) {
    val file = prepareStatisticsFile(
        filesDir = filesDir,
        fileName = fileName,
        validator = { bytes -> decode(bytes.toString(StandardCharsets.UTF_8)) },
    )
    if (!file.isFile) return@withContext null
    decode(readBoundedStatisticsFile(file).toString(StandardCharsets.UTF_8))
}

internal fun readBoundedStatisticsFile(
    file: File,
    maximumBytes: Int = MAX_STATISTICS_JSON_BYTES,
): ByteArray {
    require(maximumBytes > 0)
    if (file.length() !in 1..maximumBytes.toLong()) {
        throw StatisticsJsonException("Statistics JSON has an invalid size.")
    }
    return file.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumBytes) {
                throw StatisticsJsonException("Statistics JSON exceeds $maximumBytes bytes.")
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    }
}

const val USAGE_STATISTICS_FILE_NAME = "usage-statistics.json"
const val STEP_STATISTICS_FILE_NAME = "step-statistics.json"
internal const val LOCAL_USAGE_HISTORY_OWNER_ID = "__local_android_usage__"
internal const val STEP_HISTORY_ID = 1
internal const val LEGACY_USAGE_FILE_MIGRATION_ID = "usage-statistics-json-v1"
internal const val LEGACY_STEP_FILE_MIGRATION_ID = "step-statistics-json-v1"
internal const val LEGACY_USAGE_DEVICE_CACHE_MIGRATION_ID = "usage-device-cache-json-v1"
internal const val LEGACY_USAGE_DEVICE_CACHE_FILE_MIGRATION_PREFIX =
    "usage-device-cache-json-v1:file:"

internal fun rethrowStatisticsMigrationCancellation(error: Throwable) {
    if (error is CancellationException) throw error
}

internal fun <T> encodeAndVerifyStatisticsValue(
    value: T,
    encode: (T) -> String,
    decode: (String) -> T,
    maximumBytes: Int = MAX_STATISTICS_JSON_BYTES,
): Pair<String, T> {
    require(maximumBytes > 0)
    val encoded = encode(value)
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > maximumBytes) {
        throw StatisticsJsonException("Statistics JSON exceeds $maximumBytes bytes.")
    }
    val decoded = decode(encoded)
    if (decoded != value) {
        throw StatisticsJsonException("Statistics serialization verification failed.")
    }
    return encoded to decoded
}

internal fun canonicalUsageStatisticsHistory(
    history: UsageStatisticsHistory,
): UsageStatisticsHistory = history.copy(
    days = history.days
        .sortedBy(UsageStatisticsDay::date)
        .map { day ->
            day.copy(apps = day.apps.sortedBy(UsageAppDuration::packageName))
        },
)

internal fun <T> verifyStatisticsExportReadBack(
    expectedBytes: ByteArray,
    actualBytes: ByteArray,
    expectedValue: T,
    decode: (String) -> T,
    maximumBytes: Int = MAX_STATISTICS_JSON_BYTES,
): T {
    require(maximumBytes > 0)
    if (expectedBytes.size > maximumBytes || actualBytes.size > maximumBytes) {
        throw StatisticsJsonException("Statistics JSON exceeds $maximumBytes bytes.")
    }
    if (!actualBytes.contentEquals(expectedBytes)) {
        throw StatisticsJsonException("Statistics export read-back did not match the written bytes.")
    }
    val decoded = decode(actualBytes.toString(StandardCharsets.UTF_8))
    if (decoded != expectedValue) {
        throw StatisticsJsonException("Statistics export read-back verification failed.")
    }
    return decoded
}
