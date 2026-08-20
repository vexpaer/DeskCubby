package com.deskcubby.app.data.structuredrecords

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Derived-metric evaluator anchored on natural calendar dates. It never reads the diary switch time.
 * Missing inputs propagate as [EvalResult.Missing] rather than silently becoming zero.
 */
object MetricEvaluator {

    fun interface FieldValuesProvider {
        fun valuesFor(fieldId: String, date: LocalDate): List<NormalizedFieldValue>
    }

    sealed interface EvalResult {
        data object Missing : EvalResult
        data class Num(val value: Double) : EvalResult
        data class Dur(val seconds: Double) : EvalResult
    }

    fun evaluate(
        expression: MetricExpression,
        date: LocalDate,
        fields: FieldValuesProvider,
    ): EvalResult = when (expression) {
        is MetricExpression.Constant -> EvalResult.Num(expression.number)
        is MetricExpression.FieldRef -> resolveFieldRef(expression.ref, date, fields)
        is MetricExpression.Add -> arithmetic('+', expression.left, expression.right, date, fields)
        is MetricExpression.Subtract -> arithmetic('-', expression.left, expression.right, date, fields)
        is MetricExpression.Multiply -> arithmetic('*', expression.left, expression.right, date, fields)
        is MetricExpression.Divide -> arithmetic('/', expression.left, expression.right, date, fields)
        is MetricExpression.TimeDiff -> timeDiff(expression, date, fields)
    }

    private fun resolveFieldRef(
        ref: FieldRefNode,
        anchorDate: LocalDate,
        fields: FieldValuesProvider,
    ): EvalResult {
        val targetDate = anchorDate.plusDays(clampOffset(ref.dayOffset))
        val values = fields.valuesFor(ref.fieldId, targetDate)
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
        date: LocalDate,
        fields: FieldValuesProvider,
    ): EvalResult {
        val a = evaluate(left, date, fields)
        val b = evaluate(right, date, fields)
        if (a is EvalResult.Missing || b is EvalResult.Missing) return EvalResult.Missing
        return when (op) {
            '+', '-' -> when {
                a is EvalResult.Num && b is EvalResult.Num ->
                    EvalResult.Num(if (op == '+') a.value + b.value else a.value - b.value)
                a is EvalResult.Dur && b is EvalResult.Dur ->
                    EvalResult.Dur(if (op == '+') a.seconds + b.seconds else a.seconds - b.seconds)
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
        anchorDate: LocalDate,
        fields: FieldValuesProvider,
    ): EvalResult {
        var end = asNaturalDateTime(expression.end, anchorDate, fields) ?: return EvalResult.Missing
        val start = asNaturalDateTime(expression.start, anchorDate, fields) ?: return EvalResult.Missing

        // A same-date pair such as sleep 23:30 -> wake 07:00 is an overnight interval. This is
        // ordinary clock arithmetic, not a configurable day-boundary projection.
        if (end.isBefore(start) && sameTargetDate(expression.end, expression.start, anchorDate)) {
            end = end.plusDays(1)
        }
        return EvalResult.Dur(Duration.between(start, end).seconds.toDouble())
    }

    private fun asNaturalDateTime(
        operand: MetricExpression,
        anchorDate: LocalDate,
        fields: FieldValuesProvider,
    ): LocalDateTime? {
        if (operand !is MetricExpression.FieldRef) return null
        val ref = operand.ref
        val targetDate = anchorDate.plusDays(clampOffset(ref.dayOffset))
        val selected = applySelector(fields.valuesFor(ref.fieldId, targetDate), ref.selector)
        return (selected as? NormalizedFieldValue.Time)?.let { LocalDateTime.of(targetDate, it.time) }
    }

    private fun sameTargetDate(
        first: MetricExpression,
        second: MetricExpression,
        anchorDate: LocalDate,
    ): Boolean {
        val a = (first as? MetricExpression.FieldRef)?.ref ?: return false
        val b = (second as? MetricExpression.FieldRef)?.ref ?: return false
        return anchorDate.plusDays(clampOffset(a.dayOffset)) == anchorDate.plusDays(clampOffset(b.dayOffset))
    }

    private fun clampOffset(dayOffset: Int): Long = dayOffset.coerceIn(-40000, 40000).toLong()

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
