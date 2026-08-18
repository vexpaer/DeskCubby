package com.deskcubby.app.data.structuredrecords

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Journal Day math used across the whole structured-records system.
 *
 * A Journal Day is the user-facing "day" that may not switch at local midnight. With the default
 * boundary of 05:00, a record written at 02:37 on 2026-08-19 belongs to the Journal Day
 * 2026-08-18. Every "today" / "yesterday" / "enter today's diary" decision in the app must go
 * through these functions rather than reimplementing an ad-hoc midnight comparison.
 *
 * The Markdown files themselves always store normal local `HH:mm` times and the Calendar Date in
 * the file name; Journal Day is only used to decide *which* file a record lands in and to restore
 * a real date-time when computing differences across the boundary.
 */
object JournalDayEngine {

    /** The default day boundary, in 24-hour `HH:mm` local time. */
    const val DEFAULT_DAY_BOUNDARY: String = "05:00"

    /**
     * Parses an `HH:mm` boundary (24-hour). Returns the minutes-since-midnight, or null when the
     * value is not a valid 24-hour time. Callers must safely fall back to [DEFAULT_DAY_BOUNDARY].
     */
    fun parseBoundary(value: String?): Int? {
        val text = value?.trim() ?: return null
        val match = BOUNDARY_PATTERN.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return hour * 60 + minute
    }

    /** Formats boundary minutes-since-midnight back to `HH:mm`. */
    fun formatBoundary(boundaryMinutes: Int): String {
        val safe = boundaryMinutes.coerceIn(0, 24 * 60 - 1)
        val hour = safe / 60
        val minute = safe % 60
        return "%02d:%02d".format(hour, minute)
    }

    /**
     * Resolves the Journal Day for [instant] under a given boundary (in the same local clock).
     *
     * Rule: `localTime < boundary → previous calendar day`, otherwise `the calendar day`.
     * If [boundaryMinutes] is null/invalid it falls back to the default boundary.
     */
    fun resolveJournalDay(
        instant: Instant,
        boundaryMinutes: Int?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate {
        val local = LocalDateTime.ofInstant(instant, zone)
        return resolveJournalDay(local, boundaryMinutes)
    }

    /** Overload for an already-local moment. */
    fun resolveJournalDay(
        localDateTime: LocalDateTime,
        boundaryMinutes: Int?,
    ): LocalDate {
        val boundary = boundaryMinutes ?: parseBoundary(DEFAULT_DAY_BOUNDARY) ?: 5 * 60
        return if (localDateTime.toLocalTime().toSecondOfDay() < boundary * 60) {
            localDateTime.toLocalDate().minusDays(1)
        } else {
            localDateTime.toLocalDate()
        }
    }

    /**
     * Returns the day boundary value (as `HH:mm`) effective for [journalDay], given a history of
     * boundary changes. The first entry whose `effectiveFromJournalDay <= journalDay` wins.
     * Entries must be sorted ascending. Falls back to the default when empty or when the entry
     * values are invalid.
     */
    fun getEffectiveDayBoundary(
        journalDay: LocalDate,
        history: List<DayBoundaryRecord>,
    ): String {
        val effective = history
            .asSequence()
            .filter { record ->
                val from = runCatching { LocalDate.parse(record.effectiveFromJournalDay) }.getOrNull()
                from != null && !from.isAfter(journalDay)
            }
            .maxByOrNull { record ->
                runCatching { LocalDate.parse(record.effectiveFromJournalDay) }.getOrDefault(LocalDate.MIN)
            }
        if (effective == null) return DEFAULT_DAY_BOUNDARY
        return parseBoundary(effective.value)?.let(::formatBoundary) ?: DEFAULT_DAY_BOUNDARY
    }

    /**
     * Restores the real local date-time for a `HH:mm` value recorded on [journalDay] under the
     * [boundary] effective on that day.
     *
     * `time < boundary → journalDay + 1` else `journalDay`. This means a 02:37 value on Journal Day
     * 2026-08-18 becomes 2026-08-19 02:37 — the early-morning instant that actually happened.
     */
    fun resolveFieldDateTime(
        journalDay: LocalDate,
        timeValue: LocalTime,
        boundaryMinutes: Int?,
    ): LocalDateTime {
        val boundary = boundaryMinutes ?: parseBoundary(DEFAULT_DAY_BOUNDARY) ?: 5 * 60
        val actualDate = if (timeValue.toSecondOfDay() < boundary * 60) {
            journalDay.plusDays(1)
        } else {
            journalDay
        }
        return LocalDateTime.of(actualDate, timeValue)
    }

    /** Parses a local `HH:mm` value into [LocalTime], or returns null when malformed. */
    fun parseTime(value: String?): LocalTime? {
        val text = value?.trim() ?: return null
        val match = TIME_PATTERN.matchEntire(text) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return LocalTime.of(hour, minute)
    }

    /** Formats a [LocalTime] as `HH:mm`. */
    fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    // Also accepts a single digit hour like "5:00" for leniency on user input.
    private val BOUNDARY_PATTERN = Regex("""^(\d{1,2}):(\d{2})$""")
    private val TIME_PATTERN = Regex("""^(\d{1,2}):(\d{2})$""")
}
