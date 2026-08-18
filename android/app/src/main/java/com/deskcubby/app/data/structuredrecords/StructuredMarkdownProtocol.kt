package com.deskcubby.app.data.structuredrecords

/**
 * The DeskCubby structured-record Markdown protocol.
 *
 * Values are stored in ordinary Markdown, wrapped in paired HTML comments that only carry the
 * stable field ID:
 *
 * ```markdown
 * 做了 <!--dc:f_pushups-->20<!--dc:/f_pushups--> 个俯卧撑。
 * 午饭：[<!--dc:f_lunch_time-->12:36<!--dc:/f_lunch_time-->]
 * ```
 *
 * In Obsidian / Typora / VS Code the comments render as invisible metadata and the user sees
 * normal human text. The parser must never delete user prose to "repair" data, and must tolerate
 * unknown field IDs (the field definition may be missing because `.deskcubby` was not copied).
 */
object StructuredMarkdownProtocol {

    /** Prefix/suffix for the start marker. `<!--dc:` + fieldId + `-->`. */
    const val MARKER_PREFIX = "<!--dc:"
    const val MARKER_SUFFIX = "-->"

    fun openMarker(fieldId: String): String = "$MARKER_PREFIX$fieldId$MARKER_SUFFIX"
    fun closeMarker(fieldId: String): String = "$MARKER_PREFIX/$fieldId$MARKER_SUFFIX"

    /** One parsed occurrence inside a single Markdown document. */
    data class Occurrence(
        val fieldId: String,
        val rawValue: String,
        val startIndex: Int,
        val endIndex: Int,
        /** Sequential order among all dc markers in the file (0-based). */
        val orderInFile: Int,
    )

    /**
     * Scans [content] for dc field markers, validating that each open marker is followed by a
     * matching close marker with the same field ID before the next open marker. Non-matching or
     * unclosed markers do not abort the parse; they are simply not produced as occurrences, and
     * any surrounding text is left untouched (the caller only writes/edits matched regions).
     */
    fun parse(content: String): List<Occurrence> {
        val result = ArrayList<Occurrence>()
        var cursor = 0
        var order = 0
        val openPrefix = MARKER_PREFIX
        while (true) {
            val open = content.indexOf(openPrefix, cursor)
            if (open < 0) break
            val close = content.indexOf(MARKER_SUFFIX, open)
            if (close < 0) break
            val fieldId = content.substring(open + openPrefix.length, close).trim()
            if (fieldId.isBlank() || fieldId.contains("/")) {
                cursor = close + MARKER_SUFFIX.length
                continue
            }
            val valueStart = close + MARKER_SUFFIX.length
            val closeMarker = MARKER_PREFIX + "/" + fieldId + MARKER_SUFFIX
            val valueEnd = content.indexOf(closeMarker, valueStart)
            if (valueEnd < 0) {
                cursor = valueStart
                continue
            }
            // Reject nesting of the same field id before the close marker.
            val nestedOpen = content.indexOf("$openPrefix$fieldId$MARKER_SUFFIX", valueStart)
            if (nestedOpen in valueStart until valueEnd) {
                cursor = valueStart
                continue
            }
            val rawValue = content.substring(valueStart, valueEnd)
            result += Occurrence(
                fieldId = fieldId,
                rawValue = rawValue,
                startIndex = open,
                endIndex = valueEnd + closeMarker.length,
                orderInFile = order++,
            )
            cursor = valueEnd + closeMarker.length
        }
        return result
    }

    /** True when [content] contains at least one dc marker (fast path for scans). */
    fun containsMarkers(content: String): Boolean = MARKER_PREFIX in content

    /**
     * Replaces the value of one matched occurrence within [content] by its raw span, preserving
     * every other byte of the document (including markers and surrounding prose).
     */
    fun replaceValue(content: String, occurrence: Occurrence, newRawValue: String): String =
        content.replaceRange(occurrence.startIndex, occurrence.endIndex, openMarker(occurrence.fieldId) + newRawValue + closeMarker(occurrence.fieldId))

    /** Builds a full standalone record line from [segments] values. */
    fun buildRecordText(segments: List<StructuredRecordSegment>, values: List<String>): String {
        require(segments.count { it is StructuredRecordSegment.Field } == values.size) {
            "段数与值数量不匹配"
        }
        val sb = StringBuilder()
        var valueIndex = 0
        for (segment in segments) {
            when (segment) {
                is StructuredRecordSegment.Text -> sb.append(segment.value)
                is StructuredRecordSegment.Field -> {
                    val raw = values[valueIndex++]
                    sb.append(openMarker(segment.fieldId)).append(raw).append(closeMarker(segment.fieldId))
                }
            }
        }
        return sb.toString()
    }

    /** Strips the paired markers from [content] leaving only the visible value text. */
    fun stripMarkers(content: String): String {
        val occurrences = parse(content)
        if (occurrences.isEmpty()) return content
        var output = content
        // Replace from the end so indices stay valid.
        for (index in occurrences.indices.reversed()) {
            val occurrence = occurrences[index]
            output = output.replaceRange(
                occurrence.startIndex,
                occurrence.endIndex,
                occurrence.rawValue,
            )
        }
        return output
    }
}
