package com.deskcubby.app.data.structuredrecords

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Estimated first-use and last-use moments of one Journal Day. */
data class SleepWakeEstimate(
    val journalDay: LocalDate,
    val wakeTime: LocalTime?,
    val sleepTime: LocalTime?,
)

/**
 * Estimates "wake" and "sleep" from the phone's own interaction events — first use of the day and
 * last stop of the day — derived from [UsageStatsManager]. This is deliberately NOT medical sleep
 * detection and never touches Health Connect. It requires the system "Usage access" permission; the
 * UI must explain the purpose before enabling the collector.
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

    /**
     * Computes the first/last interaction estimate for [journalDay] within
     * `[journalDay@boundary, journalDay+1@boundary)`. Returns null when there is no usage access or
     * no relevant events were found.
     */
    fun estimateForJournalDay(
        journalDay: LocalDate,
        boundaryMinutes: Int?,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Instant = Instant.now(),
    ): SleepWakeEstimate? {
        if (!hasUsageAccess()) return null
        val boundary = boundaryMinutes ?: 5 * 60
        val dayStart = LocalDateTime.of(journalDay, LocalTime.of(0, 0))
        // A journal day with boundary B runs [dayStart+B, dayStart+1d+B).
        val begin = dayStart.plusMinutes(boundary.toLong())
        val end = dayStart.plusDays(1).plusMinutes(boundary.toLong())
        val beginMillis = begin.atZone(zone).toInstant().toEpochMilli()
        val endMillis = end.atZone(zone).toInstant().toEpochMilli()
        // Never query beyond now; UsageStatsManager cannot report future events.
        val queryEnd = minOf(endMillis, now.toEpochMilli())
        if (queryEnd <= beginMillis) return null

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        var firstUseMillis: Long? = null
        var lastStopMillis: Long? = null
        val events = usageStatsManager.queryEvents(beginMillis, queryEnd)
        while (events.hasNextEvent()) {
            val event = UsageEvents.Event()
            events.getNextEvent(event)
            val eventType = event.eventType
            if (eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                eventType == UsageEvents.Event.SCREEN_INTERACTIVE ||
                eventType == UsageEvents.Event.KEYGUARD_HIDDEN ||
                eventType == UsageEvents.Event.USER_INTERACTION
            ) {
                val candidate = firstUseMillis
                if (candidate == null || event.timeStamp < candidate) firstUseMillis = event.timeStamp
            } else if (
                eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                eventType == UsageEvents.Event.KEYGUARD_SHOWN ||
                eventType == UsageEvents.Event.DEVICE_SHUTDOWN
            ) {
                val candidate = lastStopMillis
                if (candidate == null || event.timeStamp > candidate) lastStopMillis = event.timeStamp
            }
        }
        if (firstUseMillis == null && lastStopMillis == null) return null
        val wake = firstUseMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        val sleep = lastStopMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        return SleepWakeEstimate(journalDay, wake, sleep)
    }
}
