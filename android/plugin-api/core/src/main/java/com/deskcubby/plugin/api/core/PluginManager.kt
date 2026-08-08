package com.deskcubby.plugin.api.core

import java.util.concurrent.CancellationException

/**
 * Registers plugins and serializes their lifecycle transitions.
 *
 * Callbacks deliberately run outside the internal monitor. A plugin therefore cannot deadlock
 * the manager by calling another component that inspects plugin state during load or unload.
 */
class PluginManager(
    private val contextFactory: PluginContextFactory,
) {
    private data class Record(
        val plugin: Plugin,
        val descriptor: PluginDescriptor,
        var state: PluginState = PluginState.REGISTERED,
    )

    private val monitor = Any()
    private val records = linkedMapOf<String, Record>()
    private val loadOrder = mutableListOf<String>()

    fun register(plugin: Plugin): PluginDescriptor {
        val descriptor = plugin.toValidatedDescriptor()
        synchronized(monitor) {
            require(descriptor.id !in records) {
                "A plugin with id '${descriptor.id}' is already registered."
            }
            records[descriptor.id] = Record(plugin = plugin, descriptor = descriptor)
        }
        return descriptor
    }

    fun unregister(pluginId: String): Boolean = synchronized(monitor) {
        val record = records[pluginId] ?: return@synchronized false
        check(record.state == PluginState.REGISTERED || record.state == PluginState.FAILED) {
            "Plugin '$pluginId' must be unloaded before it can be unregistered."
        }
        records.remove(pluginId)
        loadOrder.remove(pluginId)
        true
    }

    fun isRegistered(pluginId: String): Boolean = synchronized(monitor) {
        pluginId in records
    }

    fun snapshots(): List<PluginSnapshot> = synchronized(monitor) {
        records.values.map { PluginSnapshot(it.descriptor, it.state) }
    }

    suspend fun load(pluginId: String) {
        val record = synchronized(monitor) {
            val current = records[pluginId]
                ?: throw IllegalArgumentException("Plugin '$pluginId' is not registered.")
            check(current.state == PluginState.REGISTERED || current.state == PluginState.FAILED) {
                "Plugin '$pluginId' cannot load from state ${current.state}."
            }
            current.state = PluginState.LOADING
            current
        }

        val context = try {
            contextFactory.create(record.descriptor).also { created ->
                check(created.pluginId == pluginId) {
                    "Plugin context owner '${created.pluginId}' does not match '$pluginId'."
                }
            }
        } catch (error: Throwable) {
            runCatching { contextFactory.release(pluginId) }.exceptionOrNull()
                ?.let(error::addSuppressed)
            markFailed(record)
            if (error is CancellationException) throw error
            throw PluginLifecycleException(
                pluginId = pluginId,
                stage = PluginLifecycleStage.CREATE_CONTEXT,
                cause = error,
            )
        }

        try {
            record.plugin.onLoad(context)
        } catch (error: Throwable) {
            runCatching { record.plugin.onUnload() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { contextFactory.release(pluginId) }.exceptionOrNull()
                ?.let(error::addSuppressed)
            markFailed(record)
            if (error is CancellationException) throw error
            throw PluginLifecycleException(
                pluginId = pluginId,
                stage = PluginLifecycleStage.LOAD,
                cause = error,
            )
        }

        synchronized(monitor) {
            record.state = PluginState.LOADED
            loadOrder.remove(pluginId)
            loadOrder += pluginId
        }
    }

    suspend fun unload(pluginId: String) {
        val record = synchronized(monitor) {
            val current = records[pluginId]
                ?: throw IllegalArgumentException("Plugin '$pluginId' is not registered.")
            check(current.state == PluginState.LOADED) {
                "Plugin '$pluginId' cannot unload from state ${current.state}."
            }
            current.state = PluginState.UNLOADING
            current
        }

        var failure: PluginLifecycleException? = null
        try {
            record.plugin.onUnload()
        } catch (error: Throwable) {
            failure = PluginLifecycleException(
                pluginId = pluginId,
                stage = PluginLifecycleStage.UNLOAD,
                cause = error,
            )
        }

        try {
            contextFactory.release(pluginId)
        } catch (error: Throwable) {
            val releaseFailure = PluginLifecycleException(
                pluginId = pluginId,
                stage = PluginLifecycleStage.RELEASE_CONTEXT,
                cause = error,
            )
            if (failure == null) failure = releaseFailure else failure.addSuppressed(releaseFailure)
        }

        synchronized(monitor) {
            record.state = if (failure == null) PluginState.REGISTERED else PluginState.FAILED
            loadOrder.remove(pluginId)
        }
        if (failure?.cause is CancellationException) throw failure.cause as CancellationException
        failure?.let { throw it }
    }

    /** Loads every currently registered plugin without allowing one failure to block the rest. */
    suspend fun loadAll(): List<PluginLifecycleResult> {
        val candidates = snapshots().filter {
            it.state == PluginState.REGISTERED || it.state == PluginState.FAILED
        }
        return candidates.map { snapshot ->
            try {
                load(snapshot.descriptor.id)
                PluginLifecycleResult(snapshot.descriptor, succeeded = true)
            } catch (error: PluginLifecycleException) {
                PluginLifecycleResult(snapshot.descriptor, succeeded = false, error = error)
            }
        }
    }

    /** Unloads in reverse load order and still releases later plugins after an earlier failure. */
    suspend fun unloadAll(): List<PluginLifecycleResult> {
        val candidates = synchronized(monitor) { loadOrder.asReversed().toList() }
        return candidates.mapNotNull { pluginId ->
            val descriptor = synchronized(monitor) { records[pluginId]?.descriptor }
                ?: return@mapNotNull null
            try {
                unload(pluginId)
                PluginLifecycleResult(descriptor, succeeded = true)
            } catch (error: PluginLifecycleException) {
                PluginLifecycleResult(descriptor, succeeded = false, error = error)
            }
        }
    }

    private fun markFailed(record: Record) {
        synchronized(monitor) {
            record.state = PluginState.FAILED
            loadOrder.remove(record.descriptor.id)
        }
    }
}

private val PLUGIN_ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")

private fun Plugin.toValidatedDescriptor(): PluginDescriptor {
    val normalizedId = id.trim()
    val normalizedName = name.trim()
    val normalizedVersion = version.trim()
    require(normalizedId.length <= 128 && PLUGIN_ID_PATTERN.matches(normalizedId)) {
        "Plugin id must be a lowercase, stable identifier using '.', '_' or '-' separators."
    }
    require(normalizedName.isNotEmpty() && normalizedName.length <= 128) {
        "Plugin name must contain 1 to 128 characters."
    }
    require(normalizedVersion.isNotEmpty() && normalizedVersion.length <= 64) {
        "Plugin version must contain 1 to 64 characters."
    }
    return PluginDescriptor(normalizedId, normalizedName, normalizedVersion)
}
