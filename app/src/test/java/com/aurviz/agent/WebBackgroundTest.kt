package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 08/24: headless background web path (ADR-0002). Scheduled runs
// never have a WebView attached (webBridge == null), so get_page_text
// must work through a headless loader (OkHttp + Jsoup in main) reusing
// the foreground schemas and allowlist. Canned HTML only — no live sites.
class WebBackgroundTest {

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
    fun backgroundFetchReturnsTitleTextLinks() {
        val page = openBackgroundPage(
            FakeBackgroundPageLoader(mapOf("https://example.com" to fixture)),
            "https://example.com",
        )
        assertEquals("Example Shop", page.title)
        assertTrue(page.text.contains("Milk price: 60"))
        assertTrue(page.text.contains("Bread price: 40"))
        assertEquals(listOf("https://example.com/milk", "https://example.com/bread"), page.links)
    }

    @Test
    fun backgroundAllowlistBlocksNonHttp() {
        for (bad in listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hi",
            "ftp://example.com/x",
            "about:blank",
            "example.com",
            "",
        )) {
            try {
                openBackgroundPage(FakeBackgroundPageLoader(mapOf()), bad)
                assertTrue("must throw for $bad", false)
            } catch (e: WebException) {
                assertEquals(bad, e.failure.url)
                assertEquals("blocked", e.failure.reason)
            }
        }
    }

    @Test
    fun backgroundTimeoutAndLoadFailedCarryUrl() {
        try {
            openBackgroundPage(
                FakeBackgroundPageLoader(mapOf(), timeoutUrls = setOf("https://slow.example")),
                "https://slow.example",
            )
            assertTrue("must throw", false)
        } catch (e: WebException) {
            assertEquals("https://slow.example", e.failure.url)
            assertEquals("timeout", e.failure.reason)
        }
        try {
            openBackgroundPage(
                FakeBackgroundPageLoader(mapOf(), loadFailUrls = setOf("https://broken.example")),
                "https://broken.example",
            )
            assertTrue("must throw", false)
        } catch (e: WebException) {
            assertEquals("https://broken.example", e.failure.url)
            assertEquals("load-failed", e.failure.reason)
        }
    }

    @Test
    fun getPageTextUsesBackgroundLoaderWhenBridgeNull() {
        val registry = defaultToolRegistry(
            webBridge = null,
            backgroundWebLoader = FakeBackgroundPageLoader(mapOf("https://example.com" to fixture)),
        )
        val result = registry.execute("get_page_text", mapOf("url" to "https://example.com"))
        assertTrue(result is ToolResult.Success)
        result as ToolResult.Success
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any?>
        assertEquals("https://example.com", data["url"])
        assertEquals("Example Shop", data["title"])
        assertTrue((data["text"] as String).contains("Bread price: 40"))
    }

    @Test
    fun getPageTextBackgroundEnforcesAllowlist() {
        val registry = defaultToolRegistry(
            webBridge = null,
            backgroundWebLoader = FakeBackgroundPageLoader(mapOf()),
        )
        val result = registry.execute("get_page_text", mapOf("url" to "file:///etc/passwd"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("http", ignoreCase = true))
        assertFalse(result.retryable)
    }

    @Test
    fun getPageTextBackgroundTimeoutIsRetryable() {
        val registry = defaultToolRegistry(
            webBridge = null,
            backgroundWebLoader = FakeBackgroundPageLoader(
                mapOf(),
                timeoutUrls = setOf("https://slow.example"),
            ),
        )
        val result = registry.execute("get_page_text", mapOf("url" to "https://slow.example"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("timeout"))
        assertTrue(result.retryable)
    }

    @Test
    fun getPageTextWithoutBridgeOrLoaderStillFails() {
        val registry = defaultToolRegistry(webBridge = null)
        val result = registry.execute("get_page_text", mapOf("url" to "https://example.com"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("browser", ignoreCase = true))
    }

    @Test
    fun foregroundBridgeStillWinsWhenAttached() {
        val registry = defaultToolRegistry(
            webBridge = FakeWebBridge(mapOf("https://example.com" to fixture)),
            backgroundWebLoader = FakeBackgroundPageLoader(mapOf()),
        )
        val result = registry.execute("get_page_text", mapOf("url" to "https://example.com"))
        assertTrue(result is ToolResult.Success)
    }
}

// Canned-HTML BackgroundPageLoader for tests: no network, no WebView.
class FakeBackgroundPageLoader(
    private val pages: Map<String, String>,
    private val timeoutUrls: Set<String> = emptySet(),
    private val loadFailUrls: Set<String> = emptySet(),
) : BackgroundPageLoader {
    override fun loadHtml(url: String): String {
        if (url in timeoutUrls) throw WebException(WebFailure(url, "timeout"))
        if (url in loadFailUrls) throw WebException(WebFailure(url, "load-failed"))
        return pages[url] ?: throw WebException(WebFailure(url, "load-failed"))
    }
}
