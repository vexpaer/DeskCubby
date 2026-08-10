package com.deskcubby.app.widget

import com.deskcubby.app.data.local.DateRecordEntity
import com.deskcubby.app.data.sync.CloudSyncRunMode
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopWidgetInteractionPolicyTest {
    @Test
    fun narrowOneColumnCardsAlwaysRemainNavigationOnly() {
        listOf("poem", "quick_input", "meal_photos", "cloud_sync_force").forEach { module ->
            assertEquals(
                DesktopWidgetInteractionMode.NAVIGATION_ONLY,
                DesktopWidgetInteractionPolicy.mode(module, widthDp = 70, heightDp = 280),
            )
        }
    }

    @Test
    fun interactiveModulesRequireEnoughWidthAndHeight() {
        assertEquals(
            DesktopWidgetInteractionMode.POEM_ACTIONS,
            DesktopWidgetInteractionPolicy.mode("poem", 200, 100),
        )
        assertEquals(
            DesktopWidgetInteractionMode.QUICK_INPUT,
            DesktopWidgetInteractionPolicy.mode("quick_input", 180, 110),
        )
        assertEquals(
            DesktopWidgetInteractionMode.MEAL_ACTIONS_3_BY_2,
            DesktopWidgetInteractionPolicy.mode("meal_photos", 180, 150),
        )
        assertEquals(
            DesktopWidgetInteractionMode.MEAL_ACTIONS_WIDE,
            DesktopWidgetInteractionPolicy.mode("meal_photos", 270, 90),
        )
        assertEquals(
            DesktopWidgetInteractionMode.MEAL_ACTIONS_2_BY_3,
            DesktopWidgetInteractionPolicy.mode("meal_photos", 130, 210),
        )
        assertEquals(
            DesktopWidgetInteractionMode.NAVIGATION_ONLY,
            DesktopWidgetInteractionPolicy.mode("meal_photos", 129, 210),
        )
    }

    @Test
    fun nonInteractiveInformationModulesNeverExposeActionRows() {
        listOf("today", "record_overview", "notes", "game_shortcuts").forEach { module ->
            assertEquals(
                DesktopWidgetInteractionMode.NAVIGATION_ONLY,
                DesktopWidgetInteractionPolicy.mode(module, 500, 500),
            )
        }
    }

    @Test
    fun restoredMealCaptureNeverPromptsForAnotherSource() {
        assertEquals(
            false,
            DesktopWidgetInteractionPolicy.shouldPromptForMealSource(
                pendingCameraPath = "cache/meal-camera/pending.jpg",
                externalSourceLaunched = false,
            ),
        )
        assertEquals(
            false,
            DesktopWidgetInteractionPolicy.shouldPromptForMealSource(
                pendingCameraPath = null,
                externalSourceLaunched = true,
            ),
        )
        assertEquals(
            true,
            DesktopWidgetInteractionPolicy.shouldPromptForMealSource(
                pendingCameraPath = null,
                externalSourceLaunched = false,
            ),
        )
    }

    @Test
    fun forceCloudRequiresSafeSavedConfiguration() {
        assertEquals(
            ForceCloudAvailability.SYNC_DISABLED,
            DesktopWidgetInteractionPolicy.forceCloudAvailability(false, 1, download = false),
        )
        assertEquals(
            ForceCloudAvailability.NO_ENABLED_SOURCE,
            DesktopWidgetInteractionPolicy.forceCloudAvailability(true, 0, download = false),
        )
        assertEquals(
            ForceCloudAvailability.DOWNLOAD_REQUIRES_ONE_SOURCE,
            DesktopWidgetInteractionPolicy.forceCloudAvailability(true, 2, download = true),
        )
        assertEquals(
            ForceCloudAvailability.READY,
            DesktopWidgetInteractionPolicy.forceCloudAvailability(true, 2, download = false),
        )
        assertEquals(
            ForceCloudAvailability.READY,
            DesktopWidgetInteractionPolicy.forceCloudAvailability(true, 1, download = true),
        )
        assertEquals(
            false,
            DesktopWidgetInteractionPolicy.cloudActionCanRun(
                syncEnabled = true,
                enabledSourceCount = 1,
                running = false,
                queued = true,
            ),
        )
        assertEquals(
            true,
            DesktopWidgetInteractionPolicy.cloudActionCanRun(
                syncEnabled = true,
                enabledSourceCount = 1,
                running = false,
                queued = false,
            ),
        )
    }

    @Test
    fun expandedParityLayoutsRequireEnoughActualSpace() {
        assertEquals(
            ExpandedWidgetMode.CALENDAR,
            DesktopWidgetInteractionPolicy.expandedMode("calendar", 230, 250),
        )
        assertEquals(
            ExpandedWidgetMode.FOUR_ROW_LIST,
            DesktopWidgetInteractionPolicy.expandedMode("recent_diary", 180, 240),
        )
        assertEquals(
            ExpandedWidgetMode.CLOUD_STATUS,
            DesktopWidgetInteractionPolicy.expandedMode("cloud_sync_now", 180, 180),
        )
        assertEquals(
            ExpandedWidgetMode.NONE,
            DesktopWidgetInteractionPolicy.expandedMode("daily_records", 179, 400),
        )
        assertEquals(
            ExpandedWidgetMode.YEAR_PROGRESS,
            DesktopWidgetInteractionPolicy.expandedMode("year_progress", 180, 100),
        )
        assertEquals(
            ExpandedWidgetMode.FOUR_ROW_LIST,
            DesktopWidgetInteractionPolicy.expandedMode("game_shortcuts", 180, 379),
        )
        assertEquals(
            ExpandedWidgetMode.EIGHT_ROW_LIST,
            DesktopWidgetInteractionPolicy.expandedMode("game_shortcuts", 180, 380),
        )
    }

    @Test
    fun dateRecordCardKeepsTwoNearestUpcomingAndTwoNearestPast() {
        val records = listOf(
            dateRecord(1, "2026-08-20"),
            dateRecord(2, "2026-08-11"),
            dateRecord(3, "2026-08-10"),
            dateRecord(4, "2026-08-09"),
            dateRecord(5, "2026-01-01"),
            dateRecord(6, "invalid"),
        )

        assertEquals(
            listOf(3L, 2L, 4L, 5L),
            nearestDesktopDateRecords(records, LocalDate.parse("2026-08-10")).map { it.id },
        )
    }

    private fun dateRecord(id: Long, dateIso: String) = DateRecordEntity(
        id = id,
        name = "record-$id",
        icon = "",
        dateIso = dateIso,
        createdAt = id,
        updatedAt = id,
    )

    @Test
    fun diaryNavigationTokenIsRandomAndConsumedOnlyOnce() {
        DesktopWidgetNavigationTokenStore.clearForTest()
        val first = DesktopWidgetNavigationTokenStore.issueDiaryToken("content://trusted/one")
        val second = DesktopWidgetNavigationTokenStore.issueDiaryToken("content://trusted/one")

        assertEquals(false, first == second)
        assertEquals(null, DesktopWidgetNavigationTokenStore.consumeDiaryUri("attacker-token"))
        assertEquals("content://trusted/one", DesktopWidgetNavigationTokenStore.consumeDiaryUri(first))
        assertEquals(null, DesktopWidgetNavigationTokenStore.consumeDiaryUri(first))

        val configToken = DesktopWidgetNavigationTokenStore.issueConfigToken("trusted-config")
        assertEquals(null, DesktopWidgetNavigationTokenStore.consumeConfigId("forged-token"))
        assertEquals("trusted-config", DesktopWidgetNavigationTokenStore.consumeConfigId(configToken))
        assertEquals(null, DesktopWidgetNavigationTokenStore.consumeConfigId(configToken))
    }

    @Test
    fun expandedRowsCollapseMissingSlotsInsteadOfReservingSpace() {
        assertEquals(false, DesktopWidgetInteractionPolicy.shouldShowExpandedRow(0, 0, 4))
        assertEquals(true, DesktopWidgetInteractionPolicy.shouldShowExpandedRow(3, 4, 4))
        assertEquals(false, DesktopWidgetInteractionPolicy.shouldShowExpandedRow(4, 4, 8))
        assertEquals(true, DesktopWidgetInteractionPolicy.shouldShowExpandedRow(7, 8, 8))
        assertEquals(false, DesktopWidgetInteractionPolicy.shouldShowExpandedRow(8, 8, 8))
    }

    @Test
    fun pendingIntentIdentityDoesNotReuseJavaHashCollisions() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertEquals(
            false,
            widgetPendingIdentity("7", "open_diary", "content://diary/Aa") ==
                widgetPendingIdentity("7", "open_diary", "content://diary/BB"),
        )
        assertEquals(
            false,
            widgetPendingIdentity("7", "daily_record", "Aa") ==
                widgetPendingIdentity("7", "daily_record", "BB"),
        )
    }

    @Test
    fun queuedCloudWorkDisablesEveryIndependentCloudWidget() {
        assertEquals(
            false,
            shouldEnableCloudWidgetAction(true, 1, busy = true, download = false),
        )
        assertEquals(
            false,
            shouldEnableCloudWidgetAction(true, 1, busy = true, download = true),
        )
        assertEquals(
            false,
            shouldEnableCloudWidgetAction(true, 2, busy = false, download = true),
        )
        assertEquals(
            true,
            shouldEnableCloudWidgetAction(true, 1, busy = false, download = false),
        )
    }

    @Test
    fun rapidSecondCloudTapKeepsTheExistingQueueInsteadOfShowingFailure() {
        assertEquals(
            CloudEnqueueRender(CloudSyncRunMode.FORCE_UPLOAD, CloudSyncWidgetState.QUEUED),
            resolveCloudEnqueueRender(
                requestedMode = CloudSyncRunMode.NORMAL,
                accepted = false,
                queuedMode = CloudSyncRunMode.FORCE_UPLOAD,
            ),
        )
        assertEquals(
            CloudEnqueueRender(CloudSyncRunMode.NORMAL, CloudSyncWidgetState.FAILED),
            resolveCloudEnqueueRender(
                requestedMode = CloudSyncRunMode.NORMAL,
                accepted = false,
                queuedMode = null,
            ),
        )
    }

    @Test
    fun nonManualGlobalSyncDisablesBothIndependentWidgets() {
        assertEquals(
            CloudSyncWidgetState.RUNNING,
            resolveCloudWidgetState(
                requestedState = CloudSyncWidgetState.IDLE,
                globallyRunning = true,
                queuedMode = null,
            ),
        )
        assertEquals(
            false,
            shouldEnableCloudWidgetAction(true, 1, busy = true, download = false),
        )
        assertEquals(
            false,
            shouldEnableCloudWidgetAction(true, 1, busy = true, download = true),
        )
    }

    @Test
    fun staleProviderWriteCompletesBeforeWorkerTerminalWrite() = runBlocking {
        val sequencer = CloudWidgetRenderSequencer()
        val releaseStaleWrite = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val staleProvider = async(start = CoroutineStart.UNDISPATCHED) {
            sequencer.serialized {
                events += "stale-read-queued"
                releaseStaleWrite.await()
                events += "stale-write-queued"
            }
        }
        val workerTerminal = async(start = CoroutineStart.UNDISPATCHED) {
            sequencer.serialized { events += "worker-write-terminal-after-clear" }
        }

        assertEquals(listOf("stale-read-queued"), events)
        releaseStaleWrite.complete(Unit)
        awaitAll(staleProvider, workerTerminal)
        assertEquals(
            listOf(
                "stale-read-queued",
                "stale-write-queued",
                "worker-write-terminal-after-clear",
            ),
            events,
        )
    }
}
