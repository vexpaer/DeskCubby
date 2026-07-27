package com.deskcubby.app.ui.blog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedArticleNavigationPolicyTest {
    @Test
    fun `ordinary browser tabs keep existing navigation behavior`() {
        listOf(
            "http://example.com/article",
            "https://example.com/article",
            "about:blank",
            "custom-scheme://open",
            "not a url",
        ).forEach { url ->
            assertTrue(
                "$url should not be restricted by the RSS-only policy",
                isTrustedArticleMainFrameNavigationAllowed(httpsOnly = false, rawUrl = url),
            )
        }
    }

    @Test
    fun `trusted article tabs allow absolute https navigation`() {
        listOf(
            "https://example.com/article",
            "HTTPS://example.com/news/../article?q=desk#section",
            "https://sub.example.com:8443/article",
        ).forEach { url ->
            assertTrue(
                "$url should stay inside the HTTPS boundary",
                isTrustedArticleMainFrameNavigationAllowed(httpsOnly = true, rawUrl = url),
            )
        }
    }

    @Test
    fun `trusted article tabs reject downgrade and non-network main frames`() {
        listOf(
            "http://example.com/article",
            "HTTP://example.com/article",
            "about:blank",
            "javascript:alert(1)",
            "data:text/html,unsafe",
            "file:///data/local/tmp/article.html",
        ).forEach { url ->
            assertFalse(
                "$url must be blocked for a trusted RSS article tab",
                isTrustedArticleMainFrameNavigationAllowed(httpsOnly = true, rawUrl = url),
            )
        }
    }

    @Test
    fun `trusted article tabs reject relative malformed and credential-bearing urls`() {
        listOf(
            "/article/1",
            "https:///article/1",
            "https://",
            "https://user:password@example.com/article",
            "not a url",
        ).forEach { url ->
            assertFalse(
                "$url must be rejected for a trusted RSS article tab",
                isTrustedArticleMainFrameNavigationAllowed(httpsOnly = true, rawUrl = url),
            )
        }
    }

    @Test
    fun `trusted article url normalization is stable at the view model boundary`() {
        assertEquals(
            "https://example.com/article?q=desk",
            trustedHttpsUrlOrNull("  HTTPS://example.com/news/../article?q=desk  "),
        )
        assertEquals(null, trustedHttpsUrlOrNull("http://example.com/article"))
    }

    @Test
    fun `browser tabs are unrestricted unless explicitly marked as trusted articles`() {
        val tab = BrowserTabState(
            id = 1L,
            addressDraft = "http://example.com",
            url = "http://example.com",
        )

        assertFalse(tab.httpsOnly)
        assertTrue(tab.copy(httpsOnly = true).httpsOnly)
        assertFalse(tab.temporaryRssReader)
        assertTrue(tab.copy(temporaryRssReader = true).temporaryRssReader)
    }

    @Test
    fun `trusted reader never launches an external application`() {
        listOf("mailto", "tel", "sms", "intent", "custom").forEach { scheme ->
            assertFalse(
                shouldOpenExternalNavigation(
                    trustedReader = true,
                    isForMainFrame = true,
                    hasUserGesture = true,
                    scheme = scheme,
                ),
            )
        }
    }

    @Test
    fun `ordinary browser external handoff requires main frame gesture and allowlisted scheme`() {
        listOf("mailto", "tel", "sms").forEach { scheme ->
            assertTrue(
                shouldOpenExternalNavigation(
                    trustedReader = false,
                    isForMainFrame = true,
                    hasUserGesture = true,
                    scheme = scheme,
                ),
            )
        }
        listOf("intent", "file", "content", "javascript", "data", "custom").forEach { scheme ->
            assertFalse(
                shouldOpenExternalNavigation(
                    trustedReader = false,
                    isForMainFrame = true,
                    hasUserGesture = true,
                    scheme = scheme,
                ),
            )
        }
        assertFalse(
            shouldOpenExternalNavigation(
                trustedReader = false,
                isForMainFrame = false,
                hasUserGesture = true,
                scheme = "tel",
            ),
        )
        assertFalse(
            shouldOpenExternalNavigation(
                trustedReader = false,
                isForMainFrame = true,
                hasUserGesture = false,
                scheme = "tel",
            ),
        )
    }
}
