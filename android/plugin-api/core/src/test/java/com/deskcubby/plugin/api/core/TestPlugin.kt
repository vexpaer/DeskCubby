package com.deskcubby.plugin.api.core

/** Test-only plugin: it is never packaged into the DeskCubby application. */
class TestPlugin(
    private val failOnLoad: Boolean = false,
) : Plugin {
    override val id: String = "test.plugin"
    override val name: String = "Test Plugin"
    override val version: String = "1.0.0"

    var loadedContextPluginId: String? = null
        private set
    var loadCount: Int = 0
        private set
    var unloadCount: Int = 0
        private set

    override suspend fun onLoad(context: PluginContext) {
        loadCount += 1
        loadedContextPluginId = context.pluginId
        if (failOnLoad) error("Test load failure")
    }

    override suspend fun onUnload() {
        unloadCount += 1
    }
}
