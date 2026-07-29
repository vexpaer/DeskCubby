package com.deskcubby.app.data.repository

import com.deskcubby.app.data.local.DateRecordDao
import com.deskcubby.app.data.local.DateRecordEntity
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class DateRecordRepository @Inject constructor(
    private val dao: DateRecordDao,
) {
    val records: Flow<List<DateRecordEntity>> = dao.observeAll()

    fun observeAll(): Flow<List<DateRecordEntity>> = records

    suspend fun create(name: String, icon: String, dateIso: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            DateRecordEntity(
                name = requireName(name),
                icon = requireIcon(icon),
                dateIso = requireDateIso(dateIso),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun update(id: Long, name: String, icon: String, dateIso: String) {
        require(id > 0) { "Date record id must be positive" }
        dao.update(
            id = id,
            name = requireName(name),
            icon = requireIcon(icon),
            dateIso = requireDateIso(dateIso),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun delete(id: Long) {
        require(id > 0) { "Date record id must be positive" }
        dao.delete(id)
    }

    private fun requireName(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Date record name must not be blank" }
        require(it.length <= MAX_NAME_CHARS) { "Date record name is too long" }
    }

    private fun requireIcon(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Date record icon must not be blank" }
        require(it.length <= MAX_ICON_CHARS) { "Date record icon is too long" }
    }

    private fun requireDateIso(value: String): String = value.trim().also {
        require(it.length == ISO_DATE_LENGTH) { "Date record date must use yyyy-MM-dd" }
        try {
            LocalDate.parse(it)
        } catch (error: Exception) {
            throw IllegalArgumentException("Date record date must be a valid yyyy-MM-dd date", error)
        }
    }

    private companion object {
        const val ISO_DATE_LENGTH = 10
        const val MAX_NAME_CHARS = 256
        const val MAX_ICON_CHARS = 64
    }
}
