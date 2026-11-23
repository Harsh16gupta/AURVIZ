package com.aurviz.agent

// Session 2 Web: open + extract. Pure decision logic, JVM-testable.
// The real WebView plugs in through WebBridge with local fixtures here.

// One opened page: where, what it says, where it links.
data class WebPage(
    val url: String,
    val title: String,
    val text: String,
    val links: List<String>,
)

/** Page origin tag for every web Tool result, mirroring emailSource. */
fun pageSource(url: String): String = if (url.isBlank()) "page:current" else "page:$url"

// A failed open/extract, carrying the page so the screen can offer Retry.
data class WebFailure(val url: String, val reason: String)

class WebException(val failure: WebFailure) : Exception("${failure.reason}: ${failure.url}")

// The plug shape. Real WebView implements this with a JS bridge. The bridge
// is stateful like a foreground tab: load() sets the current page, and
// pageText()/click()/scrollBy() act on it. Every call either returns or
// throws WebException — the real bridge resolves its latch with a timeout
// so a hung page becomes a "timeout" result, never a stuck caller.
interface WebBridge {
    fun load(url: String): String

    fun pageText(): String

    fun click(selector: String): String

    fun scrollBy(px: Int): String

    // The foreground tab's current URL for result source tags (ticket
    // 16/29: page text travels inside source-tagged results). Blank when
    // nothing has loaded yet; the registry falls back to page:current.
    fun currentUrl(): String = ""
}

// Only http(s) pages may load. Everything else — file:, javascript:, data:,
// ftp:, about:, bare hosts with no scheme — is blocked before touching the
// WebView (ticket 07/24). Scheme match is case-insensitive; a missing or
// malformed scheme never defaults to allowed.
fun isAllowedWebUrl(raw: String): Boolean {
    val url = raw.trim()
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return false
    return when (url.substring(0, schemeEnd).lowercase()) {
        "http", "https" -> true
        else -> false
    }
}

// Ticket 41/NG-5: Browser tab routing. The address bar is the second caller
// of isAllowedWebUrl — not a second policy — so UI verdicts match the Tool
// layer (openPage/openBackgroundPage). Web addresses pass through unchanged;
// anything else refuses with an explanation the Browser tab can show.
sealed interface BrowserRoute {
    data class Load(val url: String) : BrowserRoute

    data class Refuse(val reason: String) : BrowserRoute
}

fun routeBrowserUrl(raw: String): BrowserRoute {
    val url = raw.trim()
    if (url.isBlank()) {
        return BrowserRoute.Refuse("Enter a web address starting with http:// or https://.")
    }
    if (!isAllowedWebUrl(url)) {
        return BrowserRoute.Refuse("Only http:// and https:// addresses open here — \"$url\" was not loaded.")
    }
    return BrowserRoute.Load(url)
}

// Open a page: title + readable text + links out of raw HTML.
// Failures come back typed (url included) so Retry knows what to retry.
// Disallowed URLs fail with reason "blocked" (not retryable by the model).
fun openPage(bridge: WebBridge, url: String): WebPage {
    if (!isAllowedWebUrl(url)) throw WebException(WebFailure(url, "blocked"))
    val html =
        try {
            bridge.load(url)
        } catch (e: WebException) {
            throw e
        } catch (e: Exception) {
            throw WebException(WebFailure(url, "load-failed"))
        }
    return parsePage(url, html)
}

// Pure HTML parsing shared by open/click/scroll results (JVM-testable).
fun parsePage(url: String, html: String): WebPage {
    val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty().trim()
    val links = Regex("href=\"(.*?)\"").findAll(html).map { it.groupValues[1] }.toList()
    val text =
        html.replace(Regex("<[^>]*>"), "\n")
            .split("\n")
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    return WebPage(url, title, text, links)
}

// Keep the chunks that share words with the question, best first.
fun relevantChunks(page: WebPage, query: String, max: Int = 3): List<String> {
    val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
    if (words.isEmpty()) return emptyList()
    return page.text.split("\n")
        .map { chunk ->
            val hit = chunk.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
            chunk to hit.intersect(words).size
        }
        .filter { (_, score) -> score > 0 }
        .sortedByDescending { (_, score) -> score }
        .take(max)
        .map { (chunk, _) -> chunk }
}

// The JS the real WebView runs. The question is escaped first so a
// quote in it can never break out of the script string.
fun buildExtractJs(query: String): String {
    val safe = query.replace("\\", "\\\\").replace("\"", "\\\"")
    return "(function(){var q=\"$safe\";return document.body.innerText;})()"
}

// Click the first match, reporting whether anything matched so a typo'd
// selector comes back as a "no-match" error instead of silent success.
// The selector is escaped like the extract query above.
fun buildClickJs(selector: String): String {
    val safe = selector.replace("\\", "\\\\").replace("\"", "\\\"")
    return "(function(){var el=document.querySelector(\"$safe\");if(!el)return \"no-match\";el.click();return \"clicked\";})()"
}

// Scroll the current page vertically by px pixels (negative scrolls up).
fun buildScrollJs(px: Int): String = "(function(){window.scrollBy(0,$px);return window.scrollY;})()"

// Read the current page's outer HTML back out of the WebView.
fun buildOuterHtmlJs(): String = "(function(){return document.documentElement.outerHTML;})()"

// Read the current page's rendered text back out of the WebView.
fun buildPageTextJs(): String = "(function(){return document.body.innerText;})()"
