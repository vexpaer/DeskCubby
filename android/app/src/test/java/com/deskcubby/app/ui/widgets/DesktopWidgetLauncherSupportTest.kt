package com.deskcubby.app.ui.widgets

import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWidgetLauncherSupportTest {
    @Test
    fun genericFailureGuidanceIncludesManualPathAndDoesNotPromiseBypass() {
        val chinese = desktopWidgetManualAddMessage(english = false)
        val english = desktopWidgetManualAddMessage(english = true)

        assertTrue(chinese.contains("双指捏合"))
        assertTrue(chinese.contains("最终放置仍由系统桌面决定"))
        assertTrue(english.contains("Widgets"))
        assertTrue(english.contains("launcher makes the final placement decision"))
    }

    @Test
    fun acceptedGuidanceStillOffersTheManualWidgetPickerPath() {
        val chinese = desktopWidgetPinAcceptedMessage(english = false)
        val english = desktopWidgetPinAcceptedMessage(english = true)

        assertTrue(chinese.contains("小组件/窗口小工具"))
        assertTrue(english.contains("Widgets panel"))
    }
}
