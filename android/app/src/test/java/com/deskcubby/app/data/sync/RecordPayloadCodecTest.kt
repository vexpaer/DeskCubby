package com.deskcubby.app.data.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordPayloadCodecTest {
    @Test
    fun gameSaveMayUseMoreThanGenericStringLimitWithinRecordPayload() {
        val save = "x".repeat(MAX_RECORD_STRING_CHARS + 100_000)
        val decoded = recordJson(
            recordPayload(
                JSONObject()
                    .put("gameId", "2048")
                    .put("saveJson", save),
            ),
        )

        assertEquals(save, decoded.optionalRecordString("saveJson"))
    }

    @Test
    fun legacyNestedGameSaveIsCanonicalizedToString() {
        val nested = JSONObject().put("board", JSONArray().put(2).put(4))
        val decoded = recordJson(
            recordPayload(
                JSONObject()
                    .put("gameId", "2048")
                    .put("saveJson", nested),
            ),
        )

        val restored = JSONObject(decoded.optionalRecordString("saveJson")!!)
        assertEquals(2, restored.getJSONArray("board").length())
    }
}
