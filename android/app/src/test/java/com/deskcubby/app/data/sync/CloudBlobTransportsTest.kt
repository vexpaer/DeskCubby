package com.deskcubby.app.data.sync

import java.net.URI
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

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
    fun `WebDAV metadata falls back to Last-Modified plus content hash when ETag is absent`() {
        val bytes = "remote content".toByteArray()
        val contentHash = sha256(bytes)
        val response = SyncHttpResponse(
            status = 200,
            headers = mapOf(
                "last-modified" to listOf("Mon, 03 Aug 2026 12:34:56 GMT"),
                "content-length" to listOf(bytes.size.toString()),
            ),
            body = bytes,
        )

        val metadata = response.toBlobMetadata(
            allowLastModifiedFallback = true,
            fallbackSha256 = contentHash,
        )
        val decoded = decodeLastModifiedVersion(metadata.version)

        assertNotNull(decoded)
        assertEquals(Instant.parse("2026-08-03T12:34:56Z").epochSecond, decoded!!.epochSecond)
        assertEquals(contentHash, decoded.sha256)
        assertEquals("Mon, 3 Aug 2026 12:34:56 GMT", formatHttpDate(decoded.epochSecond))
        assertEquals(bytes.size.toLong(), metadata.size)
    }

    @Test
    fun `strong ETag remains preferred over Last-Modified fallback`() {
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
            response.toBlobMetadata(
                allowLastModifiedFallback = true,
                fallbackSha256 = sha256(response.body),
            ).version,
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
            response.toBlobMetadata(
                allowLastModifiedFallback = true,
                fallbackSha256 = sha256(response.body),
            )
            fail("Expected missing conditional metadata to be rejected")
        } catch (_: CloudSyncException) {
            // A service without either validator must never silently overwrite remote data.
        }
    }
}
