package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderStateCodecTest {
    @Test
    fun schemaFourPersistsPdfZoomAndSchemaThreeUsesDefault() {
        val state = ReaderLibraryState(
            preferences = ReaderPreferences(pdfZoomPercent = 170),
        )
        assertEquals(
            170,
            ReaderStateCodec.decode(ReaderStateCodec.encode(state)).preferences.pdfZoomPercent,
        )

        val schemaThree = JSONObject(ReaderStateCodec.encode(state)).apply {
            put("schemaVersion", 3)
            getJSONObject("preferences").remove("pdfZoomPercent")
        }.toString()
        assertEquals(
            ReaderPreferences().pdfZoomPercent,
            ReaderStateCodec.decode(schemaThree).preferences.pdfZoomPercent,
        )
    }
}
