package com.deskcubby.app.data.statistics

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngagementTimeRepositoryTest {
    @Test
    fun gameAndReadingDurationsAreVerifiedAndReloadedFromJson() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "engagement-test-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val repository = EngagementTimeRepository(isolatedContext)
            repository.begin(EngagementKind.GAME, "minesweeper")
            delay(20)
            repository.end(EngagementKind.GAME, "minesweeper")
            repository.begin(EngagementKind.READING, "book-id")
            delay(20)
            repository.end(EngagementKind.READING, "book-id")

            val gameMillis = repository.snapshot.value.total(EngagementKind.GAME, "minesweeper")
            val readingMillis = repository.snapshot.value.total(EngagementKind.READING, "book-id")
            assertTrue(gameMillis > 0L)
            assertTrue(readingMillis > 0L)

            val stored = File(
                File(isolatedFiles, EngagementTimeRepository.DIRECTORY_NAME),
                EngagementTimeRepository.FILE_NAME,
            )
            val root = JSONObject(stored.readText(Charsets.UTF_8))
            assertEquals(1, root.getInt("schemaVersion"))
            assertEquals(gameMillis, root.getJSONObject("gameTotalsMillis").getLong("minesweeper"))
            assertEquals(readingMillis, root.getJSONObject("readingTotalsMillis").getLong("book-id"))

            val reloaded = EngagementTimeRepository(isolatedContext)
            assertEquals(gameMillis, reloaded.snapshot.value.total(EngagementKind.GAME, "minesweeper"))
            assertEquals(readingMillis, reloaded.snapshot.value.total(EngagementKind.READING, "book-id"))
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }
}
