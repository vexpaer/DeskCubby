package com.deskcubby.app.data.structuredrecords

import java.time.LocalTime

data class StructuredDraftField(
    val occurrenceIndex: Int,
    val fieldId: String,
    val start: Int,
    val endExclusive: Int,
    val value: String?,
    val templateBinding: Boolean = false,
)

data class StructuredRecordDraft(
    val text: String,
    val fields: List<StructuredDraftField>,
)

fun createStructuredRecordDraft(
    template: StructuredRecordTemplate,
    fieldsById: Map<String, StructuredField>,
    now: LocalTime,
): StructuredRecordDraft = buildDraft(template, fieldsById) { field ->
    if (field.type == StructuredFieldType.TIME) JournalDayEngine.formatTime(now) else field.name
}

fun createStructuredTemplateDraft(
    template: StructuredRecordTemplate,
    fieldsById: Map<String, StructuredField>,
): StructuredRecordDraft = buildDraft(
    template = template,
    fieldsById = fieldsById,
    templateBindings = true,
) { it.name }

private fun buildDraft(
    template: StructuredRecordTemplate,
    fieldsById: Map<String, StructuredField>,
    templateBindings: Boolean = false,
    display: (StructuredField) -> String,
): StructuredRecordDraft {
    val text = StringBuilder()
    val spans = mutableListOf<StructuredDraftField>()
    var occurrence = 0
    template.segments.forEach { segment ->
        when (segment) {
            is StructuredRecordSegment.Text -> text.append(segment.value)
            is StructuredRecordSegment.Field -> {
                val field = fieldsById[segment.fieldId] ?: return@forEach
                val rendered = display(field)
                val start = text.length
                text.append(rendered)
                spans += StructuredDraftField(
                    occurrenceIndex = occurrence++,
                    fieldId = field.id,
                    start = start,
                    endExclusive = text.length,
                    value = if (field.type == StructuredFieldType.TIME && rendered != field.name) rendered else null,
                    templateBinding = templateBindings,
                )
            }
        }
    }
    return StructuredRecordDraft(text.toString(), spans)
}

/**
 * Controls whether the UI may attempt submission. Placeholder labels keep typed fields visible, so
 * an unfinished draft must remain tappable and let the ViewModel return a precise validation error.
 * [structuredDraftValues] remains the strict completeness gate before any durable write.
 */
fun isStructuredDraftReady(draft: StructuredRecordDraft): Boolean = draft.text.isNotBlank()

/**
 * Applies one text edit while preserving field bindings. Edits wholly inside one field replace that
 * field value; edits outside fields shift later ranges. A change crossing a field boundary or
 * touching multiple fields is rejected rather than silently unbinding persisted markers.
 *
 * Template drafts are the one exception: deleting exactly one whole inline field removes that
 * binding from the template. Record-entry drafts keep rejecting the same edit so a required field
 * cannot become an invisible zero-length binding.
 */
fun applyStructuredDraftEdit(
    draft: StructuredRecordDraft,
    candidate: String,
): StructuredRecordDraft? {
    if (candidate == draft.text) return draft
    val old = draft.text
    var start = 0
    val prefixLimit = minOf(old.length, candidate.length)
    while (start < prefixLimit && old[start] == candidate[start]) start++

    var oldSuffix = old.length
    var newSuffix = candidate.length
    while (oldSuffix > start && newSuffix > start && old[oldSuffix - 1] == candidate[newSuffix - 1]) {
        oldSuffix--
        newSuffix--
    }

    val touched = draft.fields.filter { field ->
        if (oldSuffix == start) {
            start > field.start && start < field.endExclusive
        } else {
            start < field.endExclusive && oldSuffix > field.start
        }
    }
    if (touched.size > 1) return null
    val delta = (newSuffix - start) - (oldSuffix - start)

    if (touched.isEmpty()) {
        val shifted = draft.fields.map { field ->
            when {
                field.endExclusive <= start -> field
                field.start >= oldSuffix -> field.copy(
                    start = field.start + delta,
                    endExclusive = field.endExclusive + delta,
                )
                else -> return null
            }
        }
        return StructuredRecordDraft(candidate, shifted)
    }

    val target = touched.single()
    if (start < target.start || oldSuffix > target.endExclusive) return null
    val newTargetEnd = target.endExclusive + delta
    if (
        target.templateBinding &&
        start == target.start &&
        oldSuffix == target.endExclusive &&
        newSuffix == start
    ) {
        val remaining = draft.fields
            .filterNot { it.occurrenceIndex == target.occurrenceIndex }
            .map { field ->
                if (field.start >= oldSuffix) {
                    field.copy(
                        start = field.start + delta,
                        endExclusive = field.endExclusive + delta,
                    )
                } else {
                    field
                }
            }
        return StructuredRecordDraft(candidate, remaining)
    }
    // A required inline field in record entry must always retain a visible/editable range.
    if (newTargetEnd <= target.start || newTargetEnd > candidate.length) return null
    val newValue = candidate.substring(target.start, newTargetEnd).takeIf(String::isNotBlank)
        ?: return null
    val updated = draft.fields.map { field ->
        when {
            field.occurrenceIndex == target.occurrenceIndex -> field.copy(
                endExclusive = newTargetEnd,
                value = newValue,
            )
            field.start >= oldSuffix -> field.copy(
                start = field.start + delta,
                endExclusive = field.endExclusive + delta,
            )
            else -> field
        }
    }
    return StructuredRecordDraft(candidate, updated)
}

fun replaceStructuredDraftField(
    draft: StructuredRecordDraft,
    occurrenceIndex: Int,
    replacement: String,
): StructuredRecordDraft {
    val field = draft.fields.firstOrNull { it.occurrenceIndex == occurrenceIndex } ?: return draft
    val safe = replacement.trim()
    // Assisted replacements (chips/pickers/quick actions) must obey the same invariant as direct
    // editing: a field binding may never collapse into an invisible zero-length range.
    if (safe.isBlank()) return draft
    val text = draft.text.replaceRange(field.start, field.endExclusive, safe)
    val delta = safe.length - (field.endExclusive - field.start)
    val updated = draft.fields.map { current ->
        when {
            current.occurrenceIndex == occurrenceIndex -> current.copy(
                endExclusive = current.start + safe.length,
                value = safe,
            )
            current.start >= field.endExclusive -> current.copy(
                start = current.start + delta,
                endExclusive = current.endExclusive + delta,
            )
            else -> current
        }
    }
    return StructuredRecordDraft(text, updated)
}

fun insertStructuredTemplateField(
    draft: StructuredRecordDraft,
    field: StructuredField,
    offset: Int,
): StructuredRecordDraft {
    val safeOffset = offset.coerceIn(0, draft.text.length)
    if (draft.fields.any { safeOffset > it.start && safeOffset < it.endExclusive }) return draft
    val label = field.name
    val text = draft.text.substring(0, safeOffset) + label + draft.text.substring(safeOffset)
    val occurrence = (draft.fields.maxOfOrNull { it.occurrenceIndex } ?: -1) + 1
    val shifted = draft.fields.map { current ->
        if (current.start >= safeOffset) current.copy(
            start = current.start + label.length,
            endExclusive = current.endExclusive + label.length,
        ) else current
    } + StructuredDraftField(
        occurrenceIndex = occurrence,
        fieldId = field.id,
        start = safeOffset,
        endExclusive = safeOffset + label.length,
        value = null,
        templateBinding = true,
    )
    return StructuredRecordDraft(text, shifted.sortedBy { it.start })
}

fun structuredDraftToSegments(draft: StructuredRecordDraft): List<StructuredRecordSegment> {
    val segments = mutableListOf<StructuredRecordSegment>()
    var cursor = 0
    draft.fields.sortedBy { it.start }.forEach { field ->
        if (field.start > cursor) {
            segments += StructuredRecordSegment.Text(draft.text.substring(cursor, field.start))
        }
        segments += StructuredRecordSegment.Field(field.fieldId)
        cursor = field.endExclusive
    }
    if (cursor < draft.text.length) segments += StructuredRecordSegment.Text(draft.text.substring(cursor))
    return segments.filterNot { it is StructuredRecordSegment.Text && it.value.isEmpty() }
}

fun structuredDraftValues(draft: StructuredRecordDraft): List<String>? {
    val ordered = draft.fields.sortedBy { it.start }
    if (ordered.any { it.value.isNullOrBlank() }) return null
    return ordered.map { requireNotNull(it.value) }
}
