package com.deskcubby.plugin.api.core.api

/**
 * Provider-neutral access to DeskCubby's structured and document-backed data sources.
 *
 * The host remains responsible for enforcing the user's Agent source grants. Plugins and Agent
 * tools receive opaque entry ids and never turn content URIs into filesystem paths.
 */
interface DeskCubbyDataAPI {
    /** Null lists the full host catalog; a set must not inspect sources outside that set. */
    suspend fun sources(sourceIds: Set<String>? = null): List<DeskCubbyDataSource>

    suspend fun list(query: DeskCubbyDataQuery): DeskCubbyDataPage

    suspend fun read(sourceId: String, entryId: String): DeskCubbyDataEntry

    suspend fun prepareMutation(request: DeskCubbyDataMutationRequest): DeskCubbyMutationPlan

    suspend fun commitMutation(planToken: String): DeskCubbyMutationResult

    suspend fun undoMutation(undoToken: String): DeskCubbyMutationResult
}

data class DeskCubbyDataSource(
    val id: String,
    val labelChinese: String,
    val labelEnglish: String,
    val descriptionChinese: String,
    val descriptionEnglish: String,
    val entryCount: Int,
    val categories: List<String> = emptyList(),
    val earliestDateIso: String? = null,
    val latestDateIso: String? = null,
    val mutable: Boolean = false,
)

data class DeskCubbyDataQuery(
    val sourceId: String,
    val text: String? = null,
    val category: String? = null,
    val startDateIso: String? = null,
    val endDateIso: String? = null,
    val offset: Int = 0,
    val limit: Int = 20,
)

data class DeskCubbyDataPage(
    val entries: List<DeskCubbyDataEntry>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean,
)

data class DeskCubbyDataEntry(
    val sourceId: String,
    val entryId: String,
    val title: String,
    val subtitle: String = "",
    val content: String = "",
    val dateIso: String? = null,
    val category: String? = null,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val version: ContentVersion? = null,
    val metadata: Map<String, String> = emptyMap(),
)

enum class DeskCubbyMutationOperation {
    CREATE,
    UPDATE,
    DELETE,
}

data class DeskCubbyDataMutationRequest(
    val operation: DeskCubbyMutationOperation,
    val sourceId: String,
    val entryId: String? = null,
    val title: String? = null,
    val content: String? = null,
    val dateIso: String? = null,
    val category: String? = null,
)

data class DeskCubbyMutationPlan(
    val planToken: String,
    val operation: DeskCubbyMutationOperation,
    val target: String,
    val summary: String,
    val before: DeskCubbyDataEntry? = null,
    val after: DeskCubbyDataEntry? = null,
)

data class DeskCubbyMutationResult(
    val operation: DeskCubbyMutationOperation,
    val target: String,
    val summary: String,
    val before: DeskCubbyDataEntry? = null,
    val after: DeskCubbyDataEntry? = null,
    val undoToken: String? = null,
)
