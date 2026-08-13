package com.deskcubby.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.model.AgentDataSource
import com.deskcubby.app.data.model.AgentPermissionMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSettingsPersistenceTest {
    @Test
    fun contextGrantsAndPermissionModePersistAcrossRepositoryInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = SettingsRepository(context)
        val grants = setOf(
            AgentDataSource.DIARY,
            AgentDataSource.THOUGHTS,
            AgentDataSource.USAGE,
        )
        try {
            first.setAgentEnabledSources(grants)
            first.setAgentPermissionMode(AgentPermissionMode.FULL_AUTO)

            val reloaded = SettingsRepository(context).settings.first()

            assertEquals(grants, reloaded.agentEnabledSources)
            assertEquals(AgentPermissionMode.FULL_AUTO, reloaded.agentPermissionMode)
        } finally {
            first.setAgentEnabledSources(emptySet())
            first.setAgentPermissionMode(AgentPermissionMode.REQUIRE_APPROVAL)
        }
    }
}
