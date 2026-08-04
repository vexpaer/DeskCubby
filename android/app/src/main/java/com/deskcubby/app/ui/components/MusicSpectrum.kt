package com.deskcubby.app.ui.components

import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lowest frequency accepted by the visualizer settings and FFT mapper. */
const val MUSIC_VISUALIZER_MIN_FREQUENCY_HZ = 20

/** Highest frequency accepted by the visualizer settings and FFT mapper. */
const val MUSIC_VISUALIZER_MAX_FREQUENCY_HZ = 20_000

const val DEFAULT_MUSIC_VISUALIZER_MIN_FREQUENCY_HZ = 60
const val DEFAULT_MUSIC_VISUALIZER_MAX_FREQUENCY_HZ = 16_000

internal data class SpectrumFrequencyRange(
    val minHz: Float,
    val maxHz: Float,
)

/**
 * Stateful FFT mapper used by one [android.media.audiofx.Visualizer] capture session.
 *
 * Android reports the sampling rate in milliHertz. Keeping the conversion and adaptive-range
 * state here prevents the Compose layer from guessing FFT-bin frequencies or reacting to every
 * capture frame with a visibly different horizontal scale.
 */
internal class MusicSpectrumProcessor(
    private val frequencyMode: MusicVisualizerFrequencyMode,
    private val minFrequencyHz: Int,
    private val maxFrequencyHz: Int,
) {
    internal var adaptiveRange: SpectrumFrequencyRange? = null
        private set

    fun process(bytes: ByteArray, samplingRateMilliHertz: Int): FloatArray {
        val spectrum = decodeFft(bytes, samplingRateMilliHertz)
            ?: return FloatArray(SAMPLE_COUNT)
        if (!hasVisibleMusicSignal(spectrum.magnitudes)) {
            // Do not collapse the last useful adaptive range during pauses between notes.
            return FloatArray(SAMPLE_COUNT)
        }

        val range = when (frequencyMode) {
            MusicVisualizerFrequencyMode.MANUAL -> manualSpectrumFrequencyRange(
                minFrequencyHz = minFrequencyHz,
                maxFrequencyHz = maxFrequencyHz,
                availableMaxHz = spectrum.availableMaxHz,
            )

            MusicVisualizerFrequencyMode.ADAPTIVE -> {
                val target = detectAdaptiveFrequencyRange(
                    magnitudes = spectrum.magnitudes,
                    binWidthHz = spectrum.binWidthHz,
                    availableMaxHz = spectrum.availableMaxHz,
                )
                adaptiveRange = when {
                    target == null -> adaptiveRange
                    adaptiveRange == null -> target
                    else -> smoothAdaptiveFrequencyRange(
                        previous = checkNotNull(adaptiveRange),
                        target = target,
                        availableMaxHz = spectrum.availableMaxHz,
                    )
                }
                adaptiveRange ?: defaultSpectrumFrequencyRange(spectrum.availableMaxHz)
            }
        } ?: return FloatArray(SAMPLE_COUNT)

        return resampleSpectrum(
            magnitudes = spectrum.magnitudes,
            binWidthHz = spectrum.binWidthHz,
            range = range,
        ).zeroedWhenSilent()
    }
}

private data class DecodedFft(
    val magnitudes: FloatArray,
    val binWidthHz: Float,
    val availableMaxHz: Float,
)

private fun decodeFft(bytes: ByteArray, samplingRateMilliHertz: Int): DecodedFft? {
    if (bytes.size < 4 || samplingRateMilliHertz <= 0) return null
    val sampleRateHz = samplingRateMilliHertz / 1_000f
    if (!sampleRateHz.isFinite() || sampleRateHz <= 0f) return null

    // Visualizer FFT layout is [DC, Nyquist, real(1), imag(1), ...]. The capture byte count is
    // also the time-domain FFT size, so bin n is n * sampleRate / captureSize.
    val binCount = bytes.size / 2 - 1
    if (binCount <= 0) return null
    val binWidthHz = sampleRateHz / bytes.size
    if (!binWidthHz.isFinite() || binWidthHz <= 0f) return null
    val magnitudes = FloatArray(binCount) { index ->
        val byteIndex = (index + 1) * 2
        val real = bytes[byteIndex].toInt().toDouble()
        val imaginary = bytes[byteIndex + 1].toInt().toDouble()
        (hypot(real, imaginary) / MAX_FFT_MAGNITUDE)
            .toFloat()
            .coerceIn(0f, 1f)
    }
    return DecodedFft(
        magnitudes = magnitudes,
        binWidthHz = binWidthHz,
        availableMaxHz = binCount * binWidthHz,
    )
}

internal fun manualSpectrumFrequencyRange(
    minFrequencyHz: Int,
    maxFrequencyHz: Int,
    availableMaxHz: Float,
): SpectrumFrequencyRange? {
    val upperLimit = minOf(MUSIC_VISUALIZER_MAX_FREQUENCY_HZ.toFloat(), availableMaxHz)
    if (!upperLimit.isFinite() || upperLimit <= 0f) return null

    val minHz = minFrequencyHz.coerceIn(
        MUSIC_VISUALIZER_MIN_FREQUENCY_HZ,
        MUSIC_VISUALIZER_MAX_FREQUENCY_HZ,
    ).toFloat()
    val maxHz = maxFrequencyHz.coerceIn(
        MUSIC_VISUALIZER_MIN_FREQUENCY_HZ,
        MUSIC_VISUALIZER_MAX_FREQUENCY_HZ,
    ).toFloat()
    if (minHz >= maxHz) return defaultSpectrumFrequencyRange(upperLimit)
    if (minHz >= upperLimit) return null
    return SpectrumFrequencyRange(minHz, minOf(maxHz, upperLimit))
        .takeIf { it.minHz < it.maxHz }
}

private fun defaultSpectrumFrequencyRange(availableMaxHz: Float): SpectrumFrequencyRange? {
    val upperLimit = minOf(MUSIC_VISUALIZER_MAX_FREQUENCY_HZ.toFloat(), availableMaxHz)
    if (!upperLimit.isFinite() || upperLimit <= 0f) return null
    val minHz = minOf(DEFAULT_MUSIC_VISUALIZER_MIN_FREQUENCY_HZ.toFloat(), upperLimit / 2f)
        .coerceAtLeast(minOf(MUSIC_VISUALIZER_MIN_FREQUENCY_HZ.toFloat(), upperLimit / 2f))
    val maxHz = minOf(DEFAULT_MUSIC_VISUALIZER_MAX_FREQUENCY_HZ.toFloat(), upperLimit)
    return SpectrumFrequencyRange(minHz, maxHz).takeIf { it.minHz < it.maxHz }
}

internal fun detectAdaptiveFrequencyRange(
    magnitudes: FloatArray,
    binWidthHz: Float,
    availableMaxHz: Float,
): SpectrumFrequencyRange? {
    if (magnitudes.isEmpty() || !binWidthHz.isFinite() || binWidthHz <= 0f ||
        !availableMaxHz.isFinite() || availableMaxHz <= 0f
    ) {
        return null
    }
    val peak = magnitudes.maxOrNull()?.takeIf(Float::isFinite) ?: return null
    if (peak < SILENCE_FLOOR) return null

    // Compress the dynamic range before taking energy percentiles. This lets quiet harmonics
    // influence the selected upper edge instead of allowing one bass bin to own the full range.
    val noiseFloor = max(SILENCE_FLOOR, peak * ADAPTIVE_RELATIVE_NOISE_FLOOR)
    val weights = FloatArray(magnitudes.size) { index ->
        val magnitude = magnitudes[index]
        if (!magnitude.isFinite() || magnitude <= noiseFloor) 0f
        else sqrt(magnitude - noiseFloor)
    }
    val totalWeight = weights.sum()
    val peakIndex = magnitudes.indices.maxByOrNull { magnitudes[it] } ?: return null
    val lowerIndex: Int
    val upperIndex: Int
    if (!totalWeight.isFinite() || totalWeight <= 0f) {
        lowerIndex = peakIndex
        upperIndex = peakIndex
    } else {
        lowerIndex = weightedPercentileIndex(weights, totalWeight * ADAPTIVE_LOWER_PERCENTILE)
        upperIndex = weightedPercentileIndex(weights, totalWeight * ADAPTIVE_UPPER_PERCENTILE)
    }

    val globalMinHz = minOf(MUSIC_VISUALIZER_MIN_FREQUENCY_HZ.toFloat(), availableMaxHz / 2f)
    val globalMaxHz = minOf(MUSIC_VISUALIZER_MAX_FREQUENCY_HZ.toFloat(), availableMaxHz)
    if (globalMinHz >= globalMaxHz) return null
    var minHz = ((lowerIndex + 0.5f) * binWidthHz / ADAPTIVE_PADDING_RATIO)
        .coerceIn(globalMinHz, globalMaxHz)
    var maxHz = ((upperIndex + 1.5f) * binWidthHz * ADAPTIVE_PADDING_RATIO)
        .coerceIn(globalMinHz, globalMaxHz)

    if (maxHz / minHz < ADAPTIVE_MINIMUM_SPAN_RATIO) {
        val center = sqrt(minHz * maxHz)
        val halfSpanRatio = sqrt(ADAPTIVE_MINIMUM_SPAN_RATIO)
        minHz = (center / halfSpanRatio).coerceAtLeast(globalMinHz)
        maxHz = (center * halfSpanRatio).coerceAtMost(globalMaxHz)
        // Re-apply the minimum span against an edge when one side was clamped.
        if (maxHz / minHz < ADAPTIVE_MINIMUM_SPAN_RATIO) {
            if (minHz <= globalMinHz) {
                maxHz = (minHz * ADAPTIVE_MINIMUM_SPAN_RATIO).coerceAtMost(globalMaxHz)
            } else {
                minHz = (maxHz / ADAPTIVE_MINIMUM_SPAN_RATIO).coerceAtLeast(globalMinHz)
            }
        }
    }
    return SpectrumFrequencyRange(minHz, maxHz).takeIf { it.minHz < it.maxHz }
}

private fun weightedPercentileIndex(weights: FloatArray, targetWeight: Float): Int {
    var cumulative = 0f
    weights.forEachIndexed { index, weight ->
        cumulative += weight
        if (cumulative >= targetWeight) return index
    }
    return weights.lastIndex
}

internal fun smoothAdaptiveFrequencyRange(
    previous: SpectrumFrequencyRange,
    target: SpectrumFrequencyRange,
    availableMaxHz: Float,
): SpectrumFrequencyRange {
    val globalMinHz = minOf(MUSIC_VISUALIZER_MIN_FREQUENCY_HZ.toFloat(), availableMaxHz / 2f)
    val globalMaxHz = minOf(MUSIC_VISUALIZER_MAX_FREQUENCY_HZ.toFloat(), availableMaxHz)
    val minAlpha = if (target.minHz < previous.minHz) {
        ADAPTIVE_EXPANSION_ALPHA
    } else {
        ADAPTIVE_CONTRACTION_ALPHA
    }
    val maxAlpha = if (target.maxHz > previous.maxHz) {
        ADAPTIVE_EXPANSION_ALPHA
    } else {
        ADAPTIVE_CONTRACTION_ALPHA
    }
    val minHz = lerp(previous.minHz, target.minHz, minAlpha).coerceIn(globalMinHz, globalMaxHz)
    val maxHz = lerp(previous.maxHz, target.maxHz, maxAlpha).coerceIn(globalMinHz, globalMaxHz)
    return if (minHz < maxHz) {
        SpectrumFrequencyRange(minHz, maxHz)
    } else {
        target
    }
}

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun resampleSpectrum(
    magnitudes: FloatArray,
    binWidthHz: Float,
    range: SpectrumFrequencyRange,
): FloatArray {
    if (magnitudes.isEmpty() || range.minHz <= 0f || range.minHz >= range.maxHz) {
        return FloatArray(SAMPLE_COUNT)
    }
    val allowedFirstBin = ceil(range.minHz / binWidthHz).toInt().coerceAtLeast(1)
    val allowedLastBin = floor(range.maxHz / binWidthHz).toInt().coerceAtMost(magnitudes.size)
    if (allowedFirstBin > allowedLastBin) return FloatArray(SAMPLE_COUNT)
    val logMin = ln(range.minHz)
    val logSpan = ln(range.maxHz) - logMin
    return FloatArray(SAMPLE_COUNT) { outputIndex ->
        val lowerFrequency = exp(logMin + logSpan * outputIndex / SAMPLE_COUNT)
        val upperFrequency = exp(logMin + logSpan * (outputIndex + 1) / SAMPLE_COUNT)
        var firstBin = ceil(lowerFrequency / binWidthHz).toInt()
        var lastBin = floor(upperFrequency / binWidthHz).toInt()
        if (lastBin < firstBin) {
            val centerFrequency = sqrt(lowerFrequency * upperFrequency)
            firstBin = (centerFrequency / binWidthHz).roundToInt()
            lastBin = firstBin
        }
        firstBin = firstBin.coerceIn(allowedFirstBin, allowedLastBin)
        lastBin = lastBin.coerceIn(firstBin, allowedLastBin)
        var peak = 0f
        for (bin in firstBin..lastBin) {
            peak = max(peak, magnitudes[bin - 1])
        }
        peak
    }
}

internal const val MAX_FFT_MAGNITUDE = 180.0
internal const val SILENCE_FLOOR = 0.012f
private const val ADAPTIVE_RELATIVE_NOISE_FLOOR = 0.04f
private const val ADAPTIVE_LOWER_PERCENTILE = 0.025f
private const val ADAPTIVE_UPPER_PERCENTILE = 0.975f
private const val ADAPTIVE_PADDING_RATIO = 1.35f
private const val ADAPTIVE_MINIMUM_SPAN_RATIO = 4f
private const val ADAPTIVE_EXPANSION_ALPHA = 0.42f
private const val ADAPTIVE_CONTRACTION_ALPHA = 0.14f
