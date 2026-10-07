package com.deskcubby.app.data.statistics

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class StepStatisticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: StepStatisticsStore,
) {
    private val refreshMutex = Mutex()
    private val mutableCollectionState = MutableStateFlow(StatisticsCollectionState())

    val history: StateFlow<StepStatisticsHistory> = store.history
    val collectionState: StateFlow<StatisticsCollectionState> =
        mutableCollectionState.asStateFlow()

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    fun permissionsToRequest(): Set<String> =
        StepHealthConnectAccess.permissionsToRequest(context)

    fun healthConnectAction(): StepHealthConnectAction =
        StepHealthConnectAccess.action(context)

    suspend fun hasStepReadPermission(): Boolean {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return false
        return StepHealthConnectAccess.stepReadPermission in
            HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
    }

    suspend fun hasHealthReadPermissions(): Boolean {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return false
        val granted = HealthConnectClient.getOrCreate(context)
            .permissionController
            .getGrantedPermissions()
        return granted.containsAll(StepHealthConnectAccess.healthReadPermissions)
    }

    suspend fun canReadInBackground(): Boolean {
        if (sdkStatus() != HealthConnectClient.SDK_AVAILABLE) return false
        val client = HealthConnectClient.getOrCreate(context)
        val featureAvailable = client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        if (!featureAvailable) return false
        return HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in
            client.permissionController.getGrantedPermissions()
    }

    suspend fun refresh(
        clock: Clock = Clock.systemDefaultZone(),
        fromBackground: Boolean = false,
    ): StatisticsRefreshOutcome = refreshMutex.withLock {
        val availability = sdkStatus()
        mutableCollectionState.value = mutableCollectionState.value.copy(
            phase = StatisticsCollectionPhase.REFRESHING,
            technicalDetail = null,
        )
        try {
            if (availability == HealthConnectClient.SDK_AVAILABLE) {
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                val healthPermissionDetail = when {
                    !granted.containsAll(StepHealthConnectAccess.healthReadPermissions) ->
                        DETAIL_HEALTH_PERMISSION
                    fromBackground &&
                        client.features.getFeatureStatus(
                            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
                        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE &&
                        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted ->
                        DETAIL_BACKGROUND_PERMISSION
                    else -> null
                }
                if (healthPermissionDetail == null) {
                    val refreshed = refreshFromHealthConnect(
                        client = client,
                        clock = clock,
                        reconciliationDays = if (fromBackground) {
                            BACKGROUND_RECONCILIATION_DAYS
                        } else {
                            FOREGROUND_RECONCILIATION_DAYS
                        },
                    )
                    val refreshedAt = refreshed.days
                        .maxOfOrNull(StepStatisticsDay::collectedAtEpochMillis)
                        ?: clock.millis()
                    mutableCollectionState.value = StatisticsCollectionState(
                        phase = StatisticsCollectionPhase.READY,
                        lastSuccessfulRefreshEpochMillis = refreshedAt,
                        technicalDetail = DETAIL_HEALTH_CONNECT,
                    )
                    return@withLock StatisticsRefreshOutcome.SUCCESS
                }
                mutableCollectionState.value = StatisticsCollectionState(
                    phase = StatisticsCollectionPhase.PERMISSION_REQUIRED,
                    technicalDetail = healthPermissionDetail,
                )
                return@withLock StatisticsRefreshOutcome.PERMISSION_REQUIRED
            }
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.UNAVAILABLE,
                technicalDetail = if (
                    availability == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
                ) {
                    DETAIL_PROVIDER_UPDATE_REQUIRED
                } else {
                    DETAIL_SDK_UNAVAILABLE
                },
            )
            StatisticsRefreshOutcome.UNAVAILABLE
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SecurityException) {
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.PERMISSION_REQUIRED,
                technicalDetail = DETAIL_STEP_PERMISSION,
            )
            StatisticsRefreshOutcome.PERMISSION_REQUIRED
        } catch (error: UnsupportedOperationException) {
            mutableCollectionState.value = StatisticsCollectionState(
                phase = StatisticsCollectionPhase.UNAVAILABLE,
                technicalDetail = DETAIL_SDK_UNAVAILABLE,
            )
            StatisticsRefreshOutcome.UNAVAILABLE
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

    fun reportHealthConnectOpenFailure() {
        mutableCollectionState.value = mutableCollectionState.value.copy(
            phase = StatisticsCollectionPhase.ERROR,
            technicalDetail = DETAIL_OPEN_HEALTH_CONNECT_FAILED,
        )
    }

    private suspend fun refreshFromHealthConnect(
        client: HealthConnectClient,
        clock: Clock,
        reconciliationDays: Int,
    ): StepStatisticsHistory {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val firstDate = healthReconciliationStartDate(
            today = today,
            reconciliationDays = reconciliationDays,
        )
        val replacements = mutableMapOf<LocalDate, StepStatisticsDay>()
        var date = firstDate
        while (!date.isAfter(today)) {
            replacements[date] = queryDay(
                client = client,
                date = date,
                today = today,
                zone = zone,
                nowMillis = clock.millis(),
            )
            date = date.plusDays(1)
        }
        return store.update { latest ->
            val byDate = latest.days.associateBy(StepStatisticsDay::date).toMutableMap()
            replacements.forEach { (replacementDate, replacement) ->
                // Health Connect sources may upload an older day's data late (for example when a
                // wearable/vendor app is opened after several days). Reconcile recent FINAL rows
                // instead of treating them as immutable snapshots.
                byDate[replacementDate] = replacement
            }
            latest.copy(
                trackingStartedOn = listOfNotNull(
                    latest.trackingStartedOn,
                    firstDate,
                ).minOrNull(),
                days = byDate.values.sortedBy(StepStatisticsDay::date),
                deviceSensorBaseline = null,
            )
        }
    }

    private suspend fun queryDay(
        client: HealthConnectClient,
        date: LocalDate,
        today: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
    ): StepStatisticsDay {
        val start = date.atStartOfDay(zone).toInstant()
        val naturalEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val end = if (date == today) {
            java.time.Instant.ofEpochMilli(nowMillis).coerceAtMost(naturalEnd)
        } else {
            naturalEnd
        }
        val safeEnd = if (end.isAfter(start)) end else start.plusMillis(1)
        val aggregate = client.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(start, safeEnd),
            ),
        )
        return StepStatisticsDay(
            date = date,
            zoneId = zone.id,
            state = if (date == today) StatisticsDayState.OPEN else StatisticsDayState.FINAL,
            collectedAtEpochMillis = nowMillis,
            steps = aggregate[StepsRecord.COUNT_TOTAL],
            distanceMeters = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
            activeCaloriesKilocalories =
                aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                    ?: aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
        )
    }

    companion object {
        const val DETAIL_SDK_UNAVAILABLE = "health_connect_unavailable"
        const val DETAIL_PROVIDER_UPDATE_REQUIRED = "health_connect_update_required"
        const val DETAIL_HEALTH_PERMISSION = "health_permission_required"
        const val DETAIL_STEP_PERMISSION = DETAIL_HEALTH_PERMISSION
        const val DETAIL_BACKGROUND_PERMISSION = "background_permission_required"
        const val DETAIL_OPEN_HEALTH_CONNECT_FAILED = "health_connect_open_failed"
        const val DETAIL_HEALTH_CONNECT = "health_connect"

        // Foreground refreshes deliberately re-read a bounded historical window because Health
        // Connect providers can backfill old dates after DeskCubby already finalized them.
        private const val FOREGROUND_RECONCILIATION_DAYS = 30
        private const val BACKGROUND_RECONCILIATION_DAYS = 7
    }
}

internal fun healthReconciliationStartDate(
    today: LocalDate,
    reconciliationDays: Int,
): LocalDate =
    today.minusDays((reconciliationDays.coerceAtLeast(1) - 1).toLong())

private fun java.time.Instant.coerceAtMost(maximum: java.time.Instant): java.time.Instant =
    if (isAfter(maximum)) maximum else this
