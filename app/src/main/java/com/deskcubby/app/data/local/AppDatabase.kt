package com.deskcubby.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FlashThoughtEntity::class,
        ThoughtCategoryEntity::class,
        BrowserRecordEntity::class,
        DiaryIndexEntity::class,
        DateRecordEntity::class,
        SavedPoemEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        VaultItemEntity::class,
        GameStateEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flashThoughtDao(): FlashThoughtDao
    abstract fun thoughtCategoryDao(): ThoughtCategoryDao
    abstract fun browserRecordDao(): BrowserRecordDao
    abstract fun diaryIndexDao(): DiaryIndexDao
    abstract fun dateRecordDao(): DateRecordDao
    abstract fun savedPoemDao(): SavedPoemDao
    abstract fun aiChatDao(): AiChatDao
    abstract fun vaultItemDao(): VaultItemDao
    abstract fun gameStateDao(): GameStateDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `date_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT COLLATE NOCASE NOT NULL,
                        `icon` TEXT NOT NULL,
                        `dateIso` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `thought_categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `colorArgb` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_thought_categories_name` " +
                        "ON `thought_categories` (`name`)",
                )
                db.execSQL(
                    "ALTER TABLE `flash_thoughts` ADD COLUMN `categoryId` INTEGER DEFAULT NULL " +
                        "REFERENCES `thought_categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_flash_thoughts_categoryId` " +
                        "ON `flash_thoughts` (`categoryId`)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_poems` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `content` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_conversations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `modelConfigId` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_conversations_updatedAt` " +
                        "ON `ai_conversations` (`updatedAt`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `conversationId` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `reasoning` TEXT NOT NULL,
                        `imageUri` TEXT,
                        `imageMimeType` TEXT,
                        `imagePermissionOwned` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`conversationId`) REFERENCES `ai_conversations`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_messages_conversationId` " +
                        "ON `ai_messages` (`conversationId`)",
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `flash_thoughts` ADD COLUMN `highlighted` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vault_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `cipherText` TEXT NOT NULL,
                        `iv` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `game_states` (
                        `gameId` TEXT NOT NULL,
                        `highScore` INTEGER NOT NULL DEFAULT 0,
                        `saveJson` TEXT,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`gameId`)
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
