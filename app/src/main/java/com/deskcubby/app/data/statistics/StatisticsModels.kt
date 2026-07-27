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
)

/**
 * A null [steps] value means Health Connect successfully answered the query
 * but had no aggregate for the day. It is deliberately distinct from zero.
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
