package com.deskcubby.app.data.repository

import android.text.Html
import android.util.Xml
import com.deskcubby.app.data.model.RssSubscription
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

private const val MAX_RSS_BYTES = 5 * 1024 * 1024
private const val MAX_REDIRECTS = 5
private const val CONNECT_TIMEOUT_MILLIS = 12_000
private const val READ_TIMEOUT_MILLIS = 18_000
private const val MAX_PARALLEL_FEEDS = 4
private const val PROCESS_DOCDECL_FEATURE =
    "http://xmlpull.org/v1/doc/features.html#process-docdecl"
private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"

private data class DownloadedFeed(
    val bytes: ByteArray,
    val finalUrl: String,
)

data class RssArticle(
    val id: String,
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val url: String,
    val summary: String,
    val publishedAtMillis: Long?,
)

data class RssRefreshResult(
    val articles: List<RssArticle>,
    val successfulFeedIds: Set<String>,
    val errors: Map<String, String>,
)

class RssFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Singleton
class RssRepository @Inject constructor() {
    /**
     * Adds https:// when the user omitted a scheme and deliberately rejects clear-text feeds.
     * This is public so the editor can report the problem before saving a subscription.
     */
    fun normalizeFeedUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) throw RssFetchException("RSS 地址不能为空。")
        val withScheme = if (runCatching { URI(trimmed).scheme }.getOrNull().isNullOrBlank()) {
            "https://$trimmed"
        } else {
            trimmed
        }
        val uri = runCatching { URI(withScheme) }.getOrElse {
            throw RssFetchException("RSS 地址格式不正确。", it)
        }
        when (uri.scheme?.lowercase(Locale.ROOT)) {
            "https" -> Unit
            "http" -> throw RssFetchException("RSS 地址仅支持 HTTPS，请将 http:// 改为 https://。")
            else -> throw RssFetchException("RSS 地址仅支持 HTTPS。")
        }
        if (uri.host.isNullOrBlank()) throw RssFetchException("RSS 地址缺少有效的主机名。")
        return uri.toASCIIString()
    }

    suspend fun fetch(
        subscription: RssSubscription,
        maxItems: Int,
    ): List<RssArticle> = withContext(Dispatchers.IO) {
        val feedUrl = normalizeFeedUrl(subscription.url)
        val payload = download(feedUrl)
        parse(
            bytes = payload.bytes,
            subscription = subscription,
            sourceUrl = payload.finalUrl,
            maxItems = maxItems.coerceIn(1, 200),
        )
    }

    suspend fun refresh(
        subscriptions: List<RssSubscription>,
        maxItemsPerFeed: Int,
    ): RssRefreshResult = coroutineScope {
        val enabled = subscriptions.filter(RssSubscription::enabled)
        val connectionGate = Semaphore(MAX_PARALLEL_FEEDS)
        val outcomes = enabled.map { subscription ->
            async {
                val result = connectionGate.withPermit {
                    try {
                        Result.success(fetch(subscription, maxItemsPerFeed))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
                subscription to result
            }
        }.awaitAll()

        val successfulFeedIds = outcomes
            .filter { (_, result) -> result.isSuccess }
            .mapTo(linkedSetOf()) { (subscription, _) -> subscription.id }
        val articles = outcomes
            .flatMap { (_, result) -> result.getOrDefault(emptyList()) }
            .sortedWith(
                compareByDescending<RssArticle> { it.publishedAtMillis ?: Long.MIN_VALUE }
                    .thenBy { it.feedTitle }
                    .thenBy { it.title },
            )
        val errors = buildMap {
            outcomes.forEach { (subscription, result) ->
                result.exceptionOrNull()?.let { error ->
                    put(subscription.id, readableMessage(error))
                }
            }
        }
        RssRefreshResult(
            articles = articles,
            successfulFeedIds = successfulFeedIds,
            errors = errors,
        )
    }

    private fun download(initialUrl: String): DownloadedFeed {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val normalized = normalizeFeedUrl(currentUrl)
            val connection = (URL(normalized).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/atom+xml, application/rss+xml, application/xml, text/xml;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "DeskCubby RSS/1.0")
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw RssFetchException("RSS 地址重定向次数过多。")
                    }
                    val location = connection.getHeaderField("Location")
                        ?.takeIf(String::isNotBlank)
                        ?: throw RssFetchException("RSS 重定向缺少目标地址。")
                    currentUrl = URL(URL(normalized), location).toString()
                    // Validate here as well so an HTTPS feed cannot downgrade to HTTP.
                    normalizeFeedUrl(currentUrl)
                    return@repeat
                }
                if (status !in 200..299) {
                    throw RssFetchException("RSS 请求失败（HTTP $status）。")
                }
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_RSS_BYTES) {
                    throw RssFetchException("RSS 内容超过 5 MiB 上限。")
                }
                return DownloadedFeed(
                    bytes = connection.inputStream.use(::readLimited),
                    finalUrl = normalized,
                )
            } finally {
                connection.disconnect()
            }
        }
        throw RssFetchException("RSS 地址重定向次数过多。")
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_RSS_BYTES) throw RssFetchException("RSS 内容超过 5 MiB 上限。")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parse(
        bytes: ByteArray,
        subscription: RssSubscription,
        sourceUrl: String,
        maxItems: Int,
    ): List<RssArticle> {
        if (containsDoctype(bytes)) throw RssFetchException("为保证安全，不支持包含 DOCTYPE 的 RSS。")
        return try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setFeature(PROCESS_DOCDECL_FEATURE, false)
                setInput(ByteArrayInputStream(bytes), null)
            }
            moveToRoot(parser)
            when (parser.localName()) {
                "rss" -> parseRss(parser, subscription, sourceUrl, maxItems)
                "feed" -> parseAtom(parser, subscription, sourceUrl, maxItems)
                else -> throw RssFetchException("该地址不是受支持的 RSS 2.0 或 Atom 订阅源。")
            }
        } catch (error: RssFetchException) {
            throw error
        } catch (error: XmlPullParserException) {
            throw RssFetchException("无法解析 RSS/Atom 内容。", error)
        } catch (error: IOException) {
            throw RssFetchException("读取 RSS/Atom 内容时出错。", error)
        }
    }

    private fun parseRss(
        parser: XmlPullParser,
        subscription: RssSubscription,
        sourceUrl: String,
        maxItems: Int,
    ): List<RssArticle> {
        val rootDepth = parser.depth
        var feedTitle = subscription.title.trim()
        val drafts = mutableListOf<ArticleDraft>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == rootDepth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when {
                parser.localName() == "item" && parser.depth == rootDepth + 2 -> drafts += parseRssItem(parser)
                parser.localName() == "title" && parser.depth == rootDepth + 2 && feedTitle.isBlank() -> {
                    feedTitle = readElementText(parser).trim()
                }
                else -> Unit
            }
            if (drafts.size >= maxItems) break
        }
        val displayTitle = feedTitle.ifBlank { hostLabel(sourceUrl) }
        return drafts.map { it.toArticle(subscription.id, displayTitle, sourceUrl) }
    }

    private fun parseRssItem(parser: XmlPullParser): ArticleDraft {
        val itemDepth = parser.depth
        var title = ""
        var link = ""
        var guid = ""
        var summary = ""
        var content = ""
        var published = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == itemDepth) break
            if (parser.eventType != XmlPullParser.START_TAG || parser.depth != itemDepth + 1) continue
            when (parser.localName()) {
                "title" -> title = readElementText(parser)
                "link" -> {
                    val candidate = readElementText(parser).trim()
                    if (link.isBlank() && candidate.isNotBlank()) link = candidate
                }
                "guid" -> guid = readElementText(parser)
                "description", "summary" -> summary = readElementText(parser)
                "encoded", "content" -> content = readElementText(parser)
                "pubdate", "published", "updated", "date" -> published = readElementText(parser)
                else -> skipElement(parser)
            }
        }
        return ArticleDraft(
            sourceId = guid.trim(),
            title = plainText(title),
            link = link.trim().ifBlank { guid.trim().takeIf(::looksLikeWebUrl).orEmpty() },
            summary = plainText(summary.ifBlank { content }),
            publishedRaw = published.trim(),
        )
    }

    private fun parseAtom(
        parser: XmlPullParser,
        subscription: RssSubscription,
        sourceUrl: String,
        maxItems: Int,
    ): List<RssArticle> {
        val rootDepth = parser.depth
        val feedBaseUrl = resolveBaseUrl(
            sourceUrl,
            parser.getAttributeValue(XML_NAMESPACE, "base"),
        )
        var feedTitle = subscription.title.trim()
        val drafts = mutableListOf<ArticleDraft>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == rootDepth) break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when {
                parser.localName() == "entry" && parser.depth == rootDepth + 1 -> {
                    drafts += parseAtomEntry(parser, feedBaseUrl)
                }
                parser.localName() == "title" && parser.depth == rootDepth + 1 && feedTitle.isBlank() -> {
                    feedTitle = plainText(readElementText(parser))
                }
                else -> Unit
            }
            if (drafts.size >= maxItems) break
        }
        val displayTitle = feedTitle.ifBlank { hostLabel(sourceUrl) }
        return drafts.map { it.toArticle(subscription.id, displayTitle, feedBaseUrl) }
    }

    private fun parseAtomEntry(parser: XmlPullParser, feedBaseUrl: String): ArticleDraft {
        val entryDepth = parser.depth
        val entryBaseUrl = resolveBaseUrl(
            feedBaseUrl,
            parser.getAttributeValue(XML_NAMESPACE, "base"),
        )
        var id = ""
        var title = ""
        var summary = ""
        var content = ""
        var published = ""
        var updated = ""
        var alternateLink = ""
        var fallbackLink = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == entryDepth) break
            if (parser.eventType != XmlPullParser.START_TAG || parser.depth != entryDepth + 1) continue
            when (parser.localName()) {
                "id" -> id = readElementText(parser)
                "title" -> title = readElementText(parser)
                "summary" -> summary = readElementText(parser)
                "content" -> content = readElementText(parser)
                "published" -> published = readElementText(parser)
                "updated" -> updated = readElementText(parser)
                "link" -> {
                    val href = parser.getAttributeValue(null, "href").orEmpty().trim()
                    val rel = parser.getAttributeValue(null, "rel").orEmpty().trim().lowercase(Locale.ROOT)
                    val linkBaseUrl = resolveBaseUrl(
                        entryBaseUrl,
                        parser.getAttributeValue(XML_NAMESPACE, "base"),
                    )
                    val resolvedHref = resolveWebUrl(linkBaseUrl, href)
                    if (resolvedHref.isNotBlank()) {
                        if (rel.isBlank() || rel == "alternate") {
                            alternateLink = alternateLink.ifBlank { resolvedHref }
                        }
                        fallbackLink = fallbackLink.ifBlank { resolvedHref }
                    }
                    skipElement(parser)
                }
                else -> skipElement(parser)
            }
        }
        return ArticleDraft(
            sourceId = id.trim(),
            title = plainText(title),
            link = alternateLink.ifBlank { fallbackLink }.ifBlank {
                id.trim().takeIf(::looksLikeWebUrl).orEmpty()
            },
            summary = plainText(summary.ifBlank { content }),
            publishedRaw = published.trim().ifBlank { updated.trim() },
            baseUrl = entryBaseUrl,
        )
    }

    private data class ArticleDraft(
        val sourceId: String,
        val title: String,
        val link: String,
        val summary: String,
        val publishedRaw: String,
        val baseUrl: String? = null,
    ) {
        fun toArticle(feedId: String, feedTitle: String, sourceUrl: String): RssArticle {
            val resolvedUrl = resolveWebUrl(baseUrl ?: sourceUrl, link)
            val stableSource = sourceId.ifBlank { resolvedUrl }.ifBlank { "$title|$publishedRaw" }
            return RssArticle(
                id = "$feedId:${sha256(stableSource)}",
                feedId = feedId,
                feedTitle = feedTitle,
                title = title.ifBlank { "未命名文章" },
                url = resolvedUrl,
                summary = summary,
                publishedAtMillis = parsePublishedAt(publishedRaw),
            )
        }
    }

    private fun moveToRoot(parser: XmlPullParser) {
        while (parser.eventType != XmlPullParser.START_TAG) {
            if (parser.next() == XmlPullParser.END_DOCUMENT) {
                throw RssFetchException("RSS/Atom 内容为空。")
            }
        }
    }

    private fun readElementText(parser: XmlPullParser): String {
        val startDepth = parser.depth
        val result = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == startDepth) break
            if (parser.eventType == XmlPullParser.TEXT || parser.eventType == XmlPullParser.CDSECT) {
                result.append(parser.text)
            }
        }
        return result.toString()
    }

    private fun skipElement(parser: XmlPullParser) {
        val startDepth = parser.depth
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == startDepth) return
        }
    }

    private fun XmlPullParser.localName(): String =
        name.orEmpty().substringAfter(':').lowercase(Locale.ROOT)

    private fun containsDoctype(bytes: ByteArray): Boolean {
        // Removing NUL also catches the common UTF-16/UTF-32 encodings of "<!DOCTYPE".
        val ascii = buildString(bytes.size.coerceAtMost(MAX_RSS_BYTES)) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                if (value != 0 && value < 128) append(value.toChar().lowercaseChar())
            }
        }
        return "<!doctype" in ascii
    }

    private fun readableMessage(error: Throwable): String = when (error) {
        is RssFetchException -> error.message ?: "RSS 加载失败。"
        is java.net.SocketTimeoutException -> "RSS 请求超时。"
        is javax.net.ssl.SSLException -> "RSS 的 HTTPS 证书验证失败。"
        is IOException -> error.message?.takeIf(String::isNotBlank) ?: "RSS 网络请求失败。"
        else -> error.message?.takeIf(String::isNotBlank) ?: "RSS 加载失败。"
    }

    private companion object {
        val REDIRECT_CODES = setOf(
            HttpURLConnection.HTTP_MOVED_PERM,
            HttpURLConnection.HTTP_MOVED_TEMP,
            HttpURLConnection.HTTP_SEE_OTHER,
            307,
            308,
        )
    }
}

private fun plainText(value: String): String = Html.fromHtml(
    value,
    Html.FROM_HTML_MODE_LEGACY,
).toString().replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
    .replace(Regex("\\n{3,}"), "\\n\\n")
    .trim()

private fun looksLikeWebUrl(value: String): Boolean = runCatching {
    URI(value.trim()).scheme?.lowercase(Locale.ROOT) in setOf("http", "https")
}.getOrDefault(false)

private fun resolveWebUrl(baseUrl: String, raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        URL(URL(baseUrl), raw.trim()).toURI().let { uri ->
            if (uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https")) uri.toASCIIString() else ""
        }
    }.getOrDefault("")
}

private fun resolveBaseUrl(parentUrl: String, rawBase: String?): String {
    if (rawBase.isNullOrBlank()) return parentUrl
    return resolveWebUrl(parentUrl, rawBase).ifBlank { parentUrl }
}

private fun hostLabel(url: String): String = runCatching { URI(url).host.orEmpty() }
    .getOrDefault("")
    .ifBlank { "RSS" }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun parsePublishedAt(value: String): Long? {
    if (value.isBlank()) return null
    val parsers = listOf<(String) -> Instant>(
        { Instant.parse(it) },
        { OffsetDateTime.parse(it, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() },
        { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() },
    )
    parsers.forEach { parse ->
        try {
            return parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            // Try the next common RSS/Atom timestamp format.
        }
    }
    return null
}
