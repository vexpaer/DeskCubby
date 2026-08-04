package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealEnergyJsonTest {
    @Test
    fun textPayloadIncludesBoundedNoteWithoutChangingVisionStageData() {
        val payload = JSONObject(
            buildCalorieTextInput(
                visionJson = """{"foods":[{"name":"鸡蛋","amount":50,"unit":"g"}],"sceneNotes":"煎制"}""",
                note = "两人分享；\"午餐1\"与午餐2是同一份",
            ),
        )

        assertEquals("两人分享；\"午餐1\"与午餐2是同一份", payload.getString("userNote"))
        assertEquals("煎制", payload.getString("visionNotes"))
        val food = payload.getJSONArray("recognizedFoods").getJSONObject(0)
        assertEquals("鸡蛋", food.getString("name"))
        assertEquals("50", food.getString("amount"))
        assertEquals("g", food.getString("unit"))
    }

    @Test
    fun estimateParserKeepsPortionsAndAddsPerFoodEnergy() {
        val estimate = parseMealEnergyEstimate(
            visionJson = """{"foods":[{"name":"鸡蛋","amount":"50","unit":"g"},{"name":"玉米","amount":"300","unit":"g"}]}""",
            textResponse = """```json
                {"energyKj":1000,"foods":[{"name":"鸡蛋","energyKj":200},{"name":"玉米","energyKj":800}]}
                ```""".trimIndent(),
        )

        assertEquals(1000, estimate.energyKj)
        assertEquals(
            listOf(
                MealFoodEnergy("鸡蛋", "50", "g", 200),
                MealFoodEnergy("玉米", "300", "g", 800),
            ),
            estimate.foods,
        )
    }

    @Test
    fun legacyTotalOnlyTextResponseKeepsRecognizedFoodsForDetails() {
        val estimate = parseMealEnergyEstimate(
            visionJson = """{"foods":[{"name":"粥","amount":"1","unit":"碗"}]}""",
            textResponse = """{"energyKj":680}""",
        )

        assertEquals(680, estimate.energyKj)
        assertEquals("粥", estimate.foods.single().name)
        assertNull(estimate.foods.single().energyKj)
    }

    @Test
    fun sidecarUpgradePreservesUnknownFieldsLocationAndLowercaseKey() {
        val legacy = """{
          "version": 1,
          "futureRoot": {"keep": true},
          "entries": {
            "MEAL.JPG": {
              "energyKj": 800,
              "lat": 31.2,
              "lng": 121.5,
              "place": "上海",
              "futureEntry": 7
            }
          }
        }""".trimIndent()

        val withEstimate = MediaMetaJsonCodec.updateEntry(legacy, "Meal.JPG") { entry ->
            entry.copy(
                energyKj = 1_000,
                foods = listOf(MealFoodEnergy("鸡蛋", "50", "g", 200)),
            )
        }
        val updated = MediaMetaJsonCodec.updateMealDay(
            withEstimate,
            "2026-08-04",
            MealDayDetails(totalEnergyKjOverride = 900, note = "两人分享"),
        )
        val root = JSONObject(updated)
        val decoded = MediaMetaJsonCodec.decode(updated)

        assertTrue(root.getJSONObject("futureRoot").getBoolean("keep"))
        assertFalse(root.getJSONObject("entries").has("MEAL.JPG"))
        val rawEntry = root.getJSONObject("entries").getJSONObject("meal.jpg")
        assertEquals(7, rawEntry.getInt("futureEntry"))
        assertEquals(31.2, rawEntry.getDouble("lat"), 0.0)
        assertEquals(1_000, decoded.entries.getValue("meal.jpg").energyKj)
        assertEquals("鸡蛋", decoded.entries.getValue("meal.jpg").foods.single().name)
        assertEquals(
            MealDayDetails(totalEnergyKjOverride = 900, note = "两人分享"),
            decoded.mealDays.getValue("2026-08-04"),
        )
    }

    @Test
    fun sidecarRejectsMalformedOrOversizedOwnedDataBeforeMutation() {
        val tooManyFoods = JSONArray().apply {
            repeat(MAX_MEAL_FOODS + 1) { put(JSONObject().put("name", "food-$it")) }
        }
        val oversized = JSONObject()
            .put("version", 2)
            .put(
                "entries",
                JSONObject().put("meal.jpg", JSONObject().put("foods", tooManyFoods)),
            )
            .toString()

        assertTrue(
            runCatching {
                MediaMetaJsonCodec.updateEntry(oversized, "meal.jpg") { it.copy(energyKj = 1) }
            }.isFailure,
        )
        assertTrue(
            runCatching {
                MediaMetaJsonCodec.updateEntry(
                    """{"entries":[]}""",
                    "meal.jpg",
                ) { it.copy(energyKj = 1) }
            }.isFailure,
        )
    }

    @Test
    fun removingPhotoMetadataKeepsDateDetailsAndUnknownRootFields() {
        val source = """{
          "version": 2,
          "future": 9,
          "entries": {"meal.jpg":{"energyKj":400}},
          "mealDays": {"2026-08-04":{"totalEnergyKjOverride":350,"note":"少吃了一半"}}
        }""".trimIndent()

        val updated = MediaMetaJsonCodec.removeEntry(source, "MEAL.JPG")
            ?: error("entry should be removed")
        val root = JSONObject(updated)

        assertEquals(9, root.getInt("future"))
        assertEquals(0, root.getJSONObject("entries").length())
        assertEquals(
            "少吃了一半",
            root.getJSONObject("mealDays").getJSONObject("2026-08-04").getString("note"),
        )
    }
}
