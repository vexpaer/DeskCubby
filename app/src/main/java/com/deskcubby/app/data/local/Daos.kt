package com.deskcubby.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FlashThoughtDao {
    @Query("SELECT * FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY pinned DESC, createdAt ASC")
    fun observeActive(): Flow<List<FlashThoughtEntity>>

    @Query("SELECT * FROM flash_thoughts WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<FlashThoughtEntity>>

    @Query("SELECT * FROM flash_thoughts WHERE deletedAt IS NULL ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FlashThoughtEntity>>

    @Insert
    suspend fun insert(item: FlashThoughtEntity): Long

    @Query("UPDATE flash_thoughts SET content = :content, updatedAt = :now WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, now: Long)

    @Query("UPDATE flash_thoughts SET pinned = NOT pinned, updatedAt = :now WHERE id = :id")
    suspend fun togglePinned(id: Long, now: Long)

    @Query("UPDATE flash_thoughts SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE flash_thoughts SET deletedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("DELETE FROM flash_thoughts WHERE id = :id AND deletedAt IS NOT NULL")
    suspend fun permanentlyDelete(id: Long)

    @Query("DELETE FROM flash_thoughts WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long)
}

@Dao
interface BrowserRecordDao {
    @Query("SELECT * FROM browser_records ORDER BY lastVisitedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int = 200): Flow<List<BrowserRecordEntity>>

    @Query("SELECT * FROM browser_records WHERE favorite = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<BrowserRecordEntity>>

    @Query("SELECT * FROM browser_records WHERE url = :url LIMIT 1")
    suspend fun get(url: String): BrowserRecordEntity?

    @Upsert
    suspend fun upsert(item: BrowserRecordEntity)

    @Query("UPDATE browser_records SET favorite = :favorite WHERE url = :url")
    suspend fun setFavorite(url: String, favorite: Boolean)

    @Query("DELETE FROM browser_records WHERE favorite = 0")
    suspend fun clearNonFavorites()
}

@Dao
interface DiaryIndexDao {
    @Query("SELECT * FROM diary_index ORDER BY dateIso DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<DiaryIndexEntity>>

    @Query("SELECT * FROM diary_index ORDER BY dateIso DESC, name COLLATE NOCASE")
    suspend fun getAll(): List<DiaryIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DiaryIndexEntity>)

    @Query("DELETE FROM diary_index WHERE uri NOT IN (:activeUris)")
    suspend fun deleteMissing(activeUris: List<String>)

    @Query("DELETE FROM diary_index")
    suspend fun clear()

    @Transaction
    suspend fun replaceAfterSuccessfulScan(items: List<DiaryIndexEntity>) {
        if (items.isEmpty()) {
            clear()
        } else {
            insertAll(items)
            deleteMissing(items.map { it.uri })
        }
    }
}
