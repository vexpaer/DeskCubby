package com.deskcubby.app.ui.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWidgetLauncherSupportTest {
    @Test
    fun colorOsFamilyRecognizesOppoRelatedManufacturersCaseInsensitively() {
        listOf("OPPO", "oppo", " OnePlus ", "realme").forEach { manufacturer ->
            assertEquals(
                DesktopWidgetLauncherFamily.COLOR_OS,
                desktopWidgetLauncherFamily(manufacturer),
            )
        }
    }

    @Test
    fun unrelatedManufacturerUsesGenericLauncherGuidance() {
        assertEquals(
            DesktopWidgetLauncherFamily.GENERIC,
            desktopWidgetLauncherFamily("Google"),
        )
    }

    @Test
    fun colorOsFailureGuidanceIncludesManualPathAndDoesNotPromiseBypass() {
        val chinese = desktopWidgetManualAddMessage(
            english = false,
            family = DesktopWidgetLauncherFamily.COLOR_OS,
        )
        val english = desktopWidgetManualAddMessage(
            english = true,
            family = DesktopWidgetLauncherFamily.COLOR_OS,
        )

        assertTrue(chinese.contains("双指捏合"))
        assertTrue(chinese.contains("最终是否支持由系统桌面决定"))
        assertTrue(english.contains("Widgets"))
        assertTrue(english.contains("launcher makes the final decision"))
    }
}
