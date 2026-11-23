package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 07/24: foreground web Tools through WebViewBridge. The registry owns
// the five schemas; a fake WebBridge stands in for the real WebView (which
// needs Android). Allowlist blocks, timeout/load-failed error paths, and
// schema registration are all observed through ToolRegistry.execute.
class WebToolsTest {

    private val fixture =
        """
        <html><head><title>Example Shop</title></head><body>
        <h1>Milk price: 60</h1>
        <p>Bread price: 40. Fresh bread daily.</p>
        <a href="https://example.com/milk">milk</a>
        <a href="https://example.com/bread">bread</a>
        </body></html>
        """.trimIndent()

    private fun registryWith(
        pages: Map<String, String> = mapOf("https://example.com" to fixture),
        timeoutUrls: Set<String> = emptySet(),
        loadFailUrls: Set<String> = emptySet(),
        timeoutOps: Boolean = false,
        noMatchSelectors: Set<String> = emptySet(),
        webBridge: WebBridge? = null,
        useFake: Boolean = true,
    ): ToolRegistry {
        val bridge =
            webBridge ?: if (useFake) {
                FakeWebBridge(
                    pages = pages,
                    timeoutUrls = timeoutUrls,
                    loadFailUrls = loadFailUrls,
                    timeoutOps = timeoutOps,
                    noMatchSelectors = noMatchSelectors,
                )
            } else {
                null
            }
        return defaultToolRegistry(
            gmailApi = null,
            scheduleStore = null,
            webBridge = bridge,
        )
    }

    @Test
    fun allFiveWebSchemasRegisteredWithResultTypes() {
        val registry = registryWith()
        val expected =
            mapOf(
                "open_url" to "url",
                "get_page_text" to "url",
                "click" to "selector",
                "scroll" to "px",
                "extract" to "query",
            )
        for ((name, requiredArg) in expected) {
            val definition = registry.find(name)
            assertTrue("missing tool: $name", definition != null)
            definition!!
            assertTrue("result type blank: $name", definition.resultType.isNotBlank())
            assertEquals(RiskLevel.LOW, definition.riskLevel)
            assertFalse("web tool must not need confirmation: $name", definition.requiresConfirmation)
            val properties = definition.parameters.getJSONObject("properties")
            assertTrue("missing arg $requiredArg: $name", properties.has(requiredArg))
            val required = definition.parameters.getJSONArray("required")
            val names = (0 until required.length()).map { required.getString(it) }.toSet()
            assertTrue("arg $requiredArg not required: $name", requiredArg in names)
        }
    }

    @Test
    fun openUrlReturnsTitleTextLinks() {
        val result = registryWith().execute("open_url", mapOf("url" to "https://example.com"))
        assertTrue(result is ToolResult.Success)
        result as ToolResult.Success
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any?>
        assertEquals("https://example.com", data["url"])
        assertEquals("Example Shop", data["title"])
        assertTrue((data["text"] as String).contains("Milk price: 60"))
        assertTrue((data["links"] as List<*>).contains("https://example.com/milk"))
    }

    @Test
    fun getPageTextReturnsText() {
        val result = registryWith().execute("get_page_text", mapOf("url" to "https://example.com"))
        assertTrue(result is ToolResult.Success)
        result as ToolResult.Success
        @Suppress("UNCHECKED_CAST")
        val data = result.data as Map<String, Any?>
        assertTrue((data["text"] as String).contains("Bread price: 40"))
    }

    @Test
    fun clickScrollExtractRunOnCurrentPage() {
        val registry = registryWith()
        assertTrue(registry.execute("open_url", mapOf("url" to "https://example.com")) is ToolResult.Success)

        val clicked = registry.execute("click", mapOf("selector" to "a"))
        assertTrue(clicked is ToolResult.Success)

        val scrolled = registry.execute("scroll", mapOf("px" to 250))
        assertTrue(scrolled is ToolResult.Success)

        val extracted = registry.execute("extract", mapOf("query" to "bread price"))
        assertTrue(extracted is ToolResult.Success)
        extracted as ToolResult.Success
        @Suppress("UNCHECKED_CAST")
        val data = extracted.data as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val chunks = data["chunks"] as List<String>
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].contains("Bread price"))
    }

    @Test
    fun allowlistBlocksFileAndNonHttpUrls() {
        val registry = registryWith()
        for (bad in listOf("file:///etc/passwd", "javascript:alert(1)", "ftp://example.com/x", "example.com")) {
            for (tool in listOf("open_url", "get_page_text")) {
                val arg = if (tool == "open_url") mapOf("url" to bad) else mapOf("url" to bad)
                val result = registry.execute(tool, arg)
                assertTrue("$tool($bad) must fail", result is ToolResult.Failure)
                result as ToolResult.Failure
                assertTrue("$tool($bad): ${result.error}", result.error.contains("http", ignoreCase = true))
                assertFalse("$tool($bad) must not be retryable", result.retryable)
            }
        }
    }

    @Test
    fun timeoutAndLoadFailedBecomeRetryableErrors() {
        val slow = registryWith(timeoutUrls = setOf("https://slow.example"))
        val timeout = slow.execute("open_url", mapOf("url" to "https://slow.example"))
        assertTrue(timeout is ToolResult.Failure)
        timeout as ToolResult.Failure
        assertTrue(timeout.error.contains("timeout"))
        assertTrue(timeout.retryable)

        val broken = registryWith(loadFailUrls = setOf("https://broken.example"))
        val failed = broken.execute("get_page_text", mapOf("url" to "https://broken.example"))
        assertTrue(failed is ToolResult.Failure)
        failed as ToolResult.Failure
        assertTrue(failed.error.contains("load-failed"))
        assertTrue(failed.retryable)
    }

    @Test
    fun interactionTimeoutsBecomeRetryableErrorsNeverThrows() {
        val registry = registryWith(timeoutOps = true)
        assertTrue(registry.execute("open_url", mapOf("url" to "https://example.com")) is ToolResult.Success)
        for ((tool, args) in listOf(
            "click" to mapOf("selector" to "a"),
            "scroll" to mapOf("px" to 100),
            "extract" to mapOf("query" to "milk"),
        )) {
            val result = registry.execute(tool, args)
            assertTrue("$tool must fail, not throw", result is ToolResult.Failure)
            result as ToolResult.Failure
            assertTrue("$tool: ${result.error}", result.error.contains("timeout"))
            assertTrue("$tool must be retryable", result.retryable)
        }
    }

    @Test
    fun clickNoMatchIsRetryableError() {
        val registry = registryWith(noMatchSelectors = setOf("button.gone"))
        assertTrue(registry.execute("open_url", mapOf("url" to "https://example.com")) is ToolResult.Success)
        val result = registry.execute("click", mapOf("selector" to "button.gone"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertTrue(result.error.contains("no-match"))
        assertTrue(result.retryable)
    }

    @Test
    fun extractWithNoPageLoadedFails() {
        val registry = registryWith(pages = emptyMap())
        val result = registry.execute("extract", mapOf("query" to "milk"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).retryable)
    }

    @Test
    fun missingBridgeFailsInsteadOfHanging() {
        val registry = registryWith(useFake = false)
        val calls =
            listOf(
                "open_url" to mapOf("url" to "https://example.com"),
                "get_page_text" to mapOf("url" to "https://example.com"),
                "click" to mapOf("selector" to "a"),
                "scroll" to mapOf("px" to 100),
                "extract" to mapOf("query" to "milk"),
            )
        for ((tool, args) in calls) {
            val result = registry.execute(tool, args)
            assertTrue("$tool without bridge must fail", result is ToolResult.Failure)
            result as ToolResult.Failure
            assertTrue("$tool: ${result.error}", result.error.contains("browser", ignoreCase = true))
        }
    }
}
