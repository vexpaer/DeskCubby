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

    private companion object {
        const val TEST_DATABASE = "ai-chat-migration-test"
    }
}
