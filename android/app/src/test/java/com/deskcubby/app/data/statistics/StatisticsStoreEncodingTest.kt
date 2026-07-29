package com.deskcubby.app.data.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StatisticsStoreEncodingTest {
    @Test
    fun `encoding is decoded and compared before it can be committed`() {
        var decodedText: String? = null

        val (encoded, verified) = encodeAndVerifyStatisticsValue(
            value = 42,
            encode = { """{"value":$it}""" },
            decode = { text ->
                decodedText = text
                42
            },
        )

        assertEquals("""{"value":42}""", encoded)
        assertEquals(encoded, decodedText)
        assertEquals(42, verified)
    }

    @Test
    fun `round trip mismatch fails before commit`() {
        assertThrows(StatisticsJsonException::class.java) {
            encodeAndVerifyStatisticsValue(
                value = 42,
                encode = Int::toString,
                decode = { 41 },
            )
        }
    }

    @Test
    fun `oversized encoding fails before decode`() {
        var decoded = false

        assertThrows(StatisticsJsonException::class.java) {
            encodeAndVerifyStatisticsValue(
                value = "12345",
                encode = { it },
                decode = {
                    decoded = true
                    it
                },
                maximumBytes = 4,
            )
        }
        assertEquals(false, decoded)
    }
}
