package com.deskcubby.app.data.repository

import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Parsed view of the media sidecar. Unknown JSON fields are preserved by update operations. */
internal data class MediaMetaDocument(
    val entries: Map<String, MediaMetaEntry> = emptyMap(),
    val mealDays: Map<String, MealDayDetails> = emptyMap(),
)

/**
 * Backward-compatible codec for `dc-media.json`.
 *
 * Version 1 readers continue to see the unchanged `entries` object. Version 2 adds optional
 * per-photo food breakdowns and a separate `mealDays` object. All mutations begin with the raw
 * JSON and only touch owned fields, so metadata written by another platform or a future version
 * survives an Android update.
 */
internal object MediaMetaJsonCodec {
    private const val CURRENT_VERSION = 2

    fun decode(raw: String): MediaMetaDocument {
        val root = parseRoot(raw)
        val entriesJson = root.optJSONObject(KEY_ENTRIES)
        val entries = buildMap {
            entriesJson?.keys()?.forEach { key ->
                val item = entriesJson.optJSONObject(key) ?: return@forEach
                put(key.lowercase(Locale.ROOT), decodeEntry(item))
            }
        }
        val daysJson = root.optJSONObject(KEY_MEAL_DAYS)
        val mealDays = buildMap {
            daysJson?.keys()?.forEach { key ->
                if (runCatching { LocalDate.parse(key) }.isFailure) return@forEach
                val item = daysJson.optJSONObject(key) ?: return@forEach
                val details = decodeDay(item)
                if (details.totalEnergyKjOverride != null || details.note.isNotEmpty()) {
                    put(key, details)
                }
            }
        }
        return MediaMetaDocument(entries = entries, mealDays = mealDays)
    }

    fun updateEntry(
        raw: String,
        key: String,
        transform: (MediaMetaEntry) -> MediaMetaEntry,
    ): String {
        val normalizedKey = normalizeMediaKey(key)
        val root = parseRoot(raw)
        val entries = root.requiredObjectOrCreate(KEY_ENTRIES)
        val matchingKeys = entries.keys().asSequence()
            .filter { it.equals(normalizedKey, ignoreCase = true) }
            .toList()
        val item = matchingKeys.asSequence()
            .mapNotNull(entries::optJSONObject)
            .firstOrNull()
            ?: JSONObject()
        val updated = normalizeEntry(transform(decodeEntry(item)))
        matchingKeys.forEach(entries::remove)
        encodeEntryInto(item, updated)
        entries.put(normalizedKey, item)
        prepareForWrite(root)
        return root.toString(2)
    }

    fun removeEntry(raw: String, key: String): String? {
        val normalizedKey = normalizeMediaKey(key)
        val root = parseRoot(raw)
        val entries = root.optJSONObject(KEY_ENTRIES) ?: return null
        val matchingKeys = entries.keys().asSequence()
            .filter { it.equals(normalizedKey, ignoreCase = true) }
            .toList()
        if (matchingKeys.isEmpty()) return null
        matchingKeys.forEach(entries::remove)
        prepareForWrite(root)
        return root.toString(2)
    }

    fun updateMealDay(raw: String, dateIso: String, details: MealDayDetails): String {
        LocalDate.parse(dateIso)
        val root = parseRoot(raw)
        val days = root.requiredObjectOrCreate(KEY_MEAL_DAYS)
        val item = days.optJSONObject(dateIso) ?: JSONObject()
        val normalized = normalizeDay(details)
        setOrRemove(item, KEY_TOTAL_OVERRIDE, normalized.totalEnergyKjOverride)
        setOrRemove(item, KEY_NOTE, normalized.note.takeIf(String::isNotEmpty))
        if (item.length() == 0) {
            days.remove(dateIso)
        } else {
            days.put(dateIso, item)
        }
        if (days.length() == 0) root.remove(KEY_MEAL_DAYS)
        prepareForWrite(root)
        return root.toString(2)
    }

    private fun decodeEntry(item: JSONObject): MediaMetaEntry = MediaMetaEntry(
        energyKj = item.boundedInt(KEY_ENERGY, minimum = 0),
        latitude = item.finiteDouble(KEY_LATITUDE),
        longitude = item.finiteDouble(KEY_LONGITUDE),
        place = item.boundedString(KEY_PLACE, MAX_PLACE_CHARS),
        foods = decodeFoods(item.optJSONArray(KEY_FOODS)),
    )

    private fun decodeFoods(array: JSONArray?): List<MealFoodEnergy> = buildList {
        if (array == null) return@buildList
        require(array.length() <= MAX_MEAL_FOODS) { "媒体信息 JSON 的食物数量超出限制" }
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
                ?: error("媒体信息 JSON 的食物条目格式无效")
            val name = item.boundedString(KEY_NAME, MAX_MEAL_FOOD_NAME_CHARS)
                ?: error("媒体信息 JSON 的食物名称无效")
            add(
                MealFoodEnergy(
                    name = name,
                    amount = item.boundedScalarString(KEY_AMOUNT, MAX_MEAL_AMOUNT_CHARS),
                    unit = item.boundedString(KEY_UNIT, MAX_MEAL_UNIT_CHARS),
                    energyKj = item.boundedInt(KEY_ENERGY, minimum = 0),
                ),
            )
        }
    }

    private fun decodeDay(item: JSONObject): MealDayDetails = normalizeDay(
        MealDayDetails(
            totalEnergyKjOverride = item.boundedInt(KEY_TOTAL_OVERRIDE, minimum = 0),
            note = item.boundedString(KEY_NOTE, MAX_MEAL_NOTE_CHARS).orEmpty(),
        ),
    )

    private fun normalizeEntry(entry: MediaMetaEntry): MediaMetaEntry = entry.copy(
        energyKj = entry.energyKj?.takeIf { it in 0..MAX_MEAL_ENERGY_KJ },
        place = entry.place?.trim()?.take(MAX_PLACE_CHARS)?.takeIf(String::isNotEmpty),
        foods = entry.foods.asSequence()
            .take(MAX_MEAL_FOODS)
            .mapNotNull { food ->
                val name = food.name.trim().take(MAX_MEAL_FOOD_NAME_CHARS)
                name.takeIf(String::isNotEmpty)?.let {
                    food.copy(
                        name = it,
                        amount = food.amount?.trim()?.take(MAX_MEAL_AMOUNT_CHARS)
                            ?.takeIf(String::isNotEmpty),
                        unit = food.unit?.trim()?.take(MAX_MEAL_UNIT_CHARS)
                            ?.takeIf(String::isNotEmpty),
                        energyKj = food.energyKj?.takeIf { value ->
                            value in 0..MAX_MEAL_ENERGY_KJ
                        },
                    )
                }
            }
            .toList(),
    )

    private fun normalizeDay(details: MealDayDetails): MealDayDetails = details.copy(
        totalEnergyKjOverride = details.totalEnergyKjOverride?.takeIf {
            it in 0..MAX_MEAL_ENERGY_KJ
        },
        note = details.note.trim().take(MAX_MEAL_NOTE_CHARS),
    )

    private fun encodeEntryInto(item: JSONObject, entry: MediaMetaEntry) {
        setOrRemove(item, KEY_ENERGY, entry.energyKj)
        setOrRemove(item, KEY_LATITUDE, entry.latitude?.takeIf(Double::isFinite))
        setOrRemove(item, KEY_LONGITUDE, entry.longitude?.takeIf(Double::isFinite))
        setOrRemove(item, KEY_PLACE, entry.place)
        if (entry.foods.isEmpty()) {
            item.remove(KEY_FOODS)
        } else {
            item.put(
                KEY_FOODS,
                JSONArray().apply {
                    entry.foods.forEach { food ->
                        put(
                            JSONObject().apply {
                                put(KEY_NAME, food.name)
                                food.amount?.let { put(KEY_AMOUNT, it) }
                                food.unit?.let { put(KEY_UNIT, it) }
                                food.energyKj?.let { put(KEY_ENERGY, it) }
                            },
                        )
                    }
                },
            )
        }
    }

    private fun parseRoot(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject()
        return JSONObject(raw).also {
            require(it.opt(KEY_ENTRIES) == null || it.opt(KEY_ENTRIES) is JSONObject) {
                "媒体信息 JSON 的 entries 格式无效"
            }
            require(it.opt(KEY_MEAL_DAYS) == null || it.opt(KEY_MEAL_DAYS) is JSONObject) {
                "媒体信息 JSON 的 mealDays 格式无效"
            }
            val entries = it.optJSONObject(KEY_ENTRIES)
            require(entries == null || entries.length() <= MAX_MEDIA_META_ENTRIES) {
                "媒体信息 JSON 的图片条目数量超出限制"
            }
            entries?.keys()?.forEach { key ->
                require(key.length <= MAX_MEDIA_KEY_CHARS) { "媒体信息 JSON 的图片文件名过长" }
                val item = entries.optJSONObject(key)
                    ?: error("媒体信息 JSON 的图片条目格式无效")
                decodeEntry(item)
            }
            val days = it.optJSONObject(KEY_MEAL_DAYS)
            require(days == null || days.length() <= MAX_MEAL_DAY_ENTRIES) {
                "媒体信息 JSON 的日期条目数量超出限制"
            }
            days?.keys()?.forEach { key ->
                require(key.length <= MAX_DATE_KEY_CHARS) { "媒体信息 JSON 的日期键过长" }
                val item = days.optJSONObject(key)
                    ?: error("媒体信息 JSON 的日期条目格式无效")
                decodeDay(item)
            }
        }
    }

    private fun JSONObject.requiredObjectOrCreate(key: String): JSONObject {
        val existing = opt(key)
        require(existing == null || existing === JSONObject.NULL || existing is JSONObject) {
            "媒体信息 JSON 的 $key 格式无效"
        }
        return (existing as? JSONObject) ?: JSONObject().also { put(key, it) }
    }

    private fun prepareForWrite(root: JSONObject) {
        val previousVersion = root.optInt(KEY_VERSION, 1)
        root.put(KEY_VERSION, maxOf(previousVersion, CURRENT_VERSION))
        if (root.optJSONObject(KEY_ENTRIES) == null) root.put(KEY_ENTRIES, JSONObject())
    }

    private fun normalizeMediaKey(key: String): String {
        val normalized = key.trim().lowercase(Locale.ROOT)
        require(normalized.isNotEmpty()) { "无法确定图片文件名，热量未记录" }
        require('/' !in normalized && '\\' !in normalized && normalized != "." && normalized != "..") {
            "媒体文件名无效"
        }
        return normalized
    }

    private fun JSONObject.boundedInt(key: String, minimum: Int): Int? {
        val value = opt(key).takeUnless { it == null || it === JSONObject.NULL } ?: return null
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
        require(
            number != null && number.isFinite() &&
                number >= minimum && number <= MAX_MEAL_ENERGY_KJ,
        ) { "媒体信息 JSON 的 $key 数值无效" }
        return number.roundToInt()
    }

    private fun JSONObject.finiteDouble(key: String): Double? = optDouble(key, Double.NaN)
        .takeIf(Double::isFinite)

    private fun JSONObject.boundedString(key: String, maxChars: Int): String? {
        val value = opt(key).takeUnless { it == null || it === JSONObject.NULL } ?: return null
        require(value is String) { "媒体信息 JSON 的 $key 字段格式无效" }
        require(value.length <= maxChars) { "媒体信息 JSON 的 $key 字段过长" }
        return value.trim().takeIf(String::isNotEmpty)
    }

    private fun JSONObject.boundedScalarString(key: String, maxChars: Int): String? {
        val value = opt(key).takeUnless { it == null || it === JSONObject.NULL } ?: return null
        require(value !is JSONObject && value !is JSONArray) {
            "媒体信息 JSON 的 $key 字段格式无效"
        }
        val string = value.toString()
        require(string.length <= maxChars) { "媒体信息 JSON 的 $key 字段过长" }
        return string.trim().takeIf(String::isNotEmpty)
    }

    private fun setOrRemove(target: JSONObject, key: String, value: Any?) {
        if (value == null) target.remove(key) else target.put(key, value)
    }

    private const val KEY_VERSION = "version"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_MEAL_DAYS = "mealDays"
    private const val KEY_ENERGY = "energyKj"
    private const val KEY_LATITUDE = "lat"
    private const val KEY_LONGITUDE = "lng"
    private const val KEY_PLACE = "place"
    private const val KEY_FOODS = "foods"
    private const val KEY_NAME = "name"
    private const val KEY_AMOUNT = "amount"
    private const val KEY_UNIT = "unit"
    private const val KEY_TOTAL_OVERRIDE = "totalEnergyKjOverride"
    private const val KEY_NOTE = "note"
    private const val MAX_PLACE_CHARS = 1_000
    private const val MAX_MEDIA_META_ENTRIES = 20_000
    private const val MAX_MEAL_DAY_ENTRIES = 10_000
    private const val MAX_MEDIA_KEY_CHARS = 1_024
    private const val MAX_DATE_KEY_CHARS = 32
}
