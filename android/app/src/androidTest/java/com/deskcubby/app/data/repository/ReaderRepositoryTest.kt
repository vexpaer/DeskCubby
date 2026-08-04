package com.deskcubby.app.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderRepositoryTest {
    @Test
    fun preferencesAreAtomicallyStoredAndReloadedFromBoundedJson() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "reader-test-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val expected = ReaderPreferences(
                background = ReaderBackground.CUSTOM,
                customBackgroundArgb = 0xFF314159.toInt(),
                fontSizeSp = 26f,
                lineHeightMultiplier = 1.8f,
                paragraphSpacingDp = 16f,
                orientation = ReaderOrientation.LANDSCAPE,
            )
            val repository = ReaderRepository(isolatedContext)
            // Construction must not touch the filesystem; initialization is explicitly async.
            assertFalse(isolatedFiles.exists())
            repository.initialize()
            repository.updatePreferences(expected)

            val stored = File(
                File(isolatedFiles, ReaderRepository.DIRECTORY_NAME),
                ReaderRepository.STATE_FILE_NAME,
            )
            assertTrue(stored.isFile)
            assertTrue(stored.length() in 1..ReaderRepository.MAX_STATE_BYTES.toLong())
            assertEquals(2, JSONObject(stored.readText(Charsets.UTF_8)).getInt("schemaVersion"))

            val reloaded = ReaderRepository(isolatedContext)
            assertEquals(ReaderPreferences(), reloaded.state.value.preferences)
            reloaded.initialize()
            assertEquals(expected, reloaded.state.value.preferences)
            assertFalse(File(stored.parentFile, "${ReaderRepository.STATE_FILE_NAME}.pending").exists())
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun codecRejectsDuplicateUrisAndNormalizesHostileDisplayValues() {
        val state = ReaderLibraryState(
            books = listOf(
                ReaderBook(
                    id = "book-a",
                    uri = "content://library/a.txt",
                    title = "A",
                    type = ReaderBookType.TXT,
                    addedAt = 1L,
                    lastOpenedAt = 2L,
                ),
            ),
            preferences = ReaderPreferences(),
        )
        assertEquals(state, ReaderStateCodec.decode(ReaderStateCodec.encode(state)))

        val duplicate = JSONObject(ReaderStateCodec.encode(state)).apply {
            getJSONArray("books").put(
                JSONObject(getJSONArray("books").getJSONObject(0).toString()).put("id", "book-b"),
            )
        }
        val failed = runCatching { ReaderStateCodec.decode(duplicate.toString()) }.isFailure
        assertTrue(failed)
    }

    @Test
    fun schemaOneReaderStateMigratesParagraphProgressAndDefaultCustomColor() {
        val current = ReaderLibraryState(
            books = listOf(
                ReaderBook(
                    id = "legacy-book",
                    uri = "content://library/legacy.txt",
                    title = "Legacy",
                    type = ReaderBookType.TXT,
                    addedAt = 1L,
                    lastOpenedAt = 2L,
                    textParagraphIndex = 37,
                    textPageIndex = 9,
                ),
            ),
            preferences = ReaderPreferences(background = ReaderBackground.SEPIA),
        )
        val legacy = JSONObject(ReaderStateCodec.encode(current)).apply {
            put("schemaVersion", 1)
            getJSONObject("preferences").remove("customBackgroundArgb")
            getJSONArray("books").getJSONObject(0).remove("textPageIndex")
        }

        val decoded = ReaderStateCodec.decode(legacy.toString())

        assertEquals(-1, decoded.books.single().textPageIndex)
        assertEquals(37, decoded.books.single().textParagraphIndex)
        assertEquals(ReaderPreferences().customBackgroundArgb, decoded.preferences.customBackgroundArgb)
    }

    @Test
    fun damagedCommittedStateIsPreservedAndBlocksLaterWrites() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "reader-damaged-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        val stateDirectory = File(isolatedFiles, ReaderRepository.DIRECTORY_NAME).apply { mkdirs() }
        val stateFile = File(stateDirectory, ReaderRepository.STATE_FILE_NAME)
        val damaged = "{not-valid-reader-json"
        stateFile.writeText(damaged, Charsets.UTF_8)
        try {
            val repository = ReaderRepository(isolatedContext)
            repository.initialize()

            assertEquals(ReaderStorageIssue.STATE_FILE_DAMAGED, repository.storageIssue.value)
            assertTrue(runCatching { repository.updatePreferences(ReaderPreferences()) }.isFailure)
            assertEquals(damaged, stateFile.readText(Charsets.UTF_8))
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun verifiedPendingStateRecoversOnlyWhenCommittedStateIsMissing() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "reader-pending-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        val expected = ReaderLibraryState(
            preferences = ReaderPreferences(background = ReaderBackground.GREEN),
        )
        val stateDirectory = File(isolatedFiles, ReaderRepository.DIRECTORY_NAME).apply { mkdirs() }
        val pending = File(stateDirectory, "${ReaderRepository.STATE_FILE_NAME}.pending")
        pending.writeText(ReaderStateCodec.encode(expected), Charsets.UTF_8)
        try {
            val repository = ReaderRepository(isolatedContext)
            repository.initialize()

            assertEquals(expected, repository.state.value)
            assertEquals(null, repository.storageIssue.value)
            assertTrue(File(stateDirectory, ReaderRepository.STATE_FILE_NAME).isFile)
            assertFalse(pending.exists())
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }
}
