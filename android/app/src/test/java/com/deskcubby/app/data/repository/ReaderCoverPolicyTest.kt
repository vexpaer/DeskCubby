package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCoverPolicyTest {
    @Test
    fun targetUsesMeasuredCardWidthAndHasAStrictUpperBound() {
        assertEquals(ReaderCoverDimensions(96, 138), readerCoverTargetSize(1))
        assertEquals(ReaderCoverDimensions(320, 458), readerCoverTargetSize(320))
        assertEquals(ReaderCoverDimensions(512, 732), readerCoverTargetSize(4_096))
    }

    @Test
    fun outputIsCroppedToTheShelfAspectAndNeverExceedsSourceWidth() {
        assertEquals(
            ReaderCoverDimensions(320, 458),
            readerCoverOutputSize(4_000, 12_000, 320),
        )
        assertEquals(
            ReaderCoverDimensions(80, 115),
            readerCoverOutputSize(80, 60, 320),
        )
    }

    @Test
    fun sampleSizeBoundsExtremeCoverDecodeBeforeAllocation() {
        val sample = readerCoverSampleSize(
            width = 100_000,
            height = 100_000,
            targetWidth = 512,
        )
        val decodedWidth = (100_000L + sample - 1L) / sample
        val decodedHeight = (100_000L + sample - 1L) / sample

        assertTrue(decodedWidth <= 1_024L)
        assertTrue(decodedWidth * decodedHeight <= 1_500_000L)
    }
}
