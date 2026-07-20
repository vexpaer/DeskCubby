package com.deskcubby.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "flash_thoughts",
    foreignKeys = [
        ForeignKey(
            entity = ThoughtCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("categoryId")],
)
data class FlashThoughtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
    @ColumnInfo(defaultValue = "NULL") val categoryId: Long? = null,
)

@Entity(
    tableName = "thought_categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class ThoughtCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val colorArgb: Int,
    val sortOrder: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "browser_records")
data class BrowserRecordEntity(
    @PrimaryKey val url: String,
    val title: String,
    val lastVisitedAt: Long,
    val visitCount: Int = 1,
    val favorite: Boolean = false,
)

@Entity(tableName = "diary_index")
data class DiaryIndexEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val title: String,
    val dateIso: String,
    val monthKey: String,
    val lastModified: Long,
    val size: Long,
    val wordCount: Int,
    val sha256: String,
    val indexedAt: Long,
)

@Entity(tableName = "date_records")
data class DateRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val dateIso: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "saved_poems")
data class SavedPoemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val source: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)
