package com.deskcubby.app.data.model

/**
 * Non-destructive display settings shared by every photo in the meal calendar.
 *
 * The values intentionally stay independent from Compose so they can be persisted by DataStore
 * and included in JSON backups without introducing a UI dependency into the data layer.
 */
data class MealPhotoFilterSettings(
    val enabled: Boolean = false,
    val brightness: Float = DEFAULT_BRIGHTNESS,
    val contrast: Float = DEFAULT_CONTRAST,
    val saturation: Float = DEFAULT_SATURATION,
    val warmth: Float = DEFAULT_WARMTH,
    val tint: Float = DEFAULT_TINT,
) {
    fun normalized(): MealPhotoFilterSettings = copy(
        brightness = brightness.finiteOr(DEFAULT_BRIGHTNESS).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS),
        contrast = contrast.finiteOr(DEFAULT_CONTRAST).coerceIn(MIN_CONTRAST, MAX_CONTRAST),
        saturation = saturation.finiteOr(DEFAULT_SATURATION).coerceIn(MIN_SATURATION, MAX_SATURATION),
        warmth = warmth.finiteOr(DEFAULT_WARMTH).coerceIn(MIN_WARMTH, MAX_WARMTH),
        tint = tint.finiteOr(DEFAULT_TINT).coerceIn(MIN_TINT, MAX_TINT),
    )

    fun hasVisibleAdjustment(): Boolean {
        val value = normalized()
        return value.brightness != DEFAULT_BRIGHTNESS ||
            value.contrast != DEFAULT_CONTRAST ||
            value.saturation != DEFAULT_SATURATION ||
            value.warmth != DEFAULT_WARMTH ||
            value.tint != DEFAULT_TINT
    }

    companion object {
        const val MIN_BRIGHTNESS = -1f
        const val MAX_BRIGHTNESS = 1f
        const val DEFAULT_BRIGHTNESS = 0f

        const val MIN_CONTRAST = 0f
        const val MAX_CONTRAST = 2f
        const val DEFAULT_CONTRAST = 1f

        const val MIN_SATURATION = 0f
        const val MAX_SATURATION = 2f
        const val DEFAULT_SATURATION = 1f

        const val MIN_WARMTH = -1f
        const val MAX_WARMTH = 1f
        const val DEFAULT_WARMTH = 0f

        const val MIN_TINT = -1f
        const val MAX_TINT = 1f
        const val DEFAULT_TINT = 0f
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
