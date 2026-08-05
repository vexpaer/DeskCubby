package com.deskcubby.app.ui.components

import android.graphics.Typeface
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownPreviewAndroidTest {
    @Test
    fun htmlRenderingPreservesHeadingSizeInlineStyleAndOnlySafeLinks() {
        val rendered = markdownHtmlToSpanned(
            html = """
                <h1>Large title</h1>
                <p>Body <strong>bold</strong>
                <a href="https://example.com">safe</a>
                <a href="javascript:alert(1)">unsafe</a></p>
            """.trimIndent(),
            headingSizesSp = listOf(37f, 30f, 26f, 22f, 19f, 16f),
        )

        val titleStart = rendered.indexOf("Large title")
        val titleEnd = titleStart + "Large title".length
        assertTrue(
            rendered.getSpans(titleStart, titleEnd, AbsoluteSizeSpan::class.java)
                .any { it.size == 37 && !it.dip },
        )
        assertTrue(
            rendered.getSpans(0, rendered.length, StyleSpan::class.java)
                .any { it.style == Typeface.BOLD },
        )
        val links = rendered.getSpans(0, rendered.length, URLSpan::class.java)
        assertEquals(listOf("https://example.com"), links.map(URLSpan::getURL))
        assertFalse(rendered.toString().contains('\uE000'))
    }
}
