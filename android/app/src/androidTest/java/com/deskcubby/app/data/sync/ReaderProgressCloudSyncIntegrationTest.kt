package com.deskcubby.app.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.deskcubby.app.data.local.DiaryIndexDao
import com.deskcubby.app.data.local.DiaryIndexEntity
import com.deskcubby.app.data.model.AppSettings
import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.repository.DiaryFileRepository
import com.deskcubby.app.data.repository.ReaderBookType
import com.deskcubby.app.data.repository.ReaderProgressRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderProgressCloudSyncIntegrationTest {
    @Test
    fun localStoreUsesOneFixedKeyAndReturnsTheMergedSnapshot() = runBlocking {
        val initial = payload(record("a", page = 3, updatedAt = 10))
        val incoming = payload(record("a", page = 8, updatedAt = 20))
        val merged = payload(
            record("a", page = 8, updatedAt = 20),
            record("b", page = 2, updatedAt = 15),
        )
        val bridge = FakeReaderProgressBridge(initial).apply { nextMergedBytes = merged }
        val store = localStore(bridge)

        val listed = store.list(
            setOf(CloudSyncContent.READING_PROGRESS),
            CloudSyncLimits(),
        ).single()
        assertEquals(KEY, listed.key)
        assertEquals(CloudSyncContent.READING_PROGRESS, listed.content)
        assertArrayEquals(initial, store.read(listed, ReaderProgressJsonCodec.MAX_JSON_BYTES.toLong()))

        val result = store.writeRemote(
            key = KEY,
            bytes = incoming,
            contentSha256 = sha256(incoming),
            lastModifiedMillis = 20L,
            // Whole-object mismatch is intentional: the bridge performs record-level LWW.
            expectedLocalSha256 = null,
            limits = CloudSyncLimits(),
        ) as LocalWriteResult.Applied

        assertEquals(sha256(merged), result.objectInfo.sha256)
        assertArrayEquals(merged, store.read(result.objectInfo, merged.size.toLong()))
        assertEquals(1, bridge.mergeCalls)
        assertArrayEquals(incoming, bridge.lastIncoming)
    }

    @Test
    fun localStoreRejectsAnyOtherObjectInsideTheReadingPrefix() {
        val bytes = payload()
        val store = localStore(FakeReaderProgressBridge(bytes))

        assertThrows(CloudSyncException::class.java) {
            runBlocking {
                store.writeRemote(
                    key = "reading/v1/private.json",
                    bytes = bytes,
                    contentSha256 = sha256(bytes),
                    lastModifiedMillis = 0L,
                    expectedLocalSha256 = null,
                    limits = CloudSyncLimits(),
                )
            }
        }
    }

    @Test
    fun coordinatorUploadsOnlyTheCanonicalReadingProgressObject() = runBlocking {
        val localBytes = payload(record("c", page = 12, updatedAt = 30))
        val bridge = FakeReaderProgressBridge(localBytes)
        val remote = FakeRemoteStore()
        val coordinator = coordinator(bridge, remote)

        val result = coordinator.sync(config())

        assertEquals(CloudSyncItemOutcome.UPLOADED, result.reports.single().outcome)
        assertEquals(listOf(KEY), remote.writeKeys)
        assertArrayEquals(localBytes, remote.bytes(KEY))
    }

    @Test
    fun uploadOnlyCoordinatorDoesNotReadOrMergeRemoteReadingProgress() = runBlocking {
        val localBytes = payload()
        val remoteBytes = payload(record("d", page = 4, updatedAt = 40))
        val bridge = FakeReaderProgressBridge(localBytes)
        val remote = FakeRemoteStore().apply { put(KEY, remoteBytes) }
        val coordinator = coordinator(bridge, remote)

        val result = coordinator.sync(config(CloudSyncDirection.UPLOAD_ONLY))

        assertEquals(CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED, result.reports.single().outcome)
        assertTrue(remote.readKeys.isEmpty())
        assertEquals(0, bridge.mergeCalls)
        assertArrayEquals(remoteBytes, remote.bytes(KEY))
    }

    private fun localStore(
        bridge: CloudSyncReaderProgressBridge,
    ): DiaryCloudSyncLocalStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return DiaryCloudSyncLocalStore(
            diaryRepository = DiaryFileRepository(context, FakeDiaryIndexDao()),
            settingsProvider = { AppSettings() },
            configId = CONFIG_ID,
            readerProgressBridge = bridge,
        )
    }

    private fun coordinator(
        bridge: CloudSyncReaderProgressBridge,
        remote: FakeRemoteStore,
    ): CloudSyncCoordinator {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return CloudSyncCoordinator(
            context = context,
            diaryRepository = DiaryFileRepository(context, FakeDiaryIndexDao()),
            settingsProvider = { AppSettings() },
            readerProgressBridge = bridge,
            remoteStoreFactory = FakeRemoteStoreFactory(remote),
            stateStore = FakeStateStore(),
        )
    }

    private fun config(
        direction: CloudSyncDirection = CloudSyncDirection.TWO_WAY,
    ) = CloudSyncConfig(
        id = CONFIG_ID,
        name = "Reader progress",
        endpointUrl = "https://sync.example.test/dav",
        selectedContents = setOf(CloudSyncContent.READING_PROGRESS),
        direction = direction,
    )

    private class FakeReaderProgressBridge(
        private var currentBytes: ByteArray,
    ) : CloudSyncReaderProgressBridge {
        var nextMergedBytes: ByteArray? = null
        var mergeCalls = 0
        var lastIncoming = byteArrayOf()

        override suspend fun snapshot(maxBytes: Long): CloudSyncReaderProgressSnapshot =
            snapshot(currentBytes, maxBytes)

        override suspend fun mergeIncoming(
            bytes: ByteArray,
            sha256: String,
            maxBytes: Long,
        ): CloudSyncReaderProgressSnapshot {
            check(com.deskcubby.app.data.sync.sha256(bytes) == sha256)
            mergeCalls += 1
            lastIncoming = bytes.copyOf()
            currentBytes = nextMergedBytes?.copyOf() ?: bytes.copyOf()
            return snapshot(currentBytes, maxBytes)
        }

        private fun snapshot(
            bytes: ByteArray,
            maxBytes: Long,
        ): CloudSyncReaderProgressSnapshot {
            check(bytes.size.toLong() <= maxBytes)
            val records = ReaderProgressJsonCodec.decode(bytes)
            return CloudSyncReaderProgressSnapshot(
                bytes = bytes.copyOf(),
                lastModifiedMillis = records.maxOfOrNull(ReaderProgressRecord::updatedAt) ?: 0L,
            )
        }
    }

    private class FakeRemoteStore : CloudSyncRemoteStore {
        private data class Entry(
            val bytes: ByteArray,
            val version: String,
        )

        private val entries = linkedMapOf<String, Entry>()
        private var version = 1
        val readKeys = mutableListOf<String>()
        val writeKeys = mutableListOf<String>()

        fun put(key: String, bytes: ByteArray) {
            entries[key] = Entry(bytes.copyOf(), "v${version++}")
        }

        fun bytes(key: String): ByteArray = checkNotNull(entries[key]).bytes.copyOf()

        override suspend fun list(prefixes: Set<String>): List<RemoteSyncObject> =
            entries.mapNotNull { (key, value) ->
                if (prefixes.none(key::startsWith)) null else value.toObject(key)
            }

        override suspend fun read(
            objectInfo: RemoteSyncObject,
            maxBytes: Long,
        ): ByteArray {
            readKeys += objectInfo.key
            return bytes(objectInfo.key)
        }

        override suspend fun write(
            key: String,
            bytes: ByteArray,
            contentSha256: String,
            lastModifiedMillis: Long,
            expectedRemoteVersion: String?,
        ): RemoteSyncObject {
            writeKeys += key
            val existing = entries[key]
            if (existing?.version != expectedRemoteVersion || sha256(bytes) != contentSha256) {
                throw CloudSyncConflictException()
            }
            val replacement = Entry(bytes.copyOf(), "v${version++}")
            entries[key] = replacement
            return replacement.toObject(key)
        }

        private fun Entry.toObject(key: String) = RemoteSyncObject(
            key = key,
            size = bytes.size.toLong(),
            lastModifiedMillis = 0L,
            sha256 = sha256(bytes),
            version = version,
            storageName = key,
        )
    }

    private class FakeRemoteStoreFactory(
        private val remote: CloudSyncRemoteStore,
    ) : CloudSyncRemoteStoreFactory {
        override fun create(
            config: CloudSyncConfig,
            limits: CloudSyncLimits,
            transferBudget: TransferBudget,
        ): CloudSyncRemoteStore = remote
    }

    private class FakeStateStore : CloudSyncStateStore {
        private var state: CloudSyncBaseState? = null

        override suspend fun load(configId: String): CloudSyncBaseState? = state

        override suspend fun save(configId: String, state: CloudSyncBaseState) {
            this.state = state
        }
    }

    private class FakeDiaryIndexDao : DiaryIndexDao {
        override fun observeAll(): Flow<List<DiaryIndexEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<DiaryIndexEntity> = emptyList()
        override suspend fun insertAll(items: List<DiaryIndexEntity>) = Unit
        override suspend fun deleteMissing(activeUris: List<String>) = Unit
        override suspend fun clear() = Unit
    }

    private fun payload(vararg records: ReaderProgressRecord): ByteArray =
        ReaderProgressJsonCodec.encode(records.toList())

    private fun record(
        character: String,
        page: Int,
        updatedAt: Long,
    ) = ReaderProgressRecord(
        fingerprint = character.repeat(64),
        type = ReaderBookType.PDF,
        pdfPageIndex = page,
        totalPages = 100,
        updatedAt = updatedAt,
    )

    private companion object {
        const val CONFIG_ID = "reading-progress-test"
        const val KEY = "reading/v1/progress.json"
    }
}
