package com.deskcubby.app.ui.home

import com.deskcubby.app.data.model.AppLanguage
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeroTest {
    @Test
    fun `day phases cover every hour with expected boundaries`() {
        assertEquals(DayPhase.NIGHT, dayPhaseFor(LocalTime.of(4, 59)))
        assertEquals(DayPhase.DAWN, dayPhaseFor(LocalTime.of(5, 0)))
        assertEquals(DayPhase.DAWN, dayPhaseFor(LocalTime.of(7, 59)))
        assertEquals(DayPhase.DAY, dayPhaseFor(LocalTime.of(8, 0)))
        assertEquals(DayPhase.DAY, dayPhaseFor(LocalTime.of(16, 59)))
        assertEquals(DayPhase.DUSK, dayPhaseFor(LocalTime.of(17, 0)))
        assertEquals(DayPhase.DUSK, dayPhaseFor(LocalTime.of(19, 59)))
        assertEquals(DayPhase.NIGHT, dayPhaseFor(LocalTime.of(20, 0)))
        assertEquals(DayPhase.NIGHT, dayPhaseFor(LocalTime.MIDNIGHT))
    }

    @Test
    fun `light source travels with the sun and parks at night`() {
        val morning = lightSourceFraction(LocalTime.of(6, 0))
        val noon = lightSourceFraction(LocalTime.NOON)
        val evening = lightSourceFraction(LocalTime.of(18, 0))
        assertEquals(0.08f, morning, 0.001f)
        assertEquals(0.5f, noon, 0.001f)
        assertEquals(0.92f, evening, 0.001f)
        assertTrue(morning < noon && noon < evening)
        assertEquals(0.86f, lightSourceFraction(LocalTime.of(23, 30)), 0.001f)
        (0 until 24 * 60 step 7).forEach { minute ->
            val fraction = lightSourceFraction(LocalTime.of(minute / 60, minute % 60))
            assertTrue(fraction in 0f..1f)
        }
    }

    @Test
    fun `every app language maps to a matching java locale`() {
        assertEquals(Locale.SIMPLIFIED_CHINESE, AppLanguage.CHINESE.javaLocale())
        assertEquals(Locale.TRADITIONAL_CHINESE, AppLanguage.TRADITIONAL_CHINESE.javaLocale())
        assertEquals(Locale.ENGLISH, AppLanguage.ENGLISH.javaLocale())
        assertEquals(Locale.KOREAN, AppLanguage.KOREAN.javaLocale())
        assertEquals(Locale.JAPANESE, AppLanguage.JAPANESE.javaLocale())
    }
}
