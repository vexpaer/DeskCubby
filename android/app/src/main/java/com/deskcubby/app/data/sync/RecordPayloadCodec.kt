package com.deskcubby.app.data.sync

import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal fun recordPayload(obj: JSONObject): ByteArray =
    obj.toString().toByteArray(StandardCharsets.UTF_8).also { bytes ->
        if (bytes.isEmpty() || bytes.size > MAX_RECORD_PAYLOAD_BYTES) {
            throw CloudSyncLimitException(
                "记录同步数据超过单条载荷上限。 / Record sync payload exceeds the per-record limit.",
            )
        }
    }

internal fun recordJson(bytes: ByteArray): JSONObject {
    require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_PAYLOAD_BYTES) { "记录 payload 大小无效。" }
    return JSONObject(bytes.toString(StandardCharsets.UTF_8))
}

internal fun JSONObject.requiredRecordString(key: String): String =
    getString(key).also { require(it.length <= MAX_RECORD_STRING_CHARS) }

internal fun JSONObject.optionalRecordString(key: String): String? =
    if (!has(key) || isNull(key)) {
        null
    } else if (key == GAME_SAVE_JSON_FIELD) {
        optionalRecordPayloadString(key)
    } else {
        requiredRecordString(key)
    }

/**
 * Opaque JSON-backed record fields such as game saveJson may legitimately occupy most of a record
 * payload. They therefore use the payload byte limit instead of the generic one-million-character
 * metadata/string guard. Old builds could also leave saveJson as nested JSON; canonicalize that
 * representation back to a String so a second device can restore it without SYNC_UNEXPECTED.
 */
internal fun JSONObject.optionalRecordPayloadString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = when (val raw = get(key)) {
        is String -> raw
        is JSONObject, is JSONArray -> raw.toString()
        else -> throw IllegalArgumentException("Record field $key must be a string or JSON value")
    }
    require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_RECORD_PAYLOAD_BYTES) {
        "Record field $key exceeds the payload limit"
    }
    return value
}

internal fun JSONObject.requiredRecordLong(key: String): Long =
    getLong(key).also { require(it >= 0L) }

internal fun JSONObject.requiredRecordInt(key: String): Int =
    getInt(key)

internal fun JSONObject.requiredRecordBoolean(key: String): Boolean = getBoolean(key)

internal fun JSONObject.putRecord(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private const val GAME_SAVE_JSON_FIELD = "saveJson"
internal const val MAX_RECORD_PAYLOAD_BYTES = 4 * 1024 * 1024
internal const val MAX_RECORD_STRING_CHARS = 1_000_000
