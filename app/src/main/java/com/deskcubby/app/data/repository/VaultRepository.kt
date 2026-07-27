package com.deskcubby.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.deskcubby.app.data.local.VaultItemDao
import com.deskcubby.app.data.local.VaultItemEntity
import com.deskcubby.app.data.vault.VaultCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Vault metadata lives in its own DataStore file, fully separate from app settings and the
 * JSON backup pipeline: neither this metadata nor the encrypted rows ever enter a backup.
 */
private val Context.vaultMetaDataStore by preferencesDataStore(name = "vault_meta")

private object VaultMetadataKeys {
    val metadataVersion = intPreferencesKey("metadata_version")
    val saltBase64 = stringPreferencesKey("salt_base64")
    val verifierCipher = stringPreferencesKey("verifier_cipher")
    val verifierIv = stringPreferencesKey("verifier_iv")
    val kdfIterations = intPreferencesKey("kdf_iterations")
    val activeGenerationId = stringPreferencesKey("active_generation_id")
    val migrationState = stringPreferencesKey("password_migration_state")
    val pendingSaltBase64 = stringPreferencesKey("pending_salt_base64")
    val pendingVerifierCipher = stringPreferencesKey("pending_verifier_cipher")
    val pendingVerifierIv = stringPreferencesKey("pending_verifier_iv")
    val pendingKdfIterations = intPreferencesKey("pending_kdf_iterations")
    val pendingGenerationId = stringPreferencesKey("pending_generation_id")
}

private class DataStoreVaultMetadataStore(
    context: Context,
) : VaultMetadataStore {
    private val dataStore = context.vaultMetaDataStore

    override suspend fun read(): VaultMetadataReadResult {
        val prefs = dataStore.data.first()
        return decodeVaultStoredMetadata(
            VaultStoredMetadataFields(
                metadataVersion = prefs[VaultMetadataKeys.metadataVersion],
                saltBase64 = prefs[VaultMetadataKeys.saltBase64],
                verifierCipher = prefs[VaultMetadataKeys.verifierCipher],
                verifierIv = prefs[VaultMetadataKeys.verifierIv],
                kdfIterations = prefs[VaultMetadataKeys.kdfIterations],
                activeGenerationId = prefs[VaultMetadataKeys.activeGenerationId],
                migrationState = prefs[VaultMetadataKeys.migrationState],
                pendingSaltBase64 = prefs[VaultMetadataKeys.pendingSaltBase64],
                pendingVerifierCipher = prefs[VaultMetadataKeys.pendingVerifierCipher],
                pendingVerifierIv = prefs[VaultMetadataKeys.pendingVerifierIv],
                pendingKdfIterations = prefs[VaultMetadataKeys.pendingKdfIterations],
                pendingGenerationId = prefs[VaultMetadataKeys.pendingGenerationId],
            ),
        )
    }

    override suspend fun writePrepared(
        active: VaultKeyMetadata,
        pending: VaultKeyMetadata,
    ) {
        require(pending.generationId != null)
        dataStore.edit { prefs ->
            prefs.writeActiveVaultMetadata(active)
            prefs[VaultMetadataKeys.migrationState] = VAULT_MIGRATION_STATE_PREPARED
            prefs[VaultMetadataKeys.pendingSaltBase64] = encodeVaultSalt(pending.salt)
            prefs[VaultMetadataKeys.pendingVerifierCipher] = pending.verifierCipher
            prefs[VaultMetadataKeys.pendingVerifierIv] = pending.verifierIv
            prefs[VaultMetadataKeys.pendingKdfIterations] = pending.iterations
            prefs[VaultMetadataKeys.pendingGenerationId] = pending.generationId
        }
    }

    override suspend fun writeStable(active: VaultKeyMetadata) {
        dataStore.edit { prefs ->
            prefs.writeActiveVaultMetadata(active)
            prefs.remove(VaultMetadataKeys.migrationState)
            prefs.remove(VaultMetadataKeys.pendingSaltBase64)
            prefs.remove(VaultMetadataKeys.pendingVerifierCipher)
            prefs.remove(VaultMetadataKeys.pendingVerifierIv)
            prefs.remove(VaultMetadataKeys.pendingKdfIterations)
            prefs.remove(VaultMetadataKeys.pendingGenerationId)
        }
    }

    private fun MutablePreferences.writeActiveVaultMetadata(metadata: VaultKeyMetadata) {
        this[VaultMetadataKeys.metadataVersion] = VAULT_METADATA_VERSION
        this[VaultMetadataKeys.saltBase64] = encodeVaultSalt(metadata.salt)
        this[VaultMetadataKeys.verifierCipher] = metadata.verifierCipher
        this[VaultMetadataKeys.verifierIv] = metadata.verifierIv
        this[VaultMetadataKeys.kdfIterations] = metadata.iterations
        metadata.generationId?.let {
            this[VaultMetadataKeys.activeGenerationId] = it
        } ?: remove(VaultMetadataKeys.activeGenerationId)
    }
}

enum class VaultLockState { NOT_SET, LOCKED, UNLOCKED }

internal const val MIN_VAULT_PASSWORD_CODE_POINTS = 1

/** New and replacement passwords have no maximum length; astral symbols count as one code point. */
internal fun isValidNewVaultPassword(password: String): Boolean =
    password.codePointCount(0, password.length) >= MIN_VAULT_PASSWORD_CODE_POINTS

/** Decrypted vault entry. Exists only in memory while the vault is unlocked. */
data class VaultItem(
    val id: Long,
    val content: String,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Decryption failures are represented only as a count. Ciphertext, ids and partial plaintext are
 * never exposed to the UI, and the underlying Room rows remain untouched.
 */
data class VaultContentState(
    val items: List<VaultItem> = emptyList(),
    val corruptedItemCount: Int = 0,
)

enum class VaultPasswordChangeResult {
    SUCCESS,
    WRONG_PASSWORD,
    INVALID_NEW_PASSWORD,
    CORRUPTED_ITEMS,
}

internal enum class VaultRekeyFaultPoint {
    AFTER_JOURNAL_WRITTEN,
    AFTER_ROWS_REPLACED,
    AFTER_METADATA_COMMITTED,
}

internal fun interface VaultRekeyFaultInjector {
    suspend fun onFaultPoint(point: VaultRekeyFaultPoint)
}

@Singleton
class VaultRepository internal constructor(
    private val vaultItemDao: VaultItemDao,
    private val metadataStore: VaultMetadataStore,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        vaultItemDao: VaultItemDao,
    ) : this(
        vaultItemDao = vaultItemDao,
        metadataStore = DataStoreVaultMetadataStore(context),
    )

    private data class ResolvedKey(
        val metadata: VaultKeyMetadata,
        val key: SecretKey,
    )

    /**
     * Serializes password changes with every row mutation. This prevents an add/update/delete
     * from being lost in replaceAll(), being resurrected by it, or writing with the old key
     * after the table has moved to the new key.
     */
    private val operationMutex = Mutex()

    /** No-op in production; instrumentation tests inject process-death failures at each boundary. */
    internal var rekeyFaultInjector = VaultRekeyFaultInjector { }

    /**
     * [lock] is synchronous and can race a suspended unlock/password change. The epoch prevents
     * a completion that started before an explicit lock request from resurrecting the session.
     */
    private val lockEpoch = AtomicLong(0L)

    /** Derived AES key. Memory-only; cleared by [lock], never persisted anywhere. */
    private val mutableSessionKey = MutableStateFlow<SecretKey?>(null)

    private val mutableLockState = MutableStateFlow(VaultLockState.LOCKED)
    val lockState: StateFlow<VaultLockState> = mutableLockState.asStateFlow()

    init {
        // Start pessimistically LOCKED, then downgrade only when absolutely no metadata exists.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val stored = metadataStore.read()
            if (!stored.hasStoredMetadata) {
                mutableLockState.compareAndSet(VaultLockState.LOCKED, VaultLockState.NOT_SET)
            }
        }
    }

    /** Decrypted user items while UNLOCKED; the internal generation marker is never exposed. */
    val contentState: Flow<VaultContentState> =
        combine(
            vaultItemDao.observeAll(),
            mutableLockState,
            mutableSessionKey,
        ) { entities, state, key ->
            if (state != VaultLockState.UNLOCKED || key == null) {
                VaultContentState()
            } else {
                val decryptedItems = ArrayList<VaultItem>(entities.size)
                var corruptedCount = 0
                entities
                    .asSequence()
                    .filterNot { it.id == VAULT_KEY_MARKER_ENTITY_ID }
                    .forEach { entity ->
                        val item = decryptItem(key, entity)
                        if (item == null) {
                            corruptedCount += 1
                        } else {
                            decryptedItems += item
                        }
                    }
                VaultContentState(
                    items = decryptedItems,
                    corruptedItemCount = corruptedCount,
                )
            }
        }.flowOn(Dispatchers.Default)

    /** Compatibility projection for callers that only need valid entries. */
    val items: Flow<List<VaultItem>> = contentState.map { state: VaultContentState ->
        state.items
    }

    /** First-time setup. Existing or damaged metadata is never overwritten. */
    suspend fun setupPassword(password: String): Boolean = operationMutex.withLock {
        withContext(Dispatchers.Default) {
            if (!isValidNewVaultPassword(password)) return@withContext false
            val operationEpoch = lockEpoch.get()
            val stored = metadataStore.read()
            if (stored.hasStoredMetadata) {
                if (mutableSessionKey.value == null) {
                    mutableLockState.value = VaultLockState.LOCKED
                }
                return@withContext false
            }

            val salt = VaultCrypto.generateSalt()
            val iterations = VaultCrypto.DEFAULT_KDF_ITERATIONS
            val key = VaultCrypto.deriveKey(password, salt, iterations)
            val verifier = VaultCrypto.encrypt(key, VERIFIER_PLAINTEXT)
            metadataStore.writeStable(
                VaultKeyMetadata(
                    salt = salt,
                    verifierCipher = verifier.cipherBase64,
                    verifierIv = verifier.ivBase64,
                    iterations = iterations,
                    generationId = null,
                ),
            )
            installSessionKey(key, operationEpoch)
            true
        }
    }

    /**
     * Verifies [password] against both descriptors when a prior password change was interrupted.
     * The Room generation marker selects the only descriptor that may unlock, after which the
     * metadata is best-effort finalized or rolled back to that descriptor.
     */
    suspend fun unlock(password: String): Boolean = operationMutex.withLock {
        withContext(Dispatchers.Default) {
            val operationEpoch = lockEpoch.get()
            val stored = metadataStore.read()
            val metadata = stored.metadata ?: run {
                if (!stored.hasStoredMetadata) {
                    mutableLockState.value = VaultLockState.NOT_SET
                }
                return@withContext false
            }
            val resolved = resolvePassword(metadata, password) ?: return@withContext false
            installSessionKey(resolved.key, operationEpoch)
            true
        }
    }

    /** Drops the in-memory key. No-op when no password has been set yet. */
    fun lock() {
        lockEpoch.incrementAndGet()
        if (mutableLockState.value == VaultLockState.UNLOCKED) {
            mutableLockState.value = VaultLockState.LOCKED
        }
        mutableSessionKey.value = null
    }

    /**
     * Recoverable password-change protocol:
     *
     * 1. Atomically persist active + pending key descriptions in DataStore.
     * 2. Atomically replace all Room ciphertext and its hidden generation marker.
     * 3. Atomically make the pending description canonical and clear migration state.
     *
     * A crash or write failure before step 2 leaves the old marker/table; one after step 2 leaves
     * the new marker/table. Since step 1 durably records both salts/verifiers first, the next
     * unlock can safely choose and retain whichever password actually owns the complete table.
     */
    suspend fun changePassword(
        oldPassword: String,
        newPassword: String,
    ): VaultPasswordChangeResult =
        operationMutex.withLock {
            withContext(Dispatchers.Default) {
                if (!isValidNewVaultPassword(newPassword)) {
                    return@withContext VaultPasswordChangeResult.INVALID_NEW_PASSWORD
                }
                val operationEpoch = lockEpoch.get()
                val stored = metadataStore.read()
                val currentMetadata = stored.metadata
                    ?: return@withContext VaultPasswordChangeResult.WRONG_PASSWORD
                val current = resolvePassword(currentMetadata, oldPassword)
                    ?: return@withContext VaultPasswordChangeResult.WRONG_PASSWORD

                // Validate every user row before publishing PREPARED. A damaged GCM value or
                // payload aborts the whole operation; it is never carried into a new generation.
                val plaintextRows = ArrayList<Pair<VaultItemEntity, String>>()
                vaultItemDao.getAll()
                    .asSequence()
                    .filterNot { it.id == VAULT_KEY_MARKER_ENTITY_ID }
                    .forEach { entity ->
                        val plaintext = VaultCrypto.decrypt(
                            current.key,
                            entity.cipherText,
                            entity.iv,
                        ) ?: return@withContext VaultPasswordChangeResult.CORRUPTED_ITEMS
                        if (decodeVaultItemPayload(plaintext) == null) {
                            return@withContext VaultPasswordChangeResult.CORRUPTED_ITEMS
                        }
                        plaintextRows += entity to plaintext
                    }

                val newSalt = VaultCrypto.generateSalt()
                val iterations = VaultCrypto.DEFAULT_KDF_ITERATIONS
                val newKey = VaultCrypto.deriveKey(newPassword, newSalt, iterations)
                val newVerifier = VaultCrypto.encrypt(newKey, VERIFIER_PLAINTEXT)
                val pending = VaultKeyMetadata(
                    salt = newSalt,
                    verifierCipher = newVerifier.cipherBase64,
                    verifierIv = newVerifier.ivBase64,
                    iterations = iterations,
                    generationId = UUID.randomUUID().toString(),
                )

                // Compute everything before publishing PREPARED, while row mutations are blocked.
                val reEncrypted = plaintextRows
                    .map { (entity, plaintext) ->
                        val encrypted = VaultCrypto.encrypt(newKey, plaintext)
                        entity.copy(
                            cipherText = encrypted.cipherBase64,
                            iv = encrypted.ivBase64,
                        )
                    }
                    .toMutableList()
                val marker = VaultCrypto.encrypt(
                    newKey,
                    vaultKeyMarkerPlaintext(checkNotNull(pending.generationId)),
                )
                reEncrypted += VaultItemEntity(
                    id = VAULT_KEY_MARKER_ENTITY_ID,
                    cipherText = marker.cipherBase64,
                    iv = marker.ivBase64,
                    createdAt = 0L,
                    updatedAt = 0L,
                )

                var replacementStarted = false
                try {
                    // No Room write is allowed until both password descriptions are durable.
                    metadataStore.writePrepared(active = current.metadata, pending = pending)
                    rekeyFaultInjector.onFaultPoint(
                        VaultRekeyFaultPoint.AFTER_JOURNAL_WRITTEN,
                    )

                    // replaceAll() is one Room transaction, including the generation marker.
                    replacementStarted = true
                    vaultItemDao.replaceAll(reEncrypted)
                    // Switch before the next suspension so the committed-row emission normally
                    // observes its matching key and does not flash a false corruption warning.
                    installSessionKey(newKey, operationEpoch)
                    rekeyFaultInjector.onFaultPoint(
                        VaultRekeyFaultPoint.AFTER_ROWS_REPLACED,
                    )

                    metadataStore.writeStable(pending)
                    rekeyFaultInjector.onFaultPoint(
                        VaultRekeyFaultPoint.AFTER_METADATA_COMMITTED,
                    )
                    installSessionKey(newKey, operationEpoch)
                    VaultPasswordChangeResult.SUCCESS
                } catch (error: Throwable) {
                    if (replacementStarted) {
                        // A cancelled Room call may have committed before propagating cancellation.
                        // Do not let an ambiguous in-memory key authorize any later mutation.
                        mutableSessionKey.value = null
                        mutableLockState.value = VaultLockState.LOCKED
                    } else {
                        installSessionKey(current.key, operationEpoch)
                    }
                    throw error
                }
            }
        }

    suspend fun addItem(content: String, note: String?): Boolean =
        operationMutex.withLock {
            withContext(Dispatchers.Default) {
                if (content.isBlank()) return@withContext false
                val key = unlockedKeyForMutation() ?: return@withContext false
                val now = System.currentTimeMillis()
                val encrypted = VaultCrypto.encrypt(key, encodeVaultItemPayload(content, note))
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

    suspend fun updateItem(id: Long, content: String, note: String?): Boolean =
        operationMutex.withLock {
            withContext(Dispatchers.Default) {
                if (id == VAULT_KEY_MARKER_ENTITY_ID || content.isBlank()) {
                    return@withContext false
                }
                val key = unlockedKeyForMutation() ?: return@withContext false
                val encrypted = VaultCrypto.encrypt(key, encodeVaultItemPayload(content, note))
                vaultItemDao.update(
                    id = id,
                    cipherText = encrypted.cipherBase64,
                    iv = encrypted.ivBase64,
                    updatedAt = System.currentTimeMillis(),
                ) > 0
            }
        }

    suspend fun deleteItem(id: Long): Boolean =
        operationMutex.withLock {
            withContext(Dispatchers.Default) {
                if (id == VAULT_KEY_MARKER_ENTITY_ID) return@withContext false
                unlockedKeyForMutation() ?: return@withContext false
                vaultItemDao.delete(id) > 0
            }
        }

    /**
     * Re-validates the memory key against metadata and the Room marker before every mutation.
     * This also protects an in-process session after an ambiguous suspended/failed Room call.
     */
    private suspend fun unlockedKeyForMutation(): SecretKey? {
        if (mutableLockState.value != VaultLockState.UNLOCKED) return null
        val key = mutableSessionKey.value ?: return null
        val metadata = metadataStore.read().metadata ?: return null
        val resolved = resolveExistingKey(metadata, key) ?: return null
        return resolved.key
    }

    private suspend fun resolvePassword(
        metadata: VaultMetadata,
        password: String,
    ): ResolvedKey? {
        val candidates = listOfNotNull(metadata.active, metadata.pending)
        val needsGenerationEvidence = metadata.pending != null
        candidates.forEach { candidate ->
            val key = VaultCrypto.deriveKey(password, candidate.salt, candidate.iterations)
            if (
                keyVerifies(candidate, key) &&
                (!needsGenerationEvidence || databaseUsesKey(candidate, key))
            ) {
                if (needsGenerationEvidence) bestEffortStabilize(candidate)
                return ResolvedKey(metadata = candidate, key = key)
            }
        }
        return null
    }

    private suspend fun resolveExistingKey(
        metadata: VaultMetadata,
        key: SecretKey,
    ): ResolvedKey? {
        val candidates = listOfNotNull(metadata.active, metadata.pending)
        val needsGenerationEvidence = metadata.pending != null
        val candidate = candidates.firstOrNull {
            keyVerifies(it, key) &&
                (!needsGenerationEvidence || databaseUsesKey(it, key))
        } ?: return null
        if (needsGenerationEvidence) bestEffortStabilize(candidate)
        return ResolvedKey(metadata = candidate, key = key)
    }

    private fun keyVerifies(metadata: VaultKeyMetadata, key: SecretKey): Boolean =
        VaultCrypto.decrypt(key, metadata.verifierCipher, metadata.verifierIv) ==
            VERIFIER_PLAINTEXT

    private suspend fun databaseUsesKey(metadata: VaultKeyMetadata, key: SecretKey): Boolean {
        val marker = vaultItemDao.getById(VAULT_KEY_MARKER_ENTITY_ID)
        val decryptedMarker = marker?.let {
            VaultCrypto.decrypt(key, it.cipherText, it.iv)
        }
        return vaultDatabaseMarkerMatches(
            generationId = metadata.generationId,
            markerPresent = marker != null,
            decryptedMarker = decryptedMarker,
        )
    }

    private suspend fun bestEffortStabilize(metadata: VaultKeyMetadata) {
        try {
            metadataStore.writeStable(metadata)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The dual descriptors and Room marker remain sufficient for a later retry.
        }
    }

    private fun installSessionKey(key: SecretKey, operationEpoch: Long) {
        if (operationEpoch != lockEpoch.get()) {
            mutableSessionKey.value = null
            mutableLockState.value = VaultLockState.LOCKED
            return
        }
        mutableSessionKey.value = key
        mutableLockState.value = VaultLockState.UNLOCKED
    }

    private fun decryptItem(key: SecretKey, entity: VaultItemEntity): VaultItem? {
        val plaintext = VaultCrypto.decrypt(key, entity.cipherText, entity.iv) ?: return null
        val payload = decodeVaultItemPayload(plaintext) ?: return null
        return VaultItem(
            id = entity.id,
            content = payload.content,
            note = payload.note,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }

    private companion object {
        const val VERIFIER_PLAINTEXT = "deskcubby-vault-verifier"
    }
}
