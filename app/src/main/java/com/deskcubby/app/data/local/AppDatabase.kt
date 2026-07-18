package com.deskcubby.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [FlashThoughtEntity::class, BrowserRecordEntity::class, DiaryIndexEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flashThoughtDao(): FlashThoughtDao
    abstract fun browserRecordDao(): BrowserRecordDao
    abstract fun diaryIndexDao(): DiaryIndexDao
}
