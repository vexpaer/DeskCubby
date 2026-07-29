package com.deskcubby.app.data.statistics

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Test

class StepHealthConnectAccessTest {
    @Test
    fun `available sdk opens Health Connect management`() {
        assertEquals(
            StepHealthConnectAction.MANAGE_OR_PERMISSIONS,
            stepHealthConnectActionForSdkStatus(
                sdkStatus = HealthConnectClient.SDK_AVAILABLE,
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            ),
        )
    }

    @Test
    fun `provider update uses Play Store through Android 13`() {
        assertEquals(
            StepHealthConnectAction.UPDATE_PROVIDER_IN_PLAY_STORE,
            stepHealthConnectActionForSdkStatus(
                sdkStatus = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
            ),
        )
    }

    @Test
    fun `provider update uses system update on Android 14 and newer`() {
        assertEquals(
            StepHealthConnectAction.OPEN_SYSTEM_UPDATE,
            stepHealthConnectActionForSdkStatus(
                sdkStatus = HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            ),
        )
    }

    @Test
    fun `unavailable sdk offers no action`() {
        assertEquals(
            StepHealthConnectAction.UNSUPPORTED,
            stepHealthConnectActionForSdkStatus(
                sdkStatus = HealthConnectClient.SDK_UNAVAILABLE,
                sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
            ),
        )
    }
}
