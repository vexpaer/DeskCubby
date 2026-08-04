package com.deskcubby.app.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVisualizerTest {
    @Test
    fun captureRequiresFeatureForegroundPermissionAndSystemMotion() {
        assertTrue(musicVisualizerCaptureAllowed(true, true, true, true))
        assertFalse(musicVisualizerCaptureAllowed(false, true, true, true))
        assertFalse(musicVisualizerCaptureAllowed(true, false, true, true))
        assertFalse(musicVisualizerCaptureAllowed(true, true, false, true))
        assertFalse(musicVisualizerCaptureAllowed(true, true, true, false))
    }

    @Test
    fun captureSizeIsPowerOfTwoInsideDeviceRange() {
        assertEquals(1_024, preferredVisualizerCaptureSize(intArrayOf(128, 2_048)))
        assertEquals(512, preferredVisualizerCaptureSize(intArrayOf(128, 800)))
        assertEquals(256, preferredVisualizerCaptureSize(intArrayOf(256, 256)))
        assertNull(preferredVisualizerCaptureSize(intArrayOf(600, 800)))
        assertNull(preferredVisualizerCaptureSize(intArrayOf(1_024, 128)))
        assertNull(preferredVisualizerCaptureSize(intArrayOf(128)))
    }

    @Test
    fun waveformCentersUnsignedPcmAndSamplesBothEndpoints() {
        val bytes = ByteArray(SAMPLE_COUNT) { 128.toByte() }
        bytes[0] = 0
        bytes[bytes.lastIndex] = 255.toByte()

        val values = normalizeWaveform(bytes)

        assertEquals(-1f, values.first(), 0.0001f)
        assertEquals(127f / 128f, values.last(), 0.0001f)
        assertTrue(hasVisibleMusicSignal(values))
    }

    @Test
    fun silentWaveformAndFftDoNotProduceAVisibleIdleAnimation() {
        val waveform = normalizeWaveform(ByteArray(256) { 128.toByte() })
        val fft = normalizeFft(ByteArray(256))

        assertArrayEquals(FloatArray(SAMPLE_COUNT), waveform, 0f)
        assertArrayEquals(FloatArray(SAMPLE_COUNT), fft, 0f)
        assertFalse(hasVisibleMusicSignal(waveform))
        assertFalse(hasVisibleMusicSignal(fft))
    }

    @Test
    fun fftValuesStayFiniteAndNormalized() {
        val fft = ByteArray(128)
        fft[2] = 127
        fft[3] = 127
        fft[40] = 64
        fft[41] = 32

        val values = normalizeFft(fft)

        assertEquals(SAMPLE_COUNT, values.size)
        assertTrue(values.any { it > 0.9f })
        assertTrue(values.all { it.isFinite() && it in 0f..1f })
    }
}
