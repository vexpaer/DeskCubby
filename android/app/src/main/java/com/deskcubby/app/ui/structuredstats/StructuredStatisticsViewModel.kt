package com.deskcubby.app.ui.structuredstats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.app.data.structuredrecords.FieldSelector
import com.deskcubby.app.data.structuredrecords.StructuredField
import com.deskcubby.app.data.structuredrecords.StructuredFieldAutoStats
import com.deskcubby.app.data.structuredrecords.StructuredMetric
import com.deskcubby.app.data.structuredrecords.StructuredSeriesPoint
import com.deskcubby.app.data.structuredrecords.StructuredStatisticsRepository
import com.deskcubby.app.data.structuredrecords.StructuredWorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StructuredFieldCard(
    val field: StructuredField,
    val stats: StructuredFieldAutoStats,
)

data class StructuredMetricCard(
    val metric: StructuredMetric,
    val points: List<StructuredSeriesPoint>,
)

data class StructuredStatisticsUiState(
    val loading: Boolean = true,
    val available: Boolean = false,
    val startIso: String = LocalDate.now().minusDays(89).toString(),
    val endIso: String = LocalDate.now().toString(),
    val fields: List<StructuredField> = emptyList(),
    val metrics: List<StructuredMetric> = emptyList(),
    val fieldCards: List<StructuredFieldCard> = emptyList(),
    val metricCards: List<StructuredMetricCard> = emptyList(),
    val message: String? = null,
) {
    val hasAny: Boolean get() = fieldCards.isNotEmpty() || metricCards.isNotEmpty()
}

@HiltViewModel
class StructuredStatisticsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val workspaceRepository: StructuredWorkspaceRepository,
    private val statisticsRepository: StructuredStatisticsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    private val mutableState = MutableStateFlow(StructuredStatisticsUiState())
    val uiState: StateFlow<StructuredStatisticsUiState> = mutableState.asStateFlow()
    private val reloadMutex = Mutex()

    init {
        viewModelScope.launch {
            settings.map { it.diaryTreeUri }.distinctUntilChanged().collect { uri ->
                if (uri == null) {
                    mutableState.value = StructuredStatisticsUiState(
                        loading = false,
                        available = false,
                        message = "请先在设置中选择日记目录",
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        loading = true,
                        available = false,
                        fields = emptyList(),
                        metrics = emptyList(),
                        fieldCards = emptyList(),
                        metricCards = emptyList(),
                        message = null,
                    )
                    reload(stores = settings.value)
                }
            }
        }
        viewModelScope.launch {
            // workspaceChanges is a StateFlow. Its initial replay is not a change and the settings
            // collector above already performs the first load. Previously both reloads were queued
            // behind reloadMutex, effectively doubling the statistics page's startup latency.
            workspaceRepository.workspaceChanges.drop(1).collect {
                val appSettings = settings.value
                if (appSettings.diaryTreeUri != null) reload(stores = appSettings)
            }
        }
    }

    fun reload() {
        viewModelScope.launch { reload(settings.value) }
    }

    fun setRange(startIso: String, endIso: String) {
        viewModelScope.launch {
            val loaded = mutableState.value
            mutableState.value = loaded.copy(
                startIso = startIso,
                endIso = endIso,
            )
            // Changing 7/30/90-day range only needs Room data. Reuse the already loaded workspace
            // instead of traversing SAF and decoding fields.json/statistics.json again.
            computeCards(
                settings.value,
                startIso,
                endIso,
                workspaceFields = loaded.fields,
                workspaceMetrics = loaded.metrics,
            )
        }
    }

    /** Persists a new or updated derived metric. workspaceChanges performs the single refresh. */
    fun saveMetric(metric: StructuredMetric) {
        viewModelScope.launch {
            val appSettings = settings.value
            if (appSettings.diaryTreeUri == null) return@launch
            workspaceRepository.mutateMetrics(appSettings) { existing ->
                val normalized = if (existing.any { it.id == metric.id }) {
                    metric
                } else {
                    metric.copy(sortOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1)
                }
                (existing.filterNot { it.id == metric.id } + normalized).sortedBy { it.sortOrder }
            }
        }
    }

    fun deleteMetric(id: String) {
        viewModelScope.launch {
            val appSettings = settings.value
            if (appSettings.diaryTreeUri == null) return@launch
            workspaceRepository.mutateMetrics(appSettings) { existing -> existing.filterNot { it.id == id } }
        }
    }

    /** Creates a generic `timeDiff` derived metric: end(field,offset) - start(field,offset). */
    fun createTimeDiffMetric(
        name: String,
        endFieldId: String,
        endOffset: Int,
        startFieldId: String,
        startOffset: Int,
    ) {
        viewModelScope.launch {
            val appSettings = settings.value
            val metrics = workspaceRepository.loadMetrics(appSettings)
            val metric = StructuredMetric(
                id = "m_" + java.util.UUID.randomUUID().toString().take(8),
                name = name.take(60).ifBlank { "时间差" },
                resultType = com.deskcubby.app.data.structuredrecords.MetricResultType.DURATION,
                expression = com.deskcubby.app.data.structuredrecords.MetricExpression.TimeDiff(
                    end = com.deskcubby.app.data.structuredrecords.MetricExpression.FieldRef(
                        com.deskcubby.app.data.structuredrecords.FieldRefNode(
                            fieldId = endFieldId,
                            dayOffset = endOffset,
                            selector = com.deskcubby.app.data.structuredrecords.FieldSelector.LAST,
                        ),
                    ),
                    start = com.deskcubby.app.data.structuredrecords.MetricExpression.FieldRef(
                        com.deskcubby.app.data.structuredrecords.FieldRefNode(
                            fieldId = startFieldId,
                            dayOffset = startOffset,
                            selector = com.deskcubby.app.data.structuredrecords.FieldSelector.LAST,
                        ),
                    ),
                ),
                sortOrder = metrics.size,
            )
            saveMetric(metric)
        }
    }

    fun createSleepDurationMetric() {
        viewModelScope.launch {
            val appSettings = settings.value
            val fields = workspaceRepository.loadFields(appSettings)
            val wake = fields.firstOrNull { it.id == com.deskcubby.app.data.structuredrecords.SYSTEM_FIELD_WAKE_TIME }
            val sleep = fields.firstOrNull { it.id == com.deskcubby.app.data.structuredrecords.SYSTEM_FIELD_SLEEP_TIME }
            if (wake == null || sleep == null) return@launch
            val metrics = workspaceRepository.loadMetrics(appSettings)
            if (metrics.any { it.id == "m_sleep_duration" }) return@launch
            val metric = StructuredMetric(
                id = "m_sleep_duration",
                name = "睡眠时长",
                resultType = com.deskcubby.app.data.structuredrecords.MetricResultType.DURATION,
                expression = com.deskcubby.app.data.structuredrecords.MetricExpression.TimeDiff(
                    end = com.deskcubby.app.data.structuredrecords.MetricExpression.FieldRef(
                        com.deskcubby.app.data.structuredrecords.FieldRefNode(
                            fieldId = wake.id,
                            dayOffset = 0,
                            selector = com.deskcubby.app.data.structuredrecords.FieldSelector.LAST,
                        ),
                    ),
                    start = com.deskcubby.app.data.structuredrecords.MetricExpression.FieldRef(
                        com.deskcubby.app.data.structuredrecords.FieldRefNode(
                            fieldId = sleep.id,
                            dayOffset = -1,
                            selector = com.deskcubby.app.data.structuredrecords.FieldSelector.LAST,
                        ),
                    ),
                ),
                sortOrder = metrics.size,
            )
            saveMetric(metric)
        }
    }

    private suspend fun reload(stores: AppSettings) {
        reloadMutex.withLock {
            if (stores.diaryTreeUri == null) {
                mutableState.value = StructuredStatisticsUiState(loading = false, available = false)
                return@withLock
            }
            val state = mutableState.value
            try {
                // Statistics is a read surface. It must not initialize or rewrite workspace files on
                // every visit; those write-capable operations belong to structured-record settings.
                computeCards(stores, state.startIso, state.endIso)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (settings.value.diaryTreeUri == stores.diaryTreeUri) {
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        available = false,
                        fields = emptyList(),
                        metrics = emptyList(),
                        fieldCards = emptyList(),
                        metricCards = emptyList(),
                        message = if (stores.appLanguage == AppLanguage.ENGLISH) {
                            "Structured statistics are temporarily unavailable"
                        } else {
                            "结构化统计暂时不可用"
                        },
                    )
                }
            }
        }
    }

    private suspend fun computeCards(
        stores: AppSettings,
        startIso: String,
        endIso: String,
        workspaceFields: List<StructuredField>? = null,
        workspaceMetrics: List<StructuredMetric>? = null,
    ) {
        val state = mutableState.value.copy(loading = true, message = null)
        mutableState.value = state
        val fields = workspaceFields ?: workspaceRepository.loadFieldsReadOnly(stores)
        val metrics = workspaceMetrics ?: workspaceRepository.loadMetrics(stores)
        val activeFields = fields.filterNot { it.archived }
        val fieldsById = fields.associateBy { it.id }
        val fieldCards = activeFields.mapNotNull { field ->
            val stats = try {
                statisticsRepository.autoFieldStats(stores, field, startIso, endIso)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            if (stats.count <= 0) null else StructuredFieldCard(field, stats)
        }
        val metricCards = metrics.filterNot { it.archived }.mapNotNull { metric ->
            val points = try {
                statisticsRepository.metricSeries(
                    stores,
                    metric,
                    startIso,
                    endIso,
                    knownFieldsById = fieldsById,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@mapNotNull null
            if (points.none { it.display != null }) null else StructuredMetricCard(metric, points)
        }
        mutableState.value = StructuredStatisticsUiState(
            loading = false,
            available = true,
            startIso = startIso,
            endIso = endIso,
            fields = fields,
            metrics = metrics,
            fieldCards = fieldCards,
            metricCards = metricCards,
        )
    }
}
