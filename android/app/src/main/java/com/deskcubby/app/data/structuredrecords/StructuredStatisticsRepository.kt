package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.local.StructuredRecordDao
import com.deskcubby.app.data.local.StructuredRecordOccurrenceEntity
import com.deskcubby.app.data.model.AppSettings
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One plotted value for a field or metric on a natural calendar date. */
data class StructuredSeriesPoint(
    val journalDay: LocalDate,
    /** Numeric value for charts. TIME uses ordinary minutes since midnight (0..1439). */
    val chartValue: Double?,
    val display: String?,
    val rawValue: String? = null,
)

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

/** Computes statistics from the local index using natural Markdown file dates only. */
@Singleton
class StructuredStatisticsRepository @Inject constructor(
    private val structuredRecordDao: StructuredRecordDao,
    private val workspaceRepository: StructuredWorkspaceRepository,
) {
    suspend fun autoFieldStats(
        settings: AppSettings,
        field: StructuredField,
        startIso: String,
        endIso: String,
        selector: FieldSelector = FieldSelector.LAST,
    ): StructuredFieldAutoStats {
        @Suppress("UNUSED_VARIABLE")
        val ignoredSettings = settings
        val occurrences = structuredRecordDao.occurrencesForField(field.id, startIso, endIso)
        val byDay = occurrences.groupBy { it.journalDay }
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
                    total = if (selector == FieldSelector.SUM) {
                        series.mapNotNull { it.chartValue }.sumOrNull()?.let(StructuredFieldNormalizer::formatNumber)
                    } else null,
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
                        series.mapNotNull { it.chartValue }.sumOrNull()
                            ?.let { StructuredFieldNormalizer.formatDuration(it.toLong()) }
                    } else null,
                    average = series.mapNotNull { it.chartValue }.averageOrNull()
                        ?.let { StructuredFieldNormalizer.formatDuration(it.toLong()) },
                )
            }
            StructuredFieldType.TIME -> {
                val series = points.map { (day, value) ->
                    val time = (value as? NormalizedFieldValue.Time)?.time
                    StructuredSeriesPoint(
                        day,
                        time?.let { it.toSecondOfDay() / 60.0 },
                        time?.let(JournalDayEngine::formatTime),
                    )
                }
                val values = points.mapNotNull { (_, value) -> (value as? NormalizedFieldValue.Time)?.time }
                StructuredFieldAutoStats(
                    fieldId = field.id,
                    count = occurrences.size,
                    series = series,
                    latest = latest?.rawValue,
                    earliest = values.minOrNull()?.let(JournalDayEngine::formatTime),
                    average = circularAverageTime(values),
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
                val series = occurrences.mapNotNull { occurrence ->
                    val day = runCatching { LocalDate.parse(occurrence.journalDay) }.getOrNull()
                        ?: return@mapNotNull null
                    StructuredSeriesPoint(day, null, occurrence.rawValue, occurrence.rawValue)
                }.sortedBy { it.journalDay }
                StructuredFieldAutoStats(
                    fieldId = field.id,
                    count = occurrences.size,
                    series = series,
                    latest = latest?.rawValue,
                )
            }
        }
    }

    suspend fun metricSeries(
        settings: AppSettings,
        metric: StructuredMetric,
        startIso: String,
        endIso: String,
        /** Optional page-owned field snapshot to avoid one SAF fields.json read per metric card. */
        knownFieldsById: Map<String, StructuredField>? = null,
    ): List<StructuredSeriesPoint> {
        val fields = knownFieldsById ?: workspaceRepository.loadFields(settings).associateBy { it.id }
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

        val days = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .toList()
        return days.map { day ->
            when (val result = MetricEvaluator.evaluate(metric.expression, day, provider)) {
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

    private fun circularAverageTime(times: List<LocalTime>): String? {
        if (times.isEmpty()) return null
        val angles = times.map { time -> time.toSecondOfDay() / 86400.0 * 2.0 * PI }
        val meanSin = angles.sumOf(::sin) / angles.size
        val meanCos = angles.sumOf(::cos) / angles.size
        var angle = atan2(meanSin, meanCos)
        if (angle < 0) angle += 2.0 * PI
        val minuteOfDay = (angle / (2.0 * PI) * 1440.0).roundToInt() % 1440
        return "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
    }

    private fun averageOf(values: List<Double>): String? =
        values.averageOrNull()?.let(StructuredFieldNormalizer::formatNumber)

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else sum() / size
    private fun List<Double>.sumOrNull(): Double? = if (isEmpty()) null else sum()
}
