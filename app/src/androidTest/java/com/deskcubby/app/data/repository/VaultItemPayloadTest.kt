package com.deskcubby.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.vault.VaultCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultItemPayloadTest {
    @Test
    fun versionedPayloadRoundTripsContentAndOptionalNote() {
        val payload = decodeVaultItemPayload(
            encodeVaultItemPayload(
                content = "多行正文\nhttps://example.com/?q=\"secret\" 🔐",
                note = "可选备注",
            ),
        )

        assertEquals(
            VaultItemPayload(
                content = "多行正文\nhttps://example.com/?q=\"secret\" 🔐",
                note = "可选备注",
            ),
            payload,
        )
        assertEquals(
            VaultItemPayload(content = "正文", note = null),
            decodeVaultItemPayload(encodeVaultItemPayload("正文", "   ")),
        )
    }

    @Test
    fun legacyTitleAndContentRemainReadableWithoutRewritingCiphertext() {
        val legacyPlaintext = """{"title":"旧标题","content":"旧正文"}"""
        val salt = VaultCrypto.generateSalt()
        val key = VaultCrypto.deriveKey("🔐", salt, 1_000)
        val encrypted = VaultCrypto.encrypt(key, legacyPlaintext)

        val decrypted = VaultCrypto.decrypt(key, encrypted.cipherBase64, encrypted.ivBase64)
        assertEquals(
            VaultItemPayload(content = "旧正文", note = "旧标题"),
            decrypted?.let(::decodeVaultItemPayload),
        )
    }

    @Test
    fun legacyTitleOnlyBecomesContentSoNoVisibleTextIsLost() {
        assertEquals(
            VaultItemPayload(content = "只有旧标题", note = null),
            decodeVaultItemPayload("""{"title":"只有旧标题","content":""}"""),
        )
        assertEquals(
            VaultItemPayload(content = "只有旧正文", note = null),
            decodeVaultItemPayload("""{"title":"","content":"只有旧正文"}"""),
        )
        assertEquals(
            VaultItemPayload(content = " ", note = "旧标题"),
            decodeVaultItemPayload("""{"title":"旧标题","content":" "}"""),
        )
    }

    @Test
    fun malformedOrUnsupportedPayloadIsRejected() {
        assertNull(decodeVaultItemPayload("not-json"))
        assertNull(decodeVaultItemPayload("""{"version":3,"content":"future"}"""))
        assertNull(decodeVaultItemPayload("""{"version":2,"content":42}"""))
        assertNull(decodeVaultItemPayload("""{"title":42,"content":"legacy"}"""))
    }
}
