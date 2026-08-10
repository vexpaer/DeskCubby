package com.deskcubby.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import com.deskcubby.app.data.model.normalizeDesktopWidgetHomeModuleId
import com.deskcubby.app.data.model.DesktopWidgetConfig
import com.deskcubby.app.data.model.DesktopWidgetContentType
import com.deskcubby.app.data.model.DesktopWidgetTextAlignment
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.data.model.MAX_DESKTOP_WIDGET_TEXT_SCALE_PERCENT
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_CELLS
import com.deskcubby.app.data.model.MIN_DESKTOP_WIDGET_TEXT_SCALE_PERCENT
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Device-local, non-backed-up binding and last-known-good snapshot for each launcher App Widget ID.
 *
 * The config ID links an instance to a reusable template while that template exists. The complete
 * snapshot is deliberately retained per [AppWidgetManager.EXTRA_APPWIDGET_ID], so deleting the
 * reusable template never blanks an already placed launcher instance.
 */
@Singleton
class DesktopWidgetInstanceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun snapshot(appWidgetId: Int): DesktopWidgetConfig? = preferences
        .getString(key(appWidgetId), null)
        ?.let(DesktopWidgetInstanceSnapshotCodec::decodeOrNull)

    /** Returns the ID from a current snapshot or a pre-snapshot legacy binding. */
    fun configId(appWidgetId: Int): String? {
        val raw = preferences.getString(key(appWidgetId), null)?.takeIf(String::isNotBlank)
            ?: return null
        return DesktopWidgetInstanceSnapshotCodec.decodeOrNull(raw)?.id
            ?: raw.takeIf { !it.trimStart().startsWith("{") }
    }

    @Synchronized
    fun bind(appWidgetId: Int, config: DesktopWidgetConfig) {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        require(config.id.isNotBlank())
        check(
            preferences.edit()
                .putString(key(appWidgetId), DesktopWidgetInstanceSnapshotCodec.encode(config))
                .commit(),
        )
    }

    /**
     * Advances the fallback snapshot of every instance bound to [config]'s template.
     *
     * Each SharedPreferences key remains scoped to its own App Widget ID; unrelated instances and
     * instances bound to another template are untouched. Legacy ID-only values are upgraded here.
     */
    @Synchronized
    fun refreshTemplateSnapshot(config: DesktopWidgetConfig): IntArray {
        require(config.id.isNotBlank())
        val matchingKeys = preferences.all.asSequence()
            .mapNotNull { (key, value) ->
                val appWidgetId = key.removePrefix(KEY_PREFIX)
                    .takeIf { key.startsWith(KEY_PREFIX) }
                    ?.toIntOrNull()
                    ?: return@mapNotNull null
                val raw = value as? String ?: return@mapNotNull null
                val boundId = DesktopWidgetInstanceSnapshotCodec.decodeOrNull(raw)?.id
                    ?: raw.takeIf { !it.trimStart().startsWith("{") }
                if (boundId == config.id) key to appWidgetId else null
            }
            .toList()
        if (matchingKeys.isEmpty()) return IntArray(0)

        val encoded = DesktopWidgetInstanceSnapshotCodec.encode(config)
        check(preferences.edit().apply {
            matchingKeys.forEach { (key, _) -> putString(key, encoded) }
        }.commit())
        return matchingKeys.map { (_, appWidgetId) -> appWidgetId }.sorted().toIntArray()
    }

    @Synchronized
    fun remove(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        check(preferences.edit().apply {
            appWidgetIds.forEach { remove(key(it)) }
        }.commit())
    }

    private fun key(appWidgetId: Int): String = "$KEY_PREFIX$appWidgetId"

    private companion object {
        const val FILE_NAME = "desktop_widget_instances"
        const val KEY_PREFIX = "widget_"
    }
}

internal object DesktopWidgetInstanceSnapshotCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(config: DesktopWidgetConfig): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("id", config.id)
        .put("name", config.name)
        .put("widthCells", config.widthCells)
        .put("heightCells", config.heightCells)
        .put("backgroundColorArgb", config.backgroundColorArgb)
        .put("textColorArgb", config.textColorArgb)
        .put("backgroundImageUri", config.backgroundImageUri ?: JSONObject.NULL)
        .put("showName", config.showName)
        .put("backgroundOpacityPercent", config.backgroundOpacityPercent)
        .put("showIcon", config.showIcon)
        .put("textAlignment", config.textAlignment.name)
        .put("textScalePercent", config.textScalePercent)
        .put("contentType", config.contentType.name)
        .put("homeModuleId", config.homeModuleId)
        .put("appPackageName", config.appPackageName ?: JSONObject.NULL)
        .put("appLabel", config.appLabel ?: JSONObject.NULL)
        .toString()

    fun decodeOrNull(raw: String): DesktopWidgetConfig? = runCatching {
        val json = JSONObject(raw)
        require(json.getInt("schemaVersion") == SCHEMA_VERSION)
        val id = json.getString("id").trim()
        val name = json.getString("name").trim()
        require(id.isNotBlank() && name.isNotBlank())
        val contentType = enumValueOf<DesktopWidgetContentType>(json.getString("contentType"))
        val homeModuleId = normalizeDesktopWidgetHomeModuleId(json.getString("homeModuleId"))
        DesktopWidgetConfig(
            id = id,
            name = name,
            widthCells = json.getInt("widthCells").coerceIn(
                MIN_DESKTOP_WIDGET_CELLS,
                MAX_DESKTOP_WIDGET_CELLS,
            ),
            heightCells = json.getInt("heightCells").coerceIn(
                MIN_DESKTOP_WIDGET_CELLS,
                MAX_DESKTOP_WIDGET_CELLS,
            ),
            backgroundColorArgb = json.getInt("backgroundColorArgb") or 0xFF000000.toInt(),
            textColorArgb = json.getInt("textColorArgb") or 0xFF000000.toInt(),
            backgroundImageUri = json.optString("backgroundImageUri")
                .takeIf { !json.isNull("backgroundImageUri") && it.startsWith("content://") },
            showName = json.optBoolean("showName", true),
            backgroundOpacityPercent = json.optInt("backgroundOpacityPercent", 100).coerceIn(
                MIN_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT,
                MAX_DESKTOP_WIDGET_BACKGROUND_OPACITY_PERCENT,
            ),
            showIcon = json.optBoolean("showIcon", true),
            textAlignment = runCatching {
                enumValueOf<DesktopWidgetTextAlignment>(json.optString("textAlignment"))
            }.getOrDefault(DesktopWidgetTextAlignment.START),
            textScalePercent = json.optInt("textScalePercent", 100).coerceIn(
                MIN_DESKTOP_WIDGET_TEXT_SCALE_PERCENT,
                MAX_DESKTOP_WIDGET_TEXT_SCALE_PERCENT,
            ),
            contentType = contentType,
            homeModuleId = homeModuleId,
            appPackageName = json.optString("appPackageName")
                .takeIf { !json.isNull("appPackageName") && it.isNotBlank() },
            appLabel = json.optString("appLabel")
                .takeIf { !json.isNull("appLabel") && it.isNotBlank() },
        )
    }.getOrNull()
}
