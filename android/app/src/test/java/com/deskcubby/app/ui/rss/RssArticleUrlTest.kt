package com.deskcubby.app.ui.rss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssArticleUrlTest {
    @Test
    fun `normalizes an absolute https article URL`() {
        assertEquals(
            RssArticleUrl.Valid("https://example.com/news/today?q=desk"),
            normalizeRssArticleUrl("  HTTPS://example.com/news/./today?q=desk  "),
        )
    }

    @Test
    fun `distinguishes a missing link`() {
        assertEquals(RssArticleUrl.Missing, normalizeRssArticleUrl(" \n "))
    }

    @Test
    fun `rejects insecure and active-content schemes`() {
        listOf(
            "http://example.com/article",
            "javascript:alert(1)",
            "data:text/html,hello",
            "file:///data/local/tmp/article.html",
        ).forEach { url ->
            assertEquals(url, RssArticleUrl.UnsafeOrUnsupported, normalizeRssArticleUrl(url))
        }
    }

    @Test
    fun `rejects relative hostless malformed and credential-bearing URLs`() {
        listOf(
            "/article/1",
            "https:///article/1",
            "https://",
            "https://user:password@example.com/article",
            "not a url",
        ).forEach { url ->
            assertTrue(
                "$url must be rejected",
                normalizeRssArticleUrl(url) is RssArticleUrl.UnsafeOrUnsupported,
            )
        }
    }

    @Test
    fun `rejects an excessively long URL`() {
        val url = "https://example.com/" + "a".repeat(8_192)
        assertEquals(RssArticleUrl.UnsafeOrUnsupported, normalizeRssArticleUrl(url))
    }
}
