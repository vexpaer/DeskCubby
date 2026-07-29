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

    private companion object {
        const val TEST_DATABASE = "ai-chat-migration-test"
    }
}
