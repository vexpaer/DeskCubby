package com.deskcubby.app.data.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

internal class SyncHttpRequest(
    val method: String,
    val uri: URI,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val maxResponseBytes: Long,
) {
    override fun toString(): String = "SyncHttpRequest(method=$method, uri=<redacted>)"
}

internal class SyncHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun firstHeader(name: String): String? =
        headers[name.lowercase(Locale.ROOT)]?.firstOrNull()

    override fun toString(): String =
        "SyncHttpResponse(status=$status, bodyBytes=${body.size})"
}

internal fun interface SyncHttpExecutor {
    suspend fun execute(request: SyncHttpRequest): SyncHttpResponse
}

/** Counts each HTTP request/response body exactly once against the run-wide budget. */
internal class BudgetedSyncHttpExecutor(
    private val delegate: SyncHttpExecutor,
    private val budget: TransferBudget,
) : SyncHttpExecutor {
    override suspend fun execute(request: SyncHttpRequest): SyncHttpResponse {
        budget.reserve(request.body?.size?.toLong() ?: 0L)
        val remaining = budget.remaining
        if (request.maxResponseBytes > 0L && remaining == 0L) {
            budget.reserve(1L)
        }
        val boundedRequest = SyncHttpRequest(
            method = request.method,
            uri = request.uri,
            headers = request.headers,
            body = request.body,
            maxResponseBytes = minOf(request.maxResponseBytes, remaining),
        )
        val response = delegate.execute(boundedRequest)
        budget.reserve(response.body.size.toLong())
        return response
    }
}

/**
 * Small, bounded HttpURLConnection bridge. Cancellation closes the live connection immediately;
 * connect/read timeouts remain a second line of defence for providers that ignore disconnect().
 */
internal class BoundedHttpClient(
    private val connectTimeoutMillis: Int,
    private val readTimeoutMillis: Int,
    private val userAgent: String = "DeskCubby-Sync/1",
) : SyncHttpExecutor {
    override suspend fun execute(request: SyncHttpRequest): SyncHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val connectionRef = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation {
                connectionRef.getAndSet(null)?.disconnect()
            }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) return@Runnable
                var connection: HttpURLConnection? = null
                try {
                    connection = request.uri.toURL().openConnection() as? HttpURLConnection
                        ?: throw IOException("Unsupported cloud endpoint protocol")
                    connectionRef.set(connection)
                    if (!continuation.isActive) {
                        connection.disconnect()
                        return@Runnable
                    }
                    val response = executeBlocking(connection, request, continuation)
                    continuation.resume(response)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Exception) {
                    continuation.resumeWithException(
                        if (error is CloudSyncException) {
                            error
                        } else {
                            CloudSyncException(
                                "云端请求失败，请检查网络和服务配置。",
                                error,
                                errorCode = "NETWORK_REQUEST",
                            )
                        },
                    )
                } finally {
                    connectionRef.compareAndSet(connection, null)
                    connection?.disconnect()
                }
            })
        }

    private fun executeBlocking(
        connection: HttpURLConnection,
        request: SyncHttpRequest,
        continuation: CancellableContinuation<SyncHttpResponse>,
    ): SyncHttpResponse {
        if (request.method !in SUPPORTED_METHODS) {
            throw CloudSyncException("不支持的云端请求方式。")
        }
        if (request.maxResponseBytes !in 0..MAX_IN_MEMORY_RESPONSE_BYTES) {
            throw CloudSyncLimitException("云端响应大小上限无效。")
        }
        connection.apply {
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            requestMethod = request.method
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", userAgent)
            request.headers.forEach { (name, value) ->
                requireSafeHeader(name, value)
                setRequestProperty(name, value)
            }
            request.body?.let { body ->
                doOutput = true
                setFixedLengthStreamingMode(body.size.toLong())
            }
        }
        request.body?.let { body ->
            connection.outputStream.use { output ->
                var offset = 0
                while (offset < body.size) {
                    if (!continuation.isActive) throw CancellationException("Sync cancelled")
                    val count = minOf(DEFAULT_BUFFER_SIZE, body.size - offset)
                    output.write(body, offset, count)
                    offset += count
                }
            }
        }

        if (!continuation.isActive) throw CancellationException("Sync cancelled")
        val status = connection.responseCode
        val headers = connection.headerFields
            .orEmpty()
            .mapNotNull { (name, values) ->
                name?.lowercase(Locale.ROOT)?.let { it to values.orEmpty().filterNotNull() }
            }
            .toMap()
        val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
        if (declaredLength > request.maxResponseBytes) {
            throw CloudSyncLimitException("云端响应超过允许的大小上限。")
        }
        val stream = try {
            if (status >= 400) connection.errorStream else connection.inputStream
        } catch (error: IOException) {
            connection.errorStream ?: throw error
        }
        val body = if (stream == null || request.method == "HEAD" ||
            request.maxResponseBytes == 0L
        ) {
            stream?.close()
            byteArrayOf()
        } else {
            stream.use { input ->
                val initialSize = declaredLength
                    .takeIf { it in 1..Int.MAX_VALUE.toLong() }
                    ?.toInt()
                    ?: DEFAULT_BUFFER_SIZE
                val output = ByteArrayOutputStream(initialSize)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    if (!continuation.isActive) throw CancellationException("Sync cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > request.maxResponseBytes) {
                        throw CloudSyncLimitException("云端响应超过允许的大小上限。")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        return SyncHttpResponse(status = status, headers = headers, body = body)
    }

    private fun requireSafeHeader(name: String, value: String) {
        if (!HEADER_NAME.matches(name) || value.any { it == '\r' || it == '\n' }) {
            throw CloudSyncException("云端请求头无效。")
        }
    }

    private companion object {
        val SUPPORTED_METHODS = setOf("GET", "HEAD", "PUT")
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        const val MAX_IN_MEMORY_RESPONSE_BYTES = 512L * 1024 * 1024
    }
}
