package com.deskcubby.app.ui.reader

import android.os.Build
import java.util.concurrent.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPdfPolicyTest {
    @Test
    fun enhancedRendererFallsBackAfterFailureAndOlderAndroidAlwaysUsesCompatibilityMode() {
        assertEquals(
            ReaderPdfRendererMode.ENHANCED,
            selectReaderPdfRendererMode(Build.VERSION_CODES.P, enhancedReaderUnavailable = false),
        )
        assertEquals(
            ReaderPdfRendererMode.COMPATIBILITY,
            selectReaderPdfRendererMode(Build.VERSION_CODES.P, enhancedReaderUnavailable = true),
        )
        assertEquals(
            ReaderPdfRendererMode.COMPATIBILITY,
            selectReaderPdfRendererMode(Build.VERSION_CODES.O_MR1, enhancedReaderUnavailable = false),
        )
    }

    @Test
    fun textFeaturesRequireAndroidRExtensionThirteen() {
        assertTrue(readerPdfTextFeaturesAvailable(Build.VERSION_CODES.R, 13))
        assertTrue(!readerPdfTextFeaturesAvailable(Build.VERSION_CODES.R, 12))
        assertTrue(!readerPdfTextFeaturesAvailable(Build.VERSION_CODES.Q, 13))
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
