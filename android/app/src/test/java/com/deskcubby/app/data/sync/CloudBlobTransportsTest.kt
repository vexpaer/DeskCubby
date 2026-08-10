package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import com.deskcubby.app.data.model.CloudSyncServiceType
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking

class CloudBlobTransportsTest {
    @Test
    fun `path style places bucket before the remote directory`() {
        val uri = buildS3CollectionUri(
            endpoint = URI("https://obss3.cstcloud.cn/api"),
            bucket = "deskcubby",
            remotePath = "Desk Cubby/json",
            pathStyle = true,
        )

        assertEquals(
            "https://obss3.cstcloud.cn/api/deskcubby/Desk%20Cubby/json/",
            uri.toASCIIString(),
        )
    }

    @Test
    fun `virtual hosted style prefixes the bucket exactly once`() {
        val first = buildS3CollectionUri(
            endpoint = URI("https://obss3.cstcloud.cn/api"),
            bucket = "deskcubby",
            remotePath = "DeskCubby",
            pathStyle = false,
        )
        val alreadyPrefixed = buildS3CollectionUri(
            endpoint = URI("https://deskcubby.obss3.cstcloud.cn/api"),
            bucket = "deskcubby",
            remotePath = "DeskCubby",
            pathStyle = false,
        )

        assertEquals(
            "https://deskcubby.obss3.cstcloud.cn/api/DeskCubby/",
            first.toASCIIString(),
        )
        assertEquals(first, alreadyPrefixed)
    }

    @Test
    fun `extracts only a bounded safe S3 provider code`() {
        assertEquals(
            "SignatureDoesNotMatch",
            extractS3ErrorCode(
                """
                <Error>
                  <Code>SignatureDoesNotMatch</Code>
                  <Message>must never be surfaced</Message>
                </Error>
                """.trimIndent().toByteArray(),
            ),
        )
        assertNull(extractS3ErrorCode("<Error><Code>bad code</Code></Error>".toByteArray()))
    }

    @Test
    fun `formatted sync failures always expose a stable error code`() {
        assertEquals(
            "[SYNC_CONFIG] invalid",
            formatCloudSyncError(CloudSyncConfigurationException("invalid")),
        )
        assertEquals(
            "[SYNC_UNEXPECTED] 云端同步失败，请检查服务配置。",
            formatCloudSyncError(IllegalStateException()),
        )
    }

    @Test
    fun `strong ETag is accepted when Last-Modified is also present`() {
        val response = SyncHttpResponse(
            status = 200,
            headers = mapOf(
                "etag" to listOf("  \"strong-version\"  "),
                "last-modified" to listOf("Mon, 03 Aug 2026 12:34:56 GMT"),
            ),
            body = byteArrayOf(1),
        )

        assertEquals(
            "\"strong-version\"",
            response.toBlobMetadata().version,
        )
    }

    @Test
    fun `WebDAV metadata still fails closed when no conditional validator exists`() {
        val response = SyncHttpResponse(
            status = 200,
            headers = emptyMap(),
            body = byteArrayOf(1),
        )

        try {
            response.toBlobMetadata()
            fail("Expected missing conditional metadata to be rejected")
        } catch (_: CloudSyncException) {
            // A service without a single strong ETag must never silently overwrite remote data.
        }
    }

    @Test
    fun `WebDAV rejects Last-Modified when PROPFIND also has no strong ETag`() =
        runBlocking {
            val bytes = "remote manifest".toByteArray()
            val modified = "Mon, 3 Aug 2026 12:34:56 GMT"
            val http = QueueHttpExecutor(
                response(bytes, lastModified = modified),
                response(
                    davProperties(lastModified = modified),
                    status = 207,
                ),
            )

            try {
                webDavTransport(http).get(MANIFEST_STORAGE_NAME, 1_024L, null)
                fail("Expected Last-Modified-only service to fail closed")
            } catch (error: CloudSyncException) {
                assertEquals("SYNC_REMOTE_VALIDATION", error.errorCode)
                assertTrue(error.message.orEmpty().contains("strong ETag"))
            }
        }

    @Test
    fun `WebDAV PROPFIND strong ETag is preferred and binds a confirmation GET`() = runBlocking {
        val bytes = "remote manifest".toByteArray()
        val http = QueueHttpExecutor(
            response(bytes),
            response(davProperties(etag = "\"prop-etag\""), status = 207),
            response(bytes),
        )

        val read = webDavTransport(http).get(MANIFEST_STORAGE_NAME, 1_024L, null)

        assertEquals("\"prop-etag\"", checkNotNull(read).metadata.version)
        assertEquals("\"prop-etag\"", http.requests[2].headers["If-Match"])
    }

    @Test
    fun `shared transfer budget counts initial GET PROPFIND and confirmation GET once`() =
        runBlocking {
            val bytes = "remote manifest".toByteArray()
            val properties = davProperties(etag = "\"prop-etag\"")
            val raw = QueueHttpExecutor(
                response(bytes),
                response(properties, status = 207),
                response(bytes),
            )
            val budget = TransferBudget(1_000_000L)

            webDavTransport(BudgetedSyncHttpExecutor(raw, budget))
                .get(MANIFEST_STORAGE_NAME, 1_024L, null)

            val expected = bytes.size.toLong() * 2L + properties.size.toLong() +
                raw.requests.sumOf { it.body?.size?.toLong() ?: 0L }
            assertEquals(expected, budget.used)

            val overflowRaw = QueueHttpExecutor(
                response(bytes),
                response(properties, status = 207),
                response(bytes),
            )
            try {
                webDavTransport(
                    BudgetedSyncHttpExecutor(overflowRaw, TransferBudget(expected - 1L)),
                ).get(MANIFEST_STORAGE_NAME, 1_024L, null)
                fail("Expected the confirmation GET to exceed the shared budget")
            } catch (_: CloudSyncLimitException) {
                // The final body cannot reuse the bytes already charged for the first GET.
            }
        }

    @Test
    fun `budgeted executor does not double charge an ordinary response`() = runBlocking {
        val requestBody = byteArrayOf(1, 2, 3)
        val responseBody = byteArrayOf(4, 5, 6, 7)
        val raw = QueueHttpExecutor(response(responseBody))
        val budget = TransferBudget(100L)

        BudgetedSyncHttpExecutor(raw, budget).execute(
            SyncHttpRequest(
                method = "PUT",
                uri = URI("https://example.test/object"),
                body = requestBody,
                maxResponseBytes = 100L,
            ),
        )

        assertEquals(7L, budget.used)
        assertEquals(97L, raw.requests.single().maxResponseBytes)
    }

    @Test
    fun `WebDAV rejects content changed between PROPFIND and confirmation GET`() = runBlocking {
        val http = QueueHttpExecutor(
            response("before".toByteArray()),
            response(davProperties(etag = "\"prop-etag\""), status = 207),
            response("after".toByteArray()),
        )

        try {
            webDavTransport(http).get(MANIFEST_STORAGE_NAME, 1_024L, null)
            fail("Expected an in-flight remote change to be rejected")
        } catch (_: CloudSyncConflictException) {
            // A conditional GET that omits its validator must still bind to the bytes read before
            // PROPFIND; otherwise a provider that ignores If-Match could hide a race.
        }
    }

    @Test
    fun `WebDAV property parser rejects DTD and ignores a different href`() {
        val target = URI("https://example.test/dav/DeskCubby/$MANIFEST_STORAGE_NAME")
        try {
            parseWebDavProperties(
                """<!DOCTYPE x [<!ENTITY y SYSTEM "file:///private">]><x/>""".toByteArray(),
                target,
            )
            fail("Expected DTD to be rejected")
        } catch (error: CloudSyncException) {
            assertTrue(error.message.orEmpty().contains("DTD/entity"))
        }

        assertNull(
            parseWebDavProperties(
                davProperties(
                    etag = "\"other\"",
                    href = "/dav/DeskCubby/a-different-object",
                ),
                target,
            ),
        )
    }

    @Test
    fun `ETag accepts only one quoted strong entity tag`() {
        val invalidHeaders = listOf(
            listOf("unquoted"),
            listOf("\"one\", \"two\""),
            listOf("\"bad\u0001value\""),
            listOf("\"one\"", "\"two\""),
        )

        invalidHeaders.forEach { values ->
            try {
                SyncHttpResponse(
                    status = 200,
                    headers = mapOf("etag" to values),
                    body = byteArrayOf(),
                ).toBlobMetadata(allowMissingVersion = true)
                fail("Expected invalid ETag to be rejected: $values")
            } catch (error: CloudSyncException) {
                assertEquals("SYNC_REMOTE_VALIDATION", error.errorCode)
            }
        }
    }

    @Test
    fun `weak ETag and Last-Modified are rejected as write validators`() {
        val bytes = "remote content".toByteArray()
        val response = SyncHttpResponse(
            status = 200,
            headers = mapOf(
                "etag" to listOf("W/\"weak-version\""),
                "last-modified" to listOf("Mon, 03 Aug 2026 12:34:56 GMT"),
            ),
            body = bytes,
        )

        try {
            response.toBlobMetadata()
            fail("Expected weak ETag to fail closed")
        } catch (error: CloudSyncException) {
            assertEquals("SYNC_REMOTE_VALIDATION", error.errorCode)
        }
    }

    @Test
    fun `S3 accepts quoted multipart and bounds compatible proxy ETags`() {
        val multipart = SyncHttpResponse(
            status = 200,
            headers = mapOf("etag" to listOf("\"abc123-17\"")),
            body = byteArrayOf(),
        ).s3EntityTagResolution()
        val duplicated = SyncHttpResponse(
            status = 200,
            headers = mapOf("etag" to listOf("\"same\", \"same\"", "\"same\"")),
            body = byteArrayOf(),
        ).s3EntityTagResolution()

        assertEquals("\"abc123-17\"", multipart.trustedVersion)
        assertNull(duplicated.trustedVersion)
        assertEquals(listOf("\"same\""), duplicated.compatibleVersions)
    }

    @Test
    fun `S3 accepts an identical duplicate ETag without a condition probe`() = runBlocking {
        val bytes = "remote manifest".toByteArray()
        val http = QueueHttpExecutor(
            SyncHttpResponse(
                status = 200,
                headers = mapOf("etag" to listOf("\"same\", \"same\"", "\"same\"")),
                body = bytes,
            ),
        )

        val read = checkNotNull(s3Transport(http).get(MANIFEST_STORAGE_NAME, 1_024L, null))

        assertEquals("\"same\"", read.metadata.version)
        assertEquals(listOf("GET"), http.requests.map { it.method })
        assertNull(http.requests.single().header("If-Match"))
    }

    @Test
    fun `S3 compatible ETag read keeps the configured object limit without extra requests`() =
        runBlocking {
            val bytes = ByteArray(96 * 1024) { index -> (index and 0xff).toByte() }
            val http = QueueHttpExecutor(
                response(bytes, etag = "unquoted-version"),
            )

            val read = checkNotNull(
                s3Transport(http).get(MANIFEST_STORAGE_NAME, bytes.size.toLong(), null),
            )

            assertArrayEquals(bytes, read.bytes)
            assertEquals("\"unquoted-version\"", read.metadata.version)
            assertEquals(listOf("GET"), http.requests.map { it.method })
            assertEquals(bytes.size.toLong(), http.requests.single().maxResponseBytes)
        }

    @Test
    fun `S3 invalid ETag falls back to a content version without validation failure`() = runBlocking {
        val bytes = "remote manifest".toByteArray()
        val http = QueueHttpExecutor(
            response(bytes, etag = "\"bad\u0001value\""),
        )

        val read = checkNotNull(s3Transport(http).get(MANIFEST_STORAGE_NAME, 1_024L, null))

        assertEquals(bytes.md5Etag(), read.metadata.version)
        assertEquals(listOf("GET"), http.requests.map { it.method })
    }

    @Test
    fun `S3 repairs unquoted and weak ETags as compatibility versions`() {
        val unquoted = SyncHttpResponse(
            status = 200,
            headers = mapOf("etag" to listOf("abc123-4")),
            body = byteArrayOf(),
        ).s3EntityTagResolution()
        val weak = SyncHttpResponse(
            status = 200,
            headers = mapOf("etag" to listOf("W/\"proxy-version\"")),
            body = byteArrayOf(),
        ).s3EntityTagResolution()
        val multiple = SyncHttpResponse(
            status = 200,
            headers = mapOf("etag" to listOf("\"proxy\", \"origin\"")),
            body = byteArrayOf(),
        ).s3EntityTagResolution()

        assertNull(unquoted.trustedVersion)
        assertEquals(listOf("\"abc123-4\""), unquoted.compatibleVersions)
        assertNull(weak.trustedVersion)
        assertEquals(listOf("\"proxy-version\""), weak.compatibleVersions)
        assertNull(multiple.trustedVersion)
        assertEquals(listOf("\"proxy\"", "\"origin\""), multiple.compatibleVersions)
    }

    @Test
    fun `S3 uses repaired weak and unquoted ETags without probing provider semantics`() = runBlocking {
        listOf(
            "W/\"weak-version\"" to "\"weak-version\"",
            "multipart-hash-3" to "\"multipart-hash-3\"",
        ).forEach { (returnedHeader, expectedVersion) ->
            val bytes = "remote manifest".toByteArray()
            val http = QueueHttpExecutor(
                response(bytes, etag = returnedHeader),
            )

            val read = checkNotNull(s3Transport(http).get(MANIFEST_STORAGE_NAME, 1_024L, null))

            assertEquals(expectedVersion, read.metadata.version)
            assertEquals(listOf("GET"), http.requests.map { it.method })
        }
    }

    @Test
    fun `S3 uses the first bounded proxy ETag without a condition probe`() =
        runBlocking {
            val bytes = "remote manifest".toByteArray()
            val http = QueueHttpExecutor(
                SyncHttpResponse(
                    status = 200,
                    headers = mapOf("etag" to listOf("\"proxy\", \"origin\"")),
                    body = bytes,
                ),
            )

            val read = checkNotNull(s3Transport(http).get(MANIFEST_STORAGE_NAME, 1_024L, null))

            assertEquals("\"proxy\"", read.metadata.version)
            assertEquals(listOf("GET"), http.requests.map { it.method })
        }

    @Test
    fun `S3 missing ETag supports first upload later sync and rejects a stale phone`() =
        runBlocking {
            val http = MissingEtagS3Executor()
            val firstPhone = s3Transport(http)
            val secondPhone = s3Transport(http)
            val firstBytes = "first manifest".toByteArray()

            val created = firstPhone.put(
                storageName = MANIFEST_STORAGE_NAME,
                bytes = firstBytes,
                sha256 = sha256(firstBytes),
                condition = BlobWriteCondition.MustNotExist,
            )
            val loadedOnSecondPhone = checkNotNull(
                secondPhone.get(MANIFEST_STORAGE_NAME, 1_024L, null),
            )
            assertEquals(created.version, loadedOnSecondPhone.metadata.version)
            assertArrayEquals(firstBytes, loadedOnSecondPhone.bytes)

            val updatedBytes = "winner manifest".toByteArray()
            val winner = firstPhone.put(
                storageName = MANIFEST_STORAGE_NAME,
                bytes = updatedBytes,
                sha256 = sha256(updatedBytes),
                condition = BlobWriteCondition.MustMatch(created.version),
            )
            assertFalse(winner.version == created.version)

            try {
                secondPhone.put(
                    storageName = MANIFEST_STORAGE_NAME,
                    bytes = "stale loser".toByteArray(),
                    sha256 = sha256("stale loser".toByteArray()),
                    condition = BlobWriteCondition.MustMatch(loadedOnSecondPhone.metadata.version),
                )
                fail("Expected the stale phone's conditional write to conflict")
            } catch (error: CloudSyncConflictException) {
                assertEquals("SYNC_CONFLICT", error.errorCode)
            }
            assertArrayEquals(updatedBytes, http.storedBytes(MANIFEST_STORAGE_NAME))
        }

    @Test
    fun `S3 read is not blocked when the service ignores If-Match`() = runBlocking {
        val bytes = "remote manifest".toByteArray()
        val http = QueueHttpExecutor(
            response(bytes),
        )
        val expectedVersion = "\"known-version\""

        val read = checkNotNull(
            s3Transport(http).get(MANIFEST_STORAGE_NAME, 1_024L, expectedVersion),
        )

        assertArrayEquals(bytes, read.bytes)
        assertEquals(expectedVersion, read.metadata.version)
        assertEquals(expectedVersion, http.requests.single().header("If-Match"))
        assertEquals(listOf("GET"), http.requests.map { it.method })
    }

    @Test
    fun `S3 write ignored condition still verifies uploaded bytes by SHA`() = runBlocking {
        val bytes = "forced local manifest".toByteArray()
        val expectedVersion = "\"previous-version\""
        val http = QueueHttpExecutor(
            response(byteArrayOf(), status = 200),
            response(bytes),
        )

        val written = s3Transport(http).put(
            storageName = MANIFEST_STORAGE_NAME,
            bytes = bytes,
            sha256 = sha256(bytes),
            condition = BlobWriteCondition.MustMatch(expectedVersion),
        )

        assertEquals(bytes.md5Etag(), written.version)
        assertEquals(listOf("PUT", "GET"), http.requests.map { it.method })
        assertEquals(expectedVersion, http.requests[0].header("If-Match"))
        assertNull(http.requests[1].header("If-Match"))
        assertArrayEquals(bytes, checkNotNull(http.requests[0].body))
    }

    private fun webDavTransport(http: SyncHttpExecutor): WebDavBlobTransport =
        WebDavBlobTransport(
            config = CloudSyncConfig(
                id = "test-webdav",
                name = "Test WebDAV",
                endpointUrl = "https://example.test/dav",
            ).validateForSync(),
            http = http,
        )

    private fun s3Transport(http: SyncHttpExecutor): S3BlobTransport =
        S3BlobTransport(
            config = CloudSyncConfig(
                id = "test-s3",
                name = "Test S3",
                serviceType = CloudSyncServiceType.S3_COMPATIBLE,
                endpointUrl = "https://s3.example.test",
                s3Bucket = "deskcubby",
                s3AccessKey = "access-key",
                s3SecretKey = "secret-key",
            ).validateForSync(),
            http = http,
        )

    private fun response(
        bytes: ByteArray,
        status: Int = 200,
        lastModified: String? = null,
        etag: String? = null,
    ): SyncHttpResponse = SyncHttpResponse(
        status = status,
        headers = buildMap {
            if (status != 204) put("content-length", listOf(bytes.size.toString()))
            lastModified?.let { put("last-modified", listOf(it)) }
            etag?.let { put("etag", listOf(it)) }
        },
        body = bytes,
    )

    private fun davProperties(
        etag: String? = null,
        lastModified: String? = null,
        href: String = "/dav/DeskCubby/$MANIFEST_STORAGE_NAME",
    ): ByteArray = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>$href</D:href>
            <D:propstat>
              <D:prop>
                ${etag?.let { "<D:getetag>$it</D:getetag>" }.orEmpty()}
                ${lastModified?.let { "<D:getlastmodified>$it</D:getlastmodified>" }.orEmpty()}
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent().toByteArray()

    private class QueueHttpExecutor(vararg responses: SyncHttpResponse) : SyncHttpExecutor {
        private val remaining = ArrayDeque(responses.toList())
        val requests = mutableListOf<SyncHttpRequest>()

        override suspend fun execute(request: SyncHttpRequest): SyncHttpResponse {
            requests += request
            return remaining.removeFirstOrNull()
                ?: error("No queued response for ${request.method}")
        }
    }

    /** Minimal S3-compatible server that honors conditions but omits ETag from every response. */
    private inner class MissingEtagS3Executor : SyncHttpExecutor {
        private val objects = mutableMapOf<String, StoredObject>()

        override suspend fun execute(request: SyncHttpRequest): SyncHttpResponse {
            val storageName = request.uri.path.substringAfterLast('/')
            val current = objects[storageName]
            val ifMatch = request.header("If-Match")
            if (ifMatch != null && current?.etag != ifMatch) {
                return response(byteArrayOf(), status = 412)
            }
            return when (request.method) {
                "HEAD" -> if (current == null) {
                    response(byteArrayOf(), status = 404)
                } else {
                    response(byteArrayOf(), status = 200)
                }
                "GET" -> if (current == null) {
                    response(byteArrayOf(), status = 404)
                } else {
                    response(current.bytes)
                }
                "PUT" -> {
                    if (request.header("If-None-Match") == "*" && current != null) {
                        response(byteArrayOf(), status = 412)
                    } else {
                        val bytes = checkNotNull(request.body).copyOf()
                        objects[storageName] = StoredObject(bytes, bytes.md5Etag())
                        response(byteArrayOf(), status = 200)
                    }
                }
                else -> error("Unexpected ${request.method}")
            }
        }

        fun storedBytes(storageName: String): ByteArray =
            checkNotNull(objects[storageName]).bytes.copyOf()
    }

    private fun SyncHttpRequest.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun ByteArray.md5Etag(): String {
        val digest = MessageDigest.getInstance("MD5").digest(this)
        return digest.joinToString(separator = "", prefix = "\"", postfix = "\"") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }

    private data class StoredObject(val bytes: ByteArray, val etag: String)

    private companion object {
        const val MANIFEST_STORAGE_NAME = ".deskcubby-sync-v1.manifest"
    }
}
