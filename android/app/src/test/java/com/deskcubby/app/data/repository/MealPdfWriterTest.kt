package com.deskcubby.app.data.repository

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPdfWriterTest {
    @Test
    fun crossReferenceOffsetsPointAtEveryObject() {
        val bytes = ByteArrayOutputStream()
        val writer = MealPdfWriter(bytes)
        val catalog = writer.reserveObject()
        val pages = writer.reserveObject()
        val image = writer.reserveObject()
        writer.writeStreamObject(image, "/Type /XObject", byteArrayOf(0, 1, 2, -1))
        writer.writeObject(pages, "<< /Type /Pages /Kids [] /Count 0 >>")
        writer.writeObject(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
        writer.finish(catalog)

        val pdf = String(bytes.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-1.4\n"))
        assertTrue(pdf.endsWith("%%EOF\n"))
        val startXref = pdf.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertTrue(pdf.startsWith("xref\n0 4\n", startXref))
        val entries = pdf.substring(startXref).lines().drop(3).take(3)
        entries.forEachIndexed { index, entry ->
            assertEquals(20, entry.length + 1)
            val offset = entry.take(10).toInt()
            assertTrue(pdf.startsWith("${index + 1} 0 obj\n", offset))
        }
        assertTrue(pdf.contains("/Type /XObject /Length 4 >>\nstream\n"))
        assertTrue(pdf.contains("/Root 1 0 R"))
    }

    @Test(expected = IllegalStateException::class)
    fun missingReservedObjectFailsClosed() {
        val writer = MealPdfWriter(ByteArrayOutputStream())
        val catalog = writer.reserveObject()
        writer.reserveObject()
        writer.writeObject(catalog, "<< /Type /Catalog >>")
        writer.finish(catalog)
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateObjectWriteIsRejected() {
        val writer = MealPdfWriter(ByteArrayOutputStream())
        val catalog = writer.reserveObject()
        writer.writeObject(catalog, "<< >>")
        writer.writeObject(catalog, "<< >>")
    }
}
