package com.deskcubby.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesktopWidgetThoughtDraftStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: DesktopWidgetThoughtDraftStore

    @Before
    fun setUp() {
        context.getSharedPreferences("desktop_widget_thought_drafts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = DesktopWidgetThoughtDraftStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("desktop_widget_thought_drafts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun draftsStayPerInstanceAndDeletionRemovesOnlySelectedIds() {
        store.set(101, " first draft ")
        store.set(102, "second draft")

        assertEquals("first draft", store.get(101))
        assertEquals("second draft", store.get(102))

        store.remove(intArrayOf(101))

        assertEquals("", store.get(101))
        assertEquals("second draft", store.get(102))
    }

    @Test
    fun sendClaimPreventsDuplicatesAndNeverClearsAReplacementDraft() {
        store.set(201, "first")
        val firstClaim = store.claimForSend(201)

        assertEquals("first", firstClaim)
        assertNull(store.claimForSend(201))
        assertEquals("first", store.get(201))

        store.completeSend(201, "first", persisted = false)
        assertEquals("first", store.claimForSend(201))
        store.set(201, "replacement")
        store.completeSend(201, "first", persisted = true)
        assertEquals("replacement", store.get(201))

        assertEquals("replacement", store.claimForSend(201))
        store.completeSend(201, "replacement", persisted = true)
        assertEquals("", store.get(201))
    }
}
