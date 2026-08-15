package com.deskcubby.app.widget

import com.deskcubby.app.data.model.DesktopWidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopWidgetVisualPolicyTest {
    @Test
    fun launcherBoundsUseOneConsistentSeventyTwoDpCellFallback() {
        val config = DesktopWidgetConfig(id = "sized", name = "Sized", widthCells = 2, heightCells = 1)

        assertEquals(DesktopWidgetBoundsDp(144, 72), desktopWidgetBoundsDp(0, 0, config))
        assertEquals(DesktopWidgetBoundsDp(144, 72), desktopWidgetBoundsDp(-1, -1, config))
        assertEquals(DesktopWidgetBoundsDp(155, 83), desktopWidgetBoundsDp(155, 83, config))
    }

    @Test
    fun cardScalePreservesWidgetAspectRatio() {
        assertEquals(
            DesktopWidgetSurfaceInsetsDp(0, 0),
            desktopWidgetSurfaceInsetsDp(144, 72, 100),
        )
        assertEquals(
            DesktopWidgetSurfaceInsetsDp(14, 7),
            desktopWidgetSurfaceInsetsDp(144, 72, 80),
        )
        assertEquals(
            DesktopWidgetSurfaceInsetsDp(22, 11),
            desktopWidgetSurfaceInsetsDp(144, 72, 70),
        )
        assertEquals(
            DesktopWidgetSurfaceInsetsDp(0, 0),
            desktopWidgetSurfaceInsetsDp(144, 72, 200),
        )
    }
}
