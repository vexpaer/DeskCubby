package com.deskcubby.app.ui.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class ReaderPdfRendererMode {
    ENHANCED,
    COMPATIBILITY,
}

internal fun selectReaderPdfRendererMode(
    enhancedReaderUnavailable: Boolean,
): ReaderPdfRendererMode =
    if (!enhancedReaderUnavailable) {
        ReaderPdfRendererMode.ENHANCED
    } else {
        ReaderPdfRendererMode.COMPATIBILITY
    }

/**
 * Builds a per-channel linear transform for rendered PDF pixels.
 *
 * PDF black is mapped to [foregroundArgb] and PDF white is mapped to [backgroundArgb]. The
 * alpha channel is preserved so antialiased PDF page edges continue to blend normally.
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

internal data class ReaderPdfRenderSize(
    val width: Int,
    val height: Int,
)

internal interface ReaderPdfAcquisitionGuard {
    val isAbandoned: Boolean

    fun ensureWanted() {
        if (isAbandoned) throw CancellationException("PDF acquisition was abandoned")
    }
}

/** Holds one native-backed value until it is explicitly transferred to the next owner. */
internal class ReaderPdfResourceOwner<T>(
    private val release: (T) -> Unit,
) {
    private val owned = AtomicReference<T?>(null)

    fun own(value: T): T {
        check(owned.compareAndSet(null, value)) { "A PDF resource is already owned" }
        return value
    }

    fun transfer(value: T): T {
        check(owned.compareAndSet(value, null)) { "PDF resource ownership was already resolved" }
        return value
    }

    fun releaseOwned() {
        owned.getAndSet(null)?.let(release)
    }
}

/**
 * Runs blocking PDF work in a scope that is independent from the UI caller. A timeout or caller
 * cancellation only abandons the handoff; it never waits for a native call to return. Resources
 * that arrive after abandonment are released back on [workerScope].
 */
internal class ReaderPdfDetachedResource<T>(
    private val workerScope: CoroutineScope,
    private val release: suspend (T) -> Unit,
    acquire: suspend (ReaderPdfAcquisitionGuard) -> T,
) : ReaderPdfAcquisitionGuard {
    private val state = AtomicReference<DetachedState<T>>(DetachedState.Pending())
    private val completion = CompletableDeferred<Result<T>>()

    override val isAbandoned: Boolean
        get() = state.get() is DetachedState.Abandoned

    init {
        workerScope.launch {
            val acquired = try {
                Result.success(acquire(this@ReaderPdfDetachedResource))
            } catch (error: Throwable) {
                Result.failure(error)
            }
            acquired.fold(
                onSuccess = { publish(it) },
                onFailure = { publishFailure(it) },
            )
        }
    }

    suspend fun await(timeoutMillis: Long? = null): Result<T> {
        if (timeoutMillis != null) require(timeoutMillis > 0L)
        return try {
            val result = if (timeoutMillis == null) {
                completion.await()
            } else {
                withTimeout(timeoutMillis) { completion.await() }
            }
            // Claim only after withTimeout has returned. If timeout wins the prompt-cancellation
            // race, catch below abandons an Available resource instead of leaking a Claimed one.
            claim(result)
        } catch (error: TimeoutCancellationException) {
            abandon()
            Result.failure(error)
        } catch (error: CancellationException) {
            abandon()
            throw error
        }
    }

    fun abandon() {
        while (true) {
            when (val current = state.get()) {
                is DetachedState.Pending -> {
                    if (state.compareAndSet(current, DetachedState.Abandoned())) return
                }
                is DetachedState.Available -> {
                    if (state.compareAndSet(current, DetachedState.Abandoned())) {
                        releaseLater(current.value)
                        return
                    }
                }
                is DetachedState.Abandoned,
                is DetachedState.Claimed,
                is DetachedState.Failed,
                -> return
            }
        }
    }

    private fun claim(result: Result<T>): Result<T> {
        return result.map { value ->
            while (true) {
                when (val current = state.get()) {
                    is DetachedState.Available -> {
                        if (state.compareAndSet(current, DetachedState.Claimed())) return@map value
                    }
                    is DetachedState.Abandoned -> throw CancellationException(
                        "PDF resource handoff was abandoned",
                    )
                    else -> error("PDF resource handoff is not available")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            value
        }
    }

    private suspend fun publish(value: T) {
        while (true) {
            when (val current = state.get()) {
                is DetachedState.Pending -> {
                    if (state.compareAndSet(current, DetachedState.Available(value))) {
                        completion.complete(Result.success(value))
                        return
                    }
                }
                is DetachedState.Abandoned -> {
                    release(value)
                    completion.complete(Result.failure(CancellationException("PDF acquisition abandoned")))
                    return
                }
                else -> error("PDF resource acquisition completed more than once")
            }
        }
    }

    private fun publishFailure(error: Throwable) {
        while (true) {
            when (val current = state.get()) {
                is DetachedState.Pending -> {
                    if (state.compareAndSet(current, DetachedState.Failed())) {
                        completion.complete(Result.failure(error))
                        return
                    }
                }
                is DetachedState.Abandoned -> {
                    completion.complete(Result.failure(error))
                    return
                }
                else -> error("PDF resource acquisition completed more than once")
            }
        }
    }

    private fun releaseLater(value: T) {
        workerScope.launch { release(value) }
    }

    private sealed interface DetachedState<out T> {
        class Pending<T> : DetachedState<T>
        data class Available<T>(val value: T) : DetachedState<T>
        class Claimed<T> : DetachedState<T>
        class Abandoned<T> : DetachedState<T>
        class Failed<T> : DetachedState<T>
    }
}

/** Keeps a rendered page inside the same pixel and width bounds as the system renderer fallback. */
internal fun readerPdfRenderSize(
    pageWidthPoints: Int,
    pageHeightPoints: Int,
    targetWidthPx: Int,
): ReaderPdfRenderSize {
    require(pageWidthPoints > 0 && pageHeightPoints > 0)
    var width = targetWidthPx.coerceIn(MIN_READER_PDF_WIDTH_PX, MAX_READER_PDF_WIDTH_PX)
    var height = (width.toDouble() * pageHeightPoints / pageWidthPoints)
        .roundToInt()
        .coerceAtLeast(1)
    val pixels = width.toLong() * height
    if (pixels > MAX_READER_PDF_PIXELS) {
        val scale = sqrt(MAX_READER_PDF_PIXELS.toDouble() / pixels)
        width = (width * scale).roundToInt().coerceAtLeast(1)
        height = (height * scale).roundToInt().coerceAtLeast(1)
    }
    return ReaderPdfRenderSize(width, height)
}

internal const val READER_PDF_DOCUMENT_OPEN_TIMEOUT_MILLIS = 30_000L
internal const val READER_PDF_FIRST_CONTENT_TIMEOUT_MILLIS = 30_000L
internal const val MAX_READER_PDF_RENDERED_PIXELS = 4_000_000L
private const val DEFAULT_READER_PDF_BACKGROUND_ARGB = -0x1
// ReaderBackground.WHITE uses this softer black. Skipping the layer for that exact pair preserves
// native PDF colors and avoids an unnecessary full-view hardware compositing pass.
private const val DEFAULT_READER_PDF_FOREGROUND_ARGB = -14_671_580 // 0xFF202124
private const val MIN_READER_PDF_WIDTH_PX = 320
private const val MAX_READER_PDF_WIDTH_PX = 2_048
private const val MAX_READER_PDF_PIXELS = MAX_READER_PDF_RENDERED_PIXELS
