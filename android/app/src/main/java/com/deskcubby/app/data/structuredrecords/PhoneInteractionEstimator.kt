package com.deskcubby.app.data.structuredrecords

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One completed phone-inactivity session: last stop/lock -> next first use/unlock. */
data class SleepSessionEstimate(
    val sleepTimestamp: Instant,
    val wakeTimestamp: Instant,
    val zone: ZoneId,
) {
    val durationSeconds: Long
        get() = Duration.between(sleepTimestamp, wakeTimestamp).seconds.coerceAtLeast(0L)

    fun sleepLocalTime(): LocalTime = sleepTimestamp.atZone(zone).toLocalTime()
    fun wakeLocalTime(): LocalTime = wakeTimestamp.atZone(zone).toLocalTime()
    fun wakeLocalDate(): LocalDate = wakeTimestamp.atZone(zone).toLocalDate()
}

internal enum class PhoneInteractionKind { START, STOP }

internal data class PhoneInteractionMoment(
    val timestamp: Instant,
    val kind: PhoneInteractionKind,
)

/**
 * Pairs each latest stop/lock with the next start/unlock. The pairing uses real timestamps only;
 * there is deliberately no diary-day or configurable boundary input.
 */
internal fun buildSleepSessions(
    moments: List<PhoneInteractionMoment>,
    zone: ZoneId,
): List<SleepSessionEstimate> {
    val sorted = moments.sortedBy { it.timestamp }
    val sessions = ArrayList<SleepSessionEstimate>()
    var pendingStop: Instant? = null
    for (moment in sorted) {
        when (moment.kind) {
            PhoneInteractionKind.STOP -> pendingStop = moment.timestamp
            PhoneInteractionKind.START -> {
                val stop = pendingStop
                if (stop != null && moment.timestamp.isAfter(stop)) {
                    sessions += SleepSessionEstimate(stop, moment.timestamp, zone)
                }
                pendingStop = null
            }
        }
    }
    return sessions
}

/**
 * Estimates sleep/wake from phone interaction events. This is not medical sleep detection and does
 * not use Health Connect. For a natural wake date, the longest completed stop -> next-start session
 * is selected as that day's sleep session.
 */
@Singleton
class PhoneInteractionEstimator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun estimateForWakeDate(
        wakeDate: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): SleepSessionEstimate? {
        if (!hasUsageAccess()) return null
        val begin = wakeDate.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val naturalEnd = wakeDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val queryEnd = minOf(naturalEnd, now.toEpochMilli())
        if (queryEnd <= begin) return null

        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val moments = ArrayList<PhoneInteractionMoment>()
        try {
            val events = manager.queryEvents(begin, queryEnd)
            while (events.hasNextEvent()) {
                val event = UsageEvents.Event()
                events.getNextEvent(event)
                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.SCREEN_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_HIDDEN,
                    UsageEvents.Event.USER_INTERACTION,
                    -> PhoneInteractionKind.START

                    UsageEvents.Event.ACTIVITY_PAUSED,
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.DEVICE_SHUTDOWN,
                    -> PhoneInteractionKind.STOP

                    else -> null
                }
                if (kind != null) {
                    moments += PhoneInteractionMoment(Instant.ofEpochMilli(event.timeStamp), kind)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }

        return buildSleepSessions(moments, zone)
            .asSequence()
            .filter { it.wakeLocalDate() == wakeDate }
            .filter { it.durationSeconds in MIN_SESSION_SECONDS..MAX_SESSION_SECONDS }
            .maxByOrNull { it.durationSeconds }
    }

    private companion object {
        const val MIN_SESSION_SECONDS = 10L * 60L
        const val MAX_SESSION_SECONDS = 24L * 60L * 60L
    }
}
