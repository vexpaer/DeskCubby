package com.deskcubby.app.data.statistics

import java.time.LocalDate

/**
 * OPEN is a live, replaceable value (normally today). FINAL is an immutable
 * successful snapshot of a completed civil day.
 */
enum class StatisticsDayState {
    OPEN,
    FINAL,
}

data class UsageAppDuration(
    val packageName: String,
    val foregroundMillis: Long,
)

data class UsageStatisticsDay(
    val date: LocalDate,
    val zoneId: String,
    val state: StatisticsDayState,
    val collectedAtEpochMillis: Long,
    val apps: List<UsageAppDuration>,
) {
    val totalForegroundMillis: Long
        get() = apps.sumOf(UsageAppDuration::foregroundMillis)
}

data class UsageStatisticsHistory(
    val trackingStartedOn: LocalDate? = null,
    val days: List<UsageStatisticsDay> = emptyList(),
    /**
     * Last completed civil date covered by a successful history-discovery
     * query. Null is the explicit v1 migration state and triggers one bounded
     * wide backfill. It is a scan watermark, not proof that every intervening
     * date had a usage record.
     */
    val backfillCompletedThrough: LocalDate? = null,
)

/**
 * A null [steps] value means the selected source has not produced a trustworthy
 * daily result. It is deliberately distinct from zero.
 */
data class StepStatisticsDay(
    val date: LocalDate,
    val zoneId: String,
    val state: StatisticsDayState,
    val collectedAtEpochMillis: Long,
    val steps: Long?,
)

data class StepStatisticsHistory(
    val trackingStartedOn: LocalDate? = null,
    val days: List<StepStatisticsDay> = emptyList(),
    val deviceSensorBaseline: DeviceStepSensorBaseline? = null,
)

/**
 * Last cumulative TYPE_STEP_COUNTER sample. It is device-local collection state rather than a
 * daily result; a reset or reboot is detected when the next cumulative value is lower.
 */
data class DeviceStepSensorBaseline(
    val date: LocalDate,
    val cumulativeSteps: Long,
    val capturedAtEpochMillis: Long,
)

enum class StatisticsRange(
    val days: Long?,
) {
    LAST_7_DAYS(7),
    LAST_30_DAYS(30),
    LAST_90_DAYS(90),
    ALL(null),
}

enum class StatisticsChartType {
    BARS,
    LINE,
    CALENDAR,
}

data class StatisticsPoint(
    val date: LocalDate,
    val value: Double?,
)

data class StatisticsOverview(
    val trackingStartedOn: LocalDate?,
    val recordedDays: Int,
    val daysWithData: Int,
    val total: Double,
    val averagePerDataDay: Double,
)

fun <T> List<T>.withinStatisticsRange(
    range: StatisticsRange,
    today: LocalDate,
    dateOf: (T) -> LocalDate,
): List<T> {
    val firstDate = range.days?.let { today.minusDays(it - 1L) }
    return asSequence()
        .filter { item ->
            val date = dateOf(item)
            !date.isAfter(today) && (firstDate == null || !date.isBefore(firstDate))
        }
        .sortedBy(dateOf)
        .toList()
}

fun UsageStatisticsHistory.overview(packageName: String? = null): StatisticsOverview {
    return usageOverview(
        days = days,
        trackingStartedOn = trackingStartedOn,
        packageName = packageName,
    )
}

fun UsageStatisticsHistory.overview(
    range: StatisticsRange,
    today: LocalDate,
    packageName: String? = null,
): StatisticsOverview {
    val rangedDays = days.withinStatisticsRange(
        range = range,
        today = today,
        dateOf = UsageStatisticsDay::date,
    )
    return usageOverview(
        days = rangedDays,
        trackingStartedOn = rangedDays.firstOrNull()?.date,
        packageName = packageName,
    )
}

private fun usageOverview(
    days: List<UsageStatisticsDay>,
    trackingStartedOn: LocalDate?,
    packageName: String?,
): StatisticsOverview {
    val values = days.map { day ->
        if (packageName == null) {
            day.totalForegroundMillis.toDouble()
        } else {
            day.apps.firstOrNull { it.packageName == packageName }
                ?.foregroundMillis
                ?.toDouble()
                ?: 0.0
        }
    }
    return StatisticsOverview(
        trackingStartedOn = trackingStartedOn,
        recordedDays = days.size,
        daysWithData = days.size,
        total = values.sum(),
        averagePerDataDay = values.averageOrZero(),
    )
}

fun StepStatisticsHistory.overview(): StatisticsOverview {
    val values = days.mapNotNull { it.steps?.toDouble() }
    return StatisticsOverview(
        trackingStartedOn = trackingStartedOn,
        recordedDays = days.size,
        daysWithData = values.size,
        total = values.sum(),
        averagePerDataDay = values.averageOrZero(),
    )
}

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()
