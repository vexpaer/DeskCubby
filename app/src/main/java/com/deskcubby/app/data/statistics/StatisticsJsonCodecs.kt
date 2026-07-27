package com.deskcubby.app.data.statistics

import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class StatisticsJsonException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object UsageStatisticsJsonCodec {
    fun encode(history: UsageStatisticsHistory): String {
        validateUsageHistory(history)
        val root = JSONObject()
            .put(KEY_SCHEMA_VERSION, USAGE_STATISTICS_SCHEMA_VERSION)
            .put(KEY_TRACKING_STARTED_ON, history.trackingStartedOn?.toString() ?: JSONObject.NULL)
            .put(
                KEY_BACKFILL_COMPLETED_THROUGH,
                history.backfillCompletedThrough?.toString() ?: JSONObject.NULL,
            )
        val days = JSONArray()
        history.days.sortedBy(UsageStatisticsDay::date).forEach { day ->
            val apps = JSONArray()
            day.apps.sortedBy(UsageAppDuration::packageName).forEach { app ->
                apps.put(
                    JSONObject()
                        .put(KEY_PACKAGE_NAME, app.packageName)
                        .put(KEY_FOREGROUND_MILLIS, app.foregroundMillis),
                )
            }
            days.put(
                JSONObject()
                    .put(KEY_DATE, day.date.toString())
                    .put(KEY_ZONE_ID, day.zoneId)
                    .put(KEY_STATE, day.state.name)
                    .put(KEY_COLLECTED_AT, day.collectedAtEpochMillis)
                    .put(KEY_APPS, apps),
            )
        }
        root.put(KEY_DAYS, days)
        return encodeBounded(root)
    }

    fun decode(json: String): UsageStatisticsHistory = decodeBounded(json) { root ->
        val schemaVersion = root.requiredSchemaVersion(
            minimum = LEGACY_USAGE_STATISTICS_SCHEMA_VERSION,
            maximum = USAGE_STATISTICS_SCHEMA_VERSION,
        )
        when (schemaVersion) {
            LEGACY_USAGE_STATISTICS_SCHEMA_VERSION -> root.requireExactKeys(
                KEY_SCHEMA_VERSION,
                KEY_TRACKING_STARTED_ON,
                KEY_DAYS,
            )

            USAGE_STATISTICS_SCHEMA_VERSION -> root.requireExactKeys(
                KEY_SCHEMA_VERSION,
                KEY_TRACKING_STARTED_ON,
                KEY_BACKFILL_COMPLETED_THROUGH,
                KEY_DAYS,
            )

            else -> invalid("Unsupported usage statistics schema.")
        }
        val trackingStartedOn = root.requiredNullableDate(KEY_TRACKING_STARTED_ON)
        val backfillCompletedThrough = if (
            schemaVersion >= USAGE_STATISTICS_SCHEMA_VERSION
        ) {
            root.requiredNullableDate(KEY_BACKFILL_COMPLETED_THROUGH)
        } else {
            // Explicit v1 -> v2 migration. Existing days, especially FINAL
            // snapshots, are preserved and one bounded discovery scan remains
            // pending.
            null
        }
        val dayArray = root.requiredArray(KEY_DAYS, MAX_STATISTICS_DAYS)
        val days = buildList(dayArray.length()) {
            repeat(dayArray.length()) { index ->
                val day = dayArray.requiredObject(index)
                day.requireExactKeys(
                    KEY_DATE,
                    KEY_ZONE_ID,
                    KEY_STATE,
                    KEY_COLLECTED_AT,
                    KEY_APPS,
                )
                val appsArray = day.requiredArray(KEY_APPS, MAX_APPS_PER_DAY)
                val apps = buildList(appsArray.length()) {
                    repeat(appsArray.length()) { appIndex ->
                        val app = appsArray.requiredObject(appIndex)
                        app.requireExactKeys(KEY_PACKAGE_NAME, KEY_FOREGROUND_MILLIS)
                        add(
                            UsageAppDuration(
                                packageName = app.requiredString(
                                    KEY_PACKAGE_NAME,
                                    MAX_PACKAGE_NAME_CHARS,
                                ).also(::validatePackageName),
                                foregroundMillis = app.requiredLong(
                                    KEY_FOREGROUND_MILLIS,
                                    minimum = 0,
                                    maximum = MAX_FOREGROUND_MILLIS_PER_APP_DAY,
                                ),
                            ),
                        )
                    }
                }
                add(
                    UsageStatisticsDay(
                        date = day.requiredDate(KEY_DATE),
                        zoneId = day.requiredZoneId(KEY_ZONE_ID),
                        state = day.requiredDayState(KEY_STATE),
                        collectedAtEpochMillis = day.requiredLong(
                            KEY_COLLECTED_AT,
                            minimum = 0,
                            maximum = Long.MAX_VALUE,
                        ),
                        apps = apps,
                    ),
                )
            }
        }
        UsageStatisticsHistory(
            trackingStartedOn = trackingStartedOn,
            days = days,
            backfillCompletedThrough = backfillCompletedThrough,
        ).also(::validateUsageHistory)
    }
}

object StepStatisticsJsonCodec {
    fun encode(history: StepStatisticsHistory): String {
        validateStepHistory(history)
        val root = JSONObject()
            .put(KEY_SCHEMA_VERSION, STEP_STATISTICS_SCHEMA_VERSION)
            .put(KEY_TRACKING_STARTED_ON, history.trackingStartedOn?.toString() ?: JSONObject.NULL)
        val days = JSONArray()
        history.days.sortedBy(StepStatisticsDay::date).forEach { day ->
            days.put(
                JSONObject()
                    .put(KEY_DATE, day.date.toString())
                    .put(KEY_ZONE_ID, day.zoneId)
                    .put(KEY_STATE, day.state.name)
                    .put(KEY_COLLECTED_AT, day.collectedAtEpochMillis)
                    .put(KEY_STEPS, day.steps ?: JSONObject.NULL),
            )
        }
        root.put(KEY_DAYS, days)
        return encodeBounded(root)
    }

    fun decode(json: String): StepStatisticsHistory = decodeBounded(json) { root ->
        root.requireExactKeys(
            KEY_SCHEMA_VERSION,
            KEY_TRACKING_STARTED_ON,
            KEY_DAYS,
        )
        root.requiredSchemaVersion(
            minimum = STEP_STATISTICS_SCHEMA_VERSION,
            maximum = STEP_STATISTICS_SCHEMA_VERSION,
        )
        val trackingStartedOn = root.requiredNullableDate(KEY_TRACKING_STARTED_ON)
        val dayArray = root.requiredArray(KEY_DAYS, MAX_STATISTICS_DAYS)
        val days = buildList(dayArray.length()) {
            repeat(dayArray.length()) { index ->
                val day = dayArray.requiredObject(index)
                day.requireExactKeys(
                    KEY_DATE,
                    KEY_ZONE_ID,
                    KEY_STATE,
                    KEY_COLLECTED_AT,
                    KEY_STEPS,
                )
                val steps = if (day.isNull(KEY_STEPS)) {
                    null
                } else {
                    day.requiredLong(KEY_STEPS, minimum = 0, maximum = MAX_STEPS_PER_DAY)
                }
                add(
                    StepStatisticsDay(
                        date = day.requiredDate(KEY_DATE),
                        zoneId = day.requiredZoneId(KEY_ZONE_ID),
                        state = day.requiredDayState(KEY_STATE),
                        collectedAtEpochMillis = day.requiredLong(
                            KEY_COLLECTED_AT,
                            minimum = 0,
                            maximum = Long.MAX_VALUE,
                        ),
                        steps = steps,
                    ),
                )
            }
        }
        StepStatisticsHistory(trackingStartedOn, days).also(::validateStepHistory)
    }
}

private inline fun <T> decodeBounded(json: String, decode: (JSONObject) -> T): T {
    val bytes = json.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > MAX_STATISTICS_JSON_BYTES) {
        throw StatisticsJsonException("Statistics JSON exceeds $MAX_STATISTICS_JSON_BYTES bytes.")
    }
    return try {
        decode(JSONObject(json))
    } catch (error: StatisticsJsonException) {
        throw error
    } catch (error: JSONException) {
        throw StatisticsJsonException("Malformed statistics JSON.", error)
    } catch (error: ArithmeticException) {
        throw StatisticsJsonException("Statistics JSON contains an overflowing number.", error)
    }
}

private fun encodeBounded(root: JSONObject): String {
    val encoded = root.toString()
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_STATISTICS_JSON_BYTES) {
        throw StatisticsJsonException("Statistics JSON exceeds $MAX_STATISTICS_JSON_BYTES bytes.")
    }
    return encoded
}

private fun validateUsageHistory(history: UsageStatisticsHistory) {
    validateDates(history.trackingStartedOn, history.days.map(UsageStatisticsDay::date))
    history.days.forEach { day ->
        validateZoneId(day.zoneId)
        if (day.collectedAtEpochMillis < 0) invalid("collectedAt must be non-negative.")
        if (day.apps.size > MAX_APPS_PER_DAY) invalid("Too many apps in one statistics day.")
        val packageNames = HashSet<String>(day.apps.size)
        day.apps.forEach { app ->
            validatePackageName(app.packageName)
            if (!packageNames.add(app.packageName)) invalid("Duplicate package in ${day.date}.")
            if (app.foregroundMillis !in 0..MAX_FOREGROUND_MILLIS_PER_APP_DAY) {
                invalid("Foreground duration is outside the accepted daily range.")
            }
        }
    }
}

private fun validateStepHistory(history: StepStatisticsHistory) {
    validateDates(history.trackingStartedOn, history.days.map(StepStatisticsDay::date))
    history.days.forEach { day ->
        validateZoneId(day.zoneId)
        if (day.collectedAtEpochMillis < 0) invalid("collectedAt must be non-negative.")
        if (day.steps != null && day.steps !in 0..MAX_STEPS_PER_DAY) {
            invalid("Step count is outside the accepted daily range.")
        }
    }
}

private fun validateDates(trackingStartedOn: LocalDate?, dates: List<LocalDate>) {
    if (dates.size > MAX_STATISTICS_DAYS) invalid("Too many statistics days.")
    if (dates.toSet().size != dates.size) invalid("Duplicate statistics date.")
    if (trackingStartedOn == null && dates.isNotEmpty()) {
        invalid("trackingStartedOn is required when statistics days exist.")
    }
    if (trackingStartedOn != null && dates.any { it.isBefore(trackingStartedOn) }) {
        invalid("A statistics day predates trackingStartedOn.")
    }
}

private fun validatePackageName(value: String) {
    if (
        value.isBlank() ||
        value.length > MAX_PACKAGE_NAME_CHARS ||
        value.any { it.isISOControl() || it.isWhitespace() }
    ) {
        invalid("Invalid package name.")
    }
}

private fun validateZoneId(value: String) {
    if (value.isBlank() || value.length > MAX_ZONE_ID_CHARS) invalid("Invalid time zone.")
    try {
        ZoneId.of(value)
    } catch (error: DateTimeException) {
        throw StatisticsJsonException("Invalid time zone.", error)
    }
}

private fun JSONObject.requiredSchemaVersion(
    minimum: Int,
    maximum: Int,
): Int {
    val version = requiredLong(
        KEY_SCHEMA_VERSION,
        minimum = minimum.toLong(),
        maximum = maximum.toLong(),
    )
    return version.toInt()
}

private fun JSONObject.requireExactKeys(vararg expected: String) {
    val expectedSet = expected.toSet()
    val actual = keys().asSequence().toSet()
    if (actual != expectedSet) {
        invalid("Unexpected or missing JSON fields: ${(actual union expectedSet) - (actual intersect expectedSet)}")
    }
}

private fun JSONObject.requiredArray(key: String, maxEntries: Int): JSONArray {
    if (!has(key) || isNull(key)) invalid("$key must be an array.")
    val value = opt(key)
    if (value !is JSONArray) invalid("$key must be an array.")
    if (value.length() > maxEntries) invalid("$key contains too many entries.")
    return value
}

private fun JSONArray.requiredObject(index: Int): JSONObject {
    val value = opt(index)
    if (value !is JSONObject) invalid("Array item $index must be an object.")
    return value
}

private fun JSONObject.requiredString(key: String, maxChars: Int): String {
    if (!has(key) || isNull(key) || opt(key) !is String) invalid("$key must be a string.")
    return getString(key).also {
        if (it.length > maxChars) invalid("$key is too long.")
    }
}

private fun JSONObject.requiredLong(key: String, minimum: Long, maximum: Long): Long {
    if (!has(key) || isNull(key)) invalid("$key must be an integer.")
    val value = opt(key)
    val number = when (value) {
        is Byte, is Short, is Int, is Long -> (value as Number).toLong()
        else -> invalid("$key must be an integer.")
    }
    if (number !in minimum..maximum) invalid("$key is outside the accepted range.")
    return number
}

private fun JSONObject.requiredDate(key: String): LocalDate {
    val text = requiredString(key, MAX_DATE_CHARS)
    return try {
        LocalDate.parse(text).also {
            if (it.toString() != text) invalid("$key must use ISO-8601 yyyy-MM-dd.")
        }
    } catch (error: DateTimeException) {
        throw StatisticsJsonException("$key is not a valid date.", error)
    }
}

private fun JSONObject.requiredNullableDate(key: String): LocalDate? {
    if (!has(key)) invalid("$key is missing.")
    return if (isNull(key)) null else requiredDate(key)
}

private fun JSONObject.requiredZoneId(key: String): String =
    requiredString(key, MAX_ZONE_ID_CHARS).also(::validateZoneId)

private fun JSONObject.requiredDayState(key: String): StatisticsDayState {
    val value = requiredString(key, MAX_STATE_CHARS)
    return StatisticsDayState.entries.firstOrNull { it.name == value }
        ?: invalid("Unsupported day state.")
}

private fun invalid(message: String): Nothing = throw StatisticsJsonException(message)

internal const val LEGACY_USAGE_STATISTICS_SCHEMA_VERSION = 1
internal const val USAGE_STATISTICS_SCHEMA_VERSION = 2
internal const val STEP_STATISTICS_SCHEMA_VERSION = 1
internal const val MAX_STATISTICS_JSON_BYTES = 10 * 1024 * 1024
private const val MAX_STATISTICS_DAYS = 36_600
private const val MAX_APPS_PER_DAY = 4_096
private const val MAX_PACKAGE_NAME_CHARS = 255
private const val MAX_ZONE_ID_CHARS = 128
private const val MAX_DATE_CHARS = 10
private const val MAX_STATE_CHARS = 16
private const val MAX_FOREGROUND_MILLIS_PER_APP_DAY = 26L * 60L * 60L * 1_000L
private const val MAX_STEPS_PER_DAY = 1_000_000L

private const val KEY_SCHEMA_VERSION = "schemaVersion"
private const val KEY_TRACKING_STARTED_ON = "trackingStartedOn"
private const val KEY_BACKFILL_COMPLETED_THROUGH = "backfillCompletedThrough"
private const val KEY_DAYS = "days"
private const val KEY_DATE = "date"
private const val KEY_ZONE_ID = "zoneId"
private const val KEY_STATE = "state"
private const val KEY_COLLECTED_AT = "collectedAtEpochMillis"
private const val KEY_APPS = "apps"
private const val KEY_PACKAGE_NAME = "packageName"
private const val KEY_FOREGROUND_MILLIS = "foregroundMillis"
private const val KEY_STEPS = "steps"
