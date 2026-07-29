package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCompressionPolicyTest {
    @Test
    fun keepsImagesAlreadyWithinMaximumEdge() {
        assertEquals(CompressedImageSize(1920, 1080), compressedImageSize(1920, 1080))
    }

    @Test
    fun scalesLandscapeAndPortraitImagesWithoutChangingAspectRatio() {
        assertEquals(CompressedImageSize(2560, 1707), compressedImageSize(6000, 4000))
        assertEquals(CompressedImageSize(1707, 2560), compressedImageSize(4000, 6000))
    }

    @Test
    fun choosesSafePowerOfTwoSampleForLargeImage() {
        val target = compressedImageSize(12_000, 9_000)

        assertEquals(CompressedImageSize(2560, 1920), target)
        assertEquals(4, imageSampleSize(12_000, 9_000, target))
    }

    @Test
    fun samplesCommonPhonePhotoBeforeDecoding() {
        val target = compressedImageSize(4_032, 3_024)

        assertEquals(2, imageSampleSize(4_032, 3_024, target))
    }

    @Test
    fun avoidsHundredMegabyteIntermediateBitmapNearTenThousandPixels() {
        val target = compressedImageSize(10_239, 10_239)

        assertEquals(4, imageSampleSize(10_239, 10_239, target))
    }
}
