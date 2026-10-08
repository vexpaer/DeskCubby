package com.deskcubby.app.ui.desk

import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.ui.desk.model.DeskDayRecap
import com.deskcubby.app.ui.desk.model.buildDayRecapMarkdown
import com.deskcubby.app.ui.desk.model.spanLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskRecapTest {
    private val zone = ZoneOffset.UTC
    private val date = LocalDate.of(2026, 10, 7)
    private fun at(hour: Int, minute: Int) =
        LocalDateTime.of(date.year, date.month, date.dayOfMonth, hour, minute).toInstant(zone).toEpochMilli()

    private val recap = DeskDayRecap(
        date = date,
        diaryWords = 1234,
        ideaCount = 3,
        photoCount = 2,
        momentCount = 6,
        firstMomentMillis = at(22, 40),
        lastMomentMillis = at(8, 12),
    )

    @Test
    fun `span label orders times and collapses a single moment`() {
        assertEquals("08:12 – 22:40", recap.spanLabel(zone))
        assertEquals("09:05", recap.copy(firstMomentMillis = at(9, 5), lastMomentMillis = null).spanLabel(zone))
        assertNull(recap.copy(firstMomentMillis = null, lastMomentMillis = null).spanLabel(zone))
    }

    @Test
    fun `chinese recap block is a standalone markdown section`() {
        val markdown = buildDayRecapMarkdown(recap, AppLanguage.CHINESE, zone)
        assertEquals(
            "## 今日收尾 · 2026-10-07\n\n" +
                "- 日记 1234 字 · 小巧思 3 条 · 照片 2 张 · 痕迹 6 处\n" +
                "- 记录时间 08:12 – 22:40\n",
            markdown,
        )
    }

    @Test
    fun `english recap omits the time line when no time is known`() {
        val markdown = buildDayRecapMarkdown(
            recap.copy(firstMomentMillis = null, lastMomentMillis = null),
            AppLanguage.ENGLISH,
            zone,
        )
        assertEquals(
            "## Closing the day · 2026-10-07\n\n- Diary 1234 words · 3 thoughts · 2 photos · 6 moments\n",
            markdown,
        )
    }

    @Test
    fun `other languages translate every placeholder`() {
        listOf(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.KOREAN, AppLanguage.JAPANESE).forEach { language ->
            val markdown = buildDayRecapMarkdown(recap, language, zone)
            assertFalse(markdown, markdown.contains("{"))
            assertTrue(markdown, markdown.contains("1234"))
        }
    }

    @Test
    fun `empty day is reported as empty`() {
        assertTrue(DeskDayRecap(date, 0, 0, 0, 0, null, null).isEmpty)
        assertFalse(recap.isEmpty)
    }
}
