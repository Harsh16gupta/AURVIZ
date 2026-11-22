package com.aurviz.agent

import org.junit.Assert.assertEquals
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
}

class FakeWebBridge(
    private val pages: Map<String, String>,
    private val timeoutUrls: Set<String> = emptySet(),
) : WebBridge {
    override fun load(url: String): String {
        if (url in timeoutUrls) throw WebException(WebFailure(url, "timeout"))
        return pages[url] ?: throw WebException(WebFailure(url, "not-found"))
    }
}
