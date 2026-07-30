package com.deskcubby.app.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncServiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Device-local encrypted WebDAV credential storage and legacy S3 credential migration.
 *
 * New S3 credentials live in the app-private DataStore so they can be shown on the next edit.
 * Existing encrypted S3 values remain readable here until SettingsViewModel migrates them.
 */
@Singleton
class CloudSyncSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun hydrate(config: CloudSyncConfig): CloudSyncConfig {
        val secrets = read(config)
        return when (config.serviceType) {
            CloudSyncServiceType.WEBDAV -> config.copy(
                webDavPassword = secrets?.webDavPassword.orEmpty(),
                s3AccessKey = "",
                s3SecretKey = "",
                s3SessionToken = "",
            )

            CloudSyncServiceType.S3_COMPATIBLE -> config.copy(
                webDavPassword = "",
                s3AccessKey = config.s3AccessKey.ifBlank { secrets?.s3AccessKey.orEmpty() },
                s3SecretKey = config.s3SecretKey.ifBlank { secrets?.s3SecretKey.orEmpty() },
                s3SessionToken = config.s3SessionToken.ifBlank {
                    secrets?.s3SessionToken.orEmpty()
                },
            )
        }
    }

    /**
     * Persists non-empty values from [config]. Blank fields retain matching existing credentials
     * unless [clearExisting] is true.
     */
    @Synchronized
    fun save(config: CloudSyncConfig, clearExisting: Boolean = false): CloudSyncConfig {
        val existing = if (clearExisting) null else read(config)
        val secrets = CloudSyncSecrets(
            webDavPassword = config.webDavPassword.ifEmpty {
                existing?.webDavPassword.orEmpty()
            },
            s3AccessKey = config.s3AccessKey.ifEmpty { existing?.s3AccessKey.orEmpty() },
            s3SecretKey = config.s3SecretKey.ifEmpty { existing?.s3SecretKey.orEmpty() },
            s3SessionToken = config.s3SessionToken.ifEmpty {
                existing?.s3SessionToken.orEmpty()
            },
        )
        if (secrets.isEmpty()) {
            delete(config.id)
            return config.withoutSecrets()
        }

        val plaintext = JSONObject()
            .put("webDavPassword", secrets.webDavPassword)
            .put("s3AccessKey", secrets.s3AccessKey)
            .put("s3SecretKey", secrets.s3SecretKey)
            .put("s3SessionToken", secrets.s3SessionToken)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext)
            val suffix = keySuffix(config.id)
            check(
                preferences.edit()
                    .putString("$KEY_CIPHERTEXT.$suffix", encode(ciphertext))
                    .putString("$KEY_IV.$suffix", encode(cipher.iv))
                    .putString("$KEY_BINDING.$suffix", binding(config))
                    .commit(),
            ) { "无法保存云同步凭据。" }
        } finally {
            plaintext.fill(0)
        }
        return config.copy(
            webDavPassword = secrets.webDavPassword,
            s3AccessKey = secrets.s3AccessKey,
            s3SecretKey = secrets.s3SecretKey,
            s3SessionToken = secrets.s3SessionToken,
        )
    }

    @Synchronized
    fun delete(configId: String) {
        val suffix = keySuffix(configId)
        preferences.edit()
            .remove("$KEY_CIPHERTEXT.$suffix")
            .remove("$KEY_IV.$suffix")
            .remove("$KEY_BINDING.$suffix")
            .commit()
    }

    @Synchronized
    fun hasCredentials(config: CloudSyncConfig): Boolean = when (config.serviceType) {
        CloudSyncServiceType.WEBDAV -> read(config)?.webDavPassword?.isNotEmpty() == true
        CloudSyncServiceType.S3_COMPATIBLE ->
            config.s3AccessKey.isNotEmpty() && config.s3SecretKey.isNotEmpty() ||
                read(config)?.let { it.s3AccessKey.isNotEmpty() && it.s3SecretKey.isNotEmpty() } == true
    }

    private fun read(config: CloudSyncConfig): CloudSyncSecrets? {
        val suffix = keySuffix(config.id)
        val encodedCiphertext = preferences.getString("$KEY_CIPHERTEXT.$suffix", null)
            ?: return null
        val encodedIv = preferences.getString("$KEY_IV.$suffix", null) ?: return null
        if (preferences.getString("$KEY_BINDING.$suffix", null) != binding(config)) return null
        return runCatching {
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            require(iv.size in MIN_GCM_IV_BYTES..MAX_GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            try {
                val json = JSONObject(plaintext.toString(StandardCharsets.UTF_8))
                CloudSyncSecrets(
                    webDavPassword = json.optString("webDavPassword").take(MAX_SECRET_CHARS),
                    s3AccessKey = json.optString("s3AccessKey").take(MAX_SECRET_CHARS),
                    s3SecretKey = json.optString("s3SecretKey").take(MAX_SECRET_CHARS),
                    s3SessionToken = json.optString("s3SessionToken").take(MAX_SECRET_CHARS),
                )
            } finally {
                plaintext.fill(0)
            }
        }.getOrNull()
    }

    private fun CloudSyncConfig.withoutSecrets(): CloudSyncConfig = copy(
        webDavPassword = "",
        s3AccessKey = "",
        s3SecretKey = "",
        s3SessionToken = "",
    )

    private fun binding(config: CloudSyncConfig): String = buildString {
        append(config.serviceType.name)
        append('\n')
        append(normalizeEndpoint(config.endpointUrl))
        append('\n')
        if (config.serviceType == CloudSyncServiceType.WEBDAV) {
            append(config.webDavUsername.trim())
        } else {
            append(config.s3Bucket.trim())
            append('\n')
            append(config.s3Region.trim())
        }
    }

    private fun normalizeEndpoint(raw: String): String {
        val uri = runCatching { URI(raw.trim()).normalize() }.getOrNull() ?: return raw.trim()
        return buildString {
            append(uri.scheme?.lowercase(Locale.ROOT).orEmpty())
            append("://")
            append(uri.host?.lowercase(Locale.ROOT).orEmpty())
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty().ifEmpty { "/" })
        }
    }

    private fun getOrCreateKey(): SecretKey = existingKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }
        .generateKey()

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun keySuffix(configId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(configId.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(Locale.ROOT, it) }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private data class CloudSyncSecrets(
        val webDavPassword: String,
        val s3AccessKey: String,
        val s3SecretKey: String,
        val s3SessionToken: String,
    ) {
        fun isEmpty(): Boolean =
            webDavPassword.isEmpty() && s3AccessKey.isEmpty() &&
                s3SecretKey.isEmpty() && s3SessionToken.isEmpty()
    }

    private companion object {
        const val PREFERENCES_NAME = "deskcubby_cloud_sync_secrets"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_IV = "iv"
        const val KEY_BINDING = "binding"
        const val KEY_ALIAS = "deskcubby.cloud-sync.credentials.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val MIN_GCM_IV_BYTES = 12
        const val MAX_GCM_IV_BYTES = 32
        const val MAX_SECRET_CHARS = 8_192
    }
}
