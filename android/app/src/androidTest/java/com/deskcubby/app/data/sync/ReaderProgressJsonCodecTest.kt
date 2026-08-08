package com.deskcubby.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.repository.ReaderProgressRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderProgressJsonCodecTest {
    @Test
    fun payloadIsDeterministicSortedAndContainsNoLocalBookMetadata() {
        val txt = record("b", ReaderBookType.TXT, paragraph = 44, updatedAt = 20)
        val pdf = record("a", ReaderBookType.PDF, pdfPage = 7, updatedAt = 10)

        val first = ReaderProgressJsonCodec.encode(listOf(txt, pdf))
        val second = ReaderProgressJsonCodec.encode(listOf(pdf, txt))
        val raw = first.toString(Charsets.UTF_8)

        assertTrue(first.contentEquals(second))
        assertEquals(listOf(pdf, txt), ReaderProgressJsonCodec.decode(first))
        assertFalse(raw.contains("content://"))
        assertFalse(raw.contains("title", ignoreCase = true))
        assertFalse(raw.contains("cover", ignoreCase = true))
        val root = JSONObject(raw)
        assertEquals(ReaderProgressJsonCodec.FORMAT_VERSION, root.getInt("version"))
        assertEquals("a".repeat(64), root.getJSONArray("records").getJSONObject(0).getString("fingerprint"))
    }

    @Test
    fun decoderRejectsUnexpectedFieldsDuplicatesAndInvalidNumbers() {
        val valid = ReaderProgressJsonCodec.encode(
            listOf(record("a", ReaderBookType.PDF, pdfPage = 4, updatedAt = 10)),
        )
        val unexpected = JSONObject(valid.toString(Charsets.UTF_8))
            .put("bookTitle", "private")
            .toString()
            .toByteArray()
        val duplicate = JSONObject(valid.toString(Charsets.UTF_8)).apply {
            getJSONArray("records").put(getJSONArray("records").getJSONObject(0))
        }.toString().toByteArray()
        val fractional = JSONObject(valid.toString(Charsets.UTF_8)).apply {
            getJSONArray("records").getJSONObject(0).put("pdfPageIndex", 1.5)
        }.toString().toByteArray()
        val uppercaseFingerprint = JSONObject(valid.toString(Charsets.UTF_8)).apply {
            getJSONArray("records").getJSONObject(0).put("fingerprint", "A".repeat(64))
        }.toString().toByteArray()
        val numericType = JSONObject(valid.toString(Charsets.UTF_8)).apply {
            getJSONArray("records").getJSONObject(0).put("type", 1)
        }.toString().toByteArray()
        val oversizedPdfPageCount = JSONObject(valid.toString(Charsets.UTF_8)).apply {
            getJSONArray("records").getJSONObject(0).put("totalPages", 20_001)
        }.toString().toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(unexpected)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(duplicate)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(fractional)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(uppercaseFingerprint)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(numericType)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(oversizedPdfPageCount)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(valid + " trailing".toByteArray())
        }
    }

    @Test
    fun decoderRejectsMalformedUtf8AndOversizedPayload() {
        assertThrows(Exception::class.java) {
            ReaderProgressJsonCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReaderProgressJsonCodec.decode(
                ByteArray(ReaderProgressJsonCodec.MAX_JSON_BYTES + 1) { ' '.code.toByte() },
            )
        }
    }

    private fun record(
        fingerprintCharacter: String,
        type: ReaderBookType,
        paragraph: Int = 0,
        pdfPage: Int = 0,
        updatedAt: Long,
    ) = ReaderProgressRecord(
        fingerprint = fingerprintCharacter.repeat(64),
        type = type,
        textParagraphIndex = paragraph,
        pdfPageIndex = pdfPage,
        totalPages = 100,
        updatedAt = updatedAt,
    )
}
