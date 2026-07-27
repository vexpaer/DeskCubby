package com.deskcubby.app.ui.home

import com.deskcubby.app.data.model.AppLanguage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGreetingTest {
    @Test
    fun `contains at least twenty four bilingual templates`() {
        assertTrue(HomeGreeting.templateCount >= 24)

        val start = LocalDate.of(2026, 1, 1)
        val chinese = (0 until HomeGreeting.templateCount).map { offset ->
            HomeGreeting.forDate(start.plusDays(offset.toLong()), AppLanguage.CHINESE, "")
        }
        val english = (0 until HomeGreeting.templateCount).map { offset ->
            HomeGreeting.forDate(start.plusDays(offset.toLong()), AppLanguage.ENGLISH, "")
        }

        assertEquals(HomeGreeting.templateCount, chinese.toSet().size)
        assertEquals(HomeGreeting.templateCount, english.toSet().size)
    }

    @Test
    fun `same date is stable and full cycle repeats`() {
        val date = LocalDate.of(2026, 7, 27)
        val first = HomeGreeting.forDate(date, AppLanguage.CHINESE, "阿岚")
        val second = HomeGreeting.forDate(date, AppLanguage.CHINESE, "阿岚")
        val afterCycle = HomeGreeting.forDate(
            date.plusDays(HomeGreeting.templateCount.toLong()),
            AppLanguage.CHINESE,
            "阿岚",
        )

        assertEquals(first, second)
        assertEquals(first, afterCycle)
        assertTrue(first.contains("阿岚"))
    }

    @Test
    fun `language and anonymous fallback are localized`() {
        val date = LocalDate.of(2026, 2, 3)

        val chinese = HomeGreeting.forDate(date, AppLanguage.CHINESE, "   ")
        val english = HomeGreeting.forDate(date, AppLanguage.ENGLISH, "   ")

        assertTrue(chinese.contains("朋友"))
        assertTrue(english.contains("Friend"))
    }
}
