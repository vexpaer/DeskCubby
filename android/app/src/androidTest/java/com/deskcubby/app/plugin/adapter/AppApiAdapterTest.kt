package com.deskcubby.app.plugin.adapter

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.model.AppLanguage
import com.deskcubby.app.data.preferences.SettingsRepository
import com.deskcubby.plugin.api.core.PluginApiException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppApiAdapterTest {
    @Test
    fun settingMutationUndoRestoresExactPreviousValue() = runBlocking {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext())
        val initialLanguage = settings.settings.first().appLanguage
        try {
            settings.setAppLanguage(AppLanguage.CHINESE)
            val api = AppApiAdapter(settings)

            val plan = api.prepareSettingMutation("app_language", "english")
            val applied = api.commitSettingMutation(plan.planToken)
            assertEquals(AppLanguage.ENGLISH, settings.settings.first().appLanguage)

            api.undoSettingMutation(requireNotNull(applied.undoToken))

            assertEquals(AppLanguage.CHINESE, settings.settings.first().appLanguage)
        } finally {
            settings.setAppLanguage(initialLanguage)
        }
    }

    @Test
    fun secretAndAgentPermissionSettingsAreNeverExposedOrMutable() = runBlocking {
        val api = AppApiAdapter(SettingsRepository(ApplicationProvider.getApplicationContext()))
        val values = api.settings()

        assertFalse(values.keys.any { it.contains("key", true) || it.contains("password", true) })
        assertFalse("agent_permission_mode" in values)
        assertFalse("ai_configs" in values)
        val error = runCatching { api.prepareSettingMutation("api_key", "secret") }.exceptionOrNull()
        assertTrue(error is PluginApiException)
        assertEquals("SETTING_NOT_ALLOWED", (error as PluginApiException).code)
    }
}
