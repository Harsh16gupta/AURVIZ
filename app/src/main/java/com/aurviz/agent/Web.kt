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

// A failed open/extract, carrying the page so the screen can offer Retry.
data class WebFailure(val url: String, val reason: String)

class WebException(val failure: WebFailure) : Exception("${failure.reason}: ${failure.url}")

// The plug shape. Real WebView implements this with a JS bridge.
interface WebBridge {
    fun load(url: String): String
}

// Open a page: title + readable text + links out of raw HTML.
// Failures come back typed (url included) so Retry knows what to retry.
fun openPage(bridge: WebBridge, url: String): WebPage {
    val html =
        try {
            bridge.load(url)
        } catch (e: WebException) {
            throw e
        } catch (e: Exception) {
            throw WebException(WebFailure(url, "load-failed"))
        }
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
