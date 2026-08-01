package com.deskcubby.app.data.statistics

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
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
    private val deviceStepCounterAccess: DeviceStepCounterAccess,
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

    fun isDeviceStepCounterAvailable(): Boolean = deviceStepCounterAccess.isAvailable()

    fun isDeviceStepCounterPermissionRequired(): Boolean =
        deviceStepCounterAccess.isAvailable() && !deviceStepCounterAccess.hasPermission()

    fun deviceStepCounterPermission(): String? = deviceStepCounterAccess.runtimePermission()

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
            var healthPermissionDetail: String? = null
            if (availability == HealthConnectClient.SDK_AVAILABLE) {
                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                healthPermissionDetail = when {
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
                    val refreshed = refreshFromHealthConnect(client, clock)
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
            }

            if (deviceStepCounterAccess.isAvailable()) {
                if (!deviceStepCounterAccess.hasPermission()) {
                    mutableCollectionState.value = StatisticsCollectionState(
                        phase = StatisticsCollectionPhase.PERMISSION_REQUIRED,
                        technicalDetail = DETAIL_DEVICE_SENSOR_PERMISSION,
                    )
                    return@withLock StatisticsRefreshOutcome.PERMISSION_REQUIRED
                }
                val cumulativeSteps = deviceStepCounterAccess.readCumulativeSteps()
                    ?: throw IllegalStateException("Device step counter did not return a sample.")
                val today = LocalDate.now(clock)
                val refreshed = store.update { latest ->
                    mergeDeviceStepCounterSample(
                        history = latest,
                        date = today,
                        zoneId = clock.zone.id,
                        capturedAtEpochMillis = clock.millis(),
                        cumulativeSteps = cumulativeSteps,
                    )
                }
                mutableCollectionState.value = StatisticsCollectionState(
                    phase = StatisticsCollectionPhase.READY,
                    lastSuccessfulRefreshEpochMillis =
                        refreshed.deviceSensorBaseline?.capturedAtEpochMillis,
                    technicalDetail = DETAIL_DEVICE_STEP_COUNTER,
                )
                return@withLock StatisticsRefreshOutcome.SUCCESS
            }

            if (healthPermissionDetail != null) {
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
                technicalDetail = if (deviceStepCounterAccess.isAvailable()) {
                    DETAIL_DEVICE_SENSOR_PERMISSION
                } else {
                    DETAIL_STEP_PERMISSION
                },
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
    ): StepStatisticsHistory {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val current = store.history.value
        val firstDate = current.trackingStartedOn ?: today
        val replacements = mutableMapOf<LocalDate, StepStatisticsDay>()
        var date = firstDate
        while (!date.isAfter(today)) {
            val existing = current.days.firstOrNull { it.date == date }
            if (existing?.state != StatisticsDayState.FINAL) {
                replacements[date] = queryDay(
                    client = client,
                    date = date,
                    today = today,
                    zone = zone,
                    nowMillis = clock.millis(),
                )
            }
            date = date.plusDays(1)
        }
        return store.update { latest ->
            val byDate = latest.days.associateBy(StepStatisticsDay::date).toMutableMap()
            replacements.forEach { (replacementDate, replacement) ->
                if (byDate[replacementDate]?.state != StatisticsDayState.FINAL) {
                    byDate[replacementDate] = replacement
                }
            }
            latest.copy(
                trackingStartedOn = latest.trackingStartedOn ?: firstDate,
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
                aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
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
        const val DETAIL_DEVICE_STEP_COUNTER = "device_step_counter"
        const val DETAIL_DEVICE_SENSOR_PERMISSION = "device_sensor_permission_required"
    }
}

internal fun mergeDeviceStepCounterSample(
    history: StepStatisticsHistory,
    date: LocalDate,
    zoneId: String,
    capturedAtEpochMillis: Long,
    cumulativeSteps: Long,
): StepStatisticsHistory {
    require(cumulativeSteps >= 0)
    require(capturedAtEpochMillis >= 0)
    val previous = history.deviceSensorBaseline
    val byDate = history.days.associateBy(StepStatisticsDay::date).toMutableMap()
    if (previous != null && previous.date.isBefore(date)) {
        byDate[previous.date]?.takeIf { it.state == StatisticsDayState.OPEN }?.let { old ->
            byDate[previous.date] = old.copy(state = StatisticsDayState.FINAL)
        }
    }
    val existing = byDate[date]
    val delta = if (
        previous != null &&
        previous.date == date &&
        cumulativeSteps >= previous.cumulativeSteps
    ) {
        cumulativeSteps - previous.cumulativeSteps
    } else {
        null
    }
    val updatedSteps = when {
        delta == null -> existing?.steps
        existing?.steps == null && delta == 0L -> null
        else -> ((existing?.steps ?: 0L) + delta).coerceAtMost(MAX_SENSOR_STEPS_PER_DAY)
    }
    byDate[date] = StepStatisticsDay(
        date = date,
        zoneId = zoneId,
        state = StatisticsDayState.OPEN,
        collectedAtEpochMillis = capturedAtEpochMillis,
        steps = updatedSteps,
        distanceMeters = existing?.distanceMeters,
        activeCaloriesKilocalories = existing?.activeCaloriesKilocalories,
    )
    return history.copy(
        trackingStartedOn = history.trackingStartedOn?.let { existingStart ->
            minOf(existingStart, date)
        } ?: date,
        days = byDate.values.sortedBy(StepStatisticsDay::date),
        deviceSensorBaseline = DeviceStepSensorBaseline(
            date = date,
            cumulativeSteps = cumulativeSteps,
            capturedAtEpochMillis = capturedAtEpochMillis,
        ),
    )
}

private fun java.time.Instant.coerceAtMost(maximum: java.time.Instant): java.time.Instant =
    if (isAfter(maximum)) maximum else this

private const val MAX_SENSOR_STEPS_PER_DAY = 1_000_000L
