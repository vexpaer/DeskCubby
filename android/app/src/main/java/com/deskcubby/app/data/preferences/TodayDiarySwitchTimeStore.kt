package com.deskcubby.app.data.preferences

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.todayDiarySwitchDataStore by preferencesDataStore(
    name = "deskcubby_today_diary_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Device-local setting used only to decide which file the “进入今日日记” action opens. */
@Singleton
class TodayDiarySwitchTimeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("today_diary_switch_time")

    val switchTime = context.todayDiarySwitchDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> normalize(preferences[key]) }

    suspend fun current(): String = switchTime.first()

    suspend fun set(value: String) {
        val normalized = normalize(value)
        context.todayDiarySwitchDataStore.edit { preferences ->
            preferences[key] = normalized
        }
    }

    companion object {
        const val DEFAULT_SWITCH_TIME = "05:00"

        fun normalize(value: String?): String {
            val text = value?.trim().orEmpty()
            val match = TIME_PATTERN.matchEntire(text) ?: return DEFAULT_SWITCH_TIME
            val hour = match.groupValues[1].toIntOrNull() ?: return DEFAULT_SWITCH_TIME
            val minute = match.groupValues[2].toIntOrNull() ?: return DEFAULT_SWITCH_TIME
            if (hour !in 0..23 || minute !in 0..59) return DEFAULT_SWITCH_TIME
            return "%02d:%02d".format(hour, minute)
        }

        private val TIME_PATTERN = Regex("""^(\d{1,2}):(\d{2})$""")
    }
}
