package com.deskcubby.app.data.structuredrecords

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricEvaluatorTest {

    private val boundary = MetricEvaluator.BoundaryProvider { "05:00" }

    private fun timeProvider(vararg pairs: Triple<String, LocalDate, String>): MetricEvaluator.FieldValuesProvider {
        val map = pairs.groupBy({ it.first to it.second.toString() }, { it.third })
        return MetricEvaluator.FieldValuesProvider { fieldId, day ->
            map[fieldId to day.toString()].orEmpty().map { raw ->
                StructuredFieldNormalizer.normalize(StructuredFieldType.TIME, raw).value as NormalizedFieldValue.Time
            }
        }
    }

    /** The key acceptance test: 起床时间(D) - 睡觉时间(D-1) = 07:35 with boundary 05:00. */
    @Test
    fun sleepDurationComputesCorrectly() {
        val wake = LocalDate.of(2026, 8, 19) // D
        val sleep = LocalDate.of(2026, 8, 18) // D-1
        val provider = timeProvider(
            Triple(SYSTEM_FIELD_WAKE_TIME, wake, "08:12"),
            Triple(SYSTEM_FIELD_SLEEP_TIME, sleep, "00:37"),
        )
        val expression = MetricExpression.TimeDiff(
            end = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_WAKE_TIME, dayOffset = 0, selector = FieldSelector.LAST)),
            start = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_SLEEP_TIME, dayOffset = -1, selector = FieldSelector.LAST)),
        )
        val result = MetricEvaluator.evaluate(expression, wake, provider, boundary)
        assertTrue("expected Dur, got $result", result is MetricEvaluator.EvalResult.Dur)
        // 08:12 - 00:37 on D = 7h35m = 27300s.
        assertEquals(27300.0, (result as MetricEvaluator.EvalResult.Dur).seconds, 0.001)
    }

    @Test
    fun missingInputYieldsNullNotZero() {
        // Wake present but no previous sleep → Missing (null), never 0.
        val provider = timeProvider(
            Triple(SYSTEM_FIELD_WAKE_TIME, LocalDate.of(2026, 8, 19), "08:12"),
        )
        val expression = MetricExpression.TimeDiff(
            end = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_WAKE_TIME, 0, FieldSelector.LAST)),
            start = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_SLEEP_TIME, -1, FieldSelector.LAST)),
        )
        val result = MetricEvaluator.evaluate(expression, LocalDate.of(2026, 8, 19), provider, boundary)
        assertEquals(MetricEvaluator.EvalResult.Missing, result)
    }

    @Test
    fun arithmeticOnNumbers() {
        val expression = MetricExpression.Multiply(
            MetricExpression.Constant(30.0),
            MetricExpression.Constant(2.0),
        )
        val result = MetricEvaluator.evaluate(expression, LocalDate.of(2026, 8, 19), timeProvider(), boundary)
        assertEquals(MetricEvaluator.EvalResult.Num(60.0), result)
    }

    @Test
    fun divideByZeroIsMissing() {
        val expression = MetricExpression.Divide(
            MetricExpression.Constant(30.0),
            MetricExpression.Constant(0.0),
        )
        val result = MetricEvaluator.evaluate(expression, LocalDate.of(2026, 8, 19), timeProvider(), boundary)
        assertEquals(MetricEvaluator.EvalResult.Missing, result)
    }

    @Test
    fun selectorAggregatesSameDayValues() {
        val values = listOf(
            NormalizedFieldValue.Number(10.0),
            NormalizedFieldValue.Number(20.0),
            NormalizedFieldValue.Number(30.0),
        )
        assertEquals(
            NormalizedFieldValue.Number(60.0),
            MetricEvaluator.applySelector(values, FieldSelector.SUM),
        )
        assertEquals(
            NormalizedFieldValue.Number(20.0),
            MetricEvaluator.applySelector(values, FieldSelector.AVERAGE),
        )
    }
}
