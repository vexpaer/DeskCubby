package com.deskcubby.app.data.sync

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedPropFindHttpClientTest {
    @Test
    fun `real socket receives PROPFIND request line and bounded body`() = runBlocking {
        val captured = AtomicReference<CapturedRequest>()
        val responseBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"/>
        """.trimIndent().toByteArray()
        OneShotServer { socket ->
            val request = BufferedInputStream(socket.getInputStream()).readCapturedRequest()
            captured.set(request)
            socket.getOutputStream().use { output ->
                output.write(
                    (
                        "HTTP/1.1 207 Multi-Status\r\n" +
                            "Content-Type: application/xml\r\n" +
                            "Content-Length: ${responseBody.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.ISO_8859_1),
                )
                output.write(responseBody)
                output.flush()
            }
        }.use { server ->
            val response = client().execute(propertyRequest(server.uri("/dav/item")))
            server.awaitFinished()

            assertEquals(207, response.status)
            assertTrue(response.body.contentEquals(responseBody))
            assertEquals("PROPFIND /dav/item HTTP/1.1", captured.get().firstLine)
            assertEquals("0", captured.get().headers["depth"])
            assertEquals("identity", captured.get().headers["accept-encoding"])
            assertEquals("DeskCubby-Test/1", captured.get().headers["user-agent"])
            assertTrue(
                captured.get().body.toString(StandardCharsets.UTF_8).contains("getetag"),
            )
        }
    }

    @Test
    fun `chunked response is stopped at the streaming byte limit`() = runBlocking {
        val responseBody = ByteArray(33) { 'x'.code.toByte() }
        OneShotServer { socket ->
            BufferedInputStream(socket.getInputStream()).readCapturedRequest()
            socket.getOutputStream().use { output ->
                output.write(
                    (
                        "HTTP/1.1 207 Multi-Status\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Connection: close\r\n\r\n" +
                            responseBody.size.toString(16) + "\r\n"
                        ).toByteArray(StandardCharsets.ISO_8859_1),
                )
                output.write(responseBody)
                output.write("\r\n0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()
            }
        }.use { server ->
            try {
                client().execute(
                    propertyRequest(server.uri("/dav/item"), maxResponseBytes = 32L),
                )
                fail("Expected the decoded streaming body limit to be enforced")
            } catch (_: CloudSyncLimitException) {
                // The response has no declared decoded length, so only streaming enforcement
                // catches it.
            }
            server.awaitFinished()
        }
    }

    @Test
    fun `coroutine cancellation closes the live OkHttp call`() = runBlocking {
        val accepted = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        OneShotServer { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            input.readCapturedRequest()
            accepted.countDown()
            socket.soTimeout = 5_000
            try {
                while (input.read() >= 0) {
                    // Wait for cancellation to close the client side of the socket.
                }
            } catch (_: SocketException) {
                // A reset is also a successful cancellation signal.
            } finally {
                peerClosed.countDown()
            }
        }.use { server ->
            // Do not inherit runBlocking's single-thread event loop: the latch below deliberately
            // blocks this test thread while the real request starts on a worker thread.
            val requestJob = launch(Dispatchers.Default) {
                client().execute(propertyRequest(server.uri("/dav/item")))
            }
            assertTrue("request was not accepted", accepted.await(3, TimeUnit.SECONDS))
            requestJob.cancelAndJoin()
            assertTrue("live call was not closed", peerClosed.await(3, TimeUnit.SECONDS))
            server.awaitFinished()
        }
    }

    @Test
    fun `client pins all timeouts and disables redirects retries and cache`() {
        val client = BoundedPropFindHttpClient(
            connectTimeoutMillis = 1_001,
            readTimeoutMillis = 2_002,
            writeTimeoutMillis = 3_003,
            callTimeoutMillis = 4_004,
            userAgent = "DeskCubby-Test/1",
        )

        assertEquals(listOf(1_001L, 2_002L, 3_003L, 4_004L), client.timeoutSnapshotForTest())
        assertEquals(listOf(false, false, false, true), client.policySnapshotForTest())
    }

    private fun client(): BoundedPropFindHttpClient = BoundedPropFindHttpClient(
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = 5_000,
        writeTimeoutMillis = 2_000,
        callTimeoutMillis = 8_000,
        userAgent = "DeskCubby-Test/1",
    )

    private fun propertyRequest(
        uri: URI,
        maxResponseBytes: Long = 64L * 1_024,
    ): SyncHttpRequest = SyncHttpRequest(
        method = "PROPFIND",
        uri = uri,
        headers = mapOf(
            "Depth" to "0",
            "Content-Type" to "application/xml; charset=utf-8",
        ),
        body = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop><D:getetag/></D:prop></D:propfind>
        """.trimIndent().toByteArray(),
        maxResponseBytes = maxResponseBytes,
    )

    private data class CapturedRequest(
        val firstLine: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun BufferedInputStream.readCapturedRequest(): CapturedRequest {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < terminator.size) {
            val value = read()
            check(value >= 0) { "request ended before headers" }
            headerBytes.write(value)
            check(headerBytes.size() <= 64 * 1_024) { "request headers are unbounded" }
            matched = if (value.toByte() == terminator[matched]) {
                matched + 1
            } else if (value.toByte() == terminator[0]) {
                1
            } else {
                0
            }
        }
        val lines = headerBytes.toByteArray()
            .toString(StandardCharsets.ISO_8859_1)
            .removeSuffix("\r\n\r\n")
            .split("\r\n")
        val headers = lines.drop(1).associate { line ->
            val separator = line.indexOf(':')
            check(separator > 0) { "invalid request header" }
            line.substring(0, separator).lowercase(Locale.ROOT) to
                line.substring(separator + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        check(contentLength in 0..64 * 1_024)
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < body.size) {
            val count = read(body, offset, body.size - offset)
            check(count > 0) { "request body ended early" }
            offset += count
        }
        return CapturedRequest(lines.first(), headers, body)
    }

    private class OneShotServer(
        handler: (Socket) -> Unit,
    ) : AutoCloseable {
        private val closing = AtomicBoolean(false)
        private val failure = AtomicReference<Throwable?>()
        private val finished = CountDownLatch(1)
        private val activeSocket = AtomicReference<Socket?>()
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val worker = thread(name = "propfind-test-server", isDaemon = true) {
            try {
                server.accept().use { socket ->
                    activeSocket.set(socket)
                    handler(socket)
                }
            } catch (error: Throwable) {
                if (!closing.get()) failure.set(error)
            } finally {
                activeSocket.set(null)
                finished.countDown()
            }
        }

        fun uri(path: String): URI = URI("http://127.0.0.1:${server.localPort}$path")

        fun awaitFinished() {
            assertTrue("test server did not finish", finished.await(6, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("test server failed", it) }
        }

        override fun close() {
            closing.set(true)
            activeSocket.getAndSet(null)?.close()
            server.close()
            worker.join(1_000)
            failure.get()?.let { throw AssertionError("test server failed", it) }
        }
    }
}
