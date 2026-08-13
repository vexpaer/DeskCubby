package com.deskcubby.app.agent

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AgentToolRegistry @Inject constructor(
    contributors: Set<@JvmSuppressWildcards AgentToolContributor>,
) {
    private val byName: Map<String, AgentTool> = contributors
        .flatMap(AgentToolContributor::tools)
        .also { tools ->
            require(tools.map { it.definition.name }.distinct().size == tools.size) {
                "Agent tool names must be unique"
            }
        }
        .associateBy { it.definition.name }

    fun definitions(): List<AgentToolDefinition> = byName.values
        .map(AgentTool::definition)
        .sortedBy(AgentToolDefinition::name)

    fun find(name: String): AgentTool? = byName[name]
}
