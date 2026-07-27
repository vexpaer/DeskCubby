package com.deskcubby.app.ui.usage

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.statistics.StatisticsChartType
import com.deskcubby.app.data.statistics.StatisticsCollectionState
import com.deskcubby.app.data.statistics.StatisticsOverview
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.statistics.StatisticsRange
import com.deskcubby.app.data.statistics.UsageStatisticsHistory
import com.deskcubby.app.data.statistics.UsageStatisticsRepository
import com.deskcubby.app.data.statistics.overview
import com.deskcubby.app.data.statistics.withinStatisticsRange
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UsageAppChoice(
    val packageName: String,
    val label: String,
)

data class UsageStatisticsUiState(
    val enabled: Boolean = false,
    val history: UsageStatisticsHistory = UsageStatisticsHistory(),
    val collection: StatisticsCollectionState = StatisticsCollectionState(),
    val selectedPackage: String? = null,
    val appChoices: List<UsageAppChoice> = emptyList(),
    val range: StatisticsRange = StatisticsRange.LAST_30_DAYS,
    val chartType: StatisticsChartType = StatisticsChartType.BARS,
    val overview: StatisticsOverview = StatisticsOverview(null, 0, 0, 0.0, 0.0),
    val points: List<StatisticsPoint> = emptyList(),
)

@HiltViewModel
class UsageStatisticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
    private val repository: UsageStatisticsRepository,
) : ViewModel() {
    private val selectedPackage = MutableStateFlow<String?>(null)
    private val range = MutableStateFlow(StatisticsRange.LAST_30_DAYS)
    private val chartType = MutableStateFlow(StatisticsChartType.BARS)
    private val enabled = settingsRepository.settings
        .map { it.usageTrackingEnabled }
        .distinctUntilChanged()

    val uiState: StateFlow<UsageStatisticsUiState> = combine(
        enabled,
        repository.history,
        repository.collectionState,
        selectedPackage,
        range,
        chartType,
    ) { values ->
        val isEnabled = values[0] as Boolean
        val history = values[1] as UsageStatisticsHistory
        val collection = values[2] as StatisticsCollectionState
        val packageName = values[3] as String?
        val selectedRange = values[4] as StatisticsRange
        val selectedChart = values[5] as StatisticsChartType
        val today = LocalDate.now()
        val days = history.days.withinStatisticsRange(
            range = selectedRange,
            today = today,
            dateOf = { it.date },
        )
        UsageStatisticsUiState(
            enabled = isEnabled,
            history = history,
            collection = collection,
            selectedPackage = packageName,
            appChoices = history.days.asSequence()
                .flatMap { it.apps.asSequence() }
                .map { it.packageName }
                .distinct()
                .map { candidate ->
                    UsageAppChoice(candidate, resolveAppLabel(candidate))
                }
                .sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER, UsageAppChoice::label)
                        .thenBy(UsageAppChoice::packageName),
                )
                .toList(),
            range = selectedRange,
            chartType = selectedChart,
            overview = history.overview(packageName),
            points = days.map { day ->
                StatisticsPoint(
                    date = day.date,
                    value = if (packageName == null) {
                        day.totalForegroundMillis.toDouble()
                    } else {
                        day.apps.firstOrNull { it.packageName == packageName }
                            ?.foregroundMillis
                            ?.toDouble()
                            ?: 0.0
                    },
                )
            },
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
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
                .takeIf(String::isNotBlank)
                ?: packageName
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        } catch (_: SecurityException) {
            packageName
        }
    }
}
