package com.deskcubby.app.widget

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class DesktopWidgetAppShortcutLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun appShortcutIconUsesCenteredLauncherSizedContainer() {
        val root = LayoutInflater.from(context).inflate(R.layout.desktop_widget, null)
        val content = root.findViewById<View>(R.id.widget_app_shortcut_content)
        val icon = root.findViewById<ImageView>(R.id.widget_app_shortcut_icon)
        val layoutParams = icon.layoutParams as FrameLayout.LayoutParams
        val expectedEdgePx = (DESKTOP_APP_ICON_SIZE_DP * context.resources.displayMetrics.density)
            .roundToInt()

        assertEquals(View.GONE, content.visibility)
        assertEquals(expectedEdgePx, layoutParams.width)
        assertEquals(expectedEdgePx, layoutParams.height)
        assertTrue(layoutParams.gravity and Gravity.CENTER == Gravity.CENTER)
    }
}
