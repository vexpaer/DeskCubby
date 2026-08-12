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
            repository.begin(
                EngagementKind.READING,
                "book-id",
                readingTitle = "Persistent Book",
            )
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
            assertEquals(2, root.getInt("schemaVersion"))
            assertEquals(gameMillis, root.getJSONObject("gameTotalsMillis").getLong("minesweeper"))
            assertEquals(readingMillis, root.getJSONObject("readingTotalsMillis").getLong("book-id"))
            assertEquals(
                "Persistent Book",
                root.getJSONObject("readingTitles").getString("book-id"),
            )

            val reloaded = EngagementTimeRepository(isolatedContext)
            assertEquals(gameMillis, reloaded.snapshot.value.total(EngagementKind.GAME, "minesweeper"))
            assertEquals(readingMillis, reloaded.snapshot.value.total(EngagementKind.READING, "book-id"))
            assertEquals("Persistent Book", reloaded.snapshot.value.readingTitles["book-id"])
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun detachedEndCannotRemoveImmediatelyRestartedSession() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "engagement-race-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val repository = EngagementTimeRepository(isolatedContext)
            repository.begin(EngagementKind.GAME, "spider")
            delay(20)
            val firstInterval = requireNotNull(
                repository.endNow(EngagementKind.GAME, "spider"),
            )

            // Mirrors Activity recreation: the new UI begins before old duration I/O finishes.
            repository.begin(EngagementKind.GAME, "spider")
            repository.commit(firstInterval)
            val afterFirstCommit = repository.snapshot.value.total(EngagementKind.GAME, "spider")
            delay(20)
            repository.end(EngagementKind.GAME, "spider")

            val total = repository.snapshot.value.total(EngagementKind.GAME, "spider")
            assertTrue(total > afterFirstCommit)
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }

    @Test
    fun schemaOneDurationsUpgradeWithoutLossAndRecoverCurrentShelfTitle() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(application.cacheDir, "engagement-v1-${UUID.randomUUID()}")
        val isolatedContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val directory = File(isolatedFiles, EngagementTimeRepository.DIRECTORY_NAME)
            directory.mkdirs()
            val stored = File(directory, EngagementTimeRepository.FILE_NAME)
            stored.writeText(
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("gameTotalsMillis", JSONObject().put("spider", 42_000L))
                    .put("readingTotalsMillis", JSONObject().put("legacy-book", 84_000L))
                    .toString(),
                Charsets.UTF_8,
            )

            val repository = EngagementTimeRepository(isolatedContext)
            assertEquals(84_000L, repository.snapshot.value.readingTotalsMillis["legacy-book"])
            assertTrue(repository.snapshot.value.readingTitles.isEmpty())

            repository.rememberReadingTitles(mapOf("legacy-book" to "Recovered Title"))

            val upgraded = JSONObject(stored.readText(Charsets.UTF_8))
            assertEquals(2, upgraded.getInt("schemaVersion"))
            assertEquals(
                84_000L,
                upgraded.getJSONObject("readingTotalsMillis").getLong("legacy-book"),
            )
            assertEquals(
                "Recovered Title",
                upgraded.getJSONObject("readingTitles").getString("legacy-book"),
            )
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }
}
