package com.deskcubby.app.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestRemoteStoreTest {
    @Test
    fun `initial write is published through the manifest and can be listed and read`() =
        runBlocking {
            val transport = InMemoryConditionalBlobTransport()
            val store = ManifestRemoteStore(transport, limits())
            val bytes = "first diary".toByteArray()

            val written = store.write(
                key = DIARY_KEY,
                bytes = bytes,
                contentSha256 = sha256(bytes),
                lastModifiedMillis = 12_345L,
                expectedRemoteVersion = null,
            )

            assertEquals(DIARY_KEY, written.key)
            assertEquals(bytes.size.toLong(), written.size)
            assertEquals(12_345L, written.lastModifiedMillis)
            assertEquals(sha256(bytes), written.sha256)
            assertEquals(listOf(written), store.list(setOf("diaries")))
            assertArrayEquals(bytes, store.read(written, TEST_MAX_OBJECT_BYTES))

            val successfulPuts = transport.successfulPuts
            assertEquals(2, successfulPuts.size)
            assertEquals(written.storageName, successfulPuts[0].storageName)
            assertEquals(BlobWriteCondition.MustNotExist, successfulPuts[0].condition)
            assertEquals(MANIFEST_STORAGE_NAME, successfulPuts[1].storageName)
            assertEquals(BlobWriteCondition.MustNotExist, successfulPuts[1].condition)
        }

    @Test
    fun `updates keep immutable payloads and conditionally replace only the manifest`() =
        runBlocking {
            val transport = InMemoryConditionalBlobTransport()
            val store = ManifestRemoteStore(transport, limits())
            val firstBytes = "first".toByteArray()
            val first = store.write(
                key = DIARY_KEY,
                bytes = firstBytes,
                contentSha256 = sha256(firstBytes),
                lastModifiedMillis = 1_000L,
                expectedRemoteVersion = null,
            )
            val firstManifestVersion = transport.successfulPuts
                .single { it.storageName == MANIFEST_STORAGE_NAME }
                .returnedVersion
            val updatedBytes = "updated".toByteArray()

            val updated = store.write(
                key = DIARY_KEY,
                bytes = updatedBytes,
                contentSha256 = sha256(updatedBytes),
                lastModifiedMillis = 2_000L,
                expectedRemoteVersion = first.version,
            )

            assertNotEquals(first.storageName, updated.storageName)
            assertTrue(transport.contains(first.storageName))
            assertTrue(transport.contains(updated.storageName))
            assertArrayEquals(firstBytes, transport.storedBytes(first.storageName))
            assertArrayEquals(updatedBytes, transport.storedBytes(updated.storageName))
            assertTrue(
                transport.successfulPuts
                    .filter { it.storageName != MANIFEST_STORAGE_NAME }
                    .all { it.condition == BlobWriteCondition.MustNotExist },
            )
            assertEquals(
                BlobWriteCondition.MustMatch(firstManifestVersion),
                transport.successfulPuts
                    .last { it.storageName == MANIFEST_STORAGE_NAME }
                    .condition,
            )
            assertEquals(listOf(updated), store.list(setOf("diaries")))
            assertArrayEquals(updatedBytes, store.read(updated, TEST_MAX_OBJECT_BYTES))
        }

    @Test
    fun `a manifest race leaves an orphan payload but does not publish the losing update`() =
        runBlocking {
            val transport = InMemoryConditionalBlobTransport()
            val seedStore = ManifestRemoteStore(transport, limits())
            val seedBytes = "seed".toByteArray()
            seedStore.write(
                key = DIARY_KEY,
                bytes = seedBytes,
                contentSha256 = sha256(seedBytes),
                lastModifiedMillis = 1_000L,
                expectedRemoteVersion = null,
            )
            val seedManifestVersion = transport.successfulPuts
                .single { it.storageName == MANIFEST_STORAGE_NAME }
                .returnedVersion

            val winnerStore = ManifestRemoteStore(transport, limits())
            val loserStore = ManifestRemoteStore(transport, limits())
            val winnerBase = winnerStore.list(setOf("diaries")).single()
            val loserBase = loserStore.list(setOf("diaries")).single()
            val winnerBytes = "winner".toByteArray()
            val winner = winnerStore.write(
                key = DIARY_KEY,
                bytes = winnerBytes,
                contentSha256 = sha256(winnerBytes),
                lastModifiedMillis = 2_000L,
                expectedRemoteVersion = winnerBase.version,
            )
            val loserBytes = "loser".toByteArray()

            expectThrows(CloudSyncConflictException::class.java) {
                loserStore.write(
                    key = DIARY_KEY,
                    bytes = loserBytes,
                    contentSha256 = sha256(loserBytes),
                    lastModifiedMillis = 3_000L,
                    expectedRemoteVersion = loserBase.version,
                )
            }

            val loserPayloadPut = transport.successfulPuts.single {
                it.storageName != MANIFEST_STORAGE_NAME && it.sha256 == sha256(loserBytes)
            }
            assertTrue(transport.contains(loserPayloadPut.storageName))
            assertArrayEquals(loserBytes, transport.storedBytes(loserPayloadPut.storageName))
            assertEquals(
                BlobWriteCondition.MustMatch(seedManifestVersion),
                transport.putAttempts
                    .last { it.storageName == MANIFEST_STORAGE_NAME }
                    .condition,
            )
            assertEquals(
                2,
                transport.successfulPuts.count { it.storageName == MANIFEST_STORAGE_NAME },
            )

            val freshStore = ManifestRemoteStore(transport, limits())
            val stillPublished = freshStore.list(setOf("diaries")).single()
            assertEquals(winner, stillPublished)
            assertArrayEquals(winnerBytes, freshStore.read(stillPublished, TEST_MAX_OBJECT_BYTES))
            assertFalse(stillPublished.sha256 == sha256(loserBytes))
        }

    @Test
    fun `corrupt payloads and tampered object metadata are rejected`() = runBlocking {
        val transport = InMemoryConditionalBlobTransport()
        val store = ManifestRemoteStore(transport, limits())
        val bytes = "trusted".toByteArray()
        val written = store.write(
            key = DIARY_KEY,
            bytes = bytes,
            contentSha256 = sha256(bytes),
            lastModifiedMillis = 1_000L,
            expectedRemoteVersion = null,
        )
        transport.tamperBytes(written.storageName, "corrupt".toByteArray())

        expectThrows(CloudSyncConflictException::class.java) {
            store.read(written, TEST_MAX_OBJECT_BYTES)
        }
        expectThrows(CloudSyncException::class.java) {
            store.read(
                written.copy(
                    sha256 = sha256("forged".toByteArray()),
                    storageName = ".deskcubby-object-forged",
                ),
                TEST_MAX_OBJECT_BYTES,
            )
        }
        Unit
    }

    @Test
    fun `object and manifest count limits fail before publishing extra data`() = runBlocking {
        val transport = InMemoryConditionalBlobTransport()
        val limitedStore = ManifestRemoteStore(
            transport = transport,
            limits = limits(maxObjectBytes = 4L, maxObjects = 1),
        )
        val boundaryBytes = "four".toByteArray()
        val boundary = limitedStore.write(
            key = DIARY_KEY,
            bytes = boundaryBytes,
            contentSha256 = sha256(boundaryBytes),
            lastModifiedMillis = 1_000L,
            expectedRemoteVersion = null,
        )

        assertEquals(
            listOf(boundary),
            ManifestRemoteStore(
                transport = transport,
                limits = limits(maxObjectBytes = 4L, maxObjects = 1),
            ).list(setOf("diaries")),
        )
        val successfulPutCountAtBoundary = transport.successfulPuts.size

        val oversizedBytes = "large".toByteArray()
        expectThrows(CloudSyncLimitException::class.java) {
            limitedStore.write(
                key = "diaries/too-large.md",
                bytes = oversizedBytes,
                contentSha256 = sha256(oversizedBytes),
                lastModifiedMillis = 2_000L,
                expectedRemoteVersion = null,
            )
        }
        expectThrows(CloudSyncLimitException::class.java) {
            limitedStore.write(
                key = "media/second.jpg",
                bytes = byteArrayOf(1),
                contentSha256 = sha256(byteArrayOf(1)),
                lastModifiedMillis = 3_000L,
                expectedRemoteVersion = null,
            )
        }
        expectThrows(CloudSyncLimitException::class.java) {
            limitedStore.read(boundary, maxBytes = 3L)
        }

        assertEquals(successfulPutCountAtBoundary, transport.successfulPuts.size)
    }

    private class InMemoryConditionalBlobTransport : ConditionalBlobTransport {
        private val blobs = linkedMapOf<String, StoredBlob>()
        private var nextVersion = 1L
        val putAttempts = mutableListOf<PutAttempt>()
        val successfulPuts = mutableListOf<SuccessfulPut>()

        fun contains(storageName: String): Boolean = storageName in blobs

        fun storedBytes(storageName: String): ByteArray =
            checkNotNull(blobs[storageName]).bytes.copyOf()

        fun tamperBytes(storageName: String, replacement: ByteArray) {
            val current = checkNotNull(blobs[storageName])
            blobs[storageName] = current.copy(
                metadata = current.metadata.copy(size = replacement.size.toLong()),
                bytes = replacement.copyOf(),
            )
        }

        override suspend fun get(
            storageName: String,
            maxBytes: Long,
            expectedVersion: String?,
        ): BlobRead? {
            val stored = blobs[storageName] ?: return null
            if (expectedVersion != null && expectedVersion != stored.metadata.version) {
                throw CloudSyncConflictException("In-memory version mismatch")
            }
            if (stored.bytes.size.toLong() > maxBytes) {
                throw CloudSyncLimitException("In-memory blob exceeds read limit")
            }
            return BlobRead(
                metadata = stored.metadata.copy(),
                bytes = stored.bytes.copyOf(),
            )
        }

        override suspend fun put(
            storageName: String,
            bytes: ByteArray,
            sha256: String,
            condition: BlobWriteCondition,
        ): BlobMetadata {
            putAttempts += PutAttempt(storageName, bytes.copyOf(), sha256, condition)
            if (contentHash(bytes) != sha256) {
                throw CloudSyncException("In-memory write hash mismatch")
            }
            val current = blobs[storageName]
            when (condition) {
                BlobWriteCondition.MustNotExist -> {
                    if (current != null) {
                        throw CloudSyncConflictException("In-memory object already exists")
                    }
                }

                is BlobWriteCondition.MustMatch -> {
                    if (current?.metadata?.version != condition.version) {
                        throw CloudSyncConflictException("In-memory version mismatch")
                    }
                }
            }
            val version = "\"memory-v${nextVersion++}\""
            val metadata = BlobMetadata(
                version = version,
                size = bytes.size.toLong(),
                lastModifiedMillis = nextVersion,
            )
            blobs[storageName] = StoredBlob(metadata, bytes.copyOf())
            successfulPuts += SuccessfulPut(
                storageName = storageName,
                bytes = bytes.copyOf(),
                sha256 = sha256,
                condition = condition,
                returnedVersion = version,
            )
            return metadata
        }
    }

    private data class StoredBlob(
        val metadata: BlobMetadata,
        val bytes: ByteArray,
    )

    private data class PutAttempt(
        val storageName: String,
        val bytes: ByteArray,
        val sha256: String,
        val condition: BlobWriteCondition,
    )

    private data class SuccessfulPut(
        val storageName: String,
        val bytes: ByteArray,
        val sha256: String,
        val condition: BlobWriteCondition,
        val returnedVersion: String,
    )

    private companion object {
        const val DIARY_KEY = "diaries/2026-07-24.md"
        const val MANIFEST_STORAGE_NAME = ".deskcubby-sync-v1.manifest"
        const val TEST_MAX_OBJECT_BYTES = 1_024L

        fun limits(
            maxObjectBytes: Long = TEST_MAX_OBJECT_BYTES,
            maxObjects: Int = 10,
        ): CloudSyncLimits = CloudSyncLimits(
            maxObjectBytes = maxObjectBytes,
            maxTransferredBytes = maxObjectBytes * maxObjects.coerceAtLeast(1),
            maxObjects = maxObjects,
        )

        fun contentHash(bytes: ByteArray): String = sha256(bytes)

        suspend fun <T : Throwable> expectThrows(
            type: Class<T>,
            block: suspend () -> Unit,
        ): T {
            try {
                block()
            } catch (error: Throwable) {
                if (!type.isInstance(error)) {
                    throw AssertionError(
                        "Expected ${type.simpleName}, got ${error::class.java.simpleName}",
                        error,
                    )
                }
                return checkNotNull(type.cast(error))
            }
            throw AssertionError("Expected ${type.simpleName} to be thrown")
        }
    }
}
