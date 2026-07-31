package com.deskcubby.app.data.repository

import com.deskcubby.app.data.local.SavedPoemDao
import com.deskcubby.app.data.local.SavedPoemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PoetryBookRepositoryTest {
    @Test
    fun loadForEditQueriesCanonicalRowAndReturnsEveryLine() = runBlocking {
        val fullContent = "第一行\n第二行\n第三行"
        val dao = FakeSavedPoemDao(
            SavedPoemEntity(
                id = 7,
                content = fullContent,
                source = "作者《标题》",
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        val repository = PoetryBookRepository(dao)

        val loaded = repository.loadForEdit(7)

        assertEquals(1, dao.getByIdCalls)
        assertEquals(fullContent, loaded.content)
    }

    @Test
    fun loadForEditRejectsMissingRowInsteadOfOpeningAnEmptyEditor() {
        val repository = PoetryBookRepository(FakeSavedPoemDao())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.loadForEdit(99) }
        }
    }

    @Test
    fun updateOfDeletedRowFailsInsteadOfReportingSuccess() {
        val repository = PoetryBookRepository(FakeSavedPoemDao())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.update(99, "完整正文", "出处") }
        }
    }

    private class FakeSavedPoemDao(
        vararg initial: SavedPoemEntity,
    ) : SavedPoemDao {
        private val items = MutableStateFlow(initial.associateBy(SavedPoemEntity::id))
        private var nextId = (initial.maxOfOrNull(SavedPoemEntity::id) ?: 0L) + 1L
        var getByIdCalls: Int = 0
            private set

        override fun observeAll(): Flow<List<SavedPoemEntity>> = items.map { current ->
            current.values.sortedWith(
                compareBy<SavedPoemEntity>(SavedPoemEntity::sortOrder)
                    .thenByDescending(SavedPoemEntity::createdAt)
                    .thenByDescending(SavedPoemEntity::id),
            )
        }

        override suspend fun getById(id: Long): SavedPoemEntity? {
            getByIdCalls += 1
            return items.value[id]
        }

        override fun observeAllForBackup(): Flow<List<SavedPoemEntity>> = items.map { current ->
            current.values.sortedBy(SavedPoemEntity::id)
        }

        override suspend fun getAllForBackup(): List<SavedPoemEntity> =
            items.value.values.sortedBy(SavedPoemEntity::id)

        override suspend fun insertAllForBackup(items: List<SavedPoemEntity>) {
            this.items.value = this.items.value + items.associateBy(SavedPoemEntity::id)
        }

        override suspend fun clearAllForBackup() {
            items.value = emptyMap()
        }

        override suspend fun insert(item: SavedPoemEntity): Long {
            val id = item.id.takeIf { it > 0 } ?: nextId++
            items.value = items.value + (id to item.copy(id = id))
            return id
        }

        override suspend fun nextSortOrder(): Long =
            (items.value.values.maxOfOrNull(SavedPoemEntity::sortOrder) ?: -1L) + 1L

        override suspend fun getIdsInOrder(): List<Long> =
            items.value.values
                .sortedWith(
                    compareBy<SavedPoemEntity>(SavedPoemEntity::sortOrder)
                        .thenByDescending(SavedPoemEntity::createdAt)
                        .thenByDescending(SavedPoemEntity::id),
                )
                .map(SavedPoemEntity::id)

        override suspend fun getIdsInCategory(categoryId: Long?): List<Long> =
            items.value.values
                .filter { it.categoryId == categoryId }
                .sortedWith(
                    compareBy<SavedPoemEntity>(SavedPoemEntity::sortOrder)
                        .thenByDescending(SavedPoemEntity::createdAt)
                        .thenByDescending(SavedPoemEntity::id),
                )
                .map(SavedPoemEntity::id)

        override suspend fun updateSortOrder(id: Long, sortOrder: Long) {
            val current = items.value[id] ?: return
            items.value = items.value + (id to current.copy(sortOrder = sortOrder))
        }

        override suspend fun update(
            id: Long,
            content: String,
            source: String,
            categoryId: Long?,
            updatedAt: Long,
        ): Int {
            val current = items.value[id] ?: return 0
            items.value = items.value + (
                id to current.copy(
                    content = content,
                    source = source,
                    categoryId = categoryId,
                    updatedAt = updatedAt,
                )
            )
            return 1
        }

        override suspend fun setCategory(id: Long, categoryId: Long?, updatedAt: Long): Int {
            val current = items.value[id] ?: return 0
            items.value = items.value + (
                id to current.copy(categoryId = categoryId, updatedAt = updatedAt)
            )
            return 1
        }

        override suspend fun findMatching(
            categoryId: Long,
            content: String,
            source: String,
        ): Long? = items.value.values.firstOrNull {
            it.categoryId == categoryId && it.content == content && it.source == source
        }?.id

        override suspend fun delete(id: Long): Int {
            if (id !in items.value) return 0
            items.value = items.value - id
            return 1
        }
    }
}
