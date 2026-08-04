package com.deskcubby.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BoundedPropFindHttpClientAndroidTest {
    @Test
    fun AndroidRuntimeConstructsScopedPropFindClientWithPinnedPolicy() {
        val client = BoundedPropFindHttpClient(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 2_000,
            writeTimeoutMillis = 3_000,
            callTimeoutMillis = 4_000,
            userAgent = "DeskCubby-Android-Test/1",
        )

        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), client.timeoutSnapshotForTest())
        assertEquals(listOf(false, false, false, true), client.policySnapshotForTest())
    }
}
