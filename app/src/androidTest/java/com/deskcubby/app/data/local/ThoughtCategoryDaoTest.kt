package com.deskcubby.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThoughtCategoryDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var thoughtDao: FlashThoughtDao
    private lateinit var categoryDao: ThoughtCategoryDao

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        thoughtDao = database.flashThoughtDao()
        categoryDao = database.thoughtCategoryDao()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun insertIfNameAvailableRejectsCaseInsensitiveDuplicate() = runBlocking {
        val firstId = categoryDao.insertIfNameAvailable(category(name = "Ideas", now = 1L))
        val duplicateId = categoryDao.insertIfNameAvailable(category(name = "ideas", now = 2L))

        assertNotNull(firstId)
        assertNull(duplicateId)
        assertEquals(listOf("Ideas"), categoryDao.getAllForBackup().map { it.name })
    }

    @Test
    fun deleteAndUncategorizeMovesActiveAndTrashedThoughtsToUncategorized() = runBlocking {
        val categoryId = categoryDao.insertIfNameAvailable(category(name = "Inbox", now = 1L))!!
        val activeId = thoughtDao.insert(
            thought(
                content = "active",
                createdAt = 10L,
                sortOrder = 0L,
                categoryId = categoryId,
            ),
        )
        val trashedId = thoughtDao.insert(
            thought(
                content = "trashed",
                createdAt = 20L,
                sortOrder = 1L,
                categoryId = categoryId,
                deletedAt = 30L,
            ),
        )

        assertTrue(categoryDao.deleteAndUncategorize(categoryId))

        val thoughtsById = thoughtDao.getAllForBackup().associateBy { it.id }
        assertNull(thoughtsById.getValue(activeId).categoryId)
        assertNull(thoughtsById.getValue(activeId).deletedAt)
        assertNull(thoughtsById.getValue(trashedId).categoryId)
        assertEquals(30L, thoughtsById.getValue(trashedId).deletedAt)
        assertNull(categoryDao.findIdByName("Inbox"))
    }

    @Test
    fun moveActiveInCategoryPreservesInterleavedOtherCategorySlots() = runBlocking {
        val categoryA = categoryDao.insertIfNameAvailable(category(name = "A", now = 1L))!!
        val categoryB = categoryDao.insertIfNameAvailable(category(name = "B", now = 2L))!!
        val a1 = thoughtDao.insert(thought("A1", 10L, 0L, categoryA))
        val b1 = thoughtDao.insert(thought("B1", 11L, 1L, categoryB))
        val a2 = thoughtDao.insert(thought("A2", 12L, 2L, categoryA))
        val b2 = thoughtDao.insert(thought("B2", 13L, 3L, categoryB))
        val a3 = thoughtDao.insert(thought("A3", 14L, 4L, categoryA))

        thoughtDao.moveActiveInCategory(id = a3, targetIndex = 0, categoryId = categoryA)

        assertEquals(listOf(a3, b1, a1, b2, a2), thoughtDao.getActiveIdsInOrder())
        assertEquals(listOf(a3, a1, a2), thoughtDao.getActiveIdsInCategory(categoryA))
        assertEquals(listOf(b1, b2), thoughtDao.getActiveIdsInCategory(categoryB))
    }

    private fun category(name: String, now: Long) = ThoughtCategoryEntity(
        name = name,
        colorArgb = 0xff336699.toInt(),
        sortOrder = 99L,
        createdAt = now,
        updatedAt = now,
    )

    private fun thought(
        content: String,
        createdAt: Long,
        sortOrder: Long,
        categoryId: Long?,
        deletedAt: Long? = null,
    ) = FlashThoughtEntity(
        content = content,
        createdAt = createdAt,
        updatedAt = createdAt,
        deletedAt = deletedAt,
        sortOrder = sortOrder,
        categoryId = categoryId,
    )
}
