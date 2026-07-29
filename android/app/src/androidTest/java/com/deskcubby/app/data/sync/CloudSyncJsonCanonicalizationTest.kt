package com.deskcubby.app.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.backup.AppBackup
import com.deskcubby.app.data.backup.BackupJsonCodec
import com.deskcubby.app.data.model.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudSyncJsonCanonicalizationTest {
    @Test
    fun exportTimeDoesNotChangeCloudSyncPayload() {
        val first = BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 1_000L,
                settings = AppSettings(),
                thoughts = emptyList(),
                favorites = emptyList(),
            ),
        ).toByteArray(Charsets.UTF_8)
        val second = BackupJsonCodec.encode(
            AppBackup(
                exportedAt = 9_000L,
                settings = AppSettings(),
                thoughts = emptyList(),
                favorites = emptyList(),
            ),
        ).toByteArray(Charsets.UTF_8)

        val canonicalFirst = canonicalizeCloudSyncBackupJson(first)
        val canonicalSecond = canonicalizeCloudSyncBackupJson(second)

        assertArrayEquals(canonicalFirst, canonicalSecond)
        assertEquals(
            0L,
            BackupJsonCodec.decode(canonicalFirst.toString(Charsets.UTF_8)).exportedAt,
        )
    }
}
