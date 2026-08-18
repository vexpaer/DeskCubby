package com.deskcubby.app.plugin.adapter

/**
 * Parses the bundled `README_for_ai.md` (the full app guide, copied from the tutorial)
 * into stable, machine-readable sections so the DeskCubby Agent can offer it as a
 * read-only data source.
 *
 * Document contract (see the HTML comment at the top of README_for_ai.md):
 *   - Line 1 is the H1 title (not a section).
 *   - Every section is one `## ` heading plus everything below it until the next `## `
 *     heading (its `###` subheadings and body are part of the section content).
 *   - The stable machine id is the section's 1-based ordinal among `##` sections:
 *     `section-1`, `section-2`, ... This tracks the document's own numbering and is
 *     guaranteed unique regardless of heading wording.
 *
 * The parser is lenient at runtime: a malformed or duplicate id never breaks a run —
 * each `##` heading simply becomes one section with an ordinal id.
 */
data class AppGuideSection(
    val id: String,
    val title: String,
    val content: String,
)

/** Upper bound for a single section body handed to a read tool window (well above the largest chapter). */
const val MAX_APP_GUIDE_SECTION_CHARS = 512 * 1024

/** Splits [text] into `##` sections. Extremely large inputs are bounded by [MAX_APP_GUIDE_BYTES]. */
fun parseAppGuide(text: String): List<AppGuideSection> {
    if (text.isBlank()) return emptyList()
    val lines = text.take(MAX_APP_GUIDE_BYTES).lines()
    val sections = mutableListOf<AppGuideSection>()
    var currentTitle: String? = null
    val body = StringBuilder()
    var index = 0

    var i = 0
    // Skip the H1 title (first line) when present.
    if (lines.firstOrNull()?.startsWith("# ") == true) i = 1

    fun flush() {
        val title = currentTitle ?: return
        index++
        val content = body.toString().trim().take(MAX_APP_GUIDE_SECTION_CHARS)
        sections += AppGuideSection("section-$index", title, content)
        currentTitle = null
        body.clear()
    }

    while (i < lines.size) {
        val line = lines[i]
        if (line.startsWith("## ")) {
            flush()
            currentTitle = line.removePrefix("## ").trim()
        } else if (currentTitle != null) {
            if (body.isNotEmpty()) body.append('\n')
            body.append(line)
        }
        i++
    }
    flush()
    return sections
}

private const val MAX_APP_GUIDE_BYTES = 1 * 1024 * 1024
