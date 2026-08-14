package com.deskcubby.app.ui.desk.model

import android.net.Uri

/**
 * The kinds of content a Desk can surface. Ordering here drives editorial weight, not sorting.
 */
enum class DeskItemKind {
    DIARY,
    IDEA,
    PHOTO,
    EVENT,
}

/**
 * A single editorial "object" placed on the desk. Each object is a display-and-entry affordance:
 * the full interaction belongs to the existing feature pages, never here.
 */
data class DeskItem(
    val kind: DeskItemKind,
    val id: String,
    /** Human title / lead text used as the object's anchor. */
    val title: String,
    /** Optional excerpt shown on the desk (diary / idea body). */
    val excerpt: String,
    /** Optional caption / metadata line. */
    val meta: String,
    /** Stable, preferred rotation in degrees (reproducible per content id + date). */
    val rotationDeg: Float,
    /** Optional image source for photo objects. */
    val imageUri: Uri? = null,
    /** The diary URI to open when this is a diary object. */
    val diaryUri: String? = null,
    /** The idea id when this is an idea object. */
    val ideaId: Long? = null,
) {
    val rotationZ: Float get() = rotationDeg
}

/**
 * A compact, typographic trace of one moment from today. Like the object list, this is a
 * presentation of already-existing data, not a new data system.
 */
data class DeskTrace(
    val timeLabel: String,
    val label: String,
    /** Indicates prominence so longer/stronger traces can render with more visual weight. */
    val weight: Int,
)

/** Ambient mood derived from the time of day. Used for very subtle color/emphasis shifts. */
enum class DeskAmbient {
    MORNING,
    AFTERNOON,
    EVENING,
    LATE_NIGHT,
}

/**
 * The fully-resolved UI state for the Desk screen. Everything here is derived from existing
 * repositories in [com.deskcubby.app.ui.desk.DeskViewModel].
 */
data class DeskUiState(
    val loading: Boolean = true,
    val dateLabel: DeskDateLabel = DeskDateLabel.empty(),
    val diary: DeskItem? = null,
    val ideas: List<DeskItem> = emptyList(),
    val photos: List<DeskItem> = emptyList(),
    val traces: List<DeskTrace> = emptyList(),
    /** Total number of recorded moments today, possibly more than [traces] shows. */
    val totalTraceCount: Int = 0,
    val ambient: DeskAmbient = DeskAmbient.AFTERNOON,
    val isEmpty: Boolean = true,
) {
    val hasDiary: Boolean get() = diary != null
    val hasIdeas: Boolean get() = ideas.isNotEmpty()
    val hasPhotos: Boolean get() = photos.isNotEmpty()
    val hasTraces: Boolean get() = traces.isNotEmpty()
    val contentCount: Int get() = listOf(
        if (hasDiary) 1 else 0,
        ideas.size,
        photos.size,
        totalTraceCount,
    ).sum()
}

data class DeskDateLabel(
    val dayNumber: String,
    val month: String,
    val weekday: String,
) {
    companion object {
        fun empty() = DeskDateLabel("", "", "")
    }
}
