package com.deskcubby.app.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Simplified Chinese tr() literal should have Traditional Chinese, Korean and Japanese
 * entries; a missing key silently falls back to Simplified Chinese at runtime. Strings that were
 * already untranslated when this test was introduced live in the baseline file; anything new must
 * ship with all three translations.
 */
class AppTranslationsCoverageTest {
    @Test
    fun newTrLiteralsHaveAllThreeTranslations() {
        val sourceRoot = File("src/main/java")
        assertTrue("source root must be reachable from the test cwd", sourceRoot.isDirectory)
        val baselineFile = File("src/test/resources/translation-missing-baseline.txt")
        val baseline = baselineFile.readLines().filter(String::isNotBlank).toSet()

        val literals = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AppTranslations.kt" }
            .flatMap { file -> TR_LITERAL.findAll(file.readText()).map { it.groupValues[1] } }
            .filterNot { '$' in it }
            .toSortedSet()

        val tables = listOf(AppTranslations.TRADITIONAL, AppTranslations.KOREAN, AppTranslations.JAPANESE)
        val missing = literals.filter { raw ->
            val key = unescape(raw)
            tables.any { key !in it }
        }
        val unexpected = missing.filterNot { it in baseline }
        assertTrue(
            "Add Traditional Chinese, Korean and Japanese entries to AppTranslations for:\n" +
                unexpected.joinToString("\n"),
            unexpected.isEmpty(),
        )
    }

    private fun unescape(raw: String): String = raw
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")

    private companion object {
        val TR_LITERAL = Regex("""\btr\(\s*"((?:[^"\\]|\\.)*)"""")
    }
}
