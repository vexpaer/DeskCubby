package com.deskcubby.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.local.PoetryCategoryDao
import com.deskcubby.app.data.local.PoetryCategoryEntity
import com.deskcubby.app.data.local.SavedPoemDao
import com.deskcubby.app.data.local.SavedPoemEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Singleton
class PoetryBookRepository private constructor(
    private val dao: SavedPoemDao,
    private val categoryDao: PoetryCategoryDao?,
    private val database: AppDatabase?,
    private val presetCatalog: PoetryPresetCatalog?,
    @Suppress("UNUSED_PARAMETER") testOnly: Boolean,
) {
    @Inject
    constructor(
        dao: SavedPoemDao,
        categoryDao: PoetryCategoryDao,
        database: AppDatabase,
        presetCatalog: PoetryPresetCatalog,
    ) : this(dao, categoryDao, database, presetCatalog, false)

    internal constructor(dao: SavedPoemDao) : this(dao, null, null, null, true)

    val poems: Flow<List<SavedPoemEntity>> = dao.observeAll()
    val categories: Flow<List<PoetryCategoryEntity>> = categoryDao?.observeAll() ?: flowOf(emptyList())

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

    suspend fun create(content: String, source: String = "", categoryId: Long? = null): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            SavedPoemEntity(
                content = requireContent(content),
                source = requireSource(source),
                createdAt = now,
                updatedAt = now,
                categoryId = categoryId,
            ),
        )
        check(id > 0) { "Saved poem was not created" }
        return id
    }

    suspend fun update(
        id: Long,
        content: String,
        source: String = "",
        categoryId: Long? = null,
    ) {
        require(id > 0) { "Saved poem id must be positive" }
        val changed = dao.update(
            id = id,
            content = requireContent(content),
            source = requireSource(source),
            categoryId = categoryId,
            updatedAt = System.currentTimeMillis(),
        )
        check(changed == 1) { "Saved poem no longer exists" }
    }

    suspend fun delete(id: Long) {
        require(id > 0) { "Saved poem id must be positive" }
        check(dao.delete(id) == 1) { "Saved poem no longer exists" }
    }

    suspend fun setCategory(id: Long, categoryId: Long?) {
        require(id > 0) { "Saved poem id must be positive" }
        val now = System.currentTimeMillis()
        val changed = try {
            dao.setCategory(id, categoryId, now)
        } catch (error: SQLiteConstraintException) {
            if (categoryId == null) throw error
            dao.setCategory(id, null, now)
        }
        check(changed == 1) {
            "Saved poem no longer exists"
        }
    }

    suspend fun createCategory(name: String, colorArgb: Int): Long? {
        val categoryDao = requireNotNull(categoryDao)
        val normalizedName = normalizeCategoryName(name)
        if (normalizedName.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            categoryDao.insertIfNameAvailable(
                PoetryCategoryEntity(
                    name = normalizedName,
                    colorArgb = colorArgb or 0xFF000000.toInt(),
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
        val categoryDao = requireNotNull(categoryDao)
        if (id <= 0) return false
        val normalizedName = normalizeCategoryName(name)
        if (normalizedName.isEmpty()) return false
        return try {
            categoryDao.updateIfNameAvailable(
                id = id,
                name = normalizedName,
                colorArgb = colorArgb or 0xFF000000.toInt(),
                now = System.currentTimeMillis(),
            )
        } catch (_: SQLiteConstraintException) {
            false
        }
    }

    suspend fun deleteCategory(id: Long): Boolean =
        id > 0 && requireNotNull(categoryDao).deleteAndUncategorize(id)

    fun presetCategorySummaries(): List<PoetryPresetCategorySummary> =
        requireNotNull(presetCatalog).summaries()

    suspend fun importPresetCategory(presetId: String): PoetryPresetImportResult {
        val preset = checkNotNull(requireNotNull(presetCatalog).category(presetId)) {
            "Poetry preset category does not exist"
        }
        return requireNotNull(database).withTransaction {
            val categoryDao = requireNotNull(categoryDao)
            val now = System.currentTimeMillis()
            val categoryId = categoryDao.findIdByName(preset.summary.nameZh)
                ?: checkNotNull(
                    categoryDao.insertIfNameAvailable(
                        PoetryCategoryEntity(
                            name = preset.summary.nameZh,
                            colorArgb = preset.summary.colorArgb,
                            sortOrder = 0,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    ),
                ) { "Poetry preset category could not be created" }
            var addedCount = 0
            preset.poems.forEachIndexed { index, poem ->
                if (dao.findMatching(categoryId, poem.content, poem.source) == null) {
                    val inserted = dao.insert(
                        SavedPoemEntity(
                            content = poem.content,
                            source = poem.source,
                            // observeAll() is newest-first; decreasing timestamps preserve the
                            // source textbook order instead of displaying each import backwards.
                            createdAt = now - index,
                            updatedAt = now - index,
                            categoryId = categoryId,
                        ),
                    )
                    check(inserted > 0) { "Poetry preset entry could not be created" }
                    addedCount++
                }
            }
            PoetryPresetImportResult(
                categoryId = categoryId,
                addedCount = addedCount,
                existingCount = preset.poems.size - addedCount,
            )
        }
    }

    private fun requireContent(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Poem content must not be blank" }
        require(it.length <= MAX_CONTENT_CHARS) { "Poem content is too long" }
    }

    private fun requireSource(value: String): String = value.trim().also {
        require(it.length <= MAX_SOURCE_CHARS) { "Poem source is too long" }
    }

    private fun normalizeCategoryName(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").take(MAX_CATEGORY_NAME_CHARS)

    internal companion object {
        const val MAX_CONTENT_CHARS = 4_000
        const val MAX_SOURCE_CHARS = 512
        const val MAX_CATEGORY_NAME_CHARS = 100
    }
}

data class PoetryPresetImportResult(
    val categoryId: Long,
    val addedCount: Int,
    val existingCount: Int,
)
