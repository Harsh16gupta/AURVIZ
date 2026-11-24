package com.aurviz.agent

import org.json.JSONArray
import org.json.JSONObject

// Ticket 74 (part of #71): Web Tool adapter. Owns the five Web Tool
// declarations — foreground open, page text with headless background
// fallback for scheduled runs, foreground-only click/scroll/extract —
// and explicitly registers them into the small validate-and-gate kernel
// (ToolRegistry). No behavior change: all five stay LOW risk ungated,
// a null bridge still returns a retryable Failure (never a hang), the
// bridge still wins over the headless loader when attached, and blocked
// URLs stay non-retryable.
// Ticket #106 (T1): deterministic Tool payload budget guard. A single
// AgentLoop Task Execution serializes every Tool result into the LLM
// context, so unbounded page text or link lists overflow the window.
// Text truncates at 12,000 chars and links cap at 40 entries.
const val MAX_TOOL_TEXT_CHARS = 12_000
const val MAX_TOOL_LINKS = 40
const val TRUNCATED_SUFFIX = "\n...[truncated for context budget]"

fun truncateToolText(text: String, maxChars: Int = MAX_TOOL_TEXT_CHARS): String =
    if (text.length <= maxChars) text else text.take(maxChars) + TRUNCATED_SUFFIX

fun capToolLinks(links: List<String>, maxLinks: Int = MAX_TOOL_LINKS): List<String> =
    links.take(maxLinks)

fun openUrlTool(webBridge: WebBridge?): ToolDefinition =
    ToolDefinition(
        name = "open_url",
        description = "Open a page in the foreground browser tab. Returns its title, readable text, and links.",
        parameters = webParams("url", "The http(s) page URL to open, e.g. https://example.com"),
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "page",
        execute = { args ->
            val bridge = webBridge ?: return@ToolDefinition noWebBridge()
            val url = args["url"] as String
            try {
                val page = openPage(bridge, url)
                ToolResult.Success(
                    mapOf(
                        "url" to page.url,
                        "title" to page.title,
                        "text" to truncateToolText(page.text),
                        "links" to capToolLinks(page.links),
                        "source" to pageSource(page.url),
                    ),
                )
            } catch (e: WebException) {
                webFailure("open_url", e)
            }
        },
    )

fun getPageTextTool(webBridge: WebBridge?, backgroundWebLoader: BackgroundPageLoader? = null): ToolDefinition =
    ToolDefinition(
        name = "get_page_text",
        description = "Return the readable text of an http(s) page, without links.",
        parameters = webParams("url", "The http(s) page URL to read, e.g. https://example.com"),
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "page_text",
        execute = { args ->
            val url = args["url"] as String
            try {
                // Foreground tab wins when attached; headless loader
                // serves scheduled runs (webBridge == null, ADR-0002).
                val page =
                    if (webBridge != null) {
                        openPage(webBridge, url)
                    } else {
                        val loader = backgroundWebLoader ?: return@ToolDefinition noWebBridge()
                        openBackgroundPage(loader, url)
                    }
                ToolResult.Success(
                    mapOf(
                        "url" to page.url,
                        "title" to page.title,
                        "text" to truncateToolText(page.text),
                        "source" to pageSource(page.url),
                    ),
                )
            } catch (e: WebException) {
                webFailure("get_page_text", e)
            }
        },
    )

fun clickTool(webBridge: WebBridge?): ToolDefinition =
    ToolDefinition(
        name = "click",
        description = "Click a CSS selector in the current foreground page. Returns the page after the click.",
        parameters = webParams("selector", "CSS selector to click, e.g. a.buy"),
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "page",
        execute = { args ->
            val bridge = webBridge ?: return@ToolDefinition noWebBridge()
            val selector = args["selector"] as String
            try {
                val page = parsePage("", bridge.click(selector))
                ToolResult.Success(
                    mapOf(
                        "title" to page.title,
                        "text" to truncateToolText(page.text),
                        "links" to capToolLinks(page.links),
                        "source" to pageSource(bridge.currentUrl()),
                    ),
                )
            } catch (e: WebException) {
                webFailure("click", e)
            }
        },
    )

fun scrollTool(webBridge: WebBridge?): ToolDefinition =
    ToolDefinition(
        name = "scroll",
        description = "Scroll the current foreground page vertically. Returns the page after scrolling.",
        parameters =
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "px",
                        JSONObject().put("type", "integer")
                            .put("description", "Pixels to scroll; negative scrolls up."),
                    ),
                )
                .put("required", JSONArray().put("px")),
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "page",
        execute = { args ->
            val bridge = webBridge ?: return@ToolDefinition noWebBridge()
            val px = (args["px"] as Number).toInt()
            try {
                val page = parsePage("", bridge.scrollBy(px))
                ToolResult.Success(
                    mapOf(
                        "title" to page.title,
                        "text" to truncateToolText(page.text),
                        "links" to capToolLinks(page.links),
                        "source" to pageSource(bridge.currentUrl()),
                    ),
                )
            } catch (e: WebException) {
                webFailure("scroll", e)
            }
        },
    )

fun extractTool(webBridge: WebBridge?): ToolDefinition =
    ToolDefinition(
        name = "extract",
        description = "Return the chunks of the current foreground page most relevant to the query.",
        parameters = webParams("query", "What to look for on the current page, e.g. bread price"),
        riskLevel = RiskLevel.LOW,
        requiresConfirmation = false,
        resultType = "extract",
        execute = { args ->
            val bridge = webBridge ?: return@ToolDefinition noWebBridge()
            val query = args["query"] as String
            try {
                val text = bridge.pageText()
                val chunks = relevantChunks(WebPage(url = "", title = "", text = text, links = emptyList()), query)
                ToolResult.Success(
                    mapOf(
                        "query" to query,
                        "chunks" to chunks,
                        "source" to pageSource(bridge.currentUrl()),
                    ),
                )
            } catch (e: WebException) {
                webFailure("extract", e)
            }
        },
    )

/** The Web adapter: all five Web Tool declarations in registration order
 *  (open, page text, click, scroll, extract). The default registry
 *  builder registers every entry into the kernel. */
fun webTools(webBridge: WebBridge?, backgroundWebLoader: BackgroundPageLoader? = null): List<ToolDefinition> =
    listOf(
        openUrlTool(webBridge),
        getPageTextTool(webBridge, backgroundWebLoader),
        clickTool(webBridge),
        scrollTool(webBridge),
        extractTool(webBridge),
    )

private fun webFailure(tool: String, e: WebException): ToolResult.Failure {
    val where = if (e.failure.url.isNotBlank()) ": ${e.failure.url}" else ""
    // "blocked" never clears on retry (same URL, same verdict), so say
    // what is allowed; every bridge failure (timeout, load-failed,
    // no-match, no-page) can clear and stays retryable.
    if (e.failure.reason == "blocked") {
        return ToolResult.Failure("$tool blocked: only http(s) URLs are allowed$where", retryable = false)
    }
    return ToolResult.Failure("$tool failed (${e.failure.reason})$where", retryable = true)
}

private fun noWebBridge(): ToolResult.Failure =
    ToolResult.Failure(
        "browser is not ready yet — open the Browser tab and try again",
        retryable = true,
    )

private fun webParams(name: String, description: String): JSONObject =
    JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(name, JSONObject().put("type", "string").put("description", description)),
        )
        .put("required", JSONArray().put(name))
