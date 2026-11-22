package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Session 2 Web slice (red): open + extract over a fake bridge.
// Local HTML fixtures — no live sites, no WebView in tests.
class WebTest {

    private val fixture =
        """
        <html><head><title>Example Shop</title></head><body>
        <h1>Milk price: 60</h1>
        <p>Bread price: 40. Fresh bread daily.</p>
        <a href="https://example.com/milk">milk</a>
        <a href="https://example.com/bread">bread</a>
        </body></html>
        """.trimIndent()

    @Test
    fun openExtractsTitleTextLinks() {
        val page = openPage(FakeWebBridge(mapOf("https://example.com" to fixture)), "https://example.com")
        assertEquals("Example Shop", page.title)
        assertTrue(page.text.contains("Milk price: 60"))
        assertEquals(listOf("https://example.com/milk", "https://example.com/bread"), page.links)
    }

    @Test
    fun relevantChunksRankQueryFirst() {
        val page = openPage(FakeWebBridge(mapOf("https://example.com" to fixture)), "https://example.com")
        val chunks = relevantChunks(page, "bread price", max = 2)
        assertTrue(chunks[0].contains("Bread price"))
    }

    @Test
    fun timeoutCarriesUrlForRetry() {
        val bridge = FakeWebBridge(mapOf(), timeoutUrls = setOf("https://slow.example"))
        try {
            openPage(bridge, "https://slow.example")
            assertTrue("must throw", false)
        } catch (e: WebException) {
            assertEquals("https://slow.example", e.failure.url)
        }
    }

    @Test
    fun extractJsEscapesQuotes() {
        val js = buildExtractJs("say \"hi\" \\ bye")
        assertTrue(js.contains("\\\"hi\\\""))
        assertTrue(js.contains("\\\\ bye") || js.contains("\\\\"))
    }

    @Test
    fun allowlistPermitsHttpAndHttps() {
        assertTrue(isAllowedWebUrl("https://example.com"))
        assertTrue(isAllowedWebUrl("http://example.com/path?q=1"))
        assertTrue(isAllowedWebUrl("HTTPS://example.com"))
    }

    @Test
    fun allowlistBlocksNonHttpAndBareHosts() {
        assertFalse(isAllowedWebUrl("file:///etc/passwd"))
        assertFalse(isAllowedWebUrl("FILE:///etc/passwd"))
        assertFalse(isAllowedWebUrl("javascript:alert(1)"))
        assertFalse(isAllowedWebUrl("data:text/html,hi"))
        assertFalse(isAllowedWebUrl("ftp://example.com/x"))
        assertFalse(isAllowedWebUrl("about:blank"))
        assertFalse(isAllowedWebUrl("example.com"))
        assertFalse(isAllowedWebUrl(""))
    }

    @Test
    fun blockedUrlCarriesUrlForDisplay() {
        try {
            openPage(FakeWebBridge(mapOf()), "file:///etc/passwd")
            assertTrue("must throw", false)
        } catch (e: WebException) {
            assertEquals("file:///etc/passwd", e.failure.url)
            assertEquals("blocked", e.failure.reason)
        }
    }

    @Test
    fun clickJsTargetsSelectorAndReportsMatch() {
        val js = buildClickJs("a.buy")
        assertTrue(js.contains("querySelector"))
        assertTrue(js.contains("a.buy"))
        assertTrue(js.contains("no-match"))
        assertTrue(js.contains("clicked"))
    }

    @Test
    fun clickJsEscapesQuotes() {
        val js = buildClickJs("a[title=\"hi\"\\]")
        assertTrue(js.contains("\\\"hi\\\""))
    }

    @Test
    fun scrollJsScrollsByPixels() {
        val js = buildScrollJs(250)
        assertTrue(js.contains("scrollBy(0,250)"))
    }
}

class FakeWebBridge(
    private val pages: Map<String, String>,
    private val timeoutUrls: Set<String> = emptySet(),
    private val loadFailUrls: Set<String> = emptySet(),
    private val timeoutOps: Boolean = false,
    private val noMatchSelectors: Set<String> = emptySet(),
) : WebBridge {
    private var current: String? = null
    private var currentPageUrl: String = ""

    override fun currentUrl(): String = currentPageUrl

    override fun load(url: String): String {
        if (url in timeoutUrls) throw WebException(WebFailure(url, "timeout"))
        if (url in loadFailUrls) throw WebException(WebFailure(url, "load-failed"))
        val html = pages[url] ?: throw WebException(WebFailure(url, "not-found"))
        current = html
        currentPageUrl = url
        return html
    }

    override fun pageText(): String {
        if (timeoutOps) throw WebException(WebFailure("", "timeout"))
        val html = current ?: throw WebException(WebFailure("", "no-page"))
        return parsePage("", html).text
    }

    override fun click(selector: String): String {
        if (timeoutOps) throw WebException(WebFailure("", "timeout"))
        val html = current ?: throw WebException(WebFailure("", "no-page"))
        if (selector in noMatchSelectors) throw WebException(WebFailure("", "no-match: $selector"))
        return html
    }

    override fun scrollBy(px: Int): String {
        if (timeoutOps) throw WebException(WebFailure("", "timeout"))
        return current ?: throw WebException(WebFailure("", "no-page"))
    }
}
