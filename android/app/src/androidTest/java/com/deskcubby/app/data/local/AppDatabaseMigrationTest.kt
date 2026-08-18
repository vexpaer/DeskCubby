package com.deskcubby.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate5To6CreatesChatTablesAndCascadeDelete() {
        helper.createDatabase(TEST_DATABASE, 5).apply {
            execSQL(
                """
                INSERT INTO diary_index (
                    uri, name, title, dateIso, monthKey, lastModified,
                    size, wordCount, sha256, indexedAt
                ) VALUES (
                    'content://diary/1', '2026-07-24.md', '迁移前日记', '2026-07-24',
                    '2026-07', 10, 20, 30, 'abc', 40
                )
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            6,
            true,
            AppDatabase.MIGRATION_5_6,
        )
        database.query("SELECT title FROM diary_index WHERE uri = 'content://diary/1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("迁移前日记", cursor.getString(0))
        }
        database.execSQL(
            """
            INSERT INTO ai_conversations (id, title, modelConfigId, createdAt, updatedAt)
            VALUES (7, '测试会话', 'text-config', 10, 20)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO ai_messages (
                id, conversationId, role, content, reasoning, imageUri, imageMimeType,
                imagePermissionOwned, createdAt
            ) VALUES (
                9, 7, 'assistant', '回答', '思考', 'content://example/image', 'image/jpeg', 1, 30
            )
            """.trimIndent(),
        )
        database.query("SELECT COUNT(*) FROM ai_messages WHERE conversationId = 7").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }

        // MigrationTestHelper opens a raw SQLite connection with foreign keys disabled. Room
        // enables them for the real app, so turn them on before exercising the declared cascade.
        database.execSQL("PRAGMA foreign_keys = ON")
        database.execSQL("DELETE FROM ai_conversations WHERE id = 7")

        database.query("SELECT COUNT(*) FROM ai_messages WHERE conversationId = 7").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun migrate6To7AddsHighlightColumnAndNewTables() {
        helper.createDatabase(TEST_DATABASE, 6).apply {
            execSQL(
                """
                INSERT INTO flash_thoughts (id, content, createdAt, updatedAt, pinned, deletedAt, sortOrder, categoryId)
                VALUES (5, '迁移前的小巧思', 10, 20, 0, NULL, 0, NULL)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            7,
            true,
            AppDatabase.MIGRATION_6_7,
        )
        database.query(
            "SELECT content, highlighted FROM flash_thoughts WHERE id = 5",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("迁移前的小巧思", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        database.execSQL("UPDATE flash_thoughts SET highlighted = 1 WHERE id = 5")
        database.query("SELECT highlighted FROM flash_thoughts WHERE id = 5").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.execSQL(
            """
            INSERT INTO vault_items (id, cipherText, iv, createdAt, updatedAt)
            VALUES (1, 'Y2lwaGVy', 'aXY=', 10, 20)
            """.trimIndent(),
        )
        database.query("SELECT COUNT(*) FROM vault_items").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.execSQL(
            """
            INSERT INTO game_states (gameId, highScore, saveJson, updatedAt)
            VALUES ('2048', 2048, NULL, 30)
            """.trimIndent(),
        )
        database.query("SELECT highScore FROM game_states WHERE gameId = '2048'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(2048, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun migrate7To8AddsVaultOrderAndPreservesCurrentDisplayOrder() {
        val databaseName = "vault-order-migration-test"
        helper.createDatabase(databaseName, 7).apply {
            execSQL(
                """
                INSERT INTO vault_items (id, cipherText, iv, createdAt, updatedAt)
                VALUES
                    (1, 'b2xkZXI=', 'aXYx', 10, 20),
                    (2, 'bmV3ZXI=', 'aXYy', 30, 40)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            AppDatabase.MIGRATION_7_8,
        )
        database.query(
            "SELECT id, sortOrder FROM vault_items ORDER BY sortOrder ASC",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(2L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            cursor.moveToNext()
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
        }
        database.close()
    }

    @Test
    fun migrate8To9AddsPoetryCategoriesAndPreservesExistingPoems() {
        val databaseName = "poetry-category-migration-test"
        helper.createDatabase(databaseName, 8).apply {
            execSQL(
                """
                INSERT INTO saved_poems (id, content, source, createdAt, updatedAt)
                VALUES (7, '床前明月光', '李白《静夜思》', 10, 20)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            AppDatabase.MIGRATION_8_9,
        )
        database.query(
            "SELECT content, source, categoryId FROM saved_poems WHERE id = 7",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("床前明月光", cursor.getString(0))
            assertEquals("李白《静夜思》", cursor.getString(1))
            assertEquals(true, cursor.isNull(2))
        }
        database.execSQL(
            """
            INSERT INTO poetry_categories (id, name, colorArgb, sortOrder, createdAt, updatedAt)
            VALUES (3, '初中·七年级上册', -1, 0, 30, 30)
            """.trimIndent(),
        )
        database.execSQL("UPDATE saved_poems SET categoryId = 3 WHERE id = 7")
        database.execSQL("PRAGMA foreign_keys = ON")
        database.execSQL("DELETE FROM poetry_categories WHERE id = 3")
        database.query("SELECT categoryId FROM saved_poems WHERE id = 7").use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
        }
        database.close()
    }

    @Test
    fun migrate9To10AddsPoemOrderAndPreservesNewestFirstOrder() {
        val databaseName = "poetry-order-migration-test"
        helper.createDatabase(databaseName, 9).apply {
            execSQL(
                """
                INSERT INTO saved_poems (id, content, source, createdAt, updatedAt, categoryId)
                VALUES
                    (1, '旧诗', '作者一', 10, 10, NULL),
                    (2, '新诗', '作者二', 20, 20, NULL)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            AppDatabase.MIGRATION_9_10,
        )
        database.query(
            "SELECT id, sortOrder FROM saved_poems ORDER BY sortOrder ASC",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(2L, cursor.getLong(0))
            assertEquals(0L, cursor.getLong(1))
            cursor.moveToNext()
            assertEquals(1L, cursor.getLong(0))
            assertEquals(1L, cursor.getLong(1))
        }
        database.close()
    }

    @Test
    fun migrate10To11AddsNormalizedStatisticsTablesAndPreservesExistingData() {
        val databaseName = "statistics-room-migration-test"
        helper.createDatabase(databaseName, 10).apply {
            execSQL(
                """
                INSERT INTO game_states (gameId, highScore, saveJson, updatedAt)
                VALUES ('2048', 4096, NULL, 30)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            AppDatabase.MIGRATION_10_11,
        )
        database.query("SELECT highScore FROM game_states WHERE gameId = '2048'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(4096, cursor.getInt(0))
        }
        database.execSQL(
            """
            INSERT INTO usage_histories (ownerId, trackingStartedOn, backfillCompletedThrough)
            VALUES ('device-1', '2026-08-01', '2026-08-01')
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO usage_days (ownerId, dateIso, zoneId, state, collectedAtEpochMillis)
            VALUES ('device-1', '2026-08-01', 'Asia/Shanghai', 'FINAL', 100)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO usage_app_durations (ownerId, dateIso, packageName, foregroundMillis)
            VALUES ('device-1', '2026-08-01', 'example.app', 5000)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO usage_devices (deviceId, deviceName, platform, updatedAtEpochMillis)
            VALUES ('device-1', 'Phone', 'android', 100)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO step_history (
                id, trackingStartedOn, baselineDateIso,
                baselineCumulativeSteps, baselineCapturedAtEpochMillis
            ) VALUES (1, '2026-08-01', NULL, NULL, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO step_days (
                historyId, dateIso, zoneId, state, collectedAtEpochMillis,
                steps, distanceMeters, activeCaloriesKilocalories
            ) VALUES (1, '2026-08-01', 'Asia/Shanghai', 'FINAL', 100, 8000, 5000.5, 300.25)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO legacy_statistics_migrations (migrationId, importedAtEpochMillis)
            VALUES ('usage-statistics-json-v1', 100)
            """.trimIndent(),
        )

        database.query("SELECT foregroundMillis FROM usage_app_durations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(5000L, cursor.getLong(0))
        }
        database.query("SELECT steps FROM step_days").use { cursor ->
            cursor.moveToFirst()
            assertEquals(8000L, cursor.getLong(0))
        }
        database.execSQL("PRAGMA foreign_keys = ON")
        database.execSQL("DELETE FROM usage_histories WHERE ownerId = 'device-1'")
        database.query("SELECT COUNT(*) FROM usage_app_durations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM usage_devices").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun migrate11To12AddsAggregateGameStatisticsAndPreservesExistingData() {
        val databaseName = "game-statistics-room-migration-test"
        helper.createDatabase(databaseName, 11).apply {
            execSQL(
                """
                INSERT INTO game_states (gameId, highScore, saveJson, updatedAt)
                VALUES ('minesweeper', 8100, '{"w":9,"h":9}', 30)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO usage_histories (ownerId, trackingStartedOn, backfillCompletedThrough)
                VALUES ('device-1', '2026-08-01', '2026-08-01')
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            databaseName,
            12,
            true,
            AppDatabase.MIGRATION_11_12,
        )

        database.query("SELECT highScore FROM game_states WHERE gameId = 'minesweeper'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(8100, cursor.getInt(0))
        }
        database.query("SELECT COUNT(*) FROM usage_histories").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.execSQL(
            """
            INSERT INTO game_statistics (gameId, metricKey, value, updatedAt)
            VALUES ('minesweeper', 'minesSwept', 40, 31)
            """.trimIndent(),
        )
        database.query(
            "SELECT value, updatedAt FROM game_statistics " +
                "WHERE gameId = 'minesweeper' AND metricKey = 'minesSwept'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals(40L, cursor.getLong(0))
            assertEquals(31L, cursor.getLong(1))
        }
        database.close()
    }

    @Test
    fun migrate12To13PreservesLegacyChatAndAddsAgentReviewSchema() {
        val databaseName = "agent-room-migration-test"
        helper.createDatabase(databaseName, 12).apply {
            execSQL(
                """
                INSERT INTO ai_conversations (id, title, modelConfigId, createdAt, updatedAt)
                VALUES (7, '旧 AI 会话', 'legacy-model', 10, 20)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO ai_messages (
                    id, conversationId, role, content, reasoning, imageUri, imageMimeType,
                    imagePermissionOwned, createdAt
                ) VALUES (9, 7, 'user', '迁移前消息', '', NULL, NULL, 0, 21)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            databaseName,
            13,
            true,
            AppDatabase.MIGRATION_12_13,
        )

        database.query(
            "SELECT title, syncId, deletedAt FROM ai_conversations WHERE id = 7",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("旧 AI 会话", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
            assertEquals(true, cursor.isNull(2))
        }
        database.query("SELECT content, syncId FROM ai_messages WHERE id = 9").use { cursor ->
            cursor.moveToFirst()
            assertEquals("迁移前消息", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
        }
        database.execSQL(
            """
            INSERT INTO agent_runs (
                runId, conversationId, conversationTitle, userRequestSummary, modelConfigId,
                permissionMode, enabledSourcesJson, status, startedAt, completedAt
            ) VALUES ('run-1', 7, '旧 AI 会话', '总结', 'legacy-model',
                'REQUIRE_APPROVAL', '["diary"]', 'SUCCEEDED', 30, 40)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO agent_tool_events (
                id, runId, sequence, toolCallId, toolName, classification, status, target,
                summary, argumentsSummary, resultSummary, errorCode, startedAt, completedAt
            ) VALUES (2, 'run-1', 1, 'call-1', 'edit_content', 'MUTATION', 'SUCCEEDED',
                'thoughts/1', 'edited', 'entry=1', 'done', NULL, 31, 32)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO agent_mutations (
                id, runId, toolEventId, toolName, target, operation, summary, beforeContent,
                afterContent, undoPayload, status, createdAt, undoneAt
            ) VALUES (3, 'run-1', 2, 'edit_content', 'thoughts/1', 'UPDATE', 'edited',
                'before', 'after', 'undo', 'APPLIED', 32, NULL)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO ai_attachments (
                messageId, uri, mimeType, displayName, sizeBytes, kind, extractedText,
                permissionOwned, syncId
            ) VALUES (9, '', 'text/plain', 'legacy.txt', 6, 'DOCUMENT', 'legacy', 0, 'a-1')
            """.trimIndent(),
        )
        database.query("SELECT COUNT(*) FROM agent_mutations WHERE status = 'APPLIED'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.execSQL("PRAGMA foreign_keys = ON")
        database.execSQL("DELETE FROM ai_messages WHERE id = 9")
        database.query("SELECT COUNT(*) FROM ai_attachments").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.execSQL("DELETE FROM agent_runs WHERE runId = 'run-1'")
        database.query("SELECT COUNT(*) FROM agent_mutations").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        database.close()
    }

    @Test
    fun migrate13To14AddsAiTaskQueueAndPreservesChatData() {
        val databaseName = "ai-task-queue-migration-test"
        helper.createDatabase(databaseName, 13).apply {
            execSQL(
                """
                INSERT INTO ai_conversations (id, title, modelConfigId, createdAt, updatedAt)
                VALUES (7, '旧 AI 会话', 'legacy-model', 10, 20)
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            AppDatabase.MIGRATION_13_14,
        )

        database.query("SELECT COUNT(*) FROM ai_conversations WHERE id = 7").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
        database.execSQL(
            """
            INSERT INTO ai_task_queue (
                id, type, state, payloadJson, progressJson, resultJson, errorSummary,
                errorFailure, attemptCount, createdAt, startedAt, completedAt
            ) VALUES (1, 'CALORIE_DAY', 'QUEUED', '{}', '', '', '', '', 0, 100, NULL, NULL)
            """.trimIndent(),
        )
        database.query(
            "SELECT type, state, attemptCount FROM ai_task_queue WHERE id = 1",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("CALORIE_DAY", cursor.getString(0))
            assertEquals("QUEUED", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "ai-chat-migration-test"
    }
}
