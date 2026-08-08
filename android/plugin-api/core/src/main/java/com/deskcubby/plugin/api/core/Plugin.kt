package com.deskcubby.plugin.api.core

/** A DeskCubby extension with an application-scoped lifecycle. */
interface Plugin {
    val id: String
    val name: String
    val version: String

    suspend fun onLoad(context: PluginContext)

    suspend fun onUnload()
}

data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
)

enum class PluginState {
    REGISTERED,
    LOADING,
    LOADED,
    UNLOADING,
    FAILED,
}

data class PluginSnapshot(
    val descriptor: PluginDescriptor,
    val state: PluginState,
)

data class PluginLifecycleResult(
    val descriptor: PluginDescriptor,
    val succeeded: Boolean,
    val error: PluginLifecycleException? = null,
)

enum class PluginLifecycleStage {
    CREATE_CONTEXT,
    LOAD,
    UNLOAD,
    RELEASE_CONTEXT,
}

class PluginLifecycleException(
    val pluginId: String,
    val stage: PluginLifecycleStage,
    cause: Throwable,
) : Exception("Plugin '$pluginId' failed during ${stage.name.lowercase()}.", cause)
