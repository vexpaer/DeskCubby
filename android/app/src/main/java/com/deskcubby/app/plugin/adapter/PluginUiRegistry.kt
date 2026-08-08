package com.deskcubby.app.plugin.adapter

import com.deskcubby.plugin.api.core.api.PluginEntry
import com.deskcubby.plugin.api.core.api.PluginPage
import com.deskcubby.plugin.api.core.api.PluginRegistration
import com.deskcubby.plugin.api.core.api.PluginWidget
import com.deskcubby.plugin.api.core.api.UIAPI
import javax.inject.Inject
import javax.inject.Singleton

data class PluginUiContribution<T>(
    val pluginId: String,
    val contribution: T,
)

/**
 * In-memory contribution registry. Existing navigation deliberately does not consume it yet, so
 * introducing the API cannot add a page, widget, entry, or interaction to the current product.
 */
@Singleton
class PluginUiRegistry @Inject constructor() {
    private data class ContributionKey(val pluginId: String, val contributionId: String)

    private val monitor = Any()
    private val pages = linkedMapOf<ContributionKey, PluginPage>()
    private val widgets = linkedMapOf<ContributionKey, PluginWidget>()
    private val entries = linkedMapOf<ContributionKey, PluginEntry>()

    fun apiFor(pluginId: String): UIAPI = ScopedUiApi(pluginId, this)

    fun registeredPages(): List<PluginUiContribution<PluginPage>> = synchronized(monitor) {
        pages.map { (key, value) -> PluginUiContribution(key.pluginId, value) }
    }

    fun registeredWidgets(): List<PluginUiContribution<PluginWidget>> = synchronized(monitor) {
        widgets.map { (key, value) -> PluginUiContribution(key.pluginId, value) }
    }

    fun registeredEntries(): List<PluginUiContribution<PluginEntry>> = synchronized(monitor) {
        entries.map { (key, value) -> PluginUiContribution(key.pluginId, value) }
    }

    fun unregisterAll(pluginId: String) {
        synchronized(monitor) {
            pages.keys.removeAll { it.pluginId == pluginId }
            widgets.keys.removeAll { it.pluginId == pluginId }
            entries.keys.removeAll { it.pluginId == pluginId }
        }
    }

    private fun registerPage(pluginId: String, page: PluginPage): PluginRegistration {
        validateContribution(page.id, page.titleChinese, page.titleEnglish)
        return register(pages, ContributionKey(pluginId, page.id), page)
    }

    private fun registerWidget(pluginId: String, widget: PluginWidget): PluginRegistration {
        validateContribution(widget.id, widget.titleChinese, widget.titleEnglish)
        return register(widgets, ContributionKey(pluginId, widget.id), widget)
    }

    private fun registerEntry(pluginId: String, entry: PluginEntry): PluginRegistration {
        validateContribution(entry.id, entry.titleChinese, entry.titleEnglish)
        require(entry.targetPageId.isNotBlank()) { "Plugin entry targetPageId cannot be blank." }
        return register(entries, ContributionKey(pluginId, entry.id), entry)
    }

    private fun <T> register(
        target: MutableMap<ContributionKey, T>,
        key: ContributionKey,
        value: T,
    ): PluginRegistration {
        synchronized(monitor) {
            require(key !in target) {
                "Plugin '${key.pluginId}' already registered '${key.contributionId}'."
            }
            target[key] = value
        }
        var registered = true
        return PluginRegistration {
            synchronized(monitor) {
                if (registered) {
                    target.remove(key)
                    registered = false
                }
            }
        }
    }

    private fun validateContribution(id: String, chinese: String, english: String) {
        require(id.length in 1..128 && CONTRIBUTION_ID_PATTERN.matches(id)) {
            "Plugin contribution id must be a stable lowercase identifier."
        }
        require(chinese.isNotBlank() && chinese.length <= 128) {
            "Plugin contribution Chinese title must contain 1 to 128 characters."
        }
        require(english.isNotBlank() && english.length <= 128) {
            "Plugin contribution English title must contain 1 to 128 characters."
        }
    }

    private class ScopedUiApi(
        private val pluginId: String,
        private val registry: PluginUiRegistry,
    ) : UIAPI {
        override fun registerPage(page: PluginPage): PluginRegistration =
            registry.registerPage(pluginId, page)

        override fun registerWidget(widget: PluginWidget): PluginRegistration =
            registry.registerWidget(pluginId, widget)

        override fun registerEntry(entry: PluginEntry): PluginRegistration =
            registry.registerEntry(pluginId, entry)
    }

    private companion object {
        val CONTRIBUTION_ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")
    }
}
