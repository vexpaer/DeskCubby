package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSyncEngineTest {
    @Test
    fun `create record uploads payload and manifest then becomes unchanged`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY)
        fixture.adapter.put("local-1", record("r1", 10L, "hello"))

        val first = fixture.sync()
        assertEquals(CloudSyncItemOutcome.UPLOADED, first.singleOutcome(seedId("hello")))
        assertTrue(fixture.remote.payloads.containsKey(fixture.payloadKey(fixture.content, seedId("hello"))))
        assertEquals(1, fixture.remote.manifests[fixture.manifestKey()]!!.entries.size)

        val second = fixture.sync()
        assertEquals(CloudSyncItemOutcome.UNCHANGED, second.singleOutcome(seedId("hello")))
    }

    @Test
    fun `update record uploads only that record`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY)
        fixture.adapter.put("local-1", record("r1", 10L, "v1"))
        fixture.adapter.put("local-2", record("r2", 10L, "v2"))
        fixture.sync()

        val firstId = seedId("v1")
        val secondId = seedId("v2")
        fixture.adapter.put("local-1", record("r1", 20L, "v1 updated"))
        val result = fixture.sync()
        assertEquals(CloudSyncItemOutcome.UPLOADED, result.singleOutcome(firstId))
        assertEquals(CloudSyncItemOutcome.UNCHANGED, result.singleOutcome(secondId))
        assertEquals("v1 updated", fixture.remote.manifestEntry(firstId).payloadText(fixture))
    }

    @Test
    fun `remote create and remote update are applied deterministically`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.LWW)
        fixture.remote.seed(fixture.content, "remote-1", record("remote-1", 30L, "remote v1"))
        fixture.sync()
        assertEquals("remote v1", fixture.adapter.records.values.first { it.payloadText() == "remote v1" }.payloadText())

        fixture.remote.seed(fixture.content, "remote-1", record("remote-1", 40L, "remote v2"))
        val result = fixture.sync()
        assertEquals(CloudSyncItemOutcome.DOWNLOADED, result.singleOutcome("remote-1"))
        assertEquals("remote v2", fixture.adapter.records.values.first { it.payloadText() == "remote v2" }.payloadText())
    }

    @Test
    fun `different records merge without a conflict`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY)
        fixture.adapter.put("local-a", record("a", 1L, "phone A"))
        fixture.remote.seed(fixture.content, "b", record("b", 2L, "pad B"))

        val result = fixture.sync()
        assertEquals(CloudSyncItemOutcome.UPLOADED, result.singleOutcome(seedId("phone A")))
        assertEquals(CloudSyncItemOutcome.DOWNLOADED, result.singleOutcome("b"))
        assertEquals(0, result.conflictCount)
    }

    @Test
    fun `same record concurrent update uses conflict copy policy exactly once`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY)
        fixture.adapter.put("local-1", record("r1", 10L, "base"))
        fixture.sync()
        val id = seedId("base")
        fixture.adapter.put("local-1", record("r1", 20L, "phone"))
        fixture.remote.seed(fixture.content, id, record(id, 21L, "pad"))

        val first = fixture.sync()
        assertEquals(CloudSyncItemOutcome.CONFLICT_COPY_SAVED, first.singleOutcome(id))
        assertEquals(1, first.conflictCount)
        assertEquals("pad", fixture.adapter.records[fixture.adapterKeyForSyncId(id)]!!.payloadText())
        assertTrue(fixture.adapter.records.values.any { it.payloadText() == "phone" })

        val second = fixture.sync()
        assertEquals(0, second.conflictCount)
        assertEquals(CloudSyncItemOutcome.UNCHANGED, second.singleOutcome(id))
    }

    @Test
    fun `delete publishes a tombstone and offline record cannot resurrect it`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.LWW)
        fixture.adapter.put("local-1", record("r1", 10L, "to delete"))
        fixture.sync()

        val id = seedId("to delete")
        fixture.adapter.remove("local-1")
        val deletionRun = fixture.sync()
        assertEquals(CloudSyncItemOutcome.UPLOADED, deletionRun.singleOutcome(id))
        assertTrue(fixture.remote.manifestEntry(id).deleted)

        val offline = Fixture(policy = RecordConflictPolicy.LWW, remote = fixture.remote)
        offline.adapter.put("offline-1", record("r1", 10L, "to delete"))
        val offlineResult = offline.sync()
        println("OFFLINE_DEBUG ${offline.adapter.records.values.map{it.payloadText()}}")
        assertFalse(offline.adapter.records.values.any { it.payloadText() == "to delete" })
        assertEquals(CloudSyncItemOutcome.DOWNLOADED, offlineResult.singleOutcome(id))
        assertTrue(offline.remote.manifestEntry(id).deleted)

        val third = offline.sync()
        assertEquals(0, third.conflictCount)
        assertTrue(offline.remote.manifestEntry(id).deleted)
    }

    @Test
    fun `new device bootstrap merges different ids and identical content`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY)
        fixture.adapter.put("cloud-record", record("shared", 10L, "same"))
        fixture.sync()

        val newDevice = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY, remote = fixture.remote)
        newDevice.adapter.put("new-local", record("shared", 10L, "same"))
        newDevice.adapter.put("another-local", record("another", 11L, "new text"))
        val result = newDevice.sync()
        assertEquals(CloudSyncItemOutcome.UNCHANGED, result.singleOutcome(seedId("same")))
        assertEquals(CloudSyncItemOutcome.UPLOADED, result.singleOutcome(seedId("new text")))
        assertTrue(newDevice.adapter.records.values.any { it.payloadText() == "same" })
    }

    @Test
    fun `new device bootstrap with different same-id content keeps both sides`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY)
        fixture.adapter.put("cloud-record", record("shared", 10L, "cloud text"))
        fixture.sync()

        val newDevice = Fixture(policy = RecordConflictPolicy.CONFLICT_COPY, remote = fixture.remote)
        newDevice.adapter.put("new-local", record("shared", 10L, "local text"))
        val result = newDevice.sync()
        assertEquals(0, result.conflictCount)
        assertTrue(newDevice.adapter.records.values.any { it.payloadText() == "cloud text" })
        assertTrue(newDevice.adapter.records.values.any { it.payloadText() == "local text" })
    }

    @Test
    fun `LWW conflict converges in one extra run`() = runBlocking {
        val fixture = Fixture(policy = RecordConflictPolicy.LWW)
        fixture.adapter.put("local-1", record("r1", 10L, "base"))
        fixture.sync()
        val id = seedId("base")
        fixture.adapter.put("local-1", record("r1", 20L, "phone newer"))
        fixture.remote.seed(fixture.content, id, record(id, 21L, "pad newest"))

        assertEquals(CloudSyncItemOutcome.DOWNLOADED, fixture.sync().singleOutcome(id))
        assertEquals(CloudSyncItemOutcome.UNCHANGED, fixture.sync().singleOutcome(id))
        assertEquals("pad newest", fixture.adapter.records.values.first { it.payloadText() == "pad newest" }.payloadText())
    }

    private fun SyncRecord.payloadText(): String = payload.toString(Charsets.UTF_8)

    private fun CloudSyncRunResult.singleOutcome(id: String): CloudSyncItemOutcome =
        reports.first { it.key.endsWith("/$id") }.outcome

    private fun record(id: String, revision: Long, text: String): SyncRecord =
        SyncRecord(id, revision, revision, false, text.toByteArray())

    private fun seedId(text: String): String = "seed-${sha256(text.toByteArray())}"

    private fun RemoteRecordManifestEntry.payloadText(fixture: Fixture): String =
        fixture.remote.payloads.getValue(fixture.payloadKey(fixture.content, id)).toString(Charsets.UTF_8)

    private class Fixture(
        policy: RecordConflictPolicy,
        val remote: FakeRecordRemoteStore = FakeRecordRemoteStore(),
    ) {
        val content = CloudSyncContent.THOUGHTS
        val adapter = FakeRecordAdapter(content, policy)
        val stateStore = FakeRecordStateStore()
        val config = CloudSyncConfig(
            id = "config-1",
            name = "Test",
            endpointUrl = "https://sync.example.test/dav",
            serviceType = CloudSyncServiceType.WEBDAV,
            selectedContents = setOf(content),
            direction = CloudSyncDirection.TWO_WAY,
        )

        suspend fun sync(): CloudSyncRunResult {
            val engine = RecordSyncEngine(mapOf(content to adapter), stateStore)
            return CloudSyncEngine(
                localStore = EmptyLocalStore(),
                remoteStoreFactory = FakeRemoteStoreFactory(remote),
                stateStore = object : CloudSyncStateStore {
                    override suspend fun load(configId: String): CloudSyncBaseState? = null
                    override suspend fun save(configId: String, state: CloudSyncBaseState) {}
                },
                recordSyncEngine = engine,
            ).sync(config)
        }

        fun manifestKey(): String = "sync-meta/${content.remoteDirectory}/manifest.json"

        fun payloadKey(content: CloudSyncContent, id: String): String =
            "${content.remoteDirectory}/${
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())
            }.json"

        fun adapterKeyForSyncId(id: String): String {
            val mapping = stateStore.saved?.entries?.values?.firstOrNull { it.id == id }
            return mapping?.localKey ?: adapter.records.keys.first { key ->
                adapter.records[key]?.payload?.toString(Charsets.UTF_8) ==
                    remote.payloads.getValue(payloadKey(content, id)).toString(Charsets.UTF_8)
            }
        }
    }

    private class EmptyLocalStore : CloudSyncLocalStore {
        override suspend fun list(selectedContents: Set<CloudSyncContent>, limits: CloudSyncLimits): List<LocalSyncObject> = emptyList()
        override suspend fun read(objectInfo: LocalSyncObject, maxBytes: Long): ByteArray = byteArrayOf()
        override suspend fun writeRemote(key: String, bytes: ByteArray, contentSha256: String, lastModifiedMillis: Long, expectedLocalSha256: String?, limits: CloudSyncLimits): LocalWriteResult = error("unused")
    }

    private class FakeRecordAdapter(
        override val contentType: CloudSyncContent,
        override val conflictPolicy: RecordConflictPolicy,
    ) : RecordSyncAdapter {
        val records = linkedMapOf<String, SyncRecord>()
        private var nextKey = 1L

        fun put(localKey: String, record: SyncRecord) {
            records[localKey] = record
        }

        fun remove(localKey: String) {
            records.remove(localKey)
        }

        override suspend fun listLocalRecords(): List<LocalRecordRef> =
            records.map { (key, record) -> LocalRecordRef(key, record.revision, record.updatedAt) }

        override suspend fun readLocalRecord(localKey: String): SyncRecord =
            records[localKey] ?: error("missing $localKey")

        override suspend fun applyRemoteRecord(
            record: SyncRecord,
            preserveLocalConflict: SyncRecord?,
            preserveLocalKey: String?,
        ): RecordApplyResult {
            if (preserveLocalKey != null) records[preserveLocalKey] = record
            val canonicalKey = preserveLocalKey ?: "canonical-${nextKey++}"
            if (!records.containsKey(canonicalKey)) records[canonicalKey] = record
            val copyKey = if (preserveLocalConflict != null) {
                val key = "copy-${nextKey++}"
                records[key] = preserveLocalConflict
                key
            } else {
                null
            }
            return RecordApplyResult(canonicalKey, copyKey)
        }

        override suspend fun deleteLocalRecord(localKey: String) {
            records.remove(localKey)
        }
    }

    private class FakeRecordStateStore : RecordSyncStateStore {
        var saved: RecordSyncContentState? = null

        override suspend fun load(configId: String, contentType: CloudSyncContent): RecordSyncContentState? = saved

        override suspend fun save(configId: String, state: RecordSyncContentState) {
            saved = state
        }
    }

    private class FakeRecordRemoteStore : CloudSyncRemoteStore {
        val payloads = linkedMapOf<String, ByteArray>()
        val manifests = linkedMapOf<String, RemoteRecordManifest>()
        private var version = 1

        fun seed(content: CloudSyncContent, id: String, record: SyncRecord) {
            val key = payloadKey(content, id)
            payloads[key] = record.payload
            val current = manifests[manifestKey(content)]?.entries.orEmpty().toMutableList()
            current.removeAll { it.id == id }
            current += RemoteRecordManifestEntry(id, record.revision, record.updatedAt, record.deleted, record.payloadSha256)
            manifests[manifestKey(content)] = RemoteRecordManifest("v${version++}", content, current)
        }

        fun manifestEntry(id: String): RemoteRecordManifestEntry =
            manifests.values.first().entries.first { it.id == id }

        private fun manifestKey(content: CloudSyncContent) = "sync-meta/${content.remoteDirectory}/manifest.json"

        private fun payloadKey(content: CloudSyncContent, id: String) =
            "${content.remoteDirectory}/${
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray())
            }.json"

        override suspend fun list(prefixes: Set<String>): List<RemoteSyncObject> =
            (payloads.keys + manifests.keys).mapNotNull { key ->
                if (prefixes.none(key::startsWith)) return@mapNotNull null
                val bytes = payloads[key] ?: RecordManifestCodec.encode(
                    manifests.getValue(key).contentType,
                    manifests.getValue(key).entries,
                )
                RemoteSyncObject(key, bytes.size.toLong(), 0L, sha256(bytes), versionFor(key), key)
            }

        private fun versionFor(key: String): String =
            if (key.endsWith("/manifest.json")) manifests[key]?.version ?: "absent"
            else "payload-v1"

        override suspend fun read(objectInfo: RemoteSyncObject, maxBytes: Long): ByteArray =
            payloads[objectInfo.key] ?: RecordManifestCodec.encode(
                manifests.getValue(objectInfo.key).contentType,
                manifests.getValue(objectInfo.key).entries,
            )

        override suspend fun write(key: String, bytes: ByteArray, contentSha256: String, lastModifiedMillis: Long, expectedRemoteVersion: String?): RemoteSyncObject {
            val exists = payloads.containsKey(key) || manifests.containsKey(key)
            val existingVersion = if (exists) versionFor(key) else null
            if (existingVersion != null && existingVersion != expectedRemoteVersion) {
                throw CloudSyncConflictException("conditional write")
            }
            if (key.endsWith("/manifest.json")) {
                val dir = "records/" + key.substringAfter("sync-meta/records/").substringBefore("/manifest.json")
                val content = CloudSyncContent.entries.first { it.remoteDirectory == dir }
                val manifest = RecordManifestCodec.decode(bytes, content)
                manifests[key] = manifest.copy(version = "v${version++}")
            } else {
                payloads[key] = bytes.copyOf()
            }
            return RemoteSyncObject(key, bytes.size.toLong(), lastModifiedMillis, sha256(bytes), "v${version++}", key)
        }
    }

    private class FakeRemoteStoreFactory(
        private val store: CloudSyncRemoteStore,
    ) : CloudSyncRemoteStoreFactory {
        override fun create(config: CloudSyncConfig, limits: CloudSyncLimits, transferBudget: TransferBudget): CloudSyncRemoteStore = store
    }
}
