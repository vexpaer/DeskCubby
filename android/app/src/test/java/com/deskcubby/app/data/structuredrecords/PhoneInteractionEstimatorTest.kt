package com.deskcubby.app.data.structuredrecords

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneInteractionEstimatorTest {

    @Test
    fun sessionUsesLastStopBeforeNextStartAndRealTimestampDuration() {
        val zone = ZoneId.of("Asia/Shanghai")
        val firstStop = Instant.parse("2026-08-19T15:25:00Z")
        val finalLock = Instant.parse("2026-08-19T15:30:00Z")
        val wake = Instant.parse("2026-08-19T23:00:00Z")
        val sessions = buildSleepSessions(
            listOf(
                PhoneInteractionMoment(firstStop, PhoneInteractionKind.STOP),
                PhoneInteractionMoment(finalLock, PhoneInteractionKind.STOP),
                PhoneInteractionMoment(wake, PhoneInteractionKind.START),
            ),
            zone,
        )

        assertEquals(1, sessions.size)
        val session = sessions.single()
        assertEquals(finalLock, session.sleepTimestamp)
        assertEquals(wake, session.wakeTimestamp)
        assertEquals(Duration.between(finalLock, wake).seconds, session.durationSeconds)
        assertEquals("23:30", JournalDayEngine.formatTime(session.sleepLocalTime()))
        assertEquals("07:00", JournalDayEngine.formatTime(session.wakeLocalTime()))
    }
}
