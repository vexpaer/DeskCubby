package com.deskcubby.plugin.api.core.api

import androidx.compose.runtime.Composable

interface UIAPI {
    fun registerPage(page: PluginPage): PluginRegistration

    fun registerWidget(widget: PluginWidget): PluginRegistration

    fun registerEntry(entry: PluginEntry): PluginRegistration
}

interface PluginNavigator {
    fun openPage(pageId: String)

    fun goBack()
}

data class PluginPage(
    val id: String,
    val titleChinese: String,
    val titleEnglish: String,
    val iconKey: String,
    val content: @Composable (PluginNavigator) -> Unit,
)

data class PluginWidget(
    val id: String,
    val titleChinese: String,
    val titleEnglish: String,
    val content: @Composable () -> Unit,
)

enum class PluginEntryLocation {
    HOME,
    NAVIGATION,
    SETTINGS,
}

data class PluginEntry(
    val id: String,
    val titleChinese: String,
    val titleEnglish: String,
    val iconKey: String,
    val targetPageId: String,
    val location: PluginEntryLocation,
)

fun interface PluginRegistration : AutoCloseable {
    fun unregister()

    override fun close() = unregister()
}
