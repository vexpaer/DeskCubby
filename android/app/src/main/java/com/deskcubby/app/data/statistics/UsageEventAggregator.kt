package com.deskcubby.app.data.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Framework usage events copied into a pure model so interval reconstruction and civil-day
 * splitting can be tested without Android dependencies.
 */
internal data class RawUsageEvent(
    val timestampEpochMillis: Long,
    val packageName: String?,
    val kind: RawUsageEventKind,
)

internal enum class RawUsageEventKind {
    FOREGROUND,
    BACKGROUND,
    STOP_ALL,
}

internal data class UsageEventQueryResult(
    val events: List<RawUsageEvent>,
    /** Earliest event of any type, used to avoid inventing data before provider retention. */
    val earliestEventEpochMillis: Long?,
)

internal data class UsageEventAggregation(
    val days: List<UsageStatisticsDay>,
    /** First date whose complete event stream is safe to replace in persisted history. */
    val coverageStartDate: LocalDate?,
)

/**
 * Reconstructs package foreground sessions from resume/pause events.
 *
 * The first returned event may sit at Android's retention boundary, so that civil day and all
 * earlier dates are deliberately left untouched unless the stream starts exactly at the requested
 * midnight. Complete following dates, including genuine zero-use dates, are authoritative.
 */
internal fun aggregateUsageEvents(
    query: UsageEventQueryResult,
    firstRequestedDate: LocalDate,
    today: LocalDate,
    zone: ZoneId,
    nowMillis: Long,
): UsageEventAggregation {
    require(!firstRequestedDate.isAfter(today))
    val queryStartMillis = firstRequestedDate.atStartOfDay(zone).toInstant().toEpochMilli()
    if (nowMillis <= queryStartMillis || query.earliestEventEpochMillis == null) {
        return UsageEventAggregation(emptyList(), null)
    }

    val earliest = query.earliestEventEpochMillis.coerceAtMost(nowMillis)
    val earliestDate = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
    val coverageStart = if (earliest <= queryStartMillis) {
        firstRequestedDate
    } else {
        earliestDate.plusDays(1L).coerceAtLeast(firstRequestedDate)
    }
    if (coverageStart.isAfter(today)) {
        return UsageEventAggregation(emptyList(), null)
    }

    val durationsByDate = mutableMapOf<LocalDate, MutableMap<String, Long>>()
    val activePackages = mutableMapOf<String, Long>()

    fun addInterval(packageName: String, rawStart: Long, rawEnd: Long) {
        var cursor = rawStart.coerceAtLeast(queryStartMillis)
        val end = rawEnd.coerceAtMost(nowMillis)
        if (end <= cursor) return
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val dayEnd = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
            val segmentEnd = minOf(end, dayEnd)
            if (!date.isBefore(coverageStart) && !date.isAfter(today)) {
                val apps = durationsByDate.getOrPut(date, ::mutableMapOf)
                apps[packageName] = (apps[packageName] ?: 0L) + (segmentEnd - cursor)
            }
            cursor = segmentEnd
        }
    }

    query.events.asSequence()
        .filter { it.timestampEpochMillis in 0L..nowMillis }
        .sortedBy(RawUsageEvent::timestampEpochMillis)
        .forEach { event ->
            when (event.kind) {
                RawUsageEventKind.FOREGROUND -> {
                    val packageName = event.packageName
                        ?.takeIf(::isSafeUsagePackageName)
                        ?: return@forEach
                    // A phone normally has one resumed foreground app. Closing any stale session
                    // here prevents a missing pause event from turning into days of false usage.
                    activePackages.entries.toList().forEach { (activePackage, start) ->
                        if (activePackage != packageName) {
                            addInterval(activePackage, start, event.timestampEpochMillis)
                            activePackages.remove(activePackage)
                        }
                    }
                    activePackages.putIfAbsent(packageName, event.timestampEpochMillis)
                }

                RawUsageEventKind.BACKGROUND -> {
                    val packageName = event.packageName
                        ?.takeIf(::isSafeUsagePackageName)
                        ?: return@forEach
                    activePackages.remove(packageName)?.let { start ->
                        addInterval(packageName, start, event.timestampEpochMillis)
                    }
                }

                RawUsageEventKind.STOP_ALL -> {
                    activePackages.forEach { (packageName, start) ->
                        addInterval(packageName, start, event.timestampEpochMillis)
                    }
                    activePackages.clear()
                }
            }
        }

    activePackages.forEach { (packageName, start) ->
        addInterval(packageName, start, nowMillis)
    }

    val days = buildList {
        var date = coverageStart
        while (!date.isAfter(today)) {
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
            val observableMillis = if (date == today) {
                nowMillis.coerceIn(dayStart, dayEnd) - dayStart
            } else {
                dayEnd - dayStart
            }
            val apps = durationsByDate[date].orEmpty().entries
                .asSequence()
                .mapNotNull { (packageName, duration) ->
                    duration.coerceAtMost(observableMillis)
                        .takeIf { it > 0L }
                        ?.let { UsageAppDuration(packageName, it) }
                }
                .sortedBy(UsageAppDuration::packageName)
                .toList()
            add(
                UsageStatisticsDay(
                    date = date,
                    zoneId = zone.id,
                    state = if (date == today) {
                        StatisticsDayState.OPEN
                    } else {
                        StatisticsDayState.FINAL
                    },
                    collectedAtEpochMillis = nowMillis,
                    apps = apps,
                ),
            )
            date = date.plusDays(1L)
        }
    }
    return UsageEventAggregation(days, coverageStart)
}

private fun isSafeUsagePackageName(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_USAGE_EVENT_PACKAGE_NAME_CHARS &&
        value.none { it.isISOControl() || it.isWhitespace() }

private const val MAX_USAGE_EVENT_PACKAGE_NAME_CHARS = 255
