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

internal const val READER_PDF_DOCUMENT_OPEN_TIMEOUT_MILLIS = 8_000L
internal const val READER_PDF_FIRST_CONTENT_TIMEOUT_MILLIS = 8_000L
private const val READER_PDF_TEXT_EXTENSION_VERSION = 13
