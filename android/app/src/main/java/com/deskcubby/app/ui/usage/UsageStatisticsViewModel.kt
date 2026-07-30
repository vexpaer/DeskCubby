package com.deskcubby.app.ui.usage

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.statistics.StatisticsChartType
import com.deskcubby.app.data.statistics.StatisticsCollectionState
import com.deskcubby.app.data.statistics.StatisticsOverview
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.statistics.StatisticsRange
import com.deskcubby.app.data.statistics.UsageStatisticsDay
import com.deskcubby.app.data.statistics.UsageStatisticsHistory
import com.deskcubby.app.data.statistics.UsageStatisticsRepository
import com.deskcubby.app.data.statistics.UsageDeviceRecord
import com.deskcubby.app.data.statistics.UsageDeviceRepository
import com.deskcubby.app.data.statistics.UsageDeviceIdentity
import com.deskcubby.app.data.statistics.combineUsageDeviceHistories
import com.deskcubby.app.data.statistics.overview
import com.deskcubby.app.data.statistics.withinStatisticsRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UsageAppChoice(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long,
)

data class UsageDeviceChoice(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val isCurrentDevice: Boolean,
    val recordedDays: Int,
)

data class UsageStatisticsUiState(
    val initializing: Boolean = true,
    val enabled: Boolean = false,
    val history: UsageStatisticsHistory = UsageStatisticsHistory(),
    val devices: List<UsageDeviceChoice> = emptyList(),
    val currentDeviceId: String? = null,
    val currentDeviceName: String = "",
    val selectedDeviceId: String? = null,
    val collection: StatisticsCollectionState = StatisticsCollectionState(),
    val selectedPackage: String? = null,
    val appChoices: List<UsageAppChoice> = emptyList(),
    val range: StatisticsRange = StatisticsRange.LAST_30_DAYS,
    val chartType: StatisticsChartType = StatisticsChartType.BARS,
    val overview: StatisticsOverview = StatisticsOverview(null, 0, 0, 0.0, 0.0),
    val points: List<StatisticsPoint> = emptyList(),
    val rangeTotalForegroundMillis: Long = 0L,
    val highestDayForegroundMillis: Double = 0.0,
    val lastSevenDayAverageForegroundMillis: Double = 0.0,
)

@HiltViewModel
class UsageStatisticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
    private val repository: UsageStatisticsRepository,
    private val deviceRepository: UsageDeviceRepository,
) : ViewModel() {
    private val selectedDeviceId = MutableStateFlow<String?>(null)
    private val selectedPackage = MutableStateFlow<String?>(null)
    private val range = MutableStateFlow(StatisticsRange.LAST_30_DAYS)
    private val chartType = MutableStateFlow(StatisticsChartType.BARS)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val launcherLabels: Map<String, String> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildMap {
            val packageManager = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(
                        launcherIntent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
                }
            }.getOrDefault(emptyList())
            resolved.forEach { activity ->
                val applicationInfo = activity.activityInfo?.applicationInfo ?: return@forEach
                val label = runCatching {
                    packageManager.getApplicationLabel(applicationInfo).toString()
                }.getOrNull()?.takeIf(String::isNotBlank)
                if (label != null) put(applicationInfo.packageName, label)
            }
            runCatching {
                launcherApps.getActivityList(null, Process.myUserHandle())
            }.getOrDefault(emptyList()).forEach { activity ->
                val packageName = activity.applicationInfo.packageName
                if (packageName !in this) {
                    activity.label?.toString()?.takeIf(String::isNotBlank)?.let { label ->
                        put(packageName, label)
                    }
                }
            }
        }
    }
    private val labelCache = ConcurrentHashMap<String, String>()
    private val enabled = settingsRepository.settings
        .map { it.usageTrackingEnabled }
        .distinctUntilChanged()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<UsageStatisticsUiState> = combine(
        enabled,
        deviceRepository.records,
        deviceRepository.identity,
        repository.collectionState,
        selectedDeviceId,
        selectedPackage,
        range,
        chartType,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val records = values[1] as List<UsageDeviceRecord>
        val identity = values[2] as UsageDeviceIdentity
        val requestedDeviceId = values[4] as String?
        val selectedRecord = requestedDeviceId?.let { id ->
            records.firstOrNull { it.deviceId == id }
        }
        UsageStatisticsInputs(
            enabled = values[0] as Boolean,
            history = selectedRecord?.history ?: combineUsageDeviceHistories(records),
            devices = records,
            currentDeviceId = identity.deviceId,
            currentDeviceName = identity.deviceName,
            selectedDeviceId = selectedRecord?.deviceId,
            collection = values[3] as StatisticsCollectionState,
            selectedPackage = values[5] as String?,
            range = values[6] as StatisticsRange,
            chartType = values[7] as StatisticsChartType,
        )
    }.mapLatest { inputs ->
        val today = LocalDate.now()
        val derived = withContext(Dispatchers.Default) {
            deriveUsageStatisticsState(
                inputs = inputs,
                today = today,
            )
        }
        val appChoices = withContext(Dispatchers.IO) {
            derived.rankedPackages.map { total ->
                UsageAppChoice(
                    packageName = total.packageName,
                    label = resolveAppLabel(total.packageName),
                    foregroundMillis = total.foregroundMillis,
                )
            }
        }
        UsageStatisticsUiState(
            initializing = false,
            enabled = inputs.enabled,
            history = inputs.history,
            devices = inputs.devices.map { record ->
                UsageDeviceChoice(
                    deviceId = record.deviceId,
                    deviceName = record.deviceName,
                    platform = record.platform,
                    isCurrentDevice = record.deviceId == inputs.currentDeviceId,
                    recordedDays = record.history.days.size,
                )
            },
            currentDeviceId = inputs.currentDeviceId,
            currentDeviceName = inputs.currentDeviceName,
            selectedDeviceId = inputs.selectedDeviceId,
            collection = inputs.collection,
            selectedPackage = inputs.selectedPackage,
            appChoices = appChoices,
            range = inputs.range,
            chartType = inputs.chartType,
            overview = derived.overview,
            rangeTotalForegroundMillis = derived.rangeTotalForegroundMillis,
            highestDayForegroundMillis = derived.highestDayForegroundMillis,
            lastSevenDayAverageForegroundMillis =
                derived.lastSevenDayAverageForegroundMillis,
            points = derived.points,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UsageStatisticsUiState(),
    )

    init {
        viewModelScope.launch {
            enabled.collect { isEnabled ->
                if (isEnabled) repository.refresh() else repository.markDisabled()
            }
        }
    }

    fun refresh() {
        if (!uiState.value.enabled) return
        viewModelScope.launch { repository.refresh() }
    }

    fun selectDevice(deviceId: String?) {
        selectedDeviceId.value = deviceId
        selectedPackage.value = null
    }

    fun renameCurrentDevice(name: String) {
        viewModelScope.launch {
            try {
                deviceRepository.renameCurrentDevice(name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The dialog validates user input; an I/O failure leaves the previous name intact.
            }
        }
    }

    fun selectPackage(packageName: String?) {
        selectedPackage.value = packageName
    }

    fun selectRange(value: StatisticsRange) {
        range.value = value
    }

    fun selectChartType(value: StatisticsChartType) {
        chartType.value = value
    }

    private fun resolveAppLabel(packageName: String): String {
        labelCache[packageName]?.let { return it }
        val launcherLabel = launcherLabels[packageName]
        val applicationLabel = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        val label = (applicationLabel ?: launcherLabel)
            ?.takeIf { it.isNotBlank() && it != packageName }
            ?: fallbackUsageAppLabel(packageName)
        labelCache[packageName] = label
        return label
    }
}

private data class UsageStatisticsInputs(
    val enabled: Boolean,
    val history: UsageStatisticsHistory,
    val devices: List<UsageDeviceRecord>,
    val currentDeviceId: String,
    val currentDeviceName: String,
    val selectedDeviceId: String?,
    val collection: StatisticsCollectionState,
    val selectedPackage: String?,
    val range: StatisticsRange,
    val chartType: StatisticsChartType,
)

private data class DerivedUsageStatisticsState(
    val rankedPackages: List<UsagePackageTotal>,
    val overview: StatisticsOverview,
    val rangeTotalForegroundMillis: Long,
    val highestDayForegroundMillis: Double,
    val lastSevenDayAverageForegroundMillis: Double,
    val points: List<StatisticsPoint>,
)

private fun deriveUsageStatisticsState(
    inputs: UsageStatisticsInputs,
    today: LocalDate,
): DerivedUsageStatisticsState {
    val days = inputs.history.days.withinStatisticsRange(
        range = inputs.range,
        today = today,
        dateOf = UsageStatisticsDay::date,
    )
    val points = days.map { day ->
        StatisticsPoint(
            date = day.date,
            value = day.valueForPackage(inputs.selectedPackage),
        )
    }
    val lastSevenDays = inputs.history.days.withinStatisticsRange(
        range = StatisticsRange.LAST_7_DAYS,
        today = today,
        dateOf = UsageStatisticsDay::date,
    )
    val lastSevenValues = lastSevenDays.map { day ->
        day.valueForPackage(inputs.selectedPackage)
    }
    return DerivedUsageStatisticsState(
        rankedPackages = rankUsagePackages(
            allDays = inputs.history.days,
            rangedDays = days,
            selectedPackage = inputs.selectedPackage,
        ),
        overview = inputs.history.overview(
            range = inputs.range,
            today = today,
            packageName = inputs.selectedPackage,
        ),
        rangeTotalForegroundMillis = days.fold(0L) { total, day ->
            val value = day.totalForegroundMillis
            if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
        },
        highestDayForegroundMillis = points.mapNotNull(StatisticsPoint::value)
            .maxOrNull()
            ?: 0.0,
        lastSevenDayAverageForegroundMillis = if (lastSevenValues.isEmpty()) {
            0.0
        } else {
            lastSevenValues.average()
        },
        points = points,
    )
}

private fun UsageStatisticsDay.valueForPackage(packageName: String?): Double =
    if (packageName == null) {
        totalForegroundMillis.toDouble()
    } else {
        apps.firstOrNull { it.packageName == packageName }
            ?.foregroundMillis
            ?.toDouble()
            ?: 0.0
    }

internal data class UsagePackageTotal(
    val packageName: String,
    val foregroundMillis: Long,
)

internal fun rankUsagePackages(
    allDays: List<UsageStatisticsDay>,
    rangedDays: List<UsageStatisticsDay>,
    selectedPackage: String?,
    maximumChoices: Int = MAX_USAGE_APP_CHOICES,
): List<UsagePackageTotal> {
    require(maximumChoices > 0)
    val selectedPackageIsRecorded = selectedPackage != null && allDays.any { day ->
        day.apps.any { it.packageName == selectedPackage }
    }
    val rangedTotals = mutableMapOf<String, Long>()
    rangedDays.forEach { day ->
        day.apps.forEach { app ->
            val previous = rangedTotals[app.packageName] ?: 0L
            rangedTotals[app.packageName] = if (Long.MAX_VALUE - previous < app.foregroundMillis) {
                Long.MAX_VALUE
            } else {
                previous + app.foregroundMillis
            }
        }
    }
    val ranked = rangedTotals.asSequence()
        .filter { (_, foregroundMillis) -> foregroundMillis > 0L }
        .map { (packageName, foregroundMillis) ->
            UsagePackageTotal(packageName, foregroundMillis)
        }
        .sortedWith(
            compareByDescending(UsagePackageTotal::foregroundMillis)
                .thenBy(UsagePackageTotal::packageName),
        )
        .toMutableList()
    if (
        selectedPackage != null &&
        selectedPackageIsRecorded &&
        ranked.none { it.packageName == selectedPackage }
    ) {
        ranked += UsagePackageTotal(
            packageName = selectedPackage,
            foregroundMillis = rangedTotals[selectedPackage] ?: 0L,
        )
        ranked.sortWith(
            compareByDescending(UsagePackageTotal::foregroundMillis)
                .thenBy(UsagePackageTotal::packageName),
        )
    }
    if (ranked.size <= maximumChoices) return ranked
    val limited = ranked.take(maximumChoices).toMutableList()
    if (selectedPackage != null && limited.none { it.packageName == selectedPackage }) {
        ranked.firstOrNull { it.packageName == selectedPackage }?.let { selected ->
            limited[limited.lastIndex] = selected
            limited.sortWith(
                compareByDescending(UsagePackageTotal::foregroundMillis)
                    .thenBy(UsagePackageTotal::packageName),
            )
        }
    }
    return limited
}

internal fun fallbackUsageAppLabel(packageName: String): String {
    val parts = packageName.split('.')
        .filter(String::isNotBlank)
    val usefulParts = if (parts.lastOrNull()?.length.orZero() <= 2 && parts.size >= 2) {
        parts.takeLast(2)
    } else {
        parts.takeLast(1)
    }
    return usefulParts.joinToString(" ") { part ->
        part.replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter(String::isNotBlank)
            .joinToString(" ") { word ->
                word.replaceFirstChar { character ->
                    if (character.isLowerCase()) character.titlecase() else character.toString()
                }
            }
    }.takeIf(String::isNotBlank) ?: "App"
}

private fun Int?.orZero(): Int = this ?: 0

private const val MAX_USAGE_APP_CHOICES = 512
