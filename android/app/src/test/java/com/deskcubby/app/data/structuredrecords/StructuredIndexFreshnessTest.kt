package com.deskcubby.app.data.structuredrecords

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredIndexFreshnessTest {
    @Test
    fun unknownProviderTimestampAlwaysRequiresHashVerification() {
        assertTrue(shouldVerifyStructuredFile(0L, 1_000L, 1_001L))
    }

    @Test
    fun sameMetadataIsPeriodicallyReverified() {
        val now = 10L * STRUCTURED_HASH_AUDIT_INTERVAL_MS
        assertFalse(shouldVerifyStructuredFile(100L, now - 1_000L, now))
        assertTrue(
            shouldVerifyStructuredFile(
                100L,
                now - STRUCTURED_HASH_AUDIT_INTERVAL_MS,
                now,
            ),
        )
    }
}
