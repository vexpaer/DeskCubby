package com.deskcubby.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DiaryZipRestoreManagerTest {
    @Test
    fun nestedDiaryMarkdownIsAccepted() {
        val entry = classifyDiaryRestoreEntry("diaries/2026/08/20.md")

        requireNotNull(entry)
        assertEquals("2026/08/20.md", entry.relativePath)
        assertEquals(DiaryRestoreEntryKind.MARKDOWN, entry.kind)
    }

    @Test
    fun markdownExtensionIsCaseInsensitive() {
        val entry = classifyDiaryRestoreEntry("diaries/2026/08/20.MD")

        requireNotNull(entry)
        assertEquals("2026/08/20.MD", entry.relativePath)
        assertEquals(DiaryRestoreEntryKind.MARKDOWN, entry.kind)
    }

    @Test
    fun exactWorkspaceMetadataFilesAreAccepted() {
        val paths = listOf(
            ".deskcubby/fields.json",
            ".deskcubby/records.json",
            ".deskcubby/statistics.json",
            ".deskcubby/settings.json",
        )

        paths.forEach { path ->
            val entry = classifyDiaryRestoreEntry("diaries/$path")
            requireNotNull(entry)
            assertEquals(path, entry.relativePath)
            assertEquals(DiaryRestoreEntryKind.WORKSPACE_METADATA, entry.kind)
        }
    }

    @Test
    fun unrelatedArchiveContentIsIgnored() {
        assertNull(classifyDiaryRestoreEntry("README.md"))
        assertNull(classifyDiaryRestoreEntry("media/photo.jpg"))
        assertNull(classifyDiaryRestoreEntry("notes/note.md"))
        assertNull(classifyDiaryRestoreEntry("data/data.json"))
        assertNull(classifyDiaryRestoreEntry("diaries/photo.jpg"))
    }

    @Test
    fun unsupportedWorkspaceFileFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            classifyDiaryRestoreEntry("diaries/.deskcubby/extra.json")
        }
    }

    @Test
    fun traversalAndAmbiguousSeparatorsFailClosed() {
        listOf(
            "diaries/../outside.md",
            "diaries/2026/../../outside.md",
            "diaries/2026\\08\\20.md",
            "diaries//20.md",
            "diaries/a:bad.md",
        ).forEach { path ->
            assertThrows("expected rejection for $path", IllegalArgumentException::class.java) {
                classifyDiaryRestoreEntry(path)
            }
        }
    }
}
