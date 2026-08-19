package com.deskcubby.app.data.structuredrecords

import java.time.LocalTime
import kotlin.math.roundToLong

/**
 * Per-type value normalization. Each [StructuredFieldType] has a distinct semantics set — not a
 * shared "everything is a string" behavior — so validation, canonical form, and allowed
 * aggregations are decided once here and reused by the index, statistics, UI and Agent.
 */
sealed interface NormalizedFieldValue {

    /** Canonical display string that survives round-trip into Markdown. */
    val displayText: String

    data class Word(val text: String) : NormalizedFieldValue {
        override val displayText: String get() = text
    }

    data class Number(val value: Double) : NormalizedFieldValue {
        override val displayText: String get() = StructuredFieldNormalizer.formatNumber(value)
    }

    data class Category(val value: String) : NormalizedFieldValue {
        override val displayText: String get() = value
    }

    /** A moment-in-day; canonical [LocalTime] plus the original `HH:mm` text for display. */
    data class Time(val time: LocalTime, val rawText: String) : NormalizedFieldValue {
        override val displayText: String get() = JournalDayEngine.formatTime(time)
    }

    /** A duration stored as total whole seconds. */
    data class Duration(val seconds: Long) : NormalizedFieldValue {
        override val displayText: String get() = StructuredFieldNormalizer.formatDuration(seconds)
    }

    sealed interface ValueKind {
        data object Word : ValueKind
        data object Number : ValueKind
        data object Category : ValueKind
        data object Time : ValueKind
        data object Duration : ValueKind
    }
}

data class NormalizationResult(
    val value: NormalizedFieldValue?,
    val error: String? = null,
) {
    val isError: Boolean get() = error != null
}

/**
 * Normalizes a raw user string into its typed canonical value, validating per [StructuredFieldType].
 * Returns [NormalizationResult.error] for invalid input without throwing, so callers always have a
 * safe path. Where a valid value still leaves an empty result, [value] is null but no error is set
 * (the caller decides whether to ignore).
 */
object StructuredFieldNormalizer {

    fun normalize(fieldType: StructuredFieldType, raw: String): NormalizationResult {
        val text = raw.trim()
        if (text.isEmpty()) return NormalizationResult(null)
        // A value must never smuggle the protocol's own marker tokens into the document: the parser
        // would truncate the value at an embedded open marker and re-emit the tail as a close marker
        // that shadows real data — silently losing user text.
        if (text.contains("<!--") || text.contains("-->")) {
            return NormalizationResult(null, error = "内容包含保留标记 <!-- 与 -->")
        }
        return when (fieldType) {
            StructuredFieldType.WORD -> NormalizationResult(NormalizedFieldValue.Word(text))
            StructuredFieldType.NUMBER -> normalizeNumber(text)
            StructuredFieldType.TYPE -> NormalizationResult(NormalizedFieldValue.Category(text))
            StructuredFieldType.TIME -> normalizeTime(text)
            StructuredFieldType.DURATION -> normalizeDuration(text)
        }
    }

    /** Per-type selector whitelist for same-day multi-value aggregation. */
    fun allowedSelectors(fieldType: StructuredFieldType): Set<FieldSelector> = when (fieldType) {
        StructuredFieldType.TIME -> setOf(FieldSelector.FIRST, FieldSelector.LAST, FieldSelector.MIN, FieldSelector.MAX)
        StructuredFieldType.NUMBER -> setOf(
            FieldSelector.FIRST,
            FieldSelector.LAST,
            FieldSelector.MIN,
            FieldSelector.MAX,
            FieldSelector.SUM,
            FieldSelector.AVERAGE,
            FieldSelector.COUNT,
        )
        StructuredFieldType.DURATION -> setOf(
            FieldSelector.FIRST,
            FieldSelector.LAST,
            FieldSelector.MIN,
            FieldSelector.MAX,
            FieldSelector.SUM,
            FieldSelector.AVERAGE,
            FieldSelector.COUNT,
        )
        StructuredFieldType.TYPE -> setOf(FieldSelector.FIRST, FieldSelector.LAST, FieldSelector.COUNT)
        StructuredFieldType.WORD -> setOf(FieldSelector.FIRST, FieldSelector.LAST, FieldSelector.COUNT)
    }

    fun defaultSelector(fieldType: StructuredFieldType): FieldSelector = FieldSelector.LAST

    private fun normalizeNumber(text: String): NormalizationResult {
        // Accept an optional numeric prefix followed by a unit ("30 次", "5.2 km"), a decimal comma
        // or point, and optional sign. The unit must not itself start with a digit/dot/comma or
        // contain digits, so ambiguous tails like "1.2.3" or "12abc34" are rejected instead of
        // silently truncated to the numeric prefix.
        val match = NUMBER_PATTERN.matchEntire(text.trim()) ?: return NormalizationResult(
            null,
            error = "数值无效",
        )
        val numeric = match.groupValues[1].replace(',', '.').replace('，', '.').replace("−", "-")
        val value = numeric.toDoubleOrNull() ?: return NormalizationResult(null, error = "数值无效")
        if (!value.isFinite()) return NormalizationResult(null, error = "数值无效")
        return NormalizationResult(NormalizedFieldValue.Number(value))
    }

    private fun normalizeTime(text: String): NormalizationResult {
        // Accept HH:mm, HH:mm:ss, and single-digit hour forms.
        val match = TIME_PATTERN.matchEntire(text) ?: return NormalizationResult(
            null,
            error = "时间格式应为 HH:mm",
        )
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return NormalizationResult(null, error = "时间无效")
        // HH:mm:ss is accepted for entry but canonicalized to HH:mm; still validate the seconds the
        // user did type so "23:59:99" is rejected rather than silently coerced.
        val secondText = match.groupValues[3]
        if (secondText.isNotEmpty() && secondText.toInt() > 59) {
            return NormalizationResult(null, error = "时间无效")
        }
        return NormalizationResult(NormalizedFieldValue.Time(LocalTime.of(hour, minute), text))
    }

    private fun normalizeDuration(text: String): NormalizationResult {
        val clean = text.trim().lowercase()
        // A plain integer is interpreted as seconds.
        clean.toLongOrNull()?.let { seconds ->
            return NormalizationResult(NormalizedFieldValue.Duration(seconds.coerceAtLeast(0L)))
        }
        // H:MM (hours:minutes) — the canonical duration input, e.g. 00:42 or 7:35.
        val colon = Regex("""^(\d{1,3}):(\d{1,2})$""").matchEntire(clean)
        if (colon != null) {
            val hours = colon.groupValues[1].toLong()
            val minutes = colon.groupValues[2].toLong()
            if (minutes > 59) return NormalizationResult(null, error = "时长分钟数无效")
            return NormalizationResult(NormalizedFieldValue.Duration(hours * 3600 + minutes * 60))
        }
        val hours = Regex("""^(\d+(?:\.\d+)?)\s*h$""").matchEntire(clean)
        if (hours != null) {
            return NormalizationResult(NormalizedFieldValue.Duration((hours.groupValues[1].toDouble() * 3600).toLong()))
        }
        val minutes = Regex("""^(\d+(?:\.\d+)?)\s*(?:m|min|分钟|分)?$""").matchEntire(clean)
        if (minutes != null) {
            return NormalizationResult(NormalizedFieldValue.Duration((minutes.groupValues[1].toDouble() * 60).toLong()))
        }
        // Combined "1h30m" / "1小时30分"
        val combined = Regex("""^(?:(\d+)\s*(?:h|小时)?\s*)?(?:(\d+)\s*(?:m|分钟|分))?$""").matchEntire(clean)
        if (combined != null && (combined.groupValues[1].isNotEmpty() || combined.groupValues[2].isNotEmpty())) {
            val h = combined.groupValues[1].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
            val m = combined.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
            return NormalizationResult(NormalizedFieldValue.Duration(h * 3600 + m * 60))
        }
        return NormalizationResult(null, error = "时长格式无效")
    }

    fun formatNumber(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        val rounded = (value * 100).roundToLong() / 100.0
        if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
        return rounded.toString()
    }

    fun formatDuration(seconds: Long): String {
        // Canonical display is H:MM (hours:minutes), matching the design's examples like 00:42 and 07:35.
        val total = seconds.coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val minutes = m + if (s >= 30) 1 else 0
        val hour = h + minutes / 60
        val minute = minutes % 60
        return if (hour > 0) "%d:%02d".format(hour, minute) else "0:%02d".format(minute)
    }

    private val TIME_PATTERN = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?$""")
    private val NUMBER_PATTERN = Regex("""^([+-]?\d+(?:[.,，]\d+)?)\s*([^\d.,，]+)?$""")
}
