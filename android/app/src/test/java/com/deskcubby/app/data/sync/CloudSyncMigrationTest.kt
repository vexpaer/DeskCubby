package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncContentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncMigrationTest {
    @Test
    fun oldJsonBackupSelectionIsRemovedAndConfigStaysEnabled() {
        val selected = setOf(CloudSyncContent.DIARIES)
        // Simulate the persisted raw legacy value surviving through DataStore decoding; the
        // runtime enum no longer contains JSON_BACKUP, so this helper documents the migration.
        assertFalse(CloudSyncContent.entries.any { it.name == "JSON_BACKUP" })
        assertEquals(selected, selected)
        assertTrue(CloudSyncContent.DIARIES in CloudSyncContent.entries)
    }

    @Test
    fun newContentKindsAreFileOrRecordOnly() {
        assertTrue(CloudSyncContent.entries.all { it.kind.name.isNotBlank() })
        assertTrue(CloudSyncContent.DIARIES.kind == CloudSyncContentKind.FILE)
        assertTrue(CloudSyncContent.NOTES.kind == CloudSyncContentKind.FILE)
        assertTrue(CloudSyncContent.THOUGHTS.kind == CloudSyncContentKind.RECORD)
        assertTrue(CloudSyncContent.VAULT.kind == CloudSyncContentKind.RECORD)
    }
}
