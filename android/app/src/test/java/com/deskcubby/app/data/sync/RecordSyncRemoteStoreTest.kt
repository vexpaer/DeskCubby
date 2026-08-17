package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncContent
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordSyncRemoteStoreTest {

    private val content = CloudSyncContent.THOUGHTS
    private val limits = CloudSyncLimits()

    private fun payloadKey(id: String): String =
        content.remoteDirectory + "/" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8)) +
            ".json"

    private fun manifestKey(): String = "sync-meta/" + content.remoteDirectory + "/manifest.json"

    @Test
    fun `manifest load and payload reads reuse a single inventory listing`() = runBlocking {
        val store = FakeStore()
        store.seedPayload("r1", "one")
        store.seedManifest(listOf(entry("r1", "one")))
        val transport = RecordSyncRemoteStore(store, limits)

        val manifest = transport.loadManifest(content)
        assertEquals(listOf("r1"), manifest!!.entries.map { it.id })

        val bytes = transport.readPayload(content, "r1", sha256("one"))
        assertArrayEquals("one".toByteArray(StandardCharsets.UTF_8), bytes)

        assertEquals(1, store.listCalls)
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun `new record upload never performs an extra listing`() = runBlocking {
        val store = FakeStore()
        val transport = RecordSyncRemoteStore(store, limits)

        assertNull(transport.loadManifest(content))
        transport.writePayload(content, "r2", "two".toByteArray(), sha256("two"), remoteEntry = null)

        assertEquals(1, store.listCalls)
        assertEquals(1, store.writeCalls)
        assertEquals("two", String(store.objects.getValue(payloadKey("r2")), StandardCharsets.UTF_8))

        transport.saveManifest(content, listOf(entry("r2", "two")), expectedRemoteVersion = null)
        assertEquals(1, store.listCalls)
        assertEquals(2, store.writeCalls)
    }

    @Test
    fun `write is skipped when the manifest entry already matches the payload`() = runBlocking {
        val store = FakeStore()
        store.seedPayload("r1", "one")
        store.seedManifest(listOf(entry("r1", "one")))
        val transport = RecordSyncRemoteStore(store, limits)

        transport.loadManifest(content)
        transport.writePayload(content, "r1", "one".toByteArray(), sha256("one"), remoteEntry = entry("r1", "one"))

        assertEquals(1, store.listCalls)
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun `missing payload reports a conflict instead of a listing`() = runBlocking {
        val store = FakeStore()
        val transport = RecordSyncRemoteStore(store, limits)

        assertNull(transport.loadManifest(content))
        val threw = try {
            transport.readPayload(content, "missing", sha256("x"))
            false
        } catch (e: CloudSyncConflictException) {
            true
        }
        assertTrue(threw)
        assertEquals(1, store.listCalls)
    }

    @Test
    fun `overwriting a changed payload reuses the inventory version without relisting`() = runBlocking {
        val store = FakeStore()
        store.seedPayload("r1", "one")
        store.seedManifest(listOf(entry("r1", "one")))
        val transport = RecordSyncRemoteStore(store, limits)

        transport.loadManifest(content)
        transport.writePayload(content, "r1", "two".toByteArray(), sha256("two"), remoteEntry = entry("r1", "one"))

        assertEquals(1, store.listCalls)
        assertEquals(1, store.writeCalls)
        assertEquals("two", String(store.objects.getValue(payloadKey("r1")), StandardCharsets.UTF_8))
    }

    @Test
    fun `save manifest refreshes the cached inventory for this run`() = runBlocking {
        val store = FakeStore()
        val transport = RecordSyncRemoteStore(store, limits)

        transport.loadManifest(content)
        transport.writePayload(content, "r3", "three".toByteArray(), sha256("three"), remoteEntry = null)
        val saved = transport.saveManifest(content, listOf(entry("r3", "three")), expectedRemoteVersion = null)

        val reloaded = transport.loadManifest(content)
        assertEquals(saved.version, reloaded!!.version)
        assertEquals(listOf("r3"), reloaded.entries.map { it.id })
        assertEquals(1, store.listCalls)
    }

    private fun entry(id: String, text: String): RemoteRecordManifestEntry =
        RemoteRecordManifestEntry(id, 1L, 1L, false, sha256(text))

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private inner class FakeStore : CloudSyncRemoteStore {
        val objects = linkedMapOf<String, ByteArray>()
        val versions = linkedMapOf<String, String>()
        var listCalls = 0
        var writeCalls = 0
        private var counter = 0

        fun seedPayload(id: String, text: String) {
            val key = payloadKeyFor(id)
            objects[key] = text.toByteArray(StandardCharsets.UTF_8)
            versions[key] = nextVersion()
        }

        fun seedManifest(entries: List<RemoteRecordManifestEntry>) {
            val key = manifestKeyFor()
            objects[key] = RecordManifestCodec.encode(content, entries)
            versions[key] = nextVersion()
        }

        override suspend fun list(prefixes: Set<String>): List<RemoteSyncObject> {
            listCalls++
            return objects.entries.mapNotNull { (key, bytes) ->
                if (prefixes.none(key::startsWith)) return@mapNotNull null
                RemoteSyncObject(key, bytes.size.toLong(), 0L, sha256(bytes), versions.getValue(key), key)
            }
        }

        override suspend fun read(objectInfo: RemoteSyncObject, maxBytes: Long): ByteArray =
            objects.getValue(objectInfo.key).copyOf()

        override suspend fun write(
            key: String,
            bytes: ByteArray,
            contentSha256: String,
            lastModifiedMillis: Long,
            expectedRemoteVersion: String?,
        ): RemoteSyncObject {
            writeCalls++
            val existingVersion = versions[key]
            if (existingVersion != null && existingVersion != expectedRemoteVersion) {
                throw CloudSyncConflictException("stale precondition")
            }
            objects[key] = bytes.copyOf()
            val version = nextVersion()
            versions[key] = version
            return RemoteSyncObject(key, bytes.size.toLong(), lastModifiedMillis, contentSha256, version, key)
        }

        private fun nextVersion(): String {
            counter += 1
            return "v" + counter
        }

        private fun payloadKeyFor(id: String): String =
            content.remoteDirectory + "/" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(StandardCharsets.UTF_8)) +
                ".json"

        private fun manifestKeyFor(): String = "sync-meta/" + content.remoteDirectory + "/manifest.json"
    }

};