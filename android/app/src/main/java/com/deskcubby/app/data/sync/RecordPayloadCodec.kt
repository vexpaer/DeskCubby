package com.deskcubby.app.data.sync

import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal fun recordPayload(obj: JSONObject): ByteArray = obj.toString().toByteArray(StandardCharsets.UTF_8)

internal fun recordJson(bytes: ByteArray): JSONObject {
    require(bytes.isNotEmpty() && bytes.size <= MAX_RECORD_PAYLOAD_BYTES) { "记录 payload 大小无效。" }
    return JSONObject(bytes.toString(StandardCharsets.UTF_8))
}

internal fun JSONObject.requiredRecordString(key: String): String =
    getString(key).also { require(it.length <= MAX_RECORD_STRING_CHARS) }

internal fun JSONObject.optionalRecordString(key: String): String? =
    if (!has(key) || isNull(key)) null else requiredRecordString(key)

internal fun JSONObject.requiredRecordLong(key: String): Long =
    getLong(key).also { require(it >= 0L) }

internal fun JSONObject.requiredRecordInt(key: String): Int =
    getInt(key)

internal fun JSONObject.requiredRecordBoolean(key: String): Boolean = getBoolean(key)

internal fun JSONObject.putRecord(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

internal const val MAX_RECORD_PAYLOAD_BYTES = 4 * 1024 * 1024
internal const val MAX_RECORD_STRING_CHARS = 1_000_000
