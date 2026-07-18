package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiaryTextUtilsTest {
    @Test
    fun categorizedImagesIncrementWithoutOverwriting() {
        val existing = (1..9).map { "2026-07-18_早餐_${it.toString().padStart(2, '0')}.jpg" }
        val result = DiaryTextUtils.nextMediaFileName(
            pattern = "{date}_{category}_{seq}",
            dateText = "2026-07-18",
            category = "早餐",
            extension = "JPG",
            existingNames = existing,
        )
        assertEquals("2026-07-18_早餐_10.jpg", result)
        assertFalse(result in existing)
    }

    @Test
    fun fileNameRemovesWindowsAndSafUnsafeCharacters() {
        assertEquals("旅行_武汉_记录_", DiaryTextUtils.sanitizeFileName("旅行/武汉:记录?"))
    }

    @Test
    fun wordCountHandlesChineseAndLatinTokens() {
        assertEquals(7, DiaryTextUtils.wordCount("今天 hello world 2026 真好"))
    }

    @Test
    fun draggingImageSwapsOnlyMarkdownBlocks() {
        val image = "![早餐](<../Attachments/a.jpg>)"
        val original = "第一段\n\n$image\n\n最后一段"
        assertEquals("$image\n\n第一段\n\n最后一段", DiaryTextUtils.moveStandaloneImage(original, image, -1))
        assertEquals("第一段\n\n最后一段\n\n$image", DiaryTextUtils.moveStandaloneImage(original, image, 1))
    }
}
