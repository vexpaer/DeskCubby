package com.deskcubby.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "flash_thoughts")
data class FlashThoughtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder: Long = 0,
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
