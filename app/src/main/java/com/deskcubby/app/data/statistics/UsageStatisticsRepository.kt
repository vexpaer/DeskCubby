package com.deskcubby.app.data.statistics

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class UsageStatisticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: UsageStatisticsStore,
) {
    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)
    private val appOpsManager = context.getSystemService(AppOpsManager::class.java)
    private val refreshMutex = Mutex()
    private val mutableCollectionState = MutableStateFlow(StatisticsCollectionState())

    @Volatile
    private var refreshGeneration = 0L
    private var lastRefreshOutcome = StatisticsRefreshOutcome.ERROR

    val history: StateFlow<UsageStatisticsHistory> = store.history
    val collectionState: StateFlow<StatisticsCollectionState> =
        mutableCollectionState.asStateFlow()

    fun hasUsageAccess(): Boolean = runCatching {
        appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Concurrent callers share the result of the refresh already in progress.
     * A later call made after completion remains an explicit new refresh.
     */
    suspend fun refresh(
        clock: Clock = Clock.systemDefaultZone(),
    ): StatisticsRefreshOutcome {
        val observedGeneration = refreshGeneration
        return refreshMutex.withLock {
            if (refreshGeneration != observedGeneration) {
                return@withLock lastRefreshOutcome
            }
            val outcome = performRefresh(clock)
            lastRefreshOutcome = outcome
            refreshGeneration += 1L
            outcome
        }
    }

    private suspend fun performRefresh(clock: Clock): StatisticsRefreshOutcome {
        if (!hasUsageAccess()) {
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.PERMISSION_REQUIRED,
            )
            return StatisticsRefreshOutcome.PERMISSION_REQUIRED
        }
        mutableCollectionState.value = mutableCollectionState.value.copy(
            phase = StatisticsCollectionPhase.REFRESHING,
            technicalDetail = null,
        )
        return try {
            val zone = clock.zone
            val nowMillis = clock.millis()
            val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
            val current = store.history.value
            val queryStartDate = usageQueryStartDate(
                history = current,
                today = today,
            )
            val rawBuckets = queryDailyBuckets(
                beginDate = queryStartDate,
                today = today,
                zone = zone,
                nowMillis = nowMillis,
            )
            val replacements = aggregateDailyUsageBuckets(
                buckets = rawBuckets,
                firstRequestedDate = queryStartDate,
                today = today,
                zone = zone,
                nowMillis = nowMillis,
            )
            val attemptedCompletedThrough = today.minusDays(1L)
            store.update { latest ->
                val latestNeededStart = usageQueryStartDate(
                    history = latest,
                    today = today,
                )
                val coveredLatestDiscoveryWindow = !queryStartDate.isAfter(latestNeededStart)
                mergeUsageStatisticsHistory(
                    current = latest,
                    replacements = replacements,
                    backfillCompletedThrough = attemptedCompletedThrough.takeIf {
                        coveredLatestDiscoveryWindow
                    },
                )
            }
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.READY,
                lastSuccessfulRefreshEpochMillis = nowMillis,
            )
            StatisticsRefreshOutcome.SUCCESS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.PERMISSION_REQUIRED,
            )
            StatisticsRefreshOutcome.PERMISSION_REQUIRED
        } catch (error: Exception) {
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.ERROR,
                technicalDetail = error.message,
            )
            StatisticsRefreshOutcome.ERROR
        }
    }

    fun markDisabled() {
        mutableCollectionState.value = mutableCollectionState.value.copy(
            phase = StatisticsCollectionPhase.DISABLED,
            technicalDetail = null,
        )
    }

    private suspend fun queryDailyBuckets(
        beginDate: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
    ): List<RawDailyUsageBucket> = withContext(Dispatchers.IO) {
        val requestedDayCount = ChronoUnit.DAYS.between(beginDate, today) + 1L
        require(requestedDayCount in 1L..MAX_USAGE_BACKFILL_DAYS)
        val result = ArrayList<RawDailyUsageBucket>()
        var chunkStartDate = beginDate
        while (!chunkStartDate.isAfter(today)) {
            currentCoroutineContext().ensureActive()
            val chunkEndDateExclusive = minOf(
                chunkStartDate.plusDays(MAX_USAGE_QUERY_SPAN_DAYS),
                today.plusDays(1L),
            )
            val beginMillis = chunkStartDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = if (chunkEndDateExclusive.isAfter(today)) {
                nowMillis
            } else {
                chunkEndDateExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            }
            if (endMillis <= beginMillis) {
                // Exactly at local midnight there is no real current-day
                // interval to ask Android for yet.
                chunkStartDate = chunkEndDateExclusive
                continue
            }
            require(beginMillis >= 0L && endMillis > beginMillis)
            val values = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                beginMillis,
                endMillis,
            ) ?: throw IllegalStateException("Daily usage query returned no result.")
            if (
                values.size > MAX_USAGE_BUCKETS_PER_QUERY ||
                values.size > MAX_USAGE_BUCKETS_TOTAL - result.size
            ) {
                throw IllegalStateException("Daily usage query exceeded the safety limit.")
            }
            values.forEachIndexed { index, value ->
                if (index % CANCELLATION_CHECK_INTERVAL == 0) {
                    currentCoroutineContext().ensureActive()
                }
                result.add(
                    RawDailyUsageBucket(
                        beginEpochMillis = value.firstTimeStamp,
                        endEpochMillis = value.lastTimeStamp,
                        packageName = value.packageName.orEmpty(),
                        foregroundMillis = value.totalTimeInForeground,
                    ),
                )
            }
            chunkStartDate = chunkEndDateExclusive
        }
        result
    }

    private companion object {
        const val MAX_USAGE_BUCKETS_PER_QUERY = 100_000
        const val MAX_USAGE_BUCKETS_TOTAL = 100_000
        const val MAX_USAGE_QUERY_SPAN_DAYS = 366L
        const val CANCELLATION_CHECK_INTERVAL = 512
    }
}

internal fun usageQueryStartDate(
    history: UsageStatisticsHistory,
    today: LocalDate,
    maximumBackfillDays: Long = MAX_USAGE_BACKFILL_DAYS,
    openRetryDays: Long = MAX_OPEN_RETRY_DAYS,
): LocalDate {
    require(maximumBackfillDays > 0L)
    require(openRetryDays > 0L)
    val historyFloor = today.minusDays(maximumBackfillDays - 1L)
    if (history.backfillCompletedThrough == null) return historyFloor

    val firstUnscannedDate = runCatching {
        history.backfillCompletedThrough.plusDays(1L)
    }.getOrDefault(today).coerceAtMost(today)
    val retryFloor = today.minusDays(openRetryDays - 1L).coerceAtLeast(historyFloor)
    val oldestRetryableOpen = history.days.asSequence()
        .filter { day ->
            day.state == StatisticsDayState.OPEN &&
                !day.date.isBefore(retryFloor) &&
                !day.date.isAfter(today)
        }
        .minOfOrNull(UsageStatisticsDay::date)
    return minOf(
        today,
        firstUnscannedDate,
        oldestRetryableOpen ?: today,
    ).coerceAtLeast(historyFloor)
}

internal fun mergeUsageStatisticsHistory(
    current: UsageStatisticsHistory,
    replacements: Collection<UsageStatisticsDay>,
    backfillCompletedThrough: LocalDate? = current.backfillCompletedThrough,
): UsageStatisticsHistory {
    val byDate = current.days.associateBy(UsageStatisticsDay::date).toMutableMap()
    replacements.forEach { replacement ->
        if (byDate[replacement.date]?.state != StatisticsDayState.FINAL) {
            byDate[replacement.date] = replacement
        }
    }
    val days = byDate.values.sortedBy(UsageStatisticsDay::date)
    return UsageStatisticsHistory(
        trackingStartedOn = days.firstOrNull()?.date,
        days = days,
        backfillCompletedThrough = listOfNotNull(
            current.backfillCompletedThrough,
            backfillCompletedThrough,
        ).maxOrNull(),
    )
}

private const val MAX_USAGE_BACKFILL_DAYS = 3_650L
private const val MAX_OPEN_RETRY_DAYS = 31L
