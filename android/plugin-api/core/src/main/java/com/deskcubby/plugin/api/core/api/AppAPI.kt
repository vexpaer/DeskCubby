package com.deskcubby.plugin.api.core.api

/** A deliberately allowlisted view of non-sensitive DeskCubby settings and runtime state. */
interface AppAPI {
    suspend fun settings(): Map<String, String>

    suspend fun state(): Map<String, String>

    suspend fun prepareSettingMutation(key: String, value: String): AppSettingMutationPlan

    suspend fun commitSettingMutation(planToken: String): AppSettingMutationResult

    suspend fun undoSettingMutation(undoToken: String): AppSettingMutationResult
}

data class AppSettingMutationPlan(
    val planToken: String,
    val key: String,
    val before: String,
    val after: String,
    val summary: String,
)

data class AppSettingMutationResult(
    val key: String,
    val before: String,
    val after: String,
    val summary: String,
    val undoToken: String? = null,
)
