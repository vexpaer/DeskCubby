package com.deskcubby.app.data.repository

/** One food or drink in an AI-assisted meal estimate. */
data class MealFoodEnergy(
    val name: String,
    val amount: String? = null,
    val unit: String? = null,
    val energyKj: Int? = null,
)

/** The structured result stored for one meal photo. */
data class MealEnergyEstimate(
    val energyKj: Int,
    val foods: List<MealFoodEnergy> = emptyList(),
)

/** Date-scoped information that is intentionally only shown in the energy-details dialog. */
data class MealDayDetails(
    val totalEnergyKjOverride: Int? = null,
    val note: String = "",
)

internal fun calculatedMealEnergyKj(values: Iterable<Int?>): Int? = values
    .mapNotNull { it }
    .takeIf(List<Int>::isNotEmpty)
    ?.sumOf(Int::toLong)
    ?.coerceAtMost(MAX_MEAL_ENERGY_KJ.toLong())
    ?.toInt()

internal const val MAX_MEAL_FOODS = 64
internal const val MAX_MEAL_FOOD_NAME_CHARS = 200
internal const val MAX_MEAL_AMOUNT_CHARS = 80
internal const val MAX_MEAL_UNIT_CHARS = 40
internal const val MAX_MEAL_NOTE_CHARS = 4_000
internal const val MAX_MEAL_ENERGY_KJ = 1_000_000
