package com.deskcubby.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [FlashThoughtEntity::class, BrowserRecordEntity::class, DiaryIndexEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flashThoughtDao(): FlashThoughtDao
    abstract fun browserRecordDao(): BrowserRecordDao
    abstract fun diaryIndexDao(): DiaryIndexDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE flash_thoughts ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0",
                )
                val orderedIds = buildList {
                    db.query(
                        "SELECT id FROM flash_thoughts ORDER BY pinned DESC, createdAt ASC, id ASC",
                    ).use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }
                orderedIds.forEachIndexed { index, id ->
                    db.execSQL(
                        "UPDATE flash_thoughts SET sortOrder = ? WHERE id = ?",
                        arrayOf(index.toLong(), id),
                    )
                }
            }
        }
    }
}
