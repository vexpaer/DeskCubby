package com.deskcubby.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.deskcubby.app.data.local.FlashThoughtDao
import com.deskcubby.app.data.local.FlashThoughtEntity
import com.deskcubby.app.data.local.ThoughtCategoryDao
import com.deskcubby.app.data.local.ThoughtCategoryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThoughtRepository @Inject constructor(
    private val dao: FlashThoughtDao,
    private val categoryDao: ThoughtCategoryDao,
) {
    val active = dao.observeActive()
    val trash = dao.observeTrash()
    val recent = dao.observeRecent(5)
    val categories = categoryDao.observeAll()

    suspend fun create(content: String, categoryId: Long? = null) {
        val now = System.currentTimeMillis()
        val item = FlashThoughtEntity(
            content = content.trim(),
            createdAt = now,
            updatedAt = now,
            categoryId = categoryId,
        )
        try {
            dao.insertAtEnd(item)
        } catch (error: SQLiteConstraintException) {
            if (categoryId == null) throw error
            dao.insertAtEnd(item.copy(categoryId = null))
        }
    }

    suspend fun update(id: Long, content: String) = dao.updateContent(id, content.trim(), System.currentTimeMillis())
    suspend fun togglePinned(id: Long) = dao.togglePinned(id, System.currentTimeMillis())
    suspend fun delete(id: Long) = dao.softDelete(id, System.currentTimeMillis())
    suspend fun restore(id: Long) = dao.restoreToActiveList(id, System.currentTimeMillis())
    suspend fun permanentlyDelete(id: Long) = dao.permanentlyDelete(id)
    suspend fun move(id: Long, targetIndex: Int) = dao.moveActive(id, targetIndex)
    suspend fun moveInCategory(id: Long, targetIndex: Int, categoryId: Long?) =
        dao.moveActiveInCategory(id, targetIndex, categoryId)
    suspend fun setCategory(id: Long, categoryId: Long?) {
        try {
            dao.setCategory(id, categoryId)
        } catch (error: SQLiteConstraintException) {
            if (categoryId == null) throw error
            dao.setCategory(id, null)
        }
    }

    suspend fun createCategory(name: String, colorArgb: Int): Long? {
        val normalizedName = name.trim().take(MAX_CATEGORY_NAME_LENGTH)
        if (normalizedName.isBlank()) return null
        val now = System.currentTimeMillis()
        return try {
            categoryDao.insertIfNameAvailable(
                ThoughtCategoryEntity(
                    name = normalizedName,
                    colorArgb = colorArgb,
                    sortOrder = 0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } catch (_: SQLiteConstraintException) {
            null
        }
    }

    suspend fun updateCategory(id: Long, name: String, colorArgb: Int): Boolean {
        val normalizedName = name.trim().take(MAX_CATEGORY_NAME_LENGTH)
        if (normalizedName.isBlank()) return false
        return try {
            categoryDao.updateIfNameAvailable(
                id = id,
                name = normalizedName,
                colorArgb = colorArgb,
                now = System.currentTimeMillis(),
            )
        } catch (_: SQLiteConstraintException) {
            false
        }
    }

    suspend fun deleteCategory(id: Long): Boolean = categoryDao.deleteAndUncategorize(id)

    companion object {
        const val MAX_CATEGORY_NAME_LENGTH = 40
    }
}
