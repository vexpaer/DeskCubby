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
        PoetryCategoryEntity::class,
        SavedPoemEntity::class,
        AiConversationEntity::class,
        AiMessageEntity::class,
        AiAttachmentEntity::class,
        AgentRunEntity::class,
        AgentToolEventEntity::class,
        AgentMutationEntity::class,
        AiTaskQueueEntity::class,
        VaultItemEntity::class,
        GameStateEntity::class,
        GameStatisticEntity::class,
        UsageHistoryEntity::class,
        UsageDayEntity::class,
        UsageAppDurationEntity::class,
        UsageDeviceEntity::class,
        StepHistoryEntity::class,
        StepDayEntity::class,
        LegacyStatisticsMigrationEntity::class,
        StructuredRecordFileEntity::class,
        StructuredRecordOccurrenceEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun flashThoughtDao(): FlashThoughtDao
    abstract fun thoughtCategoryDao(): ThoughtCategoryDao
    abstract fun browserRecordDao(): BrowserRecordDao
    abstract fun diaryIndexDao(): DiaryIndexDao
    abstract fun dateRecordDao(): DateRecordDao
    abstract fun poetryCategoryDao(): PoetryCategoryDao
    abstract fun savedPoemDao(): SavedPoemDao
    abstract fun aiChatDao(): AiChatDao
    abstract fun agentDao(): AgentDao
    abstract fun vaultItemDao(): VaultItemDao
    abstract fun gameStateDao(): GameStateDao
    abstract fun gameStatisticDao(): GameStatisticDao
    abstract fun usageStatisticsDao(): UsageStatisticsDao
    abstract fun stepStatisticsDao(): StepStatisticsDao
    abstract fun legacyStatisticsMigrationDao(): LegacyStatisticsMigrationDao
    abstract fun aiTaskDao(): AiTaskDao
    abstract fun structuredRecordDao(): StructuredRecordDao

    companion object {
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `structured_record_files` (
                        `sourceFile` TEXT NOT NULL,
                        `modifiedAt` INTEGER NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `parsedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`sourceFile`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `structured_record_occurrences` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `journalDay` TEXT NOT NULL,
                        `sourceFile` TEXT NOT NULL,
                        `sourceFileModifiedAt` INTEGER NOT NULL,
                        `fieldId` TEXT NOT NULL,
                        `rawValue` TEXT NOT NULL,
                        `normalizedValue` TEXT NOT NULL,
                        `valueType` TEXT NOT NULL,
                        `orderInFile` INTEGER NOT NULL,
                        `parsedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_structured_record_occurrences_fieldId_journalDay` " +
                        "ON `structured_record_occurrences` (`fieldId`, `journalDay`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_structured_record_occurrences_sourceFile` " +
                        "ON `structured_record_occurrences` (`sourceFile`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_structured_record_occurrences_journalDay` " +
                        "ON `structured_record_occurrences` (`journalDay`)",
                )
            }
        }

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

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `vault_items` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0",
                )
                val orderedIds = buildList {
                    db.query(
                        "SELECT id FROM `vault_items` ORDER BY updatedAt DESC, id DESC",
                    ).use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }
                orderedIds.forEachIndexed { index, id ->
                    db.execSQL(
                        "UPDATE `vault_items` SET `sortOrder` = ? WHERE `id` = ?",
                        arrayOf(index.toLong(), id),
                    )
                }
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `poetry_categories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT COLLATE NOCASE NOT NULL,
                        `colorArgb` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_poetry_categories_name` " +
                        "ON `poetry_categories` (`name`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_poems_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `content` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `categoryId` INTEGER DEFAULT NULL,
                        FOREIGN KEY(`categoryId`) REFERENCES `poetry_categories`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `saved_poems_new` (`id`, `content`, `source`, `createdAt`, `updatedAt`)
                    SELECT `id`, `content`, `source`, `createdAt`, `updatedAt` FROM `saved_poems`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `saved_poems`")
                db.execSQL("ALTER TABLE `saved_poems_new` RENAME TO `saved_poems`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_saved_poems_categoryId` " +
                        "ON `saved_poems` (`categoryId`)",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `saved_poems` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0",
                )
                val orderedIds = buildList {
                    db.query(
                        "SELECT id FROM `saved_poems` ORDER BY createdAt DESC, id DESC",
                    ).use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getLong(0))
                    }
                }
                orderedIds.forEachIndexed { index, id ->
                    db.execSQL(
                        "UPDATE `saved_poems` SET `sortOrder` = ? WHERE `id` = ?",
                        arrayOf(index.toLong(), id),
                    )
                }
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_histories` (
                        `ownerId` TEXT NOT NULL,
                        `trackingStartedOn` TEXT,
                        `backfillCompletedThrough` TEXT,
                        PRIMARY KEY(`ownerId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_days` (
                        `ownerId` TEXT NOT NULL,
                        `dateIso` TEXT NOT NULL,
                        `zoneId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `collectedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerId`, `dateIso`),
                        FOREIGN KEY(`ownerId`) REFERENCES `usage_histories`(`ownerId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_usage_days_ownerId` " +
                        "ON `usage_days` (`ownerId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_app_durations` (
                        `ownerId` TEXT NOT NULL,
                        `dateIso` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `foregroundMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`ownerId`, `dateIso`, `packageName`),
                        FOREIGN KEY(`ownerId`, `dateIso`)
                            REFERENCES `usage_days`(`ownerId`, `dateIso`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_usage_app_durations_ownerId_dateIso` " +
                        "ON `usage_app_durations` (`ownerId`, `dateIso`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage_devices` (
                        `deviceId` TEXT NOT NULL,
                        `deviceName` TEXT NOT NULL,
                        `platform` TEXT NOT NULL,
                        `updatedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`deviceId`),
                        FOREIGN KEY(`deviceId`) REFERENCES `usage_histories`(`ownerId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `step_history` (
                        `id` INTEGER NOT NULL,
                        `trackingStartedOn` TEXT,
                        `baselineDateIso` TEXT,
                        `baselineCumulativeSteps` INTEGER,
                        `baselineCapturedAtEpochMillis` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `step_days` (
                        `historyId` INTEGER NOT NULL,
                        `dateIso` TEXT NOT NULL,
                        `zoneId` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `collectedAtEpochMillis` INTEGER NOT NULL,
                        `steps` INTEGER,
                        `distanceMeters` REAL,
                        `activeCaloriesKilocalories` REAL,
                        PRIMARY KEY(`historyId`, `dateIso`),
                        FOREIGN KEY(`historyId`) REFERENCES `step_history`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_step_days_historyId` " +
                        "ON `step_days` (`historyId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `legacy_statistics_migrations` (
                        `migrationId` TEXT NOT NULL,
                        `importedAtEpochMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`migrationId`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `game_statistics` (
                        `gameId` TEXT NOT NULL,
                        `metricKey` TEXT NOT NULL,
                        `value` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`gameId`, `metricKey`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_task_queue` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `progressJson` TEXT NOT NULL,
                        `resultJson` TEXT NOT NULL,
                        `errorSummary` TEXT NOT NULL,
                        `errorFailure` TEXT NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `startedAt` INTEGER,
                        `completedAt` INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_task_queue_state` " +
                        "ON `ai_task_queue` (`state`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_task_queue_createdAt` " +
                        "ON `ai_task_queue` (`createdAt`)",
                )
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `ai_conversations` ADD COLUMN `syncId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `ai_conversations` ADD COLUMN `deletedAt` INTEGER DEFAULT NULL")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_conversations_syncId` " +
                        "ON `ai_conversations` (`syncId`)",
                )
                db.execSQL("ALTER TABLE `ai_messages` ADD COLUMN `syncId` TEXT DEFAULT NULL")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_messages_syncId` " +
                        "ON `ai_messages` (`syncId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_attachments` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `messageId` INTEGER NOT NULL,
                        `uri` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `extractedText` TEXT,
                        `permissionOwned` INTEGER NOT NULL,
                        `syncId` TEXT DEFAULT NULL,
                        FOREIGN KEY(`messageId`) REFERENCES `ai_messages`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_attachments_messageId` " +
                        "ON `ai_attachments` (`messageId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ai_attachments_uri` " +
                        "ON `ai_attachments` (`uri`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_attachments_syncId` " +
                        "ON `ai_attachments` (`syncId`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_runs` (
                        `runId` TEXT NOT NULL,
                        `conversationId` INTEGER,
                        `conversationTitle` TEXT NOT NULL,
                        `userRequestSummary` TEXT NOT NULL,
                        `modelConfigId` TEXT NOT NULL,
                        `permissionMode` TEXT NOT NULL,
                        `enabledSourcesJson` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `modelCallCount` INTEGER NOT NULL DEFAULT 0,
                        `usageReportedCallCount` INTEGER NOT NULL DEFAULT 0,
                        `inputTokens` INTEGER DEFAULT NULL,
                        `outputTokens` INTEGER DEFAULT NULL,
                        `totalTokens` INTEGER DEFAULT NULL,
                        `cachedInputTokens` INTEGER DEFAULT NULL,
                        `cacheRateInputTokens` INTEGER DEFAULT NULL,
                        `reasoningTokens` INTEGER DEFAULT NULL,
                        `startedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        PRIMARY KEY(`runId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_runs_conversationId` " +
                        "ON `agent_runs` (`conversationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_runs_startedAt` " +
                        "ON `agent_runs` (`startedAt`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_tool_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` TEXT NOT NULL,
                        `sequence` INTEGER NOT NULL,
                        `toolCallId` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `classification` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `target` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `argumentsSummary` TEXT NOT NULL,
                        `resultSummary` TEXT NOT NULL,
                        `errorCode` TEXT,
                        `startedAt` INTEGER NOT NULL,
                        `completedAt` INTEGER,
                        FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_tool_events_runId` " +
                        "ON `agent_tool_events` (`runId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_tool_events_runId_sequence` " +
                        "ON `agent_tool_events` (`runId`, `sequence`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_mutations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `runId` TEXT NOT NULL,
                        `toolEventId` INTEGER NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `target` TEXT NOT NULL,
                        `operation` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `beforeContent` TEXT NOT NULL,
                        `afterContent` TEXT NOT NULL,
                        `undoPayload` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `undoneAt` INTEGER,
                        FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`runId`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`toolEventId`) REFERENCES `agent_tool_events`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_agent_mutations_runId` " +
                        "ON `agent_mutations` (`runId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_mutations_toolEventId` " +
                        "ON `agent_mutations` (`toolEventId`)",
                )
            }
        }
    }
}
