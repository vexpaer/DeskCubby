package com.deskcubby.app.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine

data class AgentWebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

data class AgentWebPage(
    val url: String,
    val title: String,
    val content: String,
)

interface AgentWebService {
    suspend fun search(query: String, limit: Int): List<AgentWebSearchResult>

    suspend fun read(url: String): AgentWebPage
}

/**
 * Bounded HTTPS-only web access for Agent tools. It is independent of the model client and never
 * sends model credentials. User-controlled URLs are screened against local/private addresses on
 * every request and redirect.
 */
@Singleton
class DefaultAgentWebService @Inject constructor() : AgentWebService {
    override suspend fun search(query: String, limit: Int): List<AgentWebSearchResult> {
        val normalized = query.replace(Regex("\\s+"), " ").trim()
        if (normalized.isEmpty() || normalized.length > MAX_QUERY_CHARS) {
            throw AgentToolException("INVALID_ARGUMENTS", "A non-empty bounded search query is required.")
        }
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_RESULTS)
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())
        val response = get(
            URI("https://html.duckduckgo.com/html/?q=$encoded"),
            maxBytes = MAX_SEARCH_BYTES,
            onlyPublicHosts = false,
        )
        return parseSearchResults(response.body, safeLimit)
    }

    override suspend fun read(url: String): AgentWebPage {
        if (url.length > MAX_URL_CHARS) {
            throw AgentToolException("INVALID_URL", "The web address is too long.")
        }
        val requested = parsePublicHttpsUri(url)
        val response = get(requested, MAX_PAGE_BYTES, onlyPublicHosts = true)
        val contentType = response.contentType.lowercase()
        if (contentType.isNotBlank() &&
            !contentType.startsWith("text/") &&
            "application/xhtml+xml" !in contentType
        ) {
            throw AgentToolException("UNSUPPORTED_WEB_CONTENT", "The web page is not readable text.")
        }
        val charset = response.contentType.substringAfter("charset=", "")
            .substringBefore(';')
            .trim()
            .takeIf(String::isNotBlank)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: StandardCharsets.UTF_8
        val raw = response.body.toString(charset)
        val title = TITLE_REGEX.find(raw)?.groupValues?.getOrNull(1)
            ?.let(::htmlToText)
            ?.take(MAX_TITLE_CHARS)
            .orEmpty()
        return AgentWebPage(
            url = response.uri.toASCIIString(),
            title = title,
            content = htmlToText(raw).take(MAX_EXTRACTED_PAGE_CHARS),
        )
    }

    private suspend fun get(
        initial: URI,
        maxBytes: Int,
        onlyPublicHosts: Boolean,
    ): WebResponse = suspendCancellableCoroutine { continuation ->
        val connectionRef = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { connectionRef.getAndSet(null)?.disconnect() }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            if (!continuation.isActive) return@Runnable
            var connection: HttpURLConnection? = null
            try {
                var current = initial
                repeat(MAX_REDIRECTS + 1) { redirectCount ->
                    if (onlyPublicHosts) requirePublicHttpsUri(current) else requireSearchUri(current)
                    connection = current.toURL().openConnection() as? HttpURLConnection
                        ?: throw AgentToolException("WEB_REQUEST_FAILED", "The web request could not be opened.")
                    connectionRef.set(connection)
                    connection!!.apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = false
                        useCaches = false
                        doInput = true
                        requestMethod = "GET"
                        setRequestProperty("Accept", "text/html,text/plain,application/xhtml+xml")
                        setRequestProperty("Accept-Encoding", "identity")
                        setRequestProperty("User-Agent", "DeskCubby-Agent/${com.deskcubby.app.BuildConfig.VERSION_NAME}")
                    }
                    if (!continuation.isActive) throw CancellationException("Agent web request canceled")
                    val status = connection!!.responseCode
                    if (status in REDIRECT_STATUSES) {
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw AgentToolException("WEB_REDIRECT_LIMIT", "The web page redirected too many times.")
                        }
                        val location = connection!!.getHeaderField("Location")
                            ?: throw AgentToolException("WEB_INVALID_REDIRECT", "The web page returned an invalid redirect.")
                        current = current.resolve(location)
                        connectionRef.compareAndSet(connection, null)
                        connection!!.disconnect()
                        connection = null
                        return@repeat
                    }
                    if (status !in 200..299) {
                        throw AgentToolException("WEB_HTTP_ERROR", "The web server returned HTTP $status.")
                    }
                    val declared = connection!!.getHeaderFieldLong("Content-Length", -1L)
                    if (declared > maxBytes) {
                        throw AgentToolException("WEB_RESPONSE_TOO_LARGE", "The web response exceeds the size limit.")
                    }
                    val bytes = connection!!.inputStream.use { input ->
                        val output = ByteArrayOutputStream(
                            declared.takeIf { it in 1..maxBytes.toLong() }?.toInt() ?: DEFAULT_BUFFER_SIZE,
                        )
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            if (!continuation.isActive) throw CancellationException("Agent web request canceled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxBytes) {
                                throw AgentToolException(
                                    "WEB_RESPONSE_TOO_LARGE",
                                    "The web response exceeds the size limit.",
                                )
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    continuation.resume(
                        WebResponse(
                            current,
                            connection!!.getHeaderField("Content-Type").orEmpty(),
                            bytes,
                        ),
                    )
                    return@Runnable
                }
                throw AgentToolException("WEB_REDIRECT_LIMIT", "The web page redirected too many times.")
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (error: AgentRuntimeException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } catch (_: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        AgentToolException("WEB_REQUEST_FAILED", "The web request failed safely."),
                    )
                }
            } finally {
                connectionRef.compareAndSet(connection, null)
                connection?.disconnect()
            }
        })
    }

    private fun parseSearchResults(html: ByteArray, limit: Int): List<AgentWebSearchResult> {
        val text = html.toString(StandardCharsets.UTF_8)
        return SEARCH_RESULT_REGEX.findAll(text).mapNotNull { match ->
            val href = decodeHtmlEntities(match.groupValues[1])
            val url = unwrapDuckDuckGoUrl(href) ?: return@mapNotNull null
            val title = htmlToText(match.groupValues[2]).take(MAX_TITLE_CHARS)
            if (title.isBlank()) return@mapNotNull null
            val tail = text.substring(match.range.last + 1).take(4_096)
            val snippet = SEARCH_SNIPPET_REGEX.find(tail)?.groupValues?.getOrNull(1)
                ?.let(::htmlToText)
                ?.take(MAX_SNIPPET_CHARS)
                .orEmpty()
            AgentWebSearchResult(title, url, snippet)
        }.distinctBy(AgentWebSearchResult::url).take(limit).toList()
    }

    private fun unwrapDuckDuckGoUrl(raw: String): String? {
        val absolute = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://html.duckduckgo.com$raw"
            else -> raw
        }
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
        val candidate = if (uri.host.equals("duckduckgo.com", true) ||
            uri.host.equals("html.duckduckgo.com", true)
        ) {
            uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("uddg=") }
                ?.substringAfter('=')
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                ?: return null
        } else {
            uri.toASCIIString()
        }
        return runCatching { parsePublicHttpsUri(candidate).toASCIIString() }.getOrNull()
    }

    private fun requireSearchUri(uri: URI) {
        if (!uri.host.equals("html.duckduckgo.com", true)) {
            throw AgentToolException("WEB_INVALID_REDIRECT", "The search provider returned an invalid redirect.")
        }
        // Keep the fixed provider host subject to the same DNS/private-address checks as pages;
        // a local hosts entry or poisoned resolver must not turn web_search into an SSRF path.
        requirePublicHttpsUri(uri)
    }

    private fun parsePublicHttpsUri(raw: String): URI {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            throw AgentToolException("INVALID_URL", "A valid HTTPS web address is required.")
        }
        requirePublicHttpsUri(uri)
        return uri
    }

    private fun requirePublicHttpsUri(uri: URI) {
        if (uri.scheme != "https" || uri.userInfo != null || uri.host.isNullOrBlank() || uri.port !in -1..65535) {
            throw AgentToolException("INVALID_URL", "Only public HTTPS web addresses are allowed.")
        }
        val addresses = try {
            InetAddress.getAllByName(uri.host)
        } catch (_: IOException) {
            throw AgentToolException("WEB_DNS_FAILED", "The web address could not be resolved.")
        }
        if (addresses.isEmpty() || addresses.any(::isPrivateAddress)) {
            throw AgentToolException("PRIVATE_ADDRESS_BLOCKED", "Local and private network addresses are not allowed.")
        }
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        if (address is Inet4Address) {
            val octets = address.address.map { it.toInt() and 0xff }
            val first = octets[0]
            val second = octets[1]
            if (first == 0 ||
                first == 100 && second in 64..127 || // carrier-grade NAT (RFC 6598)
                first == 198 && second in 18..19 || // benchmark networks
                first >= 240 // reserved/broadcast space
            ) return true
        }
        if (address is Inet6Address) {
            val first = address.address.first().toInt() and 0xff
            if (first and 0xfe == 0xfc) return true // fc00::/7 unique-local addresses
        }
        return false
    }

    private data class WebResponse(val uri: URI, val contentType: String, val body: ByteArray)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 7_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_REDIRECTS = 3
        const val MAX_QUERY_CHARS = 500
        const val MAX_URL_CHARS = 4_096
        const val MAX_SEARCH_RESULTS = 10
        const val MAX_SEARCH_BYTES = 512 * 1024
        const val MAX_PAGE_BYTES = 1024 * 1024
        const val MAX_EXTRACTED_PAGE_CHARS = 180_000
        const val MAX_TITLE_CHARS = 500
        const val MAX_SNIPPET_CHARS = 1_200
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val TITLE_REGEX = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val SEARCH_RESULT_REGEX = Regex(
            "<a[^>]+class=[\\\"'][^\\\"']*result__a[^\\\"']*[\\\"'][^>]+href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val SEARCH_SNIPPET_REGEX = Regex(
            "class=[\\\"'][^\\\"']*result__snippet[^\\\"']*[\\\"'][^>]*>(.*?)</",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}

private fun htmlToText(raw: String): String = decodeHtmlEntities(
    raw.replace(Regex("(?is)<(script|style|noscript|svg|iframe)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>") , "\n")
        .replace(Regex("<[^>]+>"), " "),
).replace('\u0000', ' ')
    .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
    .replace(Regex(" *\\n+ *"), "\n")
    .trim()

private fun decodeHtmlEntities(raw: String): String = raw
    .replace("&amp;", "&", ignoreCase = true)
    .replace("&lt;", "<", ignoreCase = true)
    .replace("&gt;", ">", ignoreCase = true)
    .replace("&quot;", "\"", ignoreCase = true)
    .replace("&#39;", "'", ignoreCase = true)
    .replace("&nbsp;", " ", ignoreCase = true)
    .replace(Regex("&#(\\d{1,7});")) { match ->
        match.groupValues[1].toIntOrNull()?.takeIf { it in 0..0x10ffff }
            ?.let { String(Character.toChars(it)) }
            ?: " "
    }
