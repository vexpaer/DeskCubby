package com.deskcubby.app.data.repository

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
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
            ),
        )

        assertEquals(19f, normalized.fontSizeSp)
        assertEquals(1.6f, normalized.lineHeightMultiplier)
        assertEquals(36f, normalized.paragraphSpacingDp)
    }
}
