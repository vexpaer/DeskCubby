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
}
