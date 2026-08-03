package com.deskcubby.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local binding between launcher widget IDs and reusable card designs. */
@Singleton
class DesktopWidgetInstanceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun configId(appWidgetId: Int): String? = preferences
        .getString(key(appWidgetId), null)
        ?.takeIf(String::isNotBlank)

    fun bind(appWidgetId: Int, configId: String) {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        require(configId.isNotBlank())
        preferences.edit().putString(key(appWidgetId), configId).apply()
    }

    fun remove(appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        preferences.edit().apply {
            appWidgetIds.forEach { remove(key(it)) }
        }.apply()
    }

    private fun key(appWidgetId: Int): String = "widget_$appWidgetId"

    private companion object {
        const val FILE_NAME = "desktop_widget_instances"
    }
}
