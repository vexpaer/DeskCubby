package com.deskcubby.app.plugin.adapter

import android.content.Context
import com.deskcubby.plugin.api.core.PluginApiException
import com.deskcubby.plugin.api.core.api.StorageAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class PluginStorageApiFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun create(pluginId: String): StorageAPI = SharedPreferencesPluginStorage(
        context = context,
        preferenceName = "plugin_api_${pluginId.storageDigest()}",
    )
}

private class SharedPreferencesPluginStorage(
    context: Context,
    preferenceName: String,
) : StorageAPI {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        preferences.getString(validateKey(key), null)
    }

    override suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        val validatedKey = validateKey(key)
        if (value.length > MAX_VALUE_CHARS) {
            throw PluginApiException(
                code = "PLUGIN_STORAGE_VALUE_TOO_LARGE",
                message = "Plugin storage values are limited to $MAX_VALUE_CHARS characters.",
            )
        }
        check(preferences.edit().putString(validatedKey, value).commit()) {
            "Plugin storage write failed."
        }
    }

    override suspend fun remove(key: String): Boolean = withContext(Dispatchers.IO) {
        val validatedKey = validateKey(key)
        if (!preferences.contains(validatedKey)) return@withContext false
        check(preferences.edit().remove(validatedKey).commit()) {
            "Plugin storage removal failed."
        }
        true
    }

    override suspend fun keys(): Set<String> = withContext(Dispatchers.IO) {
        preferences.all.keys.toSortedSet()
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(preferences.edit().clear().commit()) { "Plugin storage clear failed." }
    }

    private fun validateKey(key: String): String {
        if (key.length !in 1..MAX_KEY_CHARS || key != key.trim() || !KEY_PATTERN.matches(key)) {
            throw PluginApiException(
                code = "INVALID_PLUGIN_STORAGE_KEY",
                message = "Plugin storage keys must be stable identifiers of at most $MAX_KEY_CHARS characters.",
            )
        }
        return key
    }

    private companion object {
        const val MAX_KEY_CHARS = 128
        const val MAX_VALUE_CHARS = 1_048_576
        val KEY_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")
    }
}

private fun String.storageDigest(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(StandardCharsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(Locale.ROOT, byte) }
    .take(32)
