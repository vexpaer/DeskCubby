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
    fun newConfigIncludesCategoriesForThoughtsAndPoems() {
        val selected = CloudSyncConfig(id = "new", name = "New").selectedContents

        assertTrue(CloudSyncContent.THOUGHTS in selected)
        assertTrue(CloudSyncContent.THOUGHT_CATEGORIES in selected)
        assertTrue(CloudSyncContent.POEMS in selected)
        assertTrue(CloudSyncContent.POETRY_CATEGORIES in selected)
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
