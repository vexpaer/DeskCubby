package com.deskcubby.app.ui.ai

internal data class AiContextGroupToggleResult(
    val selection: Set<String>,
    val limitExceeded: Boolean = false,
    val resultingItemCount: Int = selection.size,
)

/**
 * Selects or clears a complete context group atomically.
 *
 * Duplicate keys do not consume extra capacity. If adding every missing item would exceed the
 * limit, the original selection is returned unchanged.
 */
internal fun toggleAiContextGroup(
    currentSelection: Set<String>,
    groupKeys: Collection<String>,
    maxItems: Int,
): AiContextGroupToggleResult {
    val normalizedGroup = groupKeys.filter(String::isNotBlank).toCollection(linkedSetOf())
    if (normalizedGroup.isEmpty()) return AiContextGroupToggleResult(currentSelection)

    if (normalizedGroup.all { it in currentSelection }) {
        return AiContextGroupToggleResult(
            selection = currentSelection.filterTo(linkedSetOf()) { it !in normalizedGroup },
        )
    }

    val merged = currentSelection.toCollection(linkedSetOf()).apply {
        addAll(normalizedGroup)
    }
    if (merged.size > maxItems) {
        return AiContextGroupToggleResult(
            selection = currentSelection,
            limitExceeded = true,
            resultingItemCount = merged.size,
        )
    }
    return AiContextGroupToggleResult(merged)
}
