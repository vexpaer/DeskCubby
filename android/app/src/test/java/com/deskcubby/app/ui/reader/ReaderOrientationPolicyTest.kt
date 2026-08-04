package com.deskcubby.app.ui.reader

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderOrientationPolicyTest {
    @Test
    fun `configuration rebuild never preserves a forced reader orientation after exit`() {
        // The old Activity must not change the ActivityRecord while it is rebuilding.
        assertNull(
            readerExitOrientation(
                isFinishing = false,
                isChangingConfigurations = true,
            ),
        )
        // The rebuilt Activity may inherit SENSOR_LANDSCAPE, but a genuine reader exit always
        // returns to the application's system-controlled baseline, never that inherited value.
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            readerExitOrientation(
                isFinishing = false,
                isChangingConfigurations = false,
            ),
        )
        assertNull(
            readerExitOrientation(
                isFinishing = true,
                isChangingConfigurations = false,
            ),
        )
    }
}
