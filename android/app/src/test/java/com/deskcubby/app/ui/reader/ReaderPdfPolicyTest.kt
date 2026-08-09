package com.deskcubby.app.ui.reader

import android.os.Build
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPdfPolicyTest {
    @Test
    fun enhancedRendererFallsBackAfterFailureAndOlderAndroidAlwaysUsesCompatibilityMode() {
        assertEquals(
            ReaderPdfRendererMode.ENHANCED,
            selectReaderPdfRendererMode(
                Build.VERSION_CODES.P,
                enhancedServiceAvailable = true,
                enhancedReaderUnavailable = false,
            ),
        )
        assertEquals(
            ReaderPdfRendererMode.COMPATIBILITY,
            selectReaderPdfRendererMode(
                Build.VERSION_CODES.P,
                enhancedServiceAvailable = true,
                enhancedReaderUnavailable = true,
            ),
        )
        assertEquals(
            ReaderPdfRendererMode.COMPATIBILITY,
            selectReaderPdfRendererMode(
                Build.VERSION_CODES.O_MR1,
                enhancedServiceAvailable = true,
                enhancedReaderUnavailable = false,
            ),
        )
        assertEquals(
            ReaderPdfRendererMode.COMPATIBILITY,
            selectReaderPdfRendererMode(
                Build.VERSION_CODES.P,
                enhancedServiceAvailable = false,
                enhancedReaderUnavailable = false,
            ),
        )
    }

    @Test
    fun textFeaturesRequireAndroidRExtensionThirteen() {
        assertTrue(readerPdfTextFeaturesAvailable(Build.VERSION_CODES.R, 13))
        assertTrue(!readerPdfTextFeaturesAvailable(Build.VERSION_CODES.R, 12))
        assertTrue(!readerPdfTextFeaturesAvailable(Build.VERSION_CODES.Q, 13))
    }

    @Test
    fun pdfColorMatrixMapsBlackToForegroundAndWhiteToBackground() {
        val values = readerPdfColorMatrixValues(
            backgroundArgb = 0xff204060.toInt(),
            foregroundArgb = 0xffe0c0a0.toInt(),
        )

        assertArrayEquals(
            floatArrayOf(
                -192f / 255f * 0.2126f,
                -192f / 255f * 0.7152f,
                -192f / 255f * 0.0722f,
                0f,
                224f,
                -128f / 255f * 0.2126f,
                -128f / 255f * 0.7152f,
                -128f / 255f * 0.0722f,
                0f,
                192f,
                -64f / 255f * 0.2126f,
                -64f / 255f * 0.7152f,
                -64f / 255f * 0.0722f,
                0f,
                160f,
                0f, 0f, 0f, 1f, 0f,
            ),
            values,
            0.0001f,
        )
        assertTrue(readerPdfColorTransformRequired(0xff204060.toInt(), 0xffe0c0a0.toInt()))
        assertTrue(!readerPdfColorTransformRequired(0xffffffff.toInt(), 0xff202124.toInt()))
        assertTrue(readerPdfColorTransformRequired(0xffffffff.toInt(), 0xff000000.toInt()))
    }

    @Test
    fun pdfLoadReturnsSuccessOrFailureWithoutThrowingOrdinaryErrors() = runBlocking {
        assertEquals(
            "loaded",
            runReaderPdfLoadWithTimeout(1_000L) { "loaded" }.getOrThrow(),
        )
        val failure = runReaderPdfLoadWithTimeout<String>(1_000L) {
            throw IllegalStateException("failed")
        }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun pdfLoadTimeoutBecomesFailure() = runBlocking {
        val result = runReaderPdfLoadWithTimeout<Unit>(100L) { awaitCancellation() }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
    }

    @Test
    fun callerCancellationIsNotConvertedIntoPdfFailure() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runReaderPdfLoadWithTimeout<Unit>(1_000L) {
                    throw CancellationException("caller cancelled")
                }
            }
        }
    }
}
