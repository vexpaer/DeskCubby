package com.deskcubby.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetBroadcastTimeoutPolicyTest {
    @Test
    fun everyGoAsyncTargetUsesSameExplicitSubTenSecondBudget() {
        val budgets = WidgetBroadcastTarget.entries.associateWith(
            WidgetBroadcastTimeoutPolicy::timeoutMillis,
        )

        assertEquals(WidgetBroadcastTarget.entries.toSet(), budgets.keys)
        assertEquals(setOf(8_500L), budgets.values.toSet())
        assertTrue(
            budgets.values.all {
                it in 1 until WidgetBroadcastTimeoutPolicy.SAFE_BROADCAST_CEILING_MS
            },
        )
    }
}
