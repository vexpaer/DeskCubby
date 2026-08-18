package com.deskcubby.app.data.structuredrecords

import java.time.LocalTime

/**
 * Read-only snapshot of the automatic sleep/wake status shown on the structured records screen.
 * The values are tentative estimates for the current Journal Day; they only become durable when the
 * day settles and the final first/last-use value is written into Markdown.
 */
data class SystemFieldSnapshot(
    val autoRecording: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val wakeTime: LocalTime? = null,
    val sleepTime: LocalTime? = null,
) {
    val anyValue: Boolean get() = wakeTime != null || sleepTime != null
}
