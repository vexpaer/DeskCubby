package com.deskcubby.app.data.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsModelsTest {
    @Test
    fun stepOverviewDoesNotTreatMissingAggregateAsZero() {
        val history = StepStatisticsHistory(
            trackingStartedOn = LocalDate.parse("2026-07-26"),
            days = listOf(
                stepDay("2026-07-26", null),
                stepDay("2026-07-27", 6_000),
            ),
        )

        val overview = history.overview()

        assertEquals(2, overview.recordedDays)
        assertEquals(1, overview.daysWithData)
        assertEquals(6_000.0, overview.total, 0.0)
        assertEquals(6_000.0, overview.averagePerDataDay, 0.0)
    }

    @Test
    fun rangeIncludesTodayAndRequestedNumberOfCivilDates() {
        val days = (1L..40L).map { LocalDate.parse("2026-07-28").minusDays(it) }
        val result = days.withinStatisticsRange(
            range = StatisticsRange.LAST_7_DAYS,
            today = LocalDate.parse("2026-07-27"),
            dateOf = { it },
        )

        assertEquals(LocalDate.parse("2026-07-21"), result.first())
        assertEquals(LocalDate.parse("2026-07-27"), result.last())
        assertEquals(7, result.size)
    }

    @Test
    fun emptyHistoryHasNoStartDate() {
        val overview = UsageStatisticsHistory().overview()

        assertNull(overview.trackingStartedOn)
        assertEquals(0, overview.recordedDays)
        assertEquals(0.0, overview.averagePerDataDay, 0.0)
    }

    private fun stepDay(date: String, steps: Long?) = StepStatisticsDay(
        date = LocalDate.parse(date),
        zoneId = "Asia/Shanghai",
        state = StatisticsDayState.FINAL,
        collectedAtEpochMillis = 1,
        steps = steps,
    )
}
