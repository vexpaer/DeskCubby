package com.deskcubby.app.data.media

import android.content.Context
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class GalleryOriginalStagerTest {
    @Test
    fun stageOwnedFileMovesCameraCaptureIntoStagingWithoutDataLoss() = runBlocking {
        val root = createTempDirectory(prefix = "dc-stage-test-").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val cacheDir = File(root, "cache/meal-camera").apply { mkdirs() }
            val cameraFile = File(cacheDir, "meal-123.jpg")
            val payload = ByteArray(4096) { index -> (index % 251).toByte() }
            cameraFile.writeBytes(payload)

            val context = Mockito.mock(Context::class.java)
            Mockito.`when`(context.filesDir).thenReturn(filesDir)

            val staged = GalleryOriginalStager.stageOwnedFile(
                context = context,
                sourceFile = cameraFile,
                displayNameHint = "午餐:original/photo",
                mimeType = "image/jpeg",
            )

            val stagedFile = File(staged.path)
            assertTrue(stagedFile.isFile)
            assertFalse("Camera temp file should be moved out of cache", cameraFile.exists())
            assertArrayEquals(payload, stagedFile.readBytes())
            assertEquals("image/jpeg", staged.mimeType)
            assertEquals("午餐_original_photo.jpeg", staged.displayName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun stageOwnedFileRejectsEmptyFileAndLeavesNoStagingArtifact() = runBlocking {
        val root = createTempDirectory(prefix = "dc-stage-empty-").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val emptyFile = File(root, "empty.jpg").apply { createNewFile() }
            val context = Mockito.mock(Context::class.java)
            Mockito.`when`(context.filesDir).thenReturn(filesDir)

            val error = runCatching {
                GalleryOriginalStager.stageOwnedFile(context, emptyFile, "lunch-original")
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            val stagingDir = File(filesDir, "media-staging")
            assertTrue(stagingDir.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupOrphansDeletesOnlyExpiredStagingFiles() {
        val root = createTempDirectory(prefix = "dc-stage-orphan-").toFile()
        try {
            val filesDir = File(root, "files").apply { mkdirs() }
            val stagingDir = File(filesDir, "media-staging").apply { mkdirs() }
            val now = 1_700_000_000_000L
            val expired = File(stagingDir, "old.jpg").apply {
                writeBytes(byteArrayOf(1, 2, 3))
                setLastModified(now - 8L * 24L * 60L * 60L * 1000L)
            }
            val fresh = File(stagingDir, "fresh.jpg").apply {
                writeBytes(byteArrayOf(4, 5, 6))
                setLastModified(now - 60_000L)
            }
            val context = Mockito.mock(Context::class.java)
            Mockito.`when`(context.filesDir).thenReturn(filesDir)

            GalleryOriginalStager.cleanupOrphans(context, now = now)

            assertFalse(expired.exists())
            assertTrue(fresh.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
