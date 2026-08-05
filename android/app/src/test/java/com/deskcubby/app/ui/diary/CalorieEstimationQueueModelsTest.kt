package com.deskcubby.app.ui.diary

import com.deskcubby.app.data.repository.MealCalorieEstimationStage
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun concurrentMapStartsUpToLimitAndKeepsInputOrder() = runBlocking {
        withTimeout(5_000) {
            val active = AtomicInteger(0)
            val maximumActive = AtomicInteger(0)
            val firstWaveStarted = CompletableDeferred<Unit>()
            val releaseFirstWave = CompletableDeferred<Unit>()
            val started = AtomicInteger(0)
            val result = async {
                mapConcurrentOrdered((1..5).toList(), maxConcurrency = 3) { value ->
                    val nowActive = active.incrementAndGet()
                    maximumActive.updateAndGet { previous -> maxOf(previous, nowActive) }
                    if (started.incrementAndGet() == 3) firstWaveStarted.complete(Unit)
                    try {
                        releaseFirstWave.await()
                        value * 10
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }

            firstWaveStarted.await()
            assertEquals(3, active.get())
            releaseFirstWave.complete(Unit)

            assertEquals(listOf(10, 20, 30, 40, 50), result.await())
            assertEquals(3, maximumActive.get())
        }
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
