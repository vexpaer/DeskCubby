package com.deskcubby.app.data.sync

import com.deskcubby.app.data.model.CloudSyncConfig
import java.net.URI
import org.junit.Assert.assertEquals
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

    private fun webDavTransport(http: SyncHttpExecutor): WebDavBlobTransport =
        WebDavBlobTransport(
            config = CloudSyncConfig(
                id = "test-webdav",
                name = "Test WebDAV",
                endpointUrl = "https://example.test/dav",
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

    private companion object {
        const val MANIFEST_STORAGE_NAME = ".deskcubby-sync-v1.manifest"
    }
}
