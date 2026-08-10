package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun schemaSixPersistsGridTitleVisibilityAndSchemaFiveKeepsTitlesVisible() {
        val state = ReaderLibraryState(
            preferences = ReaderPreferences(
                libraryLayout = ReaderLibraryLayout.GRID,
                showGridBookTitles = false,
            ),
        )
        val encoded = ReaderStateCodec.encode(state)
        assertFalse(ReaderStateCodec.decode(encoded).preferences.showGridBookTitles)

        val schemaFive = JSONObject(encoded).apply {
            put("schemaVersion", 5)
            getJSONObject("preferences").remove("showGridBookTitles")
        }.toString()
        assertTrue(ReaderStateCodec.decode(schemaFive).preferences.showGridBookTitles)
    }
}
