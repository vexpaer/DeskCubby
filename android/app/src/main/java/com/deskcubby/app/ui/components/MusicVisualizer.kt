package com.deskcubby.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deskcubby.app.data.model.MusicVisualizerStyle
import kotlin.math.hypot
import kotlinx.coroutines.flow.MutableStateFlow

/** Draws live system-output waveform/FFT data behind the bottom-navigation actions. */
@Composable
fun MusicVisualizerLayer(
    enabled: Boolean,
    style: MusicVisualizerStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val samples = remember { MutableStateFlow(FloatArray(SAMPLE_COUNT)) }
    val values by samples.collectAsState()
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(context, enabled, style, foreground) {
        if (!enabled || !foreground || !context.hasAudioCapturePermission()) {
            samples.value = FloatArray(SAMPLE_COUNT)
            return@DisposableEffect onDispose {}
        }
        val visualizer = runCatching {
            Visualizer(0).apply {
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1_024)
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (style == MusicVisualizerStyle.WAVEFORM && waveform != null) {
                                samples.value = normalizeWaveform(waveform)
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int,
                        ) {
                            if (style != MusicVisualizerStyle.WAVEFORM && fft != null) {
                                samples.value = normalizeFft(fft)
                            }
                        }
                    },
                    (Visualizer.getMaxCaptureRate() / 2).coerceAtLeast(1),
                    style == MusicVisualizerStyle.WAVEFORM,
                    style != MusicVisualizerStyle.WAVEFORM,
                )
                this.enabled = true
            }
        }.getOrNull()
        if (visualizer == null) samples.value = FloatArray(SAMPLE_COUNT)
        onDispose {
            runCatching { visualizer?.enabled = false }
            runCatching { visualizer?.release() }
            samples.value = FloatArray(SAMPLE_COUNT)
        }
    }

    Canvas(modifier.fillMaxSize()) {
        if (!enabled || !foreground || values.isEmpty()) return@Canvas
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
                val path = Path()
                values.forEachIndexed { index, amplitude ->
                    val x = index * size.width / (values.lastIndex.coerceAtLeast(1))
                    val y = size.height - amplitude.coerceIn(0.04f, 0.9f) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
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

private fun normalizeWaveform(bytes: ByteArray): FloatArray {
    if (bytes.isEmpty()) return FloatArray(SAMPLE_COUNT)
    return FloatArray(SAMPLE_COUNT) { index ->
        val source = index * bytes.size / SAMPLE_COUNT
        (((bytes[source].toInt() and 0xff) - 128) / 128f).coerceIn(-1f, 1f)
    }
}

private fun normalizeFft(bytes: ByteArray): FloatArray {
    if (bytes.size < 4) return FloatArray(SAMPLE_COUNT)
    val availableBins = (bytes.size / 2 - 1).coerceAtLeast(1)
    return FloatArray(SAMPLE_COUNT) { index ->
        val normalized = index.toFloat() / (SAMPLE_COUNT - 1)
        val logarithmicBin = (normalized * normalized * (availableBins - 1)).toInt() + 1
        val real = bytes[(logarithmicBin * 2).coerceAtMost(bytes.lastIndex)].toInt().toDouble()
        val imaginary = bytes[(logarithmicBin * 2 + 1).coerceAtMost(bytes.lastIndex)].toInt().toDouble()
        (hypot(real, imaginary) / 128.0).toFloat().coerceIn(0f, 1f)
    }
}

private const val SAMPLE_COUNT = 48
