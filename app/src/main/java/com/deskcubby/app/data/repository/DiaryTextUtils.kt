package com.deskcubby.app.data.repository

import java.security.MessageDigest
import java.util.Locale

internal object DiaryTextUtils {
    fun sanitizeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifBlank { "未命名" }

    fun wordCount(text: String): Int {
        val latin = Regex("[\\p{L}\\p{N}_'-]+").findAll(text.replace(Regex("[\\u4E00-\\u9FFF]"), " ")).count()
        val han = Regex("[\\u4E00-\\u9FFF]").findAll(text).count()
        return latin + han
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
