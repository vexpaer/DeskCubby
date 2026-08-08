package com.deskcubby.app.ui.reader

import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal enum class ReaderPdfRendererMode {
    ENHANCED,
    COMPATIBILITY,
}

internal fun selectReaderPdfRendererMode(
    sdkInt: Int,
    enhancedReaderUnavailable: Boolean,
): ReaderPdfRendererMode =
    if (sdkInt >= Build.VERSION_CODES.P && !enhancedReaderUnavailable) {
        ReaderPdfRendererMode.ENHANCED
    } else {
        ReaderPdfRendererMode.COMPATIBILITY
    }

internal fun readerPdfTextFeaturesAvailable(
    sdkInt: Int,
    sExtensionVersion: Int,
): Boolean = sdkInt >= Build.VERSION_CODES.R && sExtensionVersion >= READER_PDF_TEXT_EXTENSION_VERSION

/**
 * Builds a per-channel linear transform for rendered PDF pixels.
 *
 * PDF black is mapped to [foregroundArgb] and PDF white is mapped to [backgroundArgb]. The
 * alpha channel is preserved so page edges and AndroidX PDF overlays continue to blend normally.
 */
internal fun readerPdfColorMatrixValues(
    backgroundArgb: Int,
    foregroundArgb: Int,
): FloatArray {
    fun component(argb: Int, shift: Int): Float =
        ((argb ushr shift) and 0xff) / 255f

    val backgroundRed = component(backgroundArgb, 16)
    val backgroundGreen = component(backgroundArgb, 8)
    val backgroundBlue = component(backgroundArgb, 0)
    val foregroundRed = component(foregroundArgb, 16)
    val foregroundGreen = component(foregroundArgb, 8)
    val foregroundBlue = component(foregroundArgb, 0)
    fun luminanceRow(background: Float, foreground: Float): FloatArray {
        val delta = background - foreground
        return floatArrayOf(
            delta * 0.2126f,
            delta * 0.7152f,
            delta * 0.0722f,
            0f,
            foreground * 255f,
        )
    }
    return luminanceRow(backgroundRed, foregroundRed) +
        luminanceRow(backgroundGreen, foregroundGreen) +
        luminanceRow(backgroundBlue, foregroundBlue) +
        floatArrayOf(0f, 0f, 0f, 1f, 0f)
}

internal fun readerPdfColorTransformRequired(
    backgroundArgb: Int,
    foregroundArgb: Int,
): Boolean = backgroundArgb != DEFAULT_READER_PDF_BACKGROUND_ARGB ||
    foregroundArgb != DEFAULT_READER_PDF_FOREGROUND_ARGB

internal fun readerPdfTextFeaturesAvailable(): Boolean {
    val sdkInt = Build.VERSION.SDK_INT
    val extensionVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        currentReaderPdfTextExtensionVersion()
    } else {
        0
    }
    return readerPdfTextFeaturesAvailable(sdkInt, extensionVersion)
}

@RequiresApi(Build.VERSION_CODES.R)
private fun currentReaderPdfTextExtensionVersion(): Int =
    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S)

internal suspend fun <T> runReaderPdfLoadWithTimeout(
    timeoutMillis: Long,
    block: suspend () -> T,
): Result<T> {
    require(timeoutMillis > 0L)
    return try {
        Result.success(withTimeout(timeoutMillis) { block() })
    } catch (error: TimeoutCancellationException) {
        Result.failure(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}

internal const val READER_PDF_DOCUMENT_OPEN_TIMEOUT_MILLIS = 15_000L
internal const val READER_PDF_FIRST_CONTENT_TIMEOUT_MILLIS = 15_000L
private const val DEFAULT_READER_PDF_BACKGROUND_ARGB = -0x1
// ReaderBackground.WHITE uses this softer black. Skipping the layer for that exact pair preserves
// native PDF colors and avoids an unnecessary full-view hardware compositing pass.
private const val DEFAULT_READER_PDF_FOREGROUND_ARGB = -14_671_580 // 0xFF202124
private const val READER_PDF_TEXT_EXTENSION_VERSION = 13
