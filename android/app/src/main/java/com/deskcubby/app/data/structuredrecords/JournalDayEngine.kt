package com.deskcubby.app.data.structuredrecords

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Legacy-named compatibility utility for the single remaining pre-midnight diary behavior.
 *
 * DeskCubby no longer has a global Journal Day. Calendar ownership, structured records and
 * statistics all use natural dates. The switch-time resolver below is only for the explicit
 * “进入今日日记” action.
 */
object JournalDayEngine {
    const val DEFAULT_DAY_BOUNDARY: String = "05:00"

    /** Parses a device-local diary switch time and returns minutes since midnight. */
    fun parseBoundary(value: String?): Int? {
        val text = value?.trim() ?: return null
        val match = TIME_PATTERN.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return hour * 60 + minute
    }

    fun formatBoundary(minutesSinceMidnight: Int): String {
        val safe = minutesSinceMidnight.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    /**
     * Resolves only the file opened by “进入今日日记”. It must not be used for record ownership,
     * statistics, meals, calories, Agent dates or background jobs.
     */
    fun resolveTodayDiaryDate(
        now: LocalDateTime,
        switchMinutes: Int?,
    ): LocalDate {
        val switch = switchMinutes ?: parseBoundary(DEFAULT_DAY_BOUNDARY) ?: 5 * 60
        return if (now.toLocalTime().toSecondOfDay() < switch * 60) {
            now.toLocalDate().minusDays(1)
        } else {
            now.toLocalDate()
        }
    }

    fun parseTime(value: String?): LocalTime? {
        val text = value?.trim() ?: return null
        val match = TIME_PATTERN.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return LocalTime.of(hour, minute)
    }

    fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    private val TIME_PATTERN = Regex("""^(\d{1,2}):(\d{2})$""")
}
