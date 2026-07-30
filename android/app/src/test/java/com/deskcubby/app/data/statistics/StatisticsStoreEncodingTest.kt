package com.deskcubby.app.data.statistics

import java.nio.charset.StandardCharsets
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StatisticsStoreEncodingTest {
    @Test
    fun `encoding is decoded and compared before it can be committed`() {
        var decodedText: String? = null

        val (encoded, verified) = encodeAndVerifyStatisticsValue(
            value = 42,
            encode = { """{"value":$it}""" },
            decode = { text ->
                decodedText = text
                42
            },
        )

        assertEquals("""{"value":42}""", encoded)
        assertEquals(encoded, decodedText)
        assertEquals(42, verified)
    }

    @Test
    fun `round trip mismatch fails before commit`() {
        assertThrows(StatisticsJsonException::class.java) {
            encodeAndVerifyStatisticsValue(
                value = 42,
                encode = Int::toString,
                decode = { 41 },
            )
        }
    }

    @Test
    fun `oversized encoding fails before decode`() {
        var decoded = false

        assertThrows(StatisticsJsonException::class.java) {
            encodeAndVerifyStatisticsValue(
                value = "12345",
                encode = { it },
                decode = {
                    decoded = true
                    it
                },
                maximumBytes = 4,
            )
        }
        assertEquals(false, decoded)
    }

    @Test
    fun `usage export canonicalization sorts days and package names`() {
        val history = UsageStatisticsHistory(
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
        )

        val canonical = canonicalUsageStatisticsHistory(history)

        assertEquals(
            listOf(
                LocalDate.parse("2026-07-28"),
                LocalDate.parse("2026-07-29"),
            ),
            canonical.days.map(UsageStatisticsDay::date),
        )
        assertEquals(
            listOf("a.example", "z.example"),
            canonical.days.last().apps.map(UsageAppDuration::packageName),
        )
    }

    @Test
    fun `usage export encoding is deterministic canonical schema v4`() {
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
    fun `export read back requires exact bytes and matching decode`() {
        val expected = """{"schemaVersion":4}""".toByteArray(StandardCharsets.UTF_8)

        val verified = verifyStatisticsExportReadBack(
            expectedBytes = expected,
            actualBytes = expected.copyOf(),
            expectedValue = 4,
            decode = { text -> if ("\"schemaVersion\":4" in text) 4 else 0 },
        )

        assertEquals(4, verified)
    }

    @Test
    fun `byte mismatch fails before export decode`() {
        var decoded = false
        val expected = "expected".toByteArray(StandardCharsets.UTF_8)

        assertThrows(StatisticsJsonException::class.java) {
            verifyStatisticsExportReadBack(
                expectedBytes = expected,
                actualBytes = "changed".toByteArray(StandardCharsets.UTF_8),
                expectedValue = "expected",
                decode = {
                    decoded = true
                    it
                },
            )
        }

        assertEquals(false, decoded)
    }

    @Test
    fun `oversized export read back is rejected`() {
        val bytes = "12345".toByteArray(StandardCharsets.UTF_8)

        assertThrows(StatisticsJsonException::class.java) {
            verifyStatisticsExportReadBack(
                expectedBytes = bytes,
                actualBytes = bytes.copyOf(),
                expectedValue = "12345",
                decode = { it },
                maximumBytes = 4,
            )
        }
    }

    @Test
    fun `matching export bytes must still decode to the expected history`() {
        val bytes = """{"schemaVersion":4}""".toByteArray(StandardCharsets.UTF_8)

        assertThrows(StatisticsJsonException::class.java) {
            verifyStatisticsExportReadBack(
                expectedBytes = bytes,
                actualBytes = bytes.copyOf(),
                expectedValue = 4,
                decode = { 3 },
            )
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
