package com.deskcubby.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

data class PoetryPresetCategorySummary(
    val id: String,
    val nameZh: String,
    val nameEn: String,
    val colorArgb: Int,
    val itemCount: Int,
)

internal data class PoetryPresetPoem(
    val content: String,
    val source: String,
)

internal data class PoetryPresetCategory(
    val summary: PoetryPresetCategorySummary,
    val poems: List<PoetryPresetPoem>,
)

/** Reads the bounded, offline textbook preset bundled with the APK. */
@Singleton
class PoetryPresetCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val categories: List<PoetryPresetCategory> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        decodeAsset()
    }

    fun summaries(): List<PoetryPresetCategorySummary> = categories.map { it.summary }

    internal fun category(id: String): PoetryPresetCategory? =
        categories.firstOrNull { it.summary.id == id }

    internal fun allPoems(): List<PoetryPresetPoem> = categories.flatMap { it.poems }

    private fun decodeAsset(): List<PoetryPresetCategory> {
        val bytes = context.assets.open(ASSET_NAME).use { input ->
            input.readBytes().also {
                require(it.size in 1..MAX_ASSET_BYTES) { "Poetry preset asset is invalid" }
            }
        }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("version") == ASSET_VERSION)
        val values = root.getJSONArray("categories")
        require(values.length() in 1..MAX_CATEGORIES)
        var totalItems = 0
        val result = buildList(values.length()) {
            for (categoryIndex in 0 until values.length()) {
                val category = values.getJSONObject(categoryIndex)
                val id = category.getString("id")
                val nameZh = category.getString("nameZh").trim()
                val nameEn = category.getString("nameEn").trim()
                require(PRESET_ID.matches(id))
                require(nameZh.isNotEmpty() && nameZh.length <= MAX_CATEGORY_NAME_CHARS)
                require(nameEn.isNotEmpty() && nameEn.length <= MAX_CATEGORY_NAME_CHARS)
                val items = category.getJSONArray("items")
                require(items.length() in 1..MAX_ITEMS_PER_CATEGORY)
                totalItems += items.length()
                require(totalItems <= MAX_TOTAL_ITEMS)
                val poems = buildList(items.length()) {
                    for (itemIndex in 0 until items.length()) {
                        val item = items.getJSONObject(itemIndex)
                        val title = item.getString("title").trim()
                        val author = item.optString("author").trim()
                        val content = item.getString("content").trim()
                        require(title.isNotEmpty() && title.length <= MAX_TITLE_CHARS)
                        require(author.length <= MAX_AUTHOR_CHARS)
                        require(content.isNotEmpty() && content.length <= PoetryBookRepository.MAX_CONTENT_CHARS)
                        val source = if (author.isEmpty()) "《$title》" else "$author《$title》"
                        require(source.length <= PoetryBookRepository.MAX_SOURCE_CHARS)
                        add(PoetryPresetPoem(content = content, source = source))
                    }
                }
                add(
                    PoetryPresetCategory(
                        summary = PoetryPresetCategorySummary(
                            id = id,
                            nameZh = nameZh,
                            nameEn = nameEn,
                            colorArgb = category.getInt("colorArgb") or 0xFF000000.toInt(),
                            itemCount = poems.size,
                        ),
                        poems = poems,
                    ),
                )
            }
        }
        require(result.map { it.summary.id }.distinct().size == result.size)
        return result
    }

    private companion object {
        const val ASSET_NAME = "poetry_presets.json"
        const val ASSET_VERSION = 1
        const val MAX_ASSET_BYTES = 1 * 1024 * 1024
        const val MAX_CATEGORIES = 32
        const val MAX_ITEMS_PER_CATEGORY = 128
        const val MAX_TOTAL_ITEMS = 512
        const val MAX_CATEGORY_NAME_CHARS = 100
        const val MAX_TITLE_CHARS = 200
        const val MAX_AUTHOR_CHARS = 100
        val PRESET_ID = Regex("[a-z0-9-]{1,64}")
    }
}
