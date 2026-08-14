package com.deskcubby.app.ui.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
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

internal data class ReaderPdfPagePlacement(
    val contentX: Int,
    val horizontalOffset: Int,
    val maxHorizontalOffset: Int,
)

/**
 * Places a rendered PDF page inside its clipped viewport.
 *
 * A page narrower than the viewport is centered and cannot retain stale pan. A page wider than
 * the viewport keeps its requested pan, clamped to the real overflow width. Keeping this policy
 * independent from bitmap render resolution is important: a memory-bounded bitmap may still be
 * displayed above 100% without Compose measuring it back down to the viewport.
 */
internal fun readerPdfPagePlacement(
    viewportWidthPx: Int,
    contentWidthPx: Int,
    requestedHorizontalOffsetPx: Float,
): ReaderPdfPagePlacement {
    require(viewportWidthPx > 0)
    require(contentWidthPx > 0)
    val maxOffset = (contentWidthPx - viewportWidthPx).coerceAtLeast(0)
    val offset = requestedHorizontalOffsetPx
        .roundToInt()
        .coerceIn(0, maxOffset)
    val centeredX = ((viewportWidthPx - contentWidthPx) / 2).coerceAtLeast(0)
    return ReaderPdfPagePlacement(
        contentX = centeredX - offset,
        horizontalOffset = offset,
        maxHorizontalOffset = maxOffset,
    )
}

internal fun readerPdfMaxHorizontalOffset(
    viewportWidthPx: Int,
    contentWidthPx: Int,
): Int = readerPdfPagePlacement(
    viewportWidthPx = viewportWidthPx,
    contentWidthPx = contentWidthPx,
    requestedHorizontalOffsetPx = 0f,
).maxHorizontalOffset

/**
 * Applies a pointer pan to the clipped PDF viewport.
 *
 * Pointer movement and content scroll use opposite signs: dragging a page to the right reveals
 * its left side, so the stored offset decreases. Keeping this as a pure function makes the
 * gesture direction and both horizontal boundaries independently testable.
 */
internal fun readerPdfHorizontalOffsetAfterPan(
    currentOffsetPx: Float,
    maxOffsetPx: Float,
    pointerPanXPx: Float,
): Float {
    if (!currentOffsetPx.isFinite() || !maxOffsetPx.isFinite() || !pointerPanXPx.isFinite()) {
        return 0f
    }
    return (currentOffsetPx - pointerPanXPx).coerceIn(0f, maxOffsetPx.coerceAtLeast(0f))
}

internal data class ReaderPdfPanUpdate(
    val horizontalOffsetPx: Float,
    val verticalScrollDeltaPx: Float,
)

/** Converts one raw pointer delta into simultaneous document movement on both axes. */
internal fun readerPdfPanUpdate(
    currentHorizontalOffsetPx: Float,
    maxHorizontalOffsetPx: Float,
    pointerPanXPx: Float,
    pointerPanYPx: Float,
): ReaderPdfPanUpdate = ReaderPdfPanUpdate(
    horizontalOffsetPx = readerPdfHorizontalOffsetAfterPan(
        currentOffsetPx = currentHorizontalOffsetPx,
        maxOffsetPx = maxHorizontalOffsetPx,
        pointerPanXPx = pointerPanXPx,
    ),
    verticalScrollDeltaPx = if (pointerPanYPx.isFinite()) -pointerPanYPx else 0f,
)

/** Returns the actual page width used by the reader for a saved zoom percentage. */
internal fun readerPdfContentWidthPx(
    viewportWidthPx: Int,
    minimumPageWidthPx: Int,
    zoomPercent: Int,
): Int {
    require(viewportWidthPx > 0)
    require(minimumPageWidthPx > 0)
    return (viewportWidthPx * zoomPercent.coerceAtLeast(1) / 100f)
        .roundToInt()
        .coerceAtLeast(minimumPageWidthPx)
}

/**
 * Repositions a page after zoom so the document point under [anchorViewportXPx] stays there.
 * Narrow pages have no pan and are always centered; stale offsets can therefore never make a
 * zoomed-out page stick to the left edge.
 */
internal fun readerPdfHorizontalOffsetAfterZoom(
    viewportWidthPx: Int,
    oldContentWidthPx: Int,
    newContentWidthPx: Int,
    oldOffsetPx: Float,
    anchorViewportXPx: Float,
): Float {
    require(viewportWidthPx > 0)
    require(oldContentWidthPx > 0)
    require(newContentWidthPx > 0)
    val viewport = viewportWidthPx.toFloat()
    val oldWidth = oldContentWidthPx.toFloat()
    val newWidth = newContentWidthPx.toFloat()
    val anchor = anchorViewportXPx
        .takeIf(Float::isFinite)
        ?.coerceIn(0f, viewport)
        ?: viewport / 2f
    val safeOldOffset = oldOffsetPx
        .takeIf(Float::isFinite)
        ?.coerceIn(0f, (oldWidth - viewport).coerceAtLeast(0f))
        ?: 0f
    val oldLeft = if (oldWidth <= viewport) {
        (viewport - oldWidth) / 2f
    } else {
        -safeOldOffset
    }
    val documentFraction = ((anchor - oldLeft) / oldWidth).coerceIn(0f, 1f)
    if (newWidth <= viewport || abs(newWidth - viewport) < 0.5f) return 0f
    return (documentFraction * newWidth - anchor)
        .coerceIn(0f, newWidth - viewport)
}

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
