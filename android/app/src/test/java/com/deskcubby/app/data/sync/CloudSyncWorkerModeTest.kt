package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.widget.CloudSyncWidgetState
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

    @Test
    fun schedulerSignatureChangesWhenEnabledSourcesChangeFromTwoToOne() {
        val first = CloudSyncConfig(id = "first", name = "First")
        val second = CloudSyncConfig(id = "second", name = "Second")
        val two = cloudSyncSchedulerSignature(
            AppSettings(cloudSyncEnabled = true, cloudSyncConfigs = listOf(first, second)),
        )
        val one = cloudSyncSchedulerSignature(
            AppSettings(
                cloudSyncEnabled = true,
                cloudSyncConfigs = listOf(first, second.copy(enabled = false)),
            ),
        )

        assertEquals(listOf("first", "second"), two.enabledConfigIds)
        assertEquals(listOf("first"), one.enabledConfigIds)
        assertEquals(false, two == one)
        assertEquals(true, one.enabled)
    }

    @Test
    fun staleManualQueueMarkerCannotLockActionsForever() {
        val now = 2_000_000_000L
        assertEquals(true, isCloudQueueMarkerFresh(now - 1_000L, now))
        assertEquals(
            false,
            isCloudQueueMarkerFresh(now - CloudSyncManualQueueState.STALE_AFTER_MS - 1L, now),
        )
        assertEquals(false, isCloudQueueMarkerFresh(0L, now))
        assertEquals(false, isCloudQueueMarkerFresh(now + 1L, now))
    }

    @Test
    fun cancellationResetsIndependentWidgetsButTerminalResultsRemainVisible() {
        assertEquals(CloudSyncWidgetState.IDLE, finalCloudWidgetState(null))
        assertEquals(
            CloudSyncWidgetState.SUCCEEDED,
            finalCloudWidgetState(CloudSyncWidgetState.SUCCEEDED),
        )
        assertEquals(
            CloudSyncWidgetState.FAILED,
            finalCloudWidgetState(CloudSyncWidgetState.FAILED),
        )
    }

    @Test
    fun queueReconciliationClearsCrashMarkersButPreservesLiveOrJustCommittedWork() {
        val now = 100_000L
        assertEquals(
            true,
            shouldClearManualQueueMarker(
                hasLiveWork = false,
                markedAt = now - CloudSyncManualQueueState.ENQUEUE_GRACE_MS - 1L,
                now = now,
            ),
        )
        assertEquals(
            true,
            shouldRefreshIndependentCloudWidgetsAfterReconcile(
                hasLiveWork = false,
                markedAt = now - CloudSyncManualQueueState.ENQUEUE_GRACE_MS - 1L,
                now = now,
            ),
        )
        assertEquals(
            false,
            shouldClearManualQueueMarker(
                hasLiveWork = true,
                markedAt = now - CloudSyncManualQueueState.ENQUEUE_GRACE_MS - 1L,
                now = now,
            ),
        )
        assertEquals(
            CloudSyncManualQueueState.ENQUEUE_GRACE_MS,
            manualQueueMarkerRecheckDelayMs(
                hasLiveWork = false,
                markedAt = now - 1L,
                now = now,
            ),
        )
        assertEquals(
            null,
            manualQueueMarkerRecheckDelayMs(
                hasLiveWork = true,
                markedAt = now - 1L,
                now = now,
            ),
        )
        assertEquals(
            false,
            shouldClearManualQueueMarker(
                hasLiveWork = false,
                markedAt = now - 1L,
                now = now,
            ),
        )
    }
}
