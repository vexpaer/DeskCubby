package com.deskcubby.app.ui.desk.model

import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.ui.theme.translate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What "closing the day" on the Desk summarizes. Every number is derived from records that already
 * exist; the recap is only written to the diary when the person explicitly asks for it.
 */
data class DeskDayRecap(
    val date: LocalDate,
    val diaryWords: Int,
    val ideaCount: Int,
    val photoCount: Int,
    val momentCount: Int,
    /** Earliest and latest known record times today, epoch millis; null when unknown. */
    val firstMomentMillis: Long?,
    val lastMomentMillis: Long?,
) {
    val isEmpty: Boolean
        get() = diaryWords == 0 && ideaCount == 0 && photoCount == 0 && momentCount == 0
}

/** Progress of writing the recap into today's diary. */
enum class RecapSaveState { IDLE, SAVING, SAVED, FAILED }

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "08:12 – 22:40", a single time, or null when no record time is known. */
fun DeskDayRecap.spanLabel(zone: ZoneId = ZoneId.systemDefault()): String? {
    val first = firstMomentMillis ?: return null
    val last = lastMomentMillis ?: first
    val start = clockFormat.format(Instant.ofEpochMilli(minOf(first, last)).atZone(zone))
    val end = clockFormat.format(Instant.ofEpochMilli(maxOf(first, last)).atZone(zone))
    return if (start == end) start else "$start – $end"
}

/**
 * The standalone Markdown block appended to today's diary. It contains counts and times only, never
 * copies of thought or diary text, so saving it twice or editing it later is harmless.
 */
fun buildDayRecapMarkdown(
    recap: DeskDayRecap,
    language: AppLanguage,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val heading = translate("今日收尾", "Closing the day", language)
    val counts = translate(
        "日记 {words} 字 · 小巧思 {ideas} 条 · 照片 {photos} 张 · 痕迹 {moments} 处",
        "Diary {words} words · {ideas} thoughts · {photos} photos · {moments} moments",
        language,
    )
        .replace("{words}", recap.diaryWords.toString())
        .replace("{ideas}", recap.ideaCount.toString())
        .replace("{photos}", recap.photoCount.toString())
        .replace("{moments}", recap.momentCount.toString())
    val span = recap.spanLabel(zone)?.let { label ->
        translate("记录时间 {span}", "Recorded {span}", language).replace("{span}", label)
    }
    return buildString {
        append("## ").append(heading).append(" · ").append(recap.date.toString()).append('\n')
        append('\n')
        append("- ").append(counts).append('\n')
        if (span != null) append("- ").append(span).append('\n')
    }
}
