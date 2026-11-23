package com.deskcubby.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.AppDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealPhotoCompressionAndroidTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: DiaryFileRepository
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = DiaryFileRepository(context, database.diaryIndexDao())
    }

    @After
    fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
        database.close()
    }

    @Test
    fun compressesLargeRotatedCameraJpegIntoUprightBoundedJpeg() {
        val dir = File(context.cacheDir, "meal-camera").apply { mkdirs() }
        val sourceFile = File.createTempFile("test-camera-", ".jpg", dir).also(tempFiles::add)
        // Generate a 4000x3000 landscape photo tagged with EXIF ROTATE_90 (portrait 3000x4000).
        val rawBitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(rawBitmap)
            canvas.drawColor(Color.rgb(220, 180, 120))
            // Distinct colored corner so orientation can be verified after compression.
            val paint = android.graphics.Paint().apply { color = Color.rgb(30, 90, 200) }
            canvas.drawRect(0f, 0f, 1000f, 1000f, paint)
            sourceFile.outputStream().buffered().use { out ->
                assertTrue(rawBitmap.compress(Bitmap.CompressFormat.JPEG, 98, out))
            }
        } finally {
            rawBitmap.recycle()
        }
        ExifInterface(sourceFile.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString(),
            )
            saveAttributes()
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sourceFile,
        )
        val compressed = repository.compressMealImageToBytes(uri, quality = 80)

        assertNotNull("Large high-quality JPEG should compress cleanly", compressed)
        val bytes = requireNotNull(compressed)
        assertTrue(
            "Compressed size (${bytes.size}) should be smaller than source (${sourceFile.length()})",
            bytes.size < sourceFile.length(),
        )
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        try {
            // Upright portrait: height > width and max edge <= 2560.
            assertTrue("Expected upright portrait (${decoded.width}x${decoded.height})", decoded.height > decoded.width)
            assertTrue(decoded.height <= 2560)
            assertEquals(1920, decoded.width)
            assertEquals(2560, decoded.height)
            // After 90-degree clockwise rotation, the original top-left blue block moves to top-right.
            val topRight = decoded.getPixel(decoded.width - 100, 100)
            assertTrue("Blue channel should dominate top-right after rotation", Color.blue(topRight) > Color.red(topRight))
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun compressesTransparentPngOntoWhiteBackground() {
        val dir = File(context.cacheDir, "meal-camera").apply { mkdirs() }
        val sourceFile = File.createTempFile("test-alpha-", ".png", dir).also(tempFiles::add)
        val rawBitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            rawBitmap.eraseColor(Color.TRANSPARENT)
            sourceFile.outputStream().buffered().use { out ->
                assertTrue(rawBitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            rawBitmap.recycle()
        }
        // Make sure PNG is large enough or test compression when smaller; add noise to force PNG > JPEG.
        val noisy = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(1200 * 900) { i ->
                if (i < 1200 * 100) {
                    Color.TRANSPARENT
                } else {
                    Color.argb(255, (i * 31) and 0xFF, (i * 17) and 0xFF, (i * 13) and 0xFF)
                }
            }
            noisy.setPixels(pixels, 0, 1200, 0, 0, 1200, 900)
            sourceFile.outputStream().buffered().use { out ->
                assertTrue(noisy.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            noisy.recycle()
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sourceFile,
        )
        val compressed = repository.compressMealImageToBytes(uri, quality = 80)

        assertNotNull(compressed)
        val decoded = BitmapFactory.decodeByteArray(requireNotNull(compressed), 0, compressed.size)
        assertNotNull(decoded)
        try {
            val topPixel = decoded.getPixel(50, 20)
            assertTrue("Transparent region must flatten to white, got R=${Color.red(topPixel)}", Color.red(topPixel) > 240)
            assertTrue(Color.green(topPixel) > 240)
            assertTrue(Color.blue(topPixel) > 240)
        } finally {
            decoded.recycle()
        }
    }
}
