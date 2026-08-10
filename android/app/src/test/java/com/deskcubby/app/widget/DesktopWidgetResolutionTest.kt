package com.deskcubby.app.widget

import com.deskcubby.app.data.model.DesktopWidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopWidgetResolutionTest {
    @Test
    fun reusableTemplateWinsWhileItExistsAndSnapshotSurvivesDeletion() {
        val first = DesktopWidgetConfig(id = "first", name = "First")
        val secondSnapshot = DesktopWidgetConfig(
            id = "second",
            name = "Second instance",
            showName = false,
            backgroundOpacityPercent = 40,
        )
        val editedSecond = secondSnapshot.copy(
            name = "Edited template",
            backgroundOpacityPercent = 80,
        )

        assertEquals(
            editedSecond,
            resolveDesktopWidgetConfig(
                storedSnapshot = secondSnapshot,
                legacyConfigId = "first",
                reusableConfigs = listOf(first, editedSecond),
            ),
        )
        assertEquals(
            secondSnapshot,
            resolveDesktopWidgetConfig(
                storedSnapshot = secondSnapshot,
                legacyConfigId = null,
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
