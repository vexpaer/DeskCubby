package com.deskcubby.app.ui.components

import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
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

    @Test
    fun manualFrequencyRangeRejectsBinsOutsideSelectedBand() {
        val fft = ByteArray(1_024).apply {
            setFftBin(2, 127)
            setFftBin(213, 127)
        }
        val processor = MusicSpectrumProcessor(
            frequencyMode = MusicVisualizerFrequencyMode.MANUAL,
            minFrequencyHz = 800,
            maxFrequencyHz = 1_200,
        )

        assertArrayEquals(
            FloatArray(SAMPLE_COUNT),
            processor.process(fft, TEST_SAMPLE_RATE_MILLI_HERTZ),
            0f,
        )

        fft.setFftBin(21, 100)
        val inRange = processor.process(fft, TEST_SAMPLE_RATE_MILLI_HERTZ)
        assertTrue(inRange.any { it > 0.5f })
    }

    @Test
    fun invalidManualBoundsFallBackToSafeDefaultsAndDeviceNyquist() {
        val defaults = manualSpectrumFrequencyRange(
            minFrequencyHz = 5_000,
            maxFrequencyHz = 100,
            availableMaxHz = 23_953.125f,
        )
        val deviceLimited = manualSpectrumFrequencyRange(
            minFrequencyHz = 60,
            maxFrequencyHz = 16_000,
            availableMaxHz = 8_000f,
        )

        assertEquals(60f, defaults?.minHz ?: 0f, 0.001f)
        assertEquals(16_000f, defaults?.maxHz ?: 0f, 0.001f)
        assertEquals(60f, deviceLimited?.minHz ?: 0f, 0.001f)
        assertEquals(8_000f, deviceLimited?.maxHz ?: 0f, 0.001f)
    }

    @Test
    fun manualRangeWithNoDeviceBinDrawsNothingInsteadOfLeakingNearestBin() {
        val fft = ByteArray(1_024).apply { setFftBin(1, 127) }
        val belowFirstBin = MusicSpectrumProcessor(
            frequencyMode = MusicVisualizerFrequencyMode.MANUAL,
            minFrequencyHz = 20,
            maxFrequencyHz = 30,
        )
        val justAboveLowerEdge = MusicSpectrumProcessor(
            frequencyMode = MusicVisualizerFrequencyMode.MANUAL,
            minFrequencyHz = 800,
            maxFrequencyHz = 1_200,
        )

        assertArrayEquals(
            FloatArray(SAMPLE_COUNT),
            belowFirstBin.process(fft, TEST_SAMPLE_RATE_MILLI_HERTZ),
            0f,
        )
        fft.fill(0)
        fft.setFftBin(17, 127) // 796.875 Hz is below the requested 800 Hz lower edge.
        assertArrayEquals(
            FloatArray(SAMPLE_COUNT),
            justAboveLowerEdge.process(fft, TEST_SAMPLE_RATE_MILLI_HERTZ),
            0f,
        )
    }

    @Test
    fun manualRangeEntirelyAboveDeviceSpectrumDoesNotFallBackToOtherFrequencies() {
        assertNull(
            manualSpectrumFrequencyRange(
                minFrequencyHz = 16_000,
                maxFrequencyHz = 20_000,
                availableMaxHz = 8_000f,
            ),
        )
    }

    @Test
    fun adaptiveRangeKeepsQuietHarmonicsAndSpreadsActiveBand() {
        val magnitudes = FloatArray(511).apply {
            for (index in 1..5) this[index] = 0.9f
            this[40] = 0.35f
        }
        val detected = detectAdaptiveFrequencyRange(
            magnitudes = magnitudes,
            binWidthHz = TEST_BIN_WIDTH_HZ,
            availableMaxHz = magnitudes.size * TEST_BIN_WIDTH_HZ,
        )

        requireNotNull(detected)
        assertTrue(detected.minHz < 100f)
        assertTrue(detected.maxHz > 1_900f)

        val fft = ByteArray(1_024).apply {
            for (bin in 20..40) setFftBin(bin, 100)
        }
        val values = MusicSpectrumProcessor(
            frequencyMode = MusicVisualizerFrequencyMode.ADAPTIVE,
            minFrequencyHz = 60,
            maxFrequencyHz = 16_000,
        ).process(fft, TEST_SAMPLE_RATE_MILLI_HERTZ)
        val activeIndices = values.indices.filter { values[it] > 0f }

        assertTrue(activeIndices.first() < SAMPLE_COUNT / 2)
        assertTrue(activeIndices.last() > SAMPLE_COUNT / 2)
    }

    @Test
    fun adaptiveRangeExpandsQuicklyAndContractsSlowly() {
        val previous = SpectrumFrequencyRange(minHz = 100f, maxHz = 1_000f)
        val expanded = smoothAdaptiveFrequencyRange(
            previous = previous,
            target = SpectrumFrequencyRange(minHz = 40f, maxHz = 5_000f),
            availableMaxHz = 20_000f,
        )
        val contracted = smoothAdaptiveFrequencyRange(
            previous = previous,
            target = SpectrumFrequencyRange(minHz = 400f, maxHz = 500f),
            availableMaxHz = 20_000f,
        )

        assertTrue(expanded.minHz < 80f)
        assertTrue(expanded.maxHz > 2_500f)
        assertTrue(contracted.minHz in 100f..150f)
        assertTrue(contracted.maxHz in 900f..1_000f)
    }

    @Test
    fun silentFrameKeepsLastAdaptiveRangeAndDrawsNothing() {
        val processor = MusicSpectrumProcessor(
            frequencyMode = MusicVisualizerFrequencyMode.ADAPTIVE,
            minFrequencyHz = 60,
            maxFrequencyHz = 16_000,
        )
        val active = ByteArray(1_024).apply { setFftBin(20, 127) }
        assertTrue(processor.process(active, TEST_SAMPLE_RATE_MILLI_HERTZ).any { it > 0f })
        val lastRange = processor.adaptiveRange

        val silent = processor.process(ByteArray(1_024), TEST_SAMPLE_RATE_MILLI_HERTZ)

        assertArrayEquals(FloatArray(SAMPLE_COUNT), silent, 0f)
        assertEquals(lastRange, processor.adaptiveRange)
    }

    private fun ByteArray.setFftBin(bin: Int, real: Int, imaginary: Int = 0) {
        require(bin in 1 until size / 2)
        this[bin * 2] = real.toByte()
        this[bin * 2 + 1] = imaginary.toByte()
    }

    private companion object {
        const val TEST_SAMPLE_RATE_MILLI_HERTZ = 48_000_000
        const val TEST_BIN_WIDTH_HZ = 46.875f
    }
}
