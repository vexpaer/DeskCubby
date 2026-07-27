package com.deskcubby.app.ui.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultEntryUrlTest {
    @Test
    fun `accepts absolute http and https URLs`() {
        assertEquals(
            "https://example.com/path?q=one#result",
            safeVaultHttpUrlOrNull("https://example.com/path?q=one#result"),
        )
        assertEquals(
            "HTTP://example.com:8080/path",
            safeVaultHttpUrlOrNull("  HTTP://example.com:8080/path  "),
        )
        assertEquals(
            "https://[::1]/local",
            safeVaultHttpUrlOrNull("https://[::1]/local"),
        )
    }

    @Test
    fun `rejects non web relative and hostless URLs`() {
        listOf(
            "javascript:alert(1)",
            "file:///tmp/secret",
            "content://provider/item",
            "intent://example.com",
            "//example.com/path",
            "/relative/path",
            "https:example.com",
            "https:///missing-host",
        ).forEach { assertNull(it, safeVaultHttpUrlOrNull(it)) }
    }

    @Test
    fun `rejects misleading user info whitespace and invalid ports`() {
        listOf(
            "https://trusted.example@attacker.example/path",
            "https://example.com/path with space",
            "https://example.com/\nnext",
            "https://example.com:65536/path",
            "not a link",
            "",
        ).forEach { assertNull(it, safeVaultHttpUrlOrNull(it)) }
    }
}
