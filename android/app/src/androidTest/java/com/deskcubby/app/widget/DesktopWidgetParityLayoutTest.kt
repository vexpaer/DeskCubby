package com.deskcubby.app.widget

import android.content.Context
import android.content.ComponentName
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopWidgetParityLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun expandedHomeModuleContainersAreInflatableAndHiddenByDefault() {
        val root = LayoutInflater.from(context).inflate(R.layout.desktop_widget, null)
        val calendar = root.findViewById<View>(R.id.widget_calendar_grid)
        val calendarDays = root.findViewById<GridLayout>(R.id.widget_calendar_days)
        val calendarMonth = root.findViewById<View>(R.id.widget_calendar_month)
        val moduleList = root.findViewById<View>(R.id.widget_module_list)
        val cloudStatus = root.findViewById<View>(R.id.widget_cloud_status)
        val yearProgress = root.findViewById<View>(R.id.widget_year_progress)

        assertNotNull(calendar)
        assertEquals(49, calendarDays.childCount)
        assertNotNull(calendarMonth)
        assertEquals(View.GONE, calendar.visibility)
        assertEquals(View.GONE, moduleList.visibility)
        assertEquals(View.GONE, cloudStatus.visibility)
        assertEquals(View.GONE, yearProgress.visibility)
        listOf(
            R.id.widget_module_row_1,
            R.id.widget_module_row_2,
            R.id.widget_module_row_3,
            R.id.widget_module_row_4,
            R.id.widget_module_row_5,
            R.id.widget_module_row_6,
            R.id.widget_module_row_7,
            R.id.widget_module_row_8,
            R.id.widget_module_footer_primary,
            R.id.widget_cloud_status_1,
            R.id.widget_cloud_status_2,
            R.id.widget_cloud_status_3,
            R.id.widget_cloud_status_4,
        ).forEach { assertNotNull(root.findViewById<View>(it)) }
    }

    @Test
    fun configureActivityRejectsMissingOrForeignWidgetProviders() {
        val expected = ComponentName(context, DeskCubbyWidgetProvider::class.java)
        assertEquals(false, isOwnedDesktopWidgetProvider(null, expected))
        assertEquals(
            false,
            isOwnedDesktopWidgetProvider(ComponentName("other.app", "other.app.Provider"), expected),
        )
        assertEquals(true, isOwnedDesktopWidgetProvider(expected, expected))
    }
}
