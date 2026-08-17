package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncContent
import com.deskcubby.app.data.model.CloudSyncDirection
import com.deskcubby.app.data.model.CloudSyncServiceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncEngineTest {
    @Test
    fun localOnlyObjectIsUploadedAndBecomesTheNewBase() = runBlocking {
        val local = FakeLocalStore().apply {
            put(KEY, LOCAL_BYTES)
        }
        val remote = FakeRemoteStore()
        val state = FakeStateStore()

        val result = engine(local, remote, state).sync(config())

        assertEquals(
            listOf(CloudSyncItemReport(KEY, CloudSyncItemOutcome.UPLOADED)),
            result.reports,
        )
        assertEquals(1, result.uploadedCount)
        assertEquals(LOCAL_BYTES.size.toLong(), result.transferredBytes)
        assertArrayEquals(LOCAL_BYTES, remote.bytes(KEY))
        assertEquals(null, remote.writeCalls.single().expectedRemoteVersion)
        assertEquals(hash(LOCAL_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun remoteOnlyObjectIsDownloadedAndBecomesTheNewBase() = runBlocking {
        val local = FakeLocalStore()
        val remote = FakeRemoteStore().apply {
            put(KEY, REMOTE_BYTES)
        }
        val state = FakeStateStore()

        val result = engine(local, remote, state).sync(config())

        assertEquals(
            listOf(CloudSyncItemReport(KEY, CloudSyncItemOutcome.DOWNLOADED)),
            result.reports,
        )
        assertEquals(1, result.downloadedCount)
        assertArrayEquals(REMOTE_BYTES, local.bytes(KEY))
        assertEquals(null, local.writeCalls.single().expectedLocalSha256)
        assertEquals(hash(REMOTE_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun localChangeSinceLastSyncUploadsWithTheScannedRemoteVersion() = runBlocking {
        val local = FakeLocalStore().apply {
            put(KEY, LOCAL_BYTES)
        }
        val remote = FakeRemoteStore().apply {
            put(KEY, BASE_BYTES, version = "remote-v7")
        }
        val state = FakeStateStore().apply {
            setBase(config(), KEY to hash(BASE_BYTES))
        }

        val result = engine(local, remote, state).sync(config())

        assertEquals(CloudSyncItemOutcome.UPLOADED, result.reports.single().outcome)
        assertArrayEquals(LOCAL_BYTES, remote.bytes(KEY))
        assertEquals("remote-v7", remote.writeCalls.single().expectedRemoteVersion)
        assertTrue(local.writeCalls.isEmpty())
        assertEquals(hash(LOCAL_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun remoteChangeSinceLastSyncDownloadsOnlyWhenLocalStillMatchesBase() = runBlocking {
        val local = FakeLocalStore().apply {
            put(KEY, BASE_BYTES)
        }
        val remote = FakeRemoteStore().apply {
            put(KEY, REMOTE_BYTES)
        }
        val state = FakeStateStore().apply {
            setBase(config(), KEY to hash(BASE_BYTES))
        }

        val result = engine(local, remote, state).sync(config())

        assertEquals(CloudSyncItemOutcome.DOWNLOADED, result.reports.single().outcome)
        assertArrayEquals(REMOTE_BYTES, local.bytes(KEY))
        assertEquals(hash(BASE_BYTES), local.writeCalls.single().expectedLocalSha256)
        assertTrue(remote.writeCalls.isEmpty())
        assertEquals(hash(REMOTE_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun changesOnBothSidesPreserveLocalAndSaveRemoteAsConflictCopy() = runBlocking {
        val local = FakeLocalStore().apply {
            put(KEY, LOCAL_BYTES)
        }
        val remote = FakeRemoteStore().apply {
            put(KEY, REMOTE_BYTES)
        }
        val state = FakeStateStore().apply {
            setBase(config(), KEY to hash(BASE_BYTES))
        }

        val result = engine(local, remote, state).sync(config())

        assertEquals(
            CloudSyncItemOutcome.CONFLICT_COPY_SAVED,
            result.reports.single().outcome,
        )
        assertEquals(1, result.conflictCount)
        assertArrayEquals(LOCAL_BYTES, local.bytes(KEY))
        assertArrayEquals(REMOTE_BYTES, local.conflictBytes.single())
        assertEquals(null, local.writeCalls.single().expectedLocalSha256)
        assertTrue(remote.writeCalls.isEmpty())
        assertEquals(hash(REMOTE_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun uploadOnlySkipsRemoteChangeWithoutReadingOrOverwritingEitherSide() = runBlocking {
        val uploadOnly = config(direction = CloudSyncDirection.UPLOAD_ONLY)
        val local = FakeLocalStore().apply {
            put(KEY, BASE_BYTES)
        }
        val remote = FakeRemoteStore().apply {
            put(KEY, REMOTE_BYTES)
        }
        val state = FakeStateStore().apply {
            setBase(uploadOnly, KEY to hash(BASE_BYTES))
        }

        val result = engine(local, remote, state).sync(uploadOnly)

        assertEquals(
            CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED,
            result.reports.single().outcome,
        )
        assertArrayEquals(BASE_BYTES, local.bytes(KEY))
        assertArrayEquals(REMOTE_BYTES, remote.bytes(KEY))
        assertTrue(local.writeCalls.isEmpty())
        assertTrue(remote.readCalls.isEmpty())
        assertTrue(remote.writeCalls.isEmpty())
        assertEquals(hash(BASE_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun forceUploadConditionallyReplacesRemoteButDoesNotDeleteRemoteOnlyObjects() = runBlocking {
        val remoteOnlyKey = "diaries/remote-only.md"
        val local = FakeLocalStore().apply {
            put(KEY, LOCAL_BYTES)
        }
        val remote = FakeRemoteStore().apply {
            put(KEY, REMOTE_BYTES, version = "remote-before-force")
            put(remoteOnlyKey, REMOTE_BYTES)
        }
        val state = FakeStateStore()

        val result = engine(local, remote, state).sync(
            config = config(),
            mode = CloudSyncRunMode.FORCE_UPLOAD,
        )

        assertArrayEquals(LOCAL_BYTES, remote.bytes(KEY))
        assertArrayEquals(REMOTE_BYTES, remote.bytes(remoteOnlyKey))
        assertEquals("remote-before-force", remote.writeCalls.single().expectedRemoteVersion)
        assertEquals(
            mapOf(
                KEY to CloudSyncItemOutcome.UPLOADED,
                remoteOnlyKey to CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED,
            ),
            result.reports.associate { it.key to it.outcome },
        )
    }

    @Test
    fun forceDownloadReplacesUnchangedLocalButDoesNotDeleteLocalOnlyObjects() = runBlocking {
        val localOnlyKey = "diaries/local-only.md"
        val local = FakeLocalStore().apply {
            put(KEY, LOCAL_BYTES)
            put(localOnlyKey, LOCAL_BYTES)
        }
        val remote = FakeRemoteStore().apply {
            put(KEY, REMOTE_BYTES)
        }
        val state = FakeStateStore()

        val result = engine(local, remote, state).sync(
            config = config(direction = CloudSyncDirection.UPLOAD_ONLY),
            mode = CloudSyncRunMode.FORCE_DOWNLOAD,
        )

        assertArrayEquals(REMOTE_BYTES, local.bytes(KEY))
        assertArrayEquals(LOCAL_BYTES, local.bytes(localOnlyKey))
        assertEquals(hash(LOCAL_BYTES), local.writeCalls.single().expectedLocalSha256)
        assertTrue(remote.writeCalls.isEmpty())
        assertEquals(
            mapOf(
                KEY to CloudSyncItemOutcome.DOWNLOADED,
                localOnlyKey to CloudSyncItemOutcome.REMOTE_CHANGE_SKIPPED,
            ),
            result.reports.associate { it.key to it.outcome },
        )
    }

    @Test
    fun firstSyncIdenticalBytesEstablishesBaseWithoutTransfers() = runBlocking {
        val local = FakeLocalStore().apply { put(KEY, LOCAL_BYTES) }
        val remote = FakeRemoteStore().apply { put(KEY, LOCAL_BYTES) }
        val state = FakeStateStore()

        val result = engine(local, remote, state).sync(config())

        assertEquals(CloudSyncItemOutcome.UNCHANGED, result.reports.single().outcome)
        assertTrue(local.writeCalls.isEmpty())
        assertTrue(remote.writeCalls.isEmpty())
        assertTrue(remote.readCalls.isEmpty())
        assertEquals(hash(LOCAL_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun firstSyncDifferentBytesPrefersExistingCloudAndDoesNotRepeatConflict() = runBlocking {
        val local = FakeLocalStore().apply { put(KEY, LOCAL_BYTES) }
        val remote = FakeRemoteStore().apply { put(KEY, REMOTE_BYTES) }
        val state = FakeStateStore()

        val first = engine(local, remote, state).sync(config())
        assertEquals(CloudSyncItemOutcome.CONFLICT_COPY_SAVED, first.reports.single().outcome)
        assertArrayEquals(LOCAL_BYTES, local.bytes(KEY))
        assertArrayEquals(REMOTE_BYTES, local.conflictBytes.single())

        val second = engine(local, remote, state).sync(config())
        assertEquals(CloudSyncItemOutcome.UPLOADED, second.reports.first { it.key == KEY }.outcome)
    }

    @Test
    fun sameConflictIsNotRepeatedOnTheFollowingRun() = runBlocking {
        val local = FakeLocalStore().apply { put(KEY, LOCAL_BYTES) }
        val remote = FakeRemoteStore().apply { put(KEY, REMOTE_BYTES) }
        val state = FakeStateStore().apply { setBase(config(), KEY to hash(BASE_BYTES)) }

        assertEquals(
            CloudSyncItemOutcome.CONFLICT_COPY_SAVED,
            engine(local, remote, state).sync(config()).reports.single().outcome,
        )
        val second = engine(local, remote, state).sync(config())
        assertEquals(CloudSyncItemOutcome.UPLOADED, second.reports.first { it.key == KEY }.outcome)
        assertArrayEquals(LOCAL_BYTES, remote.bytes(KEY))
    }

    @Test
    fun newDeviceJoinsExistingCloudWithDifferentLocalFileKeepsBothVersions() = runBlocking {
        val existingRemote = FakeRemoteStore().apply { put(KEY, REMOTE_BYTES) }
        val local = FakeLocalStore().apply { put(KEY, LOCAL_BYTES) }
        val state = FakeStateStore()

        val result = engine(local, existingRemote, state).sync(config())

        assertEquals(CloudSyncItemOutcome.CONFLICT_COPY_SAVED, result.reports.single().outcome)
        assertArrayEquals(LOCAL_BYTES, local.bytes(KEY))
        assertArrayEquals(REMOTE_BYTES, local.conflictBytes.single())
        assertArrayEquals(REMOTE_BYTES, existingRemote.bytes(KEY))
        assertEquals(hash(REMOTE_BYTES), state.saved(CONFIG_ID)?.hashesByKey?.get(KEY))
    }

    @Test
    fun httpEndpointRequiresAnExplicitOptInBeforeTheFactoryIsUsed() {
        val local = FakeLocalStore()
        val remote = FakeRemoteStore()
        val state = FakeStateStore()
        val factory = FakeRemoteStoreFactory(remote)
        val unsafeConfig = config().copy(endpointUrl = "http://192.168.1.20/dav")

        val error = assertThrows(CloudSyncConfigurationException::class.java) {
            runBlocking {
                CloudSyncEngine(local, factory, state).sync(unsafeConfig)
            }
        }

        assertTrue(error.message.orEmpty().contains("HTTP"))
        assertEquals(0, factory.createCalls)
    }

    @Test
    fun configStringRedactsEveryCredential() {
        val config = config().copy(
            serviceType = CloudSyncServiceType.S3_COMPATIBLE,
            webDavUsername = "private-user",
            webDavPassword = "private-password",
            s3Bucket = "backup-bucket",
            s3AccessKey = "private-access-key",
            s3SecretKey = "private-secret-key",
            s3SessionToken = "private-session-token",
        )

        val rendered = config.toString()

        listOf(
            "private-user",
            "private-password",
            "private-access-key",
            "private-secret-key",
            "private-session-token",
        ).forEach { secret ->
            assertFalse("$secret leaked from CloudSyncConfig.toString()", rendered.contains(secret))
        }
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains("backup-bucket"))
    }

    private fun engine(
        local: FakeLocalStore,
        remote: FakeRemoteStore,
        state: FakeStateStore,
    ): CloudSyncEngine =
        CloudSyncEngine(local, FakeRemoteStoreFactory(remote), state)

    private fun config(
        direction: CloudSyncDirection = CloudSyncDirection.TWO_WAY,
    ): CloudSyncConfig =
        CloudSyncConfig(
            id = CONFIG_ID,
            name = "Test sync",
            endpointUrl = "https://sync.example.test/dav",
            selectedContents = setOf(CloudSyncContent.DIARIES),
            direction = direction,
        )

    private class FakeLocalStore : CloudSyncLocalStore {
        private val entries = linkedMapOf<String, LocalEntry>()
        val writeCalls = mutableListOf<LocalWriteCall>()
        val conflictBytes = mutableListOf<ByteArray>()

        fun put(
            key: String,
            bytes: ByteArray,
            content: CloudSyncContent = CloudSyncContent.DIARIES,
            lastModifiedMillis: Long = 1_000L,
        ) {
            entries[key] = LocalEntry(bytes.copyOf(), content, lastModifiedMillis)
        }

        fun bytes(key: String): ByteArray =
            assertNotNull(entries[key]).let { checkNotNull(entries[key]).bytes.copyOf() }

        override suspend fun list(
            selectedContents: Set<CloudSyncContent>,
            limits: CloudSyncLimits,
        ): List<LocalSyncObject> =
            entries.mapNotNull { (key, entry) ->
                entry.takeIf { it.content in selectedContents }?.toObject(key)
            }

        override suspend fun read(
            objectInfo: LocalSyncObject,
            maxBytes: Long,
        ): ByteArray =
            checkNotNull(entries[objectInfo.key]).bytes.copyOf()

        override suspend fun writeRemote(
            key: String,
            bytes: ByteArray,
            contentSha256: String,
            lastModifiedMillis: Long,
            expectedLocalSha256: String?,
            limits: CloudSyncLimits,
        ): LocalWriteResult {
            writeCalls += LocalWriteCall(key, expectedLocalSha256)
            check(bytes.size.toLong() <= limits.maxObjectBytes)
            check(hash(bytes) == contentSha256)
            val existing = entries[key]
            if (existing?.hash == expectedLocalSha256) {
                val replacement = LocalEntry(
                    bytes = bytes.copyOf(),
                    content = contentForKey(key),
                    lastModifiedMillis = lastModifiedMillis,
                )
                entries[key] = replacement
                return LocalWriteResult.Applied(replacement.toObject(key))
            }

            checkNotNull(existing)
            val conflictKey = "$key.remote-conflict"
            val conflict = LocalEntry(
                bytes = bytes.copyOf(),
                content = contentForKey(key),
                lastModifiedMillis = lastModifiedMillis,
            )
            entries[conflictKey] = conflict
            conflictBytes += bytes.copyOf()
            return LocalWriteResult.ConflictCopy(
                existing = existing.toObject(key),
                copy = conflict.toObject(conflictKey),
            )
        }
    }

    private data class LocalEntry(
        val bytes: ByteArray,
        val content: CloudSyncContent,
        val lastModifiedMillis: Long,
    ) {
        val hash: String
            get() = sha256(bytes)

        fun toObject(key: String): LocalSyncObject =
            LocalSyncObject(
                key = key,
                content = content,
                size = bytes.size.toLong(),
                lastModifiedMillis = lastModifiedMillis,
                sha256 = hash,
                localId = "content://fake/$key",
            )
    }

    private data class LocalWriteCall(
        val key: String,
        val expectedLocalSha256: String?,
    )

    private class FakeRemoteStore : CloudSyncRemoteStore {
        private val entries = linkedMapOf<String, RemoteEntry>()
        private var nextVersion = 1
        var transferBudget: TransferBudget? = null
        val readCalls = mutableListOf<String>()
        val writeCalls = mutableListOf<RemoteWriteCall>()

        fun put(
            key: String,
            bytes: ByteArray,
            lastModifiedMillis: Long = 2_000L,
            version: String = "remote-v${nextVersion++}",
        ) {
            entries[key] = RemoteEntry(bytes.copyOf(), lastModifiedMillis, version)
        }

        fun bytes(key: String): ByteArray =
            assertNotNull(entries[key]).let { checkNotNull(entries[key]).bytes.copyOf() }

        override suspend fun list(prefixes: Set<String>): List<RemoteSyncObject> =
            entries.mapNotNull { (key, entry) ->
                entry.takeIf { prefixes.any(key::startsWith) }?.toObject(key)
            }

        override suspend fun read(
            objectInfo: RemoteSyncObject,
            maxBytes: Long,
        ): ByteArray {
            readCalls += objectInfo.key
            return checkNotNull(entries[objectInfo.key]).bytes.copyOf().also { bytes ->
                transferBudget?.reserve(bytes.size.toLong())
            }
        }

        override suspend fun write(
            key: String,
            bytes: ByteArray,
            contentSha256: String,
            lastModifiedMillis: Long,
            expectedRemoteVersion: String?,
        ): RemoteSyncObject {
            writeCalls += RemoteWriteCall(key, expectedRemoteVersion)
            transferBudget?.reserve(bytes.size.toLong())
            val existing = entries[key]
            if (existing?.version != expectedRemoteVersion) {
                throw CloudSyncConflictException("Fake conditional remote write failed")
            }
            check(hash(bytes) == contentSha256)
            val replacement = RemoteEntry(
                bytes = bytes.copyOf(),
                lastModifiedMillis = lastModifiedMillis,
                version = "written-v${nextVersion++}",
            )
            entries[key] = replacement
            return replacement.toObject(key)
        }
    }

    private data class RemoteEntry(
        val bytes: ByteArray,
        val lastModifiedMillis: Long,
        val version: String,
    ) {
        fun toObject(key: String): RemoteSyncObject =
            RemoteSyncObject(
                key = key,
                size = bytes.size.toLong(),
                lastModifiedMillis = lastModifiedMillis,
                sha256 = sha256(bytes),
                version = version,
                storageName = "fake/$key",
            )
    }

    private data class RemoteWriteCall(
        val key: String,
        val expectedRemoteVersion: String?,
    )

    private class FakeRemoteStoreFactory(
        private val store: CloudSyncRemoteStore,
    ) : CloudSyncRemoteStoreFactory {
        var createCalls: Int = 0
            private set

        override fun create(
            config: CloudSyncConfig,
            limits: CloudSyncLimits,
            transferBudget: TransferBudget,
        ): CloudSyncRemoteStore {
            createCalls += 1
            (store as? FakeRemoteStore)?.transferBudget = transferBudget
            return store
        }
    }

    private class FakeStateStore : CloudSyncStateStore {
        private val states = mutableMapOf<String, CloudSyncBaseState>()

        fun setBase(
            config: CloudSyncConfig,
            vararg hashes: Pair<String, String>,
        ) {
            states[config.id] = CloudSyncBaseState(
                scopeFingerprint = config.validateForSync().scopeFingerprint,
                hashesByKey = mapOf(*hashes),
            )
        }

        fun saved(configId: String): CloudSyncBaseState? = states[configId]

        override suspend fun load(configId: String): CloudSyncBaseState? =
            states[configId]

        override suspend fun save(configId: String, state: CloudSyncBaseState) {
            states[configId] = state.copy(hashesByKey = state.hashesByKey.toMap())
        }
    }

    private companion object {
        const val CONFIG_ID = "sync-test"
        const val KEY = "diaries/2026-07-24.md"
        val BASE_BYTES = "base".toByteArray()
        val LOCAL_BYTES = "local edit".toByteArray()
        val REMOTE_BYTES = "remote edit".toByteArray()

        fun hash(bytes: ByteArray): String = sha256(bytes)

        fun contentForKey(key: String): CloudSyncContent =
            CloudSyncContent.entries.first { key.startsWith("${it.remoteDirectory}/") }
    }
}
