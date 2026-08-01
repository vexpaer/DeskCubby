package com.deskcubby.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedPoemDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var poemDao: SavedPoemDao
    private lateinit var categoryDao: PoetryCategoryDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        poemDao = database.savedPoemDao()
        categoryDao = database.poetryCategoryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun observeAllSortsByCreatedAtThenIdDescending() = runBlocking {
        val oldestId = poemDao.insert(poem(content = "oldest", createdAt = 10L))
        val firstNewestId = poemDao.insert(poem(content = "first newest", createdAt = 20L))
        val secondNewestId = poemDao.insert(poem(content = "second newest", createdAt = 20L))

        assertEquals(
            listOf(secondNewestId, firstNewestId, oldestId),
            poemDao.observeAll().first().map { it.id },
        )
    }

    @Test
    fun updateChangesEditableFieldsAndPreservesIdentityAndCreationTime() = runBlocking {
        val poemId = poemDao.insert(
            poem(
                content = "before",
                source = "old source",
                createdAt = 10L,
            ),
        )

        assertEquals(
            1,
            poemDao.update(
                id = poemId,
                content = "after",
                source = "new source",
                categoryId = null,
                updatedAt = 30L,
            ),
        )

        val updated = poemDao.getAllForBackup().single()
        assertEquals(poemId, updated.id)
        assertEquals("after", updated.content)
        assertEquals("new source", updated.source)
        assertEquals(10L, updated.createdAt)
        assertEquals(30L, updated.updatedAt)
    }

    @Test
    fun getByIdReturnsCanonicalMultilineBodyAndMissingRowsReturnNull() = runBlocking {
        val fullContent = "第一行\n第二行\n第三行"
        val poemId = poemDao.insert(
            poem(
                content = fullContent,
                source = "作者《标题》",
                createdAt = 10L,
            ),
        )

        assertEquals(fullContent, poemDao.getById(poemId)?.content)
        assertEquals(null, poemDao.getById(poemId + 10_000))
    }

    @Test
    fun deleteRemovesOnlyRequestedPoem() = runBlocking {
        val deletedId = poemDao.insert(poem(content = "delete me", createdAt = 10L))
        val retainedId = poemDao.insert(poem(content = "keep me", createdAt = 20L))

        assertEquals(1, poemDao.delete(deletedId))
        assertEquals(0, poemDao.delete(deletedId))

        val remaining = poemDao.getAllForBackup()
        assertEquals(listOf(retainedId), remaining.map { it.id })
        assertEquals("keep me", remaining.single().content)
    }

    @Test
    fun replaceAllForBackupReplacesExistingRowsAndSupportsEmptyReplacement() = runBlocking {
        poemDao.insert(poem(content = "old one", createdAt = 10L))
        poemDao.insert(poem(content = "old two", createdAt = 20L))

        poemDao.replaceAllForBackup(
            listOf(
                poem(id = 22L, content = "replacement two", createdAt = 40L),
                poem(id = 8L, content = "replacement one", createdAt = 30L),
            ),
        )

        val replacements = poemDao.getAllForBackup()
        assertEquals(listOf(8L, 22L), replacements.map { it.id })
        assertEquals(listOf("replacement one", "replacement two"), replacements.map { it.content })

        poemDao.replaceAllForBackup(emptyList())

        assertTrue(poemDao.getAllForBackup().isEmpty())
    }

    @Test
    fun moveSupportsChangingTheFirstPoemAndMovingAnotherPoemToFirst() = runBlocking {
        val first = poemDao.insertAtEnd(poem(content = "first", createdAt = 30L))
        val second = poemDao.insertAtEnd(poem(content = "second", createdAt = 20L))
        val third = poemDao.insertAtEnd(poem(content = "third", createdAt = 10L))

        poemDao.move(first, targetIndex = 2)
        assertEquals(listOf(second, third, first), poemDao.observeAll().first().map { it.id })

        poemDao.move(first, targetIndex = 0)
        assertEquals(listOf(first, second, third), poemDao.observeAll().first().map { it.id })
    }

    @Test
    fun categoryDeletionSupportsKeepingOrDeletingItsPoems() = runBlocking {
        val keepCategory = categoryDao.insert(
            category(id = 1L, name = "Keep poems"),
        )
        val deleteCategory = categoryDao.insert(
            category(id = 2L, name = "Delete poems"),
        )
        val keptPoem = poemDao.insertAtEnd(
            poem(content = "becomes uncategorized", createdAt = 30L, categoryId = keepCategory),
        )
        poemDao.insertAtEnd(
            poem(content = "deleted with category", createdAt = 20L, categoryId = deleteCategory),
        )

        assertTrue(categoryDao.deleteAndUncategorize(keepCategory))
        assertEquals(null, poemDao.getById(keptPoem)?.categoryId)
        assertTrue(categoryDao.deleteWithPoems(deleteCategory))
        assertEquals(listOf(keptPoem), poemDao.getAllForBackup().map { it.id })
    }

    private fun poem(
        content: String,
        createdAt: Long,
        id: Long = 0L,
        source: String = "",
        updatedAt: Long = createdAt,
        categoryId: Long? = null,
    ) = SavedPoemEntity(
        id = id,
        content = content,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
        categoryId = categoryId,
    )

    private fun category(id: Long, name: String) = PoetryCategoryEntity(
        id = id,
        name = name,
        colorArgb = 0xFF336699.toInt(),
        sortOrder = id,
        createdAt = id,
        updatedAt = id,
    )
}
