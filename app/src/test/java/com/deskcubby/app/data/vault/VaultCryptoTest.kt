package com.deskcubby.app.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultCryptoTest {
    // Low iteration count keeps the JVM test suite fast; production uses DEFAULT_KDF_ITERATIONS.
    private val testIterations = 1_000

    @Test
    fun `encrypt then decrypt round-trips plaintext`() {
        val salt = VaultCrypto.generateSalt()
        val key = VaultCrypto.deriveKey("correct horse battery", salt, testIterations)
        val plaintext = """{"title":"秘密标题","content":"多行内容\nsecond line ✓ émoji 🎯"}"""

        val encrypted = VaultCrypto.encrypt(key, plaintext)
        val decrypted = VaultCrypto.decrypt(key, encrypted.cipherBase64, encrypted.ivBase64)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `decrypt with wrong key returns null`() {
        val salt = VaultCrypto.generateSalt()
        val rightKey = VaultCrypto.deriveKey("right-password", salt, testIterations)
        val wrongKey = VaultCrypto.deriveKey("wrong-password", salt, testIterations)

        val encrypted = VaultCrypto.encrypt(rightKey, "vault secret")

        assertNull(VaultCrypto.decrypt(wrongKey, encrypted.cipherBase64, encrypted.ivBase64))
    }

    @Test
    fun `decrypt with tampered ciphertext or malformed base64 returns null`() {
        val salt = VaultCrypto.generateSalt()
        val key = VaultCrypto.deriveKey("password", salt, testIterations)
        val encrypted = VaultCrypto.encrypt(key, "vault secret")

        // Flip the leading character of the ciphertext (still valid Base64, invalid GCM tag path).
        val tampered = (if (encrypted.cipherBase64.first() == 'A') "B" else "A") +
            encrypted.cipherBase64.drop(1)
        assertNull(VaultCrypto.decrypt(key, tampered, encrypted.ivBase64))

        // Not Base64 at all: must return null instead of throwing.
        assertNull(VaultCrypto.decrypt(key, "@@not-base64@@", encrypted.ivBase64))
    }

    @Test
    fun `same plaintext encrypts with fresh iv and different ciphertext each time`() {
        val salt = VaultCrypto.generateSalt()
        val key = VaultCrypto.deriveKey("password", salt, testIterations)

        val first = VaultCrypto.encrypt(key, "same plaintext")
        val second = VaultCrypto.encrypt(key, "same plaintext")

        assertNotEquals(first.ivBase64, second.ivBase64)
        assertNotEquals(first.cipherBase64, second.cipherBase64)
        // Both still decrypt to the original.
        assertEquals("same plaintext", VaultCrypto.decrypt(key, first.cipherBase64, first.ivBase64))
        assertEquals("same plaintext", VaultCrypto.decrypt(key, second.cipherBase64, second.ivBase64))
    }

    @Test
    fun `key derivation is deterministic for the same password and salt`() {
        val salt = VaultCrypto.generateSalt()
        val a = VaultCrypto.deriveKey("password", salt, testIterations)
        val b = VaultCrypto.deriveKey("password", salt, testIterations)

        val encrypted = VaultCrypto.encrypt(a, "cross-key round trip")
        assertEquals("cross-key round trip", VaultCrypto.decrypt(b, encrypted.cipherBase64, encrypted.ivBase64))
    }

    @Test
    fun `different salts derive different keys`() {
        val keyA = VaultCrypto.deriveKey("password", VaultCrypto.generateSalt(), testIterations)
        val keyB = VaultCrypto.deriveKey("password", VaultCrypto.generateSalt(), testIterations)

        val encrypted = VaultCrypto.encrypt(keyA, "salted")
        assertNull(VaultCrypto.decrypt(keyB, encrypted.cipherBase64, encrypted.ivBase64))
    }

    @Test
    fun `production constants match the security spec`() {
        assertEquals(120_000, VaultCrypto.DEFAULT_KDF_ITERATIONS)
        assertEquals(16, VaultCrypto.SALT_BYTES)
        assertEquals(16, VaultCrypto.generateSalt().size)
    }
}
