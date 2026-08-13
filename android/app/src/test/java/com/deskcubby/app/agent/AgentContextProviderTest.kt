package com.deskcubby.app.agent

import com.deskcubby.plugin.api.core.api.DeskCubbyDataAPI
import com.deskcubby.plugin.api.core.api.DeskCubbyDataEntry
import com.deskcubby.plugin.api.core.api.DeskCubbyDataMutationRequest
import com.deskcubby.plugin.api.core.api.DeskCubbyDataPage
import com.deskcubby.plugin.api.core.api.DeskCubbyDataQuery
import com.deskcubby.plugin.api.core.api.DeskCubbyDataSource
import com.deskcubby.plugin.api.core.api.DeskCubbyMutationPlan
import com.deskcubby.plugin.api.core.api.DeskCubbyMutationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextProviderTest {
    @Test
    fun contextRequestsMetadataOnlyForAuthorizedSources() = runBlocking {
        val api = RecordingDataApi()
        val prompt = DefaultAgentContextProvider(api).metadataPrompt(setOf("diary"), true)

        assertEquals(setOf("diary"), api.requestedSources)
        assertTrue(prompt.contains("id=diary"))
        assertFalse(prompt.contains("private thought"))
        assertEquals(0, api.readCalls)
    }

    @Test
    fun emptyGrantDoesNotTouchAnyDataApi() = runBlocking {
        val api = RecordingDataApi()

        val prompt = DefaultAgentContextProvider(api).metadataPrompt(emptySet(), true)

        assertTrue(prompt.contains("No DeskCubby data source"))
        assertEquals(null, api.requestedSources)
        assertEquals(0, api.readCalls)
    }

    private class RecordingDataApi : DeskCubbyDataAPI {
        var requestedSources: Set<String>? = null
        var readCalls = 0

        override suspend fun sources(sourceIds: Set<String>?): List<DeskCubbyDataSource> {
            requestedSources = sourceIds
            return listOf(
                DeskCubbyDataSource(
                    id = "diary",
                    labelChinese = "日记",
                    labelEnglish = "Diary",
                    descriptionChinese = "日记元数据",
                    descriptionEnglish = "Diary metadata",
                    entryCount = 12,
                    categories = emptyList(),
                    earliestDateIso = "2026-01-01",
                    latestDateIso = "2026-08-13",
                    mutable = true,
                ),
            ).filter { sourceIds == null || it.id in sourceIds }
        }

        override suspend fun list(query: DeskCubbyDataQuery): DeskCubbyDataPage = error("not used")
        override suspend fun read(sourceId: String, entryId: String): DeskCubbyDataEntry {
            readCalls += 1
            error("metadata initialization must not read entries")
        }
        override suspend fun prepareMutation(request: DeskCubbyDataMutationRequest): DeskCubbyMutationPlan =
            error("not used")
        override suspend fun commitMutation(planToken: String): DeskCubbyMutationResult = error("not used")
        override suspend fun undoMutation(undoToken: String): DeskCubbyMutationResult = error("not used")
    }
}
