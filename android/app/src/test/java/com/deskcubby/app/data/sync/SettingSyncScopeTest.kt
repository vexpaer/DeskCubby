package com.deskcubby.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingSyncScopeTest {
    @Test
    fun globalSettingSyncs() {
        assertEquals(SettingSyncScope.GLOBAL, SettingSyncScopes.scope("userName"))
        assertEquals(SettingSyncScope.GLOBAL, SettingSyncScopes.scope("dailyEventTemplates"))
        assertTrue("reader.fontSizeSp" in SettingSyncScopes.globalKeys())
    }

    @Test
    fun deviceSettingDoesNotSync() {
        assertEquals(SettingSyncScope.DEVICE, SettingSyncScopes.scope("orientationPreference"))
        assertEquals(SettingSyncScope.DEVICE, SettingSyncScopes.scope("diaryTreeUri"))
        assertEquals(SettingSyncScope.DEVICE, SettingSyncScopes.scope("navItems"))
        assertFalse("orientationPreference" in SettingSyncScopes.globalKeys())
    }

    @Test
    fun secretSettingDoesNotSync() {
        assertEquals(SettingSyncScope.SECRET, SettingSyncScopes.scope("ai.apiKey"))
        assertEquals(SettingSyncScope.SECRET, SettingSyncScopes.scope("webDavPassword"))
        assertEquals(SettingSyncScope.SECRET, SettingSyncScopes.scope("s3AccessKey"))
        assertFalse(SettingSyncScopes.secretKeys().any { it in SettingSyncScopes.globalKeys() })
    }
}
