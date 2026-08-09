package com.deskcubby.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncForceDownloadPolicyTest {
    @Test
    fun forceDownloadRejectsMultipleEnabledSourcesWithStableCode() {
        val error = assertThrows(CloudSyncConfigurationException::class.java) {
            requireSafeForceDownloadSourceCount(
                mode = CloudSyncRunMode.FORCE_DOWNLOAD,
                enabledSourceCount = 2,
            )
        }

        assertEquals("SYNC_FORCE_DOWNLOAD_SOURCE_COUNT", error.errorCode)
        assertTrue(error.message.orEmpty().contains("只能使用一个已启用"))
        assertTrue(error.message.orEmpty().contains("requires exactly one enabled"))
    }

    @Test
    fun forceDownloadAllowsExactlyOneEnabledSource() {
        requireSafeForceDownloadSourceCount(
            mode = CloudSyncRunMode.FORCE_DOWNLOAD,
            enabledSourceCount = 1,
        )
    }

    @Test
    fun normalAndForceUploadAllowMultipleEnabledTargets() {
        requireSafeForceDownloadSourceCount(CloudSyncRunMode.NORMAL, 3)
        requireSafeForceDownloadSourceCount(CloudSyncRunMode.FORCE_UPLOAD, 3)
    }
}
