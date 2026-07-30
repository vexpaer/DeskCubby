package com.deskcubby.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PoetryPresetCatalogTest {
    @Test
    fun bundledCatalogContainsAllElevenClassicalSchoolCategories() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = PoetryPresetCatalog(context)
        val summaries = catalog.summaries()

        assertEquals(11, summaries.size)
        assertEquals(11, summaries.map(PoetryPresetCategorySummary::id).distinct().size)
        assertEquals(182, summaries.sumOf(PoetryPresetCategorySummary::itemCount))
        summaries.forEach { summary ->
            val category = catalog.category(summary.id)
            assertNotNull(category)
            assertEquals(summary.itemCount, category?.poems?.size)
        }
        val sources = summaries.flatMap { summary ->
            catalog.category(summary.id)?.poems.orEmpty().map(PoetryPresetPoem::source)
        }
        assertFalse(sources.any { "毛泽东" in it || "陈毅" in it })
    }
}
