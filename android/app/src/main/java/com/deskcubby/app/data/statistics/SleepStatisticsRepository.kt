package com.deskcubby.app.data.statistics

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.room.withTransaction
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.SleepDeviceEntity
import com.deskcubby.app.data.local.SleepNightEntity
import com.deskcubby.app.data.local.SleepStatisticsDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class SleepRecordSource {
    ESTIMATED,
    MANUAL,
}

data class SleepNight(
    val wakeDate: LocalDate,
    val zoneId: String,
    val bedtimeEpochMillis: Long,
    val wakeEpochMillis: Long,
    val durationMinutes: Int,
    val source: SleepRecordSource,
    val updatedAtEpochMillis: Long,
)

data class SleepDeviceRecord(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val updatedAtEpochMillis: Long,
    val nights: List<SleepNight>,
)

object SleepDeviceJsonCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(record: SleepDeviceRecord): ByteArray {
        val nights = JSONArray()
        record.nights.sortedBy(SleepNight::wakeDate).forEach { night ->
            nights.put(
                JSONObject()
                    .put("wakeDate", night.wakeDate.toString())
                    .put("zoneId", night.zoneId)
                    .put("bedtimeEpochMillis", night.bedtimeEpochMillis)
                    .put("wakeEpochMillis", night.wakeEpochMillis)
                    .put("durationMinutes", night.durationMinutes)
                    .put("source", night.source.name)
                    .put("updatedAtEpochMillis", night.updatedAtEpochMillis),
            )
        }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("deviceId", record.deviceId)
            .put("deviceName", record.deviceName)
            .put("platform", record.platform)
            .put("updatedAtEpochMillis", record.updatedAtEpochMillis)
            .put("nights", nights)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): SleepDeviceRecord {
        require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024) { "Sleep sync payload is invalid." }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported sleep schema." }
        val array = root.getJSONArray("nights")
        require(array.length() <= 20_000) { "Too many sleep nights." }
        val nights = buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val bedtime = item.getLong("bedtimeEpochMillis")
                val wake = item.getLong("wakeEpochMillis")
                val duration = item.getInt("durationMinutes")
                require(wake > bedtime && duration in 1..(24 * 60))
                add(
                    SleepNight(
                        wakeDate = LocalDate.parse(item.getString("wakeDate")),
                        zoneId = item.getString("zoneId"),
                        bedtimeEpochMillis = bedtime,
                        wakeEpochMillis = wake,
                        durationMinutes = duration,
                        source = SleepRecordSource.valueOf(item.getString("source")),
                        updatedAtEpochMillis = item.getLong("updatedAtEpochMillis").coerceAtLeast(0L),
                    ),
                )
            }
        }
        return SleepDeviceRecord(
            deviceId = root.getString("deviceId"),
            deviceName = root.getString("deviceName"),
            platform = root.getString("platform"),
            updatedAtEpochMillis = root.getLong("updatedAtEpochMillis").coerceAtLeast(0L),
            nights = nights,
        )
    }
}

@Singleton
class SleepStatisticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val dao: SleepStatisticsDao,
    private val usageDeviceRepository: UsageDeviceRepository,
) {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val appOpsManager = context.getSystemService(AppOpsManager::class.java)
    private val refreshMutex = Mutex()

    val records: Flow<List<SleepDeviceRecord>> = combine(
        dao.observeDevices(),
        dao.observeNights(),
    ) { devices, nights ->
        val byDevice = nights.groupBy(SleepNightEntity::deviceId)
        devices.map { device ->
            SleepDeviceRecord(
                deviceId = device.deviceId,
                deviceName = device.deviceName,
                platform = device.platform,
                updatedAtEpochMillis = device.updatedAtEpochMillis,
                nights = byDevice[device.deviceId].orEmpty().map(SleepNightEntity::toDomain),
            )
        }
    }

    fun hasUsageAccess(): Boolean = runCatching {
        appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    suspend fun refreshRecent(
        days: Int = 14,
        clock: Clock = Clock.systemDefaultZone(),
    ): Boolean = refreshMutex.withLock {
        if (!hasUsageAccess()) return@withLock false
        val identity = usageDeviceRepository.identity.first()
        val zone = clock.zone
        val today = LocalDate.now(clock)
        var changed = false
        database.withTransaction {
            ensureLocalDevice(identity, clock.millis())
            repeat(days.coerceIn(1, 60)) { offset ->
                val wakeDate = today.minusDays(offset.toLong())
                val existing = dao.getNight(identity.deviceId, wakeDate.toString())
                if (existing?.source == SleepRecordSource.MANUAL.name) return@repeat
                val estimate = estimateNight(wakeDate, zone) ?: return@repeat
                val replacement = SleepNightEntity(
                    deviceId = identity.deviceId,
                    wakeDateIso = wakeDate.toString(),
                    zoneId = zone.id,
                    bedtimeEpochMillis = estimate.bedtimeEpochMillis,
                    wakeEpochMillis = estimate.wakeEpochMillis,
                    durationMinutes = estimate.durationMinutes,
                    source = SleepRecordSource.ESTIMATED.name,
                    updatedAtEpochMillis = clock.millis().coerceAtLeast(0L),
                )
                if (
                    existing == null ||
                    existing.bedtimeEpochMillis != replacement.bedtimeEpochMillis ||
                    existing.wakeEpochMillis != replacement.wakeEpochMillis
                ) {
                    dao.upsertNight(replacement)
                    changed = true
                }
            }
        }
        changed
    }

    suspend fun saveManual(
        wakeDate: LocalDate,
        bedtimeEpochMillis: Long,
        wakeEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.systemDefaultZone(),
    ) {
        require(wakeEpochMillis > bedtimeEpochMillis)
        val duration = ((wakeEpochMillis - bedtimeEpochMillis) / 60_000L).toInt()
        require(duration in 1..(24 * 60))
        val identity = usageDeviceRepository.identity.first()
        database.withTransaction {
            ensureLocalDevice(identity, clock.millis())
            dao.upsertNight(
                SleepNightEntity(
                    deviceId = identity.deviceId,
                    wakeDateIso = wakeDate.toString(),
                    zoneId = zoneId.id,
                    bedtimeEpochMillis = bedtimeEpochMillis,
                    wakeEpochMillis = wakeEpochMillis,
                    durationMinutes = duration,
                    source = SleepRecordSource.MANUAL.name,
                    updatedAtEpochMillis = clock.millis().coerceAtLeast(0L),
                ),
            )
        }
    }

    suspend fun snapshotAll(): List<SleepDeviceRecord> {
        val devices = dao.getDevices()
        val nights = dao.getNights().groupBy(SleepNightEntity::deviceId)
        return devices.map { device ->
            SleepDeviceRecord(
                device.deviceId,
                device.deviceName,
                device.platform,
                device.updatedAtEpochMillis,
                nights[device.deviceId].orEmpty().map(SleepNightEntity::toDomain),
            )
        }
    }

    suspend fun mergeIncoming(incoming: SleepDeviceRecord): SleepDeviceRecord {
        require(incoming.deviceId.isNotBlank())
        database.withTransaction {
            val currentDevice = dao.getDevice(incoming.deviceId)
            val incomingUpdated = maxOf(
                incoming.updatedAtEpochMillis,
                incoming.nights.maxOfOrNull(SleepNight::updatedAtEpochMillis) ?: 0L,
            )
            dao.upsertDevice(
                SleepDeviceEntity(
                    deviceId = incoming.deviceId,
                    deviceName = if (
                        currentDevice == null || incomingUpdated >= currentDevice.updatedAtEpochMillis
                    ) incoming.deviceName else currentDevice.deviceName,
                    platform = incoming.platform,
                    updatedAtEpochMillis = maxOf(currentDevice?.updatedAtEpochMillis ?: 0L, incomingUpdated),
                ),
            )
            incoming.nights.forEach { night ->
                val current = dao.getNight(incoming.deviceId, night.wakeDate.toString())
                val merged = mergeNight(current?.toDomain(), night)
                if (current?.toDomain() != merged) {
                    dao.upsertNight(merged.toEntity(incoming.deviceId))
                }
            }
        }
        return snapshotAll().first { it.deviceId == incoming.deviceId }
    }


    private suspend fun ensureLocalDevice(identity: UsageDeviceIdentity, nowMillis: Long) {
        val current = dao.getDevice(identity.deviceId)
        if (
            current == null ||
            current.deviceName != identity.deviceName ||
            current.platform != identity.platform
        ) {
            dao.upsertDevice(
                SleepDeviceEntity(
                    deviceId = identity.deviceId,
                    deviceName = identity.deviceName,
                    platform = identity.platform,
                    updatedAtEpochMillis = maxOf(
                        current?.updatedAtEpochMillis ?: 0L,
                        identity.updatedAtEpochMillis,
                        nowMillis.coerceAtLeast(0L),
                    ),
                ),
            )
        }
    }

    private fun mergeNight(current: SleepNight?, incoming: SleepNight): SleepNight {
        if (current == null) return incoming
        if (current.source != incoming.source) {
            return if (current.source == SleepRecordSource.MANUAL) current else incoming
        }
        return if (incoming.updatedAtEpochMillis > current.updatedAtEpochMillis) incoming else current
    }

    private suspend fun estimateNight(wakeDate: LocalDate, zone: ZoneId): SleepEstimate? =
        withContext(Dispatchers.IO) {
            val start = at(wakeDate.minusDays(1), 17, 0, zone)
            val end = at(wakeDate, 15, 0, zone)
            val events = usageStatsManager.queryEvents(start, end) ?: return@withContext null
            val opens = mutableMapOf<String, Long>()
            val intervals = ArrayList<SleepInterval>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                if (!events.getNextEvent(event)) break
                val packageName = event.packageName?.takeIf(String::isNotBlank) ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        opens.putIfAbsent(packageName, event.timeStamp)
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        opens.remove(packageName)?.let { opened ->
                            if (event.timeStamp > opened) intervals += SleepInterval(opened, event.timeStamp)
                        }
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.DEVICE_SHUTDOWN,
                    -> {
                        opens.forEach { (_, opened) ->
                            if (event.timeStamp > opened) intervals += SleepInterval(opened, event.timeStamp)
                        }
                        opens.clear()
                    }
                }
            }
            opens.values.forEach { opened ->
                if (end > opened) intervals += SleepInterval(opened, end)
            }
            SleepEstimator.estimate(intervals, wakeDate, zone)
        }

    private fun at(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
}

private data class SleepInterval(val start: Long, val end: Long)
private data class SleepEstimate(
    val bedtimeEpochMillis: Long,
    val wakeEpochMillis: Long,
    val durationMinutes: Int,
)

private object SleepEstimator {
    private const val MIN_BLOCK_MS = 60_000L
    private const val MERGE_GAP_MS = 2 * 60_000L
    private const val MIN_SLEEP_MS = 2 * 60 * 60_000L
    private const val MAX_SLEEP_MS = 13 * 60 * 60_000L
    private const val MIN_CORE_OVERLAP_MS = 60 * 60_000L

    fun estimate(usage: List<SleepInterval>, wakeDate: LocalDate, zone: ZoneId): SleepEstimate? {
        if (usage.isEmpty()) return null
        val windowStart = at(wakeDate.minusDays(1), 17, 0, zone)
        val windowEnd = at(wakeDate, 15, 0, zone)
        val coreStart = at(wakeDate.minusDays(1), 21, 0, zone)
        val coreEnd = at(wakeDate, 10, 0, zone)
        val blocks = usage
            .mapNotNull { clip(it, windowStart, windowEnd) }
            .let(::merge)
            .filter { it.end - it.start >= MIN_BLOCK_MS }
            .let(::merge)
        if (blocks.size < 2) return null
        val gaps = blocks.zipWithNext { first, second -> SleepInterval(first.end, second.start) }
        val best = gaps
            .filter { it.end - it.start in MIN_SLEEP_MS..MAX_SLEEP_MS }
            .filter { overlap(it, coreStart, coreEnd) >= MIN_CORE_OVERLAP_MS }
            .maxByOrNull { it.end - it.start }
            ?: return null
        return SleepEstimate(
            bedtimeEpochMillis = best.start,
            wakeEpochMillis = best.end,
            durationMinutes = ((best.end - best.start) / 60_000L).toInt(),
        )
    }

    private fun merge(intervals: List<SleepInterval>): List<SleepInterval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy(SleepInterval::start)
        val result = ArrayList<SleepInterval>()
        var current = sorted.first()
        sorted.drop(1).forEach { next ->
            if (next.start <= current.end + MERGE_GAP_MS) {
                current = SleepInterval(current.start, maxOf(current.end, next.end))
            } else {
                result += current
                current = next
            }
        }
        result += current
        return result
    }

    private fun clip(interval: SleepInterval, start: Long, end: Long): SleepInterval? {
        val clippedStart = maxOf(interval.start, start)
        val clippedEnd = minOf(interval.end, end)
        return if (clippedEnd > clippedStart) SleepInterval(clippedStart, clippedEnd) else null
    }

    private fun overlap(interval: SleepInterval, start: Long, end: Long): Long =
        (minOf(interval.end, end) - maxOf(interval.start, start)).coerceAtLeast(0L)

    private fun at(date: LocalDate, hour: Int, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
}

private fun SleepNightEntity.toDomain() = SleepNight(
    wakeDate = LocalDate.parse(wakeDateIso),
    zoneId = zoneId,
    bedtimeEpochMillis = bedtimeEpochMillis,
    wakeEpochMillis = wakeEpochMillis,
    durationMinutes = durationMinutes,
    source = SleepRecordSource.valueOf(source),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SleepNight.toEntity(deviceId: String) = SleepNightEntity(
    deviceId = deviceId,
    wakeDateIso = wakeDate.toString(),
    zoneId = zoneId,
    bedtimeEpochMillis = bedtimeEpochMillis,
    wakeEpochMillis = wakeEpochMillis,
    durationMinutes = durationMinutes,
    source = source.name,
    updatedAtEpochMillis = updatedAtEpochMillis,
)
