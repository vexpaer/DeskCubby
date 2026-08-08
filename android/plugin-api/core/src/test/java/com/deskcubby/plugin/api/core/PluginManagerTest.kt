package com.deskcubby.plugin.api.core

import com.deskcubby.plugin.api.core.api.AIAPI
import com.deskcubby.plugin.api.core.api.DiaryAPI
import com.deskcubby.plugin.api.core.api.MediaAPI
import com.deskcubby.plugin.api.core.api.StorageAPI
import com.deskcubby.plugin.api.core.api.SyncAPI
import com.deskcubby.plugin.api.core.api.UIAPI
import com.deskcubby.plugin.api.core.api.VaultAPI
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginManagerTest {
    @Test
    fun testPluginCanBeRegisteredLoadedAndUnloaded() {
        val factory = RecordingContextFactory()
        val manager = PluginManager(factory)
        val plugin = TestPlugin()

        manager.register(plugin)
        runSuspend { manager.load(plugin.id) }

        assertEquals("test.plugin", plugin.loadedContextPluginId)
        assertEquals(1, plugin.loadCount)
        assertEquals(PluginState.LOADED, manager.snapshots().single().state)

        runSuspend { manager.unload(plugin.id) }

        assertEquals(1, plugin.unloadCount)
        assertEquals(listOf(plugin.id), factory.releasedPluginIds)
        assertEquals(PluginState.REGISTERED, manager.snapshots().single().state)
    }

    @Test
    fun duplicatePluginIdIsRejectedBeforeLifecycleStarts() {
        val manager = PluginManager(RecordingContextFactory())
        manager.register(TestPlugin())

        assertThrows(IllegalArgumentException::class.java) {
            manager.register(TestPlugin())
        }
        assertEquals(1, manager.snapshots().size)
    }

    @Test
    fun failedLoadCleansOwnerContextAndDoesNotBlockOtherPlugins() {
        val factory = RecordingContextFactory()
        val manager = PluginManager(factory)
        val failing = TestPlugin(failOnLoad = true)
        val healthy = object : Plugin {
            override val id = "test.healthy"
            override val name = "Healthy Test Plugin"
            override val version = "1"
            var loaded = false

            override suspend fun onLoad(context: PluginContext) {
                loaded = true
            }

            override suspend fun onUnload() = Unit
        }
        manager.register(failing)
        manager.register(healthy)

        val results = runSuspend { manager.loadAll() }

        assertFalse(results.first { it.descriptor.id == failing.id }.succeeded)
        assertTrue(results.first { it.descriptor.id == healthy.id }.succeeded)
        assertEquals(1, failing.unloadCount)
        assertEquals(listOf(failing.id), factory.releasedPluginIds)
        assertEquals(PluginState.FAILED, manager.snapshots().first().state)
        assertTrue(healthy.loaded)
    }
}

private class RecordingContextFactory : PluginContextFactory {
    val releasedPluginIds = mutableListOf<String>()

    override suspend fun create(descriptor: PluginDescriptor): PluginContext = PluginContext(
        pluginId = descriptor.id,
        host = PluginHost("com.deskcubby.test", "test"),
        diary = unsupportedApi(),
        vault = unsupportedApi(),
        media = unsupportedApi(),
        sync = unsupportedApi(),
        ai = unsupportedApi(),
        ui = unsupportedApi(),
        storage = unsupportedApi(),
    )

    override suspend fun release(pluginId: String) {
        releasedPluginIds += pluginId
    }
}

private inline fun <reified T : Any> unsupportedApi(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ ->
    error("Unexpected ${T::class.java.simpleName}.${method.name} call")
} as T

/** This test helper is intentionally limited to callbacks that complete synchronously. */
private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        },
    )
    return checkNotNull(outcome) { "Test coroutine unexpectedly suspended." }.getOrThrow()
}
