package com.aurviz.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Ticket 74 (part of #71): Web adapter pin. The adapter owns the five Web
// Tool declarations and registers them into the validate-and-gate kernel;
// behavior is exercised through the kernel seam (execute, schema view),
// never by file location. Fake bridge + fake background loader only.
class WebAdapterTest {

    private val fixture =
        """
        <html><head><title>Example Shop</title></head><body>
        <h1>Milk price: 60</h1>
        <p>Bread price: 40. Fresh bread daily.</p>
        <a href="https://example.com/milk">milk</a>
        <a href="https://example.com/bread">bread</a>
        </body></html>
        """.trimIndent()

    private fun kernelWithWeb(
        webBridge: WebBridge? = FakeWebBridge(mapOf("https://example.com" to fixture)),
        backgroundWebLoader: BackgroundPageLoader? = null,
    ): ToolRegistry =
        ToolRegistry().apply {
            for (tool in webTools(webBridge, backgroundWebLoader)) register(tool)
        }

    @Test
    fun adapterRegistersAllFiveWebToolsIntoTheKernel() {
        val kernel = kernelWithWeb()
        assertEquals(
            setOf("open_url", "get_page_text", "click", "scroll", "extract"),
            kernel.names(),
        )
    }

    @Test
    fun allFiveWebToolsStayUngatedLowRisk() {
        val kernel = kernelWithWeb()
        for (name in listOf("open_url", "get_page_text", "click", "scroll", "extract")) {
            val tool = kernel.find(name)!!
            assertEquals("$name risk", RiskLevel.LOW, tool.riskLevel)
            assertFalse("$name must not need Confirmation", tool.requiresConfirmation)
        }
    }

    @Test
    fun openAndPageTextBehaveThroughTheKernelSeam() {
        val kernel = kernelWithWeb()
        val opened = kernel.execute("open_url", mapOf("url" to "https://example.com"))
        assertTrue(opened is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val data = (opened as ToolResult.Success).data as Map<String, Any?>
        assertEquals("Example Shop", data["title"])
        assertTrue((data["text"] as String).contains("Milk price: 60"))

        val text = kernel.execute("get_page_text", mapOf("url" to "https://example.com"))
        assertTrue(text is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val textData = (text as ToolResult.Success).data as Map<String, Any?>
        assertTrue((textData["text"] as String).contains("Bread price: 40"))
    }

    @Test
    fun clickScrollExtractBehaveThroughTheKernelSeam() {
        val kernel = kernelWithWeb()
        assertTrue(kernel.execute("open_url", mapOf("url" to "https://example.com")) is ToolResult.Success)
        assertTrue(kernel.execute("click", mapOf("selector" to "a")) is ToolResult.Success)
        assertTrue(kernel.execute("scroll", mapOf("px" to 250)) is ToolResult.Success)
        val extracted = kernel.execute("extract", mapOf("query" to "bread price"))
        assertTrue(extracted is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val data = (extracted as ToolResult.Success).data as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val chunks = data["chunks"] as List<String>
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].contains("Bread price"))
    }

    @Test
    fun pageTextFallsBackToBackgroundLoaderForScheduledRuns() {
        val kernel =
            kernelWithWeb(
                webBridge = null,
                backgroundWebLoader = FakeBackgroundPageLoader(mapOf("https://example.com" to fixture)),
            )
        val result = kernel.execute("get_page_text", mapOf("url" to "https://example.com"))
        assertTrue(result is ToolResult.Success)
        @Suppress("UNCHECKED_CAST")
        val data = (result as ToolResult.Success).data as Map<String, Any?>
        assertEquals("Example Shop", data["title"])
    }

    @Test
    fun foregroundOnlyToolsFailRetryablyWithNoBridge() {
        val kernel =
            kernelWithWeb(
                webBridge = null,
                backgroundWebLoader = FakeBackgroundPageLoader(mapOf("https://example.com" to fixture)),
            )
        // get_page_text is served by the loader; the other four need the tab.
        for ((tool, args) in listOf(
            "open_url" to mapOf("url" to "https://example.com"),
            "click" to mapOf("selector" to "a"),
            "scroll" to mapOf("px" to 100),
            "extract" to mapOf("query" to "milk"),
        )) {
            val result = kernel.execute(tool, args)
            assertTrue("$tool must fail without a bridge", result is ToolResult.Failure)
            result as ToolResult.Failure
            assertTrue("$tool: ${result.error}", result.error.contains("browser", ignoreCase = true))
            assertTrue("$tool must be retryable", result.retryable)
        }
    }

    @Test
    fun missingBridgeAndLoaderStillFailsRetryably() {
        val kernel = kernelWithWeb(webBridge = null, backgroundWebLoader = null)
        val result = kernel.execute("get_page_text", mapOf("url" to "https://example.com"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).retryable)
    }

    @Test
    fun blockedUrlsStayNonRetryable() {
        val kernel = kernelWithWeb()
        val result = kernel.execute("open_url", mapOf("url" to "file:///etc/passwd"))
        assertTrue(result is ToolResult.Failure)
        result as ToolResult.Failure
        assertFalse(result.retryable)
    }

    @Test
    fun schemaViewAdvertisesAllFiveTools() {
        val names =
            kernelWithWeb().toolSchemas().let { schemas ->
                (0 until schemas.length())
                    .map { schemas.getJSONObject(it).getJSONObject("function").getString("name") }
                    .toSet()
            }
        assertEquals(setOf("open_url", "get_page_text", "click", "scroll", "extract"), names)
    }
}
