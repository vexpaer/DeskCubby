package com.deskcubby.app.data.statistics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageDailyBucketAggregatorTest {
    @Test
    fun `exact day query uses requested bounds instead of provider interval timestamps`() {
        val start = Instant.parse("2026-07-26T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-07-27T00:00:00Z").toEpochMilli()

        val buckets = exactDayUsageBuckets(
            values = listOf(
                RawQueriedUsage("example.app", 12_000L),
            ),
            dayStartMillis = start,
            dayEndMillis = end,
        )

        assertEquals(start, buckets.single().beginEpochMillis)
        assertEquals(end, buckets.single().endEpochMillis)
        assertEquals(12_000L, buckets.single().foregroundMillis)
    }

    @Test
    fun partialFirstBucketIsSkippedWhileCompleteFollowingDayIsFinal() {
        val zone = ZoneId.of("Asia/Shanghai")
        val partialDate = LocalDate.parse("2026-07-25")
        val completeDate = partialDate.plusDays(1L)
        val today = completeDate.plusDays(1L)

        val result = aggregateDailyUsageBuckets(
            buckets = listOf(
                bucket(
                    date = partialDate,
                    zone = zone,
                    beginOffsetMillis = 12L * HOUR_MILLIS,
                    foregroundMillis = HOUR_MILLIS,
                ),
                bucket(
                    date = completeDate,
                    zone = zone,
                    foregroundMillis = 2L * HOUR_MILLIS,
                ),
            ),
            firstRequestedDate = partialDate,
            today = today,
            zone = zone,
            nowMillis = today.atStartOfDay(zone).toInstant().toEpochMilli() + HOUR_MILLIS,
        )

        assertEquals(listOf(completeDate), result.map(UsageStatisticsDay::date))
        assertEquals(StatisticsDayState.FINAL, result.single().state)
        assertEquals(2L * HOUR_MILLIS, result.single().totalForegroundMillis)
    }

    @Test
    fun datesWithoutReturnedBucketsAreNotInventedAsZeroDays() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.parse("2026-07-27")

        val result = aggregateDailyUsageBuckets(
            buckets = emptyList(),
            firstRequestedDate = today,
            today = today,
            zone = zone,
            nowMillis = today.atStartOfDay(zone).toInstant().toEpochMilli() + HOUR_MILLIS,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun springDstDayUsesItsTwentyThreeHourCivilBoundary() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-03-08")
        val today = date.plusDays(1L)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()

        val result = aggregateDailyUsageBuckets(
            buckets = listOf(
                RawDailyUsageBucket(
                    beginEpochMillis = start,
                    endEpochMillis = end,
                    packageName = "example.app",
                    foregroundMillis = 25L * HOUR_MILLIS,
                ),
            ),
            firstRequestedDate = date,
            today = today,
            zone = zone,
            nowMillis = today.atStartOfDay(zone).toInstant().toEpochMilli() + HOUR_MILLIS,
        )

        assertEquals(23L * HOUR_MILLIS, end - start)
        assertEquals(23L * HOUR_MILLIS, result.single().totalForegroundMillis)
        assertEquals(StatisticsDayState.FINAL, result.single().state)
    }

    @Test
    fun autumnDstDayAcceptsItsTwentyFiveHourCivilBoundary() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-11-01")
        val today = date.plusDays(1L)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()

        val result = aggregateDailyUsageBuckets(
            buckets = listOf(
                RawDailyUsageBucket(
                    beginEpochMillis = start,
                    endEpochMillis = end,
                    packageName = "example.app",
                    foregroundMillis = 25L * HOUR_MILLIS,
                ),
            ),
            firstRequestedDate = date,
            today = today,
            zone = zone,
            nowMillis = today.atStartOfDay(zone).toInstant().toEpochMilli() + HOUR_MILLIS,
        )

        assertEquals(25L * HOUR_MILLIS, end - start)
        assertEquals(25L * HOUR_MILLIS, result.single().totalForegroundMillis)
        assertEquals(StatisticsDayState.FINAL, result.single().state)
    }

    @Test
    fun currentDayPartialBucketRemainsOpen() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.parse("2026-07-27")
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val now = start + 13L * HOUR_MILLIS

        val result = aggregateDailyUsageBuckets(
            buckets = listOf(
                RawDailyUsageBucket(
                    beginEpochMillis = start,
                    endEpochMillis = now,
                    packageName = "example.app",
                    foregroundMillis = 3L * HOUR_MILLIS,
                ),
            ),
            firstRequestedDate = today,
            today = today,
            zone = zone,
            nowMillis = now,
        )

        assertEquals(today, result.single().date)
        assertEquals(StatisticsDayState.OPEN, result.single().state)
        assertEquals(3L * HOUR_MILLIS, result.single().totalForegroundMillis)
    }

    private fun bucket(
        date: LocalDate,
        zone: ZoneId,
        beginOffsetMillis: Long = 0L,
        foregroundMillis: Long,
    ): RawDailyUsageBucket {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1L).atStartOfDay(zone).toInstant().toEpochMilli()
        return RawDailyUsageBucket(
            beginEpochMillis = start + beginOffsetMillis,
            endEpochMillis = end,
            packageName = "example.app",
            foregroundMillis = foregroundMillis,
        )
    }

    private companion object {
        const val HOUR_MILLIS = 60L * 60L * 1_000L
    }
}
