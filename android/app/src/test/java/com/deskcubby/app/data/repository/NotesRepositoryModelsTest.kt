package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesRepositoryModelsTest {
    @Test
    fun noteNamesStayMarkdownAndWindowsObsidianCompatible() {
        assertEquals("My note.md", normalizeNoteName(" My note ", markdownFile = true))
        assertEquals("already.MD", normalizeNoteName("already.MD", markdownFile = true))
        assertEquals(
            "folder_with_bad_chars",
            normalizeNoteName("folder/with:bad*chars.", markdownFile = false),
        )
    }

    @Test
    fun noteNamesRejectBlankTraversalAndWindowsDeviceNames() {
        listOf("", ".", "..", "CON", "con.md", "LPT9.md").forEach { value ->
            assertTrue(
                "Expected rejected name: $value",
                runCatching { normalizeNoteName(value, markdownFile = value.endsWith(".md")) }
                    .isFailure,
            )
        }
    }
}
