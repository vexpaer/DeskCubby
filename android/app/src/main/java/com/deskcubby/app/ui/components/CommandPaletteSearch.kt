package com.deskcubby.app.ui.components

import java.util.Locale

/** What a command-palette row does when chosen. */
enum class PaletteEntryKind { PAGE, ACTION, DIARY }

/**
 * One searchable row. [terms] are extra words that should match (for example the other
 * language's page name), so either Chinese or English input finds the same destination.
 */
data class PaletteEntry(
    val id: String,
    val kind: PaletteEntryKind,
    val title: String,
    val subtitle: String = "",
    val terms: List<String> = emptyList(),
)

/**
 * Fuzzy score of [query] against [text], or null when not every query character appears in
 * order. Contiguous runs, word starts and an exact prefix score higher; shorter targets win ties.
 * Case-insensitive and whitespace in the query is ignored, so it works for CJK and Latin input.
 */
fun paletteScore(query: String, text: String): Int? {
    val needle = query.filterNot(Char::isWhitespace).lowercase(Locale.ROOT)
    if (needle.isEmpty()) return 0
    val haystack = text.lowercase(Locale.ROOT)
    if (haystack.isEmpty()) return null
    var score = 0
    var searchFrom = 0
    var previousMatch = -2
    for (char in needle) {
        val index = haystack.indexOf(char, searchFrom)
        if (index < 0) return null
        score += 10
        if (index == previousMatch + 1) score += 15
        if (index == 0 || !haystack[index - 1].isLetterOrDigit()) score += 8
        previousMatch = index
        searchFrom = index + 1
    }
    if (haystack.startsWith(needle)) score += 40
    if (haystack.contains(needle)) score += 20
    return score - haystack.length.coerceAtMost(60) / 4
}

/**
 * Ranks [entries] for [query]. An empty query keeps the given order (pages and actions first).
 * Each entry is scored by its best-matching title, subtitle or term; diary matches are slightly
 * de-weighted so pages and actions stay on top for short queries.
 */
fun rankPaletteEntries(
    query: String,
    entries: List<PaletteEntry>,
    limit: Int = 40,
): List<PaletteEntry> {
    if (query.isBlank()) return entries.take(limit)
    return entries.asSequence()
        .mapNotNull { entry ->
            val best = (listOf(entry.title, entry.subtitle) + entry.terms)
                .mapNotNull { paletteScore(query, it) }
                .maxOrNull()
                ?: return@mapNotNull null
            val weighted = if (entry.kind == PaletteEntryKind.DIARY) best - 12 else best
            entry to weighted
        }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
        .toList()
}
