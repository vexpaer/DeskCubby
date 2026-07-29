package com.deskcubby.app.ui.diary.filter

import com.deskcubby.app.data.model.MealPhotoFilterSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPhotoFilterTest {
    @Test
    fun defaultSettings_createIdentityMatrix() {
        assertArrayEquals(IDENTITY, mealPhotoFilterMatrix(MealPhotoFilterSettings()), EPSILON)
    }

    @Test
    fun brightness_addsSameTranslationToRgbAndLeavesAlphaAlone() {
        val matrix = mealPhotoFilterMatrix(
            MealPhotoFilterSettings(brightness = 0.2f),
        )

        assertEquals(51f, matrix[4], EPSILON)
        assertEquals(51f, matrix[9], EPSILON)
        assertEquals(51f, matrix[14], EPSILON)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), matrix.copyOfRange(15, 20), EPSILON)
    }

    @Test
    fun zeroSaturation_mapsRgbChannelsToTheSameLuminance() {
        val matrix = mealPhotoFilterMatrix(
            MealPhotoFilterSettings(saturation = 0f),
        )

        assertArrayEquals(matrix.copyOfRange(0, 3), matrix.copyOfRange(5, 8), EPSILON)
        assertArrayEquals(matrix.copyOfRange(0, 3), matrix.copyOfRange(10, 13), EPSILON)
        assertEquals(1f, matrix[0] + matrix[1] + matrix[2], EPSILON)
    }

    @Test
    fun contrast_keepsMidGrayAtTheStandard128Pivot() {
        val matrix = mealPhotoFilterMatrix(
            MealPhotoFilterSettings(contrast = 1.6f),
        )

        val output = applyToRgb(matrix, 128f, 128f, 128f)

        assertArrayEquals(floatArrayOf(128f, 128f, 128f), output, EPSILON)
    }

    @Test
    fun extremeAndInvalidValues_alwaysProduceFiniteMatrix() {
        val matrix = mealPhotoFilterMatrix(
            MealPhotoFilterSettings(
                brightness = Float.NEGATIVE_INFINITY,
                contrast = Float.NaN,
                saturation = 99f,
                warmth = -99f,
                tint = 99f,
            ),
        )

        assertEquals(20, matrix.size)
        assertTrue(matrix.all(Float::isFinite))
    }

    companion object {
        private const val EPSILON = 0.0001f
        private val IDENTITY = floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}

private fun applyToRgb(matrix: FloatArray, red: Float, green: Float, blue: Float): FloatArray =
    FloatArray(3) { row ->
        val start = row * 5
        matrix[start] * red +
            matrix[start + 1] * green +
            matrix[start + 2] * blue +
            matrix[start + 3] * 255f +
            matrix[start + 4]
    }
