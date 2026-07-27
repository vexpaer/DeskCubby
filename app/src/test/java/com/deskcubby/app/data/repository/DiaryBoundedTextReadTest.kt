package com.deskcubby.app.data.repository

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryBoundedTextReadTest {
    @Test
    fun readsStrictUtf8AtTheExactByteLimit() = runBlocking {
        val expected = "日记🙂"
        val bytes = expected.toByteArray(Charsets.UTF_8)

        val actual = ByteArrayInputStream(bytes).readUtf8Bounded(bytes.size)

        assertEquals(expected, actual)
    }

    @Test
    fun rejectsTheFirstByteBeyondTheLimit() = runBlocking {
        val error = runCatching {
            ByteArrayInputStream("12345".toByteArray()).readUtf8Bounded(maxBytes = 4)
        }.exceptionOrNull()

        assertTrue(error is DiaryTextLimitExceededException)
        assertEquals(4, (error as DiaryTextLimitExceededException).maxBytes)
    }

    @Test
    fun rejectsMalformedUtf8InsteadOfReplacementDecoding() = runBlocking {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)

        val error = runCatching {
            ByteArrayInputStream(malformed).readUtf8Bounded(maxBytes = malformed.size)
        }.exceptionOrNull()

        assertTrue(error is DiaryTextInvalidUtf8Exception)
    }
}
