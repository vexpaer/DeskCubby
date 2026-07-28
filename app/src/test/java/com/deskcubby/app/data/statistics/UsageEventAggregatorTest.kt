package com.deskcubby.app.data.statistics

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventAggregatorTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun foregroundIntervalsAreSplitAtCivilMidnight() {
        val first = LocalDate.parse("2026-07-25")
        val today = first.plusDays(2L)
        val firstStart = start(first)
        val now = start(today) + 12L * HOUR

        val result = aggregateUsageEvents(
            query = UsageEventQueryResult(
                events = listOf(
                    event(firstStart, null, RawUsageEventKind.STOP_ALL),
                    event(start(first.plusDays(1L)) - HOUR, APP, RawUsageEventKind.FOREGROUND),
                    event(start(first.plusDays(1L)) + HOUR, APP, RawUsageEventKind.BACKGROUND),
                ),
                earliestEventEpochMillis = firstStart,
            ),
            firstRequestedDate = first,
            today = today,
            zone = zone,
            nowMillis = now,
        )

        assertEquals(first, result.coverageStartDate)
        assertEquals(
            listOf(HOUR, HOUR, 0L),
            result.days.map { day ->
                day.apps.singleOrNull()?.foregroundMillis ?: 0L
            },
        )
        assertEquals(HOUR, result.days[1].totalForegroundMillis)
    }

    @Test
    fun eventBeforeRequestedMidnightSeedsCrossDayForegroundState() {
        val requested = LocalDate.parse("2026-07-27")
        val requestedStart = start(requested)
        val now = requestedStart + 12L * HOUR

        val result = aggregateUsageEvents(
            query = UsageEventQueryResult(
                events = listOf(
                    event(requestedStart - HOUR, APP, RawUsageEventKind.FOREGROUND),
                    event(requestedStart + HOUR, APP, RawUsageEventKind.BACKGROUND),
                ),
                earliestEventEpochMillis = requestedStart - HOUR,
            ),
            firstRequestedDate = requested,
            today = requested,
            zone = zone,
            nowMillis = now,
        )

        assertEquals(requested, result.coverageStartDate)
        assertEquals(HOUR, result.days.single().totalForegroundMillis)
    }

    @Test
    fun retentionBoundaryDayIsSkippedButFollowingDaysReplaceBadHistory() {
        val requested = LocalDate.parse("2026-07-20")
        val retained = requested.plusDays(3L)
        val today = retained.plusDays(2L)
        val earliest = start(retained) + 10L * HOUR

        val result = aggregateUsageEvents(
            query = UsageEventQueryResult(
                events = listOf(
                    event(earliest, APP, RawUsageEventKind.FOREGROUND),
                    event(earliest + HOUR, APP, RawUsageEventKind.BACKGROUND),
                    event(start(retained.plusDays(1L)) + HOUR, APP, RawUsageEventKind.FOREGROUND),
                    event(start(retained.plusDays(1L)) + 3L * HOUR, APP, RawUsageEventKind.BACKGROUND),
                ),
                earliestEventEpochMillis = earliest,
            ),
            firstRequestedDate = requested,
            today = today,
            zone = zone,
            nowMillis = start(today) + 12L * HOUR,
        )

        assertEquals(retained.plusDays(1L), result.coverageStartDate)
        assertEquals(
            listOf(retained.plusDays(1L), today),
            result.days.map(UsageStatisticsDay::date),
        )
        assertEquals(2L * HOUR, result.days.first().totalForegroundMillis)
        assertTrue(result.days.last().apps.isEmpty())
    }

    @Test
    fun screenOffClosesSessionAndNextAppClosesStalePackage() {
        val date = LocalDate.parse("2026-07-27")
        val start = start(date)
        val now = start + 12L * HOUR

        val result = aggregateUsageEvents(
            query = UsageEventQueryResult(
                events = listOf(
                    event(start, null, RawUsageEventKind.STOP_ALL),
                    event(start + HOUR, APP, RawUsageEventKind.FOREGROUND),
                    event(start + 2L * HOUR, OTHER_APP, RawUsageEventKind.FOREGROUND),
                    event(start + 4L * HOUR, null, RawUsageEventKind.STOP_ALL),
                ),
                earliestEventEpochMillis = start,
            ),
            firstRequestedDate = date,
            today = date,
            zone = zone,
            nowMillis = now,
        )

        val apps = result.days.single().apps.associate {
            it.packageName to it.foregroundMillis
        }
        assertEquals(HOUR, apps[APP])
        assertEquals(2L * HOUR, apps[OTHER_APP])
    }

    @Test
    fun springDstDayUsesRealTwentyThreeHourInterval() {
        val dstZone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-03-08")
        val today = date.plusDays(1L)
        val start = date.atStartOfDay(dstZone).toInstant().toEpochMilli()
        val end = today.atStartOfDay(dstZone).toInstant().toEpochMilli()

        val result = aggregateUsageEvents(
            query = UsageEventQueryResult(
                events = listOf(
                    event(start, APP, RawUsageEventKind.FOREGROUND),
                    event(end, APP, RawUsageEventKind.BACKGROUND),
                ),
                earliestEventEpochMillis = start,
            ),
            firstRequestedDate = date,
            today = today,
            zone = dstZone,
            nowMillis = end + HOUR,
        )

        assertEquals(23L * HOUR, end - start)
        assertEquals(23L * HOUR, result.days.first().totalForegroundMillis)
    }

    @Test
    fun emptyEventStreamDoesNotInventZeroDays() {
        val date = LocalDate.parse("2026-07-27")

        val result = aggregateUsageEvents(
            query = UsageEventQueryResult(emptyList(), null),
            firstRequestedDate = date,
            today = date,
            zone = zone,
            nowMillis = start(date) + HOUR,
        )

        assertNull(result.coverageStartDate)
        assertTrue(result.days.isEmpty())
    }

    private fun start(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun event(
        timestamp: Long,
        packageName: String?,
        kind: RawUsageEventKind,
    ) = RawUsageEvent(timestamp, packageName, kind)

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
        const val APP = "com.tencent.mm"
        const val OTHER_APP = "com.ss.android.ugc.aweme"
    }
}
