package com.deskcubby.app.ui.diary

import com.deskcubby.app.data.repository.MealCalorieEstimationStage
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

    @Test
    fun modelTraceUsesMonotonicBoundsAndFreezesWhenFinished() {
        val running = CalorieModelTrace(
            stage = MealCalorieEstimationStage.IMAGE_RECOGNITION,
            modelName = "vision-model",
            selectedPhotoIndex = 1,
            photoLabel = "Lunch 1",
            startedAtElapsedRealtime = 1_000L,
        )
        val finished = running.copy(finishedAtElapsedRealtime = 2_345L)

        assertEquals(500L, running.elapsedMillis(1_500L))
        assertEquals(1_345L, finished.elapsedMillis(9_999L))
        assertEquals(0L, running.elapsedMillis(500L))
        assertTrue(running.isRunning)
        assertFalse(finished.isRunning)
        assertEquals("1.3", formatCalorieTraceElapsed(finished.elapsedMillis(9_999L)))
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
