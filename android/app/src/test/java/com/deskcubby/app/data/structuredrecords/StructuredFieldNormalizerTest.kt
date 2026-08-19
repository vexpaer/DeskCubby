package com.deskcubby.app.data.structuredrecords

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredFieldNormalizerTest {

    @Test
    fun numberParsesWithUnitAndComma() {
        assertEquals(
            NormalizedFieldValue.Number(30.0),
            StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "30 次").value,
        )
        assertEquals(
            NormalizedFieldValue.Number(5.2),
            StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "5.2").value,
        )
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "abc").isError)
    }
    @Test
    fun timeNormalizes() {
        assertEquals(
            "12:36",
            StructuredFieldNormalizer.normalize(StructuredFieldType.TIME, "12:36").value?.displayText,
        )
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.TIME, "25:00").isError)
    }

    @Test
    fun durationAcceptsHhMmAndNumbers() {
        // "00:42" = 42 minutes = 2520s (HH:MM interpretation).
        assertEquals(
            2520L,
            (StructuredFieldNormalizer.normalize(StructuredFieldType.DURATION, "00:42").value as NormalizedFieldValue.Duration).seconds,
        )
        assertEquals(
            27300L,
            (StructuredFieldNormalizer.normalize(StructuredFieldType.DURATION, "7:35").value as NormalizedFieldValue.Duration).seconds,
        )
        assertEquals(
            90L,
            (StructuredFieldNormalizer.normalize(StructuredFieldType.DURATION, "90").value as NormalizedFieldValue.Duration).seconds,
        )
        assertEquals(
            "0:42",
            StructuredFieldNormalizer.formatDuration(2520),
        )
        assertEquals(
            "7:35",
            StructuredFieldNormalizer.formatDuration(27300),
        )
    }

    @Test
    fun numberRejectsAmbiguousTrailingGarbage() {
        // A numeric prefix followed by a real unit is fine...
        assertEquals(
            NormalizedFieldValue.Number(30.0),
            StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "30 次").value,
        )
        assertEquals(
            NormalizedFieldValue.Number(5.2),
            StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "5.2 km").value,
        )
        // ...but tails that are not a coherent unit are rejected instead of silently truncated.
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "1.2.3").isError)
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "12abc34").isError)
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "12-5").isError)
    }

    @Test
    fun numberAcceptsFullWidthCommaDecimal() {
        // Chinese IME commonly produces "，" for a decimal separator; it maps to "." like "1,5".
        assertEquals(
            NormalizedFieldValue.Number(1.5),
            StructuredFieldNormalizer.normalize(StructuredFieldType.NUMBER, "1，5").value,
        )
    }

    @Test
    fun normalizeRejectsReservedMarkerTokens() {
        // Free-text values must not smuggle the protocol's marker tokens past the normalizer.
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.WORD, "abc<!--dc:/f_x-->def").isError)
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.TYPE, "值 --> 垃圾").isError)
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.WORD, "今天很好").value is NormalizedFieldValue.Word)
    }

    @Test
    fun timeRejectsInvalidSeconds() {
        assertTrue(StructuredFieldNormalizer.normalize(StructuredFieldType.TIME, "23:59:99").isError)
        // HH:mm:ss is accepted for entry but canonicalized to HH:mm.
        assertEquals(
            "12:34",
            StructuredFieldNormalizer.normalize(StructuredFieldType.TIME, "12:34:56").value?.displayText,
        )
    }

    @Test
    fun wordNeverBecomesNumber() {
        val value = StructuredFieldNormalizer.normalize(StructuredFieldType.WORD, "今天很好").value
        assertTrue(value is NormalizedFieldValue.Word)
    }

    @Test
    fun allowedSelectorsByType() {
        assertTrue(StructuredFieldNormalizer.allowedSelectors(StructuredFieldType.NUMBER).contains(FieldSelector.SUM))
        assertTrue(StructuredFieldNormalizer.allowedSelectors(StructuredFieldType.WORD).contains(FieldSelector.COUNT))
        assertTrue(!StructuredFieldNormalizer.allowedSelectors(StructuredFieldType.WORD).contains(FieldSelector.SUM))
        assertTrue(!StructuredFieldNormalizer.allowedSelectors(StructuredFieldType.TIME).contains(FieldSelector.SUM))
    }
}
