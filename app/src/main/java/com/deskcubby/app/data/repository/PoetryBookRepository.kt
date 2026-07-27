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

    /**
     * Loads the canonical Room row for editing. Callers must not reuse a list-card preview or any
     * visually ellipsized text as the editor's initial value.
     */
    suspend fun loadForEdit(id: Long): SavedPoemEntity {
        require(id > 0) { "Saved poem id must be positive" }
        val poem = checkNotNull(dao.getById(id)) { "Saved poem no longer exists" }
        check(poem.content.isNotBlank()) { "Saved poem content is unavailable" }
        check(poem.content.length <= MAX_CONTENT_CHARS) { "Saved poem content is too long" }
        check(poem.source.length <= MAX_SOURCE_CHARS) { "Saved poem source is too long" }
        return poem
    }

    suspend fun create(content: String, source: String = ""): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            SavedPoemEntity(
                content = requireContent(content),
                source = requireSource(source),
                createdAt = now,
                updatedAt = now,
            ),
        )
        check(id > 0) { "Saved poem was not created" }
        return id
    }

    suspend fun update(id: Long, content: String, source: String = "") {
        require(id > 0) { "Saved poem id must be positive" }
        val changed = dao.update(
            id = id,
            content = requireContent(content),
            source = requireSource(source),
            updatedAt = System.currentTimeMillis(),
        )
        check(changed == 1) { "Saved poem no longer exists" }
    }

    suspend fun delete(id: Long) {
        require(id > 0) { "Saved poem id must be positive" }
        check(dao.delete(id) == 1) { "Saved poem no longer exists" }
    }

    private fun requireContent(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Poem content must not be blank" }
        require(it.length <= MAX_CONTENT_CHARS) { "Poem content is too long" }
    }

    private fun requireSource(value: String): String = value.trim().also {
        require(it.length <= MAX_SOURCE_CHARS) { "Poem source is too long" }
    }

    internal companion object {
        const val MAX_CONTENT_CHARS = 4_000
        const val MAX_SOURCE_CHARS = 512
    }
}
