package com.deskcubby.app.ui.components

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deskcubby.app.data.model.MusicVisualizerFrequencyMode
import com.deskcubby.app.data.model.MusicVisualizerStyle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Draws live system-output waveform/FFT data behind the bottom-navigation actions.
 *
 * This layer deliberately does not impose a size of its own. Its [modifier] must receive a finite
 * size from the bottom-bar container (normally `BoxScope.matchParentSize()`), so this decorative
 * child can never participate in making a Scaffold bottom bar as tall as the whole page.
 */
@Composable
fun MusicVisualizerLayer(
    enabled: Boolean,
    style: MusicVisualizerStyle,
    frequencyMode: MusicVisualizerFrequencyMode = MusicVisualizerFrequencyMode.ADAPTIVE,
    minFrequencyHz: Int = DEFAULT_MUSIC_VISUALIZER_MIN_FREQUENCY_HZ,
    maxFrequencyHz: Int = DEFAULT_MUSIC_VISUALIZER_MAX_FREQUENCY_HZ,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var permissionGranted by remember(context) {
        mutableStateOf(context.hasAudioCapturePermission())
    }
    var systemAnimationsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    val samples = remember { MutableStateFlow(FloatArray(SAMPLE_COUNT)) }
    val values by samples.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) {
                // Both can change while the app is in system settings or a permission dialog.
                permissionGranted = context.hasAudioCapturePermission()
                systemAnimationsEnabled = ValueAnimator.areAnimatorsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val captureAllowed = musicVisualizerCaptureAllowed(
        enabled = enabled,
        foreground = foreground,
        permissionGranted = permissionGranted,
        systemAnimationsEnabled = systemAnimationsEnabled,
    )
    DisposableEffect(
        context,
        style,
        frequencyMode,
        minFrequencyHz,
        maxFrequencyHz,
        captureAllowed,
    ) {
        if (!captureAllowed) {
            samples.value = FloatArray(SAMPLE_COUNT)
            return@DisposableEffect onDispose {}
        }
        val callbackActive = AtomicBoolean(true)
        val visualizer = createOutputMixVisualizer(
            style = style,
            frequencyMode = frequencyMode,
            minFrequencyHz = minFrequencyHz,
            maxFrequencyHz = maxFrequencyHz,
        ) { captured ->
            if (callbackActive.get()) samples.value = captured
        }
        if (visualizer == null) samples.value = FloatArray(SAMPLE_COUNT)
        onDispose {
            callbackActive.set(false)
            releaseVisualizer(visualizer)
            samples.value = FloatArray(SAMPLE_COUNT)
        }
    }

    Canvas(modifier.semantics { invisibleToUser() }) {
        if (!captureAllowed || !hasVisibleMusicSignal(values)) return@Canvas
        when (style) {
            MusicVisualizerStyle.BARS -> {
                val gap = 2.dp.toPx()
                val barWidth = (size.width - gap * (values.size - 1)) / values.size
                values.forEachIndexed { index, amplitude ->
                    val height = (size.height * amplitude.coerceIn(0.025f, 0.92f))
                    drawRoundRect(
                        color = if (index % 2 == 0) primary.copy(alpha = 0.22f)
                        else secondary.copy(alpha = 0.18f),
                        topLeft = Offset(index * (barWidth + gap), size.height - height),
                        size = androidx.compose.ui.geometry.Size(barWidth.coerceAtLeast(1f), height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                    )
                }
            }
            MusicVisualizerStyle.WAVEFORM -> {
                val path = Path()
                values.forEachIndexed { index, sample ->
                    val x = index * size.width / (values.lastIndex.coerceAtLeast(1))
                    val y = size.height / 2f + sample.coerceIn(-1f, 1f) * size.height * 0.4f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    color = primary.copy(alpha = 0.34f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            MusicVisualizerStyle.CURVE -> {
                val points = values.mapIndexed { index, amplitude ->
                    Offset(
                        x = index * size.width / values.lastIndex.coerceAtLeast(1),
                        y = size.height - amplitude.coerceIn(0.04f, 0.9f) * size.height,
                    )
                }
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    var previous = points.first()
                    points.drop(1).forEach { current ->
                        val midpoint = Offset(
                            x = (previous.x + current.x) / 2f,
                            y = (previous.y + current.y) / 2f,
                        )
                        quadraticBezierTo(previous.x, previous.y, midpoint.x, midpoint.y)
                        previous = current
                    }
                    quadraticBezierTo(previous.x, previous.y, previous.x, previous.y)
                }
                drawPath(
                    path,
                    color = secondary.copy(alpha = 0.3f),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

private fun Context.hasAudioCapturePermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

internal fun musicVisualizerCaptureAllowed(
    enabled: Boolean,
    foreground: Boolean,
    permissionGranted: Boolean,
    systemAnimationsEnabled: Boolean,
): Boolean = enabled && foreground && permissionGranted && systemAnimationsEnabled

private fun createOutputMixVisualizer(
    style: MusicVisualizerStyle,
    frequencyMode: MusicVisualizerFrequencyMode,
    minFrequencyHz: Int,
    maxFrequencyHz: Int,
    onSamples: (FloatArray) -> Unit,
): Visualizer? {
    var visualizer: Visualizer? = null
    try {
        val captureSize = preferredVisualizerCaptureSize(Visualizer.getCaptureSizeRange())
            ?: return null
        val captureRate = Visualizer.getMaxCaptureRate().coerceAtMost(MAX_CAPTURE_RATE)
        if (captureRate <= 0) return null
        visualizer = Visualizer(0)
        if (visualizer.setCaptureSize(captureSize) != Visualizer.SUCCESS ||
            visualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) != Visualizer.SUCCESS
        ) {
            releaseVisualizer(visualizer)
            return null
        }
        val waveform = style == MusicVisualizerStyle.WAVEFORM
        val spectrumProcessor = MusicSpectrumProcessor(
            frequencyMode = frequencyMode,
            minFrequencyHz = minFrequencyHz,
            maxFrequencyHz = maxFrequencyHz,
        )
        val listenerResult = visualizer.setDataCaptureListener(
            object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?,
                    waveformBytes: ByteArray?,
                    samplingRate: Int,
                ) {
                    if (waveform && waveformBytes != null) {
                        onSamples(normalizeWaveform(waveformBytes))
                    }
                }

                override fun onFftDataCapture(
                    visualizer: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int,
                ) {
                    if (!waveform && fft != null) {
                        onSamples(spectrumProcessor.process(fft, samplingRate))
                    }
                }
            },
            captureRate,
            waveform,
            !waveform,
        )
        if (listenerResult != Visualizer.SUCCESS ||
            visualizer.setEnabled(true) != Visualizer.SUCCESS
        ) {
            releaseVisualizer(visualizer)
            return null
        }
        return visualizer
    } catch (_: RuntimeException) {
        // Unsupported output-mix engines and permission races must leave navigation usable.
        releaseVisualizer(visualizer)
        return null
    }
}

private fun releaseVisualizer(visualizer: Visualizer?) {
    if (visualizer == null) return
    try {
        visualizer.setDataCaptureListener(null, 0, false, false)
    } catch (_: RuntimeException) {
        // The platform effect can already be dead when audio output changes.
    }
    try {
        visualizer.setEnabled(false)
    } catch (_: RuntimeException) {
        // The platform effect can already be dead when audio output changes.
    }
    try {
        visualizer.release()
    } catch (_: RuntimeException) {
        // Native resources are already gone in this case.
    }
}

internal fun preferredVisualizerCaptureSize(
    range: IntArray,
    preferred: Int = PREFERRED_CAPTURE_SIZE,
): Int? {
    if (range.size < 2 || range[0] <= 0 || range[0] > range[1] || preferred <= 0) return null
    val upperBound = minOf(range[1], preferred)
    val powerOfTwo = Integer.highestOneBit(upperBound)
    return powerOfTwo.takeIf { it >= range[0] }
}

internal fun normalizeWaveform(bytes: ByteArray): FloatArray {
    if (bytes.isEmpty()) return FloatArray(SAMPLE_COUNT)
    val normalized = FloatArray(SAMPLE_COUNT) { index ->
        val source = index * bytes.lastIndex / (SAMPLE_COUNT - 1)
        (((bytes[source].toInt() and 0xff) - 128) / 128f).coerceIn(-1f, 1f)
    }
    return normalized.zeroedWhenSilent()
}

internal fun normalizeFft(bytes: ByteArray): FloatArray {
    return MusicSpectrumProcessor(
        frequencyMode = MusicVisualizerFrequencyMode.ADAPTIVE,
        minFrequencyHz = DEFAULT_MUSIC_VISUALIZER_MIN_FREQUENCY_HZ,
        maxFrequencyHz = DEFAULT_MUSIC_VISUALIZER_MAX_FREQUENCY_HZ,
    ).process(bytes, DEFAULT_TEST_SAMPLE_RATE_MILLI_HERTZ)
}

internal fun hasVisibleMusicSignal(values: FloatArray): Boolean =
    values.any { abs(it) >= SILENCE_FLOOR }

internal fun FloatArray.zeroedWhenSilent(): FloatArray =
    if (hasVisibleMusicSignal(this)) this else FloatArray(size)

internal const val SAMPLE_COUNT = 48
private const val PREFERRED_CAPTURE_SIZE = 1_024
private const val MAX_CAPTURE_RATE = 30_000
private const val DEFAULT_TEST_SAMPLE_RATE_MILLI_HERTZ = 44_100_000
