package com.deskcubby.app.data.repository

import com.deskcubby.app.data.local.SavedPoemDao
import com.deskcubby.app.data.local.SavedPoemEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class PoetryBookRepository @Inject constructor(
    private val dao: SavedPoemDao,
) {
    val poems: Flow<List<SavedPoemEntity>> = dao.observeAll()

    suspend fun create(content: String, source: String = ""): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            SavedPoemEntity(
                content = requireContent(content),
                source = requireSource(source),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun update(id: Long, content: String, source: String = "") {
        require(id > 0) { "Saved poem id must be positive" }
        dao.update(
            id = id,
            content = requireContent(content),
            source = requireSource(source),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun delete(id: Long) {
        require(id > 0) { "Saved poem id must be positive" }
        dao.delete(id)
    }

    private fun requireContent(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Poem content must not be blank" }
        require(it.length <= MAX_CONTENT_CHARS) { "Poem content is too long" }
    }

    private fun requireSource(value: String): String = value.trim().also {
        require(it.length <= MAX_SOURCE_CHARS) { "Poem source is too long" }
    }

    private companion object {
        const val MAX_CONTENT_CHARS = 4_000
        const val MAX_SOURCE_CHARS = 512
    }
}
