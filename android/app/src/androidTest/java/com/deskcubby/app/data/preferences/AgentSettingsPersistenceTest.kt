package com.deskcubby.app.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.model.AgentDataSource
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.model.DEFAULT_AGENT_PROMPT
import com.deskcubby.app.data.model.DEFAULT_AI_PAGE_FONT_SIZE_SP
import com.deskcubby.app.data.model.DEFAULT_AI_REPLY_BOX_WIDTH_DP
import com.deskcubby.app.data.model.DEFAULT_MORE_PAGE_COLUMNS
import com.deskcubby.app.data.model.NavItemId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentSettingsPersistenceTest {
    @Test
    fun aiPageAndMorePageSettingsPersistAcrossRepositoryInstances() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = SettingsRepository(context)
        try {
            first.setAiPageSettings(
                fontSizeSp = 20f,
                replyBoxWidthDp = 900f,
                agentPrompt = "请始终使用简体中文回答。",
            )
            first.setMorePageSettings(
                showDescriptions = false,
                columns = 3,
                items = AppSettings().navItems.map { item ->
                    if (item.id == NavItemId.DIARY) {
                        item.copy(
                            label = "日记本",
                            moreButtonColorArgb = 0xFF112233.toInt(),
                            moreCardColorArgb = 0xFF445566.toInt(),
                        )
                    } else {
                        item
                    }
                },
            )

            val reloaded = SettingsRepository(context).settings.first()

            assertEquals(20f, reloaded.aiPageFontSizeSp, 0f)
            assertEquals(900f, reloaded.aiReplyBoxWidthDp, 0f)
            assertEquals("请始终使用简体中文回答。", reloaded.agentPrompt)
            assertEquals(3, reloaded.morePageColumns)
            assertEquals(false, reloaded.morePageShowDescriptions)
            val diary = reloaded.navItems.first { it.id == NavItemId.DIARY }
            assertEquals("日记本", diary.label)
            assertEquals(0xFF112233.toInt(), diary.moreButtonColorArgb)
            assertEquals(0xFF445566.toInt(), diary.moreCardColorArgb)
        } finally {
            first.setAiPageSettings(
                fontSizeSp = DEFAULT_AI_PAGE_FONT_SIZE_SP,
                replyBoxWidthDp = DEFAULT_AI_REPLY_BOX_WIDTH_DP,
                agentPrompt = DEFAULT_AGENT_PROMPT,
            )
            first.setMorePageSettings(
                showDescriptions = true,
                columns = DEFAULT_MORE_PAGE_COLUMNS,
                items = AppSettings().navItems,
            )
        }
    }

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
