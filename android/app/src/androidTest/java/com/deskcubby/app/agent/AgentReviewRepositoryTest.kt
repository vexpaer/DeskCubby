package com.deskcubby.app.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.AppDatabase
import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.app.data.statistics.AgentTokenStatisticsRepository
import com.deskcubby.plugin.api.core.api.AIToolCall
import com.deskcubby.plugin.api.core.api.AITokenUsage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentReviewRepositoryTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun appliedMutationIsRecordedAndUndoExecutesToolThenDisablesIt() = runBlocking {
        val tool = UndoTool()
        val repository = AgentReviewRepository(
            database.agentDao(),
            AgentToolRegistry(setOf(AgentToolContributor { listOf(tool) })),
        )
        val request = request()
        repository.startRun(request)
        val call = AIToolCall("call-1", tool.definition.name, mapOf("content" to "after"))
        val eventId = repository.startToolEvent("run-1", 1, call, tool.definition)
        val mutationId = repository.beginMutation(
            "run-1",
            eventId,
            tool,
            AgentToolPreparation(
                call,
                "thoughts/7",
                "Edit thought",
                "entry=7",
                "before",
                "after",
                "prepared-plan",
            ),
        )
        repository.completeMutation(
            mutationId,
            AgentToolOutcome(
                "{\"ok\":true}",
                "Edited thought",
                "thoughts/7",
                "before",
                "after",
                "undo-exact",
            ),
        )
        repository.finishToolEvent(
            eventId,
            AgentExecutionStatus.SUCCEEDED,
            "thoughts/7",
            "Edited thought",
            "done",
        )
        repository.finishRun(
            "run-1",
            "SUCCEEDED",
            AgentRunUsage()
                .plus(
                    AITokenUsage(
                        inputTokens = 100,
                        outputTokens = 20,
                        totalTokens = 120,
                        cachedInputTokens = 40,
                        reasoningTokens = 5,
                    ),
                )
                .plus(AITokenUsage()),
        )

        val applied = repository.observeMutations("run-1").first().single()
        assertTrue(applied.canUndo)
        assertEquals("before", applied.before)
        assertEquals("after", applied.after)
        val undoOutcome = repository.undo(applied.id)

        assertEquals("undo-exact", tool.receivedUndoToken)
        assertEquals("Restored thought", undoOutcome.summary)
        assertFalse(repository.observeMutations("run-1").first().single().canUndo)
        assertEquals("UNDONE", repository.observeMutations("run-1").first().single().status)

        val usage = AgentTokenStatisticsRepository(database.agentDao()).statistics.first()
        assertEquals(1L, usage.runCount)
        assertEquals(2L, usage.modelCallCount)
        assertEquals(1L, usage.reportedCallCount)
        assertEquals(1L, usage.unreportedCallCount)
        assertEquals(120L, usage.totalTokens)
        assertEquals(0.4, requireNotNull(usage.cacheRate), 0.0001)
    }

    @Test
    fun failedMutationIsNeverUndoable() = runBlocking {
        val tool = UndoTool()
        val repository = AgentReviewRepository(
            database.agentDao(),
            AgentToolRegistry(setOf(AgentToolContributor { listOf(tool) })),
        )
        repository.startRun(request())
        val call = AIToolCall("call-1", tool.definition.name, emptyMap())
        val eventId = repository.startToolEvent("run-1", 1, call, tool.definition)
        val mutationId = repository.beginMutation(
            "run-1",
            eventId,
            tool,
            AgentToolPreparation(call, "thoughts/7", "Edit", "", "before", "after", "plan"),
        )

        repository.failMutation(mutationId)

        val failed = repository.observeMutations("run-1").first().single()
        assertEquals("FAILED", failed.status)
        assertFalse(failed.canUndo)
        val error = runCatching { repository.undo(failed.id) }.exceptionOrNull()
        assertTrue(error is AgentToolException)
    }

    @Test
    fun providerOmittedCacheUsageRemainsUnknownInsteadOfZero() = runBlocking {
        val repository = AgentReviewRepository(
            database.agentDao(),
            AgentToolRegistry(emptySet()),
        )
        repository.startRun(request())
        repository.finishRun(
            "run-1",
            "SUCCEEDED",
            AgentRunUsage().plus(
                AITokenUsage(
                    inputTokens = 100,
                    outputTokens = 20,
                    totalTokens = 120,
                ),
            ),
        )

        assertNull(database.agentDao().getRun("run-1")?.cachedInputTokens)
        val usage = AgentTokenStatisticsRepository(database.agentDao()).statistics.first()
        assertEquals(100L, usage.inputTokens)
        assertNull(usage.cachedInputTokens)
        assertNull(usage.cacheRate)
    }

    private fun request() = AgentRunRequest(
        "run-1",
        1,
        "Conversation",
        "Edit my thought",
        "model",
        "",
        setOf("thoughts"),
        AgentPermissionMode.REQUIRE_APPROVAL,
        true,
        listOf(AgentConversationMessage(AgentConversationRole.USER, "Edit my thought")),
    )

    private class UndoTool : AgentTool {
        var receivedUndoToken: String? = null
        override val definition = AgentToolDefinition(
            "edit_content",
            "Edit one content item",
            "{\"type\":\"object\"}",
            AgentToolClassification.MUTATION,
        )
        override suspend fun prepare(call: AIToolCall, scope: AgentRunScope): AgentToolPreparation =
            error("not used")
        override suspend fun execute(
            preparation: AgentToolPreparation,
            scope: AgentRunScope,
        ): AgentToolOutcome = error("not used")
        override suspend fun undo(undoToken: String): AgentToolOutcome {
            receivedUndoToken = undoToken
            return AgentToolOutcome("{\"ok\":true}", "Restored thought", "thoughts/7")
        }
    }
}
