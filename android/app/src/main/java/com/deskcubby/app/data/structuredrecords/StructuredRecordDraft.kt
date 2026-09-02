package com.deskcubby.app.data.structuredrecords

import java.time.LocalTime

data class StructuredDraftField(
    val occurrenceIndex: Int,
    val fieldId: String,
    val start: Int,
    val endExclusive: Int,
    val value: String?,
    val templateBinding: Boolean = false,
    /** UI-only legacy placeholder. It is never serialized as a structured field or indexed. */
    val plainPlaceholder: Boolean = false,
)

data class StructuredRecordDraft(
    val text: String,
    val fields: List<StructuredDraftField>,
)

private const val PLAIN_XX_FIELD_ID = "__plain_xx_placeholder__"
private val PLAIN_XX_PATTERN = Regex("xx", RegexOption.IGNORE_CASE)

/**
 * Rebuilds UI-only `xx` spans from the current text. They deliberately live in [fields] so the
 * existing inline editor keeps its original underline + tap-to-select interaction, while every
 * persistence helper below filters them out before producing structured segments or values.
 */
private fun withPlainXxPlaceholders(draft: StructuredRecordDraft): StructuredRecordDraft {
    val structured = draft.fields.filterNot { it.plainPlaceholder }
    val placeholders = PLAIN_XX_PATTERN.findAll(draft.text).mapIndexedNotNull { index, match ->
        val start = match.range.first
        val endExclusive = match.range.last + 1
        val overlapsStructuredField = structured.any { field ->
            start < field.endExclusive && endExclusive > field.start
        }
        if (overlapsStructuredField) {
            null
        } else {
            StructuredDraftField(
                occurrenceIndex = -1 - index,
                fieldId = PLAIN_XX_FIELD_ID,
                start = start,
                endExclusive = endExclusive,
                value = null,
                plainPlaceholder = true,
            )
        }
    }.toList()
    return draft.copy(fields = (structured + placeholders).sortedBy { it.start })
}

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
    return withPlainXxPlaceholders(StructuredRecordDraft(text.toString(), spans))
}

/**
 * Controls whether the UI may attempt submission. Placeholder labels keep typed fields visible, so
 * an unfinished draft must remain tappable and let the ViewModel return a precise validation error.
 * [structuredDraftValues] remains the strict completeness gate before any durable write.
 */
fun isStructuredDraftReady(draft: StructuredRecordDraft): Boolean = draft.text.isNotBlank()

/**
 * Applies one text edit while preserving structured-field bindings. `xx` placeholders are a visual
 * overlay only: edits treat them exactly like ordinary text, then the overlay is rebuilt from any
 * literal `xx` that remains. This restores the original replace-on-tap behavior without giving `xx`
 * a field ID, marker, index entry, statistic, or any other structured-record semantics.
 *
 * Edits wholly inside one structured field replace that field value; edits outside structured
 * fields shift later ranges. A change crossing a structured-field boundary or touching multiple
 * structured fields is rejected rather than silently unbinding persisted markers.
 *
 * Template drafts are the one exception: deleting exactly one whole inline structured field removes
 * that binding from the template. Record-entry drafts keep rejecting the same edit so a required
 * field cannot become an invisible zero-length binding.
 */
fun applyStructuredDraftEdit(
    draft: StructuredRecordDraft,
    candidate: String,
): StructuredRecordDraft? {
    if (candidate == draft.text) return draft
    val old = draft.text
    val structuredFields = draft.fields.filterNot { it.plainPlaceholder }
    var start = 0
    val prefixLimit = minOf(old.length, candidate.length)
    while (start < prefixLimit && old[start] == candidate[start]) start++

    var oldSuffix = old.length
    var newSuffix = candidate.length
    while (oldSuffix > start && newSuffix > start && old[oldSuffix - 1] == candidate[newSuffix - 1]) {
        oldSuffix--
        newSuffix--
    }

    val touched = structuredFields.filter { field ->
        if (oldSuffix == start) {
            start > field.start && start < field.endExclusive
        } else {
            start < field.endExclusive && oldSuffix > field.start
        }
    }
    if (touched.size > 1) return null
    val delta = (newSuffix - start) - (oldSuffix - start)

    if (touched.isEmpty()) {
        val shifted = structuredFields.map { field ->
            when {
                field.endExclusive <= start -> field
                field.start >= oldSuffix -> field.copy(
                    start = field.start + delta,
                    endExclusive = field.endExclusive + delta,
                )
                else -> return null
            }
        }
        return withPlainXxPlaceholders(StructuredRecordDraft(candidate, shifted))
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
        val remaining = structuredFields
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
        return withPlainXxPlaceholders(StructuredRecordDraft(candidate, remaining))
    }
    // A required inline field in record entry must always retain a visible/editable range.
    if (newTargetEnd <= target.start || newTargetEnd > candidate.length) return null
    val newValue = candidate.substring(target.start, newTargetEnd).takeIf(String::isNotBlank)
        ?: return null
    val updated = structuredFields.map { field ->
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
    return withPlainXxPlaceholders(StructuredRecordDraft(candidate, updated))
}

fun replaceStructuredDraftField(
    draft: StructuredRecordDraft,
    occurrenceIndex: Int,
    replacement: String,
): StructuredRecordDraft {
    val structuredFields = draft.fields.filterNot { it.plainPlaceholder }
    val field = structuredFields.firstOrNull { it.occurrenceIndex == occurrenceIndex } ?: return draft
    val safe = replacement.trim()
    // Assisted replacements (chips/pickers/quick actions) must obey the same invariant as direct
    // editing: a field binding may never collapse into an invisible zero-length range.
    if (safe.isBlank()) return draft
    val text = draft.text.replaceRange(field.start, field.endExclusive, safe)
    val delta = safe.length - (field.endExclusive - field.start)
    val updated = structuredFields.map { current ->
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
    return withPlainXxPlaceholders(StructuredRecordDraft(text, updated))
}

fun insertStructuredTemplateField(
    draft: StructuredRecordDraft,
    field: StructuredField,
    offset: Int,
): StructuredRecordDraft {
    val structuredFields = draft.fields.filterNot { it.plainPlaceholder }
    val safeOffset = offset.coerceIn(0, draft.text.length)
    if (structuredFields.any { safeOffset > it.start && safeOffset < it.endExclusive }) return draft
    val label = field.name
    val text = draft.text.substring(0, safeOffset) + label + draft.text.substring(safeOffset)
    val occurrence = (structuredFields.maxOfOrNull { it.occurrenceIndex } ?: -1) + 1
    val shifted = structuredFields.map { current ->
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
    return withPlainXxPlaceholders(StructuredRecordDraft(text, shifted.sortedBy { it.start }))
}

fun structuredDraftToSegments(draft: StructuredRecordDraft): List<StructuredRecordSegment> {
    val segments = mutableListOf<StructuredRecordSegment>()
    var cursor = 0
    draft.fields.filterNot { it.plainPlaceholder }.sortedBy { it.start }.forEach { field ->
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
    val ordered = draft.fields.filterNot { it.plainPlaceholder }.sortedBy { it.start }
    if (ordered.any { it.value.isNullOrBlank() }) return null
    return ordered.map { requireNotNull(it.value) }
}
