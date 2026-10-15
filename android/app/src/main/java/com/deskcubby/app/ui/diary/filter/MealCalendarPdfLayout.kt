package com.deskcubby.app.ui.diary.filter

import java.time.LocalDate
import java.util.Locale

/**
 * Pure page layout for the meal-calendar PDF exporter.
 *
 * Photos flow through two independent columns (masonry style) across as many A4 pages as needed.
 * Every day starts with a dashed separator in both columns at that column's own cursor, so the
 * separator is stepped when the columns end at different heights; the two halves are joined by a
 * vertical segment in the gutter when they share a page. The day's date is drawn in a gap of the
 * separator half whose column runs longer until the next separator (ties prefer the left column).
 *
 * Coordinates are PDF points measured from the page's top-left corner. Nothing here touches
 * Android graphics so the complete layout is unit-testable, and memory grows only linearly with
 * the number of photos, which keeps arbitrarily long ranges exportable.
 */
internal data class MealPdfPageSpec(
    val width: Float = 595f,
    val height: Float = 842f,
    val margin: Float = 12f,
    val gutter: Float = 10f,
    val photoGap: Float = 8f,
    val separatorBand: Float = 20f,
) {
    val columnWidth: Float = (width - margin * 2f - gutter) / 2f
    val contentTop: Float = margin
    val contentBottom: Float = height - margin
    val gutterCenterX: Float = margin + columnWidth + gutter / 2f

    fun columnLeft(column: Int): Float = margin + column * (columnWidth + gutter)

    fun columnCenterX(column: Int): Float = columnLeft(column) + columnWidth / 2f

    /** Taller photos are center-cropped so that a single photo always fits below a separator. */
    val maxPhotoHeight: Float = contentBottom - contentTop - separatorBand

    val minPhotoHeight: Float = columnWidth * 0.35f
}

internal data class MealPdfDayInput(
    val dateIso: String,
    /** Height divided by width for each photo, already EXIF-oriented; null or invalid if unknown. */
    val photoAspects: List<Float?>,
)

internal data class MealPdfPhotoPlacement(
    val dayIndex: Int,
    val photoIndex: Int,
    val page: Int,
    val column: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

/** One column's half of a day separator; [y] is the dashed line's vertical position. */
internal data class MealPdfSeparatorSegment(
    val page: Int,
    val y: Float,
)

internal data class MealPdfSeparator(
    val dayIndex: Int,
    val dateIso: String,
    /** Index 0 is the left column, index 1 the right column. */
    val segments: List<MealPdfSeparatorSegment>,
    val labelColumn: Int,
    /**
     * True when the halves sit on different pages and the other column also holds photos of this
     * day, so that page needs its own date label.
     */
    val repeatLabelInOtherColumn: Boolean = false,
)

/**
 * A dated single-column separator at the top of a page where a column continues a day whose
 * earlier photos are on the previous page.
 */
internal data class MealPdfContinuation(
    val dayIndex: Int,
    val dateIso: String,
    val page: Int,
    val column: Int,
    val y: Float,
)

internal data class MealPdfLayout(
    val spec: MealPdfPageSpec,
    val pageCount: Int,
    val photos: List<MealPdfPhotoPlacement>,
    val separators: List<MealPdfSeparator>,
    val continuations: List<MealPdfContinuation> = emptyList(),
)

internal fun mealPdfPhotoHeight(aspect: Float?, spec: MealPdfPageSpec): Float {
    val validAspect = aspect?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_PHOTO_ASPECT
    return (spec.columnWidth * validAspect).coerceIn(spec.minPhotoHeight, spec.maxPhotoHeight)
}

internal fun mealCalendarPdfLayout(
    days: List<MealPdfDayInput>,
    spec: MealPdfPageSpec = MealPdfPageSpec(),
): MealPdfLayout {
    require(days.isNotEmpty() && days.all { it.photoAspects.isNotEmpty() }) {
        "所选日期范围内没有可导出的饮食照片"
    }
    val cursorPage = IntArray(COLUMN_COUNT)
    val cursorY = FloatArray(COLUMN_COUNT) { spec.contentTop }
    val photos = ArrayList<MealPdfPhotoPlacement>(days.sumOf { it.photoAspects.size })
    val segmentsPerDay = ArrayList<Array<MealPdfSeparatorSegment>>(days.size)
    val photoColumnsPerDay = ArrayList<BooleanArray>(days.size)
    val continuations = ArrayList<MealPdfContinuation>()
    days.forEachIndexed { dayIndex, day ->
        val segments = Array(COLUMN_COUNT) { column ->
            if (cursorY[column] + spec.separatorBand > spec.contentBottom) {
                cursorPage[column]++
                cursorY[column] = spec.contentTop
            }
            val segment = MealPdfSeparatorSegment(
                page = cursorPage[column],
                y = cursorY[column] + spec.separatorBand / 2f,
            )
            cursorY[column] += spec.separatorBand
            segment
        }
        val hasPhoto = BooleanArray(COLUMN_COUNT)
        day.photoAspects.forEachIndexed { photoIndex, aspect ->
            val height = mealPdfPhotoHeight(aspect, spec)
            val column = (0 until COLUMN_COUNT).minWith(
                compareBy<Int>({ cursorPage[it] }, { cursorY[it] }),
            )
            if (cursorY[column] + height > spec.contentBottom) {
                cursorPage[column]++
                cursorY[column] = spec.contentTop
                val lineY = cursorY[column] + spec.separatorBand / 2f
                if (!hasPhoto[column]) {
                    // Keep a separator attached to the first photo it introduces instead of
                    // leaving it orphaned at the bottom of the previous page.
                    segments[column] = MealPdfSeparatorSegment(page = cursorPage[column], y = lineY)
                } else {
                    continuations += MealPdfContinuation(
                        dayIndex = dayIndex,
                        dateIso = day.dateIso,
                        page = cursorPage[column],
                        column = column,
                        y = lineY,
                    )
                }
                cursorY[column] += spec.separatorBand
            }
            photos += MealPdfPhotoPlacement(
                dayIndex = dayIndex,
                photoIndex = photoIndex,
                page = cursorPage[column],
                column = column,
                x = spec.columnLeft(column),
                y = cursorY[column],
                width = spec.columnWidth,
                height = height,
            )
            cursorY[column] += height + spec.photoGap
            hasPhoto[column] = true
        }
        segmentsPerDay += segments
        photoColumnsPerDay += hasPhoto
    }

    fun linear(page: Int, y: Float): Double = page.toDouble() * spec.height + y
    val separators = segmentsPerDay.mapIndexed { dayIndex, segments ->
        val distances = DoubleArray(COLUMN_COUNT) { column ->
            val start = linear(segments[column].page, segments[column].y)
            val end = segmentsPerDay.getOrNull(dayIndex + 1)?.get(column)
                ?.let { linear(it.page, it.y) }
                ?: linear(cursorPage[column], cursorY[column])
            end - start
        }
        // Only a column that actually holds this day's photos may carry the date.
        val hasPhotos = photoColumnsPerDay[dayIndex]
        val labelColumn = when {
            !hasPhotos[0] -> 1
            !hasPhotos[1] -> 0
            distances[1] > distances[0] -> 1
            else -> 0
        }
        val otherColumn = 1 - labelColumn
        MealPdfSeparator(
            dayIndex = dayIndex,
            dateIso = days[dayIndex].dateIso,
            segments = segments.toList(),
            labelColumn = labelColumn,
            repeatLabelInOtherColumn = segments[0].page != segments[1].page &&
                photoColumnsPerDay[dayIndex][otherColumn],
        )
    }
    val pageCount = maxOf(
        cursorPage.max(),
        photos.maxOfOrNull { it.page } ?: 0,
        separators.maxOf { separator -> separator.segments.maxOf { it.page } },
    ) + 1
    return MealPdfLayout(
        spec = spec,
        pageCount = pageCount,
        photos = photos,
        separators = separators,
        continuations = continuations,
    )
}

/**
 * Builds the PDF content stream of one page. [imageNames] holds the XObject resource name of
 * each placement in [MealPdfLayout.photos] order, or null when the photo could not be decoded
 * and a neutral placeholder should be drawn instead.
 */
internal fun mealPdfPageContent(
    layout: MealPdfLayout,
    page: Int,
    imageNames: List<String?>,
    fontResourceName: String,
): String {
    require(imageNames.size == layout.photos.size) { "图片数量与布局不一致" }
    val spec = layout.spec
    val out = StringBuilder()
    layout.photos.forEachIndexed { index, placement ->
        if (placement.page != page) return@forEachIndexed
        val bottom = spec.height - placement.y - placement.height
        val name = imageNames[index]
        if (name != null) {
            out.append("q ")
                .append(pdfNumber(placement.width)).append(" 0 0 ")
                .append(pdfNumber(placement.height)).append(' ')
                .append(pdfNumber(placement.x)).append(' ')
                .append(pdfNumber(bottom)).append(" cm /")
                .append(name).append(" Do Q\n")
        } else {
            out.append("q 0.886 0.898 0.910 rg ")
                .append(pdfNumber(placement.x)).append(' ')
                .append(pdfNumber(bottom)).append(' ')
                .append(pdfNumber(placement.width)).append(' ')
                .append(pdfNumber(placement.height)).append(" re f Q\n")
        }
    }
    layout.separators.forEach { separator ->
        val left = separator.segments[0]
        val right = separator.segments[1]
        if (left.page != page && right.page != page) return@forEach
        val label = pdfAsciiText(separator.dateIso)
        val labelHalf = helveticaTextWidth(label, LABEL_FONT_SIZE) / 2f + LABEL_PADDING
        out.append(DASH_STYLE)
        listOf(left to 0, right to 1).forEach { (segment, column) ->
            if (segment.page != page) return@forEach
            val startX = if (column == 0) spec.margin else spec.gutterCenterX
            val endX = if (column == 0) spec.gutterCenterX else spec.width - spec.margin
            val y = spec.height - segment.y
            if (separator.isLabelled(column)) {
                val center = spec.columnCenterX(column)
                appendLine(out, startX, y, center - labelHalf, y)
                appendLine(out, center + labelHalf, y, endX, y)
            } else {
                appendLine(out, startX, y, endX, y)
            }
        }
        if (left.page == page && right.page == page && left.y != right.y) {
            appendLine(
                out,
                spec.gutterCenterX,
                spec.height - left.y,
                spec.gutterCenterX,
                spec.height - right.y,
            )
        }
        out.append("Q\n")
        separator.segments.forEachIndexed { column, labelSegment ->
            if (labelSegment.page != page || !separator.isLabelled(column)) return@forEachIndexed
            appendLabel(out, spec, column, labelSegment.y, label, labelHalf, fontResourceName)
        }
    }
    layout.continuations.forEach { continuation ->
        if (continuation.page != page) return@forEach
        val label = pdfAsciiText(continuation.dateIso)
        val labelHalf = helveticaTextWidth(label, LABEL_FONT_SIZE) / 2f + LABEL_PADDING
        val center = spec.columnCenterX(continuation.column)
        val left = spec.columnLeft(continuation.column)
        val y = spec.height - continuation.y
        out.append(DASH_STYLE)
        appendLine(out, left, y, center - labelHalf, y)
        appendLine(out, center + labelHalf, y, left + spec.columnWidth, y)
        out.append("Q\n")
        appendLabel(out, spec, continuation.column, continuation.y, label, labelHalf, fontResourceName)
    }
    return out.toString()
}

private fun appendLabel(
    out: StringBuilder,
    spec: MealPdfPageSpec,
    column: Int,
    lineY: Float,
    label: String,
    labelHalf: Float,
    fontResourceName: String,
) {
    val textX = spec.columnCenterX(column) - labelHalf + LABEL_PADDING
    val baseline = spec.height - lineY - LABEL_FONT_SIZE * 0.35f
    out.append("BT /").append(fontResourceName).append(' ')
        .append(pdfNumber(LABEL_FONT_SIZE)).append(" Tf 0.36 0.38 0.40 rg ")
        .append(pdfNumber(textX)).append(' ')
        .append(pdfNumber(baseline)).append(" Td (")
        .append(label).append(") Tj ET\n")
}

private fun MealPdfSeparator.isLabelled(column: Int): Boolean =
    column == labelColumn || repeatLabelInOtherColumn

private fun appendLine(out: StringBuilder, x1: Float, y1: Float, x2: Float, y2: Float) {
    if (x1 == x2 && y1 == y2) return
    out.append(pdfNumber(x1)).append(' ').append(pdfNumber(y1)).append(" m ")
        .append(pdfNumber(x2)).append(' ').append(pdfNumber(y2)).append(" l S\n")
}

internal fun pdfNumber(value: Float): String {
    require(value.isFinite()) { "PDF 数值无效" }
    return String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.').let {
        if (it == "-0") "0" else it
    }
}

/** Keeps only printable ASCII and escapes PDF string delimiters. */
internal fun pdfAsciiText(text: String): String = buildString {
    text.forEach { char ->
        when {
            char == '(' || char == ')' || char == '\\' -> append('\\').append(char)
            char in ' '..'~' -> append(char)
            else -> append('?')
        }
    }
}

/** Standard Helvetica advance widths for the characters used in ISO dates. */
internal fun helveticaTextWidth(text: String, fontSize: Float): Float =
    text.sumOf { char: Char ->
        when (char) {
            '-' -> 333
            ' ' -> 278
            else -> 556
        }.toInt()
    } * fontSize / 1000f

internal fun isDateInMealExportRange(
    date: LocalDate,
    startInclusive: LocalDate,
    endInclusive: LocalDate,
): Boolean {
    require(!startInclusive.isAfter(endInclusive)) { "开始日期不能晚于结束日期" }
    return !date.isBefore(startInclusive) && !date.isAfter(endInclusive)
}

private const val COLUMN_COUNT = 2
private const val DEFAULT_PHOTO_ASPECT = 0.75f
internal const val LABEL_FONT_SIZE = 9f
private const val LABEL_PADDING = 4f
private const val DASH_STYLE = "q 0.62 0.62 0.62 RG 0.8 w [3 2] 0 d\n"
