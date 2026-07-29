package com.deskcubby.app.ui.diary.filter

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.deskcubby.app.data.model.MealPhotoFilterSettings

/**
 * Creates an Android-compatible 4 x 5 color matrix. Translation values use the 0..255 channel
 * convention expected by [ColorMatrix].
 */
fun mealPhotoFilterMatrix(settings: MealPhotoFilterSettings): FloatArray {
    val value = settings.normalized()
    var result = saturationMatrix(value.saturation)
    result = multiplyColorMatrices(channelBalanceMatrix(value.warmth, value.tint), result)
    result = multiplyColorMatrices(contrastMatrix(value.contrast), result)
    result = multiplyColorMatrices(brightnessMatrix(value.brightness), result)
    return result
}

fun MealPhotoFilterSettings.asComposeColorFilter(): ColorFilter? {
    val value = normalized()
    if (!value.enabled || !value.hasVisibleAdjustment()) return null
    return ColorFilter.colorMatrix(ColorMatrix(mealPhotoFilterMatrix(value)))
}

internal fun multiplyColorMatrices(left: FloatArray, right: FloatArray): FloatArray {
    require(left.size == COLOR_MATRIX_SIZE && right.size == COLOR_MATRIX_SIZE)
    val result = FloatArray(COLOR_MATRIX_SIZE)
    for (row in 0 until COLOR_ROWS) {
        val rowStart = row * COLOR_COLUMNS
        for (column in 0 until COLOR_CHANNELS) {
            var sum = 0f
            for (channel in 0 until COLOR_CHANNELS) {
                sum += left[rowStart + channel] * right[channel * COLOR_COLUMNS + column]
            }
            result[rowStart + column] = sum
        }
        var translation = left[rowStart + TRANSLATION_COLUMN]
        for (channel in 0 until COLOR_CHANNELS) {
            translation += left[rowStart + channel] *
                right[channel * COLOR_COLUMNS + TRANSLATION_COLUMN]
        }
        result[rowStart + TRANSLATION_COLUMN] = translation
    }
    return result
}

private fun saturationMatrix(saturation: Float): FloatArray {
    val inverse = 1f - saturation
    val red = LUMA_RED * inverse
    val green = LUMA_GREEN * inverse
    val blue = LUMA_BLUE * inverse
    return floatArrayOf(
        red + saturation, green, blue, 0f, 0f,
        red, green + saturation, blue, 0f, 0f,
        red, green, blue + saturation, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun channelBalanceMatrix(warmth: Float, tint: Float): FloatArray {
    val redScale = 1f + warmth * WARM_CHANNEL_STRENGTH + tint * TINT_CHANNEL_STRENGTH
    val greenScale = 1f - tint * TINT_GREEN_STRENGTH
    val blueScale = 1f - warmth * WARM_CHANNEL_STRENGTH + tint * TINT_CHANNEL_STRENGTH
    return floatArrayOf(
        redScale, 0f, 0f, 0f, 0f,
        0f, greenScale, 0f, 0f, 0f,
        0f, 0f, blueScale, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun contrastMatrix(contrast: Float): FloatArray {
    val translation = (1f - contrast) * CHANNEL_MIDPOINT
    return floatArrayOf(
        contrast, 0f, 0f, 0f, translation,
        0f, contrast, 0f, 0f, translation,
        0f, 0f, contrast, 0f, translation,
        0f, 0f, 0f, 1f, 0f,
    )
}

private fun brightnessMatrix(brightness: Float): FloatArray {
    val translation = brightness * CHANNEL_RANGE
    return floatArrayOf(
        1f, 0f, 0f, 0f, translation,
        0f, 1f, 0f, 0f, translation,
        0f, 0f, 1f, 0f, translation,
        0f, 0f, 0f, 1f, 0f,
    )
}

private const val COLOR_ROWS = 4
private const val COLOR_CHANNELS = 4
private const val COLOR_COLUMNS = 5
private const val COLOR_MATRIX_SIZE = COLOR_ROWS * COLOR_COLUMNS
private const val TRANSLATION_COLUMN = 4
private const val CHANNEL_RANGE = 255f
private const val CHANNEL_MIDPOINT = 128f

private const val LUMA_RED = 0.213f
private const val LUMA_GREEN = 0.715f
private const val LUMA_BLUE = 0.072f
private const val WARM_CHANNEL_STRENGTH = 0.24f
private const val TINT_CHANNEL_STRENGTH = 0.08f
private const val TINT_GREEN_STRENGTH = 0.16f
