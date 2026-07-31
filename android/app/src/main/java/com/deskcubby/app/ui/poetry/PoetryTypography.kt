package com.deskcubby.app.ui.poetry

import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val POETRY_TRAILING_PUNCTUATION = setOf(
    '，', '。', '！', '？', '；', '：', '、',
    ',', '.', '!', '?', ';', ':',
    '”', '’', '）', ')', '》', '〉', '】', '〕',
)

private val POETRY_CLAUSE_PUNCTUATION = POETRY_TRAILING_PUNCTUATION + setOf(
    '“', '‘', '（', '(', '《', '〈', '【', '〔', '—', '-',
)

/**
 * Returns true only when the poem is made up of at least two seven-character clauses.
 *
 * A setting named “seven-character wrap” must not reflow ci, prose, or short/long verse. The
 * daily-poetry API sometimes returns an entire poem on one line, so punctuation-delimited clauses
 * and explicit newlines are both treated as verse boundaries.
 */
internal fun isSevenCharacterPoem(text: String): Boolean {
    val clauses = text
        .trim()
        .split(Regex("[，。！？；：、,.!?;:]+"))
        .flatMap { it.lineSequence() }
        .map { clause ->
            clause.filterNot { character ->
                character.isWhitespace() || character in POETRY_CLAUSE_PUNCTUATION
            }
        }
        .filter(String::isNotBlank)
        .toList()
    return clauses.size >= 2 && clauses.all { clause ->
        clause.codePointCount(0, clause.length) == 7
    }
}

/** Extracts the title from the canonical `author《title》` source label when available. */
internal fun poetryTitleFromSource(source: String): String {
    val start = source.indexOf('《')
    val end = source.indexOf('》', startIndex = (start + 1).coerceAtLeast(0))
    return if (start >= 0 && end > start + 1) source.substring(start + 1, end).trim() else ""
}

/**
 * Wraps each manually entered line after seven non-punctuation characters and keeps punctuation
 * immediately following the seventh character on the same visual line. Existing newlines remain
 * hard boundaries, so this display-only option never rewrites the saved poem.
 */
internal fun wrapSevenCharacterVerse(text: String): String =
    text.split('\n').joinToString("\n") { line ->
        if (line.isBlank()) return@joinToString line
        val output = StringBuilder(line.length + line.length / 7)
        var contentCount = 0
        var pendingBreak = false
        line.forEachIndexed { index, character ->
            val punctuation = character in POETRY_TRAILING_PUNCTUATION
            if (pendingBreak && !punctuation) {
                output.append('\n')
                pendingBreak = false
                contentCount = 0
            }
            output.append(character)
            if (!punctuation && !character.isWhitespace()) {
                contentCount++
                if (contentCount == 7 && index < line.lastIndex) pendingBreak = true
            }
        }
        output.toString()
    }

/**
 * Loads a user-selected SAF font without converting its content URI into a filesystem path.
 * A missing permission, removed document, or invalid font safely falls back to the app font.
 */
@Composable
fun rememberPoetryFontFamily(fontUri: String?): FontFamily? {
    val context = LocalContext.current
    val family by produceState<FontFamily?>(
        initialValue = null,
        key1 = fontUri,
    ) {
        value = fontUri?.let { raw ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(raw), "r")?.use { file ->
                        FontFamily(Typeface.Builder(file.fileDescriptor).build())
                    }
                }.getOrNull()
            }
        }
    }
    return family
}
