package com.deskcubby.app.data.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Android-facing UsageStats values are copied into this small pure model so
 * bucket acceptance, DST boundaries, and partial-day rejection stay directly
 * unit-testable without Android framework objects.
 */
internal data class RawDailyUsageBucket(
    val beginEpochMillis: Long,
    val endEpochMillis: Long,
    val packageName: String,
    val foregroundMillis: Long,
)

/**
 * Converts only real INTERVAL_DAILY buckets into persisted civil-day rows.
 *
 * A completed day is accepted only when the bucket begins no later than that
 * day's start and ends no earlier than the following day's start. The bucket
 * must still be daily-sized, so an expanded multi-day interval cannot have its
 * indivisible total assigned to one date. Today's partial bucket is OPEN.
 * Dates with no returned bucket are deliberately absent rather than fabricated
 * as zero.
 */
internal fun aggregateDailyUsageBuckets(
    buckets: List<RawDailyUsageBucket>,
    firstRequestedDate: LocalDate,
    today: LocalDate,
    zone: ZoneId,
    nowMillis: Long,
): List<UsageStatisticsDay> {
    require(!firstRequestedDate.isAfter(today))
    val appsByDate = mutableMapOf<LocalDate, MutableMap<String, Long>>()
    val observedDates = mutableSetOf<LocalDate>()

    buckets.forEach { bucket ->
        if (
            bucket.beginEpochMillis < 0L ||
            bucket.endEpochMillis <= bucket.beginEpochMillis ||
            bucket.endEpochMillis - bucket.beginEpochMillis > MAX_DAILY_BUCKET_SPAN_MILLIS ||
            !isSafePackageName(bucket.packageName) ||
            bucket.foregroundMillis < 0L
        ) {
            return@forEach
        }
        val date = Instant.ofEpochMilli(bucket.beginEpochMillis)
            .atZone(zone)
            .toLocalDate()
        if (date.isBefore(firstRequestedDate) || date.isAfter(today)) return@forEach

        val dayStartMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val beginsAtDayBoundary = bucket.beginEpochMillis <= dayStartMillis
        val hasUsableCoverage = if (date == today) {
            beginsAtDayBoundary &&
                bucket.endEpochMillis > dayStartMillis &&
                bucket.beginEpochMillis <= nowMillis
        } else {
            beginsAtDayBoundary && bucket.endEpochMillis >= dayEndMillis
        }
        if (!hasUsableCoverage) return@forEach

        observedDates += date
        if (bucket.foregroundMillis > 0L) {
            val naturalDayLength = dayEndMillis - dayStartMillis
            val maximumObservableDuration = if (date == today) {
                nowMillis.coerceIn(dayStartMillis, dayEndMillis) - dayStartMillis
            } else {
                naturalDayLength
            }
            val boundedDuration = bucket.foregroundMillis.coerceAtMost(maximumObservableDuration)
            val apps = appsByDate.getOrPut(date, ::mutableMapOf)
            // Some OEMs return overlapping duplicate entries. Taking the
            // larger complete-bucket value avoids double-counting them.
            apps[bucket.packageName] = maxOf(apps[bucket.packageName] ?: 0L, boundedDuration)
        }
    }

    return observedDates.asSequence()
        .sorted()
        .map { date ->
            UsageStatisticsDay(
                date = date,
                zoneId = zone.id,
                state = if (date == today) {
                    StatisticsDayState.OPEN
                } else {
                    StatisticsDayState.FINAL
                },
                collectedAtEpochMillis = nowMillis,
                apps = appsByDate[date].orEmpty().entries
                    .asSequence()
                    .map { (packageName, foregroundMillis) ->
                        UsageAppDuration(packageName, foregroundMillis)
                    }
                    .sortedBy(UsageAppDuration::packageName)
                    .toList(),
            )
        }
        .toList()
}

private fun isSafePackageName(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= MAX_USAGE_PACKAGE_NAME_CHARS &&
        value.none { it.isISOControl() || it.isWhitespace() }

private const val MAX_DAILY_BUCKET_SPAN_MILLIS = 26L * 60L * 60L * 1_000L
private const val MAX_USAGE_PACKAGE_NAME_CHARS = 255
