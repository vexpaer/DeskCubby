package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.DailyEventTemplate
import com.deskcubby.app.data.preferences.TodayDiarySwitchTimeStore
import com.deskcubby.app.data.repository.DiaryFileRepository
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.mock

class StructuredWorkspaceRepositoryTest {
    @Test
    fun switchingDiaryRootsNeverReturnsSettingsFromPreviousRoot() = runBlocking {
        val diary = mock(DiaryFileRepository::class.java)
        val todaySwitch = mock(TodayDiarySwitchTimeStore::class.java)
        Mockito.`when`(todaySwitch.current()).thenReturn("05:00")
        val rootA = AppSettings(diaryTreeUri = "content://diary-a")
        val rootB = AppSettings(diaryTreeUri = "content://diary-b")
        Mockito.`when`(diary.readWorkspaceFile(rootA, StructuredRecordsCodec.FILE_SETTINGS))
            .thenReturn(settingsJson(schema = 1, protocol = 11))
        Mockito.`when`(diary.readWorkspaceFile(rootB, StructuredRecordsCodec.FILE_SETTINGS))
            .thenReturn(settingsJson(schema = 2, protocol = 22))

        val repository = StructuredWorkspaceRepository(diary, todaySwitch)
        val a = repository.loadSettings(rootA)
        val b = repository.loadSettings(rootB)

        assertEquals(1, a.schemaVersion)
        assertEquals(11, a.markdownProtocolVersion)
        assertEquals(2, b.schemaVersion)
        assertEquals(22, b.markdownProtocolVersion)
    }

    @Test
    fun sameRootCloudSyncReplacementIsVisibleOnNextLoad() = runBlocking {
        val diary = mock(DiaryFileRepository::class.java)
        val todaySwitch = mock(TodayDiarySwitchTimeStore::class.java)
        Mockito.`when`(todaySwitch.current()).thenReturn("05:00")
        val root = AppSettings(diaryTreeUri = "content://same-diary")
        Mockito.`when`(diary.readWorkspaceFile(root, StructuredRecordsCodec.FILE_SETTINGS))
            .thenReturn(
                settingsJson(schema = 1, protocol = 1),
                settingsJson(schema = 3, protocol = 7),
            )

        val repository = StructuredWorkspaceRepository(diary, todaySwitch)
        val beforeSync = repository.loadSettings(root)
        val afterSync = repository.loadSettings(root)

        assertEquals(1, beforeSync.schemaVersion)
        assertEquals(1, beforeSync.markdownProtocolVersion)
        assertEquals(3, afterSync.schemaVersion)
        assertEquals(7, afterSync.markdownProtocolVersion)
    }

    @Test
    fun explicitEmptyRecordsRemainEmptyWithoutSeedWrite() = runBlocking {
        val diary = mock(DiaryFileRepository::class.java)
        val todaySwitch = mock(TodayDiarySwitchTimeStore::class.java)
        val root = AppSettings(diaryTreeUri = "content://empty-records")
        val emptyRecords = StructuredRecordsCodec.encodeTemplates(emptyList())
        Mockito.`when`(diary.readWorkspaceFile(root, StructuredRecordsCodec.FILE_RECORDS))
            .thenReturn(emptyRecords)

        val repository = StructuredWorkspaceRepository(diary, todaySwitch)
        val templates = repository.loadTemplates(root)

        assertTrue(templates.isEmpty())
        Mockito.verify(diary).readWorkspaceFile(root, StructuredRecordsCodec.FILE_RECORDS)
        assertTrue(
            Mockito.mockingDetails(diary).invocations.none { it.method.name == "updateWorkspaceFile" },
        )
        Mockito.verifyNoMoreInteractions(diary)
    }

    @Test
    fun explicitEmptyRecordsDoNotResurrectLegacyWidgetIntent() = runBlocking {
        val diary = mock(DiaryFileRepository::class.java)
        val todaySwitch = mock(TodayDiarySwitchTimeStore::class.java)
        val root = AppSettings(
            diaryTreeUri = "content://empty-records-with-legacy-settings",
            dailyEventTemplates = listOf(
                DailyEventTemplate(id = "legacy-water", text = "喝水 xx 杯"),
            ),
        )
        val emptyRecords = StructuredRecordsCodec.encodeTemplates(emptyList())
        Mockito.`when`(diary.readWorkspaceFile(root, StructuredRecordsCodec.FILE_RECORDS))
            .thenReturn(emptyRecords)

        val repository = StructuredWorkspaceRepository(diary, todaySwitch)
        val resolved = repository.resolveTemplateForWidget(root, "legacy-water")

        assertNull(resolved)
        Mockito.verify(diary).readWorkspaceFile(root, StructuredRecordsCodec.FILE_RECORDS)
        assertTrue(
            Mockito.mockingDetails(diary).invocations.none { it.method.name == "updateWorkspaceFile" },
        )
        Mockito.verifyNoMoreInteractions(diary)
    }

    @Test
    fun workspaceReadFailureNeverFallsBackToSeedWrite() = runBlocking {
        val diary = mock(DiaryFileRepository::class.java)
        val todaySwitch = mock(TodayDiarySwitchTimeStore::class.java)
        val root = AppSettings(diaryTreeUri = "content://read-failure")
        Mockito.`when`(diary.readWorkspaceFile(root, StructuredRecordsCodec.FILE_RECORDS))
            .thenAnswer { throw IOException("provider read failed") }

        val repository = StructuredWorkspaceRepository(diary, todaySwitch)
        var threw = false
        try {
            repository.loadTemplates(root)
        } catch (_: IOException) {
            threw = true
        }

        assertTrue(threw)
        Mockito.verify(diary).readWorkspaceFile(root, StructuredRecordsCodec.FILE_RECORDS)
        assertTrue(
            Mockito.mockingDetails(diary).invocations.none { it.method.name == "updateWorkspaceFile" },
        )
        Mockito.verifyNoMoreInteractions(diary)
    }

    private fun settingsJson(schema: Int, protocol: Int): String =
        "{\"schemaVersion\":$schema,\"markdownProtocolVersion\":$protocol}"
}
