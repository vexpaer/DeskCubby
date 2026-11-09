package com.deskcubby.app.ui.diary.filter

import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * An edge-to-edge A4 photo journal. Each day uses one page unless there are too many photos
 * to display legibly, in which case balanced continuation pages retain that day's date.
 *
 * Every tiling candidate is a slicing tree. A horizontal join stacks two aspect-preserving
 * rectangles; a vertical join puts them side by side. The resulting collage is itself an exact
 * aspect-ratio rectangle: no image ever needs to be cropped, stretched, or overlapped.
 */
internal data class MealPdfPageSpec(
    val width: Float = 595f,
    val height: Float = 842f,
    val footerHeight: Float = 28f,
) {
    val contentHeight: Float get() = height - footerHeight
    val contentAspect: Double get() = contentHeight.toDouble() / width
}

internal data class MealPdfDayInput(
    val dateIso: String,
    /** Height / width after EXIF rotation; null when unreadable. */
    val photoAspects: List<Float?>,
)

internal data class MealPdfPhotoPlacement(
    val dayIndex: Int,
    val photoIndex: Int,
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal data class MealPdfPageDate(val page: Int, val dateIso: String)

internal data class MealPdfLayout(
    val spec: MealPdfPageSpec,
    val pageCount: Int,
    val photos: List<MealPdfPhotoPlacement>,
    val dates: List<MealPdfPageDate>,
)

private sealed class Tile {
    abstract val aspect: Double
    abstract val shares: DoubleArray

    data class Photo(val index: Int, override val aspect: Double) : Tile() {
        override val shares = doubleArrayOf(1.0)
    }

    data class Join(
        val first: Tile,
        val second: Tile,
        val sideBySide: Boolean,
        override val aspect: Double,
        val firstShare: Double,
        override val shares: DoubleArray,
    ) : Tile()
}

private fun joinTiles(first: Tile, second: Tile, sideBySide: Boolean): Tile.Join {
    val share = if (sideBySide) {
        (1.0 / first.aspect) / (1.0 / first.aspect + 1.0 / second.aspect)
    } else {
        first.aspect / (first.aspect + second.aspect)
    }
    val aspect = if (sideBySide) {
        1.0 / (1.0 / first.aspect + 1.0 / second.aspect)
    } else {
        first.aspect + second.aspect
    }
    val shares = DoubleArray(first.shares.size + second.shares.size)
    first.shares.forEachIndexed { index, value -> shares[index] = value * share }
    second.shares.forEachIndexed { index, value ->
        shares[first.shares.size + index] = value * (1.0 - share)
    }
    return Tile.Join(first, second, sideBySide, aspect, share, shares)
}

/**
 * Favor page coverage, but penalize tiny leaf tiles. A compact beam keeps the search bounded
 * for large date ranges while considering both split axes and multiple tree shapes.
 */
private fun minimumPhotoShare(count: Int): Double = when (count) {
    1 -> 0.0
    2 -> 0.28
    in 3..4 -> 0.12
    in 5..6 -> 0.07
    else -> 0.035
}

private fun pageCoverage(tile: Tile, targetAspect: Double): Double =
    min(tile.aspect / targetAspect, targetAspect / tile.aspect)

private fun tileScore(tile: Tile, targetAspect: Double): Double {
    val minimum = minimumPhotoShare(tile.shares.size)
    val shortfall = tile.shares.sumOf { max(0.0, minimum - it) }
    return pageCoverage(tile, targetAspect) - shortfall * 3.0
}

private fun bestTile(aspects: List<Float?>, targetAspect: Double): Tile {
    val count = aspects.size
    val memo = Array(count) { arrayOfNulls<List<Tile>>(count) }
    aspects.forEachIndexed { index, aspect ->
        val valid = aspect?.toDouble()?.takeIf { it.isFinite() && it > 0.0 }
            ?: DEFAULT_PHOTO_ASPECT
        memo[index][index] = listOf(Tile.Photo(index, valid))
    }
    for (length in 2..count) {
        for (start in 0..count - length) {
            val end = start + length - 1
            val candidates = ArrayList<Tile>()
            for (split in start until end) {
                for (left in requireNotNull(memo[start][split])) {
                    for (right in requireNotNull(memo[split + 1][end])) {
                        candidates.add(joinTiles(left, right, sideBySide = true))
                        candidates.add(joinTiles(left, right, sideBySide = false))
                    }
                }
            }
            // Keep candidates spanning several aspect buckets, not just near-duplicates.
            val sorted = candidates.sortedByDescending { tileScore(it, targetAspect) }
            val buckets = HashSet<Int>()
            val selected = ArrayList<Tile>(BEAM_WIDTH)
            for (candidate in sorted) {
                val bucket = (kotlin.math.ln(candidate.aspect / targetAspect) * 6)
                    .toInt().coerceIn(-30, 30)
                if (buckets.add(bucket) || selected.size < 4) selected.add(candidate)
                if (selected.size >= BEAM_WIDTH) break
            }
            memo[start][end] = selected
        }
    }
    val choices = requireNotNull(memo[0][count - 1])
    val floor = minimumPhotoShare(count)
    // A hard visibility floor is preferable to a slightly fuller page with a microscopic photo.
    // If the actual photo aspects make the floor geometrically impossible, use the soft fallback.
    val bestFair = choices
        .filter { tile -> tile.shares.all { it + 1e-6 >= floor } }
        .maxByOrNull { pageCoverage(it, targetAspect) }
    val bestCoverage = choices.maxOf { pageCoverage(it, targetAspect) }

    // A strict visibility floor must not collapse the entire collage to a tiny centered strip.
    // Keep the fair candidate only when it preserves at least 80% of the best available coverage.
    return bestFair?.takeIf {
        pageCoverage(it, targetAspect) >= bestCoverage * MIN_FAIR_COVERAGE_RATIO
    } ?: choices.maxBy { tileScore(it, targetAspect) }
}

/**
 * Chooses a restrained gray date color when it meets WCAG AA contrast, otherwise falls back
 * to the higher-contrast black or white. The same helper is used by the Compose preview.
 */
internal fun mealPdfDateColorArgb(backgroundArgb: Int): Int {
    val red = (backgroundArgb ushr 16 and 255) / 255.0
    val green = (backgroundArgb ushr 8 and 255) / 255.0
    val blue = (backgroundArgb and 255) / 255.0
    val backgroundLuminance = 0.2126 * srgbChannelToLinear(red) +
        0.7152 * srgbChannelToLinear(green) +
        0.0722 * srgbChannelToLinear(blue)

    val preferredBrightness = red * 0.2126 + green * 0.7152 + blue * 0.0722
    val preferredGray = if (preferredBrightness < 0.45) 0.76 else 0.42
    val preferredChannel = Math.round(preferredGray * 255.0) / 255.0
    val preferredLuminance = srgbChannelToLinear(preferredChannel)
    val preferredContrast = (maxOf(backgroundLuminance, preferredLuminance) + 0.05) /
        (minOf(backgroundLuminance, preferredLuminance) + 0.05)

    val selectedGray = if (preferredContrast >= 4.5) {
        preferredChannel
    } else {
        val blackContrast = (backgroundLuminance + 0.05) / 0.05
        val whiteContrast = 1.05 / (backgroundLuminance + 0.05)
        if (blackContrast >= whiteContrast) 0.0 else 1.0
    }
    val component = Math.round(selectedGray * 255.0).toInt().coerceIn(0, 255)
    return 0xFF000000.toInt() or (component shl 16) or (component shl 8) or component
}

private fun srgbChannelToLinear(channel: Double): Double =
    if (channel <= 0.04045) channel / 12.92
    else Math.pow((channel + 0.055) / 1.055, 2.4)

internal fun mealCalendarPdfLayout(
    days: List<MealPdfDayInput>,
    spec: MealPdfPageSpec = MealPdfPageSpec(),
): MealPdfLayout {
    require(spec.width > 0f && spec.contentHeight > 0f)
    require(days.isNotEmpty() && days.all { it.photoAspects.isNotEmpty() }) {
        "所选日期范围内没有可导出的饮食照片"
    }

    val photos = ArrayList<MealPdfPhotoPlacement>(days.sumOf { it.photoAspects.size })
    val dates = ArrayList<MealPdfPageDate>()
    days.forEachIndexed { dayIndex, day ->
        val total = day.photoAspects.size
        val pagesForDay = (total + MAX_PHOTOS_PER_PAGE - 1) / MAX_PHOTOS_PER_PAGE
        var start = 0
        repeat(pagesForDay) { localPage ->
            val remainingPages = pagesForDay - localPage
            val chunkCount = (total - start + remainingPages - 1) / remainingPages
            val tile = bestTile(
                day.photoAspects.subList(start, start + chunkCount),
                spec.contentAspect,
            )
            val page = dates.size
            dates += MealPdfPageDate(page, day.dateIso)
            val aspect = tile.aspect
            val width = min(spec.width.toDouble(), spec.contentHeight / aspect).toFloat()
            val height = (width * aspect).toFloat()
            val x = (spec.width - width) / 2f
            val y = (spec.contentHeight - height) / 2f

            fun place(node: Tile, left: Float, top: Float, w: Float, h: Float) {
                when (node) {
                    is Tile.Photo -> photos += MealPdfPhotoPlacement(
                        dayIndex = dayIndex,
                        photoIndex = start + node.index,
                        page = page,
                        x = left,
                        y = top,
                        width = w,
                        height = h,
                    )
                    is Tile.Join -> {
                        if (node.sideBySide) {
                            val firstWidth = w * node.firstShare.toFloat()
                            place(node.first, left, top, firstWidth, h)
                            place(node.second, left + firstWidth, top, w - firstWidth, h)
                        } else {
                            val firstHeight = h * node.firstShare.toFloat()
                            place(node.first, left, top, w, firstHeight)
                            place(node.second, left, top + firstHeight, w, h - firstHeight)
                        }
                    }
                }
            }
            place(tile, x, y, width, height)
            start += chunkCount
        }
    }
    return MealPdfLayout(spec, dates.size, photos, dates)
}

/**
 * Paints the entire page (including the footer) in the selected color, then draws photo
 * XObjects and a small bottom-right date. Missing images get a neutral tinted placeholder.
 */
internal fun mealPdfPageContent(
    layout: MealPdfLayout,
    page: Int,
    imageNames: List<String?>,
    fontResourceName: String,
    backgroundArgb: Int = DEFAULT_MEAL_PDF_BACKGROUND,
): String {
    require(imageNames.size == layout.photos.size) { "图片数量与布局不一致" }
    if (page !in 0 until layout.pageCount) return ""
    val spec = layout.spec
    val red = (backgroundArgb ushr 16 and 255) / 255f
    val green = (backgroundArgb ushr 8 and 255) / 255f
    val blue = (backgroundArgb and 255) / 255f
    val out = StringBuilder()
    out.append("q ").append(pdfNumber(red)).append(' ')
        .append(pdfNumber(green)).append(' ').append(pdfNumber(blue))
        .append(" rg 0 0 ").append(pdfNumber(spec.width)).append(' ')
        .append(pdfNumber(spec.height)).append(" re f Q\n")
    layout.photos.forEachIndexed { index, placement ->
        if (placement.page != page) return@forEachIndexed
        val bottom = spec.height - placement.y - placement.height
        val name = imageNames[index]
        if (name != null) {
            out.append("q ").append(pdfNumber(placement.width)).append(" 0 0 ")
                .append(pdfNumber(placement.height)).append(' ')
                .append(pdfNumber(placement.x)).append(' ')
                .append(pdfNumber(bottom)).append(" cm /")
                .append(name).append(" Do Q\n")
        } else {
            val gray = if ((red + green + blue) / 3f < 0.5f) 0.25f else 0.87f
            out.append("q ").append(pdfNumber(gray)).append(' ')
                .append(pdfNumber(gray)).append(' ').append(pdfNumber(gray))
                .append(" rg ").append(pdfNumber(placement.x)).append(' ')
                .append(pdfNumber(bottom)).append(' ')
                .append(pdfNumber(placement.width)).append(' ')
                .append(pdfNumber(placement.height)).append(" re f Q\n")
        }
    }
    val date = pdfAsciiText(layout.dates[page].dateIso.replace('-', '.'))
    val fontSize = 9f
    val x = max(0f, spec.width - 14f - helveticaTextWidth(date, fontSize))
    val dateGray = ((mealPdfDateColorArgb(backgroundArgb) ushr 16) and 255) / 255f
    out.append("BT /").append(fontResourceName).append(' ')
        .append(pdfNumber(fontSize)).append(" Tf ")
        .append(pdfNumber(dateGray)).append(' ').append(pdfNumber(dateGray)).append(' ')
        .append(pdfNumber(dateGray)).append(" rg ")
        .append(pdfNumber(x)).append(" 11 Td (").append(date).append(") Tj ET\n")
    return out.toString()
}

internal fun pdfNumber(value: Float): String {
    require(value.isFinite()) { "PDF 数值无效" }
    return String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.').let {
        if (it == "-0" || it.isEmpty()) "0" else it
    }
}

internal fun pdfAsciiText(text: String): String = buildString {
    text.forEach { char ->
        when {
            char == '(' || char == ')' || char == '\\' -> append('\\').append(char)
            char in ' '..'~' -> append(char)
            else -> append('?')
        }
    }
}

internal fun helveticaTextWidth(text: String, fontSize: Float): Float =
    text.sumOf { char ->
        when (char) {
            '-', '.' -> 278
            ' ' -> 278
            else -> 556
        }
    } * fontSize / 1000f

internal fun isDateInMealExportRange(
    date: LocalDate,
    startInclusive: LocalDate,
    endInclusive: LocalDate,
): Boolean {
    require(!startInclusive.isAfter(endInclusive)) { "开始日期不能晚于结束日期" }
    return !date.isBefore(startInclusive) && !date.isAfter(endInclusive)
}

internal val DEFAULT_MEAL_PDF_BACKGROUND: Int = 0xFFF5F2EA.toInt()
private const val DEFAULT_PHOTO_ASPECT = 0.75
private const val MAX_PHOTOS_PER_PAGE = 9
private const val BEAM_WIDTH = 14
private const val MIN_FAIR_COVERAGE_RATIO = 0.8
