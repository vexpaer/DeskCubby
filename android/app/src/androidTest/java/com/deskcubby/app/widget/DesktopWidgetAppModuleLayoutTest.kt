package com.deskcubby.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke tests for the app-module widget panel (0.16.1 "App module" content type).
 *
 * These run on an emulator/device and verify that the RemoteViews layouts the renderers bind
 * actually exist, are inflatable, expose every id the renderers reference, and that the removed
 * standalone cloud-sync action container is really gone from the classic card layout.
 */
@RunWith(AndroidJUnit4::class)
class DesktopWidgetAppModuleLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun appPanelLayoutExposesEveryIdTheRenderersBind() {
        val root = LayoutInflater.from(context).inflate(R.layout.desktop_widget_apps, null)
        assertNotNull(root.findViewById<View>(R.id.widget_apps_root))
        assertNotNull(root.findViewById<View>(R.id.widget_apps_title))
        assertNotNull(root.findViewById<View>(R.id.widget_apps_board))
        // D-pad buttons used by 2048 / snake / tetris.
        listOf(
            R.id.widget_apps_btn_up,
            R.id.widget_apps_btn_left,
            R.id.widget_apps_btn_right,
            R.id.widget_apps_btn_down,
        ).forEach { assertNotNull("missing dpad id " + it, root.findViewById<View>(it)) }
        // Generic action row used by every game.
        listOf(R.id.widget_apps_action_1, R.id.widget_apps_action_2)
            .forEach { assertNotNull("missing action id " + it, root.findViewById<View>(it)) }
        // Spider column selector (a horizontal LinearLayout with ten column buttons).
        val columns = root.findViewById<android.widget.LinearLayout>(R.id.widget_apps_columns)
        assertNotNull(columns)
        assertEquals(10, columns.childCount)
        // Minesweeper / go 9x9 tap grid.
        val grid = root.findViewById<GridLayout>(R.id.widget_apps_grid)
        assertNotNull(grid)
        assertEquals(81, grid.childCount)
        // Cloud-sync combined module buttons.
        listOf(
            R.id.widget_apps_cloud_now,
            R.id.widget_apps_cloud_undo,
            R.id.widget_apps_cloud_upload,
            R.id.widget_apps_cloud_download,
        ).forEach { assertNotNull("missing cloud id " + it, root.findViewById<View>(it)) }
        // Background image + scrim referenced by DesktopWidgetRenderer.renderAppModule.
        assertNotNull(root.findViewById<View>(R.id.widget_apps_background_image))
        assertNotNull(root.findViewById<View>(R.id.widget_apps_scrim))
        // Everything except the title/board starts hidden.
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_apps_dpad).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_apps_actions).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_apps_columns).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_apps_grid).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_apps_cloud_actions).visibility)
    }

    @Test
    fun classicCardLayoutNoLongerContainsCloudSyncActionContainer() {
        val root = LayoutInflater.from(context).inflate(R.layout.desktop_widget, null)
        // 0.16.1 removed the standalone sync actions row; renderers must not reference it.
        // The ids are gone from the layout, so assert via runtime resource lookup.
        listOf(
            "widget_cloud_sync_actions",
            "widget_cloud_sync_now",
            "widget_cloud_sync_upload",
            "widget_cloud_sync_download",
            "widget_cloud_sync_undo",
        ).forEach { name ->
            assertEquals(
                "removed id " + name + " must not exist anymore",
                0,
                context.resources.getIdentifier(name, "id", context.packageName),
            )
        }
    }

    @Test
    fun appPanelRemoteViewsCanBeBuiltAndApplied() {
        val views = RemoteViews(context.packageName, R.layout.desktop_widget_apps)
        views.setTextViewText(R.id.widget_apps_title, "2048")
        views.setInt(R.id.widget_apps_root, "setBackgroundColor", 0xFF263238.toInt())
        views.setViewVisibility(R.id.widget_apps_dpad, View.VISIBLE)
        views.setOnClickPendingIntent(
            R.id.widget_apps_btn_up,
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, DesktopWidgetGameActionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        val applied = views.apply(context, FrameLayout(context))
        assertNotNull(applied)
        assertEquals("2048", applied.findViewById<TextView>(R.id.widget_apps_title).text.toString())
        assertEquals(View.VISIBLE, applied.findViewById<View>(R.id.widget_apps_dpad).visibility)
    }

    @Test
    fun classicCardRemoteViewsCanBeBuiltAndApplied() {
        val views = RemoteViews(context.packageName, R.layout.desktop_widget)
        views.setTextViewText(R.id.widget_title, "Today")
        views.setTextViewText(R.id.widget_value, "2026-08-15")
        views.setViewVisibility(R.id.widget_detail, View.GONE)
        val applied = views.apply(context, FrameLayout(context))
        assertNotNull(applied)
        assertEquals("Today", applied.findViewById<TextView>(R.id.widget_title).text.toString())
        assertTrue(applied.findViewById<View>(R.id.widget_detail).visibility == View.GONE)
    }
}
