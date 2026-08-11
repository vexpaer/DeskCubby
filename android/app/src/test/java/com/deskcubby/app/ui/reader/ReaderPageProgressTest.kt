package com.deskcubby.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPageProgressTest {
    @Test
    fun pageOffsetIsFlooredToFivePercentCheckpoints() {
        assertEquals(0, quantizeReaderPageOffsetPercent(scrollOffsetPx = 0, itemSizePx = 1_000))
        assertEquals(0, quantizeReaderPageOffsetPercent(scrollOffsetPx = 49, itemSizePx = 1_000))
        assertEquals(5, quantizeReaderPageOffsetPercent(scrollOffsetPx = 50, itemSizePx = 1_000))
        assertEquals(60, quantizeReaderPageOffsetPercent(scrollOffsetPx = 649, itemSizePx = 1_000))
        assertEquals(65, quantizeReaderPageOffsetPercent(scrollOffsetPx = 650, itemSizePx = 1_000))
        assertEquals(65, quantizeReaderPageOffsetPercent(scrollOffsetPx = 699, itemSizePx = 1_000))
        assertEquals(95, quantizeReaderPageOffsetPercent(scrollOffsetPx = 999, itemSizePx = 1_000))
    }

    @Test
    fun pageOffsetIsBoundedForInvalidOrOverscrolledMeasurements() {
        assertEquals(0, quantizeReaderPageOffsetPercent(scrollOffsetPx = 500, itemSizePx = 0))
        assertEquals(0, quantizeReaderPageOffsetPercent(scrollOffsetPx = -1, itemSizePx = 1_000))
        assertEquals(95, quantizeReaderPageOffsetPercent(scrollOffsetPx = 1_000, itemSizePx = 1_000))
        assertEquals(95, quantizeReaderPageOffsetPercent(scrollOffsetPx = 1_500, itemSizePx = 1_000))
    }
}
