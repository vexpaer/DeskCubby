package com.deskcubby.app.data.statistics

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord

enum class StepHealthConnectAction {
    MANAGE_OR_PERMISSIONS,
    UPDATE_PROVIDER_IN_PLAY_STORE,
    OPEN_SYSTEM_UPDATE,
    UNSUPPORTED,
}

object StepHealthConnectAccess {
    val stepReadPermission: String =
        HealthPermission.getReadPermission(StepsRecord::class)
    val distanceReadPermission: String =
        HealthPermission.getReadPermission(DistanceRecord::class)
    val activeCaloriesReadPermission: String =
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    val healthReadPermissions: Set<String> = setOf(
        stepReadPermission,
        distanceReadPermission,
        activeCaloriesReadPermission,
    )

    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    fun action(context: Context): StepHealthConnectAction =
        stepHealthConnectActionForSdkStatus(
            sdkStatus = HealthConnectClient.getSdkStatus(context),
            sdkInt = Build.VERSION.SDK_INT,
        )

    fun open(context: Context): Result<Unit> {
        val intents = when (action(context)) {
            StepHealthConnectAction.MANAGE_OR_PERMISSIONS -> buildList {
                runCatching {
                    HealthConnectClient.getHealthConnectManageDataIntent(context)
                }.getOrNull()?.let(::add)
                add(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            }

            StepHealthConnectAction.UPDATE_PROVIDER_IN_PLAY_STORE -> listOf(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "market://details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE",
                    ),
                ).setPackage(PLAY_STORE_PACKAGE),
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://play.google.com/store/apps/details" +
                            "?id=$HEALTH_CONNECT_PROVIDER_PACKAGE",
                    ),
                ),
            )

            StepHealthConnectAction.OPEN_SYSTEM_UPDATE -> listOf(
                Intent(ACTION_SYSTEM_UPDATE_SETTINGS),
                Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
            )

            StepHealthConnectAction.UNSUPPORTED -> return Result.failure(
                UnsupportedOperationException("Health Connect is unavailable on this device."),
            )
        }
        var lastFailure: Throwable? = null
        intents.forEach { intent ->
            val prepared = Intent(intent).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(prepared)
                return Result.success(Unit)
            } catch (error: RuntimeException) {
                lastFailure = error
            }
        }
        return Result.failure(
            lastFailure ?: IllegalStateException("No Health Connect destination is available."),
        )
    }

    fun permissionsToRequest(context: Context): Set<String> {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return healthReadPermissions
        }
        val client = HealthConnectClient.getOrCreate(context)
        return buildSet {
            addAll(healthReadPermissions)
            if (
                client.features.getFeatureStatus(
                    HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
                ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            ) {
                add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            }
        }
    }

    private const val PLAY_STORE_PACKAGE = "com.android.vending"
    private const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    private const val ACTION_SYSTEM_UPDATE_SETTINGS = "android.settings.SYSTEM_UPDATE_SETTINGS"
}

internal fun stepHealthConnectActionForSdkStatus(
    sdkStatus: Int,
    sdkInt: Int,
): StepHealthConnectAction = when (sdkStatus) {
    HealthConnectClient.SDK_AVAILABLE ->
        StepHealthConnectAction.MANAGE_OR_PERMISSIONS

    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
        if (sdkInt <= Build.VERSION_CODES.TIRAMISU) {
            StepHealthConnectAction.UPDATE_PROVIDER_IN_PLAY_STORE
        } else {
            StepHealthConnectAction.OPEN_SYSTEM_UPDATE
        }

    else -> StepHealthConnectAction.UNSUPPORTED
}
