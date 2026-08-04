package com.deskcubby.app.ui.diary

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

/**
 * User-visible progress for one date. A date is committed only after every selected photo has
 * completed both model stages, so [completedPhotoCount] is calculation progress rather than a
 * claim that those intermediate results have already been saved.
 */
data class CalorieEstimationDayProgress(
    val id: Long,
    val dateIso: String,
    val status: CalorieEstimationQueueStatus = CalorieEstimationQueueStatus.QUEUED,
    val selectedPhotoCount: Int,
    val dayPhotoCount: Int,
    val completedPhotoCount: Int = 0,
    val currentSelectedPhotoIndex: Int? = null,
    val currentDayPhotoIndex: Int? = null,
    val currentPhotoLabel: String? = null,
    val forceRecalculation: Boolean = false,
    val failedAtStatus: CalorieEstimationQueueStatus? = null,
    val error: String? = null,
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
