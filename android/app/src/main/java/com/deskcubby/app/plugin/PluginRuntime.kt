package com.deskcubby.app.plugin

import com.deskcubby.plugin.api.core.Plugin
import com.deskcubby.plugin.api.core.PluginLifecycleResult
import com.deskcubby.plugin.api.core.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application bridge for Hilt-contributed plugins. The production set is intentionally empty. */
@Singleton
class PluginRuntime @Inject constructor(
    private val manager: PluginManager,
    private val plugins: Set<@JvmSuppressWildcards Plugin>,
) {
    private val lifecycleMutex = Mutex()
    private var registered = false
    private var started = false

    fun hasPlugins(): Boolean = plugins.isNotEmpty()

    suspend fun start(): List<PluginLifecycleResult> = lifecycleMutex.withLock {
        if (!registered) {
            plugins.sortedBy(Plugin::id).forEach(manager::register)
            registered = true
        }
        if (started) return@withLock emptyList()
        started = true
        manager.loadAll()
    }

    suspend fun stop(): List<PluginLifecycleResult> = lifecycleMutex.withLock {
        if (!started) return@withLock emptyList()
        started = false
        manager.unloadAll()
    }
}
