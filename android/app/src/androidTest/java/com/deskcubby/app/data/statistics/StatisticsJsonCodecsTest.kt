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
    fun usageExportEncodingUsesCanonicalSchemaV4Bytes() {
        val canonical = canonicalUsageStatisticsHistory(
            UsageStatisticsHistory(
                trackingStartedOn = LocalDate.parse("2026-07-28"),
                days = listOf(
                    usageDay(
                        date = "2026-07-29",
                        apps = listOf(
                            UsageAppDuration("z.example", 2),
                            UsageAppDuration("a.example", 1),
                        ),
                    ),
                    usageDay(
                        date = "2026-07-28",
                        apps = listOf(UsageAppDuration("m.example", 3)),
                    ),
                ),
                backfillCompletedThrough = LocalDate.parse("2026-07-28"),
            ),
        )

        val (encoded, verified) = encodeAndVerifyStatisticsValue(
            value = canonical,
            encode = UsageStatisticsJsonCodec::encode,
            decode = UsageStatisticsJsonCodec::decode,
        )
        val expected = buildString {
            append("{\"schemaVersion\":4,\"trackingStartedOn\":\"2026-07-28\",")
            append("\"backfillCompletedThrough\":\"2026-07-28\",\"days\":[")
            append(
                "{\"date\":\"2026-07-28\",\"zoneId\":\"Asia/Shanghai\"," +
                    "\"state\":\"FINAL\",\"collectedAtEpochMillis\":1,\"apps\":[" +
                    "{\"packageName\":\"m.example\",\"foregroundMillis\":3}]},",
            )
            append(
                "{\"date\":\"2026-07-29\",\"zoneId\":\"Asia/Shanghai\"," +
                    "\"state\":\"FINAL\",\"collectedAtEpochMillis\":1,\"apps\":[" +
                    "{\"packageName\":\"a.example\",\"foregroundMillis\":1}," +
                    "{\"packageName\":\"z.example\",\"foregroundMillis\":2}]}]}",
            )
        }

        assertEquals(expected, encoded)
        assertEquals(canonical, verified)
    }

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
        assertEquals(4, reencoded.getInt("schemaVersion"))
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
            4,
            JSONObject(UsageStatisticsJsonCodec.encode(migrated))
                .getInt("schemaVersion"),
        )
    }

    @Test
    fun usageV3MigrationForcesEventStreamRebuild() {
        val history = UsageStatisticsHistory(
            trackingStartedOn = LocalDate.parse("2026-07-25"),
            days = listOf(
                UsageStatisticsDay(
                    date = LocalDate.parse("2026-07-25"),
                    zoneId = "Asia/Shanghai",
                    state = StatisticsDayState.FINAL,
                    collectedAtEpochMillis = 42,
                    apps = listOf(UsageAppDuration("com.tencent.mm", 8_400_000)),
                ),
            ),
            backfillCompletedThrough = LocalDate.parse("2026-07-26"),
        )
        val versionThree = JSONObject(UsageStatisticsJsonCodec.encode(history))
            .put("schemaVersion", 3)

        val migrated = UsageStatisticsJsonCodec.decode(versionThree.toString())

        assertEquals(history.days, migrated.days)
        assertNull(migrated.backfillCompletedThrough)
        assertEquals(
            4,
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

    private fun usageDay(
        date: String,
        apps: List<UsageAppDuration>,
    ) = UsageStatisticsDay(
        date = LocalDate.parse(date),
        zoneId = "Asia/Shanghai",
        state = StatisticsDayState.FINAL,
        collectedAtEpochMillis = 1,
        apps = apps,
    )
}
