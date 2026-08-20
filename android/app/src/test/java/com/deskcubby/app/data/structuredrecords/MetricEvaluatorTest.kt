package com.deskcubby.app.data.structuredrecords

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricEvaluatorTest {

    private fun timeProvider(vararg pairs: Triple<String, LocalDate, String>): MetricEvaluator.FieldValuesProvider {
        val map = pairs.groupBy({ it.first to it.second.toString() }, { it.third })
        return MetricEvaluator.FieldValuesProvider { fieldId, day ->
            map[fieldId to day.toString()].orEmpty().map { raw ->
                StructuredFieldNormalizer.normalize(StructuredFieldType.TIME, raw).value as NormalizedFieldValue.Time
            }
        }
    }

    /** Same natural-date sleep/wake values wrap across midnight without a configurable boundary. */
    @Test
    fun overnightSleepDurationComputesCorrectly() {
        val date = LocalDate.of(2026, 8, 20)
        val provider = timeProvider(
            Triple(SYSTEM_FIELD_SLEEP_TIME, date, "00:37"),
            Triple(SYSTEM_FIELD_WAKE_TIME, date, "08:12"),
        )
        val expression = MetricExpression.TimeDiff(
            end = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_WAKE_TIME, 0, FieldSelector.LAST)),
            start = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_SLEEP_TIME, 0, FieldSelector.LAST)),
        )
        val result = MetricEvaluator.evaluate(expression, date, provider)
        assertTrue("expected Dur, got $result", result is MetricEvaluator.EvalResult.Dur)
        assertEquals(27300.0, (result as MetricEvaluator.EvalResult.Dur).seconds, 0.001)
    }

    @Test
    fun sleepBeforeMidnightAndWakeAfterMidnightWrapsOnce() {
        val date = LocalDate.of(2026, 8, 20)
        val provider = timeProvider(
            Triple(SYSTEM_FIELD_SLEEP_TIME, date, "23:30"),
            Triple(SYSTEM_FIELD_WAKE_TIME, date, "07:00"),
        )
        val expression = MetricExpression.TimeDiff(
            end = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_WAKE_TIME)),
            start = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_SLEEP_TIME)),
        )
        val result = MetricEvaluator.evaluate(expression, date, provider) as MetricEvaluator.EvalResult.Dur
        assertEquals(27000.0, result.seconds, 0.001)
    }

    @Test
    fun missingInputYieldsNullNotZero() {
        val provider = timeProvider(
            Triple(SYSTEM_FIELD_WAKE_TIME, LocalDate.of(2026, 8, 20), "08:12"),
        )
        val expression = MetricExpression.TimeDiff(
            end = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_WAKE_TIME)),
            start = MetricExpression.FieldRef(FieldRefNode(SYSTEM_FIELD_SLEEP_TIME)),
        )
        val result = MetricEvaluator.evaluate(expression, LocalDate.of(2026, 8, 20), provider)
        assertEquals(MetricEvaluator.EvalResult.Missing, result)
    }

    @Test
    fun arithmeticOnNumbers() {
        val expression = MetricExpression.Multiply(
            MetricExpression.Constant(30.0),
            MetricExpression.Constant(2.0),
        )
        val result = MetricEvaluator.evaluate(expression, LocalDate.of(2026, 8, 20), timeProvider())
        assertEquals(MetricEvaluator.EvalResult.Num(60.0), result)
    }

    @Test
    fun divideByZeroIsMissing() {
        val expression = MetricExpression.Divide(
            MetricExpression.Constant(30.0),
            MetricExpression.Constant(0.0),
        )
        val result = MetricEvaluator.evaluate(expression, LocalDate.of(2026, 8, 20), timeProvider())
        assertEquals(MetricEvaluator.EvalResult.Missing, result)
    }

    @Test
    fun selectorAggregatesSameDateValues() {
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
