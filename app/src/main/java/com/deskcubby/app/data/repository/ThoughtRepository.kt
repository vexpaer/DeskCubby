package com.deskcubby.app.data.repository

import com.deskcubby.app.data.local.FlashThoughtDao
import com.deskcubby.app.data.local.FlashThoughtEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThoughtRepository @Inject constructor(
    private val dao: FlashThoughtDao,
) {
    val active = dao.observeActive()
    val trash = dao.observeTrash()
    val recent = dao.observeRecent(5)

    suspend fun create(content: String) {
        val now = System.currentTimeMillis()
        dao.insert(FlashThoughtEntity(content = content.trim(), createdAt = now, updatedAt = now))
    }

    suspend fun update(id: Long, content: String) = dao.updateContent(id, content.trim(), System.currentTimeMillis())
    suspend fun togglePinned(id: Long) = dao.togglePinned(id, System.currentTimeMillis())
    suspend fun delete(id: Long) = dao.softDelete(id, System.currentTimeMillis())
    suspend fun restore(id: Long) = dao.restore(id, System.currentTimeMillis())
    suspend fun permanentlyDelete(id: Long) = dao.permanentlyDelete(id)
}
