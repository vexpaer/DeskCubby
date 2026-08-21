package com.deskcubby.app.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseRealUpgradeTest {
    @Test
    fun installedV15DatabaseMigratesInPlaceAndPreservesRepresentativeData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(AppDatabase.MIGRATION_15_16)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = database.openHelper.writableDatabase
            sqlite.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(16, cursor.getInt(0))
            }
            assertSingleString(
                sqlite,
                "SELECT content FROM flash_thoughts WHERE id = 91001",
                "upgrade-thought-v15",
            )
            assertSingleString(
                sqlite,
                "SELECT title FROM ai_conversations WHERE id = 91002",
                "upgrade-conversation-v15",
            )
            assertSingleString(
                sqlite,
                "SELECT content FROM ai_messages WHERE id = 91003",
                "upgrade-message-v15",
            )
            assertSingleString(
                sqlite,
                "SELECT userRequestSummary FROM agent_runs WHERE runId = 'upgrade-run-v15'",
                "preserve me",
            )
            assertSingleString(
                sqlite,
                "SELECT title FROM diary_index WHERE uri = 'content://upgrade/v15'",
                "upgrade-diary-v15",
            )

            sqlite.query("PRAGMA index_list('agent_approval_requests')").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
                var found = false
                while (cursor.moveToNext()) {
                    if (
                        cursor.getString(nameIndex) == "index_agent_approval_requests_requestId" &&
                        cursor.getInt(uniqueIndex) == 1
                    ) {
                        found = true
                    }
                }
                assertTrue("requestId UNIQUE index must exist after the real upgrade", found)
            }
        } finally {
            database.close()
        }
    }

    private fun assertSingleString(
        sqlite: androidx.sqlite.db.SupportSQLiteDatabase,
        query: String,
        expected: String,
    ) {
        sqlite.query(query).use { cursor ->
            assertTrue("expected one preserved row for: $query", cursor.moveToFirst())
            assertEquals(expected, cursor.getString(0))
        }
    }

    private companion object {
        const val DATABASE_NAME = "deskcubby.db"
    }
}
