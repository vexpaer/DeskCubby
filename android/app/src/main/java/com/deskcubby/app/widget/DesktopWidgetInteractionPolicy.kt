package com.deskcubby.app.widget

/**
 * Chooses the richest launcher-safe presentation that fits the actual widget bounds.
 *
 * AppWidget options are expressed in dp and are launcher controlled. In particular, saved design
 * dimensions cannot be trusted after the user resizes an instance. The thresholds deliberately
 * exclude narrow 1x1/1x2 cards: those keep one large navigation target instead of presenting tiny
 * controls that are difficult to hit or whose labels would be misleadingly truncated.
 */
internal object DesktopWidgetInteractionPolicy {
    fun mode(moduleId: String, widthDp: Int, heightDp: Int): DesktopWidgetInteractionMode {
        val width = widthDp.coerceAtLeast(0)
        val height = heightDp.coerceAtLeast(0)
        return when (moduleId) {
            "poem" -> if (
                (width >= 200 && height >= 100) ||
                (width >= 130 && height >= 180)
            ) {
                DesktopWidgetInteractionMode.POEM_ACTIONS
            } else {
                DesktopWidgetInteractionMode.NAVIGATION_ONLY
            }
            "quick_input" -> if (
                (width >= 180 && height >= 90) ||
                (width >= 130 && height >= 150)
            ) {
                DesktopWidgetInteractionMode.QUICK_INPUT
            } else {
                DesktopWidgetInteractionMode.NAVIGATION_ONLY
            }
            "meal_photos" -> when {
                width >= 270 && height >= 90 -> DesktopWidgetInteractionMode.MEAL_ACTIONS_WIDE
                width >= 180 && height >= 150 -> DesktopWidgetInteractionMode.MEAL_ACTIONS_3_BY_2
                width >= 130 && height >= 210 -> DesktopWidgetInteractionMode.MEAL_ACTIONS_2_BY_3
                else -> DesktopWidgetInteractionMode.NAVIGATION_ONLY
            }
            "cloud_sync_now" -> if (width >= 120 && height >= 90) {
                DesktopWidgetInteractionMode.CLOUD_SYNC_NOW
            } else {
                DesktopWidgetInteractionMode.NAVIGATION_ONLY
            }
            "cloud_sync_force" -> if (width >= 180 && height >= 90) {
                DesktopWidgetInteractionMode.CLOUD_SYNC_FORCE
            } else {
                DesktopWidgetInteractionMode.NAVIGATION_ONLY
            }
            else -> DesktopWidgetInteractionMode.NAVIGATION_ONLY
        }
    }

    fun shouldPromptForMealSource(
        pendingCameraPath: String?,
        externalSourceLaunched: Boolean,
    ): Boolean = pendingCameraPath.isNullOrBlank() && !externalSourceLaunched

    fun shouldShowExpandedRow(index: Int, rowCount: Int, maxRows: Int): Boolean =
        index >= 0 && index < rowCount.coerceAtMost(maxRows.coerceAtLeast(0))

    fun forceCloudAvailability(
        syncEnabled: Boolean,
        enabledSourceCount: Int,
        download: Boolean,
    ): ForceCloudAvailability = when {
        !syncEnabled -> ForceCloudAvailability.SYNC_DISABLED
        enabledSourceCount <= 0 -> ForceCloudAvailability.NO_ENABLED_SOURCE
        download && enabledSourceCount != 1 -> ForceCloudAvailability.DOWNLOAD_REQUIRES_ONE_SOURCE
        else -> ForceCloudAvailability.READY
    }

    fun cloudActionCanRun(
        syncEnabled: Boolean,
        enabledSourceCount: Int,
        running: Boolean,
        queued: Boolean,
    ): Boolean = syncEnabled && enabledSourceCount > 0 && !running && !queued

    fun expandedMode(moduleId: String, widthDp: Int, heightDp: Int): ExpandedWidgetMode = when {
        moduleId == "calendar" && widthDp >= 230 && heightDp >= 250 ->
            ExpandedWidgetMode.CALENDAR
        moduleId == "game_shortcuts" && widthDp >= 180 && heightDp >= 380 ->
            ExpandedWidgetMode.EIGHT_ROW_LIST
        moduleId in EXPANDED_LIST_MODULES && widthDp >= 180 && heightDp >= 240 ->
            ExpandedWidgetMode.FOUR_ROW_LIST
        moduleId in CLOUD_MODULES && widthDp >= 180 && heightDp >= 180 ->
            ExpandedWidgetMode.CLOUD_STATUS
        moduleId == "year_progress" && widthDp >= 180 && heightDp >= 100 ->
            ExpandedWidgetMode.YEAR_PROGRESS
        else -> ExpandedWidgetMode.NONE
    }

    private val EXPANDED_LIST_MODULES = setOf(
        "date_records",
        "recent_diary",
        "recent_thought",
        "daily_records",
        "game_shortcuts",
    )
    private val CLOUD_MODULES = setOf("cloud_sync_now", "cloud_sync_force")
}

internal enum class ExpandedWidgetMode {
    NONE,
    CALENDAR,
    FOUR_ROW_LIST,
    EIGHT_ROW_LIST,
    CLOUD_STATUS,
    YEAR_PROGRESS,
}

internal enum class ForceCloudAvailability {
    READY,
    SYNC_DISABLED,
    NO_ENABLED_SOURCE,
    DOWNLOAD_REQUIRES_ONE_SOURCE,
}

internal enum class DesktopWidgetInteractionMode {
    NAVIGATION_ONLY,
    POEM_ACTIONS,
    QUICK_INPUT,
    MEAL_ACTIONS_WIDE,
    MEAL_ACTIONS_3_BY_2,
    MEAL_ACTIONS_2_BY_3,
    CLOUD_SYNC_NOW,
    CLOUD_SYNC_FORCE,
}
