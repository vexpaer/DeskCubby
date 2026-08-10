package com.deskcubby.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetTextAlignment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopWidgetInstanceStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: DesktopWidgetInstanceStore

    @Before
    fun setUp() {
        context.getSharedPreferences("desktop_widget_instances", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = DesktopWidgetInstanceStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("desktop_widget_instances", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun snapshotsStayIndependentAndDeletedIdIsCleanedUp() {
        val first = DesktopWidgetConfig(id = "first", name = "First")
        val second = DesktopWidgetConfig(
            id = "second",
            name = "Second",
            showName = false,
            backgroundOpacityPercent = 35,
            showIcon = false,
            textAlignment = DesktopWidgetTextAlignment.END,
            textScalePercent = 125,
        )

        store.bind(41, first)
        store.bind(42, second)

        assertEquals(first, store.snapshot(41))
        assertEquals(second, store.snapshot(42))

        store.remove(intArrayOf(41))

        assertNull(store.snapshot(41))
        assertEquals(second, store.snapshot(42))
    }

    @Test
    fun templateRefreshUpdatesOnlyBoundInstancesAndRetainsFallbackSnapshot() {
        val original = DesktopWidgetConfig(id = "shared", name = "Before")
        val other = DesktopWidgetConfig(id = "other", name = "Other")
        store.bind(51, original)
        store.bind(52, original.copy(name = "Instance-local old snapshot"))
        store.bind(53, other)

        val latest = original.copy(
            name = "After",
            showName = false,
            showIcon = false,
            backgroundOpacityPercent = 45,
        )

        assertEquals(listOf(51, 52), store.refreshTemplateSnapshot(latest).toList())
        assertEquals(latest, store.snapshot(51))
        assertEquals(latest, store.snapshot(52))
        assertEquals(other, store.snapshot(53))

        // Removing a reusable template only changes DataStore. The instance store intentionally
        // keeps this last-known-good snapshot so a subsequent render cannot blank the widget.
        assertEquals(latest, store.snapshot(51))
    }

    @Test
    fun templateRefreshMigratesLegacyIdBindingWithoutMergingWidgetIds() {
        context.getSharedPreferences("desktop_widget_instances", Context.MODE_PRIVATE)
            .edit()
            .putString("widget_61", "legacy-template")
            .putString("widget_62", "different-template")
            .commit()
        val latest = DesktopWidgetConfig(id = "legacy-template", name = "Latest")

        assertEquals(listOf(61), store.refreshTemplateSnapshot(latest).toList())
        assertEquals(latest, store.snapshot(61))
        assertNull(store.snapshot(62))
        assertEquals("different-template", store.configId(62))
    }

    @Test
    fun legacyCloudSnapshotMigratesToSyncNow() {
        store.bind(
            71,
            DesktopWidgetConfig(
                id = "legacy-cloud",
                name = "Legacy cloud",
                homeModuleId = "cloud_sync",
            ),
        )

        assertEquals("cloud_sync_now", store.snapshot(71)?.homeModuleId)
    }
}
