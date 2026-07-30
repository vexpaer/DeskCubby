package com.deskcubby.app.data.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageDeviceMergeTest {
    @Test
    fun deviceNameLimitDoesNotSplitUnicodeCodePoints() {
        val limited = limitUsageDeviceNameInput("😀".repeat(81) + "\u0000")

        assertEquals(80, limited.codePointCount(0, limited.length))
        assertEquals("😀".repeat(80), limited)
    }

    @Test
    fun renameTimestampAdvancesPastHistoryAndExistingMetadata() {
        assertEquals(
            31L,
            nextUsageDeviceUpdatedAt(now = 10, current = 20, newestHistory = 30),
        )
    }

    @Test
    fun mergeKeepsFinalDayAndNewestOpenDay() {
        val date = LocalDate.parse("2026-07-29")
        val finalDay = day(date, StatisticsDayState.FINAL, collectedAt = 10, millis = 100)
        val newerOpen = day(date, StatisticsDayState.OPEN, collectedAt = 20, millis = 999)

        val merged = mergeUsageDeviceHistory(
            current = UsageStatisticsHistory(days = listOf(finalDay)),
            incoming = UsageStatisticsHistory(days = listOf(newerOpen)),
        )

        assertEquals(listOf(finalDay), merged.days)

        val nextDate = date.plusDays(1)
        val oldOpen = day(nextDate, StatisticsDayState.OPEN, collectedAt = 30, millis = 50)
        val latestOpen = day(nextDate, StatisticsDayState.OPEN, collectedAt = 40, millis = 75)
        val openMerged = mergeUsageDeviceHistory(
            current = UsageStatisticsHistory(days = listOf(oldOpen)),
            incoming = UsageStatisticsHistory(days = listOf(latestOpen)),
        )

        assertEquals(listOf(latestOpen), openMerged.days)
    }

    @Test
    fun allDevicesSumsAppsByDateWithoutChangingSourceRecords() {
        val date = LocalDate.parse("2026-07-30")
        val first = record(
            id = "11111111-1111-4111-8111-111111111111",
            name = "A",
            day = UsageStatisticsDay(
                date = date,
                zoneId = "Asia/Shanghai",
                state = StatisticsDayState.FINAL,
                collectedAtEpochMillis = 10,
                apps = listOf(
                    UsageAppDuration("com.example.one", 100),
                    UsageAppDuration("com.example.two", 50),
                ),
            ),
        )
        val second = record(
            id = "22222222-2222-4222-8222-222222222222",
            name = "B",
            day = UsageStatisticsDay(
                date = date,
                zoneId = "Asia/Shanghai",
                state = StatisticsDayState.OPEN,
                collectedAtEpochMillis = 20,
                apps = listOf(UsageAppDuration("com.example.one", 25)),
            ),
        )

        val combined = combineUsageDeviceHistories(listOf(first, second))

        assertEquals(StatisticsDayState.OPEN, combined.days.single().state)
        assertEquals(
            listOf(
                UsageAppDuration("com.example.one", 125),
                UsageAppDuration("com.example.two", 50),
            ),
            combined.days.single().apps,
        )
        assertEquals(100, first.history.days.single().apps.first().foregroundMillis)
    }

    private fun day(
        date: LocalDate,
        state: StatisticsDayState,
        collectedAt: Long,
        millis: Long,
    ) = UsageStatisticsDay(
        date = date,
        zoneId = "UTC",
        state = state,
        collectedAtEpochMillis = collectedAt,
        apps = listOf(UsageAppDuration("com.example", millis)),
    )

    private fun record(
        id: String,
        name: String,
        day: UsageStatisticsDay,
    ) = UsageDeviceRecord(
        deviceId = id,
        deviceName = name,
        platform = "android",
        updatedAtEpochMillis = day.collectedAtEpochMillis,
        history = UsageStatisticsHistory(
            trackingStartedOn = day.date,
            days = listOf(day),
            backfillCompletedThrough = day.date.takeIf {
                day.state == StatisticsDayState.FINAL
            },
        ),
    )
}
