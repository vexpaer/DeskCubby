package com.deskcubby.app.ui.vault

import java.net.URI
import java.util.Locale

/**
 * Returns a browser-safe HTTP(S) URL only when the whole entry content is one absolute URL.
 *
 * User-info is rejected to avoid visually misleading `trusted.example@attacker.example` links.
 * Internal whitespace/control characters and hostless forms such as `https:example.com` are also
 * treated as ordinary text, so they can only be copied.
 */
internal fun safeVaultHttpUrlOrNull(rawContent: String): String? {
    val candidate = rawContent.trim()
    if (candidate.isEmpty() || candidate.any { it.isWhitespace() || it.isISOControl() }) {
        return null
    }

    val uri = try {
        URI(candidate)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    if (!uri.isAbsolute || uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) {
        return null
    }
    val host = uri.host?.takeIf(String::isNotBlank) ?: return null
    if (host.any { it.isWhitespace() || it.isISOControl() }) return null
    if (uri.port > 65_535) return null

    return candidate
}
