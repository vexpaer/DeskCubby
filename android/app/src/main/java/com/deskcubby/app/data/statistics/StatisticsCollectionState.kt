package com.deskcubby.app.data.statistics

enum class StatisticsCollectionPhase {
    IDLE,
    REFRESHING,
    READY,
    DISABLED,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    ERROR,
}

data class StatisticsCollectionState(
    val phase: StatisticsCollectionPhase = StatisticsCollectionPhase.IDLE,
    val lastSuccessfulRefreshEpochMillis: Long? = null,
    val technicalDetail: String? = null,
)

enum class StatisticsRefreshOutcome {
    SUCCESS,
    PERMISSION_REQUIRED,
    UNAVAILABLE,
    ERROR,
}
