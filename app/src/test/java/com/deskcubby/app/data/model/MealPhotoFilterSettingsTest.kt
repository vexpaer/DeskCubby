package com.deskcubby.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPhotoFilterSettingsTest {
    @Test
    fun normalized_clampsPersistedValuesAndReplacesNonFiniteNumbers() {
        val normalized = MealPhotoFilterSettings(
            enabled = true,
            brightness = Float.NaN,
            contrast = -4f,
            saturation = Float.POSITIVE_INFINITY,
            warmth = 9f,
            tint = -9f,
        ).normalized()

        assertTrue(normalized.enabled)
        assertEquals(MealPhotoFilterSettings.DEFAULT_BRIGHTNESS, normalized.brightness, 0f)
        assertEquals(MealPhotoFilterSettings.MIN_CONTRAST, normalized.contrast, 0f)
        assertEquals(MealPhotoFilterSettings.DEFAULT_SATURATION, normalized.saturation, 0f)
        assertEquals(MealPhotoFilterSettings.MAX_WARMTH, normalized.warmth, 0f)
        assertEquals(MealPhotoFilterSettings.MIN_TINT, normalized.tint, 0f)
    }

    @Test
    fun hasVisibleAdjustment_ignoresMasterSwitch() {
        assertFalse(MealPhotoFilterSettings(enabled = true).hasVisibleAdjustment())
        assertTrue(MealPhotoFilterSettings(enabled = false, saturation = 0.8f).hasVisibleAdjustment())
    }
}
