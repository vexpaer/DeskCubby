package com.deskcubby.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncContentDefaultsTest {
    @Test
    fun newConfigSelectsReadingProgress() {
        assertTrue(
            CloudSyncContent.READING_PROGRESS in
                CloudSyncConfig(id = "new", name = "New").selectedContents,
        )
    }

    @Test
    fun explicitExistingSelectionIsNotExpanded() {
        val existing = CloudSyncConfig(
            id = "existing",
            name = "Existing",
            selectedContents = setOf(CloudSyncContent.DIARIES),
        )

        assertFalse(CloudSyncContent.READING_PROGRESS in existing.selectedContents)
    }
}
