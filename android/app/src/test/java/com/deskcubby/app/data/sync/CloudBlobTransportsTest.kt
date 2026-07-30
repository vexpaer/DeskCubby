package com.deskcubby.app.data.sync

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
