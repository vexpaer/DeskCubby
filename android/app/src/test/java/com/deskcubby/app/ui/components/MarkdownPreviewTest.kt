package com.deskcubby.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreviewTest {
    @Test
    fun renderedMarkdownOnlyKeepsExplicitSafeLinkSchemes() {
        assertTrue(isSafeMarkdownLink("https://example.com/note"))
        assertTrue(isSafeMarkdownLink("http://127.0.0.1:8080"))
        assertTrue(isSafeMarkdownLink("mailto:reader@example.com"))
        assertFalse(isSafeMarkdownLink("javascript:alert(1)"))
        assertFalse(isSafeMarkdownLink("file:///private/note"))
        assertFalse(isSafeMarkdownLink("content://provider/private"))
        assertFalse(isSafeMarkdownLink("relative-note.md"))
    }
}
