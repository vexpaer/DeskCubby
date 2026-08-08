package com.deskcubby.app.plugin

import com.deskcubby.app.BuildConfig
import com.deskcubby.app.plugin.adapter.AiApiAdapter
import com.deskcubby.app.plugin.adapter.DiaryApiAdapter
import com.deskcubby.app.plugin.adapter.MediaApiAdapter
import com.deskcubby.app.plugin.adapter.PluginStorageApiFactory
import com.deskcubby.app.plugin.adapter.PluginUiRegistry
import com.deskcubby.app.plugin.adapter.SyncApiAdapter
import com.deskcubby.app.plugin.adapter.VaultApiAdapter
import com.deskcubby.plugin.api.core.PLUGIN_API_VERSION
import com.deskcubby.plugin.api.core.PluginContext
import com.deskcubby.plugin.api.core.PluginContextFactory
import com.deskcubby.plugin.api.core.PluginDescriptor
import com.deskcubby.plugin.api.core.PluginHost
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AppPluginContextFactory @Inject constructor(
    private val diaryApi: Provider<DiaryApiAdapter>,
    private val vaultApi: Provider<VaultApiAdapter>,
    private val mediaApi: Provider<MediaApiAdapter>,
    private val syncApi: Provider<SyncApiAdapter>,
    private val aiApi: Provider<AiApiAdapter>,
    private val uiRegistry: Provider<PluginUiRegistry>,
    private val storageFactory: Provider<PluginStorageApiFactory>,
) : PluginContextFactory {
    override suspend fun create(descriptor: PluginDescriptor): PluginContext = PluginContext(
        pluginId = descriptor.id,
        host = PluginHost(
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            apiVersion = PLUGIN_API_VERSION,
        ),
        diary = diaryApi.get(),
        vault = vaultApi.get(),
        media = mediaApi.get(),
        sync = syncApi.get(),
        ai = aiApi.get(),
        ui = uiRegistry.get().apiFor(descriptor.id),
        storage = storageFactory.get().create(descriptor.id),
    )

    override suspend fun release(pluginId: String) {
        uiRegistry.get().unregisterAll(pluginId)
    }
}
