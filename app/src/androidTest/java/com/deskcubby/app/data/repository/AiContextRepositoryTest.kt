package com.deskcubby.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiContextRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun thoughtCandidatesCarryLocalCategoryGroupsInConfiguredOrder() = runBlocking {
        val categoryDao = database.thoughtCategoryDao()
        val laterCategory = categoryDao.insert(
            ThoughtCategoryEntity(
                name = "稍后",
                colorArgb = 0,
                sortOrder = 20,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        val firstCategory = categoryDao.insert(
            ThoughtCategoryEntity(
                name = "优先",
                colorArgb = 0,
                sortOrder = 10,
                createdAt = 2,
                updatedAt = 2,
            ),
        )
        val thoughtDao = database.flashThoughtDao()
        thoughtDao.insert(
            FlashThoughtEntity(
                content = "稍后处理",
                categoryId = laterCategory,
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        thoughtDao.insert(
            FlashThoughtEntity(
                content = "优先处理",
                categoryId = firstCategory,
                createdAt = 20,
                updatedAt = 20,
            ),
        )
        thoughtDao.insert(
            FlashThoughtEntity(
                content = "未分类",
                createdAt = 30,
                updatedAt = 30,
            ),
        )
        val repository = AiContextRepository(
            diaryIndexDao = database.diaryIndexDao(),
            flashThoughtDao = thoughtDao,
            thoughtCategoryDao = categoryDao,
            dateRecordDao = database.dateRecordDao(),
            savedPoemDao = database.savedPoemDao(),
            diaryFileRepository = DiaryFileRepository(context, database.diaryIndexDao()),
        )

        val candidates = repository.listCandidates().filter {
            it.source == AiContextSource.THOUGHT
        }

        assertEquals(listOf("优先处理", "稍后处理", "未分类"), candidates.map { it.title })
        assertEquals(listOf("优先", "稍后", ""), candidates.map { it.groupTitle })
        assertEquals(listOf(10L, 20L, Long.MAX_VALUE), candidates.map { it.groupSortOrder })
        assertEquals(3, candidates.map { it.groupKey }.distinct().size)

        val frozen = repository.freeze(listOf(candidates.first().selectionKey))
        assertEquals("优先", frozen.items.single().attribution)
    }
}
