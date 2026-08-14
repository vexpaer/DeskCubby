package com.deskcubby.app.ui.reader

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPdfPolicyTest {
    @Test
    fun enhancedRendererFallsBackOnlyAfterPdfiumFailure() {
        assertEquals(
            ReaderPdfRendererMode.ENHANCED,
            selectReaderPdfRendererMode(enhancedReaderUnavailable = false),
        )
        assertEquals(
            ReaderPdfRendererMode.COMPATIBILITY,
            selectReaderPdfRendererMode(enhancedReaderUnavailable = true),
        )
    }

    @Test
    fun renderSizePreservesRatioAndBoundsHugePages() {
        assertEquals(
            ReaderPdfRenderSize(1_600, 2_000),
            readerPdfRenderSize(
                pageWidthPoints = 800,
                pageHeightPoints = 1_000,
                targetWidthPx = 1_600,
            ),
        )

        val bounded = readerPdfRenderSize(
            pageWidthPoints = 1,
            pageHeightPoints = 100,
            targetWidthPx = 2_048,
        )
        assertTrue(bounded.width.toLong() * bounded.height <= MAX_READER_PDF_RENDERED_PIXELS)
        assertTrue(bounded.width > 0)
        assertTrue(bounded.height > 0)
    }

    @Test
    fun renderSizeRejectsInvalidPdfDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            readerPdfRenderSize(0, 1_000, 1_000)
        }
    }

    @Test
    fun pagePlacementKeepsZoomedContentWiderThanViewport() {
        assertEquals(
            ReaderPdfPagePlacement(
                contentX = -420,
                horizontalOffset = 420,
                maxHorizontalOffset = 1_200,
            ),
            readerPdfPagePlacement(
                viewportWidthPx = 1_200,
                contentWidthPx = 2_400,
                requestedHorizontalOffsetPx = 420f,
            ),
        )
    }

    @Test
    fun pagePlacementCentersShrunkContentAndDropsStalePan() {
        assertEquals(
            ReaderPdfPagePlacement(
                contentX = 300,
                horizontalOffset = 0,
                maxHorizontalOffset = 0,
            ),
            readerPdfPagePlacement(
                viewportWidthPx = 1_200,
                contentWidthPx = 600,
                requestedHorizontalOffsetPx = 900f,
            ),
        )
    }

    @Test
    fun pagePlacementUsesContentViewportRatherThanOuterWindow() {
        assertEquals(800, readerPdfMaxHorizontalOffset(1_000, 1_800))
    }

    @Test
    fun resourceOwnerReleasesExactlyOnceWhenFollowingCloseFails() {
        val resource = Any()
        val released = mutableListOf<Any>()
        val owner = ReaderPdfResourceOwner<Any>(released::add)

        assertThrows(IllegalStateException::class.java) {
            try {
                owner.own(resource)
                throw IllegalStateException("page close failed")
            } finally {
                owner.releaseOwned()
            }
        }
        owner.releaseOwned()
        assertEquals(listOf(resource), released)
    }

    @Test
    fun resourceOwnerDoesNotReleaseTransferredValue() {
        val resource = Any()
        val released = mutableListOf<Any>()
        val owner = ReaderPdfResourceOwner<Any>(released::add)

        assertSame(resource, owner.transfer(owner.own(resource)))
        owner.releaseOwned()

        assertTrue(released.isEmpty())
    }

    @Test
    fun detachedResourceReleasesResultThatArrivesAfterTimeout() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Any>()
        val resource = Any()
        try {
            val acquisition = ReaderPdfDetachedResource(
                workerScope = workerScope,
                release = { released.complete(it) },
            ) {
                gate.await()
                resource
            }

            val result = acquisition.await(25L)
            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            gate.complete(Unit)
            assertSame(resource, withTimeout(1_000L) { released.await() })
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun detachedResourceTransfersSuccessfulResultToCaller() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val released = AtomicBoolean(false)
        val resource = Any()
        try {
            val acquisition = ReaderPdfDetachedResource(
                workerScope = workerScope,
                release = { released.set(true) },
            ) { resource }

            assertSame(resource, acquisition.await(1_000L).getOrThrow())
            acquisition.abandon()
            assertTrue(!released.get())
        } finally {
            workerScope.cancel()
        }
    }

    @Test
    fun abandonedQueuedAcquisitionChecksGuardBeforeOpeningResource() = runBlocking {
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val openGate = Mutex(locked = true)
        val workerFinished = CompletableDeferred<Unit>()
        val opened = AtomicBoolean(false)
        try {
            val acquisition = ReaderPdfDetachedResource(
                workerScope = workerScope,
                release = { _ -> },
            ) { guard ->
                try {
                    openGate.withLock {
                        guard.ensureWanted()
                        opened.set(true)
                        Any()
                    }
                } finally {
                    workerFinished.complete(Unit)
                }
            }

            val result = acquisition.await(25L)
            assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
            openGate.unlock()
            withTimeout(1_000L) { workerFinished.await() }
            assertTrue(!opened.get())
        } finally {
            if (openGate.isLocked) openGate.unlock()
            workerScope.cancel()
        }
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
}
