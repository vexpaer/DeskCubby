package com.deskcubby.app.ui.ai

import com.deskcubby.app.agent.AgentExecutionStatus
import com.deskcubby.app.agent.AgentReviewToolEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AiChatDurableProjectionTest {
    @Test
    fun durableToolEventRestoresExecutionPanelFields() {
        val update = AgentReviewToolEvent(
            id = 7,
            toolCallId = "call-1",
            toolName = "diary_write",
            classification = "MUTATION",
            status = "SUCCEEDED",
            target = "2026-08-21.md",
            summary = "Diary updated",
            argumentsSummary = "date=2026-08-21",
            resultSummary = "Saved",
            startedAt = 10,
            completedAt = 20,
        ).toExecutionUpdate()

        assertEquals("call-1", update.toolCallId)
        assertEquals("diary_write", update.toolName)
        assertEquals(AgentExecutionStatus.SUCCEEDED, update.status)
        assertEquals("Diary updated", update.title)
        assertEquals("2026-08-21.md", update.target)
        assertEquals("date=2026-08-21", update.argumentsSummary)
        assertEquals("Saved", update.resultSummary)
    }

    @Test
    fun malformedDurableEventUsesSafeFallbacks() {
        val update = AgentReviewToolEvent(
            id = 9,
            toolCallId = "",
            toolName = "unknown_tool",
            classification = "UNKNOWN",
            status = "NOT_A_STATUS",
            target = "",
            summary = "",
            argumentsSummary = "",
            resultSummary = "",
            startedAt = 10,
            completedAt = null,
        ).toExecutionUpdate()

        assertEquals("event-9", update.toolCallId)
        assertEquals(AgentExecutionStatus.FAILED, update.status)
        assertEquals("unknown_tool", update.title)
    }
}
