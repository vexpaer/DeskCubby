package com.deskcubby.app.data.structuredrecords

import java.time.LocalTime

data class StructuredDraftField(
    val occurrenceIndex: Int,
    val fieldId: String,
    val start: Int,
    val endExclusive: Int,
    val value: String?,
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
): StructuredRecordDraft = buildDraft(template, fieldsById) { it.name }

private fun buildDraft(
    template: StructuredRecordTemplate,
    fieldsById: Map<String, StructuredField>,
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
                )
            }
        }
    }
    return StructuredRecordDraft(text.toString(), spans)
}

/**
 * A pure-text template has no required typed fields, so it is immediately recordable. Templates
 * with fields become recordable only after every bound occurrence has a non-blank value.
 */
fun isStructuredDraftReady(draft: StructuredRecordDraft): Boolean =
    draft.fields.all { !it.value.isNullOrBlank() }

/**
 * Applies one text edit while preserving field bindings. Edits wholly inside one field replace that
 * field value; edits outside fields shift later ranges. A change crossing a field boundary or
 * touching multiple fields is rejected rather than silently unbinding persisted markers.
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
    // A required inline field must always retain a visible/editable range. Replacing a selected
    // field with new text is fine; deleting it to zero characters is ignored instead of leaving an
    // invisible zero-length binding that can never be selected again.
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
