package com.deskcubby.app.ui.diary.filter

import com.deskcubby.app.data.model.MealPhotosPerRow
import com.deskcubby.app.data.model.mealPhotoRowSizes
import java.time.LocalDate

/**
 * Pixel layout used by the meal-calendar PNG exporter.
 *
 * Keeping this calculation free of Android graphics calls makes the allocation guard testable:
 * [mealCalendarExportLayout] must succeed before the repository creates the destination bitmap.
 */
internal data class MealCalendarExportLayout(
    val width: Int,
    val height: Int,
    val imageHeight: Int,
    val captionHeight: Int,
    val rowsPerDay: List<List<Int>>,
) {
    val pixelCount: Long = width.toLong() * height.toLong()
    val cardHeight: Int = imageHeight + captionHeight

    companion object {
        const val CONTENT_PADDING = 28
        const val HEADER_HEIGHT = 118
        const val DAY_HEADER_HEIGHT = 54
        const val CELL_GAP = 12
        const val ROW_GAP = 12
        const val DAY_GAP = 24
    }
}

internal fun mealCalendarExportLayout(
    photoCounts: List<Int>,
    imageMaxHeight: Int,
    showCaptions: Boolean,
    photosPerRow: MealPhotosPerRow,
): MealCalendarExportLayout {
    require(photoCounts.isNotEmpty() && photoCounts.all { it > 0 }) {
        "所选日期范围内没有可导出的饮食照片"
    }
    val imageHeight = imageMaxHeight.coerceIn(MIN_IMAGE_HEIGHT_PX, MAX_IMAGE_HEIGHT_PX)
    val captionHeight = if (showCaptions) CAPTION_HEIGHT_PX else 0
    val cardHeight = imageHeight.toLong() + captionHeight

    var height = (
        MealCalendarExportLayout.CONTENT_PADDING +
            MealCalendarExportLayout.HEADER_HEIGHT +
            MealCalendarExportLayout.CONTENT_PADDING
        ).toLong()
    // Calculate only row counts first. A corrupt or maliciously large photo count must be
    // rejected before mealPhotoRowSizes() can allocate a correspondingly huge List<Int>.
    photoCounts.forEach { photoCount ->
        val rowCount = mealPhotoRowCount(photoCount, photosPerRow)
        height += MealCalendarExportLayout.DAY_HEADER_HEIGHT
        height += rowCount * cardHeight
        height += (rowCount - 1L).coerceAtLeast(0L) * MealCalendarExportLayout.ROW_GAP
        height += MealCalendarExportLayout.DAY_GAP
        require(
            height <= EXPORT_MAX_HEIGHT_PX &&
                EXPORT_WIDTH_PX.toLong() * height <= EXPORT_MAX_PIXELS,
        ) {
            "所选范围生成的长图过高，请缩短日期范围"
        }
    }

    val pixels = EXPORT_WIDTH_PX.toLong() * height
    require(height <= EXPORT_MAX_HEIGHT_PX && pixels <= EXPORT_MAX_PIXELS) {
        "所选范围生成的长图过高，请缩短日期范围"
    }
    val rowsPerDay = photoCounts.map { mealPhotoRowSizes(it, photosPerRow) }
    return MealCalendarExportLayout(
        width = EXPORT_WIDTH_PX,
        height = height.toInt(),
        imageHeight = imageHeight,
        captionHeight = captionHeight,
        rowsPerDay = rowsPerDay,
    )
}

private fun mealPhotoRowCount(count: Int, mode: MealPhotosPerRow): Long {
    val countLong = count.toLong()
    return when (mode) {
        MealPhotosPerRow.TWO -> (countLong + 1L) / 2L
        // SMART changes the composition of rows to avoid a dangling singleton, but its number of
        // rows is still ceil(count / 3), just like the fixed THREE layout.
        MealPhotosPerRow.THREE,
        MealPhotosPerRow.SMART,
        -> (countLong + 2L) / 3L
    }
}

internal fun isDateInMealExportRange(
    date: LocalDate,
    startInclusive: LocalDate,
    endInclusive: LocalDate,
): Boolean {
    require(!startInclusive.isAfter(endInclusive)) { "开始日期不能晚于结束日期" }
    return !date.isBefore(startInclusive) && !date.isAfter(endInclusive)
}

internal const val EXPORT_WIDTH_PX = 720
internal const val EXPORT_MAX_HEIGHT_PX = 16_384
internal const val EXPORT_MAX_PIXELS = 12_000_000L
private const val MIN_IMAGE_HEIGHT_PX = 80
private const val MAX_IMAGE_HEIGHT_PX = 320
private const val CAPTION_HEIGHT_PX = 44
