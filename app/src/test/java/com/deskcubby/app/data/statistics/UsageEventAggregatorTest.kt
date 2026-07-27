package com.deskcubby.app.data.statistics

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageEventAggregatorTest {
    @Test
    fun carriesForegroundStateAcrossMidnight() {
        val start = Instant.parse("2026-07-27T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-07-28T00:00:00Z").toEpochMilli()

        val result = aggregateForegroundUsage(
            windowStartMillis = start,
            windowEndMillis = end,
            events = listOf(
                transition("2026-07-26T23:40:00Z", "example.app", true),
                transition("2026-07-27T00:30:00Z", "example.app", false),
            ),
        )

        assertEquals(
            listOf(UsageAppDuration("example.app", 30L * 60L * 1_000L)),
            result,
        )
    }

    @Test
    fun duplicateForegroundEventsDoNotDoubleCount() {
        val start = Instant.parse("2026-07-27T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-07-28T00:00:00Z").toEpochMilli()

        val result = aggregateForegroundUsage(
            windowStartMillis = start,
            windowEndMillis = end,
            events = listOf(
                transition("2026-07-27T09:00:00Z", "example.app", true),
                transition("2026-07-27T09:05:00Z", "example.app", true),
                transition("2026-07-27T09:20:00Z", "example.app", false),
                transition("2026-07-27T09:25:00Z", "example.app", false),
            ),
        )

        assertEquals(
            listOf(UsageAppDuration("example.app", 20L * 60L * 1_000L)),
            result,
        )
    }

    @Test
    fun unfinishedSessionStopsAtHalfOpenWindowEnd() {
        val start = Instant.parse("2026-07-27T00:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-07-27T12:00:00Z").toEpochMilli()

        val result = aggregateForegroundUsage(
            windowStartMillis = start,
            windowEndMillis = end,
            events = listOf(
                transition("2026-07-27T11:45:00Z", "example.app", true),
                transition("2026-07-27T12:10:00Z", "example.app", false),
            ),
        )

        assertEquals(
            listOf(UsageAppDuration("example.app", 15L * 60L * 1_000L)),
            result,
        )
    }

    private fun transition(
        timestamp: String,
        packageName: String,
        foreground: Boolean,
    ) = ForegroundTransition(
        epochMillis = Instant.parse(timestamp).toEpochMilli(),
        packageName = packageName,
        type = if (foreground) {
            ForegroundTransitionType.ENTER_FOREGROUND
        } else {
            ForegroundTransitionType.LEAVE_FOREGROUND
        },
    )
}
