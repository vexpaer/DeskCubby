package com.deskcubby.app.data.structuredrecords

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * The derived-metric evaluator. It walks the structured [MetricExpression] AST and never executes
 * user strings as code. Evaluation is anchored on a Journal Day; every field ref's `dayOffset` is
 * relative to that anchor, and the produced value is charted on the anchor day.
 *
 * Missing inputs propagate as [EvalResult.Missing] (null) — never silently 0 — so a day with no
 * sleep time simply has no sleep-duration point instead of showing a fabricated 0.
 */
object MetricEvaluator {

    /** A field's normalized values for one Journal Day; the same shape the DAO returns. */
    fun interface FieldValuesProvider {
        fun valuesFor(fieldId: String, journalDay: LocalDate): List<NormalizedFieldValue>
    }

    /** Returns the effective `HH:mm` boundary for a Journal Day. */
    fun interface BoundaryProvider {
        fun boundaryFor(journalDay: LocalDate): String
    }

    sealed interface EvalResult {
        data object Missing : EvalResult
        data class Num(val value: Double) : EvalResult
        data class Dur(val seconds: Double) : EvalResult
    }

    /** Evaluates [expression] for the anchor [journalDay]. */
    fun evaluate(
        expression: MetricExpression,
        journalDay: LocalDate,
        fields: FieldValuesProvider,
        boundary: BoundaryProvider,
    ): EvalResult = when (expression) {
        is MetricExpression.Constant -> EvalResult.Num(expression.number)
        is MetricExpression.FieldRef -> resolveFieldRef(expression.ref, journalDay, fields)
        is MetricExpression.Add -> arithmetic('+', expression.left, expression.right, journalDay, fields, boundary)
        is MetricExpression.Subtract -> arithmetic('-', expression.left, expression.right, journalDay, fields, boundary)
        is MetricExpression.Multiply -> arithmetic('*', expression.left, expression.right, journalDay, fields, boundary)
        is MetricExpression.Divide -> arithmetic('/', expression.left, expression.right, journalDay, fields, boundary)
        is MetricExpression.TimeDiff -> timeDiff(expression, journalDay, fields, boundary)
    }

    private fun resolveFieldRef(
        ref: FieldRefNode,
        anchorDay: LocalDate,
        fields: FieldValuesProvider,
    ): EvalResult {
        val targetDay = anchorDay.plusDays(clampOffset(ref.dayOffset))
        val values = fields.valuesFor(ref.fieldId, targetDay)
        if (values.isEmpty()) return EvalResult.Missing
        val selected = applySelector(values, ref.selector) ?: return EvalResult.Missing
        return when (selected) {
            is NormalizedFieldValue.Number -> EvalResult.Num(selected.value)
            is NormalizedFieldValue.Duration -> EvalResult.Dur(selected.seconds.toDouble())
            is NormalizedFieldValue.Time -> EvalResult.Num(selected.time.toSecondOfDay().toDouble())
            else -> EvalResult.Missing
        }
    }

    private fun arithmetic(
        op: Char,
        left: MetricExpression,
        right: MetricExpression,
        journalDay: LocalDate,
        fields: FieldValuesProvider,
        boundary: BoundaryProvider,
    ): EvalResult {
        val a = evaluate(left, journalDay, fields, boundary)
        val b = evaluate(right, journalDay, fields, boundary)
        if (a is EvalResult.Missing || b is EvalResult.Missing) return EvalResult.Missing

        data class PairKinds(val x: EvalResult, val y: EvalResult)
        return when (op) {
            '+', '-' -> when {
                a is EvalResult.Num && b is EvalResult.Num -> {
                    val value = if (op == '+') a.value + b.value else a.value - b.value
                    EvalResult.Num(value)
                }
                a is EvalResult.Dur && b is EvalResult.Dur -> {
                    val value = if (op == '+') a.seconds + b.seconds else a.seconds - b.seconds
                    EvalResult.Dur(value)
                }
                else -> EvalResult.Missing
            }
            '*' -> when {
                a is EvalResult.Num && b is EvalResult.Num -> EvalResult.Num(a.value * b.value)
                a is EvalResult.Num && b is EvalResult.Dur -> EvalResult.Dur(a.value * b.seconds)
                a is EvalResult.Dur && b is EvalResult.Num -> EvalResult.Dur(a.seconds * b.value)
                else -> EvalResult.Missing
            }
            '/' -> when {
                a is EvalResult.Num && b is EvalResult.Num && b.value != 0.0 -> EvalResult.Num(a.value / b.value)
                a is EvalResult.Dur && b is EvalResult.Num && b.value != 0.0 -> EvalResult.Dur(a.seconds / b.value)
                else -> EvalResult.Missing
            }
            else -> EvalResult.Missing
        }
    }

    private fun timeDiff(
        expression: MetricExpression.TimeDiff,
        anchorDay: LocalDate,
        fields: FieldValuesProvider,
        boundary: BoundaryProvider,
    ): EvalResult {
        val end = asDateTimeInstant(expression.end, anchorDay, fields, boundary) ?: return EvalResult.Missing
        val start = asDateTimeInstant(expression.start, anchorDay, fields, boundary) ?: return EvalResult.Missing
        val seconds = Duration.between(start, end).seconds.toDouble()
        return EvalResult.Dur(seconds)
    }

    /** Resolves an operand that yields a time-of-day on a known day into a real date-time. */
    private fun asDateTimeInstant(
        operand: MetricExpression,
        anchorDay: LocalDate,
        fields: FieldValuesProvider,
        boundary: BoundaryProvider,
    ): java.time.LocalDateTime? {
        // V1 supports direct time field refs (and a timeDiff whose operands are time refs).
        if (operand is MetricExpression.FieldRef) {
            val ref = operand.ref
            val targetDay = anchorDay.plusDays(clampOffset(ref.dayOffset))
            val values = fields.valuesFor(ref.fieldId, targetDay)
            if (values.isEmpty()) return null
            val selected = applySelector(values, ref.selector) ?: return null
            if (selected is NormalizedFieldValue.Time) {
                val effective = JournalDayEngine.parseBoundary(boundary.boundaryFor(targetDay))
                return JournalDayEngine.resolveFieldDateTime(targetDay, selected.time, effective)
            }
            return null
        }
        return null
    }

    /**
     * Bounds a metric's `dayOffset` so a malformed (hand-edited / synced) definition can never push
     * [LocalDate.plusDays] past LocalDate.MAX/MIN and throw. ±40000 days is ~110 years, far beyond
     * any real metric but small enough that plusDays stays well inside the date range.
     */
    private fun clampOffset(dayOffset: Int): Long = dayOffset.coerceIn(-40000, 40000).toLong()

    /**
     * Collapses multiple same-day values into one using [selector]. Type-appropriate selectors are
     * the responsibility of the UI/definition; this function computes sensible results for the ones
     * that make sense and returns null for any combination it cannot evaluate.
     */
    fun applySelector(values: List<NormalizedFieldValue>, selector: FieldSelector): NormalizedFieldValue? {
        if (values.isEmpty()) return null
        return when (selector) {
            FieldSelector.COUNT -> NormalizedFieldValue.Number(values.size.toDouble())
            FieldSelector.FIRST -> values.first()
            FieldSelector.LAST -> values.last()
            FieldSelector.MIN -> when (values.first()) {
                is NormalizedFieldValue.Number -> values.filterIsInstance<NormalizedFieldValue.Number>().minByOrNull { it.value }
                is NormalizedFieldValue.Duration -> values.filterIsInstance<NormalizedFieldValue.Duration>().minByOrNull { it.seconds }
                is NormalizedFieldValue.Time -> values.filterIsInstance<NormalizedFieldValue.Time>().minByOrNull { it.time }
                else -> null
            }
            FieldSelector.MAX -> when (values.first()) {
                is NormalizedFieldValue.Number -> values.filterIsInstance<NormalizedFieldValue.Number>().maxByOrNull { it.value }
                is NormalizedFieldValue.Duration -> values.filterIsInstance<NormalizedFieldValue.Duration>().maxByOrNull { it.seconds }
                is NormalizedFieldValue.Time -> values.filterIsInstance<NormalizedFieldValue.Time>().maxByOrNull { it.time }
                else -> null
            }
            FieldSelector.SUM -> when (values.first()) {
                is NormalizedFieldValue.Number -> NormalizedFieldValue.Number(
                    values.filterIsInstance<NormalizedFieldValue.Number>().sumOf { it.value },
                )
                is NormalizedFieldValue.Duration -> NormalizedFieldValue.Duration(
                    values.filterIsInstance<NormalizedFieldValue.Duration>().sumOf { it.seconds },
                )
                else -> null
            }
            FieldSelector.AVERAGE -> when (values.first()) {
                is NormalizedFieldValue.Number -> {
                    val nums = values.filterIsInstance<NormalizedFieldValue.Number>()
                    if (nums.isEmpty()) null else NormalizedFieldValue.Number(nums.sumOf { it.value } / nums.size)
                }
                is NormalizedFieldValue.Duration -> {
                    val durs = values.filterIsInstance<NormalizedFieldValue.Duration>()
                    if (durs.isEmpty()) null else NormalizedFieldValue.Duration(durs.sumOf { it.seconds } / durs.size)
                }
                else -> null
            }
        }
    }
}
