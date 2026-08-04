package com.deskcubby.app.data.statistics

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.usageStartupDataStore by preferencesDataStore(name = "usage_startup_state")
private val LAST_APP_OPEN_COLLECTION_DATE = stringPreferencesKey("last_app_open_collection_date")

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

    /** Refreshes once per local calendar day when the application process starts. */
    suspend fun refreshOnAppOpenIfNeeded(
        clock: Clock = Clock.systemDefaultZone(),
    ): StatisticsRefreshOutcome? {
        val today = LocalDate.now(clock).toString()
        val alreadyCollected = context.usageStartupDataStore.data
            .first()[LAST_APP_OPEN_COLLECTION_DATE] == today
        if (alreadyCollected) return null

        val outcome = refresh(clock)
        if (outcome == StatisticsRefreshOutcome.SUCCESS) {
            context.usageStartupDataStore.edit { prefs ->
                prefs[LAST_APP_OPEN_COLLECTION_DATE] = today
            }
        }
        return outcome
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
            val current = store.current()
            val queryStartDate = usageQueryStartDate(
                history = current,
                today = today,
            )
            val eventQuery = queryUsageEvents(
                beginDate = queryStartDate,
                zone = zone,
                nowMillis = nowMillis,
            )
            val eventAggregation = aggregateUsageEvents(
                query = eventQuery,
                firstRequestedDate = queryStartDate,
                today = today,
                zone = zone,
                nowMillis = nowMillis,
            )
            val replacements = eventAggregation.days.toMutableList()
            if (replacements.none { it.date == today }) {
                queryCurrentDayFallback(today, zone, nowMillis)?.let(replacements::add)
            }
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
                    replaceFinal = true,
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

    private suspend fun queryUsageEvents(
        beginDate: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
    ): UsageEventQueryResult = withContext(Dispatchers.IO) {
        // Include earlier events so a session crossing into the first requested midnight has a
        // known start, and so ordinary incremental refreshes are not mistaken for a retention
        // boundary merely because the first app use happened after midnight.
        val eventSeedDate = beginDate.minusDays(USAGE_EVENT_SEED_LOOKBACK_DAYS)
        val beginMillis = eventSeedDate.atStartOfDay(zone).toInstant().toEpochMilli()
        require(nowMillis > beginMillis)
        val usageEvents = usageStatsManager.queryEvents(beginMillis, nowMillis)
            ?: throw IllegalStateException("Usage event query returned no result.")
        val copied = ArrayList<RawUsageEvent>()
        var earliestEventEpochMillis: Long? = null
        var totalEvents = 0
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            currentCoroutineContext().ensureActive()
            if (!usageEvents.getNextEvent(event)) break
            totalEvents += 1
            if (totalEvents > MAX_USAGE_EVENTS_TOTAL) {
                throw IllegalStateException("Usage event query exceeded the safety limit.")
            }
            val timestamp = event.timeStamp
            if (timestamp !in beginMillis..nowMillis) continue
            earliestEventEpochMillis = minOf(earliestEventEpochMillis ?: timestamp, timestamp)
            val kind = when {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ->
                    RawUsageEventKind.FOREGROUND
                event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ->
                    RawUsageEventKind.BACKGROUND
                event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE ||
                    event.eventType == UsageEvents.Event.KEYGUARD_SHOWN ||
                    event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN ->
                    RawUsageEventKind.STOP_ALL
                else -> null
            }
            if (kind != null) {
                copied += RawUsageEvent(
                    timestampEpochMillis = timestamp,
                    packageName = event.packageName,
                    kind = kind,
                )
            }
        }
        UsageEventQueryResult(copied, earliestEventEpochMillis)
    }

    private suspend fun queryCurrentDayFallback(
        today: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
    ): UsageStatisticsDay? = withContext(Dispatchers.IO) {
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val values = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            start,
            nowMillis,
        ) ?: return@withContext null
        if (values.size > MAX_USAGE_BUCKETS_PER_QUERY) {
            throw IllegalStateException("Daily usage query exceeded the safety limit.")
        }
        currentCoroutineContext().ensureActive()
        aggregateDailyUsageBuckets(
            buckets = exactDayUsageBuckets(
                values = values.map { value ->
                    RawQueriedUsage(
                        packageName = value.packageName.orEmpty(),
                        foregroundMillis = value.totalTimeInForeground,
                    )
                },
                dayStartMillis = start,
                dayEndMillis = nowMillis,
            ),
            firstRequestedDate = today,
            today = today,
            zone = zone,
            nowMillis = nowMillis,
        ).singleOrNull()
    }

    private companion object {
        const val MAX_USAGE_BUCKETS_PER_QUERY = 100_000
        const val MAX_USAGE_EVENTS_TOTAL = 500_000
        const val USAGE_EVENT_SEED_LOOKBACK_DAYS = 31L
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
    replaceFinal: Boolean = false,
): UsageStatisticsHistory {
    val byDate = current.days.associateBy(UsageStatisticsDay::date).toMutableMap()
    replacements.forEach { replacement ->
        if (replaceFinal || byDate[replacement.date]?.state != StatisticsDayState.FINAL) {
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
