package com.deskcubby.plugin.api.core

import com.deskcubby.plugin.api.core.api.AIAPI
import com.deskcubby.plugin.api.core.api.AppAPI
import com.deskcubby.plugin.api.core.api.DeskCubbyDataAPI
import com.deskcubby.plugin.api.core.api.DiaryAPI
import com.deskcubby.plugin.api.core.api.FileAPI
import com.deskcubby.plugin.api.core.api.MediaAPI
import com.deskcubby.plugin.api.core.api.StorageAPI
import com.deskcubby.plugin.api.core.api.SyncAPI
import com.deskcubby.plugin.api.core.api.UIAPI
import com.deskcubby.plugin.api.core.api.VaultAPI

const val PLUGIN_API_VERSION: Int = 2

data class PluginHost(
    val applicationId: String,
    val versionName: String,
    val apiVersion: Int = PLUGIN_API_VERSION,
)

/** The only supported entry point from a plugin into DeskCubby application capabilities. */
data class PluginContext(
    val pluginId: String,
    val host: PluginHost,
    val diary: DiaryAPI,
    val vault: VaultAPI,
    val media: MediaAPI,
    val sync: SyncAPI,
    val ai: AIAPI,
    val data: DeskCubbyDataAPI,
    val files: FileAPI,
    val app: AppAPI,
    val ui: UIAPI,
    val storage: StorageAPI,
)

/** Creates and releases owner-scoped resources such as UI registrations and plugin storage. */
interface PluginContextFactory {
    suspend fun create(descriptor: PluginDescriptor): PluginContext

    suspend fun release(pluginId: String) = Unit
}

open class PluginApiException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class PluginCapabilityUnavailableException(
    capability: String,
) : PluginApiException(
    code = "CAPABILITY_UNAVAILABLE",
    message = "DeskCubby capability '$capability' is not configured.",
)
