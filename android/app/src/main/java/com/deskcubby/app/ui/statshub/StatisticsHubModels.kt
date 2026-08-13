package com.deskcubby.app.ui.statshub

import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.local.GameStateEntity
import com.deskcubby.app.data.repository.ReaderBook
import com.deskcubby.app.data.statistics.EngagementTimeSnapshot
import com.deskcubby.app.data.statistics.AgentTokenStatistics
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.statistics.StepStatisticsHistory
import com.deskcubby.app.data.statistics.UsageDeviceRecord
import com.deskcubby.app.data.statistics.combineUsageDeviceHistories
import java.time.LocalDate
import java.time.YearMonth

data class DiaryStatisticsSummary(
    val entryCount: Int = 0,
    val totalWords: Long = 0L,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val monthlyWords: List<StatisticsPoint> = emptyList(),
)

data class RecentStatisticsSummary(
    val enabled: Boolean = false,
    val recordedDays: Int = 0,
    val todayValue: Double? = null,
    val lastSevenTotal: Double = 0.0,
    /** Always seven ordered civil days; a null value means no trustworthy daily result. */
    val lastSevenPoints: List<StatisticsPoint> = emptyList(),
) {
    val lastSevenRecordedDays: Int
        get() = lastSevenPoints.count { it.value != null }
}

data class EngagementStatisticsItem(
    val id: String,
    val title: String?,
    val totalMillis: Long,
)

data class GameStatisticsHubItem(
    val gameId: String,
    val totalPlayMillis: Long = 0L,
    val highScore: Long = 0L,
    /** Stable, non-localized metric keys supplied by GameStatisticsRepository. */
    val metrics: Map<String, Long> = emptyMap(),
) {
    val hasAnyStatistics: Boolean
        get() = totalPlayMillis > 0L || highScore > 0L || metrics.values.any { it > 0L }
}

data class StatisticsHubUiState(
    val initializing: Boolean = true,
    val diary: DiaryStatisticsSummary = DiaryStatisticsSummary(),
    val usage: RecentStatisticsSummary = RecentStatisticsSummary(),
    val health: RecentStatisticsSummary = RecentStatisticsSummary(),
    val reading: List<EngagementStatisticsItem> = emptyList(),
    val games: List<GameStatisticsHubItem> = defaultGameStatisticsHubItems(),
    val agent: AgentTokenStatistics = AgentTokenStatistics(),
) {
    val totalReadingMillis: Long
        get() = reading.saturatedDurationSum()

    val totalGameMillis: Long
        get() = games.asSequence().map(GameStatisticsHubItem::totalPlayMillis).saturatedSum()
}

internal fun deriveStatisticsHubState(
    diaries: List<DiaryIndexEntity>,
    usageRecords: List<UsageDeviceRecord>,
    healthHistory: StepStatisticsHistory,
    engagement: EngagementTimeSnapshot,
    books: List<ReaderBook>,
    gameStates: List<GameStateEntity>,
    usageEnabled: Boolean,
    healthEnabled: Boolean,
    gameMetrics: Map<String, Map<String, Long>> = emptyMap(),
    today: LocalDate = LocalDate.now(),
): StatisticsHubUiState {
    val combinedUsage = combineUsageDeviceHistories(usageRecords)
    val usageByDate = combinedUsage.days.associate { day ->
        day.date to day.totalForegroundMillis.toDouble()
    }
    val healthByDate = healthHistory.days.associate { day ->
        day.date to day.steps?.toDouble()
    }
    val titlesByBookId = books.associate { it.id to it.title }
    val highScoresByGameId = gameStates.associate { state ->
        state.gameId to state.highScore.toLong().coerceAtLeast(0L)
    }
    return StatisticsHubUiState(
        initializing = false,
        diary = deriveDiaryStatistics(diaries, today),
        usage = recentStatisticsSummary(
            enabled = usageEnabled,
            recordedDays = combinedUsage.days.size,
            valuesByDate = usageByDate,
            today = today,
        ),
        health = recentStatisticsSummary(
            enabled = healthEnabled,
            recordedDays = healthHistory.days.size,
            valuesByDate = healthByDate,
            today = today,
        ),
        reading = engagement.readingTotalsMillis.asSequence()
            .filter { (_, totalMillis) -> totalMillis > 0L }
            .map { (bookId, totalMillis) ->
                EngagementStatisticsItem(
                    id = bookId,
                    title = titlesByBookId[bookId] ?: engagement.readingTitles[bookId],
                    totalMillis = totalMillis.coerceAtLeast(0L),
                )
            }
            .sortedWith(
                compareByDescending(EngagementStatisticsItem::totalMillis)
                    .thenBy { it.title.orEmpty() }
                    .thenBy(EngagementStatisticsItem::id),
            )
            .toList(),
        games = defaultGameStatisticsHubItems().map { item ->
            item.copy(
                totalPlayMillis = engagement.gameTotalsMillis[item.gameId]
                    ?.coerceAtLeast(0L)
                    ?: 0L,
                highScore = highScoresByGameId[item.gameId] ?: 0L,
                metrics = gameMetrics[item.gameId].orEmpty()
                    .mapValues { (_, value) -> value.coerceAtLeast(0L) },
            )
        },
    )
}

internal fun deriveDiaryStatistics(
    diaries: List<DiaryIndexEntity>,
    today: LocalDate,
): DiaryStatisticsSummary {
    val datedEntries = diaries.mapNotNull { diary ->
        runCatching { LocalDate.parse(diary.dateIso) }
            .getOrNull()
            ?.takeUnless { it.isAfter(today) }
            ?.let { date -> date to diary.wordCount.coerceAtLeast(0).toLong() }
    }
    val dates = datedEntries.asSequence().map(Pair<LocalDate, Long>::first).toSortedSet()
    val currentStreak = currentDiaryStreak(dates, today)
    val longestStreak = longestDiaryStreak(dates)
    val wordsByMonth = datedEntries
        .groupingBy { (date, _) -> YearMonth.from(date) }
        .fold(0L) { total, (_, words) -> saturatedAdd(total, words) }
    val latestMonth = YearMonth.from(today)
    val monthlyWords = if (datedEntries.isEmpty()) {
        emptyList()
    } else {
        (23L downTo 0L).map { offset ->
            val month = latestMonth.minusMonths(offset)
            StatisticsPoint(month.atDay(1), (wordsByMonth[month] ?: 0L).toDouble())
        }
    }
    return DiaryStatisticsSummary(
        entryCount = diaries.size,
        totalWords = diaries.asSequence()
            .map { it.wordCount.coerceAtLeast(0).toLong() }
            .saturatedSum(),
        currentStreakDays = currentStreak,
        longestStreakDays = longestStreak,
        monthlyWords = monthlyWords,
    )
}

internal fun currentDiaryStreak(dates: Set<LocalDate>, today: LocalDate): Int {
    var cursor = if (today in dates) today else today.minusDays(1L)
    var result = 0
    while (cursor in dates && result < Int.MAX_VALUE) {
        result += 1
        cursor = cursor.minusDays(1L)
    }
    return result
}

internal fun longestDiaryStreak(dates: Set<LocalDate>): Int {
    var longest = 0
    var current = 0
    var previous: LocalDate? = null
    dates.sorted().forEach { date ->
        current = if (previous?.plusDays(1L) == date) current + 1 else 1
        longest = maxOf(longest, current)
        previous = date
    }
    return longest
}

private fun recentStatisticsSummary(
    enabled: Boolean,
    recordedDays: Int,
    valuesByDate: Map<LocalDate, Double?>,
    today: LocalDate,
): RecentStatisticsSummary {
    val points = (6L downTo 0L).map { offset ->
        val date = today.minusDays(offset)
        StatisticsPoint(date, valuesByDate[date])
    }
    return RecentStatisticsSummary(
        enabled = enabled,
        recordedDays = recordedDays,
        todayValue = valuesByDate[today],
        lastSevenTotal = points.mapNotNull(StatisticsPoint::value).sum(),
        lastSevenPoints = points,
    )
}

internal fun defaultGameStatisticsHubItems(): List<GameStatisticsHubItem> = listOf(
    GameStatisticsHubItem("2048"),
    GameStatisticsHubItem("2048_5"),
    GameStatisticsHubItem("2048_6"),
    GameStatisticsHubItem("snake"),
    GameStatisticsHubItem("tetris"),
    GameStatisticsHubItem("minesweeper"),
    GameStatisticsHubItem("spider"),
    GameStatisticsHubItem("go"),
)

private fun Iterable<EngagementStatisticsItem>.saturatedDurationSum(): Long =
    asSequence().map(EngagementStatisticsItem::totalMillis).saturatedSum()

private fun Sequence<Long>.saturatedSum(): Long = fold(0L, ::saturatedAdd)

private fun saturatedAdd(left: Long, right: Long): Long {
    val safeRight = right.coerceAtLeast(0L)
    return if (Long.MAX_VALUE - left < safeRight) Long.MAX_VALUE else left + safeRight
}
