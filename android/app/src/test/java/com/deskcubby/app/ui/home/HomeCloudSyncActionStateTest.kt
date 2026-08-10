package com.deskcubby.app.ui.home

import com.deskcubby.app.data.sync.AppCloudSyncStatus
import com.deskcubby.app.data.sync.CloudSyncRunMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCloudSyncActionStateTest {
    @Test
    fun acceptedActionIsSharedAsQueuedUntilWorkerStarts() {
        val queued = reduceHomeCloudSyncEnqueue(CloudSyncRunMode.FORCE_UPLOAD, accepted = true)

        assertEquals(CloudSyncRunMode.FORCE_UPLOAD, queued.queuedMode)
        assertFalse(queued.enqueueFailed)
        assertEquals(
            queued,
            reconcileHomeCloudSyncActionState(queued, AppCloudSyncStatus()),
        )
        assertEquals(
            queued,
            reconcileHomeCloudSyncActionState(
                queued,
                AppCloudSyncStatus(running = true),
            ),
        )
    }

    @Test
    fun rejectedActionShowsFailureAndCompletionClearsIt() {
        val failed = reduceHomeCloudSyncEnqueue(CloudSyncRunMode.NORMAL, accepted = false)

        assertNull(failed.queuedMode)
        assertTrue(failed.enqueueFailed)
        assertEquals(
            HomeCloudSyncActionState(),
            reconcileHomeCloudSyncActionState(
                failed,
                AppCloudSyncStatus(message = "云端同步完成 / Cloud sync completed"),
            ),
        )
    }

    @Test
    fun persistedDesktopQueueSeedsNewHomeStateAndClearRestoresReadyState() {
        val fromDesktop = applyPersistentHomeCloudQueue(
            HomeCloudSyncActionState(enqueueFailed = true),
            CloudSyncRunMode.FORCE_DOWNLOAD,
        )
        assertEquals(CloudSyncRunMode.FORCE_DOWNLOAD, fromDesktop.queuedMode)
        assertFalse(fromDesktop.enqueueFailed)

        val cleared = applyPersistentHomeCloudQueue(fromDesktop, null)
        assertEquals(HomeCloudSyncActionState(), cleared)
    }
}
