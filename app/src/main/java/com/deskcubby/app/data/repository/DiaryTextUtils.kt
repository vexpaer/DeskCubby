package com.deskcubby.app.data.repository

import java.security.MessageDigest
import java.util.Locale

internal object DiaryTextUtils {
    fun sanitizeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifBlank { "未命名" }

    /**
     * Converts user input into one Markdown file name.
     *
     * The extension is deliberately stripped in a loop so inputs such as
     * `note.MD.md` cannot become `note.MD.md.md`. Trailing dots are removed
     * because several SAF providers reject them even though Android itself
     * does not expose a single provider-independent invalid-character list.
    */
    fun normalizeMarkdownFileName(value: String): String {
        var stem = value.trim().trimEnd(' ', '.')
        while (stem.endsWith(".md", ignoreCase = true)) {
            stem = stem.dropLast(3).trimEnd(' ', '.')
        }
        stem = sanitizeFileName(stem).trimEnd(' ', '.')
        if (stem.isBlank()) stem = "未命名"
        return "$stem.md"
    }

    fun wordCount(text: String): Int {
        val latin = Regex("[\\p{L}\\p{N}_'-]+").findAll(text.replace(Regex("[\\u4E00-\\u9FFF]"), " ")).count()
        val han = Regex("[\\u4E00-\\u9FFF]").findAll(text).count()
        return latin + han
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun preferredLineEnding(source: String): String = when {
        source.contains("\r\n") -> "\r\n"
        source.contains('\n') -> "\n"
        source.contains('\r') -> "\r"
        else -> "\n"
    }

    fun moveSourceLine(source: String, fromIndex: Int, toIndex: Int): String {
        val lineEnding = preferredLineEnding(source)
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val hadTrailingLineEnding = normalized.endsWith('\n')
        val body = if (hadTrailingLineEnding) normalized.dropLast(1) else normalized
        val lines = body.split('\n').toMutableList()
        if (fromIndex !in lines.indices || lines.size < 2) return source
        val destination = toIndex.coerceIn(0, lines.lastIndex)
        if (destination == fromIndex) return source

        val line = lines.removeAt(fromIndex)
        lines.add(destination.coerceIn(0, lines.size), line)
        return lines.joinToString(lineEnding) + if (hadTrailingLineEnding) lineEnding else ""
    }

    fun nextMediaFileName(
        pattern: String,
        dateText: String,
        category: String,
        extension: String,
        existingNames: Collection<String>,
    ): String {
        val existing = existingNames.map { it.lowercase(Locale.ROOT) }.toHashSet()
        var sequence = 1
        while (true) {
            val base = pattern
                .replace("{date}", dateText)
                .replace("{category}", category)
                .replace("{seq}", sequence.toString().padStart(2, '0'))
            val candidate = sanitizeFileName(base) + "." + extension.lowercase(Locale.ROOT)
            if (candidate.lowercase(Locale.ROOT) !in existing) return candidate
            sequence++
        }
    }

    fun moveStandaloneImage(markdown: String, imageMarkdown: String, direction: Int): String {
        if (direction == 0) return markdown
        val chunkRegex = Regex("(?s)(.*?)(\\n[ \\t]*\\n|$)")
        val chunks = chunkRegex.findAll(markdown)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filterNot { (body, separator) -> body.isEmpty() && separator.isEmpty() }
            .toMutableList()
        val index = chunks.indexOfFirst { (body, _) -> body.trim() == imageMarkdown.trim() }
        if (index < 0) return markdown
        val target = if (direction < 0) index - 1 else index + 1
        if (target !in chunks.indices) return markdown
        val firstBody = chunks[index].first
        val targetBody = chunks[target].first
        chunks[index] = targetBody to chunks[index].second
        chunks[target] = firstBody to chunks[target].second
        return chunks.joinToString(separator = "") { (body, separator) -> body + separator }
    }
}
