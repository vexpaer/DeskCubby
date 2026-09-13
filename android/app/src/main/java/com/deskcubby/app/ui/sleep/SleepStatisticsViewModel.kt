package com.deskcubby.app.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.statistics.SleepDeviceRecord
import com.deskcubby.app.data.statistics.SleepNight
import com.deskcubby.app.data.statistics.SleepStatisticsRepository
import com.deskcubby.app.data.statistics.StatisticsRange
import com.deskcubby.app.data.statistics.UsageDeviceIdentity
import com.deskcubby.app.data.statistics.UsageDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SleepStatisticsUiState(
    val enabled: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val localDeviceId: String? = null,
    val selectedDeviceId: String? = null,
    val devices: List<SleepDeviceRecord> = emptyList(),
    val range: StatisticsRange = StatisticsRange.LAST_30_DAYS,
    val nights: List<SleepNight> = emptyList(),
    val averageDurationMinutes: Int? = null,
    val averageBedtimeMinutes: Int? = null,
    val averageWakeMinutes: Int? = null,
    val recordedNights: Int = 0,
    val refreshing: Boolean = false,
)

@HiltViewModel
class SleepStatisticsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val repository: SleepStatisticsRepository,
    usageDeviceRepository: UsageDeviceRepository,
) : ViewModel() {
    private val selectedDeviceId = MutableStateFlow<String?>(null)
    private val range = MutableStateFlow(StatisticsRange.LAST_30_DAYS)
    private val refreshing = MutableStateFlow(false)

    private val coreState = combine(
        settingsRepository.settings,
        repository.records,
        usageDeviceRepository.identity,
        selectedDeviceId,
        range,
    ) { settings, records, identity, requestedDevice, selectedRange ->
        SleepCoreState(settings.sleepTrackingEnabled, records, identity, requestedDevice, selectedRange)
    }

    val uiState = combine(coreState, refreshing) { core, isRefreshing ->
        deriveState(
            enabled = core.enabled,
            hasUsageAccess = repository.hasUsageAccess(),
            records = core.records,
            identity = core.identity,
            requestedDeviceId = core.requestedDeviceId,
            range = core.range,
            refreshing = isRefreshing,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SleepStatisticsUiState(),
    )

    init {
        viewModelScope.launch {
            if (settingsRepository.settings.first().sleepTrackingEnabled) refresh()
        }
    }

    fun selectDevice(deviceId: String) {
        selectedDeviceId.value = deviceId
    }

    fun setRange(value: StatisticsRange) {
        range.value = value
    }

    fun setTrackingEnabled(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSleepTrackingEnabled(value)
            if (value) refresh()
        }
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                repository.refreshRecent(days = 30)
            } finally {
                refreshing.value = false
            }
        }
    }

    fun saveManual(wakeDate: LocalDate, bedtime: LocalTime, wakeTime: LocalTime) {
        viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val bedtimeDate = if (bedtime < wakeTime) wakeDate else wakeDate.minusDays(1)
            val bedtimeMillis = bedtimeDate.atTime(bedtime).atZone(zone).toInstant().toEpochMilli()
            val wakeMillis = wakeDate.atTime(wakeTime).atZone(zone).toInstant().toEpochMilli()
            repository.saveManual(
                wakeDate = wakeDate,
                bedtimeEpochMillis = bedtimeMillis,
                wakeEpochMillis = wakeMillis,
                zoneId = zone,
            )
        }
    }
}

private fun deriveState(
    enabled: Boolean,
    hasUsageAccess: Boolean,
    records: List<SleepDeviceRecord>,
    identity: UsageDeviceIdentity,
    requestedDeviceId: String?,
    range: StatisticsRange,
    refreshing: Boolean,
): SleepStatisticsUiState {
    val selectedId = requestedDeviceId
        ?.takeIf { id -> records.any { it.deviceId == id } }
        ?: identity.deviceId
    val selected = records.firstOrNull { it.deviceId == selectedId }
    val today = LocalDate.now()
    val firstDate = range.days?.let { today.minusDays(it - 1L) }
    val nights = selected?.nights.orEmpty()
        .filter { !it.wakeDate.isAfter(today) && (firstDate == null || !it.wakeDate.isBefore(firstDate)) }
        .sortedBy(SleepNight::wakeDate)

    fun clockMinutes(epochMillis: Long, zoneId: String): Int {
        val time = java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault()))
            .toLocalTime()
        return time.hour * 60 + time.minute
    }

    val bedtimeValues = nights.map { night ->
        val raw = clockMinutes(night.bedtimeEpochMillis, night.zoneId)
        if (raw < 12 * 60) raw + 24 * 60 else raw
    }
    val wakeValues = nights.map { clockMinutes(it.wakeEpochMillis, it.zoneId) }

    return SleepStatisticsUiState(
        enabled = enabled,
        hasUsageAccess = hasUsageAccess,
        localDeviceId = identity.deviceId,
        selectedDeviceId = selectedId,
        devices = records,
        range = range,
        nights = nights,
        averageDurationMinutes = nights.takeIf { it.isNotEmpty() }
            ?.map(SleepNight::durationMinutes)?.average()?.toInt(),
        averageBedtimeMinutes = bedtimeValues.takeIf { it.isNotEmpty() }?.average()?.toInt()?.mod(24 * 60),
        averageWakeMinutes = wakeValues.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        recordedNights = nights.size,
        refreshing = refreshing,
    )
}


private data class SleepCoreState(
    val enabled: Boolean,
    val records: List<SleepDeviceRecord>,
    val identity: UsageDeviceIdentity,
    val requestedDeviceId: String?,
    val range: StatisticsRange,
)
