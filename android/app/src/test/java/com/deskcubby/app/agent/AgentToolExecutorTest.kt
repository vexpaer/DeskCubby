package com.deskcubby.app.agent

import com.deskcubby.app.data.model.AgentPermissionMode
import com.deskcubby.plugin.api.core.api.AIToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolExecutorTest {
    @Test
    fun mutationRequiresApprovalAndRecordsAppliedReview() = runBlocking {
        val tool = MutationTool()
        val approval = RecordingApproval(AgentApprovalDecision.APPROVE)
        val review = RecordingReview()

        val result = executor(tool, approval, review).execute(
            "run",
            1,
            call(),
            scope(AgentPermissionMode.REQUIRE_APPROVAL),
        ) {}

        assertTrue(result.success)
        assertEquals(AgentPermissionMode.REQUIRE_APPROVAL, approval.mode)
        assertNotNull(approval.request)
        assertTrue(tool.executed)
        assertTrue(review.mutationStarted)
        assertTrue(review.mutationCompleted)
        assertFalse(review.mutationFailed)
    }

    @Test
    fun rejectedMutationIsReturnedToModelAndNeverExecuted() = runBlocking {
        val tool = MutationTool()
        val review = RecordingReview()

        val result = executor(
            tool,
            RecordingApproval(AgentApprovalDecision.REJECT),
            review,
        ).execute("run", 1, call(), scope(AgentPermissionMode.REQUIRE_APPROVAL)) {}

        assertFalse(result.success)
        assertTrue(result.rejected)
        assertEquals("USER_REJECTED", result.errorCode)
        assertFalse(tool.executed)
        assertFalse(review.mutationStarted)
        assertEquals(AgentExecutionStatus.REJECTED, review.eventStatus)
    }

    @Test
    fun fullAutoExecutesWithoutPendingUiButStillCreatesReview() = runBlocking {
        val tool = MutationTool()
        val permissionManager = AgentPermissionManager()
        val review = RecordingReview()
        val updates = mutableListOf<AgentExecutionUpdate>()

        val result = executor(tool, permissionManager, review).execute(
            "run",
            1,
            call(),
            scope(AgentPermissionMode.FULL_AUTO),
        ) { updates += it }

        assertTrue(result.success)
        assertNull(permissionManager.pending.value)
        assertFalse(updates.any { it.status == AgentExecutionStatus.WAITING_APPROVAL })
        assertTrue(review.mutationStarted)
        assertTrue(review.mutationCompleted)
    }

    @Test
    fun failedMutationLeavesExplicitFailedReview() = runBlocking {
        val tool = MutationTool(fail = true)
        val review = RecordingReview()

        val result = executor(
            tool,
            RecordingApproval(AgentApprovalDecision.APPROVE),
            review,
        ).execute("run", 1, call(), scope(AgentPermissionMode.REQUIRE_APPROVAL)) {}

        assertFalse(result.success)
        assertEquals("MUTATION_FAILED", result.errorCode)
        assertTrue(review.mutationStarted)
        assertTrue(review.mutationFailed)
        assertFalse(review.mutationCompleted)
    }

    @Test
    fun unauthorizedSourceFailsClosed() {
        val scope = AgentRunScope(
            "run",
            setOf("diary"),
            AgentPermissionMode.REQUIRE_APPROVAL,
            true,
        )

        scope.requireSource("diary")
        val error = runCatching { scope.requireSource("notes") }.exceptionOrNull()

        assertTrue(error is AgentToolException)
        assertEquals("SOURCE_NOT_AUTHORIZED", (error as AgentToolException).code)
    }

    private fun executor(
        tool: AgentTool,
        approval: AgentApprovalGateway,
        review: RecordingReview,
    ) = AgentToolExecutor(
        AgentToolRegistry(setOf(AgentToolContributor { listOf(tool) })),
        approval,
        review,
    )

    private fun scope(mode: AgentPermissionMode) = AgentRunScope("run", setOf("diary"), mode, true)

    private fun call() = AIToolCall("call", "edit_content", mapOf("content" to "after"))

    private class MutationTool(private val fail: Boolean = false) : AgentTool {
        var executed = false
        override val definition = AgentToolDefinition(
            "edit_content",
            "Edit a test object",
            "{\"type\":\"object\"}",
            AgentToolClassification.MUTATION,
        )

        override suspend fun prepare(call: AIToolCall, scope: AgentRunScope) = AgentToolPreparation(
            call,
            target = "diary/2026-08-13",
            summary = "Replace one line",
            argumentsSummary = "content=after",
            before = "before",
            after = "after",
            executionToken = "prepared",
        )

        override suspend fun execute(
            preparation: AgentToolPreparation,
            scope: AgentRunScope,
        ): AgentToolOutcome {
            executed = true
            if (fail) throw AgentToolException("MUTATION_FAILED", "Atomic write failed")
            return AgentToolOutcome(
                content = "{\"ok\":true}",
                summary = "Changed one line",
                target = preparation.target,
                before = "before",
                after = "after",
                undoToken = "undo-token",
            )
        }
    }

    private class RecordingApproval(
        private val decision: AgentApprovalDecision,
    ) : AgentApprovalGateway {
        var mode: AgentPermissionMode? = null
        var request: AgentApprovalRequest? = null
        override suspend fun authorize(
            mode: AgentPermissionMode,
            request: AgentApprovalRequest,
        ): AgentApprovalDecision {
            this.mode = mode
            this.request = request
            return decision
        }
    }

    private class RecordingReview : AgentReviewStore {
        var eventStatus: AgentExecutionStatus? = null
        var mutationStarted = false
        var mutationCompleted = false
        var mutationFailed = false
        override suspend fun startRun(request: AgentRunRequest) = Unit
        override suspend fun finishRun(runId: String, status: String, usage: AgentRunUsage) = Unit
        override suspend fun startToolEvent(
            runId: String,
            sequence: Int,
            call: AIToolCall,
            definition: AgentToolDefinition?,
        ) = 3L
        override suspend fun finishToolEvent(
            eventId: Long,
            status: AgentExecutionStatus,
            target: String,
            summary: String,
            resultSummary: String,
            errorCode: String?,
        ) {
            eventStatus = status
        }
        override suspend fun beginMutation(
            runId: String,
            eventId: Long,
            tool: AgentTool,
            preparation: AgentToolPreparation,
        ): Long {
            mutationStarted = true
            return 9L
        }
        override suspend fun completeMutation(mutationId: Long, outcome: AgentToolOutcome) {
            mutationCompleted = true
        }
        override suspend fun failMutation(mutationId: Long) {
            mutationFailed = true
        }
    }
}
