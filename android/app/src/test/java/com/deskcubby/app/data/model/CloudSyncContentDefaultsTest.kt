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
    fun newConfigStoresOnlyUserSelectableParentsForThoughtsAndPoems() {
        val selected = CloudSyncConfig(id = "new", name = "New").selectedContents

        assertTrue(CloudSyncContent.THOUGHTS in selected)
        assertFalse(CloudSyncContent.THOUGHT_CATEGORIES in selected)
        assertTrue(CloudSyncContent.POEMS in selected)
        assertFalse(CloudSyncContent.POETRY_CATEGORIES in selected)
        assertTrue(CloudSyncContent.THOUGHT_CATEGORIES in selected.withRequiredSyncDependencies())
        assertTrue(CloudSyncContent.POETRY_CATEGORIES in selected.withRequiredSyncDependencies())
    }

    @Test
    fun categoryDependenciesAreNotUserSelectable() {
        assertTrue(CloudSyncContent.THOUGHTS in userSelectableCloudSyncContents)
        assertTrue(CloudSyncContent.POEMS in userSelectableCloudSyncContents)
        assertFalse(CloudSyncContent.THOUGHT_CATEGORIES in userSelectableCloudSyncContents)
        assertFalse(CloudSyncContent.POETRY_CATEGORIES in userSelectableCloudSyncContents)
    }

    @Test
    fun legacyCategorySelectionsBecomeVisibleParentSelections() {
        val selected = setOf(
            CloudSyncContent.THOUGHT_CATEGORIES,
            CloudSyncContent.POETRY_CATEGORIES,
        ).toUserSelectableSyncContents()

        assertTrue(CloudSyncContent.THOUGHTS in selected)
        assertTrue(CloudSyncContent.POEMS in selected)
        assertFalse(CloudSyncContent.THOUGHT_CATEGORIES in selected)
        assertFalse(CloudSyncContent.POETRY_CATEGORIES in selected)
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
