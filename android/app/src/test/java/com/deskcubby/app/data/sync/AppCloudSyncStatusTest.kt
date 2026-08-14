package com.deskcubby.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCloudSyncStatusTest {
    @Test
    fun transferTotalsCombineEveryCompletedConfigAndIgnoreFailures() {
        val first = result(
            "first",
            CloudSyncItemOutcome.UPLOADED,
            CloudSyncItemOutcome.DOWNLOADED,
            CloudSyncItemOutcome.CONFLICT_COPY_SAVED,
        )
        val second = result(
            "second",
            CloudSyncItemOutcome.UPLOADED,
            CloudSyncItemOutcome.UPLOADED,
        )

        assertEquals(
            CloudSyncTransferTotals(uploaded = 3, downloaded = 1, conflicts = 1),
            cloudSyncTransferTotals(
                listOf(
                    CloudSyncConfigRun("first", first),
                    CloudSyncConfigRun("failed", errorMessage = "safe error"),
                    CloudSyncConfigRun("second", second),
                ),
            ),
        )
    }

    private fun result(
        configId: String,
        vararg outcomes: CloudSyncItemOutcome,
    ): CloudSyncRunResult = CloudSyncRunResult(
        configId = configId,
        startedAtMillis = 1L,
        finishedAtMillis = 2L,
        reports = outcomes.mapIndexed { index, outcome ->
            CloudSyncItemReport("item-$index", outcome)
        },
        transferredBytes = 0L,
    )
}
