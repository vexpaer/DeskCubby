package com.deskcubby.app.data.repository

import com.deskcubby.app.data.vault.VaultCrypto
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultMetadataTest {
    private val salt = ByteArray(VaultCrypto.SALT_BYTES) { it.toByte() }
    private val saltBase64 = Base64.getEncoder().encodeToString(salt)

    @Test
    fun `legacy metadata decodes with production iteration default and no migration`() {
        val decoded = decodeVaultStoredMetadata(
            VaultStoredMetadataFields(
                saltBase64 = saltBase64,
                verifierCipher = "legacy-cipher",
                verifierIv = "legacy-iv",
            ),
        )

        assertTrue(decoded.hasStoredMetadata)
        assertNotNull(decoded.metadata)
        val metadata = checkNotNull(decoded.metadata)
        assertArrayEquals(salt, metadata.active.salt)
        assertEquals(VaultCrypto.DEFAULT_KDF_ITERATIONS, metadata.active.iterations)
        assertNull(metadata.active.generationId)
        assertNull(metadata.pending)
    }

    @Test
    fun `complete prepared metadata retains both key descriptions`() {
        val decoded = decodeVaultStoredMetadata(
            VaultStoredMetadataFields(
                metadataVersion = VAULT_METADATA_VERSION,
                saltBase64 = saltBase64,
                verifierCipher = "old-cipher",
                verifierIv = "old-iv",
                kdfIterations = 120_000,
                activeGenerationId = "old-generation",
                migrationState = VAULT_MIGRATION_STATE_PREPARED,
                pendingSaltBase64 = encodeVaultSalt(salt.reversedArray()),
                pendingVerifierCipher = "new-cipher",
                pendingVerifierIv = "new-iv",
                pendingKdfIterations = 120_000,
                pendingGenerationId = "new-generation",
            ),
        )

        assertNotNull(decoded.metadata)
        val metadata = checkNotNull(decoded.metadata)
        assertEquals("old-generation", metadata.active.generationId)
        assertEquals("new-generation", metadata.pending?.generationId)
        assertArrayEquals(salt.reversedArray(), checkNotNull(metadata.pending).salt)
    }

    @Test
    fun `unknown or incomplete migration state cannot replace legacy active metadata`() {
        val unknown = decodeVaultStoredMetadata(
            VaultStoredMetadataFields(
                saltBase64 = saltBase64,
                verifierCipher = "old-cipher",
                verifierIv = "old-iv",
                migrationState = "future-state",
                pendingSaltBase64 = encodeVaultSalt(salt.reversedArray()),
                pendingVerifierCipher = "new-cipher",
                pendingVerifierIv = "new-iv",
                pendingKdfIterations = 120_000,
                pendingGenerationId = "new-generation",
            ),
        )
        val incomplete = decodeVaultStoredMetadata(
            VaultStoredMetadataFields(
                saltBase64 = saltBase64,
                verifierCipher = "old-cipher",
                verifierIv = "old-iv",
                migrationState = VAULT_MIGRATION_STATE_PREPARED,
                pendingSaltBase64 = encodeVaultSalt(salt.reversedArray()),
                pendingVerifierCipher = "new-cipher",
                // pending IV is deliberately absent.
                pendingKdfIterations = 120_000,
                pendingGenerationId = "new-generation",
            ),
        )

        assertNull(unknown.metadata?.pending)
        assertNull(incomplete.metadata?.pending)
        assertEquals("old-cipher", unknown.metadata?.active?.verifierCipher)
        assertEquals("old-cipher", incomplete.metadata?.active?.verifierCipher)
    }

    @Test
    fun `partial active metadata blocks setup instead of appearing unconfigured`() {
        val decoded = decodeVaultStoredMetadata(
            VaultStoredMetadataFields(saltBase64 = saltBase64),
        )

        assertTrue(decoded.hasStoredMetadata)
        assertNull(decoded.metadata)
        assertFalse(decodeVaultStoredMetadata(VaultStoredMetadataFields()).hasStoredMetadata)
    }

    @Test
    fun `generation marker distinguishes every recoverable interruption phase`() {
        val newGeneration = "new-generation"
        val markerPlaintext = vaultKeyMarkerPlaintext(newGeneration)

        // PREPARED persisted, Room transaction not committed: legacy/old key owns no marker.
        assertTrue(
            vaultDatabaseMarkerMatches(
                generationId = null,
                markerPresent = false,
                decryptedMarker = null,
            ),
        )
        assertFalse(
            vaultDatabaseMarkerMatches(
                generationId = newGeneration,
                markerPresent = false,
                decryptedMarker = null,
            ),
        )

        // Room transaction committed, final metadata edit not committed: only new key decrypts it.
        assertTrue(
            vaultDatabaseMarkerMatches(
                generationId = newGeneration,
                markerPresent = true,
                decryptedMarker = markerPlaintext,
            ),
        )
        assertFalse(
            vaultDatabaseMarkerMatches(
                generationId = null,
                markerPresent = true,
                decryptedMarker = null,
            ),
        )
        assertFalse(
            vaultDatabaseMarkerMatches(
                generationId = newGeneration,
                markerPresent = true,
                decryptedMarker = "wrong-generation",
            ),
        )
    }
}
