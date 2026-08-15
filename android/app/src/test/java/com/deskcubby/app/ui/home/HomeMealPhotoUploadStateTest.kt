package com.deskcubby.app.ui.home

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeMealPhotoUploadStateTest {
    @Test
    fun busyStateEndsImmediatelyAfterDurableWrite() = runBlocking {
        val events = mutableListOf<String>()

        val result = runMealPhotoDurableWrite(
            onBusyChanged = { busy -> events += "busy=$busy" },
        ) {
            events += "write"
            "media"
        }
        events += "follow-up"

        assertEquals("media", result)
        assertEquals(
            listOf("busy=true", "write", "busy=false", "follow-up"),
            events,
        )
    }

    @Test
    fun failedDurableWriteAlwaysClearsBusyState() = runBlocking {
        var busy = false

        runCatching {
            runMealPhotoDurableWrite(onBusyChanged = { busy = it }) {
                error("write failed")
            }
        }

        assertFalse(busy)
    }
}
