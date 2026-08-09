package com.deskcubby.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSyncWorkerModeTest {
    @Test
    fun missingOrInvalidInputFallsBackToNormalSync() {
        assertEquals(CloudSyncRunMode.NORMAL, parseCloudSyncRunMode(null))
        assertEquals(CloudSyncRunMode.NORMAL, parseCloudSyncRunMode("unknown"))
    }

    @Test
    fun everyPersistedModeRoundTripsByStableEnumName() {
        CloudSyncRunMode.entries.forEach { mode ->
            assertEquals(mode, parseCloudSyncRunMode(mode.name))
        }
    }

    @Test
    fun manualFailureCompletesItsQueueNodeSoTheNextActionCanRun() {
        assertEquals(
            CloudSyncWorkResolution.COMPLETE,
            cloudSyncFailureResolution(manual = true, configurationRequired = false),
        )
        assertEquals(
            CloudSyncWorkResolution.COMPLETE,
            cloudSyncFailureResolution(manual = true, configurationRequired = true),
        )
        assertEquals(
            CloudSyncWorkResolution.RETRY,
            cloudSyncFailureResolution(manual = false, configurationRequired = false),
        )
        assertEquals(
            CloudSyncWorkResolution.FAIL,
            cloudSyncFailureResolution(manual = false, configurationRequired = true),
        )
    }
}
