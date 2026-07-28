package com.deskcubby.app.data.statistics

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatisticsJsonCodecsTest {
    @Test
    fun usageRoundTripIsDeterministic() {
        val history = UsageStatisticsHistory(
            trackingStartedOn = LocalDate.parse("2026-07-26"),
            days = listOf(
                UsageStatisticsDay(
                    date = LocalDate.parse("2026-07-27"),
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.OPEN,
                    collectedAtEpochMillis = 42,
                    apps = listOf(
                        UsageAppDuration("a.example", 8_000),
                        UsageAppDuration("z.example", 12_000),
                    ),
                ),
            ),
            backfillCompletedThrough = LocalDate.parse("2026-07-26"),
        )

        val encoded = UsageStatisticsJsonCodec.encode(history)
        val decoded = UsageStatisticsJsonCodec.decode(encoded)

        assertEquals(history, decoded)
        assertEquals(encoded, UsageStatisticsJsonCodec.encode(decoded))
    }

    @Test
    fun usageV1MigrationPreservesFinalRowsAndLeavesDiscoveryPending() {
        val history = UsageStatisticsHistory(
            trackingStartedOn = LocalDate.parse("2026-07-26"),
            days = listOf(
                UsageStatisticsDay(
                    date = LocalDate.parse("2026-07-26"),
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.FINAL,
                    collectedAtEpochMillis = 42,
                    apps = listOf(UsageAppDuration("example.app", 8_000)),
                ),
            ),
            backfillCompletedThrough = LocalDate.parse("2026-07-26"),
        )
        val legacy = JSONObject(UsageStatisticsJsonCodec.encode(history))
            .put("schemaVersion", 1)
            .apply { remove("backfillCompletedThrough") }

        val migrated = UsageStatisticsJsonCodec.decode(legacy.toString())

        assertEquals(history.days, migrated.days)
        assertEquals(StatisticsDayState.FINAL, migrated.days.single().state)
        assertNull(migrated.backfillCompletedThrough)
        val reencoded = JSONObject(UsageStatisticsJsonCodec.encode(migrated))
        assertEquals(3, reencoded.getInt("schemaVersion"))
        assertEquals(true, reencoded.has("backfillCompletedThrough"))
    }

    @Test
    fun usageV2MigrationPreservesRowsAndForcesCorrectedBackfill() {
        val history = UsageStatisticsHistory(
            trackingStartedOn = LocalDate.parse("2026-07-26"),
            days = listOf(
                UsageStatisticsDay(
                    date = LocalDate.parse("2026-07-26"),
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.FINAL,
                    collectedAtEpochMillis = 42,
                    apps = listOf(UsageAppDuration("example.app", 8_000)),
                ),
            ),
            backfillCompletedThrough = LocalDate.parse("2026-07-26"),
        )
        val versionTwo = JSONObject(UsageStatisticsJsonCodec.encode(history))
            .put("schemaVersion", 2)

        val migrated = UsageStatisticsJsonCodec.decode(versionTwo.toString())

        assertEquals(history.days, migrated.days)
        assertNull(migrated.backfillCompletedThrough)
        assertEquals(
            3,
            JSONObject(UsageStatisticsJsonCodec.encode(migrated))
                .getInt("schemaVersion"),
        )
    }

    @Test
    fun stepsPreserveNoAggregateAsNull() {
        val history = StepStatisticsHistory(
            trackingStartedOn = LocalDate.parse("2026-07-27"),
            days = listOf(
                StepStatisticsDay(
                    date = LocalDate.parse("2026-07-27"),
                    zoneId = "Europe/Paris",
                    state = StatisticsDayState.OPEN,
                    collectedAtEpochMillis = 42,
                    steps = null,
                ),
            ),
        )

        val decoded = StepStatisticsJsonCodec.decode(
            StepStatisticsJsonCodec.encode(history),
        )

        assertNull(decoded.days.single().steps)
    }

    @Test
    fun stepsPreserveDeviceSensorBaselineAndImportVersionOne() {
        val date = LocalDate.parse("2026-07-27")
        val history = StepStatisticsHistory(
            trackingStartedOn = date,
            days = listOf(
                StepStatisticsDay(
                    date = date,
                    zoneId = "UTC",
                    state = StatisticsDayState.OPEN,
                    collectedAtEpochMillis = 42,
                    steps = 123,
                ),
            ),
            deviceSensorBaseline = DeviceStepSensorBaseline(
                date = date,
                cumulativeSteps = 4_567,
                capturedAtEpochMillis = 42,
            ),
        )

        val decoded = StepStatisticsJsonCodec.decode(
            StepStatisticsJsonCodec.encode(history),
        )
        assertEquals(history, decoded)

        val versionOne = JSONObject(StepStatisticsJsonCodec.encode(history))
            .put("schemaVersion", 1)
            .apply { remove("deviceSensorBaseline") }
        assertNull(
            StepStatisticsJsonCodec.decode(versionOne.toString())
                .deviceSensorBaseline,
        )
    }

    @Test
    fun rejectsUnknownField() {
        val root = JSONObject(
            StepStatisticsJsonCodec.encode(StepStatisticsHistory()),
        ).put("unexpected", true)

        assertThrows(StatisticsJsonException::class.java) {
            StepStatisticsJsonCodec.decode(root.toString())
        }
    }

    @Test
    fun rejectsDuplicateDates() {
        val day = StepStatisticsDay(
            date = LocalDate.parse("2026-07-27"),
            zoneId = "UTC",
            state = StatisticsDayState.FINAL,
            collectedAtEpochMillis = 1,
            steps = 10,
        )

        assertThrows(StatisticsJsonException::class.java) {
            StepStatisticsJsonCodec.encode(
                StepStatisticsHistory(
                    trackingStartedOn = day.date,
                    days = listOf(day, day),
                ),
            )
        }
    }

    @Test
    fun rejectsFractionalAndNegativeNumbers() {
        val valid = JSONObject(
            StepStatisticsJsonCodec.encode(
                StepStatisticsHistory(
                    trackingStartedOn = LocalDate.parse("2026-07-27"),
                    days = listOf(
                        StepStatisticsDay(
                            date = LocalDate.parse("2026-07-27"),
                            zoneId = "UTC",
                            state = StatisticsDayState.FINAL,
                            collectedAtEpochMillis = 1,
                            steps = 10,
                        ),
                    ),
                ),
            ),
        )
        valid.getJSONArray("days").getJSONObject(0).put("steps", -1)
        assertThrows(StatisticsJsonException::class.java) {
            StepStatisticsJsonCodec.decode(valid.toString())
        }

        valid.getJSONArray("days").getJSONObject(0).put("steps", 1.5)
        assertThrows(StatisticsJsonException::class.java) {
            StepStatisticsJsonCodec.decode(valid.toString())
        }
    }
}
