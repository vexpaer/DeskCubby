package com.deskcubby.app.data.repository

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time reader for API keys written by versions that used AndroidKeyStore.
 *
 * New keys are never written here. After known keys have been copied into the plain-text
 * AI configuration stored by DataStore, [discardLegacyStore] removes the obsolete encrypted
 * preferences and KeyStore entry.
 */
@Singleton
class LegacyAiKeyMigrationStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun containsApiKey(configId: String): Boolean =
        preferences.contains(key(KEY_CIPHERTEXT, configId)) ||
            preferences.contains(key(KEY_IV, configId)) ||
            preferences.contains(key(KEY_ENDPOINT_BINDING, configId))

    @Synchronized
    fun readApiKey(configId: String, endpoint: URL): String? {
        val encodedCiphertext = preferences.getString(key(KEY_CIPHERTEXT, configId), null)
            ?: return null
        val encodedIv = preferences.getString(key(KEY_IV, configId), null) ?: return null
        val storedBinding = preferences.getString(key(KEY_ENDPOINT_BINDING, configId), null)
            ?: return null
        if (storedBinding != endpointBinding(endpoint)) return null

        return runCatching {
            val secretKey = existingKey() ?: return null
            val ciphertext = Base64.decode(encodedCiphertext, Base64.NO_WRAP)
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            require(iv.size in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES) { "Invalid GCM IV" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                plaintext.toString(StandardCharsets.UTF_8).takeIf(String::isNotEmpty)
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    /** Removes every value used by the obsolete encrypted-key implementation. */
    @Synchronized
    fun discardLegacyStore(): Boolean {
        if (!preferences.edit().clear().commit()) return false
        return runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.let { keyStore ->
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            }
            true
        }.getOrDefault(false)
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun key(base: String, configId: String): String =
        if (configId == LEGACY_CONFIG_ID) base else "$base.${configId.hashCode().toUInt().toString(16)}"

    private fun endpointBinding(url: URL): String = buildString {
        append(url.protocol.lowercase(Locale.ROOT))
        append("://")
        append(url.host.lowercase(Locale.ROOT))
        if (url.port != -1 && url.port != url.defaultPort) append(':').append(url.port)
        append(url.path.ifEmpty { "/" })
        url.query?.let { append('?').append(it) }
    }

    private companion object {
        const val PREFERENCES_NAME = "deskcubby_ai_secrets"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_IV = "api_key_iv"
        const val KEY_ENDPOINT_BINDING = "api_key_endpoint_binding"
        const val KEY_ALIAS = "deskcubby.ai.api-key.v1"
        const val LEGACY_CONFIG_ID = "legacy-text"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val MIN_GCM_IV_BYTES = 12
        const val MAX_GCM_IV_BYTES = 32
    }
}
