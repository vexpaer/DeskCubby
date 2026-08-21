package com.deskcubby.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabase15To16MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate15To16PreservesDataAndValidatesSchema() {
        helper.createDatabase(TEST_DATABASE, 15).apply {
            execSQL(
                """
                INSERT INTO diary_index (
                    uri, name, title, dateIso, monthKey, lastModified,
                    size, wordCount, sha256, indexedAt
                ) VALUES (
                    'content://diary/migration-15', '2026-08-20.md', '迁移前日记',
                    '2026-08-20', '2026-08', 10, 20, 30, 'before', 40
                )
                """.trimIndent(),
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            16,
            true,
            AppDatabase.MIGRATION_15_16,
        )

        database.query(
            "SELECT title FROM diary_index WHERE uri = 'content://diary/migration-15'",
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("迁移前日记", cursor.getString(0))
        }

        database.execSQL(
            """
            INSERT INTO agent_approval_requests (
                requestId, runId, conversationId, taskId, toolCallId, toolName,
                target, summary, argumentsSummary, beforeContent, afterContent,
                executionToken, status, createdAt, decidedAt
            ) VALUES (
                'req-unique', 'run-1', NULL, NULL, 'call-1', 'append_diary',
                '2026-08-20.md', 'append', '', 'before', 'after',
                'token-1', 'PENDING', 1, NULL
            )
            """.trimIndent(),
        )

        database.query("PRAGMA index_list('agent_approval_requests')").use { cursor ->
            var foundUniqueRequestId = false
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            while (cursor.moveToNext()) {
                if (
                    cursor.getString(nameIndex) == "index_agent_approval_requests_requestId" &&
                    cursor.getInt(uniqueIndex) == 1
                ) {
                    foundUniqueRequestId = true
                }
            }
            assertEquals(true, foundUniqueRequestId)
        }

        try {
            database.execSQL(
                """
                INSERT INTO agent_approval_requests (
                    requestId, runId, conversationId, taskId, toolCallId, toolName,
                    target, summary, argumentsSummary, beforeContent, afterContent,
                    executionToken, status, createdAt, decidedAt
                ) VALUES (
                    'req-unique', 'run-2', NULL, NULL, 'call-2', 'append_diary',
                    '2026-08-21.md', 'append', '', 'before', 'after',
                    'token-2', 'PENDING', 2, NULL
                )
                """.trimIndent(),
            )
            fail("duplicate requestId must be rejected by the UNIQUE index")
        } catch (_: Exception) {
            // Expected.
        }
        database.close()
    }

    private companion object {
        const val TEST_DATABASE = "migration-15-16-release-blocker"
    }
}
