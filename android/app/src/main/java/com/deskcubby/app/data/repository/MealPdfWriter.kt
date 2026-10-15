package com.deskcubby.app.data.repository

import java.io.Closeable
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Minimal streaming PDF 1.4 writer used by the meal-calendar exporter.
 *
 * Objects are written to [output] as soon as they are produced and only their byte offsets stay in
 * memory, so a document with thousands of embedded JPEG photos never holds more than one image at
 * a time. Object numbers may be reserved before they are written (for parent/child references);
 * [finish] refuses to emit the cross-reference table while any reserved object is still missing.
 */
internal class MealPdfWriter(output: OutputStream) : Closeable {
    private val out = CountingOutputStream(output)
    private val offsets = ArrayList<Long>()
    private var finished = false

    init {
        // The binary comment marks the file as binary for transfer tools, as the spec recommends.
        out.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
    }

    val bytesWritten: Long get() = out.count

    fun reserveObject(): Int {
        check(!finished) { "PDF 已完成" }
        offsets += UNWRITTEN
        return offsets.size
    }

    fun writeObject(number: Int, body: String) {
        beginObject(number)
        writeAscii(body)
        writeAscii("\nendobj\n")
    }

    fun writeStreamObject(number: Int, dictionaryEntries: String, data: ByteArray) {
        beginObject(number)
        writeAscii("<< $dictionaryEntries /Length ${data.size} >>\nstream\n")
        out.write(data)
        writeAscii("\nendstream\nendobj\n")
    }

    fun finish(catalogNumber: Int) {
        check(!finished) { "PDF 已完成" }
        require(catalogNumber in 1..offsets.size) { "PDF 目录对象无效" }
        check(offsets.none { it == UNWRITTEN }) { "PDF 对象缺失" }
        val xrefOffset = out.count
        val xref = StringBuilder()
        xref.append("xref\n0 ").append(offsets.size + 1).append('\n')
        xref.append("0000000000 65535 f \n")
        offsets.forEach { offset ->
            xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset))
        }
        xref.append("trailer\n<< /Size ").append(offsets.size + 1)
            .append(" /Root ").append(catalogNumber).append(" 0 R >>\n")
            .append("startxref\n").append(xrefOffset).append("\n%%EOF\n")
        writeAscii(xref.toString())
        out.flush()
        finished = true
    }

    override fun close() {
        out.close()
    }

    private fun beginObject(number: Int) {
        check(!finished) { "PDF 已完成" }
        require(number in 1..offsets.size) { "PDF 对象编号无效" }
        check(offsets[number - 1] == UNWRITTEN) { "PDF 对象重复写入" }
        offsets[number - 1] = out.count
        writeAscii("$number 0 obj\n")
    }

    private fun writeAscii(text: String) {
        out.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private companion object {
        const val UNWRITTEN = -1L
    }
}
