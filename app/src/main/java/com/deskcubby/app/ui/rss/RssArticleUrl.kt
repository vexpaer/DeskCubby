package com.deskcubby.app.ui.rss

import java.net.URI
import java.util.Locale

internal sealed interface RssArticleUrl {
    data class Valid(val value: String) : RssArticleUrl
    data object Missing : RssArticleUrl
    data object UnsafeOrUnsupported : RssArticleUrl
}

/**
 * RSS content is untrusted input. Only absolute HTTPS article links with a
 * conventional host may cross the RSS-to-browser navigation boundary.
 */
internal fun normalizeRssArticleUrl(raw: String): RssArticleUrl {
    val candidate = raw.trim()
    if (candidate.isEmpty()) return RssArticleUrl.Missing
    if (candidate.length > MAX_RSS_ARTICLE_URL_LENGTH) {
        return RssArticleUrl.UnsafeOrUnsupported
    }

    val uri = runCatching { URI(candidate) }.getOrNull()
        ?: return RssArticleUrl.UnsafeOrUnsupported
    if (uri.scheme?.lowercase(Locale.ROOT) != "https") {
        return RssArticleUrl.UnsafeOrUnsupported
    }
    if (uri.host.isNullOrBlank() || uri.userInfo != null) {
        return RssArticleUrl.UnsafeOrUnsupported
    }

    val normalized = uri.normalize().toASCIIString()
    return RssArticleUrl.Valid("https${normalized.substring(normalized.indexOf(':'))}")
}

private const val MAX_RSS_ARTICLE_URL_LENGTH = 8_192
