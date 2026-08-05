package com.deskcubby.app.ui.diary

import com.deskcubby.app.data.repository.MealCalorieEstimationStage

enum class CalorieEstimationQueueStatus {
    QUEUED,
    IMAGE_RECOGNITION,
    TEXT_ESTIMATION,
    SAVING,
    COMPLETED,
    FAILED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED
}

data class CalorieModelTrace(
    val stage: MealCalorieEstimationStage,
    val modelName: String,
    val selectedPhotoIndex: Int,
    val photoLabel: String,
    val startedAtElapsedRealtime: Long,
    val finishedAtElapsedRealtime: Long? = null,
    val reasoning: String = "",
    val response: String = "",
) {
    val isRunning: Boolean get() = finishedAtElapsedRealtime == null

    fun elapsedMillis(nowElapsedRealtime: Long): Long =
        ((finishedAtElapsedRealtime ?: nowElapsedRealtime) - startedAtElapsedRealtime)
            .coerceAtLeast(0L)
}

/**
 * User-visible progress for one date. A date is committed only after every selected photo has
 * completed parallel recognition and the single date-scoped text calculation, so
 * [completedPhotoCount] is recognition progress rather than a claim that intermediate results
 * have already been saved.
 */
data class CalorieEstimationDayProgress(
    val id: Long,
    val dateIso: String,
    val status: CalorieEstimationQueueStatus = CalorieEstimationQueueStatus.QUEUED,
    val selectedPhotoCount: Int,
    val dayPhotoCount: Int,
    val completedPhotoCount: Int = 0,
    val activePhotoCount: Int = 0,
    val currentPhotoLabel: String? = null,
    val forceRecalculation: Boolean = false,
    val failedAtStatus: CalorieEstimationQueueStatus? = null,
    val error: String? = null,
    val modelTraces: List<CalorieModelTrace> = emptyList(),
) {
    val isTerminal: Boolean get() = status.isTerminal
}

data class CalorieEstimationQueueState(
    val items: List<CalorieEstimationDayProgress> = emptyList(),
) {
    val active: CalorieEstimationDayProgress?
        get() = items.firstOrNull {
            it.status != CalorieEstimationQueueStatus.QUEUED && !it.isTerminal
        }

    val queued: List<CalorieEstimationDayProgress>
        get() = items.filter { it.status == CalorieEstimationQueueStatus.QUEUED }

    val finishedDayCount: Int get() = items.count(CalorieEstimationDayProgress::isTerminal)
    val failedDayCount: Int
        get() = items.count { it.status == CalorieEstimationQueueStatus.FAILED }
    val isRunning: Boolean get() = active != null || queued.isNotEmpty()
}
