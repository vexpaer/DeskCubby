package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMetaLocationTest {
    @Test
    fun placeTakesPriorityOverCoordinates() {
        assertEquals(
            "上海市徐汇区",
            mediaMetaDisplayLocation(
                MediaMetaEntry(
                    latitude = 31.182,
                    longitude = 121.437,
                    place = "  上海市徐汇区  ",
                ),
            ),
        )
    }

    @Test
    fun validCoordinatesAreUsedWhenPlaceIsMissing() {
        assertEquals(
            "31.1820, 121.4370",
            mediaMetaDisplayLocation(
                MediaMetaEntry(latitude = 31.182, longitude = 121.437),
            ),
        )
    }

    @Test
    fun nonFiniteOrOutOfRangeCoordinatesAreNotDisplayed() {
        assertNull(
            mediaMetaDisplayLocation(
                MediaMetaEntry(latitude = Double.NaN, longitude = 121.437),
            ),
        )
        assertNull(
            mediaMetaDisplayLocation(
                MediaMetaEntry(latitude = 91.0, longitude = 121.437),
            ),
        )
        assertNull(
            mediaMetaDisplayLocation(
                MediaMetaEntry(latitude = 31.182, longitude = 181.0),
            ),
        )
    }
}
