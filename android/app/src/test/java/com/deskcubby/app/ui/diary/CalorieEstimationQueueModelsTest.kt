package com.deskcubby.app.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieEstimationQueueModelsTest {
    @Test
    fun queueSeparatesActivePendingAndTerminalDays() {
        val state = CalorieEstimationQueueState(
            items = listOf(
                progress(1, CalorieEstimationQueueStatus.COMPLETED),
                progress(2, CalorieEstimationQueueStatus.TEXT_ESTIMATION),
                progress(3, CalorieEstimationQueueStatus.QUEUED),
                progress(4, CalorieEstimationQueueStatus.FAILED),
            ),
        )

        assertEquals(2L, state.active?.id)
        assertEquals(listOf(3L), state.queued.map { it.id })
        assertEquals(2, state.finishedDayCount)
        assertEquals(1, state.failedDayCount)
        assertTrue(state.isRunning)
    }

    @Test
    fun completedAndFailedAreTheOnlyTerminalStatuses() {
        assertTrue(CalorieEstimationQueueStatus.COMPLETED.isTerminal)
        assertTrue(CalorieEstimationQueueStatus.FAILED.isTerminal)
        assertFalse(CalorieEstimationQueueStatus.SAVING.isTerminal)
        assertFalse(CalorieEstimationQueueStatus.QUEUED.isTerminal)
    }

    private fun progress(id: Long, status: CalorieEstimationQueueStatus) =
        CalorieEstimationDayProgress(
            id = id,
            dateIso = "2026-08-0$id",
            status = status,
            selectedPhotoCount = 2,
            dayPhotoCount = 3,
        )
}
