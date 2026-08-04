package com.deskcubby.app.data.sync

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Routes WebDAV's one non-standard HTTP verb to a client that can actually emit it on Android.
 * Ordinary WebDAV and S3 traffic stays on [BoundedHttpClient]/HttpURLConnection.
 */
internal class WebDavSyncHttpExecutor(
    private val standard: SyncHttpExecutor,
    private val propFind: SyncHttpExecutor,
) : SyncHttpExecutor {
    override suspend fun execute(request: SyncHttpRequest): SyncHttpResponse =
        if (request.method == PROPFIND_METHOD) {
            propFind.execute(request)
        } else {
            standard.execute(request)
        }
}

/**
 * A deliberately narrow OkHttp boundary for a Depth: 0 WebDAV PROPFIND.
 *
 * There are no redirects, cookies, authenticators or logging interceptors. Both request and
 * response data are bounded, transparent compression is disabled, and coroutine cancellation
 * cancels the live OkHttp [Call].
 */
internal class BoundedPropFindHttpClient private constructor(
    private val client: OkHttpClient,
    private val userAgent: String,
) : SyncHttpExecutor {
    constructor(
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        writeTimeoutMillis: Int,
        callTimeoutMillis: Long,
        userAgent: String = "DeskCubby-Sync/1",
        interceptor: Interceptor? = null,
    ) : this(
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cache(null)
            .connectTimeout(connectTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
            .apply {
                if (interceptor != null) addInterceptor(interceptor)
            }
            .build(),
        userAgent = userAgent,
    )

    override suspend fun execute(request: SyncHttpRequest): SyncHttpResponse {
        validateRequest(request)
        return suspendCancellableCoroutine { continuation ->
            val callRef = AtomicReference<Call?>()
            continuation.invokeOnCancellation {
                callRef.getAndSet(null)?.cancel()
            }
            Dispatchers.IO.dispatch(continuation.context, Runnable {
                if (!continuation.isActive) return@Runnable
                var call: Call? = null
                try {
                    val okRequest = buildRequest(request)
                    call = client.newCall(okRequest)
                    callRef.set(call)
                    if (!continuation.isActive) {
                        call.cancel()
                        return@Runnable
                    }
                    val response = call.execute().use { rawResponse ->
                        readResponse(rawResponse, request.maxResponseBytes, continuation)
                    }
                    if (continuation.isActive) continuation.resume(response)
                } catch (cancelled: CancellationException) {
                    continuation.cancel(cancelled)
                } catch (error: Exception) {
                    if (!continuation.isActive || call?.isCanceled() == true) {
                        continuation.cancel(CancellationException("Sync cancelled"))
                    } else {
                        // Do not retain OkHttp's exception message: it may contain an endpoint.
                        continuation.resumeWithException(
                            if (error is CloudSyncException) {
                                error
                            } else {
                                CloudSyncException(
                                    "WebDAV 属性请求失败，请检查网络和服务配置。 / " +
                                        "The WebDAV property request failed; check the network " +
                                        "and service configuration.",
                                    errorCode = "NETWORK_REQUEST",
                                )
                            },
                        )
                    }
                } finally {
                    callRef.compareAndSet(call, null)
                }
            })
        }
    }

    private fun buildRequest(request: SyncHttpRequest): Request {
        val builder = Request.Builder()
            .url(request.uri.toASCIIString())
        request.headers.forEach { (name, value) ->
            requireSafeHeader(name, value)
            builder.header(name, value)
        }
        // Pin these after caller headers so transparent decompression and accidental UA leakage
        // cannot expand or alter the bounded response semantics.
        builder.header("Accept-Encoding", "identity")
        builder.header("User-Agent", userAgent)
        return builder
            .method(PROPFIND_METHOD, checkNotNull(request.body).toRequestBody())
            .build()
    }

    private fun readResponse(
        response: okhttp3.Response,
        maxResponseBytes: Long,
        continuation: CancellableContinuation<SyncHttpResponse>,
    ): SyncHttpResponse {
        var headerBytes = 0L
        val headers = linkedMapOf<String, MutableList<String>>()
        for (index in 0 until response.headers.size) {
            val name = response.headers.name(index)
            val value = response.headers.value(index)
            headerBytes += name.length.toLong() + value.length.toLong()
            if (index >= MAX_RESPONSE_HEADERS || headerBytes > MAX_RESPONSE_HEADER_BYTES) {
                throw CloudSyncLimitException("WebDAV 属性响应头超过允许的大小上限。")
            }
            headers.getOrPut(name.lowercase(Locale.ROOT), ::mutableListOf).add(value)
        }

        val responseBody = response.body
        val declaredLength = responseBody.contentLength()
        if (declaredLength > maxResponseBytes) {
            throw CloudSyncLimitException("WebDAV 属性响应超过允许的大小上限。")
        }
        val body = if (maxResponseBytes == 0L) {
            byteArrayOf()
        } else {
            responseBody.byteStream().use { input ->
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
                    if (total > maxResponseBytes) {
                        throw CloudSyncLimitException(
                            "WebDAV 属性响应超过允许的大小上限。",
                        )
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        return SyncHttpResponse(
            status = response.code,
            headers = headers.mapValues { it.value.toList() },
            body = body,
        )
    }

    private fun validateRequest(request: SyncHttpRequest) {
        if (request.method != PROPFIND_METHOD) {
            throw CloudSyncException("不支持的 WebDAV 属性请求方式。")
        }
        val uri = request.uri
        if (!uri.isAbsolute || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.query != null || uri.fragment != null ||
            !(uri.scheme.equals("https", ignoreCase = true) ||
                uri.scheme.equals("http", ignoreCase = true))
        ) {
            throw CloudSyncException("WebDAV 属性请求地址无效。")
        }
        if (request.body == null || request.body.size > MAX_PROPERTY_REQUEST_BYTES) {
            throw CloudSyncLimitException("WebDAV 属性请求体超过允许的大小上限。")
        }
        if (request.maxResponseBytes !in 0..MAX_PROPERTY_RESPONSE_BYTES) {
            throw CloudSyncLimitException("WebDAV 属性响应大小上限无效。")
        }
        if (request.headers.size > MAX_REQUEST_HEADERS ||
            request.headers.entries.sumOf { (name, value) ->
                name.length.toLong() + value.length.toLong()
            } > MAX_REQUEST_HEADER_BYTES
        ) {
            throw CloudSyncLimitException("WebDAV 属性请求头超过允许的大小上限。")
        }
        request.headers.forEach { (name, value) -> requireSafeHeader(name, value) }
        requireSafeHeader("User-Agent", userAgent)
    }

    private fun requireSafeHeader(name: String, value: String) {
        if (!HEADER_NAME.matches(name) || value.any { it == '\r' || it == '\n' }) {
            throw CloudSyncException("WebDAV 属性请求头无效。")
        }
    }

    internal fun timeoutSnapshotForTest(): List<Long> = listOf(
        client.connectTimeoutMillis.toLong(),
        client.readTimeoutMillis.toLong(),
        client.writeTimeoutMillis.toLong(),
        client.callTimeoutMillis.toLong(),
    )

    internal fun policySnapshotForTest(): List<Boolean> = listOf(
        client.followRedirects,
        client.followSslRedirects,
        client.retryOnConnectionFailure,
        client.cache == null,
    )

    private companion object {
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        const val MAX_PROPERTY_REQUEST_BYTES = 64 * 1024
        const val MAX_PROPERTY_RESPONSE_BYTES = 64L * 1024
        const val MAX_REQUEST_HEADERS = 64
        const val MAX_RESPONSE_HEADERS = 128
        const val MAX_REQUEST_HEADER_BYTES = 32L * 1024
        const val MAX_RESPONSE_HEADER_BYTES = 64L * 1024
    }
}

private const val PROPFIND_METHOD = "PROPFIND"
