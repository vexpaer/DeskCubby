package com.deskcubby.app.plugin.adapter

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGuideParserTest {
    @Test
    fun parsesH1TitleAndNumberedSections() {
        val text = """
            # Title
            preamble
            ## First section
            body one
            line two
            ## Second section
            body two
            ## Third section (with parentheses)
            body three
        """.trimIndent()

        val sections = parseAppGuide(text)

        assertEquals(3, sections.size)
        assertEquals("section-1", sections[0].id)
        assertEquals("First section", sections[0].title)
        assertEquals("body one\nline two", sections[0].content)
        assertEquals("section-3", sections[2].id)
        assertTrue(sections[2].content.contains("body three"))
    }

    @Test
    fun skipsH1AndSubsectionsBelongToParent() {
        val text = """
            # Only the title
            ## First
            ### Sub heading
            sub body
            ## Second
            plain body
        """.trimIndent()

        val sections = parseAppGuide(text)

        assertEquals(2, sections.size)
        // A ### subheading belongs to the enclosing ## section body.
        assertTrue(sections[0].content.contains("Sub heading"))
        assertTrue(sections[0].content.contains("sub body"))
        assertFalse(sections[0].content.contains("plain body"))
    }

    @Test
    fun realGuideParsesToExpectedSectionCountAndUniqueIds() {
        val text = guideMarkdown()
        val sections = parseAppGuide(text)

        // The full tutorial has 27 ## chapters (previously Windows/Android quick-start,
        // numbered chapters 1-17 + platform-specific blocks + FAQ).
        assertTrue("expected many sections, got ${sections.size}", sections.size >= 20)
        val ids = sections.map { it.id }
        assertEquals("section ids must be unique", ids.size, ids.toSet().size)
        assertEquals("ids must be ordinal", "section-1", sections.first().id)
    }

    @Test
    fun singleSectionReadIsBounded() {
        val sections = parseAppGuide(guideMarkdown())
        val maxBody = sections.maxOfOrNull { it.content.length } ?: 0

        assertTrue(
            "largest section ($maxBody) must fit inside the read tool window ($MAX_APP_GUIDE_SECTION_CHARS)",
            maxBody <= MAX_APP_GUIDE_SECTION_CHARS,
        )
    }

    @Test
    fun sectionIndexFitsAgentPromptBudget() {
        val sections = parseAppGuide(guideMarkdown())
        // The index delivered to the Agent system prompt is one line per section.
        val index = sections.joinToString("\n") { "- ${it.id}: ${it.title}" }
        val bytes = index.toByteArray(Charsets.UTF_8).size

        // Much smaller than the 40-category cap and the 24 KiB metadata budget.
        assertTrue("index must stay compact (was $bytes bytes)", bytes <= 24 * 1024)
    }

    private fun guideMarkdown(): String {
        // Unit tests run with the module directory (android/app) as cwd; the guide lives
        // both in the android assets (used at runtime) and at the repo root (canonical).
        val candidates = listOf(
            File("src/main/assets/README_for_ai.md"),
            File("../../README_for_ai.md"),
        )
        val file = candidates.firstOrNull(File::exists)
        assertTrue(
            "README_for_ai.md must be reachable from the test cwd (tried $candidates)",
            file != null,
        )
        val resolved = file ?: error("README_for_ai.md not found")
        return resolved.readText(Charsets.UTF_8)
    }

    @Test
    fun headingInsideBlockquoteDoesNotSplitSection() {
        val text = """
            # Title
            ## Section one
            > blockquote line
            > ## not a real heading
            body
        """.trimIndent()

        val sections = parseAppGuide(text)

        assertEquals(1, sections.size)
        // The `## ` inside a blockquote line is kept as body text, not a new section.
        assertTrue(sections[0].content.contains("## not a real heading"))
    }
}