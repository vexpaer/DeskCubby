package com.deskcubby.app.data.statistics

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    suspend fun refresh(
        clock: Clock = Clock.systemDefaultZone(),
    ): StatisticsRefreshOutcome = refreshMutex.withLock {
        if (!hasUsageAccess()) {
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.PERMISSION_REQUIRED,
            )
            return@withLock StatisticsRefreshOutcome.PERMISSION_REQUIRED
        }
        mutableCollectionState.value = mutableCollectionState.value.copy(
            phase = StatisticsCollectionPhase.REFRESHING,
            technicalDetail = null,
        )
        try {
            val zone = clock.zone
            val today = LocalDate.now(clock)
            val current = store.history.value
            val firstDate = current.trackingStartedOn ?: today
            val replacements = mutableMapOf<LocalDate, UsageStatisticsDay>()
            var date = firstDate
            while (!date.isAfter(today)) {
                val existing = current.days.firstOrNull { it.date == date }
                if (existing?.state != StatisticsDayState.FINAL) {
                    replacements[date] = queryDay(
                        date = date,
                        today = today,
                        zone = zone,
                        nowMillis = clock.millis(),
                    )
                }
                date = date.plusDays(1)
            }
            val refreshed = store.update { latest ->
                val byDate = latest.days.associateBy(UsageStatisticsDay::date).toMutableMap()
                replacements.forEach { (replacementDate, replacement) ->
                    if (byDate[replacementDate]?.state != StatisticsDayState.FINAL) {
                        byDate[replacementDate] = replacement
                    }
                }
                latest.copy(
                    trackingStartedOn = latest.trackingStartedOn ?: firstDate,
                    days = byDate.values.sortedBy(UsageStatisticsDay::date),
                )
            }
            val refreshedAt = refreshed.days.maxOfOrNull(UsageStatisticsDay::collectedAtEpochMillis)
                ?: clock.millis()
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.READY,
                lastSuccessfulRefreshEpochMillis = refreshedAt,
            )
            StatisticsRefreshOutcome.SUCCESS
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
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

    private suspend fun queryDay(
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
    ): UsageStatisticsDay = withContext(Dispatchers.IO) {
        val startMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val naturalEndMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = if (date == today) nowMillis.coerceAtMost(naturalEndMillis) else naturalEndMillis
        val safeEndMillis = endMillis.coerceAtLeast(startMillis + 1L)
        val queryStartMillis = date.minusDays(FOREGROUND_STATE_LOOKBACK_DAYS)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        val events = usageStatsManager.queryEvents(queryStartMillis, safeEndMillis)
            ?: throw IllegalStateException("Usage event query returned no result.")
        val transitions = readTransitions(events)
        UsageStatisticsDay(
            date = date,
            zoneId = zone.id,
            state = if (date == today) StatisticsDayState.OPEN else StatisticsDayState.FINAL,
            collectedAtEpochMillis = nowMillis,
            apps = aggregateForegroundUsage(
                windowStartMillis = startMillis,
                windowEndMillis = safeEndMillis,
                events = transitions,
            ),
        )
    }

    private fun readTransitions(events: UsageEvents): List<ForegroundTransition> {
        val result = ArrayList<ForegroundTransition>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (result.size >= MAX_USAGE_EVENTS_PER_QUERY) {
                throw IllegalStateException("Usage event query exceeded the safety limit.")
            }
            if (!events.getNextEvent(event)) break
            val type = when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND ->
                    ForegroundTransitionType.ENTER_FOREGROUND

                UsageEvents.Event.MOVE_TO_BACKGROUND ->
                    ForegroundTransitionType.LEAVE_FOREGROUND

                else -> null
            }
            val packageName = event.packageName
            if (type != null && !packageName.isNullOrBlank()) {
                result += ForegroundTransition(
                    epochMillis = event.timeStamp,
                    packageName = packageName,
                    type = type,
                )
            }
        }
        return result
    }

    private companion object {
        const val FOREGROUND_STATE_LOOKBACK_DAYS = 7L
        const val MAX_USAGE_EVENTS_PER_QUERY = 1_000_000
    }
}
