package com.deskcubby.app.ui.settings

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFileNameTest {
    @Test
    fun `uses requested DC prefix and ISO local date`() {
        val clock = Clock.fixed(
            Instant.parse("2026-08-04T03:02:01Z"),
            ZoneOffset.UTC,
        )

        assertEquals("DC-2026-08-04.json", defaultBackupFileName(clock))
    }

    @Test
    fun `derives date from injected clock zone at a day boundary`() {
        val instant = Instant.parse("2026-08-03T16:30:00Z")

        assertEquals(
            "DC-2026-08-03.json",
            defaultBackupFileName(Clock.fixed(instant, ZoneOffset.UTC)),
        )
        assertEquals(
            "DC-2026-08-04.json",
            defaultBackupFileName(Clock.fixed(instant, ZoneId.of("Asia/Shanghai"))),
        )
    }
}
