package com.deskcubby.app.ui.steps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.statistics.StatisticsChartType
import com.deskcubby.app.data.statistics.StatisticsCollectionState
import com.deskcubby.app.data.statistics.StatisticsOverview
import com.deskcubby.app.data.statistics.StatisticsPoint
import com.deskcubby.app.data.statistics.StatisticsRange
import com.deskcubby.app.data.statistics.StepHealthConnectAction
import com.deskcubby.app.data.statistics.StepStatisticsHistory
import com.deskcubby.app.data.statistics.StepStatisticsRepository
import com.deskcubby.app.data.statistics.overview
import com.deskcubby.app.data.statistics.withinStatisticsRange
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class StepStatisticsUiState(
    val enabled: Boolean = false,
    val history: StepStatisticsHistory = StepStatisticsHistory(),
    val collection: StatisticsCollectionState = StatisticsCollectionState(),
    val range: StatisticsRange = StatisticsRange.LAST_30_DAYS,
    val chartType: StatisticsChartType = StatisticsChartType.BARS,
    val overview: StatisticsOverview = StatisticsOverview(null, 0, 0, 0.0, 0.0),
    val points: List<StatisticsPoint> = emptyList(),
    val permissionsToRequest: Set<String> = emptySet(),
    val healthConnectAction: StepHealthConnectAction = StepHealthConnectAction.UNSUPPORTED,
)

@HiltViewModel
class StepStatisticsViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val repository: StepStatisticsRepository,
) : ViewModel() {
    private val range = MutableStateFlow(StatisticsRange.LAST_30_DAYS)
    private val chartType = MutableStateFlow(StatisticsChartType.BARS)
    private val enabled = settingsRepository.settings
        .map { it.stepTrackingEnabled }
        .distinctUntilChanged()

    val uiState: StateFlow<StepStatisticsUiState> = combine(
        enabled,
        repository.history,
        repository.collectionState,
        range,
        chartType,
    ) { isEnabled, history, collection, selectedRange, selectedChart ->
        val today = LocalDate.now()
        val days = history.days.withinStatisticsRange(
            range = selectedRange,
            today = today,
            dateOf = { it.date },
        )
        StepStatisticsUiState(
            enabled = isEnabled,
            history = history,
            collection = collection,
            range = selectedRange,
            chartType = selectedChart,
            overview = history.overview(),
            points = days.map { day ->
                StatisticsPoint(day.date, day.steps?.toDouble())
            },
            permissionsToRequest = runCatching(repository::permissionsToRequest)
                .getOrDefault(emptySet()),
            healthConnectAction = runCatching(repository::healthConnectAction)
                .getOrDefault(StepHealthConnectAction.UNSUPPORTED),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StepStatisticsUiState(),
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

    fun onPermissionResult() {
        refresh()
    }

    fun onHealthConnectOpenFailed() {
        repository.reportHealthConnectOpenFailure()
    }

    fun selectRange(value: StatisticsRange) {
        range.value = value
    }

    fun selectChartType(value: StatisticsChartType) {
        chartType.value = value
    }
}
