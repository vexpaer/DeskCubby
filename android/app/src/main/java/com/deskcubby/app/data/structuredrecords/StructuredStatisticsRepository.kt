package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.local.StructuredRecordDao
import com.deskcubby.app.data.local.StructuredRecordOccurrenceEntity
import com.deskcubby.app.data.model.AppSettings
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** One plotted value for a field or metric on a Journal Day. */
data class StructuredSeriesPoint(
    val journalDay: LocalDate,
    /** Numeric value for charts, unwrapped for time-of-day where applicable. */
    val chartValue: Double?,
    /** Human-readable display, e.g. `HH:mm` for times or a formatted duration. */
    val display: String?,
    val rawValue: String? = null,
)

/** A category histogram for [StructuredFieldType.TYPE] fields. */
data class StructuredCategoryCount(
    val category: String,
    val count: Int,
)

data class StructuredFieldAutoStats(
    val fieldId: String,
    val count: Int,
    val series: List<StructuredSeriesPoint> = emptyList(),
    val categoryCounts: List<StructuredCategoryCount> = emptyList(),
    val latest: String? = null,
    val earliest: String? = null,
    val average: String? = null,
    val total: String? = null,
)

/**
 * Computes statistics from the local structured-records index. Never scans Markdown during normal
 * use; all queries read [StructuredRecordDao].
 */
@Singleton
class StructuredStatisticsRepository @Inject constructor(
    private val structuredRecordDao: StructuredRecordDao,
    private val workspaceRepository: StructuredWorkspaceRepository,
) {
    /**
     * Automatic per-field statistics driven by the field type. Selector defaults to "last" for a
     * daily series; callers may override for number/duration aggregation (sum/average/min/max).
     */
    suspend fun autoFieldStats(
        settings: AppSettings,
        field: StructuredField,
        startIso: String,
        endIso: String,
        selector: FieldSelector = FieldSelector.LAST,
    ): StructuredFieldAutoStats {
        val occurrences = structuredRecordDao.occurrencesForField(field.id, startIso, endIso)
        val byDay = occurrences.groupBy { it.journalDay }
        val boundaryMinutes = JournalDayEngine.parseBoundary(
            workspaceRepository.loadSettings(settings).effectiveDayBoundary(startDateOf(occurrences)),
        )
        val points = byDay.mapNotNull { (dayIso, dayOccurrences) ->
            val day = runCatching { LocalDate.parse(dayIso) }.getOrNull() ?: return@mapNotNull null
            val normalized = dayOccurrences.mapNotNull { occurrence ->
                StructuredFieldNormalizer.normalize(field.type, occurrence.rawValue).value
            }
            if (normalized.isEmpty()) return@mapNotNull null
            val selected = MetricEvaluator.applySelector(normalized, selector) ?: return@mapNotNull null
            day to selected
        }.sortedBy { it.first }

        val latest = occurrences.maxByOrNull { it.orderInFile }
        return when (field.type) {
            StructuredFieldType.NUMBER -> {
                val series = points.map { (day, value) ->
                    val number = (value as? NormalizedFieldValue.Number)?.value
                    StructuredSeriesPoint(day, number, number?.let(StructuredFieldNormalizer::formatNumber))
                }
                StructuredFieldAutoStats(
                    fieldId = field.id,
                    count = occurrences.size,
                    series = series,
                    latest = latest?.rawValue,
                    average = averageOf(series.mapNotNull { it.chartValue }),
                    total = if (selector == FieldSelector.SUM) series.mapNotNull { it.chartValue }.sumOrNull()?.let(StructuredFieldNormalizer::formatNumber) else null,
                )
            }
            StructuredFieldType.DURATION -> {
                val series = points.map { (day, value) ->
                    val seconds = (value as? NormalizedFieldValue.Duration)?.seconds?.toDouble()
                    StructuredSeriesPoint(
                        day,
                        seconds,
                        seconds?.let { StructuredFieldNormalizer.formatDuration(it.toLong()) },
                    )
                }
                StructuredFieldAutoStats(
                    fieldId = field.id,
                    count = occurrences.size,
                    series = series,
                    latest = latest?.rawValue,
                    total = if (selector == FieldSelector.SUM) {
                        series.mapNotNull { it.chartValue }.sumOrNull()?.let { StructuredFieldNormalizer.formatDuration(it.toLong()) }
                    } else null,
                    average = series.mapNotNull { it.chartValue }.averageOrNull()?.let { StructuredFieldNormalizer.formatDuration(it.toLong()) },
                )
            }
            StructuredFieldType.TIME -> {
                val unwrapped = points.map { (day, value) ->
                    val time = (value as? NormalizedFieldValue.Time)?.time
                    if (time == null) StructuredSeriesPoint(day, null, null)
                    else {
                        val minutes = time.toSecondOfDay() / 60
                        val boundary = boundaryMinutes ?: 5 * 60
                        val chart = if (minutes < boundary) (minutes + 1440).toDouble() else minutes.toDouble()
                        StructuredSeriesPoint(day, chart, JournalDayEngine.formatTime(time))
                    }
                }
                val values = points.mapNotNull { (_, value) -> (value as? NormalizedFieldValue.Time)?.time }
                StructuredFieldAutoStats(
                    fieldId = field.id,
                    count = occurrences.size,
                    series = unwrapped,
                    latest = latest?.rawValue,
                    earliest = values.minOrNull()?.let(JournalDayEngine::formatTime),
                    average = averageTime(values, boundaryMinutes ?: 5 * 60),
                )
            }
            StructuredFieldType.TYPE -> {
                val counts = occurrences.groupingBy { it.rawValue }
                    .eachCount()
                    .entries
                    .map { (category, count) -> StructuredCategoryCount(category, count) }
                    .sortedByDescending { it.count }
                StructuredFieldAutoStats(
                    fieldId = field.id,
                    count = occurrences.size,
                    categoryCounts = counts,
                    latest = latest?.rawValue,
                )
            }
            StructuredFieldType.WORD -> {
                val series = occurrences
                    .mapNotNull { occurrence ->
                        val day = runCatching { LocalDate.parse(occurrence.journalDay) }.getOrNull()
                            ?: return@mapNotNull null
                        StructuredSeriesPoint(day, null, occurrence.rawValue, occurrence.rawValue)
                    }
                    .sortedBy { it.journalDay }
                StructuredFieldAutoStats(
                    fieldId = field.id,
                    count = occurrences.size,
                    series = series,
                    latest = latest?.rawValue,
                )
            }
        }
    }

    /**
     * Evaluates a derived metric across every journal day in [startIso]..[endIso] and returns a
     * chartable series with nulls preserved for missing inputs.
     */
    suspend fun metricSeries(
        settings: AppSettings,
        metric: StructuredMetric,
        startIso: String,
        endIso: String,
    ): List<StructuredSeriesPoint> {
        val fields = workspaceRepository.loadFields(settings).associateBy { it.id }
        val workspace = workspaceRepository.loadSettings(settings)
        val start = runCatching { LocalDate.parse(startIso) }.getOrDefault(LocalDate.now())
        val end = runCatching { LocalDate.parse(endIso) }.getOrDefault(LocalDate.now())
        if (start.isAfter(end)) return emptyList()

        val all = structuredRecordDao.occurrencesInRange(start.minusDays(14).toString(), end.toString())
        val byFieldDay: Map<Pair<String, String>, List<StructuredRecordOccurrenceEntity>> =
            all.groupBy { it.fieldId to it.journalDay }

        val provider = MetricEvaluator.FieldValuesProvider { fieldId, day ->
            byFieldDay[fieldId to day.toString()]
                .orEmpty()
                .mapNotNull { occurrence ->
                    val field = fields[fieldId]
                    if (field == null) null
                    else StructuredFieldNormalizer.normalize(field.type, occurrence.rawValue).value
                }
        }
        val boundaryProvider = MetricEvaluator.BoundaryProvider { day ->
            workspace.effectiveDayBoundary(day)
        }

        val days = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .toList()
        return days.map { day ->
            when (val result = MetricEvaluator.evaluate(metric.expression, day, provider, boundaryProvider)) {
                is MetricEvaluator.EvalResult.Missing -> StructuredSeriesPoint(day, null, null)
                is MetricEvaluator.EvalResult.Num -> StructuredSeriesPoint(
                    day,
                    result.value,
                    StructuredFieldNormalizer.formatNumber(result.value),
                )
                is MetricEvaluator.EvalResult.Dur -> StructuredSeriesPoint(
                    day,
                    result.seconds,
                    StructuredFieldNormalizer.formatDuration(result.seconds.toLong()),
                )
            }
        }
    }

    private fun startDateOf(occurrences: List<StructuredRecordOccurrenceEntity>): LocalDate =
        occurrences.mapNotNull { runCatching { LocalDate.parse(it.journalDay) }.getOrNull() }
            .minOrNull() ?: LocalDate.now()

    private fun averageOf(values: List<Double>): String? = values.averageOrNull()?.let(StructuredFieldNormalizer::formatNumber)

    private fun averageTime(times: List<java.time.LocalTime>, boundaryMinutes: Int): String? {
        if (times.isEmpty()) return null
        // Average on the unwrapped axis so a 23:40 + 00:20 pair averages to 00:00, not 12:00.
        val unwrapped = times.map { time ->
            val minutes = time.toSecondOfDay() / 60
            if (minutes < boundaryMinutes) minutes + 1440 else minutes
        }
        val average = unwrapped.sum() / unwrapped.size
        val normalized = ((average % 1440) + 1440) % 1440
        val hour = normalized / 60
        val minute = normalized % 60
        return "%02d:%02d".format(hour, minute)
    }

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else sum() / size

    private fun List<Double>.sumOrNull(): Double? = if (isEmpty()) null else sum()
}
