package com.deskcubby.app.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal enum class WidgetBroadcastTarget {
    DESKTOP_CONTENT_UPDATE,
    PIN_BINDING,
    CLOUD_WIDGET_RENDER,
    CLOUD_SYNC_ACTION,
    GAME_ACTION,
    GAME_TICK,
}

/** Keep goAsync work below Android's roughly ten-second broadcast execution window. */
internal object WidgetBroadcastTimeoutPolicy {
    const val SAFE_BROADCAST_CEILING_MS = 10_000L

    fun timeoutMillis(target: WidgetBroadcastTarget): Long = when (target) {
        WidgetBroadcastTarget.DESKTOP_CONTENT_UPDATE,
        WidgetBroadcastTarget.PIN_BINDING,
        WidgetBroadcastTarget.CLOUD_WIDGET_RENDER,
        WidgetBroadcastTarget.CLOUD_SYNC_ACTION,
        WidgetBroadcastTarget.GAME_ACTION,
        WidgetBroadcastTarget.GAME_TICK,
        -> 8_500L
    }
}

/**
 * Runs receiver work inside its explicit budget. Timeout and ordinary failures leave the remaining
 * widget IDs untouched for the next periodic/manual refresh; caller cancellation still propagates.
 */
internal suspend fun runBoundedWidgetBroadcast(
    target: WidgetBroadcastTarget,
    block: suspend () -> Unit,
): Boolean = try {
    withTimeout(WidgetBroadcastTimeoutPolicy.timeoutMillis(target)) { block() }
    true
} catch (_: TimeoutCancellationException) {
    false
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
