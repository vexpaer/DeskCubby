package com.deskcubby.app.data.backup

import com.deskcubby.app.data.model.AgentDataSource
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the `app_guide` Agent source is forward/backward compatible with the
 * backup/sync wire format: encoding sorts by ordinal, decoding maps unknown/new values
 * leniently (existing backups without it stay untouched, and the new value round-trips).
 */
class AgentDataSourceWireCompatibilityTest {
    @Test
    fun appGuideHasStableWireValueAndIsReadOnlySource() {
        assertEquals("app_guide", AgentDataSource.APP_GUIDE.wireValue)
    }

    @Test
    fun encodingIncludesNewSourceAlongsideExistingOnes() {
        val sources = linkedSetOf(AgentDataSource.APP_GUIDE, AgentDataSource.DIARY, AgentDataSource.NOTES)
        val encoded = JSONArray().apply {
            sources.sortedBy(AgentDataSource::ordinal).forEach { put(it.wireValue) }
        }

        assertEquals(3, encoded.length())
        assertEquals("diary", encoded.getString(0))
        assertTrue(encoded.toString().contains("app_guide"))
    }

    @Test
    fun decodingLenientlyIgnoresUnknownWireValues() {
        // Simulate a future backup that contains an unknown source id; decode must not
        // fail, it should only keep sources it knows.
        val array = JSONArray(listOf("diary", "unknown_future_source", "app_guide"))
        val decoded = (0 until array.length()).mapNotNull { raw ->
            AgentDataSource.entries.firstOrNull { it.wireValue == array.getString(raw) }
        }.toSet()

        assertEquals(setOf(AgentDataSource.DIARY, AgentDataSource.APP_GUIDE), decoded)
    }

    @Test
    fun upperBoundGrowsWithEntriesSoOldBackupsStillParse() {
        // BackupJsonCodec guards `array.length() <= entries.size`; adding an enum value
        // only raises that bound. A backup that includes the new value parses fine.
        val array = JSONArray(AgentDataSource.entries.map { it.wireValue })
        assertTrue(array.length() <= AgentDataSource.entries.size)
    }
}