package com.deskcubby.app.agent

import com.deskcubby.plugin.api.core.api.DeskCubbyDataAPI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultAgentContextProvider @Inject constructor(
    private val dataApi: DeskCubbyDataAPI,
) : AgentContextProvider {
    override suspend fun metadataPrompt(allowedSources: Set<String>, english: Boolean): String {
        if (allowedSources.isEmpty()) return "No DeskCubby data source is authorized."
        return dataApi.sources(allowedSources)
            .joinToString("\n") { source ->
                buildString {
                    append("- id=").append(source.id)
                    append("; type=").append(if (english) source.labelEnglish else source.labelChinese)
                    append("; entries=").append(source.entryCount)
                    if (source.categories.isNotEmpty()) {
                        append("; categories=")
                        append(source.categories.take(40).joinToString(" | "))
                    }
                    if (source.earliestDateIso != null || source.latestDateIso != null) {
                        append("; dateRange=")
                        append(source.earliestDateIso ?: "?")
                        append("..").append(source.latestDateIso ?: "?")
                    }
                    append("; mutable=").append(source.mutable)
                    append("; description=")
                    append(if (english) source.descriptionEnglish else source.descriptionChinese)
                }
            }
            .take(MAX_METADATA_CHARS)
    }

    private companion object {
        const val MAX_METADATA_CHARS = 24 * 1024
    }
}
