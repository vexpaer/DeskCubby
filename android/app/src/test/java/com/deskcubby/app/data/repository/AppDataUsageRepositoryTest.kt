package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppDataUsageRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `private traversal totals regular files exactly`() {
        val root = temporaryFolder.newFolder("data")
        root.resolve("first.bin").writeBytes(ByteArray(7))
        root.resolve("nested").mkdirs()
        root.resolve("nested/second.bin").writeBytes(ByteArray(11))

        val measured = measurePrivateFileTree(root, maxEntries = 16)

        assertEquals(18L, measured.bytes)
        assertFalse(measured.partial)
    }

    @Test
    fun `entry cap reports a lower bound instead of an exact size`() {
        val root = temporaryFolder.newFolder("large")
        repeat(5) { index -> root.resolve("$index.bin").writeBytes(ByteArray(index + 1)) }

        val measured = measurePrivateFileTree(root, maxEntries = 2)

        assertTrue(measured.partial)
        assertTrue(measured.bytes in 0L..15L)
    }

    @Test
    fun `deadline reports a partial measurement`() {
        val root = temporaryFolder.newFolder("timed")
        root.resolve("payload.bin").writeBytes(ByteArray(9))

        val measured = measurePrivateFileTree(
            target = root,
            maxEntries = 16,
            deadlineNanos = 5L,
            nanoTime = { 5L },
        )

        assertEquals(0L, measured.bytes)
        assertTrue(measured.partial)
    }

    @Test
    fun `missing path is an exact zero and byte addition saturates`() {
        val measured = measurePrivateFileTree(
            temporaryFolder.root.resolve("missing"),
            maxEntries = 1,
        )

        assertEquals(FileMeasurement.EMPTY, measured)
        assertEquals(Long.MAX_VALUE, saturatedAddBytes(Long.MAX_VALUE - 2, 9))
    }
}
