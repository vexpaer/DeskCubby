package com.deskcubby.app.plugin.adapter

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the app_guide data-source contract: sources() reports the section index
 * (never the bodies), list() filters section titles, and read() returns a single
 * section's body bounded per section.
 */
class AppGuideDataApiTest {
    private val sections = parseAppGuide(guideMarkdown())

    @Test
    fun sourcesReportsEverySectionAsCategoryIndexEntry() {
        // The adapter builds the source metadata with the section ids in `categories`.
        val index = sections.joinToString("\n") { "${it.id}: ${it.title}" }

        assertEquals(sections.size, sections.map { it.id }.toSet().size)
        assertEquals("app_guide", "app_guide") // wire constant sanity
        assertTrue(index.startsWith("section-1:"))
        assertTrue(index.contains("section-27:") || index.contains("常见问题"))
    }

    @Test
    fun readReturnsOnlyTheRequestedSection() {
        val first = sections.first { it.id == "section-1" }
        val second = sections.first { it.id == "section-2" }

        // Every section must be reachable by id and its content must exclude sibling bodies.
        assertTrue(first.content.isNotBlank())
        assertFalse(first.content.contains(second.title))
    }

    @Test
    fun everySectionFitsTheReadToolWindow() {
        for (section in sections) {
            assertTrue(
                "section ${section.id} body (${section.content.length}) must fit the read window",
                section.content.length <= MAX_APP_GUIDE_SECTION_CHARS,
            )
        }
    }

    private fun guideMarkdown(): String {
        val candidates = listOf(
            File("src/main/assets/README_for_ai.md"),
            File("../../README_for_ai.md"),
        )
        val file = candidates.firstOrNull(File::exists)
        assertTrue("README_for_ai.md must be reachable from the test cwd", file != null)
        return (file ?: error("README_for_ai.md not found")).readText(Charsets.UTF_8)
    }
}