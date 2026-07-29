package com.deskcubby.app.ui.settings

import android.content.Intent
import android.provider.Settings
import com.deskcubby.app.data.repository.UpdateDownloadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUpdateStateTest {
    @Test
    fun `only active download and preparation block another update action`() {
        assertTrue(UpdateDownloadState.Downloading(1L, 2L).isUpdateOperationInProgress())
        assertTrue(UpdateDownloadState.Preparing("0.3.2").isUpdateOperationInProgress())

        assertFalse(UpdateDownloadState.Idle.isUpdateOperationInProgress())
        assertFalse(
            UpdateDownloadState.AwaitingInstallPermission("0.3.2")
                .isUpdateOperationInProgress(),
        )
        assertFalse(UpdateDownloadState.ReadyToInstall("0.3.2").isUpdateOperationInProgress())
        assertFalse(
            UpdateDownloadState.Failed(UpdateDownloadFailure.NETWORK_ERROR)
                .isUpdateOperationInProgress(),
        )
    }

    @Test
    fun `unavailable permission settings is distinct from unavailable installer`() {
        assertEquals(
            UpdateDownloadFailure.INSTALL_PERMISSION_SETTINGS_UNAVAILABLE,
            updateActionUnavailableFailure(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        )
        assertEquals(
            UpdateDownloadFailure.INSTALLER_UNAVAILABLE,
            updateActionUnavailableFailure(Intent.ACTION_VIEW),
        )
    }
}
