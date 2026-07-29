package com.deskcubby.app.data.statistics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStepCounterMergeTest {
    private val firstDate = LocalDate.parse("2026-07-28")

    @Test
    fun firstSampleOnlyEstablishesBaseline() {
        val result = mergeDeviceStepCounterSample(
            history = StepStatisticsHistory(),
            date = firstDate,
            zoneId = "Asia/Shanghai",
            capturedAtEpochMillis = 100,
            cumulativeSteps = 5_000,
        )

        assertEquals(firstDate, result.trackingStartedOn)
        assertNull(result.days.single().steps)
        assertEquals(5_000L, result.deviceSensorBaseline?.cumulativeSteps)
    }

    @Test
    fun laterSampleAddsOnlyTheCumulativeDifference() {
        val baseline = mergeDeviceStepCounterSample(
            StepStatisticsHistory(),
            firstDate,
            "Asia/Shanghai",
            100,
            5_000,
        )

        val result = mergeDeviceStepCounterSample(
            baseline,
            firstDate,
            "Asia/Shanghai",
            200,
            5_321,
        )

        assertEquals(321L, result.days.single().steps)
    }

    @Test
    fun rebootAndMidnightNeverInventMissingSteps() {
        val sampled = mergeDeviceStepCounterSample(
            mergeDeviceStepCounterSample(
                StepStatisticsHistory(),
                firstDate,
                "Asia/Shanghai",
                100,
                5_000,
            ),
            firstDate,
            "Asia/Shanghai",
            200,
            5_200,
        )
        val afterReboot = mergeDeviceStepCounterSample(
            sampled,
            firstDate,
            "Asia/Shanghai",
            300,
            20,
        )
        assertEquals(200L, afterReboot.days.single().steps)

        val nextDate = firstDate.plusDays(1)
        val nextDay = mergeDeviceStepCounterSample(
            afterReboot,
            nextDate,
            "Asia/Shanghai",
            400,
            120,
        )
        assertEquals(StatisticsDayState.FINAL, nextDay.days.first().state)
        assertNull(nextDay.days.last().steps)
    }
}
