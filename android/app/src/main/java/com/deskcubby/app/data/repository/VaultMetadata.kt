package com.deskcubby.app.data.repository

import com.deskcubby.app.data.vault.VaultCrypto
import java.util.Base64

/**
 * Persisted description of one password-derived vault key.
 *
 * Only the salt and an encrypted fixed verifier are persisted. The password and derived key
 * remain memory-only. [generationId] is absent for vaults created before the recoverable
 * password-change protocol was introduced.
 */
internal data class VaultKeyMetadata(
    val salt: ByteArray,
    val verifierCipher: String,
    val verifierIv: String,
    val iterations: Int,
    val generationId: String?,
)

/**
 * During a password change [active] describes the pre-change key and [pending] the new key.
 * Keeping both descriptions in one atomic DataStore record lets either side of the subsequent
 * Room transaction be identified after a crash without persisting either password.
 */
internal data class VaultMetadata(
    val active: VaultKeyMetadata,
    val pending: VaultKeyMetadata?,
)

/**
 * Raw, storage-independent representation used by the Preferences DataStore adapter. Keeping
 * parsing pure makes legacy compatibility and malformed migration-state handling JVM-testable.
 */
internal data class VaultStoredMetadataFields(
    val metadataVersion: Int? = null,
    val saltBase64: String? = null,
    val verifierCipher: String? = null,
    val verifierIv: String? = null,
    val kdfIterations: Int? = null,
    val activeGenerationId: String? = null,
    val migrationState: String? = null,
    val pendingSaltBase64: String? = null,
    val pendingVerifierCipher: String? = null,
    val pendingVerifierIv: String? = null,
    val pendingKdfIterations: Int? = null,
    val pendingGenerationId: String? = null,
)

internal data class VaultMetadataReadResult(
    val metadata: VaultMetadata?,
    /**
     * True when any vault metadata field exists, including a damaged/partial record. This keeps
     * first-time setup from overwriting a vault whose metadata cannot currently be decoded.
     */
    val hasStoredMetadata: Boolean,
)

internal interface VaultMetadataStore {
    suspend fun read(): VaultMetadataReadResult

    /** Atomically stores both key descriptions before any Room ciphertext is changed. */
    suspend fun writePrepared(active: VaultKeyMetadata, pending: VaultKeyMetadata)

    /** Atomically makes [active] canonical and removes every pending migration field. */
    suspend fun writeStable(active: VaultKeyMetadata)

    /** Removes all metadata only when an explicit v20 restore contains no configured vault. */
    suspend fun clear() {
        throw UnsupportedOperationException("Vault metadata clearing is not implemented.")
    }
}

internal const val VAULT_METADATA_VERSION = 2
internal const val VAULT_MIGRATION_STATE_PREPARED = "prepared_v1"

/**
 * A reserved, non-user Room row written in the same transaction as re-encrypted items.
 * Auto-generated user IDs are positive, so this value cannot collide with an app-created item.
 */
internal const val VAULT_KEY_MARKER_ENTITY_ID: Long = Long.MIN_VALUE

private const val VAULT_KEY_MARKER_PREFIX = "deskcubby-vault-key-generation:"
private const val MAX_SALT_BYTES = 1_024
private const val MAX_KDF_ITERATIONS = 10_000_000
private val GENERATION_ID_REGEX = Regex("[A-Za-z0-9-]{1,64}")

internal fun vaultKeyMarkerPlaintext(generationId: String): String =
    VAULT_KEY_MARKER_PREFIX + generationId

/**
 * The marker is deliberately required for generated keys and deliberately absent for legacy
 * keys. This unambiguously distinguishes "prepared, Room still old" from "Room already new".
 */
internal fun vaultDatabaseMarkerMatches(
    generationId: String?,
    markerPresent: Boolean,
    decryptedMarker: String?,
): Boolean = if (generationId == null) {
    !markerPresent
} else {
    markerPresent && decryptedMarker == vaultKeyMarkerPlaintext(generationId)
}

internal fun decodeVaultStoredMetadata(
    fields: VaultStoredMetadataFields,
): VaultMetadataReadResult {
    val hasStoredMetadata = listOf(
        fields.metadataVersion,
        fields.saltBase64,
        fields.verifierCipher,
        fields.verifierIv,
        fields.kdfIterations,
        fields.activeGenerationId,
        fields.migrationState,
        fields.pendingSaltBase64,
        fields.pendingVerifierCipher,
        fields.pendingVerifierIv,
        fields.pendingKdfIterations,
        fields.pendingGenerationId,
    ).any { it != null }

    val active = decodeVaultKeyMetadata(
        saltBase64 = fields.saltBase64,
        verifierCipher = fields.verifierCipher,
        verifierIv = fields.verifierIv,
        iterations = fields.kdfIterations ?: VaultCrypto.DEFAULT_KDF_ITERATIONS,
        generationId = fields.activeGenerationId,
        generationRequired = false,
    )
    if (active == null) {
        return VaultMetadataReadResult(metadata = null, hasStoredMetadata = hasStoredMetadata)
    }

    // Preferences DataStore edits are atomic. An incomplete/unknown pending record therefore
    // cannot be a successfully prepared migration; retain the legacy active description.
    val pending = if (fields.migrationState == VAULT_MIGRATION_STATE_PREPARED) {
        decodeVaultKeyMetadata(
            saltBase64 = fields.pendingSaltBase64,
            verifierCipher = fields.pendingVerifierCipher,
            verifierIv = fields.pendingVerifierIv,
            iterations = fields.pendingKdfIterations,
            generationId = fields.pendingGenerationId,
            generationRequired = true,
        )
    } else {
        null
    }
    return VaultMetadataReadResult(
        metadata = VaultMetadata(active = active, pending = pending),
        hasStoredMetadata = true,
    )
}

internal fun encodeVaultSalt(salt: ByteArray): String =
    Base64.getEncoder().encodeToString(salt)

private fun decodeVaultKeyMetadata(
    saltBase64: String?,
    verifierCipher: String?,
    verifierIv: String?,
    iterations: Int?,
    generationId: String?,
    generationRequired: Boolean,
): VaultKeyMetadata? {
    val salt = saltBase64
        ?.takeIf { it.length <= MAX_SALT_BYTES * 2 }
        ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        ?.takeIf { it.isNotEmpty() && it.size <= MAX_SALT_BYTES }
        ?: return null
    val cipher = verifierCipher?.takeIf(String::isNotBlank) ?: return null
    val iv = verifierIv?.takeIf(String::isNotBlank) ?: return null
    val safeIterations = iterations?.takeIf { it in 1..MAX_KDF_ITERATIONS } ?: return null
    val safeGenerationId = generationId?.takeIf(GENERATION_ID_REGEX::matches)
    if (generationRequired && safeGenerationId == null) return null
    if (generationId != null && safeGenerationId == null) return null
    return VaultKeyMetadata(
        salt = salt,
        verifierCipher = cipher,
        verifierIv = iv,
        iterations = safeIterations,
        generationId = safeGenerationId,
    )
}
