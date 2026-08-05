package com.deskcubby.app.data.repository

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextDecodingTest {
    @Test
    fun `decodes UTF BOM variants and common Chinese legacy text`() {
        assertEquals(
            "标题",
            decodeReaderText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "标题".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            "chapter",
            decodeReaderText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                "chapter".toByteArray(Charsets.UTF_16LE)),
        )
        assertEquals(
            "中文小说",
            decodeReaderText("中文小说".toByteArray(Charset.forName("GB18030"))),
        )
    }

    @Test
    fun `reader preferences reject non-finite values and stay within UI bounds`() {
        val normalized = normalizeReaderPreferences(
            ReaderPreferences(
                fontSizeSp = Float.NaN,
                lineHeightMultiplier = Float.POSITIVE_INFINITY,
                paragraphSpacingDp = 999f,
                pdfZoomPercent = 999,
            ),
        )

        assertEquals(19f, normalized.fontSizeSp)
        assertEquals(1.6f, normalized.lineHeightMultiplier)
        assertEquals(36f, normalized.paragraphSpacingDp)
        assertEquals(MAX_READER_PDF_ZOOM_PERCENT, normalized.pdfZoomPercent)
        assertEquals(0xFF345678.toInt(), normalizeReaderPreferences(
            ReaderPreferences(customBackgroundArgb = 0x00345678),
        ).customBackgroundArgb)
    }

    @Test
    fun `chapter detection skips integrated TOC entries and keeps later body headings`() {
        val layout = paginateReaderText(
            paragraphs = listOf(
                "目录",
                "第一章、初见 10",
                "第二章 重逢 25",
                "第三章 远行 40",
                "过渡说明",
                "\u200B第 1 章 初见",
                "正文内容".repeat(80),
                "第　2　章 重逢",
                "后续内容".repeat(80),
                "第3章 远行",
            ),
            targetChars = 400,
        )

        assertEquals(
            listOf("第 1 章 初见", "第 2 章 重逢", "第3章 远行"),
            layout.chapters.map(ReaderChapter::title),
        )
        assertTrue(layout.chapters.all { it.paragraphIndex >= 5 })
        assertFalse(isReaderChapterHeading("第四章 归来……52"))
    }

    @Test
    fun `full book text search is case insensitive and bounded`() {
        val pages = listOf(
            ReaderTextPage("Alpha beta ALPHA", 0),
            ReaderTextPage("later alpha", 1),
        )

        val matches = findReaderTextMatches(pages, "alpha")

        assertEquals(listOf(0, 0, 1), matches.map(ReaderTextSearchMatch::pageIndex))
        assertEquals(2, findReaderTextMatches(pages, "alpha", maxResults = 2).size)
        assertTrue(findReaderTextMatches(pages, "   ").isEmpty())
    }

    @Test
    fun `TXT pagination detects chapters and gives headings stable page starts`() {
        val layout = paginateReaderText(
            paragraphs = listOf(
                "前言",
                "简短介绍",
                "第一章 出发",
                "正文".repeat(260),
                "Chapter 2: Return",
                "结尾".repeat(80),
            ),
            targetChars = 500,
        )

        assertEquals(listOf("前言", "第一章 出发", "Chapter 2: Return"), layout.chapters.map { it.title })
        layout.chapters.forEach { chapter ->
            assertTrue(layout.pages[chapter.pageIndex].text.startsWith(chapter.title))
        }
        assertTrue(layout.pages.size > layout.chapters.size)
        assertEquals(layout, paginateReaderText(
            listOf("前言", "简短介绍", "第一章 出发", "正文".repeat(260), "Chapter 2: Return", "结尾".repeat(80)),
            targetChars = 500,
        ))
    }

    @Test
    fun `logical pages never split a surrogate pair and legacy progress maps to paragraph start`() {
        val emojiParagraph = "😀".repeat(240)
        val layout = paginateReaderText(
            paragraphs = listOf("第1章", emojiParagraph, "第二段"),
            targetChars = 200,
        )

        layout.pages.forEach { page ->
            assertFalse(page.text.firstOrNull()?.let(Character::isLowSurrogate) ?: false)
            assertFalse(page.text.lastOrNull()?.let(Character::isHighSurrogate) ?: false)
        }
        val emojiPages = layout.pages.indices.filter {
            layout.pages[it].firstParagraphIndex == 1
        }
        assertTrue(emojiPages.size > 1)
        assertEquals(emojiPages.first(), textPageForParagraph(layout.pages, 1))
        assertEquals(layout.pages.lastIndex, textPageForParagraph(layout.pages, 999))
    }
}
