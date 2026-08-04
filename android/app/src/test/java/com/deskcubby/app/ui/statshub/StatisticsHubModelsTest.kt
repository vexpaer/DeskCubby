package com.deskcubby.app.ui.statshub

import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.repository.ReaderBook
import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.statistics.EngagementTimeSnapshot
import com.deskcubby.app.data.statistics.StatisticsDayState
import com.deskcubby.app.data.statistics.StepStatisticsDay
import com.deskcubby.app.data.statistics.StepStatisticsHistory
import com.deskcubby.app.data.statistics.UsageAppDuration
import com.deskcubby.app.data.statistics.UsageDeviceRecord
import com.deskcubby.app.data.statistics.UsageStatisticsDay
import com.deskcubby.app.data.statistics.UsageStatisticsHistory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatisticsHubModelsTest {
    @Test
    fun diaryStatistics_ignoreInvalidAndFutureDatesForStreaksAndMonthlyChart() {
        val today = LocalDate.of(2026, 8, 4)
        val diaries = listOf(
            diary("2026-08-04", 100),
            diary("2026-08-03", 200),
            diary("2026-08-01", 300),
            diary("2026-07-31", 400),
            diary("2026-07-30", 500),
            diary("not-a-date", 600),
            diary("2026-08-05", 700),
        )

        val result = deriveDiaryStatistics(diaries, today)

        assertEquals(7, result.entryCount)
        assertEquals(2_800L, result.totalWords)
        assertEquals(2, result.currentStreakDays)
        assertEquals(3, result.longestStreakDays)
        assertEquals(24, result.monthlyWords.size)
        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 1) to 900.0,
                LocalDate.of(2026, 8, 1) to 600.0,
            ),
            result.monthlyWords.takeLast(2).map { it.date to it.value },
        )
    }

    @Test
    fun currentStreak_allowsYesterdayWhenTodayHasNoEntry() {
        val today = LocalDate.of(2026, 8, 4)

        assertEquals(
            3,
            currentDiaryStreak(
                setOf(today.minusDays(1), today.minusDays(2), today.minusDays(3)),
                today,
            ),
        )
        assertEquals(0, currentDiaryStreak(setOf(today.minusDays(2)), today))
    }

    @Test
    fun hubCombinesDevicesAndKeepsMissingHealthDayUnknown() {
        val today = LocalDate.of(2026, 8, 4)
        val usageRecords = listOf(
            usageRecord("a", today, 30 * 60_000L),
            usageRecord("b", today, 45 * 60_000L),
        )
        val health = StepStatisticsHistory(
            trackingStartedOn = today.minusDays(1),
            days = listOf(
                StepStatisticsDay(
                    date = today.minusDays(1),
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.FINAL,
                    collectedAtEpochMillis = 1L,
                    steps = 8_000L,
                ),
                StepStatisticsDay(
                    date = today,
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.OPEN,
                    collectedAtEpochMillis = 2L,
                    steps = null,
                ),
            ),
        )

        val result = deriveStatisticsHubState(
            diaries = emptyList(),
            usageRecords = usageRecords,
            healthHistory = health,
            engagement = EngagementTimeSnapshot(),
            books = emptyList(),
            gameStates = emptyList(),
            usageEnabled = true,
            healthEnabled = true,
            today = today,
        )

        assertEquals(75 * 60_000.0, result.usage.todayValue ?: -1.0, 0.0)
        assertEquals(75 * 60_000.0, result.usage.lastSevenTotal, 0.0)
        assertEquals(1, result.usage.lastSevenRecordedDays)
        assertNull(result.health.todayValue)
        assertEquals(8_000.0, result.health.lastSevenTotal, 0.0)
        assertEquals(1, result.health.lastSevenRecordedDays)
        assertEquals(7, result.health.lastSevenPoints.size)
    }

    @Test
    fun hubMapsReadingTitlesAndGameMetricsWithoutMixingEngagementTime() {
        val book = ReaderBook(
            id = "book-1",
            uri = "content://reader/book-1",
            title = "A Book",
            type = ReaderBookType.TXT,
            addedAt = 1L,
            lastOpenedAt = 2L,
        )
        val engagement = EngagementTimeSnapshot(
            gameTotalsMillis = mapOf("minesweeper" to 120_000L),
            readingTotalsMillis = mapOf("book-1" to 60_000L, "removed" to 30_000L),
        )

        val result = deriveStatisticsHubState(
            diaries = emptyList(),
            usageRecords = emptyList(),
            healthHistory = StepStatisticsHistory(),
            engagement = engagement,
            books = listOf(book),
            gameStates = listOf(GameStateEntity("minesweeper", 42, null, 3L)),
            usageEnabled = false,
            healthEnabled = false,
            gameMetrics = mapOf(
                "minesweeper" to mapOf("minesSwept" to 12L, "wins" to 2L),
            ),
            today = LocalDate.of(2026, 8, 4),
        )

        assertEquals(90_000L, result.totalReadingMillis)
        assertEquals("A Book", result.reading.first { it.id == "book-1" }.title)
        assertNull(result.reading.first { it.id == "removed" }.title)
        val mines = result.games.first { it.gameId == "minesweeper" }
        assertEquals(120_000L, mines.totalPlayMillis)
        assertEquals(42L, mines.highScore)
        assertEquals(12L, mines.metrics["minesSwept"])
        assertEquals(2L, mines.metrics["wins"])
    }

    private fun diary(date: String, words: Int): DiaryIndexEntity = DiaryIndexEntity(
        uri = "content://diary/$date/$words",
        name = "$date.md",
        title = date,
        dateIso = date,
        monthKey = date.take(7),
        lastModified = 1L,
        size = words.toLong(),
        wordCount = words,
        sha256 = "sha-$date-$words",
        indexedAt = 1L,
    )

    private fun usageRecord(
        deviceId: String,
        date: LocalDate,
        duration: Long,
    ): UsageDeviceRecord = UsageDeviceRecord(
        deviceId = deviceId,
        deviceName = deviceId,
        platform = "android",
        updatedAtEpochMillis = 1L,
        history = UsageStatisticsHistory(
            trackingStartedOn = date,
            days = listOf(
                UsageStatisticsDay(
                    date = date,
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.OPEN,
                    collectedAtEpochMillis = 1L,
                    apps = listOf(UsageAppDuration("example.$deviceId", duration)),
                ),
            ),
        ),
    )
}
