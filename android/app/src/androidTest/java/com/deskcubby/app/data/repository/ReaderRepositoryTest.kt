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
                pdfZoomPercent = 135,
                orientation = ReaderOrientation.LANDSCAPE,
                libraryLayout = ReaderLibraryLayout.GRID,
                showGridBookTitles = false,
                showProgressPercentage = true,
                immersiveMode = true,
                customForegroundArgb = 0xFFABCDEF.toInt(),
                chapterDetectionMode = ReaderChapterDetectionMode.CUSTOM,
                customChapterRegex = "^Scene\\s+[0-9]+$",
                chapterHeadingMaxChars = 96,
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
            assertEquals(7, JSONObject(stored.readText(Charsets.UTF_8)).getInt("schemaVersion"))

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
                    fingerprint = "a".repeat(64),
                    progressUpdatedAt = 10L,
                    pageOffsetPercent = 65,
                ),
            ),
            preferences = ReaderPreferences(),
            progressLedger = listOf(
                ReaderProgressRecord(
                    fingerprint = "a".repeat(64),
                    type = ReaderBookType.TXT,
                    updatedAt = 10L,
                    pageOffsetPercent = 65,
                ),
            ),
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
    fun schemaSixReaderStateDefaultsPageOffsetToPageStart() {
        val current = ReaderLibraryState(
            books = listOf(
                ReaderBook(
                    id = "legacy-offset",
                    uri = "content://library/legacy-offset.pdf",
                    title = "Legacy offset",
                    type = ReaderBookType.PDF,
                    addedAt = 1L,
                    lastOpenedAt = 2L,
                    pdfPageIndex = 13,
                    fingerprint = "e".repeat(64),
                    totalPages = 100,
                    progressUpdatedAt = 20L,
                    pageOffsetPercent = 65,
                ),
            ),
            progressLedger = listOf(
                ReaderProgressRecord(
                    fingerprint = "e".repeat(64),
                    type = ReaderBookType.PDF,
                    pdfPageIndex = 13,
                    totalPages = 100,
                    updatedAt = 20L,
                    pageOffsetPercent = 65,
                ),
            ),
        )
        val legacy = JSONObject(ReaderStateCodec.encode(current)).apply {
            put("schemaVersion", 6)
            getJSONArray("books").getJSONObject(0).remove("pageOffsetPercent")
            getJSONArray("progressLedger").getJSONObject(0).remove("pageOffsetPercent")
        }

        val decoded = ReaderStateCodec.decode(legacy.toString())

        assertEquals(0, decoded.books.single().pageOffsetPercent)
        assertEquals(0, decoded.progressLedger.single().pageOffsetPercent)
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
    fun schemaTwoReaderStateDefaultsNewChapterPreferences() {
        val encoded = JSONObject(
            ReaderStateCodec.encode(
                ReaderLibraryState(
                    preferences = ReaderPreferences(
                        chapterDetectionMode = ReaderChapterDetectionMode.CUSTOM,
                        customChapterRegex = "^Scene [0-9]+$",
                        chapterHeadingMaxChars = 88,
                    ),
                ),
            ),
        ).apply {
            put("schemaVersion", 2)
            getJSONObject("preferences").apply {
                remove("chapterDetectionMode")
                remove("customChapterRegex")
                remove("chapterHeadingMaxChars")
            }
        }

        assertEquals(
            ReaderPreferences(),
            ReaderStateCodec.decode(encoded.toString()).preferences,
        )
    }

    @Test
    fun schemaFourReaderStateDefaultsLibraryCoverAndSyncFields() {
        val fingerprint = "a".repeat(64)
        val encoded = JSONObject(
            ReaderStateCodec.encode(
                ReaderLibraryState(
                    books = listOf(
                        ReaderBook(
                            id = "legacy-pdf",
                            uri = "content://library/legacy.pdf",
                            title = "Legacy PDF",
                            type = ReaderBookType.PDF,
                            addedAt = 1L,
                            lastOpenedAt = 2L,
                            coverUri = "content://covers/legacy.png",
                            fingerprint = fingerprint,
                            totalPages = 80,
                            progressUpdatedAt = 99L,
                        ),
                    ),
                    preferences = ReaderPreferences(
                        libraryLayout = ReaderLibraryLayout.GRID,
                        showProgressPercentage = true,
                        immersiveMode = true,
                        customForegroundArgb = 0xFF123456.toInt(),
                    ),
                    progressLedger = listOf(
                        ReaderProgressRecord(
                            fingerprint = fingerprint,
                            type = ReaderBookType.PDF,
                            pdfPageIndex = 10,
                            totalPages = 80,
                            updatedAt = 99L,
                        ),
                    ),
                ),
            ),
        ).apply {
            put("schemaVersion", 4)
            getJSONObject("preferences").apply {
                remove("libraryLayout")
                remove("showProgressPercentage")
                remove("immersiveMode")
                remove("customForegroundArgb")
            }
            getJSONArray("books").getJSONObject(0).apply {
                remove("coverUri")
                remove("fingerprint")
                remove("totalPages")
                remove("progressUpdatedAt")
            }
            remove("progressLedger")
        }

        val decoded = ReaderStateCodec.decode(encoded.toString())

        assertEquals(ReaderLibraryLayout.LIST, decoded.preferences.libraryLayout)
        assertFalse(decoded.preferences.showProgressPercentage)
        assertFalse(decoded.preferences.immersiveMode)
        assertEquals(null, decoded.preferences.customForegroundArgb)
        assertEquals(null, decoded.books.single().coverUri)
        assertEquals(null, decoded.books.single().fingerprint)
        assertEquals(0, decoded.books.single().totalPages)
        assertEquals(0L, decoded.books.single().progressUpdatedAt)
        assertTrue(decoded.progressLedger.isEmpty())
    }

    @Test
    fun unmatchedProgressLedgerAppliesWhenSameBookIsImportedLater() {
        val fingerprint = "b".repeat(64)
        val remote = ReaderProgressRecord(
            fingerprint = fingerprint,
            type = ReaderBookType.PDF,
            pdfPageIndex = 42,
            totalPages = 100,
            updatedAt = 1_000L,
        )
        val withoutBook = mergeReaderProgress(ReaderLibraryState(), listOf(remote)).state

        assertEquals(listOf(remote), withoutBook.progressLedger)
        val withBook = withoutBook.copy(
            books = listOf(
                ReaderBook(
                    id = "same-pdf",
                    uri = "content://library/same.pdf",
                    title = "Same PDF",
                    type = ReaderBookType.PDF,
                    addedAt = 1L,
                    lastOpenedAt = 1L,
                    fingerprint = fingerprint,
                    totalPages = 100,
                ),
            ),
        )

        val applied = mergeReaderProgress(withBook, withBook.progressLedger)

        assertEquals(1, applied.result.matchedBooks)
        assertEquals(1, applied.result.updatedBooks)
        assertEquals(42, applied.state.books.single().pdfPageIndex)
        assertEquals(1_000L, applied.state.books.single().progressUpdatedAt)
    }

    @Test
    fun syncedTxtProgressUsesCanonicalParagraphAndRequestsLocalPageRemap() {
        val fingerprint = "c".repeat(64)
        val state = ReaderLibraryState(
            books = listOf(
                ReaderBook(
                    id = "same-txt",
                    uri = "content://library/same.txt",
                    title = "Same TXT",
                    type = ReaderBookType.TXT,
                    addedAt = 1L,
                    lastOpenedAt = 1L,
                    fingerprint = fingerprint,
                    totalPages = 80,
                    textPageIndex = 5,
                    textParagraphIndex = 50,
                    progressUpdatedAt = 10L,
                ),
            ),
        )
        val remote = ReaderProgressRecord(
            fingerprint = fingerprint,
            type = ReaderBookType.TXT,
            textPageIndex = 30,
            textParagraphIndex = 900,
            totalPages = 60,
            updatedAt = 20L,
        )

        val book = mergeReaderProgress(state, listOf(remote)).state.books.single()

        assertEquals(-1, book.textPageIndex)
        assertEquals(900, book.textParagraphIndex)
        assertEquals(20L, book.progressUpdatedAt)
    }

    @Test
    fun equalTimestampProgressUsesLaterFivePercentCheckpoint() {
        val fingerprint = "f".repeat(64)
        val earlier = ReaderProgressRecord(
            fingerprint = fingerprint,
            type = ReaderBookType.PDF,
            pdfPageIndex = 13,
            totalPages = 100,
            updatedAt = 100L,
            pageOffsetPercent = 60,
        )
        val later = earlier.copy(pageOffsetPercent = 65)

        val merged = mergeReaderProgress(
            ReaderLibraryState(progressLedger = listOf(earlier)),
            listOf(later),
        ).state

        assertEquals(65, merged.progressLedger.single().pageOffsetPercent)
    }

    @Test
    fun externalProgressExportOmitsLocalPageOffset() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "reader-export-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        val fingerprint = "1".repeat(64)
        val progress = ReaderProgressRecord(
            fingerprint = fingerprint,
            type = ReaderBookType.PDF,
            pdfPageIndex = 13,
            totalPages = 100,
            updatedAt = 100L,
            pageOffsetPercent = 65,
        )
        val state = ReaderLibraryState(
            books = listOf(
                ReaderBook(
                    id = "private-offset",
                    uri = "content://library/private-offset.pdf",
                    title = "Private offset",
                    type = ReaderBookType.PDF,
                    addedAt = 1L,
                    lastOpenedAt = 2L,
                    pdfPageIndex = 13,
                    fingerprint = fingerprint,
                    totalPages = 100,
                    progressUpdatedAt = 100L,
                    pageOffsetPercent = 65,
                ),
            ),
            progressLedger = listOf(progress),
        )
        val stateDirectory = File(isolatedFiles, ReaderRepository.DIRECTORY_NAME).apply { mkdirs() }
        File(stateDirectory, ReaderRepository.STATE_FILE_NAME)
            .writeText(ReaderStateCodec.encode(state), Charsets.UTF_8)
        try {
            val repository = ReaderRepository(isolatedContext)
            repository.initialize()

            val exported = repository.exportProgressRecords().single()

            assertEquals(13, exported.pdfPageIndex)
            assertEquals(0, exported.pageOffsetPercent)
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun rollbackReplacementRestoresOnlyProgressAndLedger() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "reader-rollback-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        val fingerprint = "d".repeat(64)
        val remote = ReaderProgressRecord(
            fingerprint = fingerprint,
            type = ReaderBookType.PDF,
            pdfPageIndex = 70,
            totalPages = 100,
            updatedAt = 2_000L,
        )
        val previous = ReaderProgressRecord(
            fingerprint = fingerprint,
            type = ReaderBookType.PDF,
            pdfPageIndex = 12,
            totalPages = 100,
            updatedAt = 1_000L,
        )
        val initial = ReaderLibraryState(
            books = listOf(
                ReaderBook(
                    id = "rollback-pdf",
                    uri = "content://library/rollback.pdf",
                    title = "Do not replace",
                    type = ReaderBookType.PDF,
                    addedAt = 11L,
                    lastOpenedAt = 22L,
                    pdfPageIndex = 70,
                    coverUri = "content://covers/keep.png",
                    fingerprint = fingerprint,
                    totalPages = 100,
                    progressUpdatedAt = 2_000L,
                ),
            ),
            preferences = ReaderPreferences(background = ReaderBackground.NIGHT),
            progressLedger = listOf(remote),
        )
        val stateDirectory = File(isolatedFiles, ReaderRepository.DIRECTORY_NAME).apply { mkdirs() }
        File(stateDirectory, ReaderRepository.STATE_FILE_NAME)
            .writeText(ReaderStateCodec.encode(initial), Charsets.UTF_8)
        try {
            val repository = ReaderRepository(isolatedContext)
            repository.initialize()
            repository.replaceProgressRecordsForRollback(listOf(previous))

            val restored = repository.state.value
            val book = restored.books.single()
            assertEquals("content://library/rollback.pdf", book.uri)
            assertEquals("Do not replace", book.title)
            assertEquals("content://covers/keep.png", book.coverUri)
            assertEquals(ReaderBackground.NIGHT, restored.preferences.background)
            assertEquals(12, book.pdfPageIndex)
            assertEquals(1_000L, book.progressUpdatedAt)
            assertEquals(listOf(previous), restored.progressLedger)

            val reloaded = ReaderRepository(isolatedContext)
            reloaded.initialize()
            assertEquals(restored, reloaded.state.value)
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun smartChapterDetectionHandlesChineseEnglishMarkdownAndSpecialHeadings() {
        listOf(
            "第一百二十三章 风雪夜归人",
            "Chapter XLII: The Answer",
            "【第七回】故人重逢",
            "# Markdown chapter",
            "尾声",
            "12. Numbered section",
        ).forEach { heading ->
            assertTrue("Expected chapter heading: $heading", isReaderChapterHeading(heading))
        }
        assertFalse(isReaderChapterHeading("这是一段普通的正文，不应该进入目录。"))
    }

    @Test
    fun customChapterRegexCanReplaceSmartRulesAndHonorsLengthLimit() {
        val custom = ReaderPreferences(
            chapterDetectionMode = ReaderChapterDetectionMode.CUSTOM,
            customChapterRegex = "^Scene\\s+[0-9]+$",
            chapterHeadingMaxChars = 40,
        )

        assertTrue(isReaderChapterHeading("Scene 18", custom))
        assertFalse(isReaderChapterHeading("Chapter 18", custom))
        assertFalse(isReaderChapterHeading("Scene ${"1".repeat(50)}", custom))
        assertTrue(isValidReaderChapterRegex(custom.customChapterRegex))
        assertFalse(isValidReaderChapterRegex("[unfinished"))
    }

    @Test
    fun detectedChapterStartsAStableLogicalPage() {
        val layout = paginateReaderText(
            paragraphs = listOf(
                "opening text",
                "第一章 起点",
                "chapter body",
                "Scene 2",
                "custom body",
            ),
            targetChars = 200,
            preferences = ReaderPreferences(
                chapterDetectionMode = ReaderChapterDetectionMode.SMART_AND_CUSTOM,
                customChapterRegex = "^Scene\\s+[0-9]+$",
            ),
        )

        assertEquals(listOf("第一章 起点", "Scene 2"), layout.chapters.map { it.title })
        layout.chapters.forEach { chapter ->
            assertEquals(chapter.paragraphIndex, layout.pages[chapter.pageIndex].firstParagraphIndex)
        }
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
