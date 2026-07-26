package com.deskcubby.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deskcubby.app.data.local.VaultItemDao
import com.deskcubby.app.data.local.VaultItemEntity
import com.deskcubby.app.data.vault.VaultCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Vault metadata lives in its own DataStore file, fully separate from app settings and the
 * JSON backup pipeline: neither this metadata nor the encrypted rows ever enter a backup.
 */
private val Context.vaultMetaDataStore by preferencesDataStore(name = "vault_meta")

enum class VaultLockState { NOT_SET, LOCKED, UNLOCKED }

/** Decrypted vault entry. Exists only in memory while the vault is unlocked. */
data class VaultItem(
    val id: Long,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultItemDao: VaultItemDao,
) {
    private object Keys {
        val saltBase64 = stringPreferencesKey("salt_base64")
        val verifierCipher = stringPreferencesKey("verifier_cipher")
        val verifierIv = stringPreferencesKey("verifier_iv")
        val kdfIterations = intPreferencesKey("kdf_iterations")
    }

    private class VaultMeta(
        val salt: ByteArray,
        val verifierCipher: String,
        val verifierIv: String,
        val iterations: Int,
    )

    private val operationMutex = Mutex()

    /** Derived AES key. Memory-only; cleared by [lock], never persisted anywhere. */
    @Volatile
    private var sessionKey: SecretKey? = null

    private val mutableLockState = MutableStateFlow(VaultLockState.LOCKED)
    val lockState: StateFlow<VaultLockState> = mutableLockState.asStateFlow()

    init {
        // Start pessimistically LOCKED, then downgrade to NOT_SET once we know no password exists.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val configured = context.vaultMetaDataStore.data.first()[Keys.saltBase64] != null
            if (!configured) {
                mutableLockState.compareAndSet(VaultLockState.LOCKED, VaultLockState.NOT_SET)
            }
        }
    }

    /** Decrypted items while UNLOCKED; empty list otherwise. Undecryptable rows are skipped. */
    val items: Flow<List<VaultItem>> =
        combine(vaultItemDao.observeAll(), mutableLockState) { entities, state ->
            val key = sessionKey
            if (state != VaultLockState.UNLOCKED || key == null) {
                emptyList()
            } else {
                entities.mapNotNull { entity -> decryptItem(key, entity) }
            }
        }.flowOn(Dispatchers.Default)

    /** First-time setup. Only valid while NOT_SET; leaves the vault UNLOCKED on success. */
    suspend fun setupPassword(password: String) = operationMutex.withLock {
        withContext(Dispatchers.Default) {
            if (mutableLockState.value != VaultLockState.NOT_SET) return@withContext
            if (context.vaultMetaDataStore.data.first()[Keys.saltBase64] != null) {
                mutableLockState.value = VaultLockState.LOCKED
                return@withContext
            }
            val salt = VaultCrypto.generateSalt()
            val iterations = VaultCrypto.DEFAULT_KDF_ITERATIONS
            val key = VaultCrypto.deriveKey(password, salt, iterations)
            val verifier = VaultCrypto.encrypt(key, VERIFIER_PLAINTEXT)
            context.vaultMetaDataStore.edit { prefs ->
                prefs[Keys.saltBase64] = Base64.getEncoder().encodeToString(salt)
                prefs[Keys.verifierCipher] = verifier.cipherBase64
                prefs[Keys.verifierIv] = verifier.ivBase64
                prefs[Keys.kdfIterations] = iterations
            }
            sessionKey = key
            mutableLockState.value = VaultLockState.UNLOCKED
        }
    }

    /** Returns true and moves to UNLOCKED if [password] verifies against the stored verifier. */
    suspend fun unlock(password: String): Boolean = operationMutex.withLock {
        withContext(Dispatchers.Default) {
            val meta = readMeta() ?: run {
                mutableLockState.value = VaultLockState.NOT_SET
                return@withContext false
            }
            val key = VaultCrypto.deriveKey(password, meta.salt, meta.iterations)
            val verified =
                VaultCrypto.decrypt(key, meta.verifierCipher, meta.verifierIv) == VERIFIER_PLAINTEXT
            if (verified) {
                sessionKey = key
                mutableLockState.value = VaultLockState.UNLOCKED
            }
            verified
        }
    }

    /** Drops the in-memory key. No-op when no password has been set yet. */
    fun lock() {
        sessionKey = null
        mutableLockState.compareAndSet(VaultLockState.UNLOCKED, VaultLockState.LOCKED)
    }

    /**
     * Verifies [oldPassword], re-encrypts every row with a fresh salt/key, then atomically
     * replaces all rows and finally updates the metadata. All new ciphertexts are computed
     * up front; the metadata write happens only after [VaultItemDao.replaceAll] succeeds.
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): Boolean =
        operationMutex.withLock {
            withContext(Dispatchers.Default) {
                val meta = readMeta() ?: return@withContext false
                val oldKey = VaultCrypto.deriveKey(oldPassword, meta.salt, meta.iterations)
                val oldVerified =
                    VaultCrypto.decrypt(oldKey, meta.verifierCipher, meta.verifierIv) == VERIFIER_PLAINTEXT
                if (!oldVerified) return@withContext false

                val newSalt = VaultCrypto.generateSalt()
                val iterations = VaultCrypto.DEFAULT_KDF_ITERATIONS
                val newKey = VaultCrypto.deriveKey(newPassword, newSalt, iterations)

                val reEncrypted = vaultItemDao.getAll().map { entity ->
                    val plaintext = VaultCrypto.decrypt(oldKey, entity.cipherText, entity.iv)
                        ?: return@map entity // already unrecoverable; carry the row over untouched
                    val encrypted = VaultCrypto.encrypt(newKey, plaintext)
                    entity.copy(cipherText = encrypted.cipherBase64, iv = encrypted.ivBase64)
                }
                vaultItemDao.replaceAll(reEncrypted)

                val newVerifier = VaultCrypto.encrypt(newKey, VERIFIER_PLAINTEXT)
                context.vaultMetaDataStore.edit { prefs ->
                    prefs[Keys.saltBase64] = Base64.getEncoder().encodeToString(newSalt)
                    prefs[Keys.verifierCipher] = newVerifier.cipherBase64
                    prefs[Keys.verifierIv] = newVerifier.ivBase64
                    prefs[Keys.kdfIterations] = iterations
                }
                sessionKey = newKey
                mutableLockState.value = VaultLockState.UNLOCKED
                true
            }
        }

    suspend fun addItem(title: String, content: String): Boolean {
        val key = unlockedKeyOrNull() ?: return false
        return withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val encrypted = VaultCrypto.encrypt(key, encodeItemJson(title, content))
            vaultItemDao.insert(
                VaultItemEntity(
                    cipherText = encrypted.cipherBase64,
                    iv = encrypted.ivBase64,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            true
        }
    }

    suspend fun updateItem(id: Long, title: String, content: String): Boolean {
        val key = unlockedKeyOrNull() ?: return false
        return withContext(Dispatchers.Default) {
            val encrypted = VaultCrypto.encrypt(key, encodeItemJson(title, content))
            vaultItemDao.update(
                id = id,
                cipherText = encrypted.cipherBase64,
                iv = encrypted.ivBase64,
                updatedAt = System.currentTimeMillis(),
            ) > 0
        }
    }

    suspend fun deleteItem(id: Long): Boolean {
        unlockedKeyOrNull() ?: return false
        return withContext(Dispatchers.Default) { vaultItemDao.delete(id) > 0 }
    }

    private fun unlockedKeyOrNull(): SecretKey? =
        sessionKey.takeIf { mutableLockState.value == VaultLockState.UNLOCKED }

    private suspend fun readMeta(): VaultMeta? {
        val prefs = context.vaultMetaDataStore.data.first()
        val saltBase64 = prefs[Keys.saltBase64] ?: return null
        val verifierCipher = prefs[Keys.verifierCipher] ?: return null
        val verifierIv = prefs[Keys.verifierIv] ?: return null
        val salt = runCatching { Base64.getDecoder().decode(saltBase64) }.getOrNull() ?: return null
        return VaultMeta(
            salt = salt,
            verifierCipher = verifierCipher,
            verifierIv = verifierIv,
            iterations = prefs[Keys.kdfIterations] ?: VaultCrypto.DEFAULT_KDF_ITERATIONS,
        )
    }

    private fun decryptItem(key: SecretKey, entity: VaultItemEntity): VaultItem? {
        val plaintext = VaultCrypto.decrypt(key, entity.cipherText, entity.iv) ?: return null
        return try {
            val json = JSONObject(plaintext)
            VaultItem(
                id = entity.id,
                title = json.optString(JSON_TITLE),
                content = json.optString(JSON_CONTENT),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        } catch (_: Exception) {
            // Malformed plaintext JSON: skip the row. Never log or rethrow decrypted content.
            null
        }
    }

    private fun encodeItemJson(title: String, content: String): String =
        JSONObject().put(JSON_TITLE, title).put(JSON_CONTENT, content).toString()

    private companion object {
        const val VERIFIER_PLAINTEXT = "deskcubby-vault-verifier"
        const val JSON_TITLE = "title"
        const val JSON_CONTENT = "content"
    }
}
