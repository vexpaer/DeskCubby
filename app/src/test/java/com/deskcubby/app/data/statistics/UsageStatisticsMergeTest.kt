package com.deskcubby.app.data.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageStatisticsMergeTest {
    @Test
    fun finalDayIsImmutableWhileOpenDayCanRefreshAndOlderHistoryCanBackfill() {
        val final = day("2026-07-26", StatisticsDayState.FINAL, 10)
        val open = day("2026-07-27", StatisticsDayState.OPEN, 20)
        val merged = mergeUsageStatisticsHistory(
            current = UsageStatisticsHistory(
                trackingStartedOn = final.date,
                days = listOf(final, open),
                backfillCompletedThrough = LocalDate.parse("2026-07-24"),
            ),
            replacements = listOf(
                day("2026-07-25", StatisticsDayState.FINAL, 5),
                day("2026-07-26", StatisticsDayState.FINAL, 999),
                day("2026-07-27", StatisticsDayState.OPEN, 30),
            ),
            backfillCompletedThrough = LocalDate.parse("2026-07-26"),
        )

        assertEquals(LocalDate.parse("2026-07-25"), merged.trackingStartedOn)
        assertEquals(listOf(5L, 10L, 30L), merged.days.map { it.totalForegroundMillis })
        assertEquals(LocalDate.parse("2026-07-26"), merged.backfillCompletedThrough)
    }

    @Test
    fun absentReplacementDoesNotFinalizeAnUnavailablePastOpenDay() {
        val open = day("2026-07-25", StatisticsDayState.OPEN, 20)

        val merged = mergeUsageStatisticsHistory(
            current = UsageStatisticsHistory(open.date, listOf(open)),
            replacements = emptyList(),
        )

        assertEquals(StatisticsDayState.OPEN, merged.days.single().state)
        assertEquals(20L, merged.days.single().totalForegroundMillis)
    }

    @Test
    fun missingMigrationWatermarkRequestsOneBoundedDiscoveryWindow() {
        val today = LocalDate.parse("2026-07-27")

        val result = usageQueryStartDate(
            history = UsageStatisticsHistory(backfillCompletedThrough = null),
            today = today,
            maximumBackfillDays = 30L,
        )

        assertEquals(LocalDate.parse("2026-06-28"), result)
    }

    @Test
    fun completedBackfillNormallyQueriesOnlyToday() {
        val today = LocalDate.parse("2026-07-27")

        val result = usageQueryStartDate(
            history = UsageStatisticsHistory(
                backfillCompletedThrough = today.minusDays(1L),
            ),
            today = today,
        )

        assertEquals(today, result)
    }

    @Test
    fun recentOpenDayIsRetriedWithoutReopeningFinalDays() {
        val today = LocalDate.parse("2026-07-27")
        val open = day("2026-07-20", StatisticsDayState.OPEN, 20)

        val result = usageQueryStartDate(
            history = UsageStatisticsHistory(
                trackingStartedOn = open.date,
                days = listOf(open),
                backfillCompletedThrough = today.minusDays(1L),
            ),
            today = today,
        )

        assertEquals(open.date, result)
    }

    @Test
    fun mergeStartsTrackingOnFirstCompleteDayNotTruncatedDiscoveryDay() {
        val truncatedDate = LocalDate.parse("2026-07-25")
        val firstComplete = day(
            date = truncatedDate.plusDays(1L).toString(),
            state = StatisticsDayState.FINAL,
            duration = 10L,
        )

        val merged = mergeUsageStatisticsHistory(
            current = UsageStatisticsHistory(),
            replacements = listOf(firstComplete),
            backfillCompletedThrough = firstComplete.date,
        )

        assertEquals(firstComplete.date, merged.trackingStartedOn)
        assertEquals(listOf(firstComplete.date), merged.days.map { it.date })
    }

    private fun day(
        date: String,
        state: StatisticsDayState,
        duration: Long,
    ) = UsageStatisticsDay(
        date = LocalDate.parse(date),
        zoneId = "UTC",
        state = state,
        collectedAtEpochMillis = duration,
        apps = listOf(UsageAppDuration("example.app", duration)),
    )
}
