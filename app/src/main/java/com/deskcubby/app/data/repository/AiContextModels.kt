package com.deskcubby.app.data.repository

/** Sources that can be frozen into an AI conversation as a local-only snapshot. */
enum class AiContextSource(val wireValue: String) {
    DIARY("diary"),
    THOUGHT("thought"),
    DATE_RECORD("date_record"),
    POEM("poem"),
}

/**
 * A picker row. [selectionKey] is an app-local lookup token and must never be put in an AI
 * request or persisted as part of a context snapshot.
 */
data class AiContextCandidate(
    val selectionKey: String,
    val source: AiContextSource,
    val title: String,
    val subtitle: String = "",
    val previewExcerpt: String = "",
    val previewIsExcerpt: Boolean = false,
    val estimatedBytes: Long? = null,
)

/**
 * The immutable, provider-independent representation sent to an AI service.
 *
 * Deliberately absent: Room ids, content URIs, hashes, file metadata, and credentials.
 */
data class AiContextItem(
    val source: AiContextSource,
    val title: String,
    val date: String = "",
    val attribution: String = "",
    val content: String = "",
)

data class AiContextSnapshot(
    val items: List<AiContextItem>,
)

data class AiContextItemPreview(
    val source: AiContextSource,
    val title: String,
    val date: String,
    val attribution: String,
    val contentExcerpt: String,
    val contentIsExcerpt: Boolean,
    val encodedBytes: Int,
    val exceedsItemLimit: Boolean,
)

enum class AiContextFailure {
    TOO_MANY_ITEMS,
    ITEM_TOO_LARGE,
    TOTAL_TOO_LARGE,
    SOURCE_UNAVAILABLE,
    INVALID_TEXT_ENCODING,
    INVALID_SNAPSHOT,
}

class AiContextException(
    val failure: AiContextFailure,
    val itemTitle: String? = null,
    val measuredBytes: Int? = null,
    val itemCount: Int? = null,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
