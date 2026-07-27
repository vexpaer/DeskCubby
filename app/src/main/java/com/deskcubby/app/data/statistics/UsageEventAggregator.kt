package com.deskcubby.app.data.statistics

data class ForegroundTransition(
    val epochMillis: Long,
    val packageName: String,
    val type: ForegroundTransitionType,
)

enum class ForegroundTransitionType {
    ENTER_FOREGROUND,
    LEAVE_FOREGROUND,
}

/**
 * Aggregates package foreground sessions into the exact half-open interval
 * [windowStartMillis, windowEndMillis). Events before the window are used only
 * to carry foreground state over a civil-day boundary.
 */
fun aggregateForegroundUsage(
    windowStartMillis: Long,
    windowEndMillis: Long,
    events: List<ForegroundTransition>,
): List<UsageAppDuration> {
    require(windowStartMillis < windowEndMillis)
    val activeSince = mutableMapOf<String, Long>()
    val totals = mutableMapOf<String, Long>()

    events.asSequence()
        .filter { it.epochMillis < windowEndMillis }
        .sortedWith(compareBy(ForegroundTransition::epochMillis, ForegroundTransition::packageName))
        .forEach { event ->
            if (event.packageName.isBlank()) return@forEach
            when (event.type) {
                ForegroundTransitionType.ENTER_FOREGROUND -> {
                    activeSince.putIfAbsent(
                        event.packageName,
                        event.epochMillis.coerceAtLeast(windowStartMillis),
                    )
                }

                ForegroundTransitionType.LEAVE_FOREGROUND -> {
                    val startedAt = activeSince.remove(event.packageName) ?: return@forEach
                    val stoppedAt = event.epochMillis.coerceIn(windowStartMillis, windowEndMillis)
                    if (stoppedAt > startedAt) {
                        totals.addDuration(event.packageName, stoppedAt - startedAt)
                    }
                }
            }
        }

    activeSince.forEach { (packageName, startedAt) ->
        if (windowEndMillis > startedAt) {
            totals.addDuration(packageName, windowEndMillis - startedAt)
        }
    }
    val intervalLength = windowEndMillis - windowStartMillis
    return totals.entries.asSequence()
        .map { (packageName, duration) ->
            UsageAppDuration(
                packageName = packageName,
                foregroundMillis = duration.coerceAtMost(intervalLength),
            )
        }
        .filter { it.foregroundMillis > 0 }
        .sortedBy(UsageAppDuration::packageName)
        .toList()
}

private fun MutableMap<String, Long>.addDuration(packageName: String, duration: Long) {
    this[packageName] = Math.addExact(get(packageName) ?: 0L, duration)
}
