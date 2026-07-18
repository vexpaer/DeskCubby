package com.deskcubby.app.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test
    fun normalizeUrlAddsHttpsWhenSchemeMissing() {
        assertEquals("https://example.com/path", SettingsRepository.normalizeUrl(" example.com/path "))
    }

    @Test
    fun normalizeUrlPreservesExplicitScheme() {
        assertEquals("http://192.168.1.2", SettingsRepository.normalizeUrl("http://192.168.1.2"))
    }
}
