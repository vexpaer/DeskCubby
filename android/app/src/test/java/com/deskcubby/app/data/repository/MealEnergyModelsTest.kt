package com.deskcubby.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealEnergyModelsTest {
    @Test
    fun calculatedTotalIgnoresMissingValuesAndUsesLongWithoutOverflow() {
        assertNull(calculatedMealEnergyKj(listOf(null, null)))
        assertEquals(1_000, calculatedMealEnergyKj(listOf(200, null, 800)))
        assertEquals(
            MAX_MEAL_ENERGY_KJ,
            calculatedMealEnergyKj(listOf(Int.MAX_VALUE, Int.MAX_VALUE)),
        )
    }
}
