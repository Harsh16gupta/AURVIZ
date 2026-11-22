package com.aurviz.agent

import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

// Ticket 08/24: headless background web path (ADR-0002, gaps §3G).
// Scheduled runs fire with no WebView attached (webBridge == null), so
// get_page_text must not depend on the foreground tab. This file is the
// background seam: a BackgroundPageLoader returns raw HTML over plain
// HTTP, and parsing reuses the foreground allowlist (isAllowedWebUrl)
// with Jsoup instead of the WebView's rendered DOM.
//
// The foreground bridge behavior in Web.kt / WebViewBridge.kt is
// untouched: when a WebBridge is attached it still wins. Only
// get_page_text falls back to this loader; open_url/click/scroll/
// extract stay foreground-only (they need the live tab).

// One headless fetch: raw HTML for url, or a WebException (timeout /
// load-failed) the registry maps to a retryable Failure. Blocked URLs
// never reach here — openBackgroundPage rejects them first.
fun interface BackgroundPageLoader {
    fun loadHtml(url: String): String
}

// OkHttp loader for scheduled runs. Timeouts mirror the app's HTTP
// posture (gaps §3H): connect 15s, read 60s, so a hung page becomes a
// "timeout" result, never a stuck Worker.
fun defaultBackgroundHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()

class OkHttpBackgroundPageLoader(
    private val client: OkHttpClient = defaultBackgroundHttpClient(),
) : BackgroundPageLoader {
    override fun loadHtml(url: String): String {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", "AURVIZ-background")
                .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw WebException(WebFailure(url, "load-failed"))
                return response.body?.string()
                    ?: throw WebException(WebFailure(url, "load-failed"))
            }
        } catch (e: WebException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw WebException(WebFailure(url, "timeout"))
        } catch (e: java.io.InterruptedIOException) {
            throw WebException(WebFailure(url, "timeout"))
        } catch (e: java.io.IOException) {
            throw WebException(WebFailure(url, "load-failed"))
        }
    }
}

// Jsoup parse of headless HTML into the shared WebPage shape. Same
// contract as parsePage (title + readable text + links) so the
// get_page_text schema and result type are reused verbatim; only the
// parser differs (static HTML, no rendered DOM — JS-heavy pages may
// read thinner than the foreground tab).
fun parseBackgroundPage(url: String, html: String): WebPage {
    val doc = Jsoup.parse(html, url)
    val title = doc.title().orEmpty().trim()
    val links = doc.select("a[href]").map { it.attr("href") }
    val text =
        (doc.body()?.wholeText() ?: "")
            .split("\n")
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    return WebPage(url, title, text, links)
}

// Headless open: same allowlist verdicts as the foreground openPage —
// non-http(s) fails with reason "blocked" (never retryable). Loader
// failures pass through with their url so Retry knows what to retry.
fun openBackgroundPage(loader: BackgroundPageLoader, url: String): WebPage {
    if (!isAllowedWebUrl(url)) throw WebException(WebFailure(url, "blocked"))
    val html =
        try {
            loader.loadHtml(url)
        } catch (e: WebException) {
            throw e
        } catch (e: Exception) {
            throw WebException(WebFailure(url, "load-failed"))
        }
    return parseBackgroundPage(url, html)
}
