package com.deskcubby.app.widget

import com.deskcubby.app.data.model.DesktopWidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopWidgetResolutionTest {
    @Test
    fun placedSnapshotWinsOverReusableDesigns() {
        val first = DesktopWidgetConfig(id = "first", name = "First")
        val secondSnapshot = DesktopWidgetConfig(
            id = "second",
            name = "Second instance",
            showName = false,
            backgroundOpacityPercent = 40,
        )

        assertEquals(
            secondSnapshot,
            resolveDesktopWidgetConfig(
                storedSnapshot = secondSnapshot,
                legacyConfigId = "first",
                reusableConfigs = listOf(first),
            ),
        )
    }

    @Test
    fun legacyBindingMigratesByExactIdAndUnboundInstanceDoesNotUseFirstDesign() {
        val first = DesktopWidgetConfig(id = "first", name = "First")
        val second = DesktopWidgetConfig(id = "second", name = "Second")

        assertEquals(
            second,
            resolveDesktopWidgetConfig(null, "second", listOf(first, second)),
        )
        assertNull(resolveDesktopWidgetConfig(null, null, listOf(first, second)))
        assertNull(resolveDesktopWidgetConfig(null, "missing", listOf(first, second)))
    }
}
