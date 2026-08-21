package com.deskcubby.app.data.structuredrecords

import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.preferences.TodayDiarySwitchTimeStore
import com.deskcubby.app.data.repository.DiaryFileRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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

    private fun settingsJson(schema: Int, protocol: Int): String =
        "{\"schemaVersion\":$schema,\"markdownProtocolVersion\":$protocol}"
}